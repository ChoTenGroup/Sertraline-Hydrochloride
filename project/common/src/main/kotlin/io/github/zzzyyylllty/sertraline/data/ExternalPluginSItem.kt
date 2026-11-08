package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.external.ExternalItemData
import io.github.zzzyyylllty.sertraline.util.ExternalItemHelper
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack


/**
 * 外部插件物品（ItemsAdder / Oraxen / Nexo / CraftEngine 等）。
 *
 * 基础物品通过 ItemBridge 按 [ExternalItemData] 构建，不伪造 Bukkit Material。
 */
data class ExternalPluginSItem(

    override val key: String,

    override val sItemType: String = "plugin",
    override val data: LinkedHashMap<String, Any?> = linkedMapOf(),
    override val config: LinkedHashMap<String, Any?> = linkedMapOf(),

    val externalItemData: ExternalItemData,
) : AbstractSItem {

    /** 由 provider 与物品 ID 组合，避免不同 provider 的同名物品冲突。 */
    override val storageKey: String
        get() = "${externalItemData.source}::$key"

    /** 运行时策略，由 SItemFactory 赋值，不参与序列化。 */
    @Transient
    override var shouldAdapt: Boolean = true

    /**
     * 外部物品不保证对应 bukkit [Material]。
     * 这里尝试无 Player 构建一次并取其材质；失败或未安装 provider 时返回 [Material.AIR]。
     */
    override fun getMaterial(): Material {
        return runCatching { buildBaseItem(null)?.type }.getOrNull() ?: Material.AIR
    }

    private fun buildBaseItem(player: Player?): ItemStack? {
        val source = externalItemData.source
        val id = externalItemData.id.ifEmpty { key }
        return runCatching {
            if (player != null) ExternalItemHelper.build(player, source, id)
            else ExternalItemHelper.buildNoPlayer(source, id)
        }.getOrNull()
    }

    override fun deepCopy(): ExternalPluginSItem = ExternalPluginSItem(
        key = key,
        sItemType = sItemType,
        data = deepCopyMap(data),
        config = deepCopyMap(config),
        // Deep-copied as well: `metaData` is a mutable map, and sharing it meant edits on one copy
        // (a build override, an API tweak) leaked into the live registry template.
        externalItemData = externalItemData.deepCopy()
    ).also { it.shouldAdapt = shouldAdapt }

    override fun buildItem(player: Player?): ItemStack {
        val base = buildBaseItem(player) ?: return ItemStack(Material.AIR)
        if (base.type == Material.AIR) return base
        return Sertraline.itemManager.processItem(this, base, player)
    }
}
