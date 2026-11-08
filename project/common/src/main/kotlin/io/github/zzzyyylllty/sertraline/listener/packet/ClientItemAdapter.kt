package io.github.zzzyyylllty.sertraline.listener.packet

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.Sertraline.console
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.impl.removeComponentNMS
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import io.github.zzzyyylllty.sertraline.impl.setComponent
import io.github.zzzyyylllty.sertraline.impl.setComponentNMS
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import io.github.zzzyyylllty.sertraline.util.parseNamespacedKey
import io.github.zzzyyylllty.sertraline.util.loreformat.handleLoreFormat
import io.github.zzzyyylllty.sertraline.util.minimessage.toComponent
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import com.cryptomorin.xseries.XMaterial
import taboolib.module.lang.asLangText
import taboolib.module.nms.NMSItemTag.Companion.asBukkitCopy
import taboolib.module.nms.NMSItemTag.Companion.asNMSCopy
import taboolib.module.nms.getItemTag
import taboolib.module.nms.setItemTag
import taboolib.platform.util.deserializeToItemStack
import taboolib.platform.util.serializeToByteArray
import io.github.zzzyyylllty.sertraline.util.toUpperCase
import org.bukkit.NamespacedKey
import kotlin.math.roundToInt

private val carriedItemFieldInContainerClick by lazy { if (VersionHelper.isUniversal) "carriedItem" else "item" }
val suffix by lazy { console.asLangText("Editor_Item_Suffix").asListEnhanced()?.toComponent() ?: emptyList() }

/**
 * Gets the SERTRALINE_OITEM Tag from the incoming virtual item's Tag to restore the original item
 * */
fun ItemStack.c2s(): ItemStack {
    val tag = this.getItemTag()
    val bytes = tag["sertraline_oitem"]?.asByteArray() ?: return this
    // Corrupted/malformed payloads (or a legacy server that fails to deserialize a
    // modern item) must fall back to the incoming item instead of breaking the click.
    val deserialized = runCatching { bytes.deserializeToItemStack() }.getOrNull()
    return if (deserialized == null || deserialized.type == Material.AIR) this else deserialized
}
/**
 * Converts a server-side item into a virtual item
 * */
fun ItemStack.s2c(player: Player?): ItemStack {
    val oItem = this.clone()
    if (type == Material.AIR) return oItem
    val tag = this.getItemTag(true)
    val id = tag["sertraline_id"]?.asString() ?: return oItem
    val sItem = itemSerializer(id, player) ?: return oItem
    var modified = false
    if (SertralineSettings.packetLore) {
        // Apply to the CLONE. This used to call `PlatformCompat.setLore(this, it)` and
        // `asNMSCopy(this)`, mutating the server-side ItemStack that the packet pipeline handed in —
        // the packet-sent lore then leaked back into the real inventory item (and into whatever the
        // caller did with the stack afterwards).
        handleLoreFormat(sItem, player, PlatformCompat.getLore(oItem), true, oItem)?.let {
            PlatformCompat.setLore(oItem, it)
            modified = true
        }
    }
    val nmsItem = asNMSCopy(oItem)
    val serialized = oItem.serializeToByteArray()
    val modifiedItem = visualComponentSetterNMS(nmsItem, sItem, serialized)
    if (tag["sertraline_browse_item"] != null && PlatformCompat.getLore(oItem) != PlatformCompat.getLore(modifiedItem)) { // if it is a browse item and its lore was modified
        val lore = PlatformCompat.getLore(modifiedItem)?.toMutableList() ?: mutableListOf()
        lore.addAll(suffix)
        PlatformCompat.setLore(modifiedItem, lore)
        modified = true
    }
    // Every client-side-visualized item must carry the restore tag, not just items
    // with a `visual:` map: the client returns this modified copy in click packets
    // (e.g. DOUBLE_CLICK grab-all carries the merged visual stack as carriedItem),
    // and c2s() needs the tag to restore the real item. Without it, the server
    // would adopt a client-only fake item on its cursor.
    if (modified) {
        val outTag = modifiedItem.getItemTag()
        if (outTag["sertraline_oitem"] == null) {
            outTag["sertraline_oitem"] = serialized
            return modifiedItem.setItemTag(outTag)
        }
    }
    return modifiedItem
}
fun visualComponentSetterNMS(item: Any, sItem: AbstractSItem,serialized: ByteArray): ItemStack {

    // visual overrides are written via DataComponent, older versions (1.12.2 etc.) return the original item directly
    if (!VersionHelper.isOrAbove12005()) return asBukkitCopy(item)
    var resultItem = item
    var visualMaterial: Material? = null
    val autoComponents = LinkedHashMap<String, Any>()

    if (SertralineSettings.packetComponent) {
        val filtered = (sItem.data["visual"] as? Map<*,*>)?.toMutableMap() ?: return asBukkitCopy(item)
        if (!filtered.isEmpty()) {

            filtered["material"]?.let {
                // safe lookup：非法材质名不静默变�?STONE（那会伪装成真实物品），
                // 保持原材质并在日志中报告
                val mat = runCatching { XMaterial.valueOf(it.toString().uppercase(java.util.Locale.ROOT)) }
                    .getOrNull()?.get()
                if (mat != null) visualMaterial = mat
                else warningS(console.asLangText("Warning_Visual_Material_Invalid", it.toString()))
                filtered.remove("material")
            }

            filtered.remove("auto_lore")?.let { autoComponents["autoLore"] = it }
            filtered.remove("auto_name")?.let { autoComponents["autoName"] = it }
            filtered.remove("auto_custom_model_data")?.let { autoComponents["autoCMD"] = it }
            filtered.remove("auto_model")?.let { autoComponents["autoModel"] = it }

            filtered.forEach { (key, value) ->
                if (value != null) resultItem.setComponentNMS("minecraft:$key", value)?.let { resultItem = it }
                else resultItem = resultItem.removeComponentNMS("minecraft:$key")
            }


        }
    }

    var resultBItem = asBukkitCopy(resultItem)
    devLog("autoComponents: $autoComponents")
    if (autoComponents.isNotEmpty()) {
        resultBItem.itemMeta?.let { meta ->
            autoComponents.forEach { (key, value) ->
                when (key) {
                    "autoName" -> PlatformCompat.setDisplayName(meta, value.toString().toComponent())
                    "autoLore" -> PlatformCompat.setLore(meta, (value.asListEnhanced())?.toComponent() ?: listOf(value.toString().toComponent()))
                    // 拒绝 NaN/Infinity/非有限值；非法配置跳过�?visual 而不是把整轮 render 打断
                    "autoCMD" -> {
                        val d = value.toString().toDoubleOrNull()
                        if (d != null && d.isFinite()) PlatformCompat.setCustomModelData(meta, d.roundToInt())
                        else warningS(console.asLangText("Warning_Visual_AutoCMD_Invalid", value.toString()))
                    }
                    "autoModel" -> PlatformCompat.setItemModel(meta, value.toString().parseNamespacedKey())
                }
                resultBItem.setItemMeta(meta)
            }
        }
    }
    val tag = resultBItem.getItemTag()
    tag["sertraline_oitem"] = serialized
    resultBItem = resultBItem.setItemTag(tag)
    visualMaterial?.let { resultBItem.type = it }
    return resultBItem
}

@Deprecated("Performance Issue")
fun visualComponentSetter(item: ItemStack, sItem: AbstractSItem): ItemStack {
    val filtered = (sItem.data["visual"] as? Map<*,*>)?.toMutableMap() ?: return item
    if (filtered.isEmpty()) return item

    var resultItem = item
    filtered["material"]?.let {
        // safe lookup：非法材质名保持原材质，不静默变�?STONE
        val mat = runCatching { XMaterial.valueOf(it.toString().uppercase(java.util.Locale.ROOT)) }
            .getOrNull()?.get()
        if (mat != null) resultItem.type = mat
        else warningS(console.asLangText("Warning_Visual_Material_Invalid", it.toString()))
        filtered.remove("material")
    }

    if (!filtered.isEmpty()) {
        var nmsItem = asNMSCopy(resultItem)
        filtered.forEach { (key, value) ->
            nmsItem.setComponentNMS("minecraft:$key", value!!)?.let { nmsItem = it }
        }
        resultItem = asBukkitCopy(nmsItem)
    }

    return resultItem
}