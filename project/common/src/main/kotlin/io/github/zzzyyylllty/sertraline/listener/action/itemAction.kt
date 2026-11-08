package io.github.zzzyyylllty.sertraline.listener.action

import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.impl.getComponent
import io.github.zzzyyylllty.sertraline.impl.getComponentJava
import io.github.zzzyyylllty.sertraline.util.ActionHelper.throttleAction
import net.kyori.adventure.title.Title
import org.bukkit.event.block.Action
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent


data class ThrottleActionLink(
    val uuid: String,
    val str: String,
    val subStr: String? = null,
)
data class ThrottleActionParam(
    val p: Player,
    val e: Event,
    val ce: Cancellable?,
    val bItem : ItemStack? = null,
    val abItem : ItemStack? = null,
    val sItemId : String? = null
)

@SubscribeEvent
fun onInteract(e: PlayerInteractEvent) {
    val uuid = e.player.uniqueId.toString()
    // PlayerInteractEvent fires once per hand and `e.item` belongs to the hand of *this* event.
    // Without the hand check both hands dispatched the same `onClick`/`onRightClick` key, so with
    // `action.throttle.onClick: 0` (throttling off) one physical right-click ran the whole action
    // chain twice — and the second run used the off-hand item. Mirror the @main/@off model that
    // onLogin / onConsume / onMine already use.
    val handSuffix: String? = when (e.hand) {
        EquipmentSlot.HAND -> null        // main hand keeps the bare action name (backwards compatible)
        EquipmentSlot.OFF_HAND -> "off"   // off hand gets an explicit variant
        else -> return                    // no hand information: nothing item-bound to run
    }
    if (handSuffix == "off" && e.item == null) return

    val param = ThrottleActionParam(e.player, e, e, e.item)

    throttleAction(ThrottleActionLink(uuid, "onClick", handSuffix), param)
    if (e.action == Action.RIGHT_CLICK_AIR || e.action == Action.RIGHT_CLICK_BLOCK) {
        throttleAction(ThrottleActionLink(uuid, "onRightClick", handSuffix), param)
    } else if (e.action == Action.LEFT_CLICK_AIR || e.action == Action.LEFT_CLICK_BLOCK) {
        throttleAction(ThrottleActionLink(uuid, "onLeftClick", handSuffix), param)
    }
}

@SubscribeEvent
fun onShoot(e: EntityShootBowEvent) {
    val player = e.entity as? Player? ?: return
    val uuid = player.uniqueId.toString()
    val param = ThrottleActionParam(player, e, e, e.bow)
    throttleAction(ThrottleActionLink(uuid, "onShoot"), param)
}

@SubscribeEvent
fun onLogin(e: PlayerLoginEvent) {
    val inv = e.player.inventory
    val uuid = e.player.uniqueId.toString()
    throttleAction(ThrottleActionLink(uuid, "onLogin", "main"), ThrottleActionParam(e.player, e, null, inv.itemInMainHand))
    throttleAction(ThrottleActionLink(uuid, "onLogin", "off"), ThrottleActionParam(e.player, e, null, inv.itemInOffHand))
    inv.helmet?.let { throttleAction(ThrottleActionLink(uuid, "onLogin", "helmet"), ThrottleActionParam(e.player, e, null, it)) }
    inv.chestplate?.let { throttleAction(ThrottleActionLink(uuid, "onLogin", "chestplate"), ThrottleActionParam(e.player, e, null, it)) }
    inv.leggings?.let { throttleAction(ThrottleActionLink(uuid, "onLogin", "leggings"), ThrottleActionParam(e.player, e, null, it)) }
    inv.boots?.let { throttleAction(ThrottleActionLink(uuid, "onLogin", "boots"), ThrottleActionParam(e.player, e, null, it)) }
}

@SubscribeEvent
fun onMine(e: BlockBreakEvent) {
    val inv = e.player.inventory
    val uuid = e.player.uniqueId.toString()
    throttleAction(ThrottleActionLink(uuid, "onMine", "main"), ThrottleActionParam(e.player, e, e, inv.itemInMainHand))
    throttleAction(ThrottleActionLink(uuid, "onMine", "off"), ThrottleActionParam(e.player, e, e, inv.itemInOffHand))
}

@SubscribeEvent
fun onAttack(e: EntityDamageByEntityEvent) {
    // damageSource/AbstractArrow.weapon are 1.13+ APIs, so we go through PlatformCompat (v11200 reflection fallback)
    val player = PlatformCompat.getDamageCausingPlayer(e) ?: return
    var item: ItemStack? = null
    val dEntity = PlatformCompat.getDamageDirectEntity(e)
    if (dEntity is Player) {
        item = dEntity.inventory.itemInMainHand
    } else {
        item = PlatformCompat.getProjectileWeapon(dEntity)
    }
    throttleAction(ThrottleActionLink(player.uniqueId.toString(), "onAttack"), ThrottleActionParam(player, e, e, item))
}

@SubscribeEvent(priority = taboolib.common.platform.event.EventPriority.MONITOR)
fun onConsume(e: PlayerItemConsumeEvent) {
    val uuid = e.player.uniqueId.toString()
    throttleAction(ThrottleActionLink(uuid, "onConsume"), ThrottleActionParam(e.player, e, e, e.item, PlatformCompat.getConsumeReplacement(e)))
}

@SubscribeEvent
fun onBreak(e: PlayerItemBreakEvent) {
    val uuid = e.player.uniqueId.toString()
    throttleAction(ThrottleActionLink(uuid, "onBreak"), ThrottleActionParam(e.player, e, null, e.brokenItem))
}
@SubscribeEvent
fun onDrop(e: PlayerDropItemEvent) {
    val uuid = e.player.uniqueId.toString()
    throttleAction(ThrottleActionLink(uuid, "onDrop"), ThrottleActionParam(e.player, e, e, e.itemDrop.itemStack))
}
@SubscribeEvent
fun onClickInventory(e: InventoryClickEvent) {
    if (e.whoClicked is Player) {
        val player = e.whoClicked as Player
        throttleAction(
            ThrottleActionLink(player.uniqueId.toString(), "onInventoryClick"),
            ThrottleActionParam(player, e, e, e.currentItem, e.cursor)
        )
    }
}
@SubscribeEvent
fun onSwap(e: PlayerSwapHandItemsEvent) {
    val uuid = e.player.uniqueId.toString()
    throttleAction(ThrottleActionLink(uuid, "onSwap", "main"), ThrottleActionParam(e.player, e, e, e.mainHandItem, e.offHandItem))
    throttleAction(ThrottleActionLink(uuid, "onSwap", "off"), ThrottleActionParam(e.player, e, e, e.offHandItem, e.mainHandItem))
}

@SubscribeEvent
fun onSneak(e: PlayerToggleSneakEvent) {
    // PlayerToggleSneakEvent fires once on sneak start and once on sneak end; actions may use onSneak or onSneak@main / onSneak@off
    val inv = e.player.inventory
    val uuid = e.player.uniqueId.toString()
    throttleAction(ThrottleActionLink(uuid, "onSneak", "main"), ThrottleActionParam(e.player, e, e, inv.itemInMainHand))
    throttleAction(ThrottleActionLink(uuid, "onSneak", "off"), ThrottleActionParam(e.player, e, e, inv.itemInOffHand))
}