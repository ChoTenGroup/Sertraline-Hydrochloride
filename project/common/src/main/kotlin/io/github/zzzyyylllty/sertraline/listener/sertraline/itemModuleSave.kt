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
    // 统一经工厂创建，保证 shouldAdapt 等运行时状态由工厂赋值
    val sItem = SItemFactory.sertraline(
        key = e.itemKey ?: return,
        data = data as LinkedHashMap<*, *> as LinkedHashMap<String, Any?>,
        config = e.arguments as LinkedHashMap<*, *> as LinkedHashMap<String, Any?>
    )
    itemDebugger(e, sItem)
    // context 模式写 reload 候选表；null 为兼容入口，写 live 表（旧语义）
    val sink = e.context?.items ?: itemMap
    e.itemKey?.let { sink[it] = sItem }
}
