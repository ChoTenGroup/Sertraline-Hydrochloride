package io.github.zzzyyylllty.sertraline.listener

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.CraftingStationDataManager
import io.github.zzzyyylllty.sertraline.gui.CraftingStationManager
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submitAsync

/**
 * Listens for player join/quit and handles crafting station session persistence.
 */
object CraftingStationListener {

    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player

        submitAsync {
            // Try to restore the incomplete crafting session
            CraftingStationDataManager.restoreSession(player)
        }
    }

    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player

        // Cancel the in-memory session; persisted data has already been written by the PTC object
        CraftingStationManager.cancelPlayerSession(player.uniqueId.toString())

        // Evict the player's private persistent cache to free memory (DB data is safe)
        Sertraline.manager.evictPlayer(player.uniqueId.toString())
    }
}
