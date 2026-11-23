package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.logger.warningL
import org.bukkit.Bukkit
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import java.io.File
import java.util.Collections
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
 *   $template:
 *     use: template_name        # or the shorthand `$template: template_name`
 *     arguments:
 *       param: value
 *   %{param}              - Parameter reference
 *   %{param?:default}     - Parameter reference with a default value
 *   \{  \}                - Escaped braces (treated as plain text)
 *   \?:                   - Escaped ?: (treated as plain text)
 *
 * **Arguments are a substitution dictionary, not an injection.** A parameter only takes effect where the template
 * body references it as `%{param}` or `%{param?:default}`. A template that hardcodes `material: IRON_SWORD` ignores
 * a `material:` argument entirely (this is reported as an "unused template argument" warning at load time).
 *
 * Default parameters:
 *   _ID                   - The current entry's root node key, passed automatically
 *
 * Parameter transformations ($t):
 *   Use the $t directive in $template.arguments to transform parameter values:
 *   - type: when          - Map values
 *   - type: upper/lower   - Change letter case
 *   - type: condition     - Select a value based on a condition
 *   - type: self_increase - Incrementing counter (one value per expansion)
 *   - type: kether/...    - Script expression
 *
 *   `$t` is evaluated in declaration order, so it can only see arguments declared **before** it (plus `_ID`).
 *
 * Type conversion ($c):
 *   Use the $c directive in a parameter value to convert its final type:
 *   - type: int/double/string
 *   - round: ceil/round/floor     (`cell` is accepted as a legacy alias)
 *   `$c` converts an existing value: it needs a source, which is either the `$t` result in the same value or an
 *   argument inherited from an enclosing template, or an explicit value in the converter. `$c` alone has nothing to convert and is reported as a warning.
 *
 * Special parameters:
 *   _ORIGIN               - Reference the parameter's original value in a $t/$c configuration
 *
 * Merge rules (an entry's own fields over the resolved template):
 *   - Sections (Maps) are merged recursively: overriding `xbuilder.lore` keeps `xbuilder.material`/`name`/`attributes`.
 *   - Lists are appended (`template_list + entry_list`) so extra lore lines follow the template's lines.
 *   - `$list-mode: replace` next to `$template` replaces lists instead of appending them.
 *   - An entry's own fields and nested directives see this invocation's arguments and the enclosing context.
 */
object TemplateManager {

    private fun diagnostic(key: String, vararg args: Any) {
        // The parser is also used by offline validation tools without a Bukkit platform.
        if (Bukkit.getServer() != null) warningL(key, *args)
        else java.util.logging.Logger.getLogger(TemplateManager::class.java.name)
            .warning("$key: " + args.joinToString(", "))
    }

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

    private class TemplateView(val definitions: Map<String, Map<String, Any?>>, val counters: Counters)
    @Volatile
    private var live = TemplateView(emptyMap(), Counters())

    private data class CounterKey(val template: String, val parameter: String, val from: Int, val to: Int, val step: Int, val interval: Int)

    internal class Counters {
        private val entries = ConcurrentHashMap<CounterKey, SelfIncreaseCounter>()

        fun next(template: String, parameter: String, from: Int, to: Int, step: Int, interval: Int): String {
            val key = CounterKey(template, parameter, from, to, step, interval)
            return entries.computeIfAbsent(key) { SelfIncreaseCounter(from, to, step, interval) }.next()
                ?: from.toString()
        }
    }

    private val customTransformerProviders = ConcurrentHashMap<String, TransformerProvider>()
    private val customConverterProviders = ConcurrentHashMap<String, ConverterProvider>()
    private val customDirectiveProviders = ConcurrentHashMap<String, DirectiveProvider>()

    fun templateCount(): Int = live.definitions.size

    internal fun clearProviders() {
        customTransformerProviders.clear()
        customConverterProviders.clear()
        customDirectiveProviders.clear()
    }

    fun clearTemplates() {
        live = TemplateView(emptyMap(), Counters())
    }

    /** Returns a loaded template without triggering parsing. */
    fun getTemplate(name: String): Map<String, Any?>? = live.definitions[name]

    /** Returns the names of all loaded templates. */
    fun getTemplateNames(): Set<String> = live.definitions.keys.toSet()

    /** Returns an immutable snapshot of all loaded templates. */
    fun getAllTemplates(): Map<String, Map<String, Any?>> = live.definitions

    /** Manually resolves a template: deep copy, parameter substitution, then recursive resolution. */
    fun resolveTemplate(name: String, args: Map<String, String>): Map<String, Any?>? {
        val view = live
        val template = view.definitions[name] ?: return null
        val resolved = resolveValue(template, args, view, visiting = setOf(name))
        @Suppress("UNCHECKED_CAST")
        return (resolved as? Map<String, Any?>)?.let { warnUnresolvedPlaceholders(it, name) }
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

    /**
     * Loads every template file under the templates directory.
     *
     * Files are walked recursively in deterministic path order, and only supported config extensions are considered
     * (a stray `README.md` in templates/ is skipped instead of producing a parse error). Duplicate template names
     * across files are reported instead of silently keeping the last one. Unparseable files are reported and the
     * loop keeps going, so one broken template file cannot abort the whole load.
     */
    fun loadTemplates(context: LoadContext? = null): Int {
        val templateDir = File(getDataFolder(), "templates")
        if (!templateDir.exists()) {
            templateDir.mkdirs()
            releaseResourceFolder("templates")
        }
        return loadTemplates(templateDir, context)
    }

    internal fun loadTemplates(templateDir: File, context: LoadContext?): Int {
        val root = templateDir.absoluteFile.normalize()
        // In context mode, resolve into a local candidate and publish it all at once with publishTemplates when building completes.
        // Without a context (the compatibility entry point), build a new snapshot and swap it in at the end.
        val candidate: MutableMap<String, Map<String, Any?>> = context?.templates ?: LinkedHashMap()
        val origins = LinkedHashMap<String, String>()

        var count = 0
        val files = try {
            safeWalkDirectory(root, filter = ::isTemplateFile)
        } catch (e: Exception) {
            throw IllegalStateException("Could not list templates", e)
        }

        for (file in files) {
            val source = file.relativeTo(root).invariantSeparatorsPath
            val data = try {
                multiExtensionLoader(file, context)
            } catch (e: Exception) {
                diagnostic("Config_Load_Error_Parse", file.name, "can not parse template file: ${e.message}")
                null
            }
            if (data == null) continue
            for ((key, value) in data) {
                if (value !is Map<*, *>) {
                    diagnostic("Config_Load_Error_Parse", file.name, "template '$key' is not a section and was skipped")
                    continue
                }
                origins[key]?.let { previous ->
                    diagnostic("Config_Load_Warning_Duplicate_Template", key, previous, source)
                    continue
                }
                origins[key] = source
                @Suppress("UNCHECKED_CAST")
                candidate[key] = value as Map<String, Any?>
                count++
            }
        }

        // Without a context the caller does not publish, so swap the snapshot here (never mutate the live table in place).
        if (context == null) publishTemplates(candidate)
        return count
    }

    /** Template files must parse as one of the supported config formats; other files are ignored. */
    private fun isTemplateFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return !file.name.startsWith(".") && !file.name.startsWith("#") && !file.name.startsWith("!") && extension in setOf("yml", "yaml", "toml", "tml", "json", "conf")
    }

    /**
     * Replaces the live template table with the candidate in one operation (called once after a successful reload build).
     * Does nothing when there is no candidate, leaving the live table unchanged for non-context paths.
     *
     * The snapshot reference is swapped instead of `clear()` + `putAll()` so readers never observe an empty or
     * half-filled template table (a reader that saw one would silently fail to resolve every `$template`).
     */
    fun publishTemplates(candidate: Map<String, Map<String, Any?>>?) {
        if (candidate != null) publish(candidate, Counters())
    }

    private fun publish(candidate: Map<String, Map<String, Any?>>, counters: Counters) {
        val definitions = Collections.unmodifiableMap(candidate.mapValues { (_, template) ->
            @Suppress("UNCHECKED_CAST")
            freeze(template) as Map<String, Any?>
        })
        live = TemplateView(definitions, counters)
    }

    internal fun freeze(value: Any?): Any? = when (value) {
        is Map<*, *> -> Collections.unmodifiableMap(value.mapValues { (_, child) -> freeze(child) })
        is List<*> -> Collections.unmodifiableList(value.map(::freeze))
        else -> value
    }

    internal fun publish(context: LoadContext) = publish(context.templates, context.templateCounters)

    /**
     * Resolves $template directives throughout a data map.
     * Each top-level entry automatically receives the _ID parameter, set to that entry's key.
     *
     * @param context The candidate build context for the current reload. When non-null, template lookup checks
     * the current candidate table, including newly loaded templates.
     */
    fun resolveInMap(data: Map<String, Any?>, context: LoadContext? = null): Map<String, Any?> {
        if (data.values.none(::needsResolution)) return data
        val view = if (context == null) live else TemplateView(context.templates, context.templateCounters)
        return data.mapValues { (key, value) ->
            if (!needsResolution(value)) value else {
                val resolved = resolveValue(value, mapOf("_ID" to key), view)
                warnUnresolvedPlaceholders(resolved, key)
                resolved
            }
        }
    }

    // ==================== Recursive resolution ====================

    private fun needsResolution(value: Any?): Boolean = when (value) {
        is Map<*, *> -> "\$template" in value || value.values.any(::needsResolution)
        is List<*> -> value.any(::needsResolution)
        is String -> "%{" in value
        else -> false
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveValue(
        value: Any?,
        context: Map<String, String>,
        view: TemplateView,
        visiting: Set<String> = emptySet()
    ): Any? {
        return when (value) {
            is Map<*, *> -> {
                val map = value as Map<String, Any?>
                if (map.containsKey("\$template")) {
                    resolveTemplateDirective(map, context, view, visiting)
                } else {
                    map.mapValues { (_, v) -> resolveValue(v, context, view, visiting) }
                }
            }
            is List<*> -> value.map { resolveValue(it, context, view, visiting) }
            is String -> substituteParamsInString(value, context, emptyMap())
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
        view: TemplateView,
        visiting: Set<String>
    ): Map<String, Any?> {
        val directive = node["\$template"]

        // Shorthand `$template: template_name` (a plain scalar) is accepted instead of silently leaving the
        // directive unresolved, which used to leave the item without a material (it rendered as a grass block).
        val directiveMap: Map<*, *> = when (directive) {
            is Map<*, *> -> directive
            is String -> linkedMapOf("use" to directive)
            else -> {
                diagnostic("Config_Load_Error_Parse", "\$template", "'\$template' must be a section or a template name")
                return node.mapValues { (_, v) -> resolveValue(v, context, view, visiting) } as Map<String, Any?>
            }
        }

        val templateName = (directiveMap["use"] as? String)?.let { substituteParamsInString(it, context, emptyMap()) }
        if (templateName.isNullOrBlank()) {
            diagnostic("Config_Load_Error_Parse", "\$template", "missing 'use' field")
            return node.filterKeys { it != "\$template" }
        }
        if (templateName in visiting) {
            diagnostic("Config_Load_Error_Parse", "\$template", "cycle: ${(visiting.toList() + templateName).joinToString(" -> ")}")
            return node.filterKeys { it != "\$template" }
        }

        val table = view.definitions
        val template = table[templateName]
        if (template == null) {
            diagnostic("Config_Load_Error_Parse", "\$template", "template '$templateName' not found")
            return node.filterKeys { it != "\$template" }
        }

        val rawArgs = directiveMap["arguments"] as? Map<*, *>
        val otherKeys = node.filterKeys { it != "\$template" && it != LIST_MODE_KEY }
        val processedArgs = processArguments(rawArgs, context, templateName, view.counters)
        warnUnusedArguments(templateName, rawArgs, template, otherKeys, table)

        val fullyResolved = resolveValue(template, processedArgs, view, visiting + templateName)
        val resolvedOtherKeys = resolveValue(otherKeys, processedArgs, view, visiting)
        val merged = mergeMaps(
            fullyResolved as Map<String, Any?>,
            resolvedOtherKeys as Map<String, Any?>,
            node[LIST_MODE_KEY]?.toString()?.equals("replace", ignoreCase = true) == true
        )
        return merged
    }

    /** Placeholder names a template body can actually consume, including `_t`/`_c` directive fields. */
    private fun collectPlaceholderNames(value: Any?, into: MutableSet<String> = LinkedHashSet()): MutableSet<String> {
        when (value) {
            is String -> PARAM_PATTERN.findAll(value).forEach { match ->
                val inner = match.groupValues[1]
                into += splitPlaceholder(inner).first
            }
            is Map<*, *> -> value.values.forEach { collectPlaceholderNames(it, into) }
            is List<*> -> value.forEach { collectPlaceholderNames(it, into) }
        }
        return into
    }

    /** Warns about `$template.arguments` entries the template body never references (their value is discarded). */
    private fun warnUnusedArguments(
        templateName: String,
        rawArgs: Map<*, *>?,
        template: Map<String, Any?>,
        overlay: Map<String, Any?>,
        table: Map<String, Map<String, Any?>>,
    ) {
        if (rawArgs.isNullOrEmpty()) return
        val used = collectPlaceholderNames(listOf(rawArgs.values.toList(), template, overlay))
        val seen = hashSetOf(templateName)
        fun collectNested(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    val directive = value["\$template"]
                    val child = when (directive) {
                        is String -> directive
                        is Map<*, *> -> directive["use"] as? String
                        else -> null
                    }
                    if (child != null && seen.add(child)) table[child]?.let {
                        collectPlaceholderNames(it, used)
                        collectNested(it)
                    }
                    value.values.forEach(::collectNested)
                }
                is List<*> -> value.forEach(::collectNested)
            }
        }
        collectNested(template)
        collectNested(overlay)
        for (rawKey in rawArgs.keys) {
            val key = rawKey as? String ?: continue
            if (key !in used) {
                diagnostic("Config_Load_Warning_Unused_Template_Argument", templateName, key)
            }
        }
    }

    /** Warns about `%{...}` placeholders left over in an entry's own fields (they would be written verbatim). */
    private fun <T> warnUnresolvedPlaceholders(resolved: T, templateName: String): T {
        for (name in collectPlaceholderNames(resolved)) {
            diagnostic("Config_Load_Warning_Unresolved_Placeholder", templateName, name)
        }
        return resolved
    }

    // ==================== Parameter processing ($t / $c) ====================

    /**
     * Processes parameter values in $template.arguments.
     * Supports plain string values and complex values with a $t transformer and/or a $c converter.
     *
     * Processing order:
     * - `$t`/`$c` values are evaluated in declaration order and only see arguments declared **before** them
     *   (plus `_ID`). This is a deliberate constraint, not an accident: `$t` may run scripts that must observe a
     *   stable partial context.
     * - Plain string values are substituted twice: once for `$t`/`$c` configs that reference them, and once in the
     *   second pass so they can reference parameters declared **later** as well as dynamic values produced by `$t`
     *   (for example, `name: "Token #%{number}"` together with `number: {$t: {type: self_increase}}`).
     *
     * Dynamic values are sampled once before the second pass so repeated references agree.
     *
     * @param rawArgs The original arguments map.
     * @param baseContext The base context (such as _ID).
     * @return The resolved arguments, including one sampled value for each dynamic parameter.
     */
    private fun processArguments(
        rawArgs: Map<*, *>?,
        baseContext: Map<String, String>,
        templateName: String,
        counters: Counters
    ): Map<String, String> {
        val staticArgs = LinkedHashMap(baseContext)
        val dynamicArgs = LinkedHashMap<String, () -> String>()

        if (rawArgs == null) return staticArgs

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

                    if (hasT || hasC || map.keys.any { customDirectiveProviders.containsKey(it) }) {
                        if (key == "_ID") continue
                        processArgWithTC(key, map, staticArgs, dynamicArgs, rawValues, templateName, counters)
                    } else {
                        diagnostic("Config_Load_Error_Parse", templateName, "argument '$key' must be a scalar or a directive")
                    }
                }
                is Number, is Boolean -> staticArgs.putIfAbsent(key, rawValue.toString())
                else -> diagnostic("Config_Load_Error_Parse", templateName, "argument '$key' must be a scalar or a directive")
            }
        }

        for ((key, supplier) in dynamicArgs) staticArgs[key] = supplier()
        for ((key, raw) in rawValues) {
            if (key !in dynamicArgs) staticArgs[key] = substituteParamsInString(raw, staticArgs, emptyMap())
        }
        return staticArgs
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
        rawValues: MutableMap<String, String>,
        templateName: String,
        counters: Counters
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
            val result = processTransformer(tConfig, resolveCtx, templateName, key, counters)
            value = result.value
            dynamic = result.dynamicSupplier
        }

        val cConfig = config["\$c"]
        val converterOptions = cConfig as? Map<*, *>
        val explicitSource = converterOptions?.get("value")?.toString()
            ?.let { substituteParamsInString(it, resolveCtx, emptyMap()) }
        val source = value ?: explicitSource ?: origin.takeIf { hadOrigin }
        if (cConfig != null && source == null && converterOptions?.get("fallback") == null) {
            diagnostic("Config_Load_Warning_Converter_Without_Source", key, converterOptions?.get("type") ?: cConfig)
        }

        fun convert(input: String): String? =
            processConverter(cConfig, input, resolveCtx + ("_ORIGIN" to input))

        val finalValue = when {
            cConfig == null -> source
            source != null -> convert(source)
            else -> converterOptions?.get("fallback")?.toString()
                ?.let { substituteParamsInString(it, resolveCtx, emptyMap()) }
        }
        if (finalValue != null) {
            currentArgs[key] = substituteParamsInString(finalValue, currentArgs, emptyMap())
            rawValues[key] = finalValue
        } else if (cConfig != null) {
            currentArgs.remove(key)
        }
        if (dynamic != null) {
            val supplier = dynamic
            dynamicArgs[key] = {
                val next = supplier()
                if (cConfig == null) next else convert(next) ?: finalValue.orEmpty()
            }
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
        val value: String?,
        val dynamicSupplier: (() -> String)? = null
    )

    @Suppress("UNCHECKED_CAST")
    private fun processTransformer(
        tConfig: Any?,
        resolveCtx: Map<String, String>,
        templateName: String,
        parameter: String,
        counters: Counters
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
            "self_increase" -> processSelfIncreaseTransformer(config, resolveCtx, templateName, parameter, counters)
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
                    diagnostic("Config_Load_Error_Parse", "\$t", "unknown transformer type: $type")
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
        config: Map<String, Any?>,
        args: Map<String, String>,
        templateName: String,
        parameter: String,
        counters: Counters
    ): TransformResult {
        fun integer(key: String, default: Int): Int? {
            val raw = config[key]?.toString() ?: return default
            val parsed = substituteParamsInString(raw, args, emptyMap()).toIntOrNull()
            if (parsed == null) diagnostic("Config_Load_Error_Parse", "\$t self_increase", "$key must be an integer: $raw")
            return parsed
        }
        val from = integer("from", 0) ?: return TransformResult(null)
        val to = integer("to", Int.MAX_VALUE) ?: return TransformResult(null)
        val step = integer("step", 1) ?: return TransformResult(null)
        val stepInterval = integer("step_interval", 1) ?: return TransformResult(null)

        // Validate during loading: step_interval <= 0 would cause an ArithmeticException when next() computes the remainder.
        // A zero step or a direction that conflicts with from/to would stall or overflow the counter.
        // Reject and report these configurations here instead of failing at runtime.
        if (stepInterval <= 0) {
            diagnostic("Config_Load_Error_Parse", "\$t self_increase", "step_interval must be > 0, got $stepInterval")
            return TransformResult(from.toString())
        }
        if (step == 0 && from != to) {
            diagnostic("Config_Load_Error_Parse", "\$t self_increase", "step must not be 0 unless from == to")
            return TransformResult(from.toString())
        }
        if ((step > 0 && from > to) || (step < 0 && from < to)) {
            diagnostic("Config_Load_Error_Parse", "\$t self_increase", "step direction does not match from/to range (from=$from, to=$to, step=$step)")
            return TransformResult(from.toString())
        }

        return TransformResult(
            value = from.toString(),
            dynamicSupplier = { counters.next(templateName, parameter, from, to, step, stepInterval) }
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

        @Synchronized
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
    ): String? {
        val rawType: String
        val config: Map<String, Any?>

        when (cConfig) {
            is String -> {
                rawType = cConfig
                config = emptyMap()
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                config = cConfig as Map<String, Any?>
                rawType = config["type"]?.toString() ?: "string"
            }
            else -> return value
        }

        val fallback = config["fallback"]?.toString()?.let {
            substituteParamsInString(it, resolveCtx, emptyMap())
        }

        val type = substituteParamsInString(rawType, resolveCtx, emptyMap()).lowercase()
        val converted = try {
            when (type.lowercase()) {
                "int" -> {
                    val round = config["round"]?.toString()
                        ?.let { substituteParamsInString(it, resolveCtx, emptyMap()).lowercase() } ?: "floor"
                    val doubleVal = value.toDouble()
                    val result = when (round) {
                        "ceil", "cell" -> ceil(doubleVal)
                        "round" -> doubleVal.roundToLong().toDouble()
                        "floor" -> floor(doubleVal)
                        else -> throw NumberFormatException("unknown rounding mode $round")
                    }
                    if (!doubleVal.isFinite() || result !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                        throw NumberFormatException("integer outside range")
                    }
                    result.toInt().toString()
                }
                "double" -> {
                    value.toDouble().also { if (!it.isFinite()) throw NumberFormatException("non-finite value") }.toString()
                }
                "string" -> applyCase(value, config)
                else -> {
                    val customProvider = customConverterProviders[type.lowercase()]
                    if (customProvider != null) {
                        customProvider.process(type.lowercase(), config, value, resolveCtx)
                    } else {
                        throw NumberFormatException("unknown converter type $type")
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            diagnostic("Config_Load_Error_Parse", "\$c", "cannot convert '$value' to $type: ${e.message}")
            fallback ?: return null
        }

        return applyCase(converted, config.mapValues { (_, option) ->
            if (option is String) substituteParamsInString(option, resolveCtx, emptyMap()) else option
        })
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

    private val PARAM_PATTERN = Regex("""%\{((?:\\[{}\\?]|[^}])*)}""")
    private val ESCAPED_PARAM_CHAR = Regex("""\\([{}\\?])""")

    private fun splitPlaceholder(inner: String): Pair<String, String?> {
        var index = 0
        while (index < inner.length) {
            if (inner[index] == '\\') {
                index += 2
            } else if (inner.startsWith("?:", index)) {
                return unescapePlaceholder(inner.substring(0, index)) to unescapePlaceholder(inner.substring(index + 2))
            } else {
                index++
            }
        }
        return unescapePlaceholder(inner) to null
    }

    private fun unescapePlaceholder(value: String): String =
        ESCAPED_PARAM_CHAR.replace(value) { it.groupValues[1] }

    private fun substituteParamsInString(
        input: String,
        arguments: Map<String, String>,
        dynamicValues: Map<String, () -> String>
    ): String {
        if ("%{" !in input) return input
        return PARAM_PATTERN.replace(input) { match ->
            val (name, fallback) = splitPlaceholder(match.groupValues[1])
            dynamicValues[name]?.invoke() ?: arguments[name] ?: fallback ?: match.value
        }
    }

    // ==================== Utility functions ====================

    @Suppress("UNCHECKED_CAST")
    internal fun deepClone(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> (value as Map<String, Any?>).mapValues { (_, v) -> deepClone(v) }
            is List<*> -> value.map { deepClone(it) }
            else -> value
        }
    }

    /**
     * Merges the node's own fields over the resolved template content.
     *
     * Maps are merged **recursively**: an item that only wants to override `xbuilder.lore` must keep the
     * template's `material`/`name`/`attributes`. Before this was a shallow overwrite, overriding one field of
     * `xbuilder` or `sertraline` discarded the whole block (the item degraded to a grass block, or a template's
     * `actions`/`vars` silently disappeared).
     *
     * Lists keep the historical append semantics ([baseValue] + [value]) so an entry that adds one lore line still
     * gets the template's lore lines first. Set `$list-mode: replace` next to `$template` to replace instead of
     * append (e.g. to fully replace `attribute-modifiers`).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun mergeMaps(base: Map<String, Any?>, overlay: Map<String, Any?>, replaceLists: Boolean = false): Map<String, Any?> {
        val result = LinkedHashMap(base)
        for ((key, value) in overlay) {
            val baseValue = result[key]
            result[key] = when {
                value is Map<*, *> && baseValue is Map<*, *> ->
                    mergeMaps(baseValue as Map<String, Any?>, value as Map<String, Any?>, replaceLists)
                value is List<*> && baseValue is List<*> && !replaceLists -> baseValue + value
                else -> value
            }
        }
        return result
    }

    private const val LIST_MODE_KEY = "\$list-mode"
}
