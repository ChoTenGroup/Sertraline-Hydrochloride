package io.github.zzzyyylllty.sertraline.command

import io.github.zzzyyylllty.embiancomponent.EmbianComponent.SafetyComponentSetter
import io.github.zzzyyylllty.embiancomponent.tools.getComponentsNMSFiltered
import io.github.zzzyyylllty.sertraline.Sertraline.itemCache
import io.github.zzzyyylllty.sertraline.Sertraline.itemManager
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.Sertraline.mappings
import io.github.zzzyyylllty.sertraline.Sertraline.tagManager
import io.github.zzzyyylllty.sertraline.item.rebuild
import io.github.zzzyyylllty.sertraline.item.rebuildLore
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.impl.getComponentsNMS
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.util.ComponentFormatter
import io.github.zzzyyylllty.sertraline.util.data.DataUtil
import io.github.zzzyyylllty.sertraline.util.minimessage.mmUtil
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.bool
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.player
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.submitAsync
import taboolib.common.util.asList
import taboolib.module.nms.NMSItemTag.Companion.asNMSCopy
import taboolib.module.nms.getItemTag
import taboolib.platform.util.asLangText
import taboolib.platform.util.giveItem

@CommandHeader(
    name = "sertralinedata",
    aliases = ["needyitemdata","depazdata"],
    permission = "sertraline.command.debug",
    description = "DEBUG Command of DepazItems.",
    permissionMessage = "",
    permissionDefault = PermissionDefault.OP,
    newParser = false,
)
object DataCommand {

    @CommandBody
    val main = mainCommand {
        createModernHelper()
    }

    @CommandBody
    val help = subCommand {
        createModernHelper()
    }

    @CommandBody
    val get = subCommand {
        dynamic("id") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val player = sender as? Player? ?: run {
                        sender.severeS(sender.asLangText("Player_Only_Command"))
                        return@submitAsync
                    }
                    val key = context["id"]
                    val data = DataUtil.getDataRaw(player, key)
                    var message = sender.asLangText("PlayerData_Fetch", player.name, key, data ?: "<i>null")
                    sender.infoS(message, false)
                }
            }
            player("player") {
                execute<CommandSender> { sender, context, argument ->
                    submitAsync {
                        val id = context["id"]
                        val tabooPlayer = context.player("player")
                        // Convert to a Bukkit Player
                        val player = tabooPlayer.castSafely<Player>() ?: run {
                            sender.severeS(sender.asLangText("Player_Not_Exist"))
                            return@submitAsync
                        }
                        val key = context["id"]
                        val data = DataUtil.getDataRaw(player, key)
                        var message = sender.asLangText("PlayerData_Fetch", player.name, key, data ?: "<i>null")
                        sender.infoS(message, false)
                    }
                }
            }
        }
    }


    @CommandBody
    val remove = subCommand {
        dynamic("id") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val player = sender as? Player? ?: run {
                        sender.severeS(sender.asLangText("Player_Only_Command"))
                        return@submitAsync
                    }
                    val key = context["id"]
                    DataUtil.removeData(player, key)
                    var message = sender.asLangText("PlayerData_Remove", player.name, key)
                    sender.infoS(message, false)
                }
            }
            player("player") {
                execute<CommandSender> { sender, context, argument ->
                    submitAsync {
                        val id = context["id"]
                        val tabooPlayer = context.player("player")
                        // Convert to a Bukkit Player
                        val player = tabooPlayer.castSafely<Player>() ?: run {
                            sender.severeS(sender.asLangText("Player_Not_Exist"))
                            return@submitAsync
                        }
                        val key = context["id"]
                        DataUtil.removeData(player, key)
                        var message = sender.asLangText("PlayerData_Remove", player.name, key)
                        sender.infoS(message, false)
                    }
                }
            }
        }
    }


    @CommandBody
    val set = subCommand {
        player("player") {
            dynamic("id") {
                dynamic("value") {
                    execute<CommandSender> { sender, context, argument ->
                        submitAsync {
                            val id = context["id"]
                            val dvalue = context["value"]
                            val tabooPlayer = context.player("player")
                            // Convert to a Bukkit Player
                            val player = tabooPlayer.castSafely<Player>() ?: run {
                                sender.severeS(sender.asLangText("Player_Not_Exist"))
                                return@submitAsync
                            }
                            val key = context["id"]
                            DataUtil.setData(player, key, dvalue)
                            var message = sender.asLangText("PlayerData_Modify", player.name, key, dvalue)
                            sender.infoS(message, false)
                        }
                    }
                }
            }
        }
    }

    @CommandBody
    val clear = subCommand {
        execute<CommandSender> { sender, context, argument ->
            submitAsync {
                val player = sender as? Player? ?: run {
                    sender.severeS(sender.asLangText("Player_Only_Command"))
                    return@submitAsync
                }
                DataUtil.resetAllData(player)
                var message = sender.asLangText("PlayerData_Clear", player.name)
                sender.infoS(message, false)
            }

        }
        player("player") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val tabooPlayer = context.player("player")
                    // Convert to a Bukkit Player
                    val player = tabooPlayer.castSafely<Player>() ?: run {
                        sender.severeS(sender.asLangText("Player_Not_Exist"))
                        return@submitAsync
                    }
                    DataUtil.resetAllData(player)
                    var message = sender.asLangText("PlayerData_Clear", player.name)
                    sender.infoS(message, false)
                }

            }
        }
    }

    @CommandBody
    val getCooldown = subCommand {
        dynamic("id") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val player = sender as? Player? ?: run {
                        sender.severeS(sender.asLangText("Player_Only_Command"))
                        return@submitAsync
                    }
                    val key = context["id"]
                    val data = DataUtil.getCooldownLeftLong(player, key)?.toDouble()?.div(1000)
                    var message = sender.asLangText("PlayerCooldown_Fetch", player.name, key, data ?: "<i>null")
                    sender.infoS(message, false)
                }
            }
            player("player") {
                execute<CommandSender> { sender, context, argument ->
                    submitAsync {
                        val id = context["id"]
                        val tabooPlayer = context.player("player")
                        // Convert to a Bukkit Player
                        val player = tabooPlayer.castSafely<Player>() ?: run {
                            sender.severeS(sender.asLangText("Player_Not_Exist"))
                            return@submitAsync
                        }
                        val key = context["id"]
                        val data = DataUtil.getCooldownLeftLong(player, key)?.toDouble()?.div(1000)
                        var message = sender.asLangText("PlayerCooldown_Fetch", player.name, key, data ?: "<i>null")
                        sender.infoS(message, false)
                    }
                }
            }
        }
    }


    @CommandBody
    val removeCooldown = subCommand {
        dynamic("id") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val player = sender as? Player? ?: run {
                        sender.severeS(sender.asLangText("Player_Only_Command"))
                        return@submitAsync
                    }
                    val key = context["id"]
                    DataUtil.resetCooldown(player, key)
                    var message = sender.asLangText("PlayerCooldown_Remove", player.name, key)
                    sender.infoS(message, false)
                }
            }
            player("player") {
                execute<CommandSender> { sender, context, argument ->
                    submitAsync {
                        val id = context["id"]
                        val tabooPlayer = context.player("player")
                        // Convert to a Bukkit Player
                        val player = tabooPlayer.castSafely<Player>() ?: run {
                            sender.severeS(sender.asLangText("Player_Not_Exist"))
                            return@submitAsync
                        }
                        val key = context["id"]
                        DataUtil.resetCooldown(player, key)
                        var message = sender.asLangText("PlayerCooldown_Remove", player.name, key)
                        sender.infoS(message, false)
                    }
                }
            }
        }
    }


    @CommandBody
    val setCooldown = subCommand {
        player("player") {
            dynamic("id") {
                dynamic("value") {
                    execute<CommandSender> { sender, context, argument ->
                        submitAsync {
                            val id = context["id"]
                            val dvalue = context["value"]
                            val tabooPlayer = context.player("player")
                            // Convert to a Bukkit Player
                            val player = tabooPlayer.castSafely<Player>() ?: run {
                                sender.severeS(sender.asLangText("Player_Not_Exist"))
                                return@submitAsync
                            }
                            val key = context["id"]
                            DataUtil.setCooldown(player, key, dvalue.toDouble())
                            var message = sender.asLangText("PlayerCooldown_Modify", player.name, key, dvalue)
                            sender.infoS(message, false)
                        }
                    }
                }
            }
        }
    }

    @CommandBody
    val clearCooldown = subCommand {
        execute<CommandSender> { sender, context, argument ->
            submitAsync {
                val player = sender as? Player? ?: run {
                    sender.severeS(sender.asLangText("Player_Only_Command"))
                    return@submitAsync
                }
                DataUtil.resetAllCooldown(player)
                var message = sender.asLangText("PlayerCooldown_Clear", player.name)
                sender.infoS(message, false)
            }

        }
        player("player") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val tabooPlayer = context.player("player")
                    // Convert to a Bukkit Player
                    val player = tabooPlayer.castSafely<Player>() ?: run {
                        sender.severeS(sender.asLangText("Player_Not_Exist"))
                        return@submitAsync
                    }
                    DataUtil.resetAllCooldown(player)
                    var message = sender.asLangText("PlayerCooldown_Clear", player.name)
                    sender.infoS(message, false)
                }

            }
        }
    }

    @CommandBody
    val browse = subCommand {
        execute<CommandSender> { sender, context, argument ->
            submitAsync {
                val player = sender as? Player? ?: run {
                    sender.severeS(sender.asLangText("Player_Only_Command"))
                    return@submitAsync
                }

                sender.sendStringAsComponent(browseDataMap(sender, player.name, DataUtil.getAllDataRaw(player)))
            }

        }
        player("player") {
            execute<CommandSender> { sender, context, argument ->
                submitAsync {
                    val tabooPlayer = context.player("player")
                    // Convert to a Bukkit Player
                    val player = tabooPlayer.castSafely<Player>() ?: run {
                        sender.severeS(sender.asLangText("Player_Not_Exist"))
                        return@submitAsync
                    }
                    sender.sendStringAsComponent(browseDataMap(sender, player.name, DataUtil.getAllDataRaw(player)))
                }

            }
        }
    }

    fun browseDataMap(sender: CommandSender, name: String, map: Map<String, String>): String {
        var str = sender.asLangText("PlayerData_Browse_Title", name)
        for (entry in map) {
            str += "<br>${sender.asLangText("PlayerData_Browse_Section", entry.key, entry.value)}"
        }
        if (map.isEmpty()) sender.sendStringAsComponent(sender.asLangText("PlayerData_Browse_Empty"))
        return str
            .replace("cooldown.", "<#ffcc66><u>cooldown.</u></#ffcc66>")
    }
}