package io.github.zzzyyylllty.sertraline.function.action

import io.github.zzzyyylllty.sertraline.Sertraline.itemCache
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.data.Action
import io.github.zzzyyylllty.sertraline.function.data.getSavedData
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import io.github.zzzyyylllty.sertraline.util.ComplexTypeHelper
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submitAsync
import taboolib.module.nms.getItemTag

fun Player.applyActions(trigger: String, e: Event, ce: Cancellable?, i: ItemStack, abItem: ItemStack? = null, sItemId: String? = null) {
    devLog("Triggering action $trigger - ${e.eventName}")
    val player = this@applyActions
    val inv = player.inventory
    val id = sItemId ?: i.getItemTag(true)["sertraline_id"]?.asString() ?: return
    if (((itemCache[id] as? Map<*,*>)?.get("actions") as? List<String>?)?.contains(trigger) != true) return
    val allActions = ((itemCache[id] as? Map<*,*>)?.get("preloadActions") as? LinkedHashMap<String, List<Action>>?) ?: run {
        devLog("Can't get preload actions for $trigger, fallback to complextypehelper to get as actions.")
        val fallbackItem = itemSerializer(id, player) ?: return
        ComplexTypeHelper(
            fallbackItem.getDeepData("sertraline:actions")
        ).getAsActions()
    }
    val actions = allActions?.get(trigger) ?: return
    // Resolve item and data once outside the loop to avoid competing accesses to the same local variables across threads.
    // deepCopy: the serializer's fast path returns the *live* registry template, and action code is
    // free to write to the item it receives (set-var / lore actions) — that used to mutate the cache.
    val resolvedItem = itemSerializer(id, player)?.deepCopy() ?: return
    val resolvedData = getSavedData(resolvedItem, i, true, player).collect()
    actions.forEach { action ->
        if (action.async ?: true) submitAsync {
            action.runAction(player, resolvedData, i, e, ce, resolvedItem, abItem)
        } else {
            action.runAction(player, resolvedData, i, e, ce, resolvedItem, abItem)
        }
    }
}
