package io.github.zzzyyylllty.sertraline.item.adapter

import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.util.ComplexTypeHelper
import io.github.zzzyyylllty.sertraline.util.parseNamespacedKey
import io.github.zzzyyylllty.sertraline.util.loreformat.performPlaceholders
import io.github.zzzyyylllty.sertraline.util.minimessage.toComponent
import io.github.zzzyyylllty.sertraline.util.toBooleanTolerance
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import com.cryptomorin.xseries.XItemStack
import kotlin.math.roundToInt

fun xbuilderAdapter(item: ItemStack, sItem: AbstractSItem, player: Player?): ItemStack {

    val configMap = (sItem.config["xbuilder"] as Map<String, Any?>?)?.toMutableMap() ?: return item

    val orgItemType = item.type

    for (entry in XItemStack.serialize(item)) {
        configMap.let { it[entry.key] = entry.value }
    }

    val xDeserialized = configMap.let { XItemStack.deserializer().fromConfig(it) }

    // Obtain the ItemMeta from the deserialization result
    val deserializedMeta = xDeserialized.deserialize().itemMeta

    val prefix = "xbuilder"
    val name = sItem.getDeepData("$prefix:name")?.toString()?.toComponent()
    val model = sItem.getDeepData("$prefix:item-model")?.toString()
    val lore = run {
        val get = sItem.getDeepData("$prefix:lore")
        val list = get.asListEnhanced() ?: return@run null
        val retList : MutableList<Component> = mutableListOf()
        list.asListEnhanced()?.performPlaceholders(sItem, player)?.forEach { it.toComponent()?.let { element -> retList.add(element) } }
        retList
    }

    // Prefer the deserialized meta's content, but preserve the original meta's custom content
    if (deserializedMeta != null) {
        if (name != null) {
            PlatformCompat.setDisplayName(deserializedMeta, name)
        }
        if (lore != null) {
            PlatformCompat.setLore(deserializedMeta, lore)
        }
        if (model != null && SertralineSettings.fixXBuilderReapplyItemModel) {
            PlatformCompat.setItemModel(deserializedMeta, model.parseNamespacedKey())
        }
        item.itemMeta = deserializedMeta
    } else {
        // If there is no deserialized meta, keep the original item's Meta as much as possible and update the display name and lore
        item.itemMeta?.let { meta ->
            if (name != null) PlatformCompat.setDisplayName(meta, name)
            if (lore != null) PlatformCompat.setLore(meta, lore)
            if (model != null && SertralineSettings.fixXBuilderReapplyItemModel) PlatformCompat.setItemModel(meta, model.parseNamespacedKey())
            item.itemMeta = meta
        }
    }
    // Keep the original item's Material type
    item.type = orgItemType

    // Return the ItemStack with the modified meta
    return item
}



