package io.github.zzzyyylllty.sertraline.command

import io.github.zzzyyylllty.sertraline.item.gem.GemSocketMethod
import io.github.zzzyyylllty.sertraline.item.gem.GemSocketResult
import io.github.zzzyyylllty.sertraline.item.gem.GemUnsocketResult
import io.github.zzzyyylllty.sertraline.function.data.getSertralineId
import io.github.zzzyyylllty.sertraline.item.gem.getGemSlots
import io.github.zzzyyylllty.sertraline.item.gem.getSocketedGems
import io.github.zzzyyylllty.sertraline.item.gem.socketGem
import io.github.zzzyyylllty.sertraline.item.gem.unsocketGem
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.util.isNotExist
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.platform.util.asLangText

@CommandHeader(
    name = "sertralinegem",
    aliases = ["sgem"],
    permission = "sertraline.command.gem",
    description = "Gem socketing commands.",
    permissionDefault = PermissionDefault.OP,
    newParser = false,
)
object GemCommand {

    @CommandBody
    val main = mainCommand {
        createModernHelper()
    }

    /** /sertralinegem socket <slotId> [gemId] — gems defaults to the item in the off hand. */
    @CommandBody
    val socket = subCommand {
        dynamic("slotId") {
            suggestion<CommandSender>(uncheck = true) { sender, _ ->
                (sender as? Player)?.inventory?.itemInMainHand?.getGemSlots()?.keys?.toList() ?: emptyList()
            }
            dynamic("gemId", optional = true) {
                suggestion<CommandSender>(uncheck = true) { _, _ ->
                    io.github.zzzyyylllty.sertraline.Sertraline.itemMap.keys.toList()
                }
                execute<CommandSender> { sender, context, _ ->
                    val player = sender as? Player ?: return@execute
                    val weapon = player.inventory.itemInMainHand
                    if (weapon.isNotExist()) return@execute
                    val gemId = context.getOrNull("gemId")
                    // `gemId` names WHICH gem to socket, it does not conjure one: an explicitly
                    // named gem must be physically present (bag or off hand) so socketGem() can
                    // consume it. Building it from the template instead made /sgem socket a free
                    // gem generator — the template's decrement was thrown away, while unsocket
                    // still handed a real gem back.
                    var gemSourceSlot = -1
                    val gem: ItemStack = if (gemId != null) {
                        val contents = player.inventory.contents ?: emptyArray()
                        val found = contents.indexOfFirst { stack ->
                            stack != null && !stack.isNotExist() && stack !== weapon &&
                                stack.getSertralineId() == gemId && stack.amount > 0
                        }
                        if (found >= 0) {
                            gemSourceSlot = found
                            contents[found]
                        } else {
                            val off = player.inventory.itemInOffHand
                            if (!off.isNotExist() && off.getSertralineId() == gemId) {
                                gemSourceSlot = -106 // off hand marker
                                off
                            } else {
                                player.sendStringAsComponent(player.asLangText("Gem_Not_Owned", gemId))
                                return@execute
                            }
                        }
                    } else player.inventory.itemInOffHand
                    if (gem.isNotExist()) return@execute
                    val fromOffHand = gemId == null
                    when (socketGem(player, weapon, gem, context["slotId"], GemSocketMethod.COMMAND)) {
                        GemSocketResult.SUCCESS -> {
                            // Write both sides back explicitly instead of relying on the
                            // inventory returning live mirrors.
                            player.inventory.setItemInMainHand(weapon)
                            if (fromOffHand) {
                                player.inventory.setItemInOffHand(if (gem.amount > 0) gem else null)
                            } else when (gemSourceSlot) {
                                -106 -> player.inventory.setItemInOffHand(if (gem.amount > 0) gem else null)
                                in 0..Int.MAX_VALUE ->
                                    player.inventory.setItem(gemSourceSlot, if (gem.amount > 0) gem else null)
                            }
                            player.updateInventory()
                            player.sendStringAsComponent(player.asLangText("Gem_Socket_Success", context["slotId"]))
                        }
                        else -> player.sendStringAsComponent(player.asLangText("Gem_Socket_Failed", context["slotId"]))
                    }
                }
            }
        }
    }

    /** /sertralinegem unsocket <slotId> — extracts the gem back to the player. */
    @CommandBody
    val unsocket = subCommand {
        dynamic("slotId") {
            suggestion<CommandSender>(uncheck = true) { sender, _ ->
                (sender as? Player)?.inventory?.itemInMainHand?.getSocketedGems()?.map { it.slotId } ?: emptyList()
            }
            execute<CommandSender> { sender, context, _ ->
                val player = sender as? Player ?: return@execute
                val weapon = player.inventory.itemInMainHand
                if (weapon.isNotExist()) return@execute
                when (unsocketGem(player, weapon, context["slotId"])) {
                    GemUnsocketResult.SUCCESS -> {
                        player.inventory.setItemInMainHand(weapon)
                        player.updateInventory()
                        player.sendStringAsComponent(player.asLangText("Gem_Unsocket_Success", context["slotId"]))
                    }
                    else -> player.sendStringAsComponent(player.asLangText("Gem_Unsocket_Failed", context["slotId"]))
                }
            }
        }
    }

    /** /sertralinegem list — shows slots and socketed gems of the item in the main hand. */
    @CommandBody
    val list = subCommand {
        execute<CommandSender> { sender, _, _ ->
            val player = sender as? Player ?: return@execute
            val weapon = player.inventory.itemInMainHand
            // Empty hand: `getItemTag` on AIR throws inside TabooLib's NMS helper, and the slot
            // lookup would return nothing anyway.
            if (weapon.isNotExist()) {
                player.sendStringAsComponent(player.asLangText("Gem_List_No_Item"))
                return@execute
            }
            val slots = weapon.getGemSlots()
            val socketed = weapon.getSocketedGems().associateBy { it.slotId }
            for ((id, slot) in slots) {
                val gem = socketed[id]
                player.sendStringAsComponent(
                    player.asLangText(
                        if (gem != null) "Gem_List_Used" else "Gem_List_Empty",
                        id, slot.name, gem?.gemId ?: "",
                    )
                )
            }
        }
    }
}
