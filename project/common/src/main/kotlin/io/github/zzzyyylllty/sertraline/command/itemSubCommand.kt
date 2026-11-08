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
import taboolib.common.platform.function.submitAsync
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
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val id = context["id"]
                    if (sender is Player) {
                        val item = sertralineItemBuilder(id,sender) ?: run {
                            sender.sendStringAsComponent(sender.asLangText("Item_Not_Exist", id))
                            null
                        }
                        item?.let {
                            sender?.giveItem(it)
                            sender?.sendStringAsComponent(sender.asLangText("Item_Give", 1, PlatformCompat.getDisplayName(it).toMiniMessageString()))
                        }
                    }}
            }
            suggestion<CommandSender>(uncheck = true) { sender, context ->
                itemMap.keys.asList()
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            }
            player("player") {
                execute<CommandSender> { sender, context, argument ->
                    submitAsync {
                        val id = context["id"]
                        val tabooPlayer = context.player("player")
                        // Convert to a Bukkit Player
                        val bukkitPlayer = tabooPlayer.castSafely<Player>()
                        val item = sertralineItemBuilder(id,bukkitPlayer) ?: run {
                            sender.sendStringAsComponent(
                                sender.asLangText(
                                    "Item_Not_Exist", id
                                )
                            )
                            null
                        }
                        item?.let {
                            bukkitPlayer?.giveItem(it)
                            bukkitPlayer?.sendStringAsComponent(bukkitPlayer.asLangText("Item_Give", 1, PlatformCompat.getDisplayName(it).toMiniMessageString()))
                        }}
                }

                dynamic("amount") {
                    execute<CommandSender> { sender, context, argument ->
                        submitAsync {
                            val id = context["id"]
                            val amount = context["amount"].toInt()
                            val tabooPlayer = context.player("player")
                            // Convert to a Bukkit Player
                            val bukkitPlayer = tabooPlayer.castSafely<Player>()
                            val item = sertralineItemBuilder(id, bukkitPlayer, amount = amount) ?: run {
                                sender.sendStringAsComponent(
                                    sender.asLangText(
                                        "Item_Not_Exist", id
                                    )
                                )
                                null
                            }
                            item?.let {
                                bukkitPlayer?.giveItem(it)
                                bukkitPlayer?.sendStringAsComponent(
                                    bukkitPlayer.asLangText(
                                        "Item_Give",
                                        amount,
                                        PlatformCompat.getDisplayName(it).toMiniMessageString()
                                    )
                                )
                            }
                        }
                    }
                    suggestion<CommandSender>(uncheck = true) { sender, context ->
                        listOf("1","64","16")
                    }
                    bool("silent") {
                        execute<CommandSender> { sender, context, argument ->
                            submitAsync {
                                val id = context["id"]
                                val amount = context["amount"].toInt()
                                val tabooPlayer = context.player("player")
                                // Convert to a Bukkit Player
                                val bukkitPlayer = tabooPlayer.castSafely<Player>()
                                val item = sertralineItemBuilder(id, bukkitPlayer, amount = amount) ?: run {
                                    sender.sendStringAsComponent(
                                        sender.asLangText(
                                            "Item_Not_Exist", id
                                        )
                                    )
                                    null
                                }
                                item?.let {
                                    bukkitPlayer?.giveItem(it)
                                    if (context.bool("silent")) bukkitPlayer?.sendStringAsComponent(
                                        bukkitPlayer.asLangText(
                                            "Item_Give",
                                            amount,
                                            PlatformCompat.getDisplayName(it).toMiniMessageString()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


}
