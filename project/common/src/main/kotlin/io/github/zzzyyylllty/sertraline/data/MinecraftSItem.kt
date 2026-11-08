package io.github.zzzyyylllty.sertraline.data

import com.cryptomorin.xseries.XMaterial
import io.github.zzzyyylllty.sertraline.Sertraline
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack


/**
 * 原版物品。
 *
 * [key] 是来源内部标识（例如 `stone`），[minecraftId] 是用于解析 [Material] 的名称。
 */
data class MinecraftSItem(

    override val key: String,

    /* Material id, uppercase, eg STONE */
    val minecraftId: String,

    override val sItemType: String = "minecraft",
    override val data: LinkedHashMap<String, Any?> = linkedMapOf(),
    override val config: LinkedHashMap<String, Any?> = linkedMapOf(),
) : AbstractSItem {

    override val storageKey: String
        get() = "minecraft::$key"

    /** 运行时策略，由 SItemFactory 赋值，不参与序列化。 */
    @Transient
    override var shouldAdapt: Boolean = false

    override fun getMaterial(): Material {
        val normalized = minecraftId.trim().substringAfter(':').uppercase()
        if (normalized.isEmpty()) return Material.AIR
        return runCatching { Material.valueOf(normalized) }.getOrNull()
            ?: runCatching {
                XMaterial.matchXMaterial(normalized).orElse(null)?.parseItem()?.type
            }.getOrNull()
            ?: Material.AIR
    }

    override fun deepCopy(): MinecraftSItem = MinecraftSItem(
        key = key,
        minecraftId = minecraftId,
        sItemType = sItemType,
        data = deepCopyMap(data),
        config = deepCopyMap(config)
    ).also { it.shouldAdapt = shouldAdapt }

    override fun buildItem(player: Player?): ItemStack {
        val material = getMaterial()
        if (material == Material.AIR) return ItemStack(Material.AIR)
        val base = ItemStack(material)
        return Sertraline.itemManager.processItem(this, base, player)
    }
}
