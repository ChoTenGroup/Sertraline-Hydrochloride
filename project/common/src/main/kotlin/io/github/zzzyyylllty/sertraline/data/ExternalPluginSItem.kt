package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.external.ExternalItemData
import io.github.zzzyyylllty.sertraline.util.ExternalItemHelper
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack


/**
 * External plugin item (such as ItemsAdder, Oraxen, Nexo, or CraftEngine).
 *
 * The base item is built through ItemBridge using [ExternalItemData]; no Bukkit Material is fabricated.
 */
data class ExternalPluginSItem(

    override val key: String,

    override val sItemType: String = "plugin",
    override val data: LinkedHashMap<String, Any?> = linkedMapOf(),
    override val config: LinkedHashMap<String, Any?> = linkedMapOf(),

    val externalItemData: ExternalItemData,
) : AbstractSItem {

    /** Combines the provider and item ID to prevent collisions between same-named items from different providers. */
    override val storageKey: String
        get() = "${externalItemData.source}::$key"

    /** Runtime policy assigned by SItemFactory; not serialized. */
    @Transient
    override var shouldAdapt: Boolean = true

    /**
     * An external item is not guaranteed to have a corresponding Bukkit [Material].
     * Try building it once without a Player and read its material; return [Material.AIR] on failure or when the provider is not installed.
     */
    override fun getMaterial(): Material {
        return runCatching { buildBaseItem(null)?.type }.getOrNull() ?: Material.AIR
    }

    private fun buildBaseItem(player: Player?): ItemStack? {
        val source = externalItemData.source
        val id = externalItemData.id.ifEmpty { key }
        return runCatching {
            if (player != null) ExternalItemHelper.build(player, source, id)
            else ExternalItemHelper.buildNoPlayer(source, id)
        }.getOrNull()
    }

    override fun deepCopy(): ExternalPluginSItem = ExternalPluginSItem(
        key = key,
        sItemType = sItemType,
        data = deepCopyMap(data),
        config = deepCopyMap(config),
        // Deep-copied as well: `metaData` is a mutable map, and sharing it meant edits on one copy
        // (a build override, an API tweak) leaked into the live registry template.
        externalItemData = externalItemData.deepCopy()
    ).also { it.shouldAdapt = shouldAdapt }

    override fun buildItem(player: Player?): ItemStack {
        val base = buildBaseItem(player) ?: return ItemStack(Material.AIR)
        if (base.type == Material.AIR) return base
        return Sertraline.itemManager.processItem(this, base, player)
    }
}
