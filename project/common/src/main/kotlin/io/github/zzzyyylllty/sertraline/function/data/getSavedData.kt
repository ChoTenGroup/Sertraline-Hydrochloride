package io.github.zzzyyylllty.sertraline.function.data

import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.data.ItemData
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.function.kether.evalKether
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseMapNBT
import io.github.zzzyyylllty.sertraline.util.isNotExist
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.getItemTag


fun getSavedData(item: AbstractSItem?,itemStack: ItemStack?,evalDynamic: Boolean,player: Player?): ItemData {

    val itemVal = item?.getDeepData("sertraline:vals") as Map<String, Any?>?
    val itemVar = mutableMapOf<String, Any?>()
    ((itemStack?.getItemTag(true)["sertraline_data"]?.parseMapNBT()))?.let {
        (it).let { it ->
            devLog("SavedData: $it")
            itemVar.putAll(it)
        }
    } ?: run {
        (item?.getDeepData("sertraline:vars") as Map<String, Any?>?)?.let { itemVar.putAll(it) }
    }
    val itemDynamic = (item?.getDeepData("sertraline:dynamics") as Map<String, Any?>?)
    // context is runtime-independent data, not persisted to NBT, and only exists in the build-time template
    val itemContext = (item?.getDeepData("sertraline:context") as Map<String, Any?>?)

    val data = ItemData(itemVal, itemVar, itemDynamic, itemContext, item?.key)

    if (evalDynamic && itemDynamic != null) {
        val newDynamic = mutableMapOf<String, Any>()
        for (entry in itemDynamic) {
            entry.value.asListEnhanced()?.evalKether(player, data.collect())?.get()?.let { newDynamic[entry.key] = it }
        }
        return ItemData(itemVal, itemVar, newDynamic, itemContext, item.key)
    }
    return data
}

fun ItemStack.getSertralineId(): String? {

    if (this.isNotExist()) return null
    val tag = this.getItemTag(true)
    return tag["sertraline_id"]?.asString()
}

/**
 * Converts a context value to a string: Player/Entity returns its name, avoiding a
 * CraftPlayer{name=...}-style class description.
 */
fun Any?.contextValue(): String? = when (this) {
    is Player -> name
    is Entity -> name
    is String -> this
    is Number -> toString()
    is Boolean -> toString()
    else -> toString()
}