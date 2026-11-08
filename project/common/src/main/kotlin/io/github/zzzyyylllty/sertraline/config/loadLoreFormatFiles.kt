package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.Sertraline.loreFormats
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.AbstractLoreElement
import io.github.zzzyyylllty.sertraline.data.AbstractLoreFormat
import io.github.zzzyyylllty.sertraline.data.CommonLoreElement
import io.github.zzzyyylllty.sertraline.data.CompareElementSetting
import io.github.zzzyyylllty.sertraline.data.CompareOp
import io.github.zzzyyylllty.sertraline.data.CompareSetting
import io.github.zzzyyylllty.sertraline.data.CompareTarget
import io.github.zzzyyylllty.sertraline.data.CompareTargetFilter
import io.github.zzzyyylllty.sertraline.data.CompareTargetFilterCondition
import io.github.zzzyyylllty.sertraline.data.CompareTargetFilterDecision
import io.github.zzzyyylllty.sertraline.data.CompareTargetFilterWhen
import io.github.zzzyyylllty.sertraline.data.CompareConditionGroup
import io.github.zzzyyylllty.sertraline.data.CompareTargetEquipmentSlot
import io.github.zzzyyylllty.sertraline.data.CompareTargetFixedPath
import io.github.zzzyyylllty.sertraline.data.CompareTargetWhen
import io.github.zzzyyylllty.sertraline.data.parseCompareSlot
import io.github.zzzyyylllty.sertraline.data.ComplexLoreElement
import io.github.zzzyyylllty.sertraline.data.DataCompareCondition
import io.github.zzzyyylllty.sertraline.data.DataCompareGroup
import io.github.zzzyyylllty.sertraline.data.LineMode
import io.github.zzzyyylllty.sertraline.data.LoreFormat
import io.github.zzzyyylllty.sertraline.data.LoreSetting
import io.github.zzzyyylllty.sertraline.data.LoreSettingOverride
import io.github.zzzyyylllty.sertraline.data.PlainLoreElement
import io.github.zzzyyylllty.sertraline.data.ResultLimit
import io.github.zzzyyylllty.sertraline.data.ScriptCompareGroup
import io.github.zzzyyylllty.sertraline.data.SwitchFork
import io.github.zzzyyylllty.sertraline.data.SwitchableLoreFormat
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.event.SertralineLoreFormatFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineLoreFormatFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.DependencyHelper
import io.github.zzzyyylllty.sertraline.util.serialize.parseToMap
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common5.compileJS
import java.io.File
import javax.script.CompiledScript

private val packetLoreLF: Boolean get() = SertralineSettings.packetLore && DependencyHelper.pe

/**
 * loreFormats 写入目标：context 模式写 candidate map；无 context（兼容入口）写 live 表。
 * switchable 解析期间的 containsKey/读 也必须走同一张表（先解析 normal 再解析 switchable）。
 */
private fun loreFormatSink(context: LoadContext?): MutableMap<String, AbstractLoreFormat> =
    context?.loreFormats ?: Sertraline.loreFormats

fun loadLoreFormatFiles(context: LoadContext? = null) {
    infoL("LoreFormat_Load")
    SertralineLoreFormatFileLoadPreEvent(context).call()
    try {
        if (!File(getDataFolder(), "lore-formats").exists()) {
            warningL("LoreFormat_Load_Regen")
            releaseResourceFolder("lore-formats")
        }
        val loreFormatsFolder = File(getDataFolder(), "lore-formats")
        val regex = SertralineSettings.fileLoadLoreFormat
        val files = safeWalkDirectory(loreFormatsFolder, maxDepth = 10) { file ->
            file.isFile && regex.matches(file.name)
        }
        val allSwitchable = LinkedHashMap<String, Map<String, Any?>>()
        for (file in files) {
            allSwitchable.putAll(loadLoreFormatFile(file, regex, context))
        }
        loadSwitchableFormats(allSwitchable, "all files", context)
        SertralineLoreFormatFileLoadPostEvent(context).call()
    } catch (e: Exception) {
        SertralineLoreFormatFileLoadPostEvent(context, e).call()
        throw e
    }
}

fun loadLoreFormatFile(file: File, regex: Regex, context: LoadContext? = null): Map<String, Map<String, Any?>> {
    devLog("Loading file ${file.name}")

    if (file.isDirectory) {
        // 目录递归已由safeWalkDirectory处理，这里直接返回空map
        return emptyMap()
    }

    if (!file.name.matches(regex)) {
        devLog("${file.name} not match regex, skipping...")
        return emptyMap()
    }
    val raw = multiExtensionLoader(file) ?: return emptyMap()
    val map = TemplateManager.resolveInMap(raw, context)
    if (map.isEmpty()) {
        severeL("Config_Load_Error_Empty", file.name)
        return emptyMap()
    }
    val normal = LinkedHashMap<String, Map<String, Any?>>()
    val switchable = LinkedHashMap<String, Map<String, Any?>>()
    map.forEach { (key, value) ->
        val m = value as? Map<String, Any?> ?: linkedMapOf()
        if (m["mode"]?.toString() == "switch") switchable[key] = m else normal[key] = m
    }
    normal.forEach { (key, m) ->
        try {
            loadLoreFormat(key, m, context)
        } catch (ex: Exception) {
            severeL("LoreFormat_Load_Error_Parse", key, ex.message ?: "Unknown error")
        }
    }
    return switchable
}

fun loadLoreFormat(key: String, arg: Map<String, Any?>, context: LoadContext? = null) {
    val c = ConfigUtil
    val elementConfigs = c.getDeep(arg, "elements") as? List<*> ?: emptyList<Any?>()
    val elements = mutableListOf<AbstractLoreElement>()
    elementConfigs.forEach {
        elements.add(
            if (it is String) PlainLoreElement(it)
            else if (it is Map<*, *>) {
                val content = it["content"].asListedStringEnhanced() ?: ""
                val key = it["key"] as? String
                val lineMode = (it["lineMode"] as? String)?.let { value -> runCatching { LineMode.valueOf(value) }.getOrNull() }
                val lineRequire = it["lineRequire"] as? List<String>
                val compareEl = loadElementCompare(it)
                val hasComplex = lineMode != null || lineRequire != null || compareEl != null
                if (!hasComplex && key == null) {
                    PlainLoreElement(content)
                } else if (!hasComplex) {
                    CommonLoreElement(content, key)
                } else {
                    ComplexLoreElement(content, key, lineMode, lineRequire, jsParses = null, compare = compareEl)
                }
            } else throw IllegalArgumentException("Invalid lore format data type!")
        )
    }
    val settingsMap = c.getDeep(arg, "settings") as? Map<*, *>

    val visual = if (packetLoreLF) {
        settingsMap?.get("visual") as? Boolean ?: false
    } else {
        warningL("Warning_Packet_Lore_Not_Enabled", key)
        false
    }

    val loreFormat = LoreFormat(
        settings = LoreSetting(
            overwrite = settingsMap?.get("overwrite") as? Boolean ?: true,
            visual = visual,
            skipBlank = settingsMap?.get("auto-skip-blank") as? Boolean ?: true,
            compareEnabled = settingsMap?.get("compare-enabled") as? Boolean ?: false,
            compare = loadCompareSetting(settingsMap?.get("compare") as? Map<*, *>),
        ),
        elements = elements
    )
    // Validate element compareName cross-references against the format's own compare settings
    validateCompareNameRefs(key, loreFormat)
    loreFormatSink(context)[key] = loreFormat
}

// ── Element-level compare ──────────────────────────────────────

private fun loadElementCompare(m: Map<*, *>): CompareElementSetting? {
    val raw = m["compare"] as? Map<*, *> ?: return null
    val contents = linkedMapOf<String, String>()
    (raw["contents"] as? Map<*, *>)?.forEach { (k, v) ->
        contents[k.toString()] = v?.toString() ?: ""
    }
    return CompareElementSetting(
        contents = contents,
        currentId = raw["currentId"] as? String,
        compareId = raw["compareId"] as? String,
        compareName = raw["compareName"]?.toString() ?: "default"
    )
}

// ── Switchable format loading ──────────────────────────────────

private fun loadSwitchableFormats(switchable: Map<String, Map<String, Any?>>, fileName: String, context: LoadContext? = null) {
    val sink = loreFormatSink(context)
    val pending = switchable.toMutableMap()
    val pendingIt = pending.entries.iterator()
    while (pendingIt.hasNext()) {
        val (key, m) = pendingIt.next()
        val whenList = ConfigUtil.getDeep(m, "when") as? List<*>
        if (whenList.isNullOrEmpty()) {
            severeL("LoreFormat_Switch_Load_No_When", key, fileName)
            pendingIt.remove()
        }
    }
    var progressed = true
    while (progressed && pending.isNotEmpty()) {
        progressed = false
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (key, m) = iterator.next()
            if (isSwitchableReady(m, sink)) {
                try {
                    loadSwitchableLoreFormat(key, m, sink)
                } catch (ex: Exception) {
                    severeL("LoreFormat_Switch_Load_Error", key, fileName, ex.message ?: "Unknown error")
                }
                iterator.remove()
                progressed = true
            }
        }
    }
    pending.forEach { (key, m) ->
        val missing = missingSwitchTargets(m, sink)
        severeL("LoreFormat_Switch_Load_Unresolved", key, fileName, missing.joinToString(", "))
    }
}

private fun isSwitchableReady(arg: Map<String, Any?>, sink: Map<String, AbstractLoreFormat>): Boolean {
    val whenList = ConfigUtil.getDeep(arg, "when") as? List<*> ?: return false
    if (whenList.isEmpty()) return false
    return whenList.all { item ->
        val then = (item as? Map<*, *>)?.get("then")?.toString()
        then != null && sink.containsKey(then)
    }
}

private fun missingSwitchTargets(arg: Map<String, Any?>, sink: Map<String, AbstractLoreFormat>): List<String> {
    return (ConfigUtil.getDeep(arg, "when") as? List<*>)?.mapNotNull { item ->
        val then = (item as? Map<*, *>)?.get("then")?.toString()
        then?.takeUnless { sink.containsKey(it) }
    }?.distinct() ?: emptyList()
}

private fun loadSwitchableLoreFormat(key: String, arg: Map<String, Any?>, sink: MutableMap<String, AbstractLoreFormat>) {
    val c = ConfigUtil
    val ovSettings = c.getDeep(arg, "overwriteable-settings") as? Map<*, *>
    val settingsOverride = if (ovSettings != null) LoreSettingOverride(
        overwrite = ovSettings["overwrite"] as? Boolean,
        visual = ovSettings["visual"] as? Boolean,
        skipBlank = ovSettings["auto-skip-blank"] as? Boolean,
        compareEnabled = (ovSettings["compare-enabled"] as? Boolean) ?: (ovSettings["compare"] != null),
        compare = loadCompareSetting(ovSettings["compare"] as? Map<*, *>),
    ) else null
    val preVariables = LinkedHashMap<String, CompiledScript>()
    (c.getDeep(arg, "pre-variables") as? Map<*, *>)?.forEach { (name, expr) ->
        val compiled = expr?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { compileJsSafely(it) }
        if (compiled == null) {
            warningL("LoreFormat_Switch_Load_PreVar_Compile", key, name?.toString() ?: "?", expr?.toString() ?: "")
        } else {
            preVariables[name.toString()] = compiled
        }
    }
    val forks = mutableListOf<SwitchFork>()
    val whenList = c.getDeep(arg, "when") as? List<*> ?: emptyList<Any?>()
    for (item in whenList) {
        val m = item as? Map<*, *> ?: continue
        val thenKey = m["then"]?.toString() ?: continue
        val target = sink[thenKey] ?: throw IllegalArgumentException("target \"$thenKey\" not loaded")
        val conditionCompiled = compileJsSafely(m["if"]?.toString() ?: "true")
        if (conditionCompiled == null) {
            warningL("LoreFormat_Switch_Load_Condition_Compile", key, thenKey, m["if"]?.toString() ?: "")
            continue
        }
        forks += SwitchFork(conditionCompiled, target)
    }
    if (forks.isEmpty()) {
        throw IllegalArgumentException("no valid fork in \"when\"")
    }
    // Validate element compareName references against the override's compare groups.
    // Sub-format elements (e.g. comparable_attribute) get their compare settings from the
    // parent's `overwriteable-settings`, not from their own settings — so we check them here
    // against settingsOverride.compare, which validateCompareNameRefs() inside loadLoreFormat
    // cannot see (the sub-format's own settings.compare is null).
    settingsOverride?.compare?.let { overrideCompare ->
        val compareNames = overrideCompare.compares.keys
        for (fork in forks) {
            val target = fork.toLoreFormat
            if (target is LoreFormat) {
                for (element in target.elements) {
                    if (element is ComplexLoreElement && element.compare != null) {
                        val cn = element.compare.compareName
                        if (cn !in compareNames) {
                            warningL("LoreFormat_Load_Element_CompareName_Not_Found", key, cn, compareNames.joinToString())
                        }
                    }
                }
            }
        }
    }
    sink[key] = SwitchableLoreFormat(
        settings = LoreSetting(
            overwrite = settingsOverride?.overwrite ?: true,
            visual = settingsOverride?.visual ?: true,
            skipBlank = settingsOverride?.skipBlank ?: true,
            compareEnabled = settingsOverride?.compareEnabled ?: false,
            compare = settingsOverride?.compare,
        ),
        preVariables = preVariables,
        forks = forks,
        settingsOverride = settingsOverride,
    )
    devLog("Loaded switchable lore format \"$key\" with ${forks.size} fork(s)")
}

// ── Compare setting loading ────────────────────────────────────

private fun loadCompareSetting(raw: Map<*, *>?): CompareSetting? {
    if (raw == null) return null
    val target = loadCompareTarget(raw["target"] as? Map<*, *>)
    val compares = linkedMapOf<String, CompareConditionGroup>()
    (raw["compares"] as? Map<*, *>)?.forEach { (name, value) ->
        val group = when (value) {
            is String -> {
                val compiled = compileJsSafely(value)
                if (compiled != null) ScriptCompareGroup(compiled)
                else null
            }
            is Map<*, *> -> {
                val type = value["type"]?.toString()
                if (type == "data") {
                    val conditions = mutableListOf<DataCompareCondition>()
                    val whenList = value["when"] as? List<*> ?: emptyList<Any?>()
                    for (item in whenList) {
                        val m = item as? Map<*, *> ?: continue
                        val operator = (m["operator"] as? String)?.let { runCatching { CompareOp.valueOf(it) }.getOrNull() }
                        val value = m["value"]?.toString()
                        val parsedLow: Double?
                        val parsedHigh: Double?
                        if (operator == CompareOp.BETWEEN && value != null) {
                            val parts = value.split("~")
                            if (parts.size == 2) {
                                parsedLow = parts[0].toDoubleOrNull()
                                parsedHigh = parts[1].toDoubleOrNull()
                            } else {
                                parsedLow = null
                                parsedHigh = null
                            }
                        } else {
                            parsedLow = null
                            parsedHigh = null
                        }
                        // Bug 3: warn when LOST/NEW conditions carry unnecessary operator/value
                        val resultLimit = (m["result-limit"] as? String)?.let { runCatching { ResultLimit.valueOf(it) }.getOrNull() } ?: ResultLimit.DEFAULT
                        if (resultLimit != ResultLimit.DEFAULT && (operator != null || value != null)) {
                            warningL("LoreFormat_Load_Compare_Redundant_Operator", name.toString(), m["result"]?.toString() ?: "?")
                        }
                        conditions += DataCompareCondition(
                            operator = operator,
                            value = value,
                            result = m["result"]?.toString() ?: continue,
                            resultLimit = resultLimit,
                            defaultValue = (m["default-value"] as? Number)?.toDouble() ?: 0.0,
                            parsedLow = parsedLow,
                            parsedHigh = parsedHigh,
                            parsedValue = value?.toDoubleOrNull(),
                        )
                    }
                    DataCompareGroup(conditions)
                } else {
                    warningL("LoreFormat_Load_Compare_Unknown_Type", name.toString(), type ?: "?")
                    null
                }
            }
            else -> null
        }
        if (group != null) compares[name.toString()] = group
    }
    return CompareSetting(target, compares)
}

private fun loadCompareTarget(raw: Map<*, *>?): CompareTarget? {
    if (raw == null) return null
    val type = raw["type"]?.toString() ?: "simple"
    val whens = mutableListOf<CompareTargetWhen>()
    (raw["when"] as? List<*>)?.forEach { item ->
        val m = item as? Map<*, *> ?: return@forEach
        val ifBlock = m["if"] as? Map<*, *> ?: return@forEach
        // fixed-path
        if (ifBlock["fixed-path"] == true) {
            whens += CompareTargetFixedPath()
        }
        // equipment-slot
        val slots = ifBlock["equipment-slot"]
        if (slots != null) {
            val slotList = when (slots) {
                is List<*> -> slots.mapNotNull { s -> s?.toString()?.let { parseCompareSlot(it) } }
                is String -> listOfNotNull(parseCompareSlot(slots.toString()))
                else -> emptyList()
            }
            if (slotList.isNotEmpty()) {
                whens += CompareTargetEquipmentSlot(slotList)
            }
        }
    }
    return CompareTarget(type, whens, loadCompareTargetFilter(raw["filter"] as? Map<*, *>))
}

/**
 * Load the optional compare-target filter gate.
 *
 * Structure:
 *   filter:
 *     when:
 *       - if: "<js boolean condition>"   # optional; absent = always matches
 *         then: deny                     # or "allow"
 *
 * Entries are evaluated top-to-bottom at render time; the first match wins.
 * DENY skips the whole comparison (compare-enabled treated as false); ALLOW proceeds.
 */
private fun loadCompareTargetFilter(raw: Map<*, *>?): CompareTargetFilter? {
    if (raw == null) return null
    val whens = mutableListOf<CompareTargetFilterWhen>()
    (raw["when"] as? List<*>)?.forEach { item ->
        val m = item as? Map<*, *> ?: return@forEach
        val decision = (m["then"] as? String)?.let {
            runCatching { CompareTargetFilterDecision.valueOf(it.trim().uppercase()) }.getOrNull()
        }
        if (decision == null) {
            warningL("LoreFormat_Load_Filter_Unknown_Decision", m["then"]?.toString() ?: "?")
            return@forEach
        }
        val condition = parseFilterCondition(m["if"])
        whens += CompareTargetFilterWhen(condition, decision)
    }
    if (whens.isEmpty()) return null
    return CompareTargetFilter(whens)
}

/**
 * Parse a single filter condition entry.
 *
 * Supported forms in YAML `if:`:
 *   - `null` (bare `then: deny`) → [CompareTargetFilterCondition.Always]
 *   - `"js expression"` → [CompareTargetFilterCondition.Script]
 *   - `{ js: "..." }` → [CompareTargetFilterCondition.Script]
 *   - `{ self-compare: true }` → [CompareTargetFilterCondition.SelfCompare]
 *   - `{ lore-format: "name" }` → [CompareTargetFilterCondition.LoreFormat]
 */
private fun parseFilterCondition(ifRaw: Any?): CompareTargetFilterCondition {
    return when (ifRaw) {
        null -> CompareTargetFilterCondition.Always
        is String -> {
            compileJsSafely(ifRaw)?.let { CompareTargetFilterCondition.Script(it) }
                ?: CompareTargetFilterCondition.Always
        }
        is Map<*, *> -> {
            when {
                ifRaw["self-compare"] == true -> CompareTargetFilterCondition.SelfCompare
                ifRaw["lore-format"] != null -> CompareTargetFilterCondition.LoreFormat(parseLoreFormatNames(ifRaw["lore-format"]))
                ifRaw["js"] != null -> {
                    compileJsSafely(ifRaw["js"].toString())?.let { CompareTargetFilterCondition.Script(it) }
                        ?: CompareTargetFilterCondition.Always
                }
                else -> CompareTargetFilterCondition.Always
            }
        }
        else -> CompareTargetFilterCondition.Always
    }
}

/**
 * Parse the `lore-format` filter value into a set of accepted format names.
 *
 * Accepts both a single quoted string and a YAML multi-line block scalar (which
 * SnakeYAML delivers as one string with embedded newlines), as well as a list:
 *   - "comparable_format"
 *   - |
 *       comparable_format
 *       comparable_attribute
 *   - [comparable_format, comparable_attribute]
 * Each line/element is trimmed; blank lines are dropped.
 */
private fun parseLoreFormatNames(raw: Any?): Set<String> = when (raw) {
    is Collection<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { n -> n.isNotEmpty() } }.toSet()
    is String -> raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    else -> emptySet()
}

/**
 * Validate that every element's compareName references a key in the format's own compare settings.
 * This is a load-time check; the switchable override path is validated at runtime.
 */
private fun validateCompareNameRefs(formatKey: String, format: LoreFormat) {
    val compareNames = format.settings.compare?.compares?.keys
    if (compareNames.isNullOrEmpty()) return
    for (element in format.elements) {
        if (element is ComplexLoreElement && element.compare != null) {
            val cn = element.compare.compareName
            if (cn !in compareNames) {
                warningL("LoreFormat_Load_Element_CompareName_Not_Found", formatKey, cn, compareNames.joinToString())
            }
        }
    }
}

private fun compileJsSafely(source: String): CompiledScript? =
    runCatching { source.compileJS() }
        .onFailure { warningL("JS compilation error: ${it.message}") }
        .getOrNull()