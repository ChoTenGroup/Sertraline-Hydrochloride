package io.github.zzzyyylllty.sertraline.listener.update

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.function.update.buildUpdatedItem
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import taboolib.common.platform.event.SubscribeEvent

// EntityPickupItemEvent is a 1.13+ class, absent from the legacy12 (v11200) compile surface;
// this standalone file is excluded entirely in legacy12 builds (see project/common/build.gradle.kts).
@SubscribeEvent
fun onEntityPickupItem(e: EntityPickupItemEvent) {
    if (!SertralineSettings.itemUpdateOnPickup) return
    if (!SertralineSettings.itemUpdateEnabled) return
    val player = e.entity as? Player ?: return
    // The event fires before the stack is merged into the inventory, so replacing the dropped
    // stack means the player picks up the updated item directly (no stale copy in the inventory).
    val updated = buildUpdatedItem(player, e.item.itemStack) ?: return
    e.item.itemStack = updated
}
