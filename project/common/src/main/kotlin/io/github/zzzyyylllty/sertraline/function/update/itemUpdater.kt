package io.github.zzzyyylllty.sertraline.function.update

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.Sertraline.itemExpectedRevision
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.impl.getComponentsFilteredNMS
import io.github.zzzyyylllty.sertraline.impl.setComponentNMS
import io.github.zzzyyylllty.sertraline.item.adapter.transferBooleanToByte
import io.github.zzzyyylllty.sertraline.item.itemTemplateForStack
import io.github.zzzyyylllty.sertraline.item.finishInitialBuild
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilderInternal
import io.github.zzzyyylllty.sertraline.logger.sendStringAsComponent
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.manager.PRIVATE_OWNER_TAG
import io.github.zzzyyylllty.sertraline.manager.SubManagerType
import io.github.zzzyyylllty.sertraline.manager.isPrivateItemId
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseMapNBT
import io.github.zzzyyylllty.sertraline.util.ItemTagUtil.parseNBT
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submit
import taboolib.module.nms.ItemTag
import taboolib.module.nms.NMSItemTag.Companion.asBukkitCopy
import taboolib.module.nms.NMSItemTag.Companion.asNMSCopy
import taboolib.module.nms.getItemTag
import taboolib.platform.util.asLangText
import taboolib.platform.util.isAir
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keys in `rebuild.keep-data` related to revisions: the updater must allow the **new** revision to take effect.
 * Restoring these keys from the old item would leave the revision unchanged, causing every check to rebuild the item indefinitely.
 */
private val REVISION_KEEP_KEYS = setOf("sertraline_revision")

/**
 * Keys that must be preserved regardless of `rebuild.keep-data` settings.
 *
 * `sertraline_data` stores runtime item variables (such as script-written kill counts or bound players). It has two safeguards during rebuild:
 * the builder passes old values as `overrideData["sertraline:vars"]` (including folded alterations), and
 * the updater merges them again to add newly declared template defaults without losing old values.
 * Losing variables is data loss, not a formatting preference, so this behavior is not configurable.
 */
private val ALWAYS_KEEP_DATA = setOf("sertraline_data")

/**
 * Keys that must be merged rather than replaced wholesale (see [preserveKeptData]): add newly declared template defaults,
 * or updated items will have different NBT from newly created items and cannot stack with them.
 */
private val MERGE_DATA_KEYS = setOf("sertraline_data")

/**
 * Player-authored content components that must be preserved during updates.
 *
 * A clean base restores the item to its definition, but shulker box contents, bundle contents, loaded crossbow arrows, and player-written books
 * are player data rather than definition data. Losing them would destroy player data (the default `rebuild.keep-component` list does not cover them).
 * This content already prevents stacking (vanilla shulker boxes with contents cannot stack), so preserving it does not introduce new stacking issues.
 */
private val ALWAYS_KEEP_COMPONENT = setOf(
    "minecraft:container",
    "minecraft:bundle_contents",
    "minecraft:charged_projectiles",
    "minecraft:written_book_content",
    "minecraft:profile"
)

/** Coalesces notifications so one batch of checks (login/container/periodic scan) sends one message, settled together after 1 tick. */
private const val NOTIFY_FLUSH_DELAY = 1L
private val pendingNotify = ConcurrentHashMap<UUID, AtomicInteger>()

/**
 * Gets the expected revision number of an item
 * Priority: explicit value > auto tracking
 *
 * Note: templates for private items (with `private:`/`__` prefixes) are stored in the database by owner, so automatic tracking ([initRevisionAutoTracker])
 * cannot enumerate them. Only private items that explicitly declare `sertraline:revision-id` are updated.
 */
fun getExpectedRevision(itemId: String, player: Player? = null, ownerUuid: String? = null): Int {
    val normalizedId = if (isPrivateItemId(itemId)) {
        io.github.zzzyyylllty.sertraline.manager.normalizePrivateItemId(itemId)
    } else itemId
    val template = if (isPrivateItemId(normalizedId)) {
        val uuid = ownerUuid ?: player?.uniqueId?.toString()
        if (uuid == null) null else {
            Sertraline.manager.privateManager.getItem(uuid, normalizedId, SubManagerType.TEMPORARY)
                ?: Sertraline.manager.privateManager.getItem(uuid, normalizedId, SubManagerType.PERSISTENT)
        }
    } else {
        itemMap[normalizedId]
    }
    val explicit = template?.getDeepData("sertraline:revision-id")
    if (explicit is Number) return explicit.toInt()
    return itemExpectedRevision[itemId] ?: itemExpectedRevision[normalizedId] ?: 0
}

/**
 * Builds the updated copy of [itemStack] when its definition changed, or returns null when
 * nothing has to be done (no Sertraline id / untracked / already up to date / rebuild failed).
 *
 * The caller owns the write-back: [checkAndUpdateItem] writes into a player-inventory slot,
 * [checkAndUpdateInventoryItem] into a container slot, the pickup listener into the item entity.
 *
 * Revision comparison uses **equality**: a stale item whose tag number is *ahead* of the expected
 * one (revision tracker deleted / config rolled back) is refreshed too. `current >= expected` used
 * to skip those forever, so an item could never come back in sync.
 *
 * **Canonical rebuild — why the old stack is no longer the default `source`:**
 * `xbuilderAdapter` merges the base stack's own serialization *over* the template config
 * (`for (entry in XItemStack.serialize(item)) configMap[entry.key] = entry.value`) and then forces
 * `item.type = orgItemType`. Passing the old item as `source` therefore meant:
 *  - config changes to material / enchantments / attributes / flags / unbreakable / custom model
 *    data / removed tier-type… never reached the item (the item's own old values won);
 *  - the updated item silently kept components of its previous build, so it was **not** equal to a
 *    freshly given copy of the same id — and two stacks with different NBT cannot merge, which is
 *    exactly the "the item was updated but still won't stack" report.
 * So the item is now rebuilt from a clean base (`source = null`, like
 * [io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder]) whenever the definition declares
 * its own material. Rendering runs again for stack-dependent expressions, and the
 * state that must survive is carried over: [preserveKeptData] (runtime vars + `rebuild.keep-data`,
 * revision keys excluded) and the components in `rebuild.keep-component` plus
 * [ALWAYS_KEEP_COMPONENT] ([applyKeptComponents] — durability, enchantments and player-written item
 * contents such as shulker / bundle / crossbow / book data).
 * Definitions without a material key keep the old stack as base — their material comes from the
 * item itself, `itemSource` would otherwise fall back to a grass block.
 *
 * @param force true = rebuild even when the revision matches (used by the admin "full refresh")
 */
fun buildUpdatedItem(player: Player, itemStack: ItemStack?, force: Boolean = false): ItemStack? {
    if (itemStack == null || itemStack.isAir) return null
    if (!SertralineSettings.itemUpdateEnabled) return null

    val tag = itemStack.getItemTag(true)
    val sID = tag["sertraline_id"]?.asString() ?: return null
    val ownerUuid = tag[PRIVATE_OWNER_TAG]?.asString()?.trim()?.takeIf { it.isNotEmpty() }
    val expectedRevision = getExpectedRevision(sID, ownerUuid = ownerUuid)
    if (!force && expectedRevision <= 0) return null

    val currentRevision = tag["sertraline_revision"]?.asInt() ?: 0
    if (!force && currentRevision == expectedRevision) return null

    devLog("Updating item $sID (rev $currentRevision -> $expectedRevision) for ${player.name}${if (force) " [forced]" else ""}")

    val overrideData = mutableMapOf<String, Any?>()
    val oldVars = tag["sertraline_data"]?.parseMapNBT()
    if (oldVars != null) overrideData["sertraline:vars"] = oldVars

    // Clean base when the definition declares its material: the result then equals what
    // /sertraline give (or any other build) produces for the same id.
    val template = itemTemplateForStack(itemStack, player, tag)
    val declaresMaterial = template != null &&
        (template.getDeepData("xbuilder:material") != null || template.getDeepData("minecraft:material") != null)

    val newItem = try {
        val built = sertralineItemBuilderInternal(
            sID, player,
            source = if (declaresMaterial) null else itemStack,
            amount = itemStack.amount,
            overrideData = overrideData,
            rebuild = itemStack
        ) ?: return null

        // Runtime vars have to be on the intermediate item *before* the second pass: that pass
        // re-bakes `${var:...}` placeholders from the instance data, otherwise they would fall back
        // to the template defaults.
        preserveKeptData(tag, built)
        val rebuilt = built.finishInitialBuild(player)
        if (rebuilt !== built) preserveKeptData(tag, rebuilt)
        applyKeptComponents(itemStack, rebuilt)
    } catch (e: Exception) {
        severeS("Failed to rebuild item $sID (rev $currentRevision -> $expectedRevision) for ${player.name}: ${e.message}")
        e.printStackTrace()
        return null
    }
    newItem.amount = itemStack.amount

    queueUpdateNotify(player)
    return newItem
}

/**
 * Copies the configured `rebuild.keep-data` keys (plus [ALWAYS_KEEP_DATA]) from the old tag onto
 * the rebuilt item — this is what actually keeps the item's runtime vars alive across an update.
 * Revision keys are skipped so the freshly written revision wins and the update converges.
 *
 * `sertraline_data` is **merged** instead of replaced: the rebuilt item's vars (current definition,
 * i.e. newly added defaults included) form the base and the old item's runtime values win on top.
 * Replacing wholesale would leave an updated item without a var that the new definition declares —
 * one more NBT difference against freshly built copies.
 */
private fun preserveKeptData(oldTag: ItemTag, newItem: ItemStack) {
    val keep = (SertralineSettings.rebuildKeepData + ALWAYS_KEEP_DATA).distinct()
    if (keep.isEmpty()) return
    val newTag = newItem.getItemTag()
    var touched = false
    keep.forEach { key ->
        if (key in REVISION_KEEP_KEYS) return@forEach
        val oldValue = oldTag[key] ?: return@forEach
        if (key in MERGE_DATA_KEYS) {
            val merged = LinkedHashMap<String, Any?>()
            runCatching { newTag[key]?.parseMapNBT() }.getOrNull()
                ?.forEach { (k, v) -> merged[k] = v }
            runCatching { oldValue.parseMapNBT() }.getOrNull()
                ?.forEach { (k, v) -> merged[k] = v }
            newTag[key] = transferBooleanToByte(merged)
        } else {
            newTag[key] = transferBooleanToByte(oldValue.parseNBT())
        }
        touched = true
    }
    if (touched) newTag.saveTo(newItem)
}

/**
 * Copies the `rebuild.keep-component` components (durability / enchantments / repair cost by
 * default) from the old item onto the rebuilt one. With a clean base these would otherwise be lost,
 * which would destroy player-applied state on every update.
 *
 * @return the rebuilt item (the caller uses the returned instance)
 */
private fun applyKeptComponents(oldItem: ItemStack, newItem: ItemStack): ItemStack {
    val keepComp = (SertralineSettings.rebuildKeepComponent + ALWAYS_KEEP_COMPONENT).distinct()
    if (keepComp.isEmpty()) return newItem
    val oldComponents = try {
        asNMSCopy(oldItem).getComponentsFilteredNMS()
    } catch (e: Exception) {
        devLog("Failed to read components for item update: ${e.message}")
        return newItem
    }
    var nms = asNMSCopy(newItem)
    keepComp.forEach { key ->
        oldComponents[key]?.let { value -> nms.setComponentNMS(key, value)?.let { nms = it } }
    }
    newItem.setItemMeta(asBukkitCopy(nms).itemMeta)
    return newItem
}

/**
 * Checks whether a single item needs updating, and rebuilds/replaces it if so.
 *
 * Uses the atomic slot.setItem replacement; addItem/removeItem combos are forbidden,
 * to avoid an item-duplication exploit.
 *
 * @return true if the item was updated
 */
fun checkAndUpdateItem(player: Player, itemStack: ItemStack?, slot: Int, force: Boolean = false): Boolean {
    val updated = buildUpdatedItem(player, itemStack, force) ?: return false
    player.inventory.setItem(slot, updated)
    return true
}

/**
 * Same as [checkAndUpdateItem], but writes back into an arbitrary [inventory] (block containers
 * such as chests / barrels / shulker boxes) instead of the player's own inventory.
 *
 * @return true if the item was updated
 */
fun checkAndUpdateInventoryItem(player: Player, inventory: Inventory, slot: Int, force: Boolean = false): Boolean {
    val updated = buildUpdatedItem(player, inventory.getItem(slot), force) ?: return false
    inventory.setItem(slot, updated)
    return true
}

/**
 * Checks all of a player's inventory items (including held, armor and offhand slots, plus the
 * ender chest). Used at login, after a reload and by the periodic scan.
 */
fun updatePlayerItems(player: Player, force: Boolean = false) {
    val inv = player.inventory
    // main inventory + hotbar slots 0-35 (storageContents guarantees no armor/offhand)
    inv.storageContents.forEachIndexed { index, item ->
        if (item == null || item.isAir) return@forEachIndexed
        checkAndUpdateItem(player, item, index, force)
    }
    // armor slots 36-39
    inv.armorContents.forEachIndexed { index, item ->
        if (item == null || item.isAir) return@forEachIndexed
        val armorSlot = 36 + index // 0→36(boots), 1→37, 2→38, 3→39(helmet)
        checkAndUpdateItem(player, item, armorSlot, force)
    }
    // offhand slot 40
    val offHand = inv.itemInOffHand
    if (!offHand.isAir) {
        checkAndUpdateItem(player, offHand, 40, force)
    }
    // ender chest (27 slots) — also "the player's items", so scans cover it too
    updateEnderChestItems(player, force)
}

/**
 * Checks the four armor slots only (36-39). Used when armor changed (Paper event / Spigot polling),
 * so a stale armor piece is refreshed without scanning the whole inventory.
 */
fun updateArmorItems(player: Player, force: Boolean = false) {
    val inv = player.inventory
    inv.armorContents.forEachIndexed { index, item ->
        if (item == null || item.isAir) return@forEachIndexed
        checkAndUpdateItem(player, item, 36 + index, force)
    }
}

/** Checks every slot of an opened container inventory. */
fun updateInventoryItems(inventory: Inventory, player: Player, force: Boolean = false) {
    val contents = try {
        inventory.contents
    } catch (e: Exception) {
        devLog("Failed to read inventory contents for item update: ${e.message}")
        return
    }
    contents.forEachIndexed { index, item ->
        if (item == null || item.isAir) return@forEachIndexed
        checkAndUpdateInventoryItem(player, inventory, index, force)
    }
}

private fun updateEnderChestItems(player: Player, force: Boolean = false) {
    val ender = try {
        player.enderChest
    } catch (e: Exception) {
        devLog("Failed to read ender chest for item update: ${e.message}")
        return
    }
    for (index in 0 until ender.size) {
        val item = ender.getItem(index) ?: continue
        if (item.isAir) continue
        checkAndUpdateInventoryItem(player, ender, index, force)
    }
}

/**
 * Queues an update notification. All updates produced inside the same tick (bulk scans included)
 * are merged into a single message, so a login scan does not spam one line per item.
 */
private fun queueUpdateNotify(player: Player) {
    if (!SertralineSettings.itemUpdateNotify) return
    val counter = pendingNotify.computeIfAbsent(player.uniqueId) { AtomicInteger() }
    if (counter.getAndIncrement() == 0) {
        // delay >= 1: `submit` treats delay <= 0 as "run right now", which would fire inside the
        // very scan that is still counting updates.
        submit(async = false, delay = NOTIFY_FLUSH_DELAY) { flushUpdateNotify(player) }
    }
}

private fun flushUpdateNotify(player: Player) {
    val count = pendingNotify.remove(player.uniqueId)?.get() ?: return
    if (count <= 0 || !player.isOnline) return
    try {
        player.sendStringAsComponent(player.asLangText("Item_Update_Notify", count))
    } catch (e: Exception) {
        devLog("Failed to send item update notification: ${e.message}")
    }
}
