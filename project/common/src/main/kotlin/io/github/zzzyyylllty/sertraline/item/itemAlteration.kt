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
    /**
     * Value of the target before a folded SET was applied. Only recorded for
     * persistent-target SET alterations, so unsocketing can restore the original
     * value into `sertraline_data`. Absent for other operations (ADD is reversed
     * by applying the negated value; REMOVE clears the key).
     */
    val previous: Any? = null,
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
            RecordedAlteration(SDataAlteration(name, key, operation, map["value"]), persisted, map["previous"])
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
                // SET 到持久化目标时额外记录原值，供拆卸时恢复。
                outRecords += if (alteration.isPersistentTarget()) {
                    val previous = if (alteration.operation == AlterationOperation.SET && record.previous == null) {
                        readPersistentTargetValue(rebuild, alteration.key)
                    } else record.previous
                    record.copy(persisted = true, previous = previous)
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
 * 用于 SET 折叠前记录原值。目标不存在时返回 null。
 */
fun readPersistentTargetValue(itemStack: ItemStack?, key: String): Any? {
    if (itemStack == null) return null
    val prefix = PERSISTENT_ALTERATION_PREFIXES.firstOrNull {
        key == it || key.startsWith("$it.") || key.startsWith("$it:")
    } ?: return null
    val remainder = key.removePrefix(prefix).removePrefix(".").removePrefix(":")
    val tagName = when (prefix) {
        "sertraline:vars" -> "sertraline_data"
        else -> return null
    }
    val root = itemStack.getItemTag(true)[tagName] ?: return null
    if (remainder.isEmpty()) return root.parseNBT()
    val data = root.parseMapNBT().toMutableMap()
    var current: Any? = data
    val segments = remainder.split(".")
    for (segment in segments.dropLast(1)) {
        current = (current as? Map<*, *>)?.get(segment) ?: return null
    }
    return (current as? Map<*, *>)?.get(segments.last())
}

/**
 * 反向撤回一条已折叠的持久化修改：直接改写 `sertraline_data` NBT。
 * - SET：恢复 [RecordedAlteration.previous]（无原值则删除该键）
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
    if (remainder.isEmpty()) return

    val tag = itemStack.getItemTag()
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
    when (alteration.operation) {
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
