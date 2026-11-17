package io.github.zzzyyylllty.sertraline.listener.sertraline

import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.event.ItemLoadEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

@SubscribeEvent(priority = EventPriority.MONITOR)
fun itemModuleSave(e: ItemLoadEvent) {
    // __-prefixed items are handled by the private manager, skip loading into itemMap
    if (e.itemKey?.startsWith("__") == true) return

    val data = mutableMapOf<String, Any?>()
    for (entry in e.itemData) {
        val location = entry.key
        val split = location.split(":")
        if (split.size >= 2) {
            val major = split[0]
            val section = location.removePrefix("${split[0]}:")
            val majormap = (data[major] as? Map<*,*>)?.toMutableMap() ?: mutableMapOf()
            majormap[section] = entry.value
            data[major] = majormap
        } else data[location] = entry.value
    }
    // Create through the factory so it assigns runtime state such as shouldAdapt.
    val sItem = SItemFactory.sertraline(
        key = e.itemKey ?: return,
        data = data as LinkedHashMap<*, *> as LinkedHashMap<String, Any?>,
        config = e.arguments as LinkedHashMap<*, *> as LinkedHashMap<String, Any?>
    )
    itemDebugger(e, sItem)
    // In context mode, write to the reload candidate table; null is the compatibility entry point and writes to the live table (legacy behavior).
    val sink = e.context?.items ?: itemMap
    e.itemKey?.let { sink[it] = sItem }
}
