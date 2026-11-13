package io.github.zzzyyylllty.sertraline.listener.attribute

import io.github.zzzyyylllty.sertraline.attribute.SertralineAttributeEngine
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.event.SubscribeEvent

/**
 * Restores vanilla attribute values before the player is saved.
 *
 * Attribute base values are persisted in the player's data. Without this, a player who quits while
 * wearing a +100 attack damage sword would keep 101 as their *base* next session and our modifier
 * would add another +100 on top (101 -> 201 -> 301...). It also drops the per-player caches.
 */
@SubscribeEvent
fun onQuitRestoreAttributes(e: PlayerQuitEvent) {
    runCatching { SertralineAttributeEngine.clear(e.player) }
}
