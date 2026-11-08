package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.util.DependencyHelper
import io.github.zzzyyylllty.sertraline.util.dependencies.MMOUtil.mmoAttributeCalculate
import io.lumine.mythic.lib.api.player.EquipmentSlot
import io.lumine.mythic.lib.api.player.MMOPlayerData
import io.lumine.mythic.lib.player.modifier.ModifierSource
import org.bukkit.entity.Player

class MythicLibAttributeProvider : AttributeProvider {

    override val name: String = "mythiclib"

    override fun refreshAttributes(player: Player, itemList: Map<String, ItemBound>) {
        if (!DependencyHelper.mmLib) return

        val playerData = MMOPlayerData.getOrNull(player) ?: return
        val statMap = playerData.statMap

        // Remove all old modifiers at once (both those with and without slot suffixes)
        for (instance in statMap.instances) {
            instance.removeIf { key ->
                key == "sertraline_item" || key.startsWith("sertraline_item_")
            }
        }

        // Add new modifiers for each slot that has an item
        itemList.forEach { (slot, itemBound) ->
            val sItem = itemBound.sItem
            val bItem = itemBound.bItem
            val (defSource, defSlot) = when (slot) {
                "mainhand" -> Pair(ModifierSource.MAINHAND_ITEM, EquipmentSlot.MAIN_HAND)
                "offhand" -> Pair(ModifierSource.OFFHAND_ITEM, EquipmentSlot.OFF_HAND)
                else -> Pair(
                    ModifierSource.ARMOR, when (slot) {
                        "helmet" -> EquipmentSlot.HEAD
                        "chestplate" -> EquipmentSlot.CHEST
                        "leggings" -> EquipmentSlot.LEGS
                        "boots" -> EquipmentSlot.FEET
                        else -> EquipmentSlot.OTHER
                    }
                )
            }
            mmoAttributeCalculate(
                sItem,
                playerData,
                defSource,
                defSlot,
                slot,
                bItem.type.name,
                async = false
            )
        }
    }
}
