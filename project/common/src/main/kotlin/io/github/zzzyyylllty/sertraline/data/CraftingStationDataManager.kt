package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.database.CraftingSession
import io.github.zzzyyylllty.sertraline.database.DatabaseManager
import io.github.zzzyyylllty.sertraline.gui.CraftingStationManager
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.minimessage.toComponent
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submit
import taboolib.platform.util.deserializeToItemStack
import taboolib.platform.util.giveItem
import taboolib.platform.util.serializeToByteArray
import java.util.Base64

/**
 * Crafting station data manager.
 * Persists players' in-progress crafting sessions using the PTC Object ORM (CraftingSession table),
 * preventing material loss when the server closes or a player disconnects.
 */
object CraftingStationDataManager {

    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    /**
     * Saves a player's in-progress crafting session.
     */
    fun saveSession(player: Player, stationId: String, recipeId: String, startTime: Long, totalSeconds: Double, consumedItems: List<ItemStack>) {
        try {
            val uuid = player.uniqueId.toString()
            val itemsBlob = consumedItems.joinToString(";") { item ->
                base64Encoder.encodeToString(item.serializeToByteArray())
            }

            DatabaseManager.sessionMapper.insertOrUpdate(
                CraftingSession(uuid, stationId, recipeId, startTime, totalSeconds, itemsBlob)
            ) { "uuid" eq uuid }
        } catch (e: Exception) {
            severeS("Failed to save crafting session for ${player.name}: ${e.message}")
        }
    }

    /**
     * Reads a player's unfinished crafting session.
     * @return the session data, or null if none exists
     */
    fun loadSession(player: Player): PendingSession? {
        try {
            val uuid = player.uniqueId.toString()
            val session = DatabaseManager.sessionMapper.findById(uuid) ?: return null

            val items = if (session.consumedItemsBlob.isNotBlank()) {
                session.consumedItemsBlob.split(";").mapNotNull { encoded ->
                    try {
                        val bytes = base64Decoder.decode(encoded)
                        bytes.deserializeToItemStack()
                    } catch (e: Exception) {
                        warningS("Failed to deserialize consumed item for ${player.name}: ${e.message}")
                        null
                    }
                }
            } else emptyList()

            return PendingSession(session.stationId, session.recipeId, session.startTime, session.totalSeconds, items)
        } catch (e: Exception) {
            severeS("Failed to load crafting session for ${player.name}: ${e.message}")
            return null
        }
    }

    /**
     * Clears a player's crafting session data.
     */
    fun clearSession(player: Player) {
        try {
            DatabaseManager.sessionMapper.deleteById(player.uniqueId.toString())
        } catch (e: Exception) {
            warningS("Failed to clear crafting session for ${player.name}: ${e.message}")
        }
    }

    /**
     * Attempts to restore a player's unfinished crafting session.
     * - If the crafting time has elapsed -> grant the outputs
     * - If the crafting is unfinished -> return the materials
     */
    fun restoreSession(player: Player) {
        val pending = loadSession(player) ?: return

        // Always manipulate items on the main thread
        submit(async = false) {
            try {
                val station = Sertraline.craftingStations[pending.stationId]
                val recipe = station?.recipes?.values?.find { it.displayName == pending.recipeId }

                val now = System.currentTimeMillis()
                val elapsed = (now - pending.startTime) / 1000.0
                val isComplete = elapsed >= pending.totalSeconds

                if (isComplete && recipe != null) {
                    // Crafting completed -> grant the outputs
                    for (output in recipe.outputs) {
                        val stack = output.build(player)
                        player.giveItem(stack)
                    }
                    CraftingStationManager.fireLifecycleAgents(station!!, recipe, "onClaim", player)
                    PlatformCompat.sendComponent(player, SertralineSettings.msgCraftingComplete.toComponent()
                    )
                    infoS("Restored completed craft for ${player.name}: ${pending.recipeId}")
                } else {
                    // Crafting unfinished -> return the materials
                    for (item in pending.consumedItems) {
                        player.inventory.addItem(item).values.forEach { leftover ->
                            player.world.dropItem(player.location, leftover)
                        }
                    }
                    if (recipe != null) {
                        CraftingStationManager.fireLifecycleAgents(station!!, recipe, "onCancel", player)
                        PlatformCompat.sendComponent(player, "<yellow>Your crafting was interrupted. Materials returned.</yellow>".toComponent())
                    } else {
                        PlatformCompat.sendComponent(player, "<yellow>Your crafting station/recipe no longer exists. Materials returned.</yellow>".toComponent())
                    }
                    infoS("Returned materials to ${player.name} for interrupted craft: ${pending.recipeId}")
                }
            } catch (e: Exception) {
                severeS("Failed to restore crafting session for ${player.name}: ${e.message}")
                // Fallback: return materials whenever possible
                for (item in pending.consumedItems) {
                    try {
                        player.inventory.addItem(item).values.forEach { leftover ->
                            player.world.dropItem(player.location, leftover)
                        }
                    } catch (_: Exception) {}
                }
            } finally {
                clearSession(player)
            }
        }
    }

    /**
     * A crafting session awaiting restore.
     */
    data class PendingSession(
        val stationId: String,
        val recipeId: String,
        val startTime: Long,
        val totalSeconds: Double,
        val consumedItems: List<ItemStack>
    )
}
