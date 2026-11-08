package io.github.zzzyyylllty.sertraline.item.gem

import io.github.zzzyyylllty.sertraline.data.AlterationOperation
import io.github.zzzyyylllty.sertraline.data.GemDefinition
import io.github.zzzyyylllty.sertraline.data.GemSlotDefinition
import io.github.zzzyyylllty.sertraline.data.compileGemAlterations
import io.github.zzzyyylllty.sertraline.data.gemAlterationOwner
import io.github.zzzyyylllty.sertraline.data.gemAlterationSlotPrefix
import io.github.zzzyyylllty.sertraline.data.getGemDefinition
import io.github.zzzyyylllty.sertraline.data.getGemSlotDefinitions
import io.github.zzzyyylllty.sertraline.data.isClickSocketEnabled
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.function.data.getSertralineId
import io.github.zzzyyylllty.sertraline.item.RecordedAlteration
import io.github.zzzyyylllty.sertraline.item.getItemAlterationRecords
import io.github.zzzyyylllty.sertraline.item.itemTemplateForStack
import io.github.zzzyyylllty.sertraline.item.isPersistentTarget
import io.github.zzzyyylllty.sertraline.item.rebuild
import io.github.zzzyyylllty.sertraline.item.revertPersistedAlteration
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.item.writeAlterationRecordsToTag
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.util.isNotExist
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import taboolib.module.nms.ItemTag
import taboolib.module.nms.getItemTag
import taboolib.platform.util.giveItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Gem socketing system — sockets gems into Sertraline items by writing gem
 * alterations ([io.github.zzzyyylllty.sertraline.data.compileGemAlterations]) plus a
 * `sertraline_gem` NBT node, then rebuilding the item through the normal pipeline.
 *
 * NBT structure (`used` is the single source of truth for occupied slots):
 * ```yaml
 * sertraline_gem:
 *   used: ["slot_attack"]
 *   slots:
 *     slot_attack:
 *       gem: "example_gem"     # Sertraline id of the socketed gem item
 *       timestamp: 1791764589000  # unix millis, Long
 *       item: <byte[]>         # optional serialized gem item, restores the exact stack
 * ```
 */

/** NBT node holding the gem state of an item. */
const val GEM_TAG = "sertraline_gem"

// ---------- Result types ----------

enum class GemSocketResult {
    SUCCESS,
    WEAPON_NOT_SERTRALINE,
    WEAPON_HAS_NO_SLOTS,
    SLOT_NOT_FOUND,
    SLOT_OCCUPIED,
    GEM_NOT_SERTRALINE,
    NOT_A_GEM,
    COLOR_MISMATCH,
    EMPTY_MODIFY_DATA,

    /** The item / socket / gem forbids this route (e.g. `click: false`). */
    METHOD_NOT_ALLOWED,

    /**
     * The socket was rolled back because the rebuild failed. Nothing was consumed and the
     * item is back to its pre-socket state — retrying later is safe.
     */
    INTERNAL_ERROR,
}

enum class GemUnsocketResult {
    SUCCESS,
    NOT_SERTRALINE,
    SLOT_NOT_FOUND,
    SLOT_EMPTY,

    /**
     * The gem could not be handed back (missing template / rebuild failure). The slot was
     * left untouched so no gem is lost — retrying later is safe.
     */
    INTERNAL_ERROR,
}

/** Snapshot of one socketed gem, read from NBT. */
data class SocketedGem(
    val slotId: String,
    val gemId: String?,
    val timestamp: Long,
    /** Serialized original gem stack, when the socket wrote one. Null for legacy sockets. */
    val itemPayload: ByteArray? = null,
) {
    // data class with a ByteArray needs identity equals/hashCode; the payload is only ever
    // used for restoring the item, so compare it by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SocketedGem) return false
        return slotId == other.slotId &&
            gemId == other.gemId &&
            timestamp == other.timestamp &&
            (itemPayload?.contentEquals(other.itemPayload) ?: (other.itemPayload == null))
    }

    override fun hashCode(): Int {
        var result = slotId.hashCode()
        result = 31 * result + (gemId?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (itemPayload?.contentHashCode() ?: 0)
        return result
    }
}

// ---------- Read API ----------

/** Slot definitions of the given weapon stack, resolved from its Sertraline template. */
fun ItemStack.getGemSlots(): Map<String, GemSlotDefinition> =
    itemTemplateForStack(this, null)?.getGemSlotDefinitions() ?: emptyMap()

/** Gem definition of this stack when it is a Sertraline gem item, null otherwise. */
fun ItemStack.getGemDefinition(): GemDefinition? =
    itemTemplateForStack(this, null)?.getGemDefinition()

/** Slot ids currently occupied (`sertraline_gem.used`). */
fun ItemStack.getUsedGemSlots(): List<String> {
    if (isNotExist()) return emptyList()
    return getItemTag(true)[GEM_TAG]?.asCompound()?.get("used")?.asList()?.map { it.asString() } ?: emptyList()
}

/** All socketed gems with their metadata. */
fun ItemStack.getSocketedGems(): List<SocketedGem> {
    if (isNotExist()) return emptyList()
    val compound = getItemTag(true)[GEM_TAG]?.asCompound() ?: return emptyList()
    val used = compound["used"]?.asList()?.map { it.asString() } ?: return emptyList()
    val slots = compound["slots"]?.asCompound()
    return used.map { slotId ->
        val entry = slots?.get(slotId)?.asCompound()
        SocketedGem(
            slotId = slotId,
            gemId = entry?.get("gem")?.asString(),
            timestamp = entry?.get("timestamp")?.asLong() ?: 0L,
            itemPayload = runCatching { entry?.get("item")?.asByteArray() }.getOrNull(),
        )
    }
}

/** The gem socketed in [slotId], or null when the slot is empty. */
fun ItemStack.getSocketedGem(slotId: String): SocketedGem? =
    getSocketedGems().firstOrNull { it.slotId == slotId }

// ---------- Socket ----------

/**
 * Sockets [gem] into [slotId] of [weapon]. Mutates and returns [weapon]
 * (rebuilt in place); consumes one [gem] item on success.
 *
 * [method] is the route the request came from. Route-level gates (master switch, click
 * gesture, permission) are checked by the caller via [GemSocketPolicy.denialOf]; the
 * definition-level gates (`click: false` on the item / socket / gem) are enforced here so
 * every click-driven caller gets them for free.
 *
 * Non-persistent gem alterations (e.g. `xbuilder.name`) are replayed every
 * rebuild and revert automatically when the gem is removed. Persistent-target
 * alterations (`sertraline:vars.*`) fold into `sertraline_data` once and are
 * reverted on unsocket via [revertPersistedAlteration].
 *
 * **Transactional**: the gem is only consumed *after* the rebuild succeeded. A rebuild
 * failure restores the pre-socket item and returns [GemSocketResult.INTERNAL_ERROR], so a
 * half-socketed item (state written, gem not consumed) can never be created — that state
 * used to let a player unsocket a gem they never paid for.
 */
fun socketGem(
    player: Player?,
    weapon: ItemStack,
    gem: ItemStack,
    slotId: String,
    method: GemSocketMethod = GemSocketMethod.API,
): GemSocketResult {
    if (weapon.isNotExist()) return GemSocketResult.WEAPON_NOT_SERTRALINE
    if (gem.isNotExist() || gem.amount <= 0) return GemSocketResult.GEM_NOT_SERTRALINE

    val weaponId = weapon.getSertralineId() ?: return GemSocketResult.WEAPON_NOT_SERTRALINE
    val weaponTemplate = itemTemplateForStack(weapon, player) ?: return GemSocketResult.WEAPON_NOT_SERTRALINE
    val slots = weaponTemplate.getGemSlotDefinitions()
    if (slots.isEmpty()) return GemSocketResult.WEAPON_HAS_NO_SLOTS
    val slot = slots[slotId] ?: return GemSocketResult.SLOT_NOT_FOUND
    if (slotId in weapon.getUsedGemSlots()) return GemSocketResult.SLOT_OCCUPIED

    val gemId = gem.getSertralineId() ?: return GemSocketResult.GEM_NOT_SERTRALINE
    val gemDef = itemTemplateForStack(gem, player)?.getGemDefinition() ?: return GemSocketResult.NOT_A_GEM
    if (!slot.accepts(gemDef.color)) return GemSocketResult.COLOR_MISMATCH
    if (!GemSocketPolicy.isAllowedByDefinition(method, weaponTemplate.isClickSocketEnabled(), slot, gemDef)) {
        devLog("Gem '$gemId' refused by definition for route $method (item/socket/gem click flag)")
        return GemSocketResult.METHOD_NOT_ALLOWED
    }

    // A gem without modify-data is purely cosmetic: it still occupies the slot and
    // shows up in the `${gem-slot:..}$` lore, it simply carries no alterations.
    val alterations = compileGemAlterations(slotId, gemDef.modifyData)
    if (alterations.isEmpty()) {
        devLog("Gem '$gemId' has no modify-data; socketing it as a cosmetic gem")
    }

    // Write gem alterations and the gem-slot state in ONE tag save — separate
    // putItemAlterationRecords/writeGemSlot saves would overwrite each other,
    // since each reloads the tag from the item before writing.
    //
    // Ownership is resolved by the LONGEST matching `gem_<slotId>_` prefix, never by a plain
    // `startsWith`: `slot` is a prefix of `slot_red`, so a naive filter would delete another
    // socket's alterations (and revert their stats) while leaving them marked as occupied.
    val records = weapon.getItemAlterationRecords()
        .filterNot { gemAlterationOwner(it.alteration.name, slots.keys) == slotId } +
        alterations.map { RecordedAlteration(it) }

    val backup = weapon.clone()
    val tag = weapon.getItemTag()
    writeAlterationRecordsToTag(tag, records)
    writeGemSlotToTag(tag, slotId, gemId, gem)
    tag.saveTo(weapon)

    return try {
        val rebuilt = weapon.rebuild(player)
        weapon.type = rebuilt.type
        weapon.itemMeta = rebuilt.itemMeta

        if (gem.amount > 1) gem.amount -= 1 else gem.amount = 0
        devLog("Socketed gem '$gemId' into slot '$slotId' of '$weaponId' (${alterations.size} alterations)")
        GemSocketResult.SUCCESS
    } catch (e: Throwable) {
        // Roll the item back: without this the NBT would already say "slot filled" while the
        // gem was never consumed, so an unsocket would hand out a free gem.
        weapon.type = backup.type
        weapon.itemMeta = backup.itemMeta
        severeS("Failed to socket gem '$gemId' into slot '$slotId' of '$weaponId'; the item was rolled back: ${e.message}")
        e.printStackTrace()
        GemSocketResult.INTERNAL_ERROR
    }
}

// ---------- Unsocket ----------

/**
 * Removes the gem in [slotId] from [weapon]. Mutates and returns [weapon];
 * optionally gives the extracted gem back to [player].
 *
 * Order matters and is deliberately: resolve the returned gem first -> reverse persisted
 * effects -> drop the gem's alteration records and slot state -> rebuild once -> hand the gem
 * back. Resolving/rebuilding **before** clearing the state is what keeps the gem from being
 * destroyed when the rebuild fails: the slot is only cleared once the item is known to build.
 *
 * Rebuilding *after* clearing the records is what actually removes the gem: every
 * non-persistent alteration (lore / name / attributes / …) is replayed from the
 * records on each build, so as long as the records are still in the NBT the rebuild
 * would bake the gem's effect into the item again. Clearing the state before the
 * rebuild also makes `${gem-slot:<slotId>}$` render the empty slot in the same pass.
 */
fun unsocketGem(player: Player?, weapon: ItemStack, slotId: String, returnGem: Boolean = true): GemUnsocketResult {
    if (weapon.isNotExist()) return GemUnsocketResult.NOT_SERTRALINE
    weapon.getSertralineId() ?: return GemUnsocketResult.NOT_SERTRALINE

    val slots = weapon.getGemSlots()
    val socketed = weapon.getSocketedGem(slotId) ?: return if (slots.isNotEmpty() && slotId !in slots) {
        // Unknown slot id on an item that does declare sockets — a config mistake, not an empty slot.
        GemUnsocketResult.SLOT_NOT_FOUND
    } else {
        GemUnsocketResult.SLOT_EMPTY
    }

    // 0) Resolve the gem to hand back BEFORE touching anything. If it cannot be resolved the
    //    slot stays untouched, otherwise the gem would be destroyed without a trace.
    var gemToReturn: ItemStack? = null
    if (returnGem) {
        if (player == null) {
            warningS("Unsocketing gem from slot '$slotId' without a player; the gem item cannot be returned and is lost")
        } else {
            gemToReturn = socketed.itemPayload?.let { decodeGemItem(it) }
                ?: socketed.gemId?.let { sertralineItemBuilder(it, player) }
            if (socketed.gemId != null && gemToReturn == null) {
                severeS("Cannot restore gem '${socketed.gemId}' from slot '$slotId' (template missing or build failed); the slot was left untouched")
                return GemUnsocketResult.INTERNAL_ERROR
            }
        }
    }

    // Ownership by longest matching prefix, see socketGem.
    val knownSlots = slots.keys + weapon.getUsedGemSlots()
    val records = weapon.getItemAlterationRecords()
    val (gemRecords, others) = records.partition {
        gemAlterationOwner(it.alteration.name, knownSlots) == slotId
    }

    // 1) Reverse folded (persisted) effects in sertraline_data. SET restores its recorded
    //    previous value, ADD applies the negation.
    for (record in gemRecords) {
        if (record.persisted && record.alteration.isPersistentTarget()) {
            runCatching { revertPersistedAlteration(weapon, record) }
                .onFailure { devLog("Failed to revert persisted gem alteration '${record.alteration.name}': ${it.message}") }
        }
    }

    // 2) Drop the gem's records and the slot state in ONE tag save, before the rebuild.
    val backup = weapon.clone()
    val tag = weapon.getItemTag()
    writeAlterationRecordsToTag(tag, others)
    clearGemSlotFromTag(tag, slotId)
    tag.saveTo(weapon)

    // 3) Single rebuild on the cleaned NBT — no gem effect is replayed, so the item is
    //    genuinely back to its gem-free state and the slot lore shows as empty.
    try {
        val rebuilt = weapon.rebuild(player)
        weapon.type = rebuilt.type
        weapon.itemMeta = rebuilt.itemMeta
    } catch (e: Throwable) {
        weapon.type = backup.type
        weapon.itemMeta = backup.itemMeta
        severeS("Failed to rebuild '$slotId' after unsocketing; the item was rolled back and the gem was kept: ${e.message}")
        e.printStackTrace()
        return GemUnsocketResult.INTERNAL_ERROR
    }

    if (gemToReturn != null && player != null) {
        if (player.inventory.firstEmpty() == null) {
            warningS("Inventory of ${player.name} is full; gem '${socketed.gemId}' from slot '$slotId' is dropped on the ground")
        }
        player.giveItem(gemToReturn!!)
    }
    devLog("Unsocketed gem from slot '$slotId' (${gemRecords.size} alterations removed)")
    return GemUnsocketResult.SUCCESS
}

// ---------- NBT helpers ----------

/**
 * Writes `used += slotId` and `slots[slotId] = {gem, timestamp, item}` into [tag]
 * (does NOT save).
 *
 * The serialized [gemItem] is stored so [unsocketGem] can hand back the *exact* stack the
 * player paid with (enchants, display name, custom NBT) instead of a freshly built template.
 */
private fun writeGemSlotToTag(tag: ItemTag, slotId: String, gemId: String, gemItem: ItemStack?) {
    val gemTag = tag[GEM_TAG]?.asCompound() ?: ItemTag()
    val used = (gemTag["used"]?.asList()?.map { it.asString() } ?: emptyList()).toMutableList()
    if (slotId !in used) used += slotId
    gemTag["used"] = used

    val slots = gemTag["slots"]?.asCompound() ?: ItemTag()
    val entry = ItemTag()
    entry["gem"] = gemId
    entry["timestamp"] = System.currentTimeMillis()
    encodeGemItem(gemItem)?.let { entry["item"] = it }
    slots[slotId] = entry
    gemTag["slots"] = slots

    tag[GEM_TAG] = gemTag
}

/** Removes [slotId] from the gem state in [tag] (does NOT save). Drops the node when it becomes empty. */
private fun clearGemSlotFromTag(tag: ItemTag, slotId: String) {
    val gemTag = tag[GEM_TAG]?.asCompound() ?: return
    val used = gemTag["used"]?.asList()?.map { it.asString() }?.filter { it != slotId } ?: emptyList()
    if (used.isEmpty()) gemTag.remove("used") else gemTag["used"] = used
    gemTag["slots"]?.asCompound()?.remove(slotId)
    if (gemTag.entries.isEmpty()) tag.remove(GEM_TAG) else tag[GEM_TAG] = gemTag
}

/**
 * Copies the gem state of [from] onto [tag] (does NOT save).
 *
 * The gem state is not an alteration record, so the build pipeline has to carry it
 * over explicitly — depending on `rebuild.keep-data` alone drops every socketed gem
 * whenever a server still runs the older default list.
 *
 * The node is rebuilt from its primitives instead of sharing the source [ItemTag],
 * so the new item never aliases the state of the item it was rebuilt from.
 */
fun copyGemStateToTag(tag: ItemTag, from: ItemStack?) {
    if (from.isNotExist()) return
    val source = from?.getItemTag(true)?.get(GEM_TAG)?.asCompound() ?: return

    val copy = ItemTag()
    source["used"]?.asList()?.map { it.asString() }?.takeIf { it.isNotEmpty() }?.let { copy["used"] = it }

    val slots = ItemTag()
    val rawSlots = source["slots"]?.asCompound()
    if (rawSlots != null) {
        for ((slotId, rawEntry) in rawSlots.entries) {
            val entry = rawEntry?.asCompound() ?: continue
            val newEntry = ItemTag()
            entry["gem"]?.asString()?.let { newEntry["gem"] = it }
            entry["timestamp"]?.asLong()?.let { newEntry["timestamp"] = it }
            // The serialized gem stack must survive a rebuild, or unsocketing would fall back
            // to a template rebuild and lose the original item's data.
            runCatching { entry["item"]?.asByteArray() }.getOrNull()?.let { newEntry["item"] = it }
            slots[slotId] = newEntry
        }
    }
    if (slots.entries.isNotEmpty()) copy["slots"] = slots

    if (copy.entries.isEmpty()) return
    tag[GEM_TAG] = copy
}

// ---------- Item (de)serialization helpers ----------

/** Serializes a gem stack for the `item` NBT node; null when serialization is not possible. */
internal fun encodeGemItem(item: ItemStack?): ByteArray? {
    if (item.isNotExist()) return null
    return runCatching {
        ByteArrayOutputStream().use { bytes ->
            BukkitObjectOutputStream(bytes).use { it.writeObject(item) }
            bytes.toByteArray()
        }
    }.onFailure { devLog("Failed to serialize gem item for the socket payload: ${it.message}") }.getOrNull()
}

/** Restores a gem stack written by [encodeGemItem]; null when the payload is unusable. */
internal fun decodeGemItem(payload: ByteArray): ItemStack? = runCatching {
    ByteArrayInputStream(payload).use { bytes ->
        BukkitObjectInputStream(bytes).use { it.readObject() as? ItemStack }
    }
}.onFailure { devLog("Failed to deserialize gem socket payload: ${it.message}") }.getOrNull()