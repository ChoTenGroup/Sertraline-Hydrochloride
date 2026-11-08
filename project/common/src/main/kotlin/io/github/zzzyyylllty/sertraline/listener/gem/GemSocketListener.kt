package io.github.zzzyyylllty.sertraline.listener.gem

import io.github.zzzyyylllty.sertraline.data.isClickSocketEnabled
import io.github.zzzyyylllty.sertraline.item.gem.GemRouteDenial
import io.github.zzzyyylllty.sertraline.item.gem.GemSocketMethod
import io.github.zzzyyylllty.sertraline.item.gem.GemSocketPolicy
import io.github.zzzyyylllty.sertraline.item.gem.GemSocketResult
import io.github.zzzyyylllty.sertraline.item.gem.getGemDefinition
import io.github.zzzyyylllty.sertraline.item.gem.getGemSlots
import io.github.zzzyyylllty.sertraline.item.gem.getUsedGemSlots
import io.github.zzzyyylllty.sertraline.item.gem.socketGem
import io.github.zzzyyylllty.sertraline.item.itemTemplateForStack
import io.github.zzzyyylllty.sertraline.util.isNotExist
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.platform.util.sendLang

/**
 * Click-to-socket interaction: picking up a Sertraline gem on the cursor and
 * clicking it onto a socketable item fills the first compatible free slot.
 *
 * Everything is configurable:
 * - `gem.interaction.click.enable` / `.types` / `.permission` gate the route as a whole
 *   (see [GemSocketPolicy.denialOf]),
 * - `click: false` on the item (`sertraline:gem-click`), on a socket, or on the gem
 *   definition forbids this route for that specific item / socket / gem
 *   (enforced inside [socketGem], and pre-filtered here so a disabled socket is never
 *   selected in the first place).
 *
 * The click is not filtered by [org.bukkit.event.inventory.InventoryAction]: vanilla
 * only reports SWAP_WITH_CURSOR for slots that can exchange their content (armor /
 * offhand), while clicking a cursor item onto an occupied slot of a normal container
 * is reported as NOTHING — filtering on the action would make the feature dead for
 * regular inventory slots. Interception is instead gated on the click actually being
 * a gem-onto-socketable-item interaction, so storing gems in containers still works.
 */
@SubscribeEvent(priority = EventPriority.HIGHEST, ignoreCancelled = true)
fun onGemSocketClick(e: InventoryClickEvent) {
    val player = e.whoClicked as? Player ?: return
    val gem = e.cursor.takeUnless { it.isNotExist() } ?: return
    val weapon = e.currentItem?.takeUnless { it.isNotExist() } ?: return

    val gemDef = gem.getGemDefinition() ?: return // cursor is not a gem
    val slots = weapon.getGemSlots()
    if (slots.isEmpty()) return // target has no sockets

    val used = weapon.getUsedGemSlots()
    // Every free slot that accepts this gem's colour, ignoring the route flags.
    val freeSlot = slots.values.firstOrNull { it.id !in used && it.accepts(gemDef.color) }
        ?: return // no compatible free slot — let vanilla handle the click

    // Definition-level click gates. Checking them here (instead of only inside socketGem)
    // keeps a `click: false` socket from ever being *selected*: previously the first free
    // socket was picked regardless of its flag, the socket was refused and the click was
    // left to vanilla, so a usable second socket could never be reached by clicking.
    val itemClickEnabled = itemTemplateForStack(weapon, null)?.isClickSocketEnabled() ?: true
    if (!itemClickEnabled || !gemDef.clickSocket) {
        player.sendLang("gem-socket-denied", freeSlot.name)
        return
    }
    val target = slots.values.firstOrNull {
        it.id !in used && it.clickSocket && it.accepts(gemDef.color)
    } ?: run {
        player.sendLang("gem-socket-denied", freeSlot.name)
        return
    }

    // The click would have socketed: apply the route gate now, so an unusable gesture
    // stays silent while a permission problem is reported.
    when (GemSocketPolicy.denialOf(GemSocketMethod.CLICK, player, e.click)) {
        null -> Unit
        GemRouteDenial.PERMISSION -> {
            player.sendLang("gem-click-denied")
            return
        }
        GemRouteDenial.DISABLED, GemRouteDenial.CLICK_TYPE -> return
    }

    // All validations happen before socketGem touches anything, so a failed attempt
    // leaves the item untouched and the click can still be handled by vanilla.
    val result = socketGem(player, weapon, gem, target.id, GemSocketMethod.CLICK)
    when (result) {
        GemSocketResult.SUCCESS -> {
            e.isCancelled = true
            // socketGem consumed one gem from the passed stack; write both sides back
            // explicitly so the result never depends on the event returning live mirrors.
            e.currentItem = weapon
            e.view.cursor = if (gem.amount > 0) gem else null
            player.updateInventory()
            player.sendLang("gem-socket-success", target.name)
        }
        // The item / socket / gem opted out of click socketing — say so instead of
        // silently swallowing the click.
        GemSocketResult.METHOD_NOT_ALLOWED -> player.sendLang("gem-socket-denied", target.name)
        else -> player.sendLang("gem-socket-failed", result.name)
    }
}