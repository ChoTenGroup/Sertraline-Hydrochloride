package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.data.AbstractLoreFormat
import io.github.zzzyyylllty.sertraline.data.CraftingStation
import io.github.zzzyyylllty.sertraline.data.Level
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.Tier
import io.github.zzzyyylllty.sertraline.data.Type

/**
 * Candidate registry build context for a single reload.
 *
 * During the build phase, loaders, ItemLoadEvent, and FeatureLoadEvent read and write this context's local maps
 * instead of writing directly to the live registry in [io.github.zzzyyylllty.sertraline.Sertraline].
 * After the entire build succeeds, the reload process publishes everything at once by copying it to the live maps.
 * If the build fails, the context is discarded and the live registry retains its complete previous state.
 *
 * Live fallback: getters such as [itemMap], [mappings], and [tiers] read from the live registry when a key
 * is absent from the candidate map. This supports incremental loading: entries from unchanged files remain
 * in the live maps and do not need to be parsed again during the build.
 */
class LoadContext {

    var attributeTypes: Map<String, io.github.zzzyyylllty.sertraline.attribute.data.AttributeType>? = null
    var attributeOperations: Map<String, io.github.zzzyyylllty.sertraline.attribute.data.AttributeExpression> = emptyMap()
    var attributes: io.github.zzzyyylllty.sertraline.attribute.AttributeRegistry.Snapshot? = null

    // ---- Locally added or overridden entries (candidate) ----

    val items = LinkedHashMap<String, AbstractSItem>()
    val mappings = LinkedHashMap<String, MutableList<String>>()
    val tiers = LinkedHashMap<String, Tier>()
    val types = LinkedHashMap<String, Type>()
    val levels = LinkedHashMap<String, Level>()
    val loreFormats = LinkedHashMap<String, AbstractLoreFormat>()
    val craftingStations = LinkedHashMap<String, CraftingStation>()
    val templates = LinkedHashMap<String, Map<String, Any?>>()

    /** itemCache entries written during this build (merged into the global itemCache when published). */
    val itemCache = LinkedHashMap<String, Map<String, Any?>>()

    /** Item IDs parsed during this build -> absolute source file paths (written to the live index only when published). */
    val itemSourceFiles: LinkedHashMap<String, String> = LinkedHashMap()

    /** Absolute paths of files actually parsed during incremental item loading (used to decide what to merge when publishing). */
    val parsedItemFiles = LinkedHashSet<String>()

    /** All item files seen in the workspace during this build, whether parsed or skipped; files absent from this set were deleted. */
    val seenItemFiles = LinkedHashSet<String>()

    /** Whether item loading used incremental mode in this build (determines whether live entries are merged before publishing). */
    var itemsLoadedIncrementally = false

    // ---- Live-registry fallback accessors ----

    val liveItems: Map<String, AbstractSItem> get() = io.github.zzzyyylllty.sertraline.Sertraline.itemMap
    val liveMappings: Map<String, List<String>?> get() = io.github.zzzyyylllty.sertraline.Sertraline.mappings
    val liveTiers: Map<String, Tier> get() = io.github.zzzyyylllty.sertraline.Sertraline.tiers
    val liveTypes: Map<String, Type> get() = io.github.zzzyyylllty.sertraline.Sertraline.types
    val liveLevels: Map<String, Level> get() = io.github.zzzyyylllty.sertraline.Sertraline.levels
    val liveLoreFormats: Map<String, AbstractLoreFormat> get() = io.github.zzzyyylllty.sertraline.Sertraline.loreFormats
    val liveCraftingStations: Map<String, CraftingStation> get() = io.github.zzzyyylllty.sertraline.Sertraline.craftingStations
    val liveTemplates: Map<String, Map<String, Any?>> get() = TemplateManager.getAllTemplates()

    /**
     * Publishes candidate contents to the live registry, replacing it as a whole.
     *
     * Publishing runs inside ReloadCoordinator's serial gate, so another reload cannot enter concurrently.
     * loreFormats retains its existing whole-reference replacement semantics: packet readers keep the old reference
     * until their next read, and never observe a partially populated map.
     */
    fun publishToLive() {
        val s = io.github.zzzyyylllty.sertraline.Sertraline
        // Build every new map *first*, then swap the references one by one, with `itemMap` LAST.
        // The eight assignments are not a single atomic transaction (that would need one immutable
        // state object), but publishing the item table last means a reader that already sees the new
        // items is guaranteed to see the new mappings / loreFormats / tiers … that those items were
        // built against. Publishing items first (the old order) exposed items whose ids were not yet
        // resolvable through the aux tables.
        val newMappings = java.util.concurrent.ConcurrentHashMap<String, List<String>?>()
        for ((k, v) in mappings) newMappings[k] = v.toList()
        val newTiers = java.util.concurrent.ConcurrentHashMap(tiers)
        val newTypes = java.util.concurrent.ConcurrentHashMap(types)
        val newLevels = java.util.concurrent.ConcurrentHashMap(levels)
        val newStations = java.util.concurrent.ConcurrentHashMap(craftingStations)
        val newLoreFormats = java.util.concurrent.ConcurrentHashMap<String, AbstractLoreFormat>(loreFormats)
        // Atomically replace the global cache with the itemCache entries written during this build, like the fields above.
        // Create it through the factory so it remains a bounded Caffeine cache (Caffeine is injected at LifeCycle.INIT, before this point).
        val newItemCache = s.newItemCacheMap()
        newItemCache.putAll(itemCache)

        attributeTypes?.let { io.github.zzzyyylllty.sertraline.attribute.AttributeRegistry.publishConfig(it, attributeOperations) }
        s.mappings = newMappings
        s.tiers = newTiers
        s.types = newTypes
        s.levels = newLevels
        s.craftingStations = newStations
        s.loreFormats = newLoreFormats
        TemplateManager.publishTemplates(templates)
        s.itemCache = newItemCache
        // Align the source index with the published itemMap, removing stale records for deleted entries,
        // and add paths parsed during this build. Candidate building no longer touches the live index.
        s.itemSourceFile.putAll(itemSourceFiles)
        s.itemSourceFile.keys.retainAll(items.keys)
        // Publish itemMap last so readers that see new items have already seen all related auxiliary maps.
        s.itemMap = java.util.concurrent.ConcurrentHashMap(items)
    }
}
