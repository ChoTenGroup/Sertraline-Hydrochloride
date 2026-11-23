package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.database.CraftingSession
import io.github.zzzyyylllty.sertraline.database.DatabaseManager
import io.github.zzzyyylllty.sertraline.gui.CraftingStationManager
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
    fun saveSession(player: Player, stationId: String, recipeId: String, startTime: Long, totalSeconds: Double, consumedItems: List<ItemStack>): Boolean =
        try {
            val uuid = player.uniqueId.toString()
            val itemsBlob = consumedItems.joinToString(";") { item ->
                base64Encoder.encodeToString(item.serializeToByteArray())
            }

            DatabaseManager.sessionMapper.insertOrUpdate(
                CraftingSession(uuid, stationId, recipeId, startTime, totalSeconds, itemsBlob)
            ) { "uuid" eq uuid }
            true
        } catch (e: Exception) {
            severeS("Failed to save crafting session for ${player.name}: ${e.message}")
            false
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
                session.consumedItemsBlob.split(";").map { encoded ->
                    base64Decoder.decode(encoded).deserializeToItemStack()
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
    fun clearSession(player: Player): Boolean =
        try {
            DatabaseManager.sessionMapper.deleteById(player.uniqueId.toString())
            true
        } catch (e: Exception) {
            warningS("Failed to clear crafting session for ${player.name}: ${e.message}")
            false
        }

    /** Claims one stored session on the server thread. Legacy rows may store the display name. */
    fun restoreSession(player: Player) {
        submit {
            if (!player.isOnline) return@submit
            val pending = loadSession(player) ?: return@submit
            val station = Sertraline.craftingStations[pending.stationId]
            val recipe = station?.recipes?.get(pending.recipeId)
                ?: station?.recipes?.values?.firstOrNull { it.displayName == pending.recipeId }
            val elapsed = (System.currentTimeMillis() - pending.startTime) / 1000.0
            val outputs = if (recipe != null && elapsed >= pending.totalSeconds) {
                try {
                    recipe.outputs.map { output ->
                    output.build(player).also { stack ->
                        require(stack.type != org.bukkit.Material.AIR && stack.amount > 0) { "Crafting output is empty" }
                    }
                }
                } catch (e: Exception) {
                    severeS("Failed to restore crafting outputs for ${player.name}: ${e.message}")
                    null
                }
            } else null

            if (!clearSession(player)) return@submit
            if (outputs != null && recipe != null && station != null) {
                outputs.forEach { player.giveItem(it) }
                CraftingStationManager.fireLifecycleAgents(station, recipe, "onClaim", player)
                PlatformCompat.sendComponent(player, SertralineSettings.msgCraftingComplete.toComponent())
            } else {
                for (item in pending.consumedItems) {
                    player.inventory.addItem(item).values.forEach { player.world.dropItem(player.location, it) }
                }
                if (recipe != null && station != null) {
                    CraftingStationManager.fireLifecycleAgents(station, recipe, "onCancel", player)
                }
                PlatformCompat.sendComponent(player, "<yellow>Your crafting was interrupted. Materials returned.</yellow>".toComponent())
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
