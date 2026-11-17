package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.logger.warningL
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Template system manager.
 *
 * Template files are stored in the templates/ directory and can be referenced from any YAML file with the $template directive.
 *
 * Template syntax:
 *   %{param}              - Parameter reference
 *   %{param?:default}     - Parameter reference with a default value
 *   \{  \}                - Escaped braces (treated as plain text)
 *   \?:                   - Escaped ?: (treated as plain text)
 *
 * Default parameters:
 *   _ID                   - The current entry's root node key, passed automatically
 *
 * Parameter transformations ($t):
 *   Use the $t directive in $template.arguments to transform parameter values:
 *   - type: when          - Map values
 *   - type: upper/lower   - Change letter case
 *   - type: condition     - Select a value based on a condition
 *   - type: self_increase - Incrementing counter
 *   - type: kether/...    - Script expression
 *
 * Type conversion ($c):
 *   Use the $c directive in a parameter value to convert its final type:
 *   - type: int/double/string
 *   - round: cell/round/floor
 *
 * Special parameters:
 *   _ORIGIN               - Reference the parameter's original value in a $t/$c configuration
 *
 * Merge rules:
 *   - For duplicate keys, the later (lower) value overrides the earlier one.
 *   - List entries are merged into a single list.
 */
object TemplateManager {

    // ==================== Custom handler interfaces ====================

    /**
     * Custom handler for a $t transformer.
     * Register it with [registerTransformer], then use it in YAML as `$t: { type: your_type, ... }`.
     */
    fun interface TransformerProvider {
        /**
         * @param type The type name in use, converted to lowercase.
         * @param config The complete $t configuration map.
         * @param resolveCtx The current parameter context, including _ID and _ORIGIN.
         * @return The transformed string value.
         */
        fun process(type: String, config: Map<String, Any?>, resolveCtx: Map<String, String>): String
    }

    /**
     * Custom handler for a $c converter.
     * Register it with [registerConverter], then use it in YAML as `$c: { type: your_type, ... }`.
     */
    fun interface ConverterProvider {
        /**
         * @param type The type name in use, converted to lowercase.
         * @param config The complete $c configuration map.
         * @param value The string value to convert, after any $t transformation.
         * @param resolveCtx The current parameter context.
         * @return The converted string value.
         */
        fun process(type: String, config: Map<String, Any?>, value: String, resolveCtx: Map<String, String>): String
    }

    /**
     * Custom handler for a parameter-level directive (at the same level as $t/$c).
     * Register it with [registerDirective], then use it in arguments as `$your_name: { ... }`.
     */
    fun interface DirectiveProvider {
        /**
         * @param name The directive name (for example, "$my_macro").
         * @param config The configuration map for this directive.
         * @param key The key of the parameter currently being processed.
         * @param currentArgs The current static parameter map (mutable).
         * @param dynamicArgs The dynamic value provider map (mutable).
         * @param resolveCtx The current parameter context.
         */
        fun process(
            name: String,
            config: Map<String, Any?>,
            key: String,
            currentArgs: MutableMap<String, String>,
            dynamicArgs: MutableMap<String, () -> String>,
            resolveCtx: Map<String, String>
        )
    }

    private val templates = ConcurrentHashMap<String, Map<String, Any?>>()
    private val customTransformerProviders = ConcurrentHashMap<String, TransformerProvider>()
    private val customConverterProviders = ConcurrentHashMap<String, ConverterProvider>()
    private val customDirectiveProviders = ConcurrentHashMap<String, DirectiveProvider>()

    fun templateCount(): Int = templates.size

    fun clearTemplates() {
        templates.clear()
    }

    /** Returns a loaded template without triggering parsing. */
    fun getTemplate(name: String): Map<String, Any?>? = templates[name]

    /** Returns the names of all loaded templates. */
    fun getTemplateNames(): Set<String> = templates.keys.toSet()

    /** Returns an immutable snapshot of all loaded templates. */
    fun getAllTemplates(): Map<String, Map<String, Any?>> = templates.toMap()

    /** Manually resolves a template: deep copy, parameter substitution, then recursive resolution. */
    fun resolveTemplate(name: String, args: Map<String, String>): Map<String, Any?>? {
        val template = templates[name] ?: return null
        val resolved = resolveValue(deepClone(template), args)
        @Suppress("UNCHECKED_CAST")
        return resolved as? Map<String, Any?>
    }

    // ---- Custom handler registration ----

    /** Registers a custom $t transformer type. */
    fun registerTransformer(type: String, provider: TransformerProvider) {
        customTransformerProviders[type.lowercase()] = provider
    }

    /** Unregisters a custom $t transformer type. */
    fun unregisterTransformer(type: String) {
        customTransformerProviders.remove(type.lowercase())
    }

    /** Registers a custom $c converter type. */
    fun registerConverter(type: String, provider: ConverterProvider) {
        customConverterProviders[type.lowercase()] = provider
    }

    /** Unregisters a custom $c converter type. */
    fun unregisterConverter(type: String) {
        customConverterProviders.remove(type.lowercase())
    }

    /** Registers a custom parameter-level directive (at the same level as $t/$c). */
    fun registerDirective(name: String, provider: DirectiveProvider) {
        customDirectiveProviders[name] = provider
    }

    /** Unregisters a custom parameter-level directive. */
    fun unregisterDirective(name: String) {
        customDirectiveProviders.remove(name)
    }

    fun loadTemplates(context: LoadContext? = null) {
        val templateDir = File(getDataFolder(), "templates")
        if (!templateDir.exists()) {
            templateDir.mkdirs()
            releaseResourceFolder("templates")
        }

        // In context mode, resolve into a local candidate and publish it all at once with publishTemplates when building completes.
        // Without a context (the compatibility entry point), write directly to the live table.
        val sink: MutableMap<String, Map<String, Any?>> = context?.templates ?: templates.also { it.clear() }

        var count = 0
        templateDir.walk().filter { it.isFile }.forEach { file ->
            val data = multiExtensionLoader(file) ?: return@forEach
            data.forEach { (key, value) ->
                if (value is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    sink[key] = value as Map<String, Any?>
                    count++
                }
            }
        }
    }

    /**
     * Replaces the live template table with the candidate in one operation (called once after a successful reload build).
     * Does nothing when there is no candidate, leaving the live table unchanged for non-context paths.
     */
    fun publishTemplates(candidate: Map<String, Map<String, Any?>>?) {
        if (candidate == null) return
        templates.clear()
        templates.putAll(candidate)
    }

    /**
     * Resolves $template directives throughout a data map.
     * Each top-level entry automatically receives the _ID parameter, set to that entry's key.
     *
     * @param context The candidate build context for the current reload. When non-null, template lookup checks
     * the current candidate table first (newly loaded templates are immediately visible to later loaders), then the live table.
     */
    fun resolveInMap(data: Map<String, Any?>, context: LoadContext? = null): Map<String, Any?> {
        return data.mapValues { (key, value) ->
            val ctx = mapOf("_ID" to key)
            resolveValue(value, ctx, context)
        }
    }

    // ==================== Recursive resolution ====================

    @Suppress("UNCHECKED_CAST")
    private fun resolveValue(value: Any?, context: Map<String, String>, loadCtx: LoadContext? = null): Any? {
        return when (value) {
            is Map<*, *> -> {
                val map = value as Map<String, Any?>
                if (map.containsKey("\$template")) {
                    resolveTemplateDirective(map, context, loadCtx)
                } else {
                    map.mapValues { (_, v) -> resolveValue(v, context, loadCtx) }
                }
            }
            is List<*> -> value.map { resolveValue(it, context, loadCtx) }
            else -> value
        }
    }

    /**
     * Processes a $template directive node.
     *
     * Node structure:
     * ```yaml
     * $template:
     *   use: template_name
     *   arguments:
     *     key: value
     *     key_with_t:
     *       $t:
     *         type: when/upper/condition/...
     *         ...
     *       $c:
     *         type: int/double/string
     * other_key: other_value
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveTemplateDirective(
        node: Map<String, Any?>,
        context: Map<String, String>,
        loadCtx: LoadContext? = null
    ): Map<String, Any?> {
        val directive = node["\$template"]
        if (directive !is Map<*, *>) {
            return node.mapValues { (_, v) -> resolveValue(v, context, loadCtx) } as Map<String, Any?>
        }

        val templateName = directive["use"] as? String
        if (templateName == null) {
            warningL("Config_Load_Error_Parse", "\$template", "missing 'use' field")
            return node.mapValues { (_, v) -> resolveValue(v, context, loadCtx) } as Map<String, Any?>
        }

        // Extract and process parameters (supports $t/$c directives).
        val rawArgs = directive["arguments"] as? Map<*, *>
        val (processedArgs, dynamicValues) = processArguments(rawArgs, context)

        // During candidate building, check this reload's candidate templates first, then fall back to the live table.
        val template = (loadCtx?.templates?.get(templateName) ?: templates[templateName])
        if (template == null) {
            warningL("Config_Load_Error_Parse", "\$template", "template '$templateName' not found")
            return node.mapValues { (_, v) -> resolveValue(v, context, loadCtx) } as Map<String, Any?>
        }

        // Deep-copy the template content and substitute parameters, including dynamic values.
        val substituted = substituteParams(deepClone(template), processedArgs, dynamicValues)

        // Resolve recursively to process nested $template directives.
        val fullyResolved = resolveValue(substituted, processedArgs, loadCtx)

        // Merge the node's other fields, excluding $template.
        val otherKeys = node.filterKeys { it != "\$template" }
        val resolvedOtherKeys = resolveValue(otherKeys, context, loadCtx)

        return mergeMaps(fullyResolved as Map<String, Any?>, resolvedOtherKeys as Map<String, Any?>)
    }

    // ==================== Parameter processing ($t / $c) ====================

    /**
     * Processes parameter values in $template.arguments.
     * Supports plain string values and complex values with a $t transformer and/or a $c converter.
     *
     * Two-pass processing:
     * - First pass: compute each parameter's pre-substitution text in declaration order, while allowing later $t _ORIGIN / %{} references
     *   to see previously declared parameters (preserving the old behavior). **Pass an empty dynamicValues map during this pass.**
     * - Second pass: after collecting every parameter, substitute all %{...} placeholders using the complete static and dynamic parameter maps.
     *   Placeholders can then reference parameters **declared later** as well as dynamic values produced by $t
     *   (for example, `name: "Token #%{number}"` together with `number: {$t: {type: self_increase}}`).
     *
     * Dynamic values are consumed only during the second pass. If dynamicValues is supplied in both passes, the same %{number}
     * would consume two values and cause the incrementing counter to skip numbers.
     *
     * @param rawArgs The original arguments map.
     * @param baseContext The base context (such as _ID).
     * @return A pair containing the static parameter map and dynamic value provider map.
     */
    private fun processArguments(
        rawArgs: Map<*, *>?,
        baseContext: Map<String, String>
    ): Pair<LinkedHashMap<String, String>, LinkedHashMap<String, () -> String>> {
        val staticArgs = LinkedHashMap(baseContext)
        val dynamicArgs = LinkedHashMap<String, () -> String>()

        if (rawArgs == null) return Pair(staticArgs, dynamicArgs)

        // Pre-substitution text for the second pass.
        val rawValues = LinkedHashMap<String, String>()

        for ((rawKey, rawValue) in rawArgs) {
            val key = rawKey as? String ?: continue

            when (rawValue) {
                is String -> {
                    if (!staticArgs.containsKey(key)) {
                        rawValues[key] = rawValue
                        staticArgs[key] = substituteParamsInString(rawValue, staticArgs, emptyMap())
                    }
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = rawValue as Map<String, Any?>
                    val hasT = map.containsKey("\$t")
                    val hasC = map.containsKey("\$c")

                    if ((hasT || hasC) && !staticArgs.containsKey(key)) {
                        processArgWithTC(key, map, staticArgs, dynamicArgs, rawValues)
                    } else if (!staticArgs.containsKey(key)) {
                        rawValues[key] = map.toString()
                        staticArgs[key] = substituteParamsInString(map.toString(), staticArgs, emptyMap())
                    }
                }
                else -> {
                    if (!staticArgs.containsKey(key)) {
                        staticArgs[key] = rawValue.toString()
                    }
                }
            }
        }

        // Second pass: substitute again using the complete parameter set (dynamic values are consumed only here).
        for ((key, raw) in rawValues) {
            staticArgs[key] = substituteParamsInString(raw, staticArgs, dynamicArgs)
        }

        return Pair(staticArgs, dynamicArgs)
    }

    /**
     * Processes one parameter value with $t and/or $c.
     *
     * @param rawValues The resolved final text before %{} substitution; [processArguments] performs substitution in its second pass.
     */
    private fun processArgWithTC(
        key: String,
        config: Map<String, Any?>,
        currentArgs: MutableMap<String, String>,
        dynamicArgs: MutableMap<String, () -> String>,
        rawValues: MutableMap<String, String>
    ) {
        // _ORIGIN is the value already present for this parameter in the current context; pass it to the $t/$c configuration for reference.
        val hadOrigin = currentArgs.containsKey(key)
        val origin = currentArgs[key] ?: ""
        val resolveCtx = currentArgs.toMap() + mapOf("_ORIGIN" to origin)

        var value: String? = null
        var dynamic: (() -> String)? = null

        // Process $t transformer
        val tConfig = config["\$t"]
        if (tConfig != null) {
            val result = processTransformer(tConfig, resolveCtx)
            value = result.value
            dynamic = result.dynamicSupplier
        }

        // A source value exists if $t produced one or this parameter has an inherited value.
        // Neither condition holds when only $c is specified without a source value (see the empty-value handling below).
        val hasSource = value != null || hadOrigin

        val rawValue = value ?: origin

        // Process $c converter (with updated _ORIGIN = value after $t)
        val cConfig = config["\$c"]
        if (cConfig != null) {
            val convertCtx = if (value != null) resolveCtx + mapOf("_ORIGIN" to rawValue) else resolveCtx
            value = processConverter(cConfig, rawValue, convertCtx)
        }

        val finalValue = value ?: ""

        // When only $c/$t is specified with no source value and the conversion result is empty, **do not** register this parameter.
        // Otherwise, an empty string would override a template's %{key?:default} value (the old behavior).
        // For example, `damage: {$c: {type: int}}` would make amount empty and cause item construction to fail.
        // Let the template's default handle this case; if $c specifies a fallback, the result is non-empty and is registered normally.
        if (hasSource || finalValue.isNotEmpty()) {
            currentArgs[key] = substituteParamsInString(finalValue, currentArgs, emptyMap())
            rawValues[key] = finalValue
        }

        if (dynamic != null) {
            dynamicArgs[key] = dynamic
        }

        // Process custom argument-level directives ($xxx, same level as $t/$c)
        for ((cfgKey, cfgValue) in config) {
            if (cfgKey.startsWith("\$") && cfgKey != "\$t" && cfgKey != "\$c") {
                val directive = customDirectiveProviders[cfgKey]
                if (directive != null && cfgValue is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    directive.process(cfgKey, cfgValue as Map<String, Any?>, key, currentArgs, dynamicArgs, resolveCtx)
                }
            }
        }
    }

    // ==================== $t transformers ====================

    private data class TransformResult(
        val value: String,
        val dynamicSupplier: (() -> String)? = null
    )

    @Suppress("UNCHECKED_CAST")
    private fun processTransformer(
        tConfig: Any?,
        resolveCtx: Map<String, String>
    ): TransformResult {
        val type: String
        val config: Map<String, Any?>

        when (tConfig) {
            is String -> {
                type = tConfig
                config = emptyMap()
            }
            is Map<*, *> -> {
                val map = tConfig as Map<String, Any?>
                type = map["type"]?.toString() ?: return TransformResult("")
                config = map
            }
            else -> return TransformResult(tConfig?.toString() ?: "")
        }

        return when (type.lowercase()) {
            "when" -> processWhenTransformer(config, resolveCtx)
            "to_upper_case", "uppercase", "upper" -> processCaseTransformer(config, resolveCtx, upper = true)
            "to_lower_case", "lowercase", "lower" -> processCaseTransformer(config, resolveCtx, upper = false)
            "condition" -> processConditionTransformer(config, resolveCtx)
            "self_increase" -> processSelfIncreaseTransformer(config)
            "kether_expr", "kether", "kether_expression" -> processScriptTransformer(config, resolveCtx, "kether")
            "js_expr", "js", "javascript", "javascript_expr", "javascript_expression", "js_expression" ->
                processScriptTransformer(config, resolveCtx, "javascript")
            "fl", "fluxon" -> processScriptTransformer(config, resolveCtx, "fluxon")
            "gjs", "graaljs" -> processScriptTransformer(config, resolveCtx, "graaljs")
            "jexl" -> processScriptTransformer(config, resolveCtx, "jexl")
            else -> {
                val customProvider = customTransformerProviders[type.lowercase()]
                if (customProvider != null) {
                    TransformResult(customProvider.process(type.lowercase(), config, resolveCtx))
                } else {
                    warningL("Config_Load_Error_Parse", "\$t", "unknown transformer type: $type")
                    TransformResult("")
                }
            }
        }
    }

    // ---- when ----

    /**
     * $t: when
     * ```yaml
     * $t:
     *   type: when
     *   source: "%{part}"
     *   when:
     *     helmet: head
     *     chestplate: chest
     *   fallback: any
     * ```
     */
    private fun processWhenTransformer(
        config: Map<String, Any?>,
        resolveCtx: Map<String, String>
    ): TransformResult {
        val source = substituteParamsInString(config["source"]?.toString() ?: "", resolveCtx, emptyMap())
        val whenMap = config["when"]
        val fallback = substituteParamsInString(config["fallback"]?.toString() ?: "", resolveCtx, emptyMap())

        if (whenMap !is Map<*, *>) return TransformResult(fallback)

        val matched = whenMap[source]
        val matchedValue = matched?.toString() ?: fallback
        return TransformResult(substituteParamsInString(matchedValue, resolveCtx, emptyMap()))
    }

    // ---- case ----

    /**
     * $t: upper / lower
     * ```yaml
     * $t:
     *   type: upper
     *   value: "%{tier}"
     *   locale: en
     * ```
     */
    private fun processCaseTransformer(
        config: Map<String, Any?>,
        resolveCtx: Map<String, String>,
        upper: Boolean
    ): TransformResult {
        val value = substituteParamsInString(config["value"]?.toString() ?: "", resolveCtx, emptyMap())
        val locale = config["locale"]?.toString()
        val result = if (locale != null) {
            val l = java.util.Locale(locale)
            if (upper) value.uppercase(l) else value.lowercase(l)
        } else {
            if (upper) value.uppercase() else value.lowercase()
        }
        return TransformResult(result)
    }

    // ---- condition ----

    /**
     * $t: condition
     * ```yaml
     * $t:
     *   type: condition
     *   condition: "%{tier}"
     *   true: oak_leaves
     *   false: birch_leaves
     * ```
     */
    private fun processConditionTransformer(
        config: Map<String, Any?>,
        resolveCtx: Map<String, String>
    ): TransformResult {
        val condition = substituteParamsInString(config["condition"]?.toString() ?: "false", resolveCtx, emptyMap())
        val trueVal = substituteParamsInString(config["true"]?.toString() ?: "", resolveCtx, emptyMap())
        val falseVal = substituteParamsInString(config["false"]?.toString() ?: "", resolveCtx, emptyMap())
        val bool = condition.toBooleanStrictOrNull() ?: (condition == "yes" || condition == "1")
        return TransformResult(if (bool) trueVal else falseVal)
    }

    // ---- self_increase ----

    /**
     * $t: self_increase
     * ```yaml
     * $t:
     *   type: self_increase
     *   from: 0
     *   to: 20
     *   step: 1
     *   step_interval: 1
     * ```
     */
    private fun processSelfIncreaseTransformer(
        config: Map<String, Any?>
    ): TransformResult {
        val from = (config["from"] as? Number)?.toInt() ?: 0
        val to = (config["to"] as? Number)?.toInt() ?: Int.MAX_VALUE
        val step = (config["step"] as? Number)?.toInt() ?: 1
        val stepInterval = (config["step_interval"] as? Number)?.toInt() ?: 1

        // Validate during loading: step_interval <= 0 would cause an ArithmeticException when next() computes the remainder.
        // A zero step or a direction that conflicts with from/to would stall or overflow the counter.
        // Reject and report these configurations here instead of failing at runtime.
        if (stepInterval <= 0) {
            warningL("Config_Load_Error_Parse", "\$t self_increase", "step_interval must be > 0, got $stepInterval")
            return TransformResult(from.toString())
        }
        if (step == 0 && from != to) {
            warningL("Config_Load_Error_Parse", "\$t self_increase", "step must not be 0 unless from == to")
            return TransformResult(from.toString())
        }
        if ((step > 0 && from > to) || (step < 0 && from < to)) {
            warningL("Config_Load_Error_Parse", "\$t self_increase", "step direction does not match from/to range (from=$from, to=$to, step=$step)")
            return TransformResult(from.toString())
        }

        val counter = SelfIncreaseCounter(from, to, step, stepInterval)
        return TransformResult(
            value = from.toString(),
            dynamicSupplier = { counter.next() ?: from.toString() }
        )
    }

    /**
     * Incrementing counter. Each call to next() returns the current value, then advances the counter.
     */
    internal class SelfIncreaseCounter(
        from: Int,
        private val to: Int,
        private val step: Int,
        private val stepInterval: Int
    ) {
        private var current = from.toLong()
        private var callCount = 0
        private var finished = false

        fun next(): String? {
            if (finished) return null
            if (if (step > 0) current > to.toLong() else current < to.toLong()) return null
            val value = current
            callCount++
            if (step == 0) {
                finished = true
            } else if (callCount % stepInterval == 0) {
                current += step.toLong()
            }
            return value.toString()
        }
    }

    // ---- script evaluation ----

    /**
     * $t: kether / js / fluxon / graaljs / jexl
     * ```yaml
     * $t:
     *   type: kether
     *   shell: |-
     *     random 1 to 10
     *   sandbox: true
     *   fallback: 1
     *   nullfallback: 2
     *   unitfallback: 3
     * ```
     */
    private fun processScriptTransformer(
        config: Map<String, Any?>,
        resolveCtx: Map<String, String>,
        scriptType: String
    ): TransformResult {
        val shell = getFlexibleString(config, "shell")
        if (shell.isNullOrBlank()) {
            return TransformResult(config["fallback"]?.toString() ?: "")
        }

        val sandbox = config["sandbox"] as? Boolean ?: true
        val fallback = config["fallback"]?.toString()
        val nullfallback = config["nullfallback"]?.toString()
        val unitfallback = config["unitfallback"]?.toString()

        // Resolve %{...} inside the shell.
        val resolvedShell = substituteParamsInString(shell, resolveCtx, emptyMap())

        return try {
            val result = executeScript(resolvedShell, scriptType)

            when {
                result == null && nullfallback != null -> TransformResult(nullfallback)
                result == Unit && unitfallback != null -> TransformResult(unitfallback)
                result == null && fallback != null -> TransformResult(fallback)
                result != null -> TransformResult(result.toString())
                else -> TransformResult(fallback ?: "")
            }
        } catch (e: Exception) {
            if (sandbox) {
                TransformResult(fallback ?: "")
            } else {
                throw e
            }
        }
    }

    /**
     * Reads a string value from the configuration. Supports a single string or a list (joined as multiline text).
     */
    private fun getFlexibleString(config: Map<String, Any?>, key: String): String? {
        val value = config[key] ?: return null
        return when (value) {
            is String -> value
            is List<*> -> value.joinToString("\n") { it?.toString() ?: "" }
            else -> value.toString()
        }
    }

    /**
     * Executes a script expression of the specified type.
     * Note: Script execution requires a runtime context and may fail silently.
     */
    private fun executeScript(shell: String, scriptType: String): Any? {
        // Script execution requires a Bukkit runtime context (player/sender),
        // which may be unavailable during configuration reloads.
        // Try to execute it here; on failure, return null so the caller can use its fallback.
        return try {
            when (scriptType) {
                "kether" -> evalKether(shell)
                "javascript" -> evalStandardJs(shell)
                "fluxon" -> evalFluxon(shell)
                "graaljs" -> evalGraalJs(shell)
                "jexl" -> evalJexl(shell)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun evalKether(shell: String): Any? {
        // KetherShell.eval requires a sender context, so use the console sender.
        val sender = io.github.zzzyyylllty.sertraline.Sertraline.consoleSender
        return taboolib.module.kether.KetherShell.eval(
            shell,
            taboolib.module.kether.ScriptOptions.builder()
                .sender(sender)
                .build()
        ).get()
    }

    private fun evalStandardJs(shell: String): Any? {
        // Reuse the JSR-223 engine cached for this thread instead of creating a ScriptEngineManager and engine on every call.
        val engine = io.github.zzzyyylllty.sertraline.util.GraalJsUtil.nashornEngineHolder.get() ?: return null
        // Use separate bindings so top-level script vars do not affect later eval calls.
        return engine.eval(shell, engine.createBindings())
    }

    private fun evalFluxon(shell: String): Any? {
        return io.github.zzzyyylllty.sertraline.function.fluxon.FluxonShell.invoke(shell, useCache = true) {
            // Empty variable set.
        }
    }

    private fun evalGraalJs(shell: String): Any? {
        // Try GraalJS (requires the dependency); fall back to standard JS if it fails.
        return try {
            // evalToJsString preserves the JavaScript-style output format of the previous Value.toString implementation.
            io.github.zzzyyylllty.sertraline.util.GraalJsUtil.evalToJsString(shell, emptyMap())
        } catch (e: Exception) {
            evalStandardJs(shell)
        }
    }

    private fun evalJexl(shell: String): Any? {
        return try {
            val compiled = io.github.zzzyyylllty.sertraline.util.JexlUtil.prodJexlCompiler.compileToScript(shell)
            compiled.eval(emptyMap())
        } catch (e: Exception) {
            null
        }
    }

    // ==================== $c type conversion ====================

    /**
     * $c type converter.
     *
     * Full format:
     * ```yaml
     * $c:
     *   type: int
     *   round: floor   # cell / round / floor
     *   fallback: 1
     *   case: uppercase  # Optional: uppercase / lowercase
     * ```
     *
     * Shorthand format:
     * ```yaml
     * $c: double
     * ```
     */
    private fun processConverter(
        cConfig: Any?,
        value: String,
        resolveCtx: Map<String, String>
    ): String {
        val type: String
        val config: Map<String, Any?>

        when (cConfig) {
            is String -> {
                type = cConfig
                config = emptyMap()
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                config = cConfig as Map<String, Any?>
                type = config["type"]?.toString() ?: return value
            }
            else -> return value
        }

        val fallback = config["fallback"]?.toString()?.let {
            substituteParamsInString(it, resolveCtx, emptyMap())
        }

        val converted = try {
            when (type.lowercase()) {
                "int" -> {
                    val round = config["round"]?.toString()?.lowercase() ?: "floor"
                    val doubleVal = value.toDouble()
                    val result = when (round) {
                        "ceil", "cell" -> ceil(doubleVal)
                        "round" -> doubleVal.roundToLong().toDouble()
                        "floor" -> floor(doubleVal)
                        else -> doubleVal
                    }
                    result.toInt().toString()
                }
                "double" -> {
                    value.toDouble().toString()
                }
                "string" -> applyCase(value, config)
                else -> {
                    val customProvider = customConverterProviders[type.lowercase()]
                    if (customProvider != null) {
                        customProvider.process(type.lowercase(), config, value, resolveCtx)
                    } else {
                        applyCase(value, config)
                    }
                }
            }
        } catch (e: NumberFormatException) {
            fallback ?: value
        }

        return applyCase(converted, config)
    }

    private fun applyCase(value: String, config: Map<String, Any?>): String {
        val caseType = config["case"]?.toString()?.lowercase() ?: return value
        val locale = config["locale"]?.toString()
        return if (locale != null) {
            val l = java.util.Locale(locale)
            when (caseType) {
                "uppercase", "upper" -> value.uppercase(l)
                "lowercase", "lower" -> value.lowercase(l)
                else -> value
            }
        } else {
            when (caseType) {
                "uppercase", "upper" -> value.uppercase()
                "lowercase", "lower" -> value.lowercase()
                else -> value
            }
        }
    }

    // ==================== Parameter substitution ====================

    private const val ESCAPE_LEFT = "\u0000L\u0000"
    private const val ESCAPE_RIGHT = "\u0000R\u0000"
    private const val ESCAPE_DEFAULT = "\u0000D\u0000"

    private val PARAM_PATTERN = Regex("%\\{([^}]*)\\}")

    @Suppress("UNCHECKED_CAST")
    private fun substituteParams(
        value: Any?,
        arguments: Map<String, String>,
        dynamicValues: Map<String, () -> String> = emptyMap()
    ): Any? {
        return when (value) {
            is String -> substituteParamsInString(value, arguments, dynamicValues)
            is Map<*, *> -> (value as Map<String, Any?>).mapValues { (_, v) ->
                substituteParams(v, arguments, dynamicValues)
            }
            is List<*> -> value.map { substituteParams(it, arguments, dynamicValues) }
            else -> value
        }
    }

    private fun substituteParamsInString(
        input: String,
        arguments: Map<String, String>,
        dynamicValues: Map<String, () -> String>
    ): String {
        // Fast path.
        if (input.none { it == '%' || it == '\\' }) return input

        // Step 1: Protect escape sequences.
        val result = StringBuilder(input.length + 16)
        var i = 0
        while (i < input.length) {
            when {
                input.startsWith("\\{", i) -> { result.append(ESCAPE_LEFT); i += 2 }
                input.startsWith("\\}", i) -> { result.append(ESCAPE_RIGHT); i += 2 }
                input.startsWith("\\?:", i) -> { result.append(ESCAPE_DEFAULT); i += 3 }
                else -> { result.append(input[i]); i++ }
            }
        }

        // Step 2: Substitute the %{param} pattern (supports dynamic values).
        var processed = PARAM_PATTERN.replace(result.toString()) { match ->
            val inner = match.groupValues[1]
            val separatorIdx = inner.indexOf("?:")
            val paramName = if (separatorIdx >= 0) inner.substring(0, separatorIdx) else inner

            val dynamic = dynamicValues[paramName]
            if (dynamic != null) {
                dynamic()
            } else {
                processParam(inner, arguments)
            }
        }

        // Step 3: Restore escaped characters.
        processed = processed
            .replace(ESCAPE_LEFT, "{")
            .replace(ESCAPE_RIGHT, "}")
            .replace(ESCAPE_DEFAULT, "?:")

        return processed
    }

    /**
     * Processes the content inside %{...}.
     * Supports both %{param} and %{param?:default} formats.
     */
    private fun processParam(inner: String, arguments: Map<String, String>): String {
        val separatorIdx = inner.indexOf("?:")
        val paramName: String
        val defaultValue: String?

        if (separatorIdx >= 0) {
            paramName = inner.substring(0, separatorIdx)
            defaultValue = inner.substring(separatorIdx + 2)
        } else {
            paramName = inner
            defaultValue = null
        }

        val value = arguments[paramName]
        if (value != null) return value
        if (defaultValue != null) return defaultValue
        return "%{$inner}"
    }

    // ==================== Utility functions ====================

    @Suppress("UNCHECKED_CAST")
    private fun deepClone(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> (value as Map<String, Any?>).mapValues { (_, v) -> deepClone(v) }
            is List<*> -> value.map { deepClone(it) }
            else -> value
        }
    }

    private fun mergeMaps(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap(base)
        for ((key, value) in overlay) {
            val baseValue = result[key]
            if (value is List<*> && baseValue is List<*>) {
                @Suppress("UNCHECKED_CAST")
                result[key] = baseValue + value
            } else {
                result[key] = value
            }
        }
        return result
    }
}
