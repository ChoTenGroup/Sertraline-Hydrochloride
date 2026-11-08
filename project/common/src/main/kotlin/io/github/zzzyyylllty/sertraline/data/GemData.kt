package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.toBooleanTolerance
import java.util.concurrent.ConcurrentHashMap

// ---------- Gem slot / gem definitions ----------

/** Definition of a single gem socket declared under `sertraline:gem-slots`. */
data class GemSlotDefinition(
    val id: String,
    val name: String,
    val desc: String,
    val colorPattern: Regex?, // null = accepts every gem color
    /** Whether this socket may be filled by the click interaction (`click: false` to forbid). */
    val clickSocket: Boolean = true,
) {
    fun accepts(color: String): Boolean = colorPattern?.matches(color) ?: true
}

/** Definition of the gem behaviour declared under `sertraline:gem` of a gem item. */
data class GemDefinition(
    val color: String,
    val meta: Map<String, Any?>,
    val modifyData: Map<String, Any?>, // raw nested map, e.g. {"sertraline": {"vars": {"test1": "+1"}}}
    /** Whether this gem may be socketed by the click interaction (`click: false` to forbid). */
    val clickSocket: Boolean = true,
)

/**
 * Tolerant boolean read for gem config flags: `true/false`, `yes/no`, `on/off`, `1/0`,
 * `enable/disable`, `allow/deny`. Missing or unparsable values fall back to [default].
 */
internal fun Any?.gemFlag(default: Boolean = true): Boolean = when (this) {
    null -> default
    is String -> when (val token = trim().lowercase()) {
        "true", "yes", "on", "enable", "enabled", "allow", "allowed" -> true
        "false", "no", "off", "disable", "disabled", "deny", "denied" -> false
        // numeric spellings such as "1" / "0"
        else -> token.toDoubleOrNull()?.let { it > 0.0 } ?: default
    }
    else -> toBooleanTolerance()
}

/** Reads `click` / `click-socket` from a gem or slot definition. */
private fun Map<*, *>.clickFlag(): Boolean = (this["click"] ?: this["click-socket"]).gemFlag(true)

/**
 * Item-wide switch for the click interaction: `sertraline:gem-click: false` forbids click
 * socketing for **every** socket of the item. Per-socket `click: false` narrows it further.
 */
fun AbstractSItem.isClickSocketEnabled(): Boolean =
    (getDeepData("sertraline:gem-click") ?: getDeepData("sertraline:gem-click-socket")).gemFlag(true)

/** Reads the gem definition of a Sertraline item template, or null when the item is not a gem. */
fun AbstractSItem.getGemDefinition(): GemDefinition? {
    val raw = getDeepData("sertraline:gem") as? Map<*, *> ?: return null
    val color = raw["color"]?.toString() ?: return null
    @Suppress("UNCHECKED_CAST")
    val meta = (raw["meta"] as? Map<String, Any?>) ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val modifyData = (raw["modify-data"] as? Map<String, Any?>) ?: emptyMap()
    return GemDefinition(color, meta, modifyData, raw.clickFlag())
}

// ---------- gem-color pattern cache ----------

/**
 * Compiled `gem-color` patterns, keyed by the raw config string so every item sharing a
 * pattern only compiles it once (this used to recompile the regex on every single build).
 */
private val gemColorPatternCache = ConcurrentHashMap<String, Regex>()

/** Raw patterns that failed to compile; remembered so the warning is logged exactly once. */
private val invalidGemColorPatterns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/**
 * Pattern that never matches. Used when `gem-color` is not a valid regex: a broken pattern is
 * a configuration error, and letting it fall open would accept *every* gem into the socket,
 * so the socket fails closed instead and the admin gets a warning.
 */
private val NEVER_MATCHES_COLOR = Regex("(?!)")

/** Slot ids are used verbatim as NBT keys; `ItemTag` treats `.` as a nesting separator. */
private val INVALID_GEM_SLOT_CHARS = charArrayOf('.', '[', ']', '/', '\\')

/** Slot ids already reported as invalid, so a build-heavy server does not spam the log. */
private val warnedGemSlotIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

private fun gemColorPattern(raw: String, slotId: String): Regex {
    gemColorPatternCache[raw]?.let { return it }
    if (raw in invalidGemColorPatterns) return NEVER_MATCHES_COLOR
    val compiled = runCatching { Regex(raw) }.getOrNull()
    if (compiled == null) {
        invalidGemColorPatterns += raw
        warningS(
            "Invalid `gem-color` regex '$raw' on gem socket '$slotId': the socket now rejects every gem. " +
                "Fix the pattern in the item definition."
        )
        return NEVER_MATCHES_COLOR
    }
    gemColorPatternCache[raw] = compiled
    return compiled
}

/** Reads the gem slot definitions of a Sertraline item template. Empty when the item has no sockets. */
fun AbstractSItem.getGemSlotDefinitions(): Map<String, GemSlotDefinition> {
    val raw = getDeepData("sertraline:gem-slots") as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (key, value) ->
        val id = key?.toString() ?: return@mapNotNull null
        val map = value as? Map<*, *> ?: return@mapNotNull null
        // The slot id is written straight into the `sertraline_gem.used` / `.slots` NBT nodes,
        // and `ItemTag.set` splits on `.`, so such an id would create a nested compound that
        // can never be read back: the slot would stay occupied and the gem would be lost.
        if (id.isBlank() || id.any { it in INVALID_GEM_SLOT_CHARS }) {
            if (warnedGemSlotIds.add("$key|$id")) {
                warningS(
                    "Gem socket id '$id' of item '$key' is not a valid NBT key " +
                        "(blank, or contains one of . [ ] / \\\\) and was ignored. Rename the socket in the item definition."
                )
            }
            return@mapNotNull null
        }
        val pattern = map["gem-color"]?.toString()?.takeIf { it.isNotBlank() }?.let { gemColorPattern(it, id) }
        id to GemSlotDefinition(
            id = id,
            name = map["name"]?.toString() ?: id,
            desc = map["desc"]?.toString() ?: "",
            colorPattern = pattern,
            clickSocket = map.clickFlag(),
        )
    }.toMap(LinkedHashMap())
}

// ---------- modify-data -> SDataAlteration compilation ----------

/** Prefix of every alteration generated by the gem system: `gem_<slotId>_<flattened path>`. */
const val GEM_ALTERATION_PREFIX = "gem_"

/** Builds the alteration name prefix used by one slot, for batch removal on unsocket. */
fun gemAlterationSlotPrefix(slotId: String): String = "$GEM_ALTERATION_PREFIX${slotId}_"

/**
 * Resolves which socket an alteration name belongs to.
 *
 * `gem_<slotId>_<path>` is ambiguous when one socket id is a prefix of another (`slot` vs
 * `slot_red`), so the owner is the **longest** matching slot id — never a plain `startsWith`,
 * which would let unsocketing `slot` delete (and stat-revert) `slot_red`'s alterations while
 * leaving `slot_red` marked as occupied.
 */
fun gemAlterationOwner(alterationName: String, knownSlotIds: Collection<String>): String? =
    knownSlotIds
        .filter { alterationName.startsWith(gemAlterationSlotPrefix(it)) }
        .maxByOrNull { it.length }

/**
 * Compiles a gem `modify-data` tree into alterations for [slotId].
 *
 * Value semantics per leaf:
 * - `"+n"` / `"-n"` strings -> [AlterationOperation.ADD] with n / -n (Int or Double)
 * - operation shorthands: `"set:v"`, `"add:v"`, `"remove"`, `"list_add:v"`, `"list_add_last:v"`,
 *   `"list_remove:i"`, `"list_remove_value:v"`, `"list_remove_all"`, `"list_clear"`,
 *   `"map_set:k=v"`, `"map_remove:k"`, `"map_clear"` (case / `-` / `_` insensitive)
 * - anything else -> [AlterationOperation.SET] with the literal value
 *
 * Nested maps are flattened into deep keys. The first level is the item data
 * namespace, so the flattened key keeps its `:` separator: `sertraline` + `vars`
 * + `test1` becomes `sertraline:vars.test1` (never `sertraline.vars.test1`, which
 * [SDataAlteration] would read as a single top-level key and silently ignore).
 * Flat keys that already contain `:` are used as-is.
 */
fun compileGemAlterations(slotId: String, modifyData: Map<String, Any?>): List<SDataAlteration> {
    val leaves = LinkedHashMap<String, Any?>()
    flattenGemModifyData(modifyData, "", leaves)
    return leaves.mapNotNull { (rawPath, rawValue) ->
        val path = normalizeGemDataKey(rawPath)
        // A malformed leaf (e.g. `list_remove:first`) is skipped instead of silently turning
        // into index 0 and deleting an unrelated list element.
        val (operation, value) = parseGemLeafValue(rawPath, rawValue) ?: return@mapNotNull null
        SDataAlteration(
            name = "${gemAlterationSlotPrefix(slotId)}$path",
            key = path,
            operation = operation,
            value = value,
        )
    }
}

/**
 * Normalizes a flattened `modify-data` path into an item data key.
 *
 * `sertraline.vars.atk` -> `sertraline:vars.atk`; keys that already carry a
 * namespace (`sertraline:vars.atk`, `xbuilder:name`) are returned unchanged.
 */
internal fun normalizeGemDataKey(path: String): String {
    if (path.contains(':')) return path
    val dot = path.indexOf('.')
    if (dot <= 0) return path
    return path.substring(0, dot) + ":" + path.substring(dot + 1)
}

private fun flattenGemModifyData(map: Map<String, Any?>, prefix: String, out: MutableMap<String, Any?>) {
    for ((key, value) in map) {
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            flattenGemModifyData(value as Map<String, Any?>, path, out)
        } else {
            out[path] = value
        }
    }
}

/**
 * Parses one leaf value of `modify-data` into an operation + payload.
 *
 * Returns null when the leaf is malformed (currently: `list_remove:` without a numeric
 * index). Such a leaf is dropped with a warning rather than defaulting to index 0.
 */
private fun parseGemLeafValue(path: String, raw: Any?): Pair<AlterationOperation, Any?>? {
    if (raw is String) {
        val trimmed = raw.trim()
        // Relative numeric shorthand, e.g. "+1" / "-2.5"
        if (trimmed.length > 1 && (trimmed.startsWith("+") || trimmed.startsWith("-"))) {
            trimmed.toIntOrNull()?.let { return AlterationOperation.ADD to it }
            trimmed.toDoubleOrNull()?.let { return AlterationOperation.ADD to it }
        }
        // Explicit operation shorthand, e.g. "list_add:last"
        val split = trimmed.indexOf(':')
        val opToken = if (split >= 0) trimmed.substring(0, split) else trimmed
        val op = runCatching {
            AlterationOperation.valueOf(opToken.uppercase().replace('-', '_'))
        }.getOrNull()
        if (op != null) {
            val payload = if (split >= 0) trimmed.substring(split + 1) else null
            return when (op) {
                // Payload-less operations
                AlterationOperation.REMOVE, AlterationOperation.LIST_REMOVE_ALL,
                AlterationOperation.LIST_CLEAR, AlterationOperation.MAP_CLEAR,
                -> op to null
                // LIST_REMOVE requires an Int index; a non-numeric payload is a config error.
                AlterationOperation.LIST_REMOVE -> {
                    val index = payload?.trim()?.toIntOrNull()
                    if (index == null) {
                        warningS(
                            "Gem modify-data '$path' uses 'list_remove' with a non-numeric index " +
                                "('${payload ?: ""}'); the entry was ignored."
                        )
                        null
                    } else {
                        op to index
                    }
                }
                // MAP_SET payload: "k=v"
                AlterationOperation.MAP_SET -> {
                    val eq = payload?.indexOf('=') ?: -1
                    if (payload == null || eq < 0) op to null
                    else op to mapOf(
                        "key" to payload.substring(0, eq),
                        "value" to parseGemScalar(payload.substring(eq + 1)),
                    )
                }
                // MAP_REMOVE payload: key string
                AlterationOperation.MAP_REMOVE -> op to payload
                else -> op to parseGemScalar(payload)
            }
        }
    }
    return AlterationOperation.SET to raw
}

/** Converts a scalar string payload to Int/Double/Boolean when possible, keeps the string otherwise. */
private fun parseGemScalar(raw: String?): Any? {
    if (raw == null) return null
    raw.toIntOrNull()?.let { return it }
    raw.toDoubleOrNull()?.let { return it }
    if (raw.equals("true", true)) return true
    if (raw.equals("false", true)) return false
    return raw
}
