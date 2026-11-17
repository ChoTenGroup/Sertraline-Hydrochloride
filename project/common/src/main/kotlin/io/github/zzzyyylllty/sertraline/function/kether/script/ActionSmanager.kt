package io.github.zzzyyylllty.sertraline.function.kether.script

import io.github.zzzyyylllty.sertraline.Sertraline.manager
import io.github.zzzyyylllty.sertraline.manager.ItemManager
import io.github.zzzyyylllty.sertraline.manager.ManagerConfig
import io.github.zzzyyylllty.sertraline.manager.ManagerType
import io.github.zzzyyylllty.sertraline.manager.SubManagerType
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import io.github.zzzyyylllty.sertraline.util.linkedHashMapStringType
import org.bukkit.entity.Player
import taboolib.module.kether.*
import java.util.concurrent.CompletableFuture

/**
 * smanager Kether actions.
 *
 * Usage:
 *   smanager use <public|private> [persistent|temporary]
 *   smanager switch <uuid>
 *   smanager create <itemId> by <jsonBody> [in <subPath>] [on <sync|async>]
 *   smanager select <itemId>
 *   smanager delete
 *   smanager list
 *   smanager info [itemId]
 */
class ActionSmanager {

    class Use(val type: ManagerType, val sub: SubManagerType) : ScriptAction<Boolean>() {
        override fun run(frame: ScriptFrame): CompletableFuture<Boolean> {
            frame.variables().set(ItemManager.Vars.MANAGER, type.name.lowercase())
            frame.variables().set(ItemManager.Vars.SUB, sub.name.lowercase())
            frame.variables().remove(ItemManager.Vars.SELECTED)
            frame.variables().remove(ItemManager.Vars.UUID)
            return CompletableFuture.completedFuture(true)
        }
    }

    class Switch(val uuid: String) : ScriptAction<Boolean>() {
        override fun run(frame: ScriptFrame): CompletableFuture<Boolean> {
            frame.variables().set(ItemManager.Vars.UUID, uuid)
            return CompletableFuture.completedFuture(true)
        }
    }

    class Create(
        val itemId: String,
        val jsonBody: String,
        val subPath: String?,
        val async: Boolean
    ) : ScriptAction<Boolean>() {
        override fun run(frame: ScriptFrame): CompletableFuture<Boolean> {
            val type = resolveManagerType(frame)
            val sub = resolveSubType(frame)

            val data: Map<String, Any?> = try {
                jsonUtils.fromJson(jsonBody, linkedHashMapStringType) ?: linkedMapOf()
            } catch (e: Exception) {
                error("Failed to parse item data JSON: ${e.message}")
            }

            val action: () -> Boolean = {
                try {
                    val uuid = if (type == ManagerType.PRIVATE) resolvePrivateUuid(frame) else null
                    manager.createItem(type, sub, itemId, data, uuid)
                    true
                } catch (e: UnsupportedOperationException) {
                    error(e.message ?: "Operation not supported")
                } catch (e: IllegalStateException) {
                    error(e.message ?: "Failed to create item")
                }
            }

            return if (async) {
                CompletableFuture.supplyAsync(action)
            } else {
                CompletableFuture.completedFuture(action())
            }
        }
    }

    class Select(val itemId: String) : ScriptAction<Boolean>() {
        override fun run(frame: ScriptFrame): CompletableFuture<Boolean> {
            val type = resolveManagerType(frame)
            val sub = resolveSubType(frame)
            val uuid = if (type == ManagerType.PRIVATE) resolvePrivateUuid(frame) else null
            val item = manager.getItem(type, sub, itemId, uuid)
            if (item == null) {
                error("Item $itemId not found in current scope")
            }
            frame.variables().set(ItemManager.Vars.SELECTED, itemId)
            return CompletableFuture.completedFuture(true)
        }
    }

    class Delete : ScriptAction<Boolean>() {
        override fun run(frame: ScriptFrame): CompletableFuture<Boolean> {
            val selected = getStringVariable(frame, ItemManager.Vars.SELECTED)
                ?: error("No item selected. Use 'smanager select <id>' first.")
            val type = resolveManagerType(frame)
            val sub = resolveSubType(frame)
            val uuid = if (type == ManagerType.PRIVATE) resolvePrivateUuid(frame) else null
            try {
                val deleted = manager.deleteItemResult(type, sub, selected, uuid)
                if (!deleted) return CompletableFuture.completedFuture(false)
                frame.variables().remove(ItemManager.Vars.SELECTED)
                return CompletableFuture.completedFuture(true)
            } catch (e: Exception) {
                error(e.message ?: "Failed to delete item")
            }
        }
    }

    class ListAction : ScriptAction<Collection<String>>() {
        override fun run(frame: ScriptFrame): CompletableFuture<Collection<String>> {
            val type = resolveManagerType(frame)
            val sub = resolveSubType(frame)
            val items: Collection<String> = when (type) {
                ManagerType.PUBLIC -> manager.public.getAll(sub).keys
                ManagerType.PRIVATE -> {
                    val uuid = resolvePrivateUuid(frame)
                    manager.privateManager.getAll(uuid, sub).keys
                }
            }
            return CompletableFuture.completedFuture(items)
        }
    }

    class Info(val itemId: String?) : ScriptAction<String>() {
        override fun run(frame: ScriptFrame): CompletableFuture<String> {
            val id = itemId ?: getStringVariable(frame, ItemManager.Vars.SELECTED)
                ?: error("No item specified and no item selected. Use 'smanager info <id>' or 'smanager select <id>' first.")
            val type = resolveManagerType(frame)
            val sub = resolveSubType(frame)
            val uuid = if (type == ManagerType.PRIVATE) resolvePrivateUuid(frame) else null
            val item = manager.getItem(type, sub, id, uuid)
                ?: error("Item $id not found in current scope")
            return CompletableFuture.completedFuture("Item: $id (type: ${type.name.lowercase()}/${sub.name.lowercase()}, data keys: ${item.data.keys})")
        }
    }

    companion object {
        @KetherParser(["smanager"], shared = true)
        fun parser() = scriptParser {
            when (val action = it.nextToken()) {
                "use" -> {
                    val typeStr = it.nextToken()
                    val type = ManagerType.fromAlias(typeStr)
                        ?: error("Unknown manager type: $typeStr (expected: public, private)")
                    val sub = try {
                        it.mark()
                        SubManagerType.fromAlias(it.nextToken())
                    } catch (_: Exception) {
                        it.reset(); null
                    } ?: SubManagerType.PERSISTENT
                    Use(type, sub)
                }
                "switch" -> {
                    val uuid = it.nextToken()
                    Switch(uuid)
                }
                "create" -> {
                    val itemId = it.nextToken()
                    it.expects("by")
                    val jsonBody = it.nextToken()
                    val subPath = try {
                        it.mark()
                        it.expects("in")
                        it.nextToken()
                    } catch (_: Exception) {
                        it.reset(); null
                    }
                    val async = try {
                        it.mark()
                        it.expects("on")
                        when (it.nextToken()) {
                            "async" -> true
                            "sync" -> false
                            else -> error("Expected 'sync' or 'async'")
                        }
                    } catch (_: Exception) {
                        it.reset(); false
                    }
                    Create(itemId, jsonBody, subPath, async)
                }
                "select" -> {
                    val itemId = it.nextToken()
                    Select(itemId)
                }
                "delete" -> Delete()
                "list" -> ListAction()
                "info" -> {
                    val itemId = try { it.nextToken() } catch (_: Exception) { null }
                    Info(itemId)
                }
                else -> error("Unknown smanager action: $action (expected: use, switch, create, select, delete, list, info)")
            }
        }

        private fun getStringVariable(frame: ScriptFrame, key: String): String? {
            return try { frame.variables().get<String>(key)?.orElse(null) } catch (_: Exception) { null }
        }

        private fun resolveManagerType(frame: ScriptFrame): ManagerType {
            val str = getStringVariable(frame, ItemManager.Vars.MANAGER)
            return ManagerType.fromAlias(str ?: ManagerType.PUBLIC.name.lowercase()) ?: ManagerType.PUBLIC
        }

        private fun resolveSubType(frame: ScriptFrame): SubManagerType {
            val str = getStringVariable(frame, ItemManager.Vars.SUB)
            return SubManagerType.fromAlias(str ?: SubManagerType.PERSISTENT.name.lowercase()) ?: SubManagerType.PERSISTENT
        }

        private fun resolvePrivateUuid(frame: ScriptFrame): String {
            // 1. explicit UUID from switch
            val switchUuid = getStringVariable(frame, ItemManager.Vars.UUID)
            if (!switchUuid.isNullOrBlank()) return switchUuid
            // 2. player UUID from Kether context
            val player = frame.script().sender
            if (player is Player) {
                return player.uniqueId.toString()
            }
            // 3. auto UUID from config
            val auto = ManagerConfig.autoUuid
            if (auto.isNotBlank()) return auto
            // 4. error
            error("No UUID available for private manager operation. Use 'smanager switch <uuid>' or run in player context.")
        }
    }
}
