package io.github.zzzyyylllty.sertraline.item.adapter

import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.getItemTag
import taboolib.module.nms.setItemTag

fun customDataAdapter(item: ItemStack, sItem: AbstractSItem, player: Player?): ItemStack {
    val map = (sItem.getDeepData("custom_data") as? Map<String, Any?>)
    if (map == null || map.isEmpty()) {
        devLog("custom_data is null or empty, skipping adapting.")
        return item
    }

    devLog("Adapting custom_data: $map")

    val tag = item.getItemTag(true)
    (transferBooleanToByte(map) as Map<String, Any>).forEach {
        tag.put(it.key, it.value)
    }

    return item.setItemTag(tag, true)
}

fun chotenDataAdapter(item: ItemStack, sItem: AbstractSItem, player: Player?): ItemStack {
    val rawData = sItem.getDeepData("chotenatb") as? Map<String, Any?> ?: run {
        devLog("chotenatb data is null or not a map, skipping adapting.")
        return item
    }

    devLog("Adapting chotenatb data: $rawData")

    // If chotenatb is not empty and no "allowed" is specified, auto-infer it from the item material
    val processedData = if (rawData.isNotEmpty() && !rawData.containsKey("allowed")) {
        val autoState = when (item.type.name.lowercase().substringAfterLast("_")) {
            "boots", "chestplate", "leggings", "helmet" -> item.type.name.lowercase().substringAfterLast("_")
            else -> "mainhand"
        }
        devLog("chotenatb allowed not specified, auto-inferred as: $autoState")
        rawData.toMutableMap().also { it["allowed"] = listOf(autoState) }
    } else {
        rawData
    }

    val tag = item.getItemTag(true)
    tag["chotenatb"] = listOf(transferBooleanToByte(processedData))

    return item.setItemTag(tag, true)
}
