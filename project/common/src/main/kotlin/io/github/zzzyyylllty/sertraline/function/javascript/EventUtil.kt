package io.github.zzzyyylllty.sertraline.function.javascript

import com.github.retrooper.packetevents.protocol.dialog.input.Input
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.item.adapter.transferBooleanToByte
import io.github.zzzyyylllty.sertraline.item.rebuildLore
import io.github.zzzyyylllty.sertraline.logger.severeS
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.ItemTag
import taboolib.module.nms.getItemTag
import taboolib.module.nms.setItemTag

object EventUtil {
    fun cancel(event: Cancellable,cancel: Boolean = true) {
        try {
            event.isCancelled = cancel
        } catch (e: Exception) {
            severeS("An error occurred while trying to cancel event $event")
            e.printStackTrace()
        }
    }

    /** Reads the event's cancelled state (a read-side convenience method for cancel). */
    fun isCancelled(event: Cancellable): Boolean {
        return try {
            event.isCancelled
        } catch (e: Exception) {
            severeS("An error occurred while trying to read event $event")
            false
        }
    }

    fun call(event: Event) {
        try {
            PlatformCompat.callEvent(event)
        } catch (e: Exception) {
            severeS("An error occurred while trying to calling event $event")
            e.printStackTrace()
        }
    }
}

fun a(e: EntityDamageByEntityEvent) {

}