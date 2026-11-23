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
 * Data alteration system, providing a foundation for features such as gems that modify item data during construction.
 *
 * Data comes from two sources:
 * 1. Template definition: the `sertraline:alteration` node in the item configuration (applied on every build).
 * 2. Instance persistence: the [ALTERATION_TAG] compound in the item's NBT (restored during rebuild;
 *    for example, changes written when socketing a gem persist across rebuilds).
 *
 * NBT structure:
 * ```yaml
 * sertraline_alteration:
 *   ALTERATION_NAME:
 *     key: "sertraline:vars.test1"
 *     operation: "SET"
 *     value: 1
 * ```
 */

/** Name of the NBT node that stores instance alterations. */
const val ALTERATION_TAG = "sertraline_alteration"

/** Node in the template configuration (data namespace) that stores alteration definitions. */
const val ALTERATION_DATA_KEY = "sertraline:alteration"

/**
 * Prefixes for "NBT-persistent targets": alterations whose keys begin with these prefixes are folded directly into
 * the item's persistent NBT (for example, vars are ultimately written to `sertraline_data` by sertralineAdapter).
 * They are therefore **irreversible**: removing the record later does not remove the effect from the item.
 * The record remains in [ALTERATION_TAG] with `persisted: true` for tracing and is not replayed.
 */
val PERSISTENT_ALTERATION_PREFIXES = listOf("sertraline:vars")

/** Whether this alteration targets persistent NBT (permanent and irreversible). */
fun SDataAlteration.isPersistentTarget(): Boolean =
    PERSISTENT_ALTERATION_PREFIXES.any { key == it || key.startsWith("$it.") || key.startsWith("$it:") }

/**
 * Parses an alteration list from configuration. Supports two formats:
 *
 * Map format (the key is the alteration name):
 * ```yaml
 * sertraline:alteration:
 *   gem_ruby_3:
 *     key: "sertraline:vars.atk"
 *     operation: ADD
 *     value: 15
 * ```
 *
 * List format (the name is specified in the name field):
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
 * Parses a single alteration definition. In addition to the common key / operation / value fields,
 * supports convenience fields: `map-key` (MAP_SET / MAP_REMOVE) and `index` (LIST_REMOVE).
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
            // applyTo expects value to be an Int index for LIST_REMOVE.
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
 * Instance alteration record restored from NBT: the alteration itself and whether it has been folded into persistent NBT.
 * Entries with [persisted] set to true are retained for tracing and not replayed during construction (preventing operations such as ADD from accumulating indefinitely).
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

/** Reads persisted instance alteration records from the item's NBT ([ALTERATION_TAG], including the persisted marker). */
fun readAlterationRecordsFromNBT(itemStack: ItemStack): List<RecordedAlteration> =
    readAlterationRecords(itemStack.getItemTag(true))

internal fun readAlterationRecords(tag: ItemTag): List<RecordedAlteration> {
    val compound = tag[ALTERATION_TAG]?.asCompound() ?: return emptyList()
    return compound.entries.mapNotNull { (nbtKey, data) ->
        try {
            val map = data.parseMapNBT()
            val key = map["key"]?.toString() ?: return@mapNotNull null
            val operation = parseOperation(map["operation"])
            // The record name is stored explicitly: the NBT key it lives under is sanitized
            // (see alterationNbtKey), so it must not be used as the name. Fall back to it for
            // records written before the name field existed.
            val name = map["name"]?.toString() ?: nbtKey
            // Booleans are converted to bytes (0/1) when written to NBT; accept both representations when reading them back.
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


/** Reads instance alterations from the item's NBT, ignoring the persisted marker for backward compatibility. */
fun readAlterationsFromNBT(itemStack: ItemStack): List<SDataAlteration> =
    readAlterationRecordsFromNBT(itemStack).map { it.alteration }

/** Writes alteration records to ItemTag, replacing entries by name without touching other nodes; removes the entire node for an empty list. */
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

/** Legacy signature: writes an alteration list without persisted markers. */
fun writeAlterationsToTag(tag: ItemTag, alterations: Collection<SDataAlteration>) {
    writeAlterationRecordsToTag(tag, alterations.map { RecordedAlteration(it) })
}

/** API for directly reading and writing item NBT alterations, used by external features such as the gem system. */
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
 * Adds or replaces an instance alteration, deduplicating by name. If an existing same-named entry has been folded (persisted),
 * preserve its persisted marker; for example, socketing a gem with the same name again does not change the fact that its effect is already in NBT.
 */
fun ItemStack.addItemAlteration(alteration: SDataAlteration, persisted: Boolean = false) {
    val current = getItemAlterationRecords().filter { it.alteration.name != alteration.name }
    putItemAlterationRecords(current + RecordedAlteration(alteration, persisted))
}

/** Removes an instance alteration record by name (effects already folded into NBT are not reverted). */
fun ItemStack.removeItemAlteration(name: String) {
    putItemAlterationRecords(getItemAlterationRecords().filter { it.alteration.name != name })
}

/**
 * Build pipeline entry point: computes and applies template and instance alterations to item data.
 *
 * Rules:
 * - Apply all template alterations to fresh template data on every build; this is naturally idempotent.
 *   If a template alteration has the same name as an instance alteration, the template takes precedence and the instance entry is skipped to prevent operations such as ADD from being doubled.
 * - Apply instance alterations targeting persistent NBT ([PERSISTENT_ALTERATION_PREFIXES], such as
 *   `sertraline:vars`) **once**: mark them as persisted after applying. Their effects are permanently stored with vars in
 *   `sertraline_data` NBT and are not replayed during later rebuilds. Removing the record cannot undo them, though the record remains for tracing.
 * - Replay all other instance alterations on every rebuild. They are reversible: removing the record and rebuilding restores the original state.
 *
 * @return The complete record list to write back to NBT.
 */
fun applyAlterationsToTemplate(template: AbstractSItem, rebuild: ItemStack?): List<RecordedAlteration> =
    applyAlterationsFromTag(template, rebuild?.getItemTag(true))

internal fun applyAlterationsFromTag(template: AbstractSItem, rebuild: ItemTag?): List<RecordedAlteration> {
    val templateAlterations = parseAlterations(template.getDeepData(ALTERATION_DATA_KEY))
    val templateNames = templateAlterations.mapTo(HashSet()) { it.name }
    val records = rebuild?.let(::readAlterationRecords) ?: emptyList()

    val root = rebuild?.get("sertraline_data")
    val variables by lazy { root?.let { runCatching { it.parseMapNBT() }.getOrNull() } }
    val applied = mutableListOf<SDataAlteration>()
    applied += templateAlterations

    val outRecords = mutableListOf<RecordedAlteration>()
    for (record in records) {
        val alteration = record.alteration
        if (alteration.name in templateNames) continue // Template entry takes precedence; discard the same-named instance entry.
        when {
            // Already folded into NBT: the effect is permanent, so do not replay it; keep only the record.
            record.persisted && alteration.isPersistentTarget() -> outRecords += record
            else -> {
                applied += alteration
                // Persistent NBT target: mark it as folded immediately after applying. sertralineAdapter writes
                // the modified vars to sertraline_data, making the effect permanent after this build.
                // Save the pre-change snapshot so gem socket reordering can revert and replay it.
                outRecords += if (alteration.isPersistentTarget()) {
                    val remainder = persistentVariablePath(alteration.key).orEmpty()
                    val (previousPresent, previous) = persistentTargetState(root, variables, remainder)
                    val previousParent = when {
                        remainder.isEmpty() -> null
                        root != null && variables == null -> PersistentParentSnapshot("", true, root.parseNBT())
                        else -> capturePersistentParentSnapshot(variables, remainder)
                    }
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
    // Template alterations apply on every build and are not recorded in NBT; only instance alterations need records.
    return outRecords
}

/**
 * Reads the current NBT value at a persistent target (for example, `sertraline:vars.test1` -> `sertraline_data.test1`).
 * Used to record the target value before folding an alteration. Returns null if the target does not exist.
 */
fun readPersistentTargetValue(itemStack: ItemStack?, key: String): Any? {
    val remainder = persistentVariablePath(key) ?: return null
    val root = itemStack?.getItemTag(true)?.get("sertraline_data") ?: return null
    val variables = runCatching { root.parseMapNBT() }.getOrNull()
    return persistentTargetState(root, variables, remainder).second
}

private fun persistentVariablePath(key: String): String? {
    val prefix = PERSISTENT_ALTERATION_PREFIXES.firstOrNull {
        key == it || key.startsWith("$it.") || key.startsWith("$it:")
    } ?: return null
    return key.removePrefix(prefix).removePrefix(".").removePrefix(":")
}

private fun persistentTargetState(root: taboolib.module.nms.ItemTagData?, variables: Map<String, Any?>?, remainder: String): Pair<Boolean, Any?> {
    if (root == null) return false to null
    if (remainder.isEmpty()) return true to root.parseNBT()
    val data = variables ?: return false to null
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

/**
 * Reverses a folded persistent alteration by directly modifying `sertraline_data` NBT.
 * - New records restore their exact pre-application value for every operation.
 * - Legacy records without snapshots use the former best-effort inverse.
 * - ADD: apply the opposite value.
 * - REMOVE / other: cannot be reliably reversed, so delete the key (the template default takes over when vars is absent).
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

/** Checks whether a node exists when reading ItemTagData. */
fun ItemStack.hasAlterationsNBT(): Boolean = getItemTag(true)[ALTERATION_TAG] != null
