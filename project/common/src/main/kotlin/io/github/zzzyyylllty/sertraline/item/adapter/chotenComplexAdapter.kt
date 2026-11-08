package io.github.zzzyyylllty.sertraline.item.adapter

import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.getItemTag
import taboolib.module.nms.setItemTag

fun chotenComplexAdapter(item: ItemStack, sItem: AbstractSItem, player: Player?): ItemStack {
    val rawData = sItem.getDeepData("chotenatb_complex") as? List<Map<String, Any?>> ?: run {
        devLog("chotenatb_complex data is null or not a list, skipping adapting.")
        return item
    }

    devLog("Adapting chotenatb_complex data: $rawData")

    val tag = item.getItemTag(true)
    tag["chotenatb"] = transferBooleanToByte(rawData)

    return item.setItemTag(tag, true)
}
