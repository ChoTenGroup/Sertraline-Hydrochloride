package io.github.zzzyyylllty.sertraline.item

import io.github.zzzyyylllty.sertraline.data.AlterationOperation
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.SDataAlteration
import io.github.zzzyyylllty.sertraline.data.applyAllTo
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.item.adapter.transferBooleanToByte
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseMapNBT
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseNBT
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.ItemTag
import taboolib.module.nms.getItemTag

/**
 * 数据修改（Alteration）系统 —— 宝石系统等「在构建时修改物品数据」功能的基底。
 *
 * 数据来源有两类：
 * 1. 模板定义：物品配置中的 `sertraline:alteration` 节点（每次构建都会应用）
 * 2. 实例持久化：物品 NBT 中的 [ALTERATION_TAG] 复合节点（重建时回注，
 *    例如镶嵌宝石后写入的修改会随 rebuild 一直保留）
 *
 * NBT 结构：
 * ```yaml
 * sertraline_alteration:
 *   ALTERATION_NAME:
 *     key: "sertraline:vars.test1"
 *     operation: "SET"
 *     value: 1
 * ```
 */

/** NBT 中存放实例修改的节点名 */
const val ALTERATION_TAG = "sertraline_alteration"

/** 模板配置中存放修改定义的节点（data 命名空间） */
const val ALTERATION_DATA_KEY = "sertraline:alteration"

/**
 * 「NBT 持久化目标」前缀：key 以这些前缀开头的修改，效果会直接折叠进物品
 * 自身的持久化 NBT（如 vars 最终由 sertralineAdapter 写入 `sertraline_data`），
 * 因此**不可撤回**——之后即使移除记录，效果仍留在物品上；记录本身保留在
 * [ALTERATION_TAG] 中并打上 `persisted: true` 标记，仅供追溯、不再重放。
 */
val PERSISTENT_ALTERATION_PREFIXES = listOf("sertraline:vars")

/** 该修改是否指向 NBT 持久化目标（永久生效、不可撤回） */
fun SDataAlteration.isPersistentTarget(): Boolean =
    PERSISTENT_ALTERATION_PREFIXES.any { key == it || key.startsWith("$it.") || key.startsWith("$it:") }

/**
 * 从配置内容解析修改列表。支持两种格式：
 *
 * Map 形式（key 即修改名称）：
 * ```yaml
 * sertraline:alteration:
 *   gem_ruby_3:
 *     key: "sertraline:vars.atk"
 *     operation: ADD
 *     value: 15
 * ```
 *
 * List 形式（名称写在 name 字段）：
 * ```yaml
 * sertraline:alteration:
 *   - name: gem_ruby_3
 *     key: "sertraline:vars.atk"
 *     operation: ADD
 *     value: 15
 * ```
 */
fun parseAlterations(source: Any?): List<SDataAlteration> {
    return when (source) {
        is Map<*, *> -> source.entries.mapNotNull { (name, def) ->
            name?.toString()?.let { parseAlterationEntry(it, def) }
        }
        is List<*> -> source.mapNotNull { element ->
            if (element is Map<*, *>) {
                val name = element["name"]?.toString() ?: return@mapNotNull null
                parseAlterationEntry(name, element)
            } else null
        }
        else -> emptyList()
    }
}

/**
 * 解析单条修改定义。除通用的 key / operation / value 外，
 * 还支持便捷字段：`map-key`（MAP_SET / MAP_REMOVE）、`index`（LIST_REMOVE）。
 */
fun parseAlterationEntry(name: String, def: Any?): SDataAlteration? {
    if (def !is Map<*, *>) return null
    val key = def["key"]?.toString() ?: return null
    val operation = parseOperation(def["operation"])
    val value = when (operation) {
        AlterationOperation.MAP_SET -> {
            val mapKey = def["map-key"]?.toString() ?: return null
            mapOf("key" to mapKey, "value" to def["value"])
        }
        AlterationOperation.MAP_REMOVE -> def["map-key"]?.toString() ?: return null
        AlterationOperation.LIST_REMOVE -> {
            // applyTo 要求 LIST_REMOVE 的 value 为 Int 下标
            ((def["index"] ?: def["value"]) as? Number)?.toInt() ?: return null
        }
        else -> def["value"]
    }
    return SDataAlteration(name, key, operation, value)
}

private fun parseOperation(raw: Any?): AlterationOperation {
    val name = raw?.toString()?.uppercase()?.replace('-', '_') ?: return AlterationOperation.SET
    return AlterationOperation.entries.firstOrNull { it.name == name } ?: AlterationOperation.SET
}

/**
 * 从 NBT 读回的实例修改记录： alteration 本体 + 是否已折叠进持久化 NBT。
 * [persisted] 为 true 的条目仅供追溯，构建时不再重放（防止 ADD 等操作无限累加）。
 */
data class RecordedAlteration(
    val alteration: SDataAlteration,
    val persisted: Boolean = false,
    /** Value of the target before a folded persistent alteration was applied. */
    val previous: Any? = null,
    /** Distinguishes a known missing value from an old record with no snapshot. */
    val previousKnown: Boolean = false,
    /** Whether the target existed before this alteration was folded. */
    val previousPresent: Boolean = false,
    /** Stable application order for replaying folded alterations across gem slots. */
    val order: Long? = null,
    /** First intermediate node that was missing or non-map and therefore replaced. */
    val previousParentPath: String? = null,
    val previousParent: Any? = null,
    val previousParentPresent: Boolean = false,
    val previousParentRoot: Boolean = false,
)

/** 读取物品 NBT 中持久化的实例修改记录（[ALTERATION_TAG]，含 persisted 标记） */
fun readAlterationRecordsFromNBT(itemStack: ItemStack): List<RecordedAlteration> {
    val compound = itemStack.getItemTag(true)[ALTERATION_TAG]?.asCompound() ?: return emptyList()
    return compound.entries.mapNotNull { (nbtKey, data) ->
        try {
            val map = data.parseMapNBT()
            val key = map["key"]?.toString() ?: return@mapNotNull null
            val operation = parseOperation(map["operation"])
            // The record name is stored explicitly: the NBT key it lives under is sanitized
            // (see alterationNbtKey), so it must not be used as the name. Fall back to it for
            // records written before the name field existed.
            val name = map["name"]?.toString() ?: nbtKey
            // Boolean 写入 NBT 会被转成 byte(0/1)，读回时兼容两种表示
            val persisted = when (val p = map["persisted"]) {
                is Number -> p.toInt() != 0
                is String -> p.equals("true", ignoreCase = true)
                else -> false
            }
            val previousKnown = when (val known = map["previous-known"]) {
                is Number -> known.toInt() != 0
                is String -> known.equals("true", ignoreCase = true)
                else -> "previous" in map
            }
            val previousPresent = when (val present = map["previous-present"]) {
                is Number -> present.toInt() != 0
                is String -> present.equals("true", ignoreCase = true)
                else -> "previous" in map
            }
            val previousParentRoot = when (val root = map["previous-parent-root"]) {
                is Number -> root.toInt() != 0
                is String -> root.equals("true", ignoreCase = true)
                else -> false
            }
            val previousParentPath = map["previous-parent-path"]?.toString() ?: if (previousParentRoot) "" else null
            val previousParentPresent = when (val present = map["previous-parent-present"]) {
                is Number -> present.toInt() != 0
                is String -> present.equals("true", ignoreCase = true)
                else -> "previous-parent" in map
            }
            RecordedAlteration(
                SDataAlteration(name, key, operation, map["value"]),
                persisted,
                map["previous"],
                previousKnown,
                previousPresent,
                (map["order"] as? Number)?.toLong(),
                previousParentPath,
                map["previous-parent"],
                previousParentPresent,
                previousParentRoot,
            )
        } catch (e: Exception) {
            devLog("Failed to parse alteration '$nbtKey' from NBT: ${e.message}")
            null
        }
    }
}

/**
 * NBT key a record is stored under inside [ALTERATION_TAG].
 *
 * `ItemTag.set()` reads dots as nesting separators, so a record named
 * `gem_slot_red_sertraline:vars.atk` was written as the nested compound
 * `gem_slot_red_sertraline:vars -> atk` instead of a flat key. Reads iterate the flat entries
 * and require `key`/`operation`, so every such record was silently dropped — which is exactly
 * every gem alteration, since their names always end in a dotted data path. Sanitizing the key
 * keeps the node flat; the real name travels inside the entry.
 *
 * The replacement is **not injective** (`probe.with.dot` and `probe_with_dot` both sanitize to
 * `probe_with_dot`), and the compound is written with `put`, so the second record silently
 * overwrote the first and its `previous`/`persisted` state was lost. Names that actually contain a
 * dot therefore get a stable suffix derived from the original name; names made only of safe
 * characters keep their exact spelling, so existing items stay readable.
 */
private fun alterationNbtKey(name: String): String {
    if ('.' !in name) return name
    return name.replace('.', '_') + "_" + Integer.toHexString(name.hashCode())
}


/** 读取物品 NBT 中的实例修改（忽略 persisted 标记，兼容旧用法） */
fun readAlterationsFromNBT(itemStack: ItemStack): List<SDataAlteration> =
    readAlterationRecordsFromNBT(itemStack).map { it.alteration }

/** 把修改记录写入 ItemTag（按名称覆盖，不触碰其他节点；空列表则移除整个节点） */
fun writeAlterationRecordsToTag(tag: ItemTag, records: Collection<RecordedAlteration>) {
    if (records.isEmpty()) {
        tag.remove(ALTERATION_TAG)
        return
    }
    val compound = ItemTag()
    for (record in records) {
        val alteration = record.alteration
        val entry = ItemTag()
        // Stored explicitly: the NBT key below is sanitized and cannot carry the real name.
        entry["name"] = alteration.name
        entry["key"] = alteration.key
        entry["operation"] = alteration.operation.name
        alteration.value?.let { entry.put("value", transferBooleanToByte(it) as Any) }
        if (record.persisted) entry["persisted"] = 1.toByte()
        record.previous?.let { entry.put("previous", transferBooleanToByte(it) as Any) }
        if (record.previousKnown) {
            entry["previous-known"] = 1.toByte()
            entry["previous-present"] = if (record.previousPresent) 1.toByte() else 0.toByte()
        }
        record.previousParentPath?.takeIf { it.isNotEmpty() }?.let { entry["previous-parent-path"] = it }
        record.previousParent?.let { entry.put("previous-parent", transferBooleanToByte(it) as Any) }
        if (record.previousParentPath != null) {
            entry["previous-parent-present"] = if (record.previousParentPresent) 1.toByte() else 0.toByte()
        }
        if (record.previousParentRoot) entry["previous-parent-root"] = 1.toByte()
        record.order?.let { entry["order"] = it }
        compound[alterationNbtKey(alteration.name)] = entry
    }
    tag[ALTERATION_TAG] = compound
}

/** 兼容旧签名：写入不带动折叠标记的修改列表 */
fun writeAlterationsToTag(tag: ItemTag, alterations: Collection<SDataAlteration>) {
    writeAlterationRecordsToTag(tag, alterations.map { RecordedAlteration(it) })
}

/** 直接读写物品 NBT 修改的 API，供宝石系统等外部功能使用 */
fun ItemStack.getItemAlterations(): List<SDataAlteration> = readAlterationsFromNBT(this)

fun ItemStack.getItemAlterationRecords(): List<RecordedAlteration> = readAlterationRecordsFromNBT(this)

fun ItemStack.putItemAlterationRecords(records: Collection<RecordedAlteration>) {
    val tag = this.getItemTag()
    writeAlterationRecordsToTag(tag, records)
    tag.saveTo(this)
}

fun ItemStack.putItemAlterations(alterations: Collection<SDataAlteration>) {
    putItemAlterationRecords(alterations.map { RecordedAlteration(it) })
}

/**
 * 追加/覆盖一条实例修改（按名称去重）。同名条目已折叠（persisted）时保留其
 * persisted 标记——例如同名 gem 重新镶嵌不会改变「效果已写入 NBT」的事实。
 */
fun ItemStack.addItemAlteration(alteration: SDataAlteration, persisted: Boolean = false) {
    val current = getItemAlterationRecords().filter { it.alteration.name != alteration.name }
    putItemAlterationRecords(current + RecordedAlteration(alteration, persisted))
}

/** 按名称移除一条实例修改记录（注意：已折叠进 NBT 的效果不会因此撤回） */
fun ItemStack.removeItemAlteration(name: String) {
    putItemAlterationRecords(getItemAlterationRecords().filter { it.alteration.name != name })
}

/**
 * 构建管线入口：计算「模板修改 + 实例修改」并应用到物品数据。
 *
 * 规则：
 * - 模板修改每次构建都作用于全新模板数据，天然幂等，全部应用；
 *   与实例修改同名时以模板为准（跳过同名实例条目，防止 ADD 类操作翻倍）。
 * - 实例修改中，指向 NBT 持久化目标（[PERSISTENT_ALTERATION_PREFIXES]，如
 *   `sertraline:vars`）的条目只应用**一次**：应用后打上 persisted 标记，
 *   效果随 vars 写入 `sertraline_data` NBT 永久保留；之后重建不再重放，
 *   即使移除记录也无法撤回（但记录仍保留供追溯）。
 * - 其余实例修改每次重建都重放（可撤回：移除记录后重建即还原）。
 *
 * @return 本次应写回 NBT 的完整记录列表
 */
fun applyAlterationsToTemplate(template: AbstractSItem, rebuild: ItemStack?): List<RecordedAlteration> {
    val templateAlterations = parseAlterations(template.getDeepData(ALTERATION_DATA_KEY))
    val templateNames = templateAlterations.mapTo(HashSet()) { it.name }
    val records = rebuild?.let { readAlterationRecordsFromNBT(it) } ?: emptyList()

    val applied = mutableListOf<SDataAlteration>()
    applied += templateAlterations

    val outRecords = mutableListOf<RecordedAlteration>()
    for (record in records) {
        val alteration = record.alteration
        if (alteration.name in templateNames) continue // 模板同名优先，实例条目丢弃
        when {
            // 已折叠进 NBT：效果已永久存在，不再重放，仅保留记录
            record.persisted && alteration.isPersistentTarget() -> outRecords += record
            else -> {
                applied += alteration
                // NBT 持久化目标：应用后即刻标记为已折叠——sertralineAdapter 会把
                // 修改后的 vars 写入 sertraline_data，本轮构建后效果永久生效。
                // 保存修改前快照，供宝石槽位重排时先撤回再重放。
                outRecords += if (alteration.isPersistentTarget()) {
                    val (previousPresent, previous) = readPersistentTargetState(rebuild, alteration.key)
                    val previousParent = readPersistentParentState(rebuild, alteration.key)
                    record.copy(
                        persisted = true,
                        previous = previous,
                        previousKnown = true,
                        previousPresent = previousPresent,
                        previousParentPath = previousParent?.path,
                        previousParent = previousParent?.value,
                        previousParentPresent = previousParent?.present ?: false,
                        previousParentRoot = previousParent?.path.isNullOrEmpty() && previousParent != null,
                    )
                } else record
            }
        }
    }

    if (applied.isNotEmpty()) {
        devLog("Applying ${applied.size} alteration(s) to item ${template.key}: ${applied.map { it.name }}")
        applied.applyAllTo(template.data)
    }
    // 模板修改不写入 NBT 记录（每次构建自行生效）；只有实例修改需要记录
    return outRecords
}

/**
 * 读取持久化目标当前的 NBT 值（如 `sertraline:vars.test1` -> `sertraline_data.test1`）。
 * 用于折叠修改前记录目标值。目标不存在时返回 null。
 */
fun readPersistentTargetValue(itemStack: ItemStack?, key: String): Any? {
    return readPersistentTargetState(itemStack, key).second
}

private fun readPersistentTargetState(itemStack: ItemStack?, key: String): Pair<Boolean, Any?> {
    if (itemStack == null) return false to null
    val prefix = PERSISTENT_ALTERATION_PREFIXES.firstOrNull {
        key == it || key.startsWith("$it.") || key.startsWith("$it:")
    } ?: return false to null
    val remainder = key.removePrefix(prefix).removePrefix(".").removePrefix(":")
    val tagName = when (prefix) {
        "sertraline:vars" -> "sertraline_data"
        else -> return false to null
    }
    val root = itemStack.getItemTag(true)[tagName] ?: return false to null
    if (remainder.isEmpty()) return true to root.parseNBT()
    val data = runCatching { root.parseMapNBT() }.getOrNull() ?: return false to null
    var current: Any? = data
    val segments = remainder.split(".")
    for (segment in segments.dropLast(1)) {
        val map = current as? Map<*, *> ?: return false to null
        if (segment !in map) return false to null
        current = map[segment]
    }
    val map = current as? Map<*, *> ?: return false to null
    val leaf = segments.last()
    return (leaf in map) to map[leaf]
}

internal data class PersistentParentSnapshot(val path: String, val present: Boolean, val value: Any?)

internal fun capturePersistentParentSnapshot(
    variables: Map<String, Any?>?,
    remainder: String,
): PersistentParentSnapshot? {
    if (remainder.isEmpty()) return null
    if (variables == null) return PersistentParentSnapshot("", false, null)

    var node: Map<*, *> = variables
    var path = ""
    for (segment in remainder.split(".").dropLast(1)) {
        path = if (path.isEmpty()) segment else "$path.$segment"
        if (segment !in node) return PersistentParentSnapshot(path, false, null)
        val child = node[segment]
        if (child !is Map<*, *>) return PersistentParentSnapshot(path, true, child)
        node = child
    }
    return null
}

internal fun restorePersistentMapNode(
    variables: MutableMap<String, Any?>,
    path: String,
    present: Boolean,
    value: Any?,
) {
    require(path.isNotEmpty()) { "A root node must be restored on the ItemTag directly" }
    val segments = path.split(".")
    var node = variables
    for (segment in segments.dropLast(1)) {
        val child = node[segment] as? Map<*, *>
        val mutableChild = LinkedHashMap<String, Any?>()
        child?.forEach { (key, childValue) ->
            if (key is String) mutableChild[key] = childValue
        }
        node[segment] = mutableChild
        node = mutableChild
    }
    val leaf = segments.last()
    if (present && value != null) node[leaf] = transferBooleanToByte(value)
    else node.remove(leaf)
}

private fun readPersistentParentState(itemStack: ItemStack?, key: String): PersistentParentSnapshot? {
    val prefix = PERSISTENT_ALTERATION_PREFIXES.firstOrNull {
        key == it || key.startsWith("$it.") || key.startsWith("$it:")
    } ?: return null
    val remainder = key.removePrefix(prefix).removePrefix(".").removePrefix(":")
    if (remainder.isEmpty()) return null
    val tagName = when (prefix) {
        "sertraline:vars" -> "sertraline_data"
        else -> return null
    }
    val root = itemStack?.getItemTag(true)?.get(tagName)
    if (root == null) return capturePersistentParentSnapshot(null, remainder)
    val variables = runCatching { root.parseMapNBT() }.getOrNull()
        ?: return PersistentParentSnapshot("", true, root.parseNBT())
    return capturePersistentParentSnapshot(variables, remainder)
}

/**
 * 反向撤回一条已折叠的持久化修改：直接改写 `sertraline_data` NBT。
 * - New records restore their exact pre-application value for every operation.
 * - Legacy records without snapshots use the former best-effort inverse.
 * - ADD：应用相反数
 * - REMOVE / 其他：无法可靠反推，删除该键（vars 缺省时模板默认值接管）
 */
fun revertPersistedAlteration(itemStack: ItemStack, record: RecordedAlteration) {
    val alteration = record.alteration
    val key = alteration.key
    val prefix = PERSISTENT_ALTERATION_PREFIXES.firstOrNull {
        key == it || key.startsWith("$it.") || key.startsWith("$it:")
    } ?: return
    val tagName = if (prefix == "sertraline:vars") "sertraline_data" else return
    val remainder = key.removePrefix(prefix).removePrefix(".").removePrefix(":")
    val tag = itemStack.getItemTag()

    if (record.previousParentPath != null) {
        if (record.previousParentPath.isEmpty()) {
            if (record.previousParentPresent && record.previousParent != null) {
                tag[tagName] = transferBooleanToByte(record.previousParent) as Any
            } else {
                tag.remove(tagName)
            }
        } else {
            val data = tag[tagName]?.let { root ->
                runCatching { linkedMapOf<String, Any?>().apply { putAll(root.parseMapNBT()) } }.getOrNull()
            } ?: linkedMapOf()
            restorePersistentMapNode(data, record.previousParentPath, record.previousParentPresent, record.previousParent)
            tag[tagName] = data
        }
        tag.saveTo(itemStack)
        return
    }

    // The root target represents the entire vars compound (for example MAP_CLEAR).
    // Restore its saved pre-application value instead of leaving an empty compound behind.
    if (remainder.isEmpty()) {
        if (record.previousKnown) {
            if (record.previousPresent && record.previous != null) {
                tag[tagName] = transferBooleanToByte(record.previous) as Any
            } else {
                tag.remove(tagName)
            }
            tag.saveTo(itemStack)
        }
        return
    }

    val dataTag = tag[tagName]?.asCompound() ?: ItemTag()
    val segments = remainder.split(".")

    // Walk/create the path, then apply the reverse operation at the leaf.
    var node = dataTag
    for (segment in segments.dropLast(1)) {
        val next = node[segment]?.asCompound() ?: ItemTag()
        node[segment] = next
        node = next
    }
    val leaf = segments.last()
    if (record.previousKnown) {
        if (record.previousPresent && record.previous != null) node[leaf] = transferBooleanToByte(record.previous) as Any
        else node.remove(leaf)
    } else when (alteration.operation) {
        AlterationOperation.SET -> {
            if (record.previous != null) node[leaf] = transferBooleanToByte(record.previous) as Any
            else node.remove(leaf)
        }
        AlterationOperation.ADD -> {
            val current = node[leaf]?.parseNBT() as? Number
            val delta = alteration.value as? Number
            if (current != null && delta != null) {
                val result = if (current is Double || delta is Double || current is Float || delta is Float) {
                    current.toDouble() - delta.toDouble()
                } else {
                    current.toLong() - delta.toLong()
                }
                node[leaf] = result
            } else node.remove(leaf)
        }
        else -> node.remove(leaf)
    }
    tag[tagName] = dataTag
    tag.saveTo(itemStack)
}

/** 供 ItemTagData 读取时判断节点存在性 */
fun ItemStack.hasAlterationsNBT(): Boolean = getItemTag(true)[ALTERATION_TAG] != null
