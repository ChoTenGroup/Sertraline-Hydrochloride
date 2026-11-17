package io.github.zzzyyylllty.sertraline.api

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.ExternalPluginSItem
import io.github.zzzyyylllty.sertraline.data.MinecraftSItem
import io.github.zzzyyylllty.sertraline.data.ModernSItem
import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Item API for [AbstractSItem].
 *
 * Differences from the legacy [SertralineAPI]:
 * - Parameters and return values use `AbstractSItem`, supporting Sertraline, Minecraft, and external plugin items.
 * - Keys always use [AbstractSItem.storageKey] (a bare `ITEMID` for Sertraline,
 *   `minecraft::ITEMID` for Minecraft, and `provider::ITEMID` for external plugins).
 *
 * The legacy API remains available and is only marked as deprecated.
 */
public object SertralineItemAPI {

    /**
     * Looks up an item by ID.
     *
     * Accepts both bare Sertraline IDs (`ITEMID`) and namespaced references
     * (`minecraft::stone`, `provider::id`). Returns null if a namespaced reference is not registered.
     *
     * **No fallback from a namespaced reference to a bare key is performed.** Otherwise,
     * `getItem("myprovider::gun")` could resolve to a Sertraline item named `gun`, mixing items from different sources.
     */
    public fun getItem(id: String): AbstractSItem? {
        Sertraline.itemMap[id]?.let { return it }
        val parsed = SItemFactory.parse(id) ?: return null
        return Sertraline.itemMap[parsed.storageKey]
    }

    /**
     * Resolves an item from an ItemStack.
     *
     * Prefers the **instance in the registry**; if none is found, returns the instance parsed by
     * [itemSerializer] (private items with the `__`/`private:` prefix are not registered and are only available this way).
     * Therefore, the returned item is not guaranteed to be registered; use [isRegistered] to check.
     */
    public fun getItemFromStack(itemStack: ItemStack, player: Player? = null): AbstractSItem? {
        val template = itemSerializer(itemStack, player) ?: return null
        // No bare-`key` fallback: it made `getItemFromStack` return a *different source's* item that
        // merely shared the same local id (the very cross-source mix-up `getItem` avoids).
        return Sertraline.itemMap[template.storageKey] ?: template
    }

    /** All items in the current registry (an immutable snapshot keyed by the registry's actual keys). */
    public fun getAllItems(): Map<String, AbstractSItem> =
        java.util.Collections.unmodifiableMap(java.util.HashMap(Sertraline.itemMap))

    /** Returns only Sertraline items, keyed by [AbstractSItem.storageKey] (the bare key for Sertraline). */
    public fun getSertralineItems(): Map<String, ModernSItem> =
        getAllItems().values.filterIsInstance<ModernSItem>().associateBy { it.storageKey }

    /** Returns only Minecraft items, keyed by `minecraft::$key`. */
    public fun getMinecraftItems(): Map<String, MinecraftSItem> =
        getAllItems().values.filterIsInstance<MinecraftSItem>().associateBy { it.storageKey }

    /** Returns only external plugin items, keyed by `provider::$key`. */
    public fun getExternalItems(): Map<String, ExternalPluginSItem> =
        getAllItems().values.filterIsInstance<ExternalPluginSItem>().associateBy { it.storageKey }

    /** Whether the item is registered. */
    public fun isRegistered(id: String): Boolean = getItem(id) != null

    /**
     * Registers an item.
     *
     * Uses [AbstractSItem.storageKey] as the key by default, preventing same-named items from different sources from overwriting each other.
     * When [id] is supplied explicitly, the caller is responsible for avoiding conflicts with other entries.
     */
    public fun register(item: AbstractSItem, id: String = item.storageKey) {
        val previous = Sertraline.itemMap.put(id, item)
        if (previous != null && previous !== item) {
            warningS(
                "[SertralineItemAPI] Registered item '$id' overwrote an existing entry " +
                    "(${previous.sItemType}:${previous.key}); use a distinct id if this was unintended"
            )
        }
    }

    /** Updates a registered item and returns the item it replaced. */
    public fun update(id: String, item: AbstractSItem): AbstractSItem? =
        Sertraline.itemMap.put(id, item)

    /** Removes an item and returns the removed item. */
    public fun unregister(id: String): AbstractSItem? =
        Sertraline.itemMap.remove(id)

    /** The number of currently registered items. */
    public fun getItemCount(): Int = Sertraline.itemMap.size

    /**
     * Resolves an item reference without querying the registry.
     * A bare ID resolves to a Sertraline item; `minecraft::x` / `provider::x` resolve to their respective sources.
     */
    public fun parse(reference: String): AbstractSItem? = SItemFactory.parse(reference)

    /** Builds an item, returning AIR if construction fails. */
    public fun build(item: AbstractSItem, player: Player?): ItemStack = item.buildItem(player)
}
