package io.github.zzzyyylllty.sertraline.listener.attribute

import io.github.zzzyyylllty.sertraline.attribute.AttributeManager
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerLoginEvent
import taboolib.common.function.debounce
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

// var so reload can recreate with updated delay
var debounceRefreshStat = debounce<Player>(SertralineSettings.attributeDebounceTime) { player ->
    AttributeManager.refreshAttributes(player)
}

/**
 * Recreate the debounce function with the current config delay.
 * Called from [io.github.zzzyyylllty.sertraline.Sertraline.runReload] after settings are refreshed.
 */
fun resetDebounceRefreshStat() {
    debounceRefreshStat = debounce<Player>(SertralineSettings.attributeDebounceTime) { player ->
        AttributeManager.refreshAttributes(player)
    }
}

// Both handlers used to wrap the call in `submitAsync`. `debounce` already schedules its own delayed
// task and `AttributeManager.refreshAttributes` now hops to the main thread itself, so the extra
// async wrapper only added a thread hop (and moved the work off the main thread, which is exactly
// what must not happen for inventory/NMS reads).
@SubscribeEvent
fun hotBarChangeEvent(e: PlayerItemHeldEvent) {
    debounceRefreshStat(e.player, delay = SertralineSettings.attributeDebounceTime)
}

// The Paper-only PlayerArmorChangeEvent has been moved to PaperEventBridge (hooks);
// on Spigot it is simulated via attribute.armor-change-polling polling.

@SubscribeEvent(EventPriority.MONITOR)
fun onLoginUpdate(e: PlayerLoginEvent) {
    // PlayerLoginEvent fires before the player is added to the world inventory-wise; refreshing on
    // the next tick keeps the main-thread contract and still runs before the player sees anything.
    AttributeManager.refreshAttributes(e.player)
}