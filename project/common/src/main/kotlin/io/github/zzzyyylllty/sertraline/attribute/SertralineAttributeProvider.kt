package io.github.zzzyyylllty.sertraline.attribute

import org.bukkit.entity.Player

/** Bridges Sertraline's own attribute engine into the existing [AttributeProvider] pipeline. */
class SertralineAttributeProvider : AttributeProvider {

    override val name: String = SertralineAttributeEngine.providerName

    override fun refreshAttributes(player: Player, itemList: Map<String, ItemBound>) {
        SertralineAttributeEngine.refresh(player, itemList)
    }
}
