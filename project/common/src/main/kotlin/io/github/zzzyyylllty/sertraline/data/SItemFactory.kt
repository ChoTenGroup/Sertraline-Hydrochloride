package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.data.external.ExternalItemData
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.Material

/**
 * The single entry point for creating and restoring items.
 *
 * All paths that construct an `AbstractSItem` must go through this factory because:
 * 1. Gson does not select an implementation based on `sItemType`; polymorphic restoration must dispatch explicitly.
 * 2. `shouldAdapt` is a `@Transient` runtime policy that has the JVM default value after deserialization,
 *    so this factory must reassign it based on the source.
 */
object SItemFactory {

    const val TYPE_SERTRALINE = "sertraline"
    const val TYPE_MINECRAFT = "minecraft"
    const val TYPE_EXTERNAL = "plugin"

    /** Treat an item without a namespace prefix as a Sertraline item. */
    private const val NAMESPACE_SEPARATOR = "::"

    private val minecraftAliases = setOf("minecraft", "mc", "vanilla")
    private val sertralineAliases = setOf("sertraline", "depazitems", "depaz")

    // ---------------------------------------------------------------- Creation

    /** Creates a Sertraline item. */
    fun sertraline(
        key: String,
        data: LinkedHashMap<String, Any?> = linkedMapOf(),
        config: LinkedHashMap<String, Any?> = linkedMapOf(),
        shouldAdapt: Boolean = true,
    ): ModernSItem = ModernSItem(key, data, config).also { it.shouldAdapt = shouldAdapt }

    /** Creates a vanilla Minecraft item. */
    fun minecraft(
        key: String,
        minecraftId: String,
        data: LinkedHashMap<String, Any?> = linkedMapOf(),
        config: LinkedHashMap<String, Any?> = linkedMapOf(),
        shouldAdapt: Boolean = false,
    ): MinecraftSItem = MinecraftSItem(key, minecraftId, TYPE_MINECRAFT, data, config)
        .also { it.shouldAdapt = shouldAdapt }

    /** Creates an external plugin item. */
    fun external(
        externalItemData: ExternalItemData,
        data: LinkedHashMap<String, Any?> = linkedMapOf(),
        config: LinkedHashMap<String, Any?> = linkedMapOf(),
        shouldAdapt: Boolean = true,
    ): ExternalPluginSItem = ExternalPluginSItem(
        key = externalItemData.id,
        sItemType = TYPE_EXTERNAL,
        data = data,
        config = config,
        externalItemData = externalItemData
    ).also { it.shouldAdapt = shouldAdapt }

    // ---------------------------------------------------------------- Parsing

    /** Item source category shared by parsers to avoid maintaining duplicate alias sets. */
    enum class SourceKind { SERTRALINE, MINECRAFT, EXTERNAL }

    /**
     * Determines the source category from a namespace.
     *
     * This is the project's single namespace alias table. `OmniItem`, `itemSource`, and recipe parsing should all use it
     * instead of maintaining separate `minecraft/mc/vanilla` lists.
     */
    fun classify(namespace: String?): SourceKind {
        // Allow null: Gson can bypass Kotlin's non-null checks and assign null to the field, so handle it here instead of throwing an NPE.
        val lowered = namespace?.trim()?.lowercase() ?: return SourceKind.EXTERNAL
        return when (lowered) {
            in sertralineAliases -> SourceKind.SERTRALINE
            in minecraftAliases -> SourceKind.MINECRAFT
            else -> SourceKind.EXTERNAL
        }
    }

    /**
     * Resolves an `sItemType::id` reference into a concrete item.
     *
     * - A bare ID without `::` is treated as a Sertraline item (preserving existing semantics).
     * - `minecraft::stone` resolves to a vanilla item.
     * - `provider::id` resolves to an external plugin item.
     *
     * Parses the reference structure only; the caller determines whether the item is registered.
     */
    fun parse(reference: String): AbstractSItem? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return null

        val index = trimmed.indexOf(NAMESPACE_SEPARATOR)
        if (index < 0) return sertraline(trimmed)

        val namespace = trimmed.substring(0, index).trim().lowercase()
        val localId = trimmed.substring(index + NAMESPACE_SEPARATOR.length).trim()
        if (localId.isEmpty()) return null

        return when {
            namespace.isEmpty() -> null
            namespace in sertralineAliases -> sertraline(localId)
            namespace in minecraftAliases -> minecraft(localId, localId.uppercase())
            else -> external(ExternalItemData(source = namespace, id = localId))
        }
    }

    // ---------------------------------------------------------------- Restoration

    /**
     * Restores an item from JSON.
     *
     * Older data has no `sItemType` field, so restore it as [ModernSItem] to keep existing databases and caches readable.
     *
     * All restored objects go through [validate]. Gson populates fields by reflection and **does not call Kotlin constructors or evaluate default expressions**,
     * so missing `data` / `config` / `externalItemData` fields in JSON become `null` and bypass non-null checks.
     * Without validation, malformed data would cause an NPE on the first access to `storageKey` / `hasPlaceholders`.
     */
    fun deserialize(json: String?): AbstractSItem? {
        if (json.isNullOrBlank()) return null

        val root = runCatching {
            jsonUtils.fromJson(json, com.google.gson.JsonObject::class.java)
        }.getOrNull() ?: return null

        // Dispatch must ignore case and surrounding whitespace, just like classify/parse; otherwise, "Minecraft" would
        // silently fall back to ModernSItem and permanently lose minecraftId.
        val type = runCatching {
            root.get("sItemType")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()
        }.getOrNull()

        val key = runCatching {
            root.get("key")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

        if (type != null && type !in setOf(TYPE_SERTRALINE, TYPE_MINECRAFT, TYPE_EXTERNAL)) {
            warningS("[SItemFactory] Unknown sItemType '$type' for item '$key', falling back to $TYPE_SERTRALINE")
        }

        val item: AbstractSItem = runCatching {
            when (type) {
                TYPE_MINECRAFT -> jsonUtils.fromJson(json, MinecraftSItem::class.java)
                TYPE_EXTERNAL -> jsonUtils.fromJson(json, ExternalPluginSItem::class.java)
                else -> jsonUtils.fromJson(json, ModernSItem::class.java)
            }
        }.getOrNull() ?: run {
            warningS("[SItemFactory] Failed to deserialize item '$key'")
            return null
        }

        // `validate` used to run *outside* any guard. Gson fills missing fields with null even for
        // non-null Kotlin types, so `item.minecraftId.isBlank()` threw an NPE that escaped
        // `deserialize()` instead of the item being skipped with a warning — one malformed JSON
        // aborted the whole load/build path. Everything touching potentially-null Gson state is
        // inside the guard now.
        val validated = runCatching { validate(item) }.getOrElse { error ->
            warningS(
                "[SItemFactory] Item '$key' is not recoverable " +
                    "(${error.javaClass.simpleName}: ${error.message}), skipping"
            )
            null
        } ?: return null

        return runCatching { restoreRuntimeState(validated) }.getOrElse { error ->
            warningS("[SItemFactory] Failed to restore runtime state of '$key' (${error.message}), skipping")
            null
        }
    }

    /**
     * Restores non-null constraints skipped by Gson's reflection-based deserialization.
     *
     * Returning null means the data cannot be restored; callers should skip it instead of adding it to the registry.
     */
    private fun validate(item: AbstractSItem): AbstractSItem? {
        if (item.data == null || item.config == null) {
            warningS("[SItemFactory] Item '${item.key}' has null data/config, skipping")
            return null
        }
        // `key` is the registry key. A null/blank one used to be accepted and produced an
        // unreachable entry (itemMap[null]) instead of a skipped item.
        if (item.key.let { it.isBlank() }) {
            warningS("[SItemFactory] Item with a null/blank key is not recoverable, skipping")
            return null
        }
        when (item) {
            is ExternalPluginSItem -> {
                val external = item.externalItemData
                if (external == null || external.source.isNullOrBlank() || external.id.isNullOrBlank()) {
                    warningS("[SItemFactory] External item '${item.key}' has incomplete externalItemData, skipping")
                    return null
                }
            }
            is MinecraftSItem -> {
                // Null-tolerant on purpose: Gson may have written null into this non-null field.
                if (item.minecraftId.isNullOrBlank()) {
                    warningS("[SItemFactory] Minecraft item '${item.key}' has a null/blank minecraftId, skipping")
                    return null
                }
            }
            else -> Unit
        }
        return item
    }

    /** Restores runtime state for an already constructed object, such as an instance from the cache. */
    fun restoreRuntimeState(item: AbstractSItem): AbstractSItem {
        when (item) {
            is MinecraftSItem -> {
                item.shouldAdapt = false
                // `shouldAdapt = false` means the whole Adapter pipeline (lore / visual / name) is
                // skipped, so any presentation config on a vanilla item would be silently ignored.
                // Say so instead of letting the author wonder why nothing renders.
                val hasPresentationConfig = item.config.containsKey("visual") ||
                    item.config.containsKey("lore") ||
                    item.data.containsKey("xbuilder") ||
                    item.data.containsKey("minecraft")
                if (hasPresentationConfig) {
                    warningS(
                        "[SItemFactory] Minecraft item '${item.key}' declares visual/lore/xbuilder data but " +
                            "vanilla items never enter the Adapter pipeline; those keys are ignored"
                    )
                }
            }
            is ExternalPluginSItem -> item.shouldAdapt = true
            is ModernSItem -> item.shouldAdapt = true
            // No `else` used to be a silent hole for any future AbstractSItem implementation: it
            // kept the JVM default and never entered the Adapter pipeline without any diagnostic.
            else -> warningS(
                "[SItemFactory] Unknown AbstractSItem implementation '${item.javaClass.name}' for " +
                    "'${item.key}'; shouldAdapt was left unchanged"
            )
        }
        return item
    }
}
