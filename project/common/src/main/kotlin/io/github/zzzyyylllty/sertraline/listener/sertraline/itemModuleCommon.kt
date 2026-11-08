package io.github.zzzyyylllty.sertraline.listener.sertraline

import io.github.zzzyyylllty.sertraline.config.ConfigUtil
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

@SubscribeEvent(priority = EventPriority.LOW)
fun itemModuleCommon(e: ItemLoadEvent) {
    val prefix = "common"
    val features = listOf(
        "$prefix:disable-crafting",
        "$prefix:disable-smelting",
        "$prefix:disable-smithing",
        "$prefix:disable-enchanting",
        "$prefix:disable-anvil",
        "$prefix:disable-brewing",
        "$prefix:disable-stonecutting",
        "$prefix:disable-village-trade"
    )

    // Pass the reload candidate context so the first load does not read the (still empty) live mapping table.
    e.itemData.putAll(ConfigUtil.getFeatures(e.itemKey, e.arguments, features, e.itemData, e.context))
}
