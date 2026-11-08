package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.embiancomponent.EmbianComponent
import io.github.zzzyyylllty.sertraline.Sertraline.itemMap
import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import io.github.zzzyyylllty.sertraline.config.asListEnhanced
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.util.parseNamespacedKey
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.ExternalItemHelper
import io.github.zzzyyylllty.sertraline.util.VersionHelper
import io.github.zzzyyylllty.sertraline.util.minimessage.toComponent
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import com.cryptomorin.xseries.XMaterial
import taboolib.library.xseries.XItemStack
import taboolib.module.nms.NMSItemTag.Companion.asNMSCopy
import kotlin.math.roundToInt

// 命名空间别名表统一由 SItemFactory.classify 维护，这里不再保留各自的副本
val componentHelper by lazy { if (VersionHelper.isOrAbove12005()) EmbianComponent.SafetyComponentSetter else null }

data class OmniItem(
    val source: String,
    val item: String,
    val parameters: LinkedHashMap<String, Any?>? = null,
    val components: LinkedHashMap<String, Any?>? = null,
    val amount: String? = "1",
) {
    fun build(player: Player?, overrideAmount: Int? = null): ItemStack {

        val amount = overrideAmount ?: (amount ?: "1").toDoubleOrNull()?.roundToInt()

        var itemStack: ItemStack?

        try {
            val providedItem = when {
                // Vanilla items (mc / minecraft / vanilla)
                SItemFactory.classify(source) == SItemFactory.SourceKind.MINECRAFT -> {
                    val params = (parameters ?: mapOf<String, Any?>()).toMutableMap()
                    params["material"] = item
                    XItemStack.deserialize(params)
                }
                // Sertraline-owned items
                SItemFactory.classify(source) == SItemFactory.SourceKind.SERTRALINE -> {
                    if (player != null) sertralineItemBuilder(item, player)
                    else sertralineItemBuilder(item, null)
                }
                // External plugin items (ItemsAdder, Oraxen, Nexo, CraftEngine, etc.)
                else -> {
                    if (player != null) {
                        ExternalItemHelper.build(player, source, item)
                            ?: ExternalItemHelper.itemBridgeAll?.build(source, item, player)?.get()
                    } else {
                        ExternalItemHelper.buildNoPlayer(source, item)
                            ?: ExternalItemHelper.itemBridgeAll?.build(source, item)?.get()
                    }
                }
            }

            if (providedItem == null) {
                severeL("ErrorItemGenerationFailedNull", source, item)
                // 1.13+ enum name; 1.12.2 lacks this constant; XMaterial resolves it by server version (1.12.2 -> GRASS)
return ItemStack(XMaterial.GRASS_BLOCK.parseMaterial() ?: Material.STONE)
            }
            itemStack = providedItem

        } catch (e: Exception) {
            severeL("ErrorItemGenerationFailed", source, item)
            e.printStackTrace()
            // 1.13+ enum name; 1.12.2 lacks this constant; XMaterial resolves it by server version (1.12.2 -> GRASS)
return ItemStack(XMaterial.GRASS_BLOCK.parseMaterial() ?: Material.STONE)
        }

        if (parameters?.isNotEmpty() ?: false) {

            itemStack.itemMeta?.let { meta ->
                parameters["name"]?.toString()?.toComponent()?.let { PlatformCompat.setDisplayName(meta, it) }
                parameters["display-name"]?.toString()?.toComponent()?.let { PlatformCompat.setDisplayName(meta, it) }
                parameters["custom-name"]?.toString()?.toComponent()?.let { PlatformCompat.setCustomName(meta, it) }
                parameters["item-name"]?.toString()?.toComponent()?.let { PlatformCompat.setItemName(meta, it) }
                (parameters["item-model"] ?: parameters["model"])?.toString()?.let { PlatformCompat.setItemModel(meta, it.parseNamespacedKey()) }
                itemStack.setItemMeta(meta)
            }
            parameters["lore"].asListEnhanced()?.toComponent()?.let { PlatformCompat.setLore(itemStack, it) }

        }
        if (!components.isNullOrEmpty()) {
            if (VersionHelper.isOrAbove12005()) {
                var nmsStack = asNMSCopy(itemStack)
                components.forEach {
                    val value = it.value
                    if (value != null) EmbianComponent.SafetyComponentSetter.setComponentNMS(nmsStack, it.key, value)?.let { nmsStack = it }
                    else EmbianComponent.SafetyComponentSetter.removeComponentNMS(nmsStack, it.key).let { nmsStack = it }
                }
            } else {
                warningL("WarningNotSupportDataComponent")
            }
        }
        itemStack.amount = amount ?: 1
        return itemStack
    }
}