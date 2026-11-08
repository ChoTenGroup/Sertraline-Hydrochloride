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
 * 模板系统管理器。
 *
 * 模板文件放置在 templates/ 目录下，支持 $template 指令在任意 YAML 文件中引用。
 *
 * 模板语法：
 *   %{param}              - 参数引用
 *   %{param?:default}     - 带默认值的参数引用
 *   \{  \}                - 转义花括号（作为普通文本）
 *   \?:                   - 转义 ?:（作为普通文本）
 *
 * 默认参数：
 *   _ID                   - 自动传入当前条目的根节点 key
 *
 * 参数变换（$t）：
 *   在 $template.arguments 中使用 $t 指令对参数值进行变换：
 *   - type: when          - 按值映射
 *   - type: upper/lower   - 大小写转换
 *   - type: condition     - 条件选择
 *   - type: self_increase - 自增计数器
 *   - type: kether/...    - 脚本表达式
 *
 * 类型转换（$c）：
 *   在参数值中使用 $c 指令转换最终类型：
 *   - type: int/double/string
 *   - round: cell/round/floor
 *
 * 特殊参数：
 *   _ORIGIN               - 在 $t/$c 配置中引用参数的原始值
 *
 * 合并规则：
 *   - 相同配置项：下方（后合并的）覆盖上方
 *   - 列表项：合并为同一个列表
 */
object TemplateManager {

    // ==================== 自定义处理器接口 ====================

    /**
     * $t 变换器自定义处理器。
     * 通过 [registerTransformer] 注册，可在 YAML 中以 `$t: { type: your_type, ... }` 使用。
     */
    fun interface TransformerProvider {
        /**
         * @param type     实际使用的类型名（已小写化）
         * @param config   $t 配置的完整 map
         * @param resolveCtx 当前参数上下文，包含 _ID, _ORIGIN 等
         * @return 变换后的字符串值
         */
        fun process(type: String, config: Map<String, Any?>, resolveCtx: Map<String, String>): String
    }

    /**
     * $c 转换器自定义处理器。
     * 通过 [registerConverter] 注册，可在 YAML 中以 `$c: { type: your_type, ... }` 使用。
     */
    fun interface ConverterProvider {
        /**
         * @param type     实际使用的类型名（已小写化）
         * @param config   $c 配置的完整 map
         * @param value    待转换的字符串值（已通过 $t 变换）
         * @param resolveCtx 当前参数上下文
         * @return 转换后的字符串值
         */
        fun process(type: String, config: Map<String, Any?>, value: String, resolveCtx: Map<String, String>): String
    }

    /**
     * 参数级指令自定义处理器（与 $t/$c 同级）。
     * 通过 [registerDirective] 注册，可在 arguments 中以 `$your_name: { ... }` 使用。
     */
    fun interface DirectiveProvider {
        /**
         * @param name       指令名（如 "$my_macro"）
         * @param config     该指令对应的配置 map
         * @param key        当前处理的参数键名
         * @param currentArgs 当前静态参数 map（可修改）
         * @param dynamicArgs 动态值供应商 map（可修改）
         * @param resolveCtx 当前参数上下文
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

    /** 获取已加载的模板，不触发解析。 */
    fun getTemplate(name: String): Map<String, Any?>? = templates[name]

    /** 获取所有已加载的模板名。 */
    fun getTemplateNames(): Set<String> = templates.keys.toSet()

    /** 获取所有已加载的模板（不可变快照）。 */
    fun getAllTemplates(): Map<String, Map<String, Any?>> = templates.toMap()

    /** 手动解析模板：深拷贝 → 参数替换 → 递归解析。 */
    fun resolveTemplate(name: String, args: Map<String, String>): Map<String, Any?>? {
        val template = templates[name] ?: return null
        val resolved = resolveValue(deepClone(template), args)
        @Suppress("UNCHECKED_CAST")
        return resolved as? Map<String, Any?>
    }

    // ---- 自定义处理器注册 ----

    /** 注册自定义 $t 变换器类型。 */
    fun registerTransformer(type: String, provider: TransformerProvider) {
        customTransformerProviders[type.lowercase()] = provider
    }

    /** 注销自定义 $t 变换器类型。 */
    fun unregisterTransformer(type: String) {
        customTransformerProviders.remove(type.lowercase())
    }

    /** 注册自定义 $c 转换器类型。 */
    fun registerConverter(type: String, provider: ConverterProvider) {
        customConverterProviders[type.lowercase()] = provider
    }

    /** 注销自定义 $c 转换器类型。 */
    fun unregisterConverter(type: String) {
        customConverterProviders.remove(type.lowercase())
    }

    /** 注册自定义参数级指令（与 $t/$c 同级）。 */
    fun registerDirective(name: String, provider: DirectiveProvider) {
        customDirectiveProviders[name] = provider
    }

    /** 注销自定义参数级指令。 */
    fun unregisterDirective(name: String) {
        customDirectiveProviders.remove(name)
    }

    fun loadTemplates(context: LoadContext? = null) {
        val templateDir = File(getDataFolder(), "templates")
        if (!templateDir.exists()) {
            templateDir.mkdirs()
            releaseResourceFolder("templates")
        }

        // context 模式：解析到局部 candidate，构建完成后由 publishTemplates 整体发布；
        // 无 context（兼容入口）：直接写 live 表。
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
     * 用 candidate 整体替换 live 模板表（reload 构建成功后调用一次）。
     * 无 candidate 时不做任何事（live 表保持现状，供非 context 路径使用）。
     */
    fun publishTemplates(candidate: Map<String, Map<String, Any?>>?) {
        if (candidate == null) return
        templates.clear()
        templates.putAll(candidate)
    }

    /**
     * 解析整个数据 map 中的 $template 指令。
     * 每个顶层条目自动获得 _ID 参数（值为该条目 key）。
     *
     * @param context 当前 reload 的候选构建上下文；非 null 时模板查找优先命中
     * 本轮候选表（本轮刚加载的新模板对后续 loader 立即可见），未命中回读 live 表。
     */
    fun resolveInMap(data: Map<String, Any?>, context: LoadContext? = null): Map<String, Any?> {
        return data.mapValues { (key, value) ->
            val ctx = mapOf("_ID" to key)
            resolveValue(value, ctx, context)
        }
    }

    // ==================== 递归解析 ====================

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
     * 处理 $template 指令节点。
     *
     * 节点结构：
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

        // 提取并处理参数（支持 $t/$c 指令）
        val rawArgs = directive["arguments"] as? Map<*, *>
        val (processedArgs, dynamicValues) = processArguments(rawArgs, context)

        // 候选构建时优先读本轮候选模板，未命中回读 live 表
        val template = (loadCtx?.templates?.get(templateName) ?: templates[templateName])
        if (template == null) {
            warningL("Config_Load_Error_Parse", "\$template", "template '$templateName' not found")
            return node.mapValues { (_, v) -> resolveValue(v, context, loadCtx) } as Map<String, Any?>
        }

        // 深拷贝模板内容并替换参数（含动态值）
        val substituted = substituteParams(deepClone(template), processedArgs, dynamicValues)

        // 递归解析（处理嵌套 $template）
        val fullyResolved = resolveValue(substituted, processedArgs, loadCtx)

        // 合并节点中除 $template 外的其他字段
        val otherKeys = node.filterKeys { it != "\$template" }
        val resolvedOtherKeys = resolveValue(otherKeys, context, loadCtx)

        return mergeMaps(fullyResolved as Map<String, Any?>, resolvedOtherKeys as Map<String, Any?>)
    }

    // ==================== 参数处理（$t / $c） ====================

    /**
     * 处理 $template.arguments 中的参数值。
     * 支持普通字符串值，以及带 $t（变换器）和/或 $c（类型转换器）的复杂值。
     *
     * 两趟处理：
     * - 第一趟：按声明顺序求出每个参数的「替换前文本」，同时让后续 $t 的 _ORIGIN / %{} 引用
     *   能看到前面已声明的参数（与旧行为一致）。这一趟**只传空 dynamicValues**。
     * - 第二趟：所有参数都收集完之后，再用完整的静态参数 + 动态参数统一替换 %{...}。
     *   于是占位符可以引用**声明在后面**的参数，也可以引用 $t 产生的动态值
     *   （如 `name: "Token #%{number}"` 配合 `number: {$t: {type: self_increase}}`）。
     *
     * 动态值只在第二趟被消耗：若两趟都传 dynamicValues，同一个 %{number} 会取两次值，
     * 自增计数器就会跳号。
     *
     * @param rawArgs 原始 arguments map
     * @param baseContext 基础上下文（_ID 等）
     * @return Pair(静态参数 map, 动态值供应商 map)
     */
    private fun processArguments(
        rawArgs: Map<*, *>?,
        baseContext: Map<String, String>
    ): Pair<LinkedHashMap<String, String>, LinkedHashMap<String, () -> String>> {
        val staticArgs = LinkedHashMap(baseContext)
        val dynamicArgs = LinkedHashMap<String, () -> String>()

        if (rawArgs == null) return Pair(staticArgs, dynamicArgs)

        // 替换前文本，供第二趟重新替换
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

        // 第二趟：用完整参数集重新替换（动态值只在这里被消耗）
        for ((key, raw) in rawValues) {
            staticArgs[key] = substituteParamsInString(raw, staticArgs, dynamicArgs)
        }

        return Pair(staticArgs, dynamicArgs)
    }

    /**
     * 处理带 $t 和/或 $c 的单个参数值。
     *
     * @param rawValues 求出但尚未做 %{} 替换的最终文本，交给 [processArguments] 第二趟统一替换
     */
    private fun processArgWithTC(
        key: String,
        config: Map<String, Any?>,
        currentArgs: MutableMap<String, String>,
        dynamicArgs: MutableMap<String, () -> String>,
        rawValues: MutableMap<String, String>
    ) {
        // _ORIGIN 为该参数在当前上下文中已有的值，传入 $t/$c 配置供引用
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

        // 是否存在「源值」：$t 产出了值，或该参数已有继承值。
        // 只写 $c 而没有源值时两者都不成立（见下面的空值处理）。
        val hasSource = value != null || hadOrigin

        val rawValue = value ?: origin

        // Process $c converter (with updated _ORIGIN = value after $t)
        val cConfig = config["\$c"]
        if (cConfig != null) {
            val convertCtx = if (value != null) resolveCtx + mapOf("_ORIGIN" to rawValue) else resolveCtx
            value = processConverter(cConfig, rawValue, convertCtx)
        }

        val finalValue = value ?: ""

        // 只写了 $c/$t 而没有源值、且转换结果为空时，**不要**注册这个参数。
        // 否则会用空串顶掉模板里的 %{key?:default} 默认值（旧行为），
        // 例如 `damage: {$c: {type: int}}` 会把 amount 变成空串、物品直接构建失败。
        // 交给模板默认值处理才是符合直觉的结果；若 $c 配了 fallback，则结果非空，仍会正常注册。
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

    // ==================== $t 变换器 ====================

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

        // 加载期校验：step_interval <= 0 会在 next() 的取模处触发 ArithmeticException，
        // step == 0 或方向与 from/to 相反会让计数器永远不动/溢出 —— 在这里拒绝并报告，
        // 而不是等运行时炸掉
        if (stepInterval <= 0) {
            warningL("Config_Load_Error_Parse", "\$t self_increase", "step_interval must be > 0, got $stepInterval")
            return TransformResult(from.toString())
        }
        if (step == 0 && from != to) {
            warningL("Config_Load_Error_Parse", "\$t self_increase", "step must not be 0 unless from == to")
            return TransformResult(from.toString())
        }
        if (step != 0 && ((to - from) * step < 0)) {
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
     * 自增计数器。每次调用 next() 返回当前值并步进。
     */
    private class SelfIncreaseCounter(
        from: Int,
        private val to: Int,
        private val step: Int,
        private val stepInterval: Int
    ) {
        private var current = from
        private var callCount = 0

        fun next(): String? {
            if (current > to) return null
            val value = current
            callCount++
            if (callCount % stepInterval == 0) {
                current += step
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

        // 解析 shell 中的 %{...}
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
     * 获取配置中的字符串值，支持单字符串和列表格式（合并为多行文本）。
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
     * 执行指定类型的脚本表达式。
     * 注意：脚本执行需要运行时上下文，可能会静默失败。
     */
    private fun executeScript(shell: String, scriptType: String): Any? {
        // 脚本执行需要 Bukkit 运行时上下文（player/sender），
        // 在配置重载期间可能不可用。
        // 这里尝试执行，失败时返回 null 由 caller 走 fallback。
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
        // KetherShell.eval 需要 sender 上下文，使用 console sender
        val sender = io.github.zzzyyylllty.sertraline.Sertraline.consoleSender
        return taboolib.module.kether.KetherShell.eval(
            shell,
            taboolib.module.kether.ScriptOptions.builder()
                .sender(sender)
                .build()
        ).get()
    }

    private fun evalStandardJs(shell: String): Any? {
        // 复用每线程缓存的 JSR-223 引擎，避免每次调用都创建 ScriptEngineManager + engine
        val engine = io.github.zzzyyylllty.sertraline.util.GraalJsUtil.nashornEngineHolder.get() ?: return null
        // 使用独立 bindings，避免脚本顶层 var 污染后续 eval
        return engine.eval(shell, engine.createBindings())
    }

    private fun evalFluxon(shell: String): Any? {
        return io.github.zzzyyylllty.sertraline.function.fluxon.FluxonShell.invoke(shell, useCache = true) {
            // 空变量集
        }
    }

    private fun evalGraalJs(shell: String): Any? {
        // 尝试使用 GraalJS (需要依赖)，失败时回退到标准 JS
        return try {
            // evalToJsString 保持旧实现 Value.toString 的 JS 语义输出格式
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

    // ==================== $c 类型转换 ====================

    /**
     * $c 类型转换器。
     *
     * 完整格式：
     * ```yaml
     * $c:
     *   type: int
     *   round: floor   # cell / round / floor
     *   fallback: 1
     *   case: uppercase  # 可选：uppercase / lowercase
     * ```
     *
     * 简写格式：
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

    // ==================== 参数替换 ====================

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
        // 快速路径
        if (input.none { it == '%' || it == '\\' }) return input

        // Step 1: 保护转义序列
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

        // Step 2: 替换 %{param} 模式（支持动态值）
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

        // Step 3: 恢复转义字符
        processed = processed
            .replace(ESCAPE_LEFT, "{")
            .replace(ESCAPE_RIGHT, "}")
            .replace(ESCAPE_DEFAULT, "?:")

        return processed
    }

    /**
     * 处理 %{...} 内部的内容。
     * 支持 %{param} 和 %{param?:default} 两种格式。
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

    // ==================== 工具函数 ====================

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
