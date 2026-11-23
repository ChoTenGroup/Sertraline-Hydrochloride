package io.github.zzzyyylllty.sertraline.command

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.manager.ItemManager
import io.github.zzzyyylllty.sertraline.manager.ManagerConfig
import io.github.zzzyyylllty.sertraline.manager.ManagerType
import io.github.zzzyyylllty.sertraline.manager.SubManagerType
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import io.github.zzzyyylllty.sertraline.util.linkedHashMapStringType
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.adaptCommandSender
import taboolib.module.lang.asLangText
import java.util.concurrent.ConcurrentHashMap

@CommandHeader(
    name = "manager",
    aliases = ["mgr"],
    permission = "sertraline.command.manager",
    permissionDefault = PermissionDefault.OP,
    newParser = false,
)
object ManagerCommand {

    // Per-player state for command context (parallel to Kether variables)
    private val playerManager = ConcurrentHashMap<String, String>()  // playerName -> managerType
    private val playerSub = ConcurrentHashMap<String, String>()      // playerName -> subType
    private val playerUuid = ConcurrentHashMap<String, String>()     // playerName -> uuid

    @CommandBody
    val main = subCommand {
        execute<CommandSender> { sender, _, _ ->
            sender.sendStringAsComponent(
                "<gradient:yellow:aqua>Usage:</gradient> <white>/manager <use|switch|create|clone|delete|list|info></white>"
            )
        }
    }

    @CommandBody
    val help = subCommand {
        execute<CommandSender> { sender, _, _ ->
            sender.sendStringAsComponent(
                """
                <gradient:yellow:aqua>=== manager Commands ===</gradient>
                <white>  use <public|private> [persistent|temporary]</white> <gray>- Set manager scope</gray>
                <white>  switch <uuid></white> <gray>- Switch private manager UUID</gray>
                <white>  create <id> <json></white> <gray>- Create item in current scope</gray>
                <white>  clone <templateId> <newId></white> <gray>- Clone an existing item</gray>
                <white>  delete <id></white> <gray>- Delete an item by ID</gray>
                <white>  list</white> <gray>- List items in current scope</gray>
                <white>  info <id></white> <gray>- Show item details</gray>
                """.trimIndent()
            )
        }
    }

    @CommandBody
    val use = subCommand {
        dynamic("type") {
            suggestion<CommandSender> { _, _ -> listOf("public", "private") }
            execute<CommandSender> { sender, context, _ ->
                val type = context["type"]
                val managerType = ManagerType.fromAlias(type)
                if (managerType == null) {
                    sender.sendStringAsComponent(sender.langText("Manager_Use_Invalid", type))
                    return@execute
                }
                val sub = SubManagerType.PERSISTENT
                val name = sender.name
                playerManager[name] = managerType.name.lowercase()
                playerSub[name] = sub.name.lowercase()
                playerUuid.remove(name)
                sender.sendStringAsComponent(sender.langText("Manager_Use_Set", managerType.name.lowercase(), sub.name.lowercase()))
            }
        }
        dynamic("sub") {
            suggestion<CommandSender> { _, _ -> listOf("persistent", "temporary", "pers", "temp", "p", "t") }
            execute<CommandSender> { sender, context, _ ->
                val type = context["type"]
                val subStr = context["sub"]
                val managerType = ManagerType.fromAlias(type)
                if (managerType == null) {
                    sender.sendStringAsComponent(sender.langText("Manager_Use_Invalid", type))
                    return@execute
                }
                val subType = SubManagerType.fromAlias(subStr)
                if (subType == null) {
                    sender.sendStringAsComponent(sender.langText("Manager_Use_Invalid_Sub", subStr))
                    return@execute
                }
                val name = sender.name
                playerManager[name] = managerType.name.lowercase()
                playerSub[name] = subType.name.lowercase()
                playerUuid.remove(name)
                sender.sendStringAsComponent(sender.langText("Manager_Use_Set", managerType.name.lowercase(), subType.name.lowercase()))
            }
        }
    }

    @CommandBody
    val switch = subCommand {
        dynamic("uuid") {
            execute<CommandSender> { sender, context, _ ->
                val uuid = context["uuid"]
                playerUuid[sender.name] = uuid
                sender.sendStringAsComponent(sender.langText("Manager_Switch_UUID", uuid))
            }
        }
    }

    @CommandBody
    val create = subCommand {
        dynamic("itemId") {
            execute<CommandSender> { sender, context, _ ->
                val itemId = context["itemId"]
                sender.sendStringAsComponent(sender.langText("Manager_Create_Error_PublicPersistent"))
            }
        }
        dynamic("jsonBody") {
            suggestion<CommandSender> { _, _ ->
                listOf("""{"xbuilder:{material:stone}}""")
            }
            execute<CommandSender> { sender, context, _ ->
                val itemId = context["itemId"]
                val jsonBody = context["jsonBody"]
                val name = sender.name
                val type = ManagerType.fromAlias(playerManager[name] ?: ManagerConfig.defaultScope)
                    ?: ManagerType.PUBLIC
                val sub = SubManagerType.fromAlias(playerSub[name] ?: ManagerConfig.defaultSub)
                    ?: SubManagerType.PERSISTENT

                submitAsync {
                    try {
                        val data: Map<String, Any?> = jsonUtils.fromJson(jsonBody, linkedHashMapStringType) ?: linkedMapOf()
                        val uuid = if (type == ManagerType.PRIVATE) {
                            resolveCommandUuid(sender, name)
                        } else null
                        Sertraline.manager.createItem(type, sub, itemId, data, uuid)
                        sender.sendStringAsComponent(sender.langText("Manager_Create_Success", itemId))
                    } catch (e: UnsupportedOperationException) {
                        sender.sendStringAsComponent(sender.langText("Manager_Create_Error_PublicPersistent"))
                    } catch (e: IllegalStateException) {
                        sender.sendStringAsComponent("<red>${e.message}</red>")
                    } catch (e: Exception) {
                        sender.sendStringAsComponent("<red>Failed to create item: ${e.message}</red>")
                    }
                }
            }
        }
    }

    @CommandBody
    val clone = subCommand {
        dynamic("templateId") {
            suggestion<CommandSender> { sender, _ ->
                suggestItemIds(sender)
            }
            execute<CommandSender> { sender, context, _ ->
                sender.sendStringAsComponent("<yellow>Usage: /manager clone <templateId> <newId></yellow>")
            }
        }
        dynamic("newId") {
            execute<CommandSender> { sender, context, _ ->
                val templateId = context["templateId"]
                val newId = context["newId"]
                val name = sender.name
                val type = resolveType(name)
                val sub = resolveSub(name)

                submitAsync {
                    try {
                        val uuid = if (type == ManagerType.PRIVATE) resolveCommandUuid(sender, name) else null
                        val template = Sertraline.manager.getItem(type, sub, templateId, uuid)
                        if (template == null) {
                            sender.sendStringAsComponent(sender.langText("Manager_Info_Not_Found", templateId))
                            return@submitAsync
                        }
                        val data = template.data.toMap()
                        Sertraline.manager.createItem(type, sub, newId, data, uuid)
                        sender.sendStringAsComponent(sender.langText("Manager_Create_Success", newId))
                    } catch (e: UnsupportedOperationException) {
                        sender.sendStringAsComponent(sender.langText("Manager_Create_Error_PublicPersistent"))
                    } catch (e: IllegalStateException) {
                        sender.sendStringAsComponent("<red>${e.message}</red>")
                    } catch (e: Exception) {
                        sender.sendStringAsComponent("<red>Failed to clone item: ${e.message}</red>")
                    }
                }
            }
        }
    }

    @CommandBody
    val delete = subCommand {
        dynamic("itemId") {
            suggestion<CommandSender> { sender, _ ->
                suggestItemIds(sender)
            }
            execute<CommandSender> { sender, context, _ ->
                val itemId = context["itemId"]
                val name = sender.name
                val type = resolveType(name)
                val sub = resolveSub(name)
                submitAsync {
                    try {
                        val uuid = if (type == ManagerType.PRIVATE) resolveCommandUuid(sender, name) else null
                        if (Sertraline.manager.deleteItemResult(type, sub, itemId, uuid)) {
                            sender.sendStringAsComponent(sender.langText("Manager_Delete_Success", itemId))
                        } else {
                            sender.sendStringAsComponent(sender.langText("Manager_Delete_Not_Found", itemId))
                        }
                    } catch (e: Exception) {
                        sender.sendStringAsComponent("<red>${e.message}</red>")
                    }
                }
            }
        }
    }

    @CommandBody
    val list = subCommand {
        execute<CommandSender> { sender, _, _ ->
            val name = sender.name
            val type = resolveType(name)
            val sub = resolveSub(name)
            val uuid = if (type == ManagerType.PRIVATE) resolveCommandUuid(sender, name) else null

            val items = when (type) {
                ManagerType.PUBLIC -> Sertraline.manager.public.getAll(sub)
                ManagerType.PRIVATE -> {
                    val u = uuid ?: run {
                        sender.sendStringAsComponent(sender.langText("Manager_Error_No_UUID"))
                        return@execute
                    }
                    Sertraline.manager.privateManager.getAll(u, sub)
                }
            }
            val header = sender.langText("Manager_List_Header", type.name.lowercase(), sub.name.lowercase())
            sender.sendStringAsComponent(header)
            if (items.isEmpty()) {
                sender.sendStringAsComponent(sender.langText("Manager_List_Empty"))
            } else {
                items.keys.forEach { sender.sendStringAsComponent(sender.langText("Manager_List_Entry", it)) }
            }
        }
    }

    @CommandBody
    val info = subCommand {
        dynamic("itemId") {
            suggestion<CommandSender> { sender, _ ->
                suggestItemIds(sender)
            }
            execute<CommandSender> { sender, context, _ ->
                val itemId = context["itemId"]
                showInfo(sender, itemId)
            }
        }
    }

    private fun CommandSender.langText(node: String, vararg args: Any): String {
        return adaptCommandSender(this).asLangText(node, *args)
    }

    private fun showInfo(sender: CommandSender, itemId: String) {
        val name = sender.name
        val type = resolveType(name)
        val sub = resolveSub(name)
        val uuid = if (type == ManagerType.PRIVATE) resolveCommandUuid(sender, name) else null
        val item = Sertraline.manager.getItem(type, sub, itemId, uuid)
        if (item == null) {
            sender.sendStringAsComponent(sender.langText("Manager_Info_Not_Found", itemId))
            return
        }
        sender.sendStringAsComponent(sender.langText("Manager_Info_Header", itemId))
        sender.sendStringAsComponent("<gray>  Scope: <white>${type.name.lowercase()}/${sub.name.lowercase()}</white></gray>")
        sender.sendStringAsComponent("<gray>  Key: <white>${item.key}</white></gray>")
        sender.sendStringAsComponent("<gray>  Data keys: <white>${item.data.keys.joinToString(", ")}</white></gray>")
        sender.sendStringAsComponent("<gray>  Config keys: <white>${item.config.keys.joinToString(", ")}</white></gray>")
    }

    /** Suggest item IDs in the current scope for tab completion. */
    private fun suggestItemIds(sender: CommandSender): List<String> {
        val name = sender.name
        val type = resolveType(name)
        val sub = resolveSub(name)
        return try {
            val uuid = if (type == ManagerType.PRIVATE) resolveCommandUuid(sender, name) else null
            when (type) {
                ManagerType.PUBLIC -> Sertraline.manager.public.getAll(sub).keys.toList()
                ManagerType.PRIVATE -> Sertraline.manager.privateManager.cachedIds(uuid!!, sub)
            }
        } catch (_: Exception) { emptyList() }
    }

    @taboolib.common.platform.event.SubscribeEvent
    fun forgetSelection(event: org.bukkit.event.player.PlayerQuitEvent) {
        val name = event.player.name
        playerManager.remove(name)
        playerSub.remove(name)
        playerUuid.remove(name)
    }

    private fun resolveType(playerName: String): ManagerType {
        return ManagerType.fromAlias(playerManager[playerName] ?: ManagerConfig.defaultScope) ?: ManagerType.PUBLIC
    }

    private fun resolveSub(playerName: String): SubManagerType {
        return SubManagerType.fromAlias(playerSub[playerName] ?: ManagerConfig.defaultSub) ?: SubManagerType.PERSISTENT
    }

    @Throws(IllegalStateException::class)
    private fun resolveCommandUuid(sender: CommandSender, playerName: String): String {
        val switchUuid = playerUuid[playerName]
        if (!switchUuid.isNullOrBlank()) return switchUuid
        if (sender is Player) return sender.uniqueId.toString()
        val auto = ManagerConfig.autoUuid
        if (auto.isNotBlank()) return auto
        throw IllegalStateException("No UUID available for private manager operation. Use '/manager switch <uuid>' or run as player.")
    }
}
