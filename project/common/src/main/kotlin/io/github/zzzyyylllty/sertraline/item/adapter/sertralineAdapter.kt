package io.github.zzzyyylllty.sertraline.item.adapter

import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.util.loreformat.handleLoreFormat
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import com.cryptomorin.xseries.XItemStack
import taboolib.module.nms.getItemTag
import taboolib.module.nms.setItemTag

fun sertralineAdapter(item: ItemStack, sItem: AbstractSItem, player: Player?): ItemStack {

    val dataMap = (sItem.getDeepData("sertraline:vars") as Map<String, Any?>?)?.toMutableMap()
    val tag = item.getItemTag(true)
    // TODO: the filterNbtSafe comment needs confirmation; vars no longer contains Player/Event (now separate as context)
    tag["sertraline_data"] = dataMap
    //tag["sertraline_data"] = dataMap?.filterNbtSafe()

    // Write the type to NBT
    val typeData = sItem.getDeepData("sertraline:type")
    if (typeData != null) {
        val typeId = when (typeData) {
            is io.github.zzzyyylllty.sertraline.data.Type -> typeData.id
            else -> typeData.toString()
        }
        tag["sertraline_type"] = typeId
    }

    // Write the tier to NBT
    val tierData = sItem.getDeepData("sertraline:tier")
    if (tierData != null) {
        val tierId = when (tierData) {
            is io.github.zzzyyylllty.sertraline.data.Tier -> tierData.id
            else -> tierData.toString()
        }
        tag["sertraline_tier"] = tierId
    }

    val item = item.setItemTag(tag, true)

    // lore format
    handleLoreFormat(sItem, player, PlatformCompat.getLore(item), false, item)?.let {
        PlatformCompat.setLore(item, it)
    }

    return item

}

/**
 * Filter out values that cannot be serialized to NBT, avoiding an Unsupported nbt exception when writing ItemTag.
 * Script/loot vars may contain runtime objects such as Player and Event, which do not need to be persisted to item NBT.
 */
private fun Map<String, Any?>.filterNbtSafe(): Map<String, Any?> {
    return mapValues { (_, value) -> sanitizeNbtValue(value) }
        .filter { (_, value) -> value !== FILTERED_SENTINEL }
}

private val FILTERED_SENTINEL = Any()

private fun sanitizeNbtValue(value: Any?): Any? {
    when {
        value == null -> return null
        value is String -> return value
        value is Number -> return value
        value is Boolean -> return value
        value is ByteArray -> return value
        value is IntArray -> return value
        value is LongArray -> return value
        value is Map<*, *> -> {
            val sanitized = value.entries.associate { (k, v) ->
                k.toString() to sanitizeNbtValue(v)
            }.filter { (_, v) -> v !== FILTERED_SENTINEL }
            return sanitized
        }
        value is Collection<*> -> {
            val sanitized = value.map { sanitizeNbtValue(it) }.filter { it !== FILTERED_SENTINEL }
            return sanitized
        }
        value is Array<*> -> {
            val sanitized = value.map { sanitizeNbtValue(it) }.filter { it !== FILTERED_SENTINEL }
            return sanitized
        }
        value is Player -> return value.name
        // Other non-serializable types (Event, Entity, etc.) → discard
        else -> return FILTERED_SENTINEL
    }
}



