package io.github.zzzyyylllty.sertraline.util.loreformat

import io.github.zzzyyylllty.sertraline.Sertraline.loreFormats
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.data.AbstractLoreFormat
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * A LoreFormat utility class callable from JS.
 *
 * Used from JavaScript via Java.type:
 * ```js
 * var LoreFormatUtil = Java.type("io.github.zzzyyylllty.sertraline.util.loreformat.LoreFormatUtil");
 * var lore = LoreFormatUtil.generateLore(itemStack, sItem, "example_lore_format", player);
 * var replaced = LoreFormatUtil.applyLore(itemStack, sItem, "example_lore_format", player);
 * ```
 */
object LoreFormatUtil {

    /**
     * Generates a lore component list using the specified lore format name.
     *
     * @param item        The item ItemStack to generate lore for
     * @param sItem       The item's AbstractSItem data
     * @param format      The lore format name (corresponds to a config under the lore-formats directory)
     * @param player      The player (used for placeholder resolution, optional)
     * @param defaultVars Extra default data, merged into the item's vars for placeholder resolution
     * @return The generated lore Component list, or null if the format does not exist
     */
    @JvmStatic
    fun generateLore(
        item: ItemStack,
        sItem: AbstractSItem,
        format: String,
        player: Player? = null,
        defaultVars: Map<String, Any?>? = null,
    ): List<Component>? {
        val loreFormat = loreFormats[format] ?: return null
        if (defaultVars != null) {
            // When injecting defaultVars, operate on a deep copy of sItem to avoid polluting the shared template instance
            // (itemSerializer returns the same instance from itemMap for items without placeholders/dynamic data,
            //   so calling setDeepData directly would pollute concurrent calls from other players/threads)
            val working = sItem.deepCopy()
            working.setDeepData("sertraline:vars", defaultVars)
            return applyLoreFormat(working, player, PlatformCompat.getLore(item), loreFormat, currentItem = item)
        }
        return applyLoreFormat(sItem, player, PlatformCompat.getLore(item), loreFormat, currentItem = item)
    }

    /**
     * Generates lore using the specified lore format name and sets it directly on the ItemStack.
     *
     * @param item        The item ItemStack to modify
     * @param sItem       The item's AbstractSItem data
     * @param format      The lore format name
     * @param player      The player (used for placeholder resolution, optional)
     * @param defaultVars Extra default data, merged into the item's vars for placeholder resolution
     * @return The modified ItemStack (or the original item if the format does not exist)
     */
    @JvmStatic
    fun applyLore(
        item: ItemStack,
        sItem: AbstractSItem,
        format: String,
        player: Player? = null,
        defaultVars: Map<String, Any?>? = null,
    ): ItemStack {
        val lore = generateLore(item, sItem, format, player, defaultVars) ?: return item
        PlatformCompat.setLore(item, lore)
        return item
    }

    /**
     * Gets a loaded Lore format object (plain or switchable).
     *
     * @param format The lore format name
     * @return The format object, or null if it does not exist
     */
    @JvmStatic
    fun getFormat(format: String): AbstractLoreFormat? = loreFormats[format]

    /**
     * Gets the names of all loaded lore formats.
     */
    @JvmStatic
    fun getFormatNames(): Set<String> = loreFormats.keys
}
