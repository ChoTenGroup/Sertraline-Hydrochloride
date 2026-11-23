package io.github.zzzyyylllty.sertraline.listener.gem

import io.github.zzzyyylllty.sertraline.data.isClickSocketEnabled
import io.github.zzzyyylllty.sertraline.data.getGemDefinition
import io.github.zzzyyylllty.sertraline.data.getGemSlotDefinitions
import io.github.zzzyyylllty.sertraline.data.localizedName
import io.github.zzzyyylllty.sertraline.item.gem.GemRouteDenial
import io.github.zzzyyylllty.sertraline.item.gem.GemSocketMethod
import io.github.zzzyyylllty.sertraline.item.gem.GemSocketPolicy
import io.github.zzzyyylllty.sertraline.item.gem.GemSocketResult
import io.github.zzzyyylllty.sertraline.item.gem.socketKnownGem
import io.github.zzzyyylllty.sertraline.item.gem.usedGemSlots
import io.github.zzzyyylllty.sertraline.item.itemTemplateForStack
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.util.isNotExist
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.platform.util.asLangText
import taboolib.module.nms.getItemTag
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Sends a lang node the way the rest of the plugin does.
 *
 * TabooLib's `sendLang` writes the raw node text with legacy codes only, so the
 * MiniMessage colours used by every lang entry (`<#66ccff>`, `<red>`, …) end up on
 * screen as literal tags. [sendStringAsComponent] converts legacy codes and parses
 * MiniMessage, which also lets the interpolated socket name keep its own colours.
 */
private fun Player.sendGemLang(node: String, vararg args: Any) =
    sendStringAsComponent(asLangText(node, *args))

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
private val lastSocketAttempt = HashMap<UUID, Long>()
private val socketInterval = TimeUnit.MILLISECONDS.toNanos(200)

@SubscribeEvent
fun forgetGemClick(event: PlayerQuitEvent) {
    lastSocketAttempt.remove(event.player.uniqueId)
}

@SubscribeEvent(priority = EventPriority.HIGHEST, ignoreCancelled = true)
fun onGemSocketClick(e: InventoryClickEvent) {
    val player = e.whoClicked as? Player ?: return
    val denial = GemSocketPolicy.denialOf(GemSocketMethod.CLICK, player, e.click)
    if (denial == GemRouteDenial.DISABLED || denial == GemRouteDenial.CLICK_TYPE) return
    val gem = e.cursor.takeUnless { it.isNotExist() } ?: return
    val weapon = e.currentItem?.takeUnless { it.isNotExist() } ?: return

    if (!gem.hasItemMeta() || !weapon.hasItemMeta()) return
    val gemTemplate = itemTemplateForStack(gem, player) ?: return
    val gemDef = gemTemplate.getGemDefinition() ?: return
    val weaponTag = weapon.getItemTag(true)
    val weaponTemplate = itemTemplateForStack(weapon, player, weaponTag) ?: return
    val slots = weaponTemplate.getGemSlotDefinitions()
    if (slots.isEmpty()) return

    val used = usedGemSlots(weaponTag).toSet()
    // First select a socket that accepts this gem and permits click socketing.
    val compatibleSlot = slots.values.firstOrNull { it.id !in used && it.accepts(gemDef.color) }
        ?: return // no compatible free slot — let vanilla handle the click

    val itemClickEnabled = weaponTemplate.isClickSocketEnabled()
    if (!itemClickEnabled || !gemDef.clickSocket) {
        player.sendGemLang("gem-socket-denied", compatibleSlot.localizedName(player))
        return
    }
    val target = slots.values.firstOrNull {
        it.id !in used && it.clickSocket && it.accepts(gemDef.color)
    } ?: run {
        player.sendGemLang("gem-socket-denied", compatibleSlot.localizedName(player))
        return
    }

    // The click would have socketed: apply the route gate now, so an unusable gesture
    // stays silent while a permission problem is reported.
    when (denial) {
        null -> Unit
        GemRouteDenial.PERMISSION -> {
            player.sendGemLang("gem-click-denied")
            return
        }
        GemRouteDenial.DISABLED, GemRouteDenial.CLICK_TYPE -> return
    }

    // All validations happen before socketGem touches anything, so a failed attempt
    // leaves the item untouched and the click can still be handled by vanilla.
    val now = System.nanoTime()
    val previous = lastSocketAttempt[player.uniqueId]
    if (previous != null && now - previous < socketInterval) {
        e.isCancelled = true
        return
    }
    lastSocketAttempt[player.uniqueId] = now
    val result = socketKnownGem(player, weapon, gem, target.id, GemSocketMethod.CLICK,
        weaponTemplate, weaponTag, gemDef, gemTemplate.storageKey)
    when (result) {
        GemSocketResult.SUCCESS -> {
            e.isCancelled = true
            // socketGem consumed one gem from the passed stack; write both sides back
            // explicitly so the result never depends on the event returning live mirrors.
            e.currentItem = weapon
            e.view.cursor = if (gem.amount > 0) gem else null
            player.updateInventory()
            player.sendGemLang("gem-socket-success", target.localizedName(player))
        }
        // The item / socket / gem opted out of click socketing — say so instead of
        // silently swallowing the click.
        GemSocketResult.METHOD_NOT_ALLOWED -> player.sendGemLang("gem-socket-denied", target.localizedName(player))
        else -> player.sendGemLang("gem-socket-failed", result.name)
    }
}
