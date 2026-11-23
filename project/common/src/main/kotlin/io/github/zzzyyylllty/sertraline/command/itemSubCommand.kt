package io.github.zzzyyylllty.sertraline.command

import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.function.kether.evalKetherString
import io.github.zzzyyylllty.sertraline.gui.ItemExplorer
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.util.minimessage.toMiniMessageString
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.bool
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.player
import taboolib.common.platform.command.subCommand
import taboolib.common.util.asList
import taboolib.platform.util.asLangText
import taboolib.platform.util.giveItem

@CommandHeader(
    name = "sertralineitem",
    aliases = ["items","needyitemi","depazi", "di", "si"],
    permission = "sertraline.command.item",
    description = "DEBUG Command of DepazItems.",
    permissionMessage = "",
    permissionDefault = PermissionDefault.OP,
    newParser = false,
)
object ItemCommand {

    @CommandBody
    val main = mainCommand {
        createModernHelper()
    }

    @CommandBody
    val help = subCommand {
        createModernHelper()
    }
    @CommandBody
    val browse = subCommand {
        execute<CommandSender> { sender, context, argument ->
            if (sender is Player) ItemExplorer().mainItemExplorer(sender)
        }
    }

    @CommandBody
    val give = subCommand {
        dynamic("id") {
            execute<CommandSender> { sender, context, _ ->
                if (sender is Player) giveItem(sender, sender, context["id"])
            }
            suggestion<CommandSender>(uncheck = true) { _, _ ->
                itemMap.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
            player("player") {
                execute<CommandSender> { sender, context, _ ->
                    giveItem(sender, context.player("player").castSafely<Player>(), context["id"])
                }
                dynamic("amount") {
                    execute<CommandSender> { sender, context, _ ->
                        giveItem(sender, context.player("player").castSafely<Player>(), context["id"], context["amount"])
                    }
                    suggestion<CommandSender>(uncheck = true) { _, _ -> listOf("1", "16", "64") }
                    bool("silent") {
                        execute<CommandSender> { sender, context, _ ->
                            giveItem(sender, context.player("player").castSafely<Player>(), context["id"], context["amount"], context.bool("silent"))
                        }
                    }
                }
            }
        }
    }

    private fun giveItem(sender: CommandSender, player: Player?, id: String, amountText: String = "1", silent: Boolean = false) {
        val amount = amountText.toIntOrNull()?.takeIf { it in 1..2304 }
        if (amount == null) {
            sender.sendStringAsComponent(sender.asLangText("Item_Give_Invalid_Amount"))
            return
        }
        if (player == null || !player.isOnline) return
        val item = sertralineItemBuilder(id, player) ?: run {
            sender.sendStringAsComponent(sender.asLangText("Item_Not_Exist", id))
            return
        }
        if (item.type == org.bukkit.Material.AIR) return
        var remaining = amount
        val stackSize = item.maxStackSize.coerceAtLeast(1)
        while (remaining > 0) {
            val count = minOf(remaining, stackSize)
            player.giveItem(item.clone().apply { this.amount = count })
            remaining -= count
        }
        if (!silent) {
            player.sendStringAsComponent(player.asLangText("Item_Give", amount, PlatformCompat.getDisplayName(item).toMiniMessageString()))
        }
    }
}
