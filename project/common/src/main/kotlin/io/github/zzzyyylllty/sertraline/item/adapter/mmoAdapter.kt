package io.github.zzzyyylllty.sertraline.item.adapter

import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.util.loreformat.handleLoreFormat
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.getItemTag
import taboolib.module.nms.setItemTag
import java.util.LinkedHashMap

fun mmoAdapter(item: ItemStack, sItem: AbstractSItem, player: Player?): ItemStack {

    val fix = (sItem.getDeepData("mmo:fix-attack-speed") as? Boolean?)
    if (fix == null) {
        devLog("mmo build setting is null or empty, skipping adapting.")
        return item
    }
    item.itemMeta?.let { meta ->
        // Attribute API is 1.13+; go through PlatformCompat (silently skipped when the reflection is missing on older versions)
        PlatformCompat.fixMmoAttackSpeed(meta)
        item.setItemMeta(meta)
    }

    return item

}



