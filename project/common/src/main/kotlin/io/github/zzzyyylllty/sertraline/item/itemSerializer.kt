package io.github.zzzyyylllty.sertraline.item

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.Sertraline.tagManager
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.manager.PRIVATE_OWNER_TAG
import io.github.zzzyyylllty.sertraline.manager.isPrivateItemId
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.getItemTag
import taboolib.platform.util.isAir

fun itemSerializer(itemStack: ItemStack, player: Player?): AbstractSItem? {
    if (itemStack.type == Material.AIR || itemStack.isAir) return null
    val item = itemTemplateForStack(itemStack, player) ?: return null
    // Fast path: skip serialization → processing → deserialization when there are no placeholders and no dynamic content
    if (!item.hasPlaceholders && !item.hasDynamics) return item
    val json = item.serialize() ?: return item
    // 恢复必须经过工厂：shouldAdapt 是 transient 的运行时状态，不能由 Gson 带回
    return SItemFactory.deserialize(tagManager.processItem(json, item, itemStack, player))
}

/**
 * Resolve the template represented by an ItemStack. Private items use the owner
 * marker when present, so rebuilding an item does not accidentally use the
 * current player's private template.
 */
internal fun itemTemplateForStack(itemStack: ItemStack, player: Player?): AbstractSItem? {
    val id = itemStack.getItemTag(true)["sertraline_id"]?.asString() ?: return null
    if (!isPrivateItemId(id)) return resolveItemTemplate(id)

    val owner = privateOwnerUuid(itemStack)
    val uuid = owner ?: player?.uniqueId?.toString() ?: return null
    return Sertraline.manager.privateManager.getItem(
        uuid,
        id,
        io.github.zzzyyylllty.sertraline.manager.SubManagerType.TEMPORARY
    ) ?: Sertraline.manager.privateManager.getItem(
        uuid,
        id,
        io.github.zzzyyylllty.sertraline.manager.SubManagerType.PERSISTENT
    )
}

internal fun privateOwnerUuid(itemStack: ItemStack): String? {
    return itemStack.getItemTag(true)[PRIVATE_OWNER_TAG]?.asString()?.trim()?.takeIf { it.isNotEmpty() }
}

fun itemSerializer(item: AbstractSItem, player: Player?): AbstractSItem =
    itemSerializer(item, null, player)

/**
 * Serializes [item] while giving tag processing [itemStack] as its context.
 *
 * A rebuild needs both halves: the template object has to stay [item], because that is the detached
 * copy carrying the instance overrides (current vars, folded alterations), while `{nbt:...}`-style
 * tags still expect the stack they are rebuilding. Resolving the template from the stack instead
 * (the [itemSerializer] overload for [ItemStack]) re-reads it from the item cache and throws the
 * overrides away.
 */
fun itemSerializer(item: AbstractSItem, itemStack: ItemStack?, player: Player?): AbstractSItem {
    // Fast path: return directly when there are no placeholders and no dynamic content; no serialization/deserialization needed
    if (!item.hasPlaceholders && !item.hasDynamics) return item
    val context = item.getDeepData("sertraline:context")
    // context may contain runtime objects such as Player/Event, which Gson cannot safely round-trip;
    // strip it before serialization (item is always a deep copy on the build path), while tag processing still reads the full context from the item argument
    val json = if (context != null) {
        item.setDeepData("sertraline:context", null)
        try { item.serialize() } finally { item.setDeepData("sertraline:context", context) }
    } else item.serialize()
    val result = SItemFactory.deserialize(tagManager.processItem(json ?: return item, item, itemStack, player)) ?: return item
    // context is lost after the round-trip; reattach it (for downstream sertralineAdapter lore placeholders)
    if (context != null) result.setDeepData("sertraline:context", context)
    return result
}

fun itemSerializer(id: String, player: Player?): AbstractSItem? {
    val item = resolveItemTemplate(id) ?: return null
    // Fast path: return directly when there are no placeholders and no dynamic content
    if (!item.hasPlaceholders && !item.hasDynamics) return item
    val json = item.serialize() ?: return item
    return SItemFactory.deserialize(tagManager.processItem(json, item, null, player))
}
