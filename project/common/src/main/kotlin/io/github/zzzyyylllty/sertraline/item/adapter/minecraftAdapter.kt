package io.github.zzzyyylllty.sertraline.item.adapter

import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.impl.removeComponentNMS
import io.github.zzzyyylllty.sertraline.impl.setComponentNMS
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.NMSItemTag.Companion.asBukkitCopy
import taboolib.module.nms.NMSItemTag.Companion.asNMSCopy
import java.util.concurrent.atomic.AtomicBoolean

private val warnedMinecraftComponent = AtomicBoolean(false)

fun minecraftAdapter(item: ItemStack,sItem: AbstractSItem,player: Player?): ItemStack {
    val item = item
    val map = (sItem.getMajorData("minecraft") as? Map<String, Any?>) ?: return item
    if (map.isEmpty()) return item
    // DataComponent is a 1.20.5+ feature; older versions (1.12.2, etc.) cannot write components, so skip entirely
    if (!VersionHelper.isOrAbove12005()) {
        if (warnedMinecraftComponent.compareAndSet(false, true)) {
            warningL("WarningNotSupportDataComponent")
        }
        return item
    }
    var nmsItem = asNMSCopy(item)

    devLog("sItem: $sItem")
    devLog("map: $map")
    map.forEach { (key, value) ->
        if (value != null) {
            devLog("Setting $key component.")
            nmsItem.setComponentNMS(key, value)?.let { nmsItem = it }
        } else {
            devLog("DataComponent $key is null. now removing component.")
            nmsItem.removeComponentNMS(key)
        }
    }
    return asBukkitCopy(nmsItem)
}

fun transferBooleanToByte(input: Any?): Any? {
    return when (input) {
        is Map<*, *> -> input.map { (k, v) ->
            k.toString() to transferBooleanToByte(v)
        }.toMap() // directly use map and toMap

        is List<*> -> input.map { transferBooleanToByte(it) } // use map

        is Boolean -> if (input) 1.toByte() else 0.toByte()

        else -> input
    }
}