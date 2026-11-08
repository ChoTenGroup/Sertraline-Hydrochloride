package io.github.zzzyyylllty.sertraline.listener.update

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.function.update.checkAndUpdateItem
import io.github.zzzyyylllty.sertraline.function.update.updateArmorItems
import io.github.zzzyyylllty.sertraline.function.update.updateInventoryItems
import io.github.zzzyyylllty.sertraline.function.update.updatePlayerItems
import io.github.zzzyyylllty.sertraline.listener.attribute.debounceRefreshStat
import org.bukkit.block.BlockState
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.platform.util.isAir

@SubscribeEvent
fun onPlayerJoin(e: PlayerJoinEvent) {
    if (!SertralineSettings.itemUpdateOnLogin) return
    if (!SertralineSettings.itemUpdateEnabled) return
    updatePlayerItems(e.player)
}

@SubscribeEvent
fun onPlayerHeldItem(e: PlayerItemHeldEvent) {
    if (!SertralineSettings.itemUpdateOnHold) return
    if (!SertralineSettings.itemUpdateEnabled) return
    val item = e.player.inventory.getItem(e.newSlot)
    if (item == null || item.isAir) return
    checkAndUpdateItem(e.player, item, e.newSlot)
}

@SubscribeEvent
fun onPlayerInteract(e: PlayerInteractEvent) {
    if (!SertralineSettings.itemUpdateOnInteract) return
    if (!SertralineSettings.itemUpdateEnabled) return
    // PlayerInteractEvent fires once PER HAND, and e.item belongs to the hand of THIS event.
    // Resolving the slot from the held hotbar slot for an OFF_HAND event made the updater
    // write the rebuilt off-hand item into the main-hand slot: the held item was destroyed
    // and a duplicate of the off-hand item appeared in the main hand.
    val slot = when (e.hand) {
        EquipmentSlot.HAND -> e.player.inventory.heldItemSlot
        EquipmentSlot.OFF_HAND -> 40
        else -> return
    }
    // Read from the slot itself so the item matches the slot we are about to replace.
    val item = e.player.inventory.getItem(slot)
    if (item == null || item.isAir) return
    checkAndUpdateItem(e.player, item, slot)
}

/**
 * F (swap hands) moves a stale item between the main hand and the off-hand slot without firing
 * held/interact, so both slots are checked. The swap itself happens *after* this event, therefore
 * the check is deferred by one tick (`delay = 1`, not 0 — TabooLib runs delay <= 0 immediately),
 * so the slots already hold the swapped items.
 */
@SubscribeEvent
fun onPlayerSwapHandItems(e: PlayerSwapHandItemsEvent) {
    if (!SertralineSettings.itemUpdateOnSwapHand) return
    if (!SertralineSettings.itemUpdateEnabled) return
    val player = e.player
    submit(async = false, delay = 1L) {
        val held = player.inventory.heldItemSlot
        val heldItem = player.inventory.getItem(held)
        if (heldItem != null && !heldItem.isAir) checkAndUpdateItem(player, heldItem, held)
        val offHand = player.inventory.getItem(40)
        if (offHand != null && !offHand.isAir) checkAndUpdateItem(player, offHand, 40)
    }
}

/**
 * Containers (chests / barrels / shulker boxes / hoppers / furnaces ...) never fire any player
 * trigger while closed, and the updater never touched block inventories before: an item stored in
 * a chest stayed on its old definition until it was taken out. Scanning the opened container fixes
 * that. Only real block containers are refreshed — player inventories (own / invsee) and plugin
 * GUIs (custom InventoryHolder, e.g. the item explorer and crafting stations) are left alone.
 *
 * Detection uses `BlockState` (every block container's holder is one, and unlike
 * `org.bukkit.inventory.BlockInventoryHolder` it also exists on the 1.12 API surface) plus
 * `DoubleChest` (a plain InventoryHolder). Entity containers (chest boat / storage minecart /
 * horse) are intentionally skipped: their contents are usually plugin- or vanilla-managed.
 */
@SubscribeEvent
fun onInventoryOpen(e: InventoryOpenEvent) {
    if (!SertralineSettings.itemUpdateOnContainerOpen) return
    if (!SertralineSettings.itemUpdateEnabled) return
    val player = e.player as? Player ?: return
    val top = e.inventory
    val holder = top.holder
    if (holder !is BlockState && holder !is DoubleChest) return
    updateInventoryItems(top, player)
}

/**
 * Armor slot changed (Paper: PlayerArmorChangeEvent; Spigot: attribute.armor-change-polling).
 * Both call sites already run on the main thread, so the armor item check can run inline.
 * Has to stay in sync with the attribute refresh — this is the single entry point for both.
 */
fun handleArmorSlotChanged(player: Player) {
    submitAsync { debounceRefreshStat(player) }
    if (!SertralineSettings.itemUpdateEnabled) return
    if (!SertralineSettings.itemUpdateOnArmorChange) return
    updateArmorItems(player)
}
