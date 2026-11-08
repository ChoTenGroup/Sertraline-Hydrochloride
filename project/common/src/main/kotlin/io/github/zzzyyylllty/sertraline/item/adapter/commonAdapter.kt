package io.github.zzzyyylllty.sertraline.item.adapter

import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

fun commonAdapter(item: ItemStack, sItem: AbstractSItem, player: Player?): ItemStack {
    // common module handles gameplay restrictions via event listeners.
    // No item transformation needed.
    return item
}
