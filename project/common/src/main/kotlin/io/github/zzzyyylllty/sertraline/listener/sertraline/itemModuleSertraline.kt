package io.github.zzzyyylllty.sertraline.listener.sertraline

import io.github.zzzyyylllty.sertraline.config.ConfigUtil
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

@SubscribeEvent(priority = EventPriority.LOWEST)
fun itemModuleSertraline(e: ItemLoadEvent) {
    //if (settings.)

    val prefix = "sertraline"
    val c = ConfigUtil

    val features = listOf(
        "$prefix:tier",
        "$prefix:type",
        "$prefix:level",
        "$prefix:lore-format",
        "$prefix:vals",
        "$prefix:vars",
        "$prefix:dynamics",
        "$prefix:keep-context",
        "$prefix:actions",
        "$prefix:revision-id",
        "$prefix:no-sertraline-id",
        "$prefix:compare-path",
        // Gem system. These are read back through getDeepData("sertraline:gem…"), so a key
        // missing from this list (and from internal/mapping/mappings.yml) is dropped at load
        // time with no warning — the socket list simply comes back empty and lore placeholders
        // such as ${gem-slot:attack} stay unsubstituted.
        "$prefix:gem",
        "$prefix:gem-slots",
        "$prefix:gem-click",
        "$prefix:gem-click-socket"
    )


    // Forward the reload candidate context. Without it the lookup falls back to the live mapping table,
    // which is still EMPTY during the first load (mappings are only published after items are parsed),
    // so every feature warns "Mappings not found" and tier/type/level/actions/vars are silently dropped.
    e.itemData.putAll(c.getFeatures(e.itemKey, e.arguments, features, e.itemData, e.context))
}
