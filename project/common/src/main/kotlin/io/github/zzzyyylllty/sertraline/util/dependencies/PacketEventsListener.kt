package io.github.zzzyyylllty.sertraline.util.dependencies

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemType
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.listener.packet.c2s
import io.github.zzzyyylllty.sertraline.listener.packet.s2c
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit
import taboolib.platform.util.isAir
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PacketEventsReceiveListener : PacketListener {
    override fun onPacketReceive(event: PacketReceiveEvent) {

        when (event.packetType) {
            PacketType.Play.Client.CLICK_WINDOW -> {
                val player = event.getPlayer<Player>()
                val packet = WrapperPlayClientClickWindow(event)

                packet.carriedItemStack = packet.carriedItemStack.let {
                    val bItem = SpigotConversionUtil.toBukkitItemStack(it ?: return)
                    if (bItem.isAir) return
                    SpigotConversionUtil.fromBukkitItemStack(bItem.c2s())
                }

                event.lastUsedWrapper = packet
                event.markForReEncode(true)
            }
            PacketType.Play.Client.CREATIVE_INVENTORY_ACTION -> {
                val player = event.getPlayer<Player>()
                val packet = WrapperPlayClientCreativeInventoryAction(event)

                packet.itemStack = packet.itemStack.let {
                    val bItem = SpigotConversionUtil.toBukkitItemStack(it ?: return)
                    if (bItem.isAir) return
                    SpigotConversionUtil.fromBukkitItemStack(bItem.c2s())
                }

                event.lastUsedWrapper = packet
                event.markForReEncode(true)
            }
        }
    }
}


class PacketEventsSendListener : PacketListener {
    override fun onPacketSend(event: PacketSendEvent) {
        if (!packetRenderingEnabled()) return
        val player = event.getPlayer<Player>() ?: return
        val viewer = event.user.uuid ?: return
        var changed = false
        fun rewrite(stack: ItemStack): ItemStack =
            PacketItemRendering.render(viewer, player, stack).also { if (it !== stack) changed = true }

        val packet = when (event.packetType) {
            PacketType.Play.Server.WINDOW_ITEMS -> WrapperPlayServerWindowItems(event).apply {
                items = items.orEmpty().map(::rewrite)
                carriedItem.orElse(null)?.let { setCarriedItem(rewrite(it)) }
            }
            PacketType.Play.Server.SET_PLAYER_INVENTORY -> WrapperPlayServerSetPlayerInventory(event).apply {
                stack = rewrite(stack)
            }
            PacketType.Play.Server.SET_CURSOR_ITEM -> WrapperPlayServerSetCursorItem(event).apply {
                stack = rewrite(stack)
            }
            PacketType.Play.Server.SET_SLOT -> WrapperPlayServerSetSlot(event).apply {
                item = rewrite(item)
            }
            else -> return
        }
        if (changed) {
            event.lastUsedWrapper = packet
            event.markForReEncode(true)
        }
    }
}

private fun packetRenderingEnabled(): Boolean {
    val config = SertralineSettings.currentSnapshot()
    return Sertraline.isEnabled && config.packetRegister && (config.packetLore || config.packetComponent)
}

fun handleItemStack(player: Player, itemStack: ItemStack): ItemStack =
    if (packetRenderingEnabled()) PacketItemRendering.render(player.uniqueId, player, itemStack) else itemStack

/** Packet IO uses protocol stacks only. Bukkit conversion, scripts and NBT serialization run on the server thread. */
internal object PacketItemRendering {
    private data class RenderKey(val viewer: UUID, val type: ItemType, val amount: Int, val metadataHash: Int)
    private data class Rendered(val original: ItemStack, val result: ItemStack?)
    private data class Request(val key: RenderKey, val player: Player, val item: ItemStack)

    private val cache by lazy {
        Caffeine.newBuilder().maximumSize(4096).expireAfterWrite(2, TimeUnit.SECONDS)
            .build<RenderKey, Rendered>().asMap()
    }
    private val queue = ArrayBlockingQueue<Request>(512)
    private val pending = ConcurrentHashMap.newKeySet<RenderKey>()
    private val scheduled = AtomicBoolean()
    private val reportedErrors = ConcurrentHashMap.newKeySet<Class<out Throwable>>()

    fun render(viewer: UUID, player: Player, item: ItemStack): ItemStack {
        if (item.type == ItemTypes.AIR || item.amount <= 0) return item
        val data = item.nbt ?: item.getComponent(ComponentTypes.CUSTOM_DATA).orElse(null) ?: return item
        if (data.getStringTagOrNull("sertraline_id") == null) return item
        val key = RenderKey(viewer, item.type, item.amount, data.hashCode())
        val cached = cache[key]
        if (cached != null && cached.original == item) return cached.result?.copy() ?: item
        if (Bukkit.isPrimaryThread()) return compute(player, item, key)?.copy() ?: item

        if (pending.add(key)) {
            if (queue.offer(Request(key, player, item.copy()))) schedule()
            else pending.remove(key)
        }
        return item
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        submit(delay = 1) {
            try {
                if (!packetRenderingEnabled()) {
                    clear()
                    return@submit
                }
                val refresh = LinkedHashSet<Player>()
                repeat(64) {
                    val request = queue.poll() ?: return@repeat
                    pending.remove(request.key)
                    if (!request.player.isOnline) return@repeat
                    if (compute(request.player, request.item, request.key) != null) refresh += request.player
                }
                refresh.forEach(Player::updateInventory)
            } finally {
                scheduled.set(false)
                if (queue.isNotEmpty()) schedule()
            }
        }
    }

    private fun compute(player: Player, item: ItemStack, key: RenderKey): ItemStack? {
        val result = try {
            val original = SpigotConversionUtil.toBukkitItemStack(item)
            val rendered = original.s2c(player)
            if (rendered === original) null else SpigotConversionUtil.fromBukkitItemStack(rendered)
        } catch (e: Exception) {
            if (reportedErrors.add(e.javaClass)) warningS("Could not render a Sertraline packet item: ${e.message}")
            null
        }
        cache[key] = Rendered(item.copy(), result)
        return result
    }

    fun clear() {
        cache.clear()
        queue.clear()
        pending.clear()
        reportedErrors.clear()
    }
}
