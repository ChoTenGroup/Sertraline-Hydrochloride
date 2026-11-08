package io.github.zzzyyylllty.sertraline.listener.sertraline

import io.github.zzzyyylllty.sertraline.config.ConfigUtil
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

@SubscribeEvent(priority = EventPriority.LOW)
fun itemModuleXBuilder(e: ItemLoadEvent) {
    
    val prefix = "xbuilder"
    val c = ConfigUtil

    val features = listOf(
        "$prefix:material",
        "$prefix:name",
        "$prefix:lore",
        "$prefix:item-model",
    )

    // Pass the reload candidate context so the first load does not read the (still empty) live mapping table.
    e.itemData.putAll(c.getFeatures(e.itemKey, e.arguments, features, e.itemData, e.context))
}
