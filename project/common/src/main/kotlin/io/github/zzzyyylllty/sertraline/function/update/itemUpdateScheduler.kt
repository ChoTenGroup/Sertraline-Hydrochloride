package io.github.zzzyyylllty.sertraline.function.update

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.logger.severeS
import org.bukkit.Bukkit
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor.PlatformTask

/**
 * Periodic item-update scanner (`item-update.triggers.async-interval`, in ticks, 0 = disabled).
 *
 * Runs on the **main thread**: the scan rewrites inventory slots (PlayerInventory#setItem), and
 * slot writes are not thread-safe. Cost per run is O(online players × 41 inventory slots + 27
 * ender-chest slots), and only items whose revision is behind are actually rebuilt, so a scan with
 * nothing to do is cheap. Raise the interval on busy servers (e.g. 6000 = 5 minutes) rather than
 * running it every tick.
 */
object ItemUpdateScheduler {

    private var task: PlatformTask? = null

    /** (Re)creates the repeating task from the current settings. Safe to call on every reload. */
    fun restart() {
        cancel()
        if (!SertralineSettings.itemUpdateEnabled) return
        val interval = SertralineSettings.itemUpdateAsyncInterval
        if (interval <= 0) return
        devLog("Item update scheduler: scanning online players every $interval tick(s)")
        task = submit(delay = interval.toLong(), period = interval.toLong(), async = false) {
            scanOnlinePlayers()
        }
    }

    fun cancel() {
        task?.cancel()
        task = null
    }

    /** Scans every online player's inventory once (main thread only). */
    fun scanOnlinePlayers(force: Boolean = false) {
        if (!SertralineSettings.itemUpdateEnabled) return
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                updatePlayerItems(player, force)
            } catch (e: Exception) {
                severeS("Failed to update items for ${player.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Defers one scan to the next tick. Used after a config reload, where the revision table has
     * just been recomputed and every online player may hold stale items — the reload itself may run
     * off the main thread, so the scan must be handed back to it.
     */
    fun scanOnlinePlayersLater(force: Boolean = false) {
        if (!SertralineSettings.itemUpdateEnabled) return
        submit(async = false, delay = 1L) { scanOnlinePlayers(force) }
    }
}
