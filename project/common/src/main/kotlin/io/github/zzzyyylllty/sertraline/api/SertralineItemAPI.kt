package io.github.zzzyyylllty.sertraline.api

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.ExternalPluginSItem
import io.github.zzzyyylllty.sertraline.data.MinecraftSItem
import io.github.zzzyyylllty.sertraline.data.ModernSItem
import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 面向 [AbstractSItem] 的物品 API。
 *
 * 与旧的 [SertralineAPI] 的区别：
 * - 参数与返回值使用 `AbstractSItem`，可以容纳 Sertraline / Minecraft / 外部插件物品；
 * - 键一律是 [AbstractSItem.storageKey]（Sertraline 为裸 `ITEMID`，
 *   Minecraft 为 `minecraft::ITEMID`，外部插件为 `provider::ITEMID`）。
 *
 * 旧 API 不会被移除，只是标记为 deprecated。
 */
public object SertralineItemAPI {

    /**
     * 按 ID 查询物品。
     *
     * 既接受裸 Sertraline ID（`ITEMID`），也接受带命名空间的引用
     * （`minecraft::stone`、`provider::id`）。带命名空间的引用若未注册则返回 null。
     *
     * 注意**不会**做「带命名空间 → 裸 key」的回退：否则 `getItem("myprovider::gun")`
     * 会命中一个同名的 Sertraline `gun`，把不同来源的物品串在一起。
     */
    public fun getItem(id: String): AbstractSItem? {
        Sertraline.itemMap[id]?.let { return it }
        val parsed = SItemFactory.parse(id) ?: return null
        return Sertraline.itemMap[parsed.storageKey]
    }

    /**
     * 按 ItemStack 解析物品。
     *
     * 优先返回**注册表里的实例**；未命中时返回 [itemSerializer] 解析出的实例
     * （私有物品 `__`/`private:` 不在注册表中，只有这条路径能拿到），
     * 因此返回值不保证已注册，需要判断时请用 [isRegistered]。
     */
    public fun getItemFromStack(itemStack: ItemStack, player: Player? = null): AbstractSItem? {
        val template = itemSerializer(itemStack, player) ?: return null
        // No bare-`key` fallback: it made `getItemFromStack` return a *different source's* item that
        // merely shared the same local id (the very cross-source mix-up `getItem` avoids).
        return Sertraline.itemMap[template.storageKey] ?: template
    }

    /** 当前注册表中所有物品（不可变快照，键为注册表实际键）。 */
    public fun getAllItems(): Map<String, AbstractSItem> =
        java.util.Collections.unmodifiableMap(java.util.HashMap(Sertraline.itemMap))

    /** 只返回 Sertraline 物品，键为 [AbstractSItem.storageKey]（对 Sertraline 即裸 key）。 */
    public fun getSertralineItems(): Map<String, ModernSItem> =
        getAllItems().values.filterIsInstance<ModernSItem>().associateBy { it.storageKey }

    /** 只返回原版物品，键为 `minecraft::$key`。 */
    public fun getMinecraftItems(): Map<String, MinecraftSItem> =
        getAllItems().values.filterIsInstance<MinecraftSItem>().associateBy { it.storageKey }

    /** 只返回外部插件物品，键为 `provider::$key`。 */
    public fun getExternalItems(): Map<String, ExternalPluginSItem> =
        getAllItems().values.filterIsInstance<ExternalPluginSItem>().associateBy { it.storageKey }

    /** 物品是否已注册。 */
    public fun isRegistered(id: String): Boolean = getItem(id) != null

    /**
     * 注册物品。
     *
     * 默认用 [AbstractSItem.storageKey] 作为键，避免不同来源的同名物品互相覆盖。
     * 显式传 [id] 时按传入的键存储，调用方需自行保证不与其它条目冲突。
     */
    public fun register(item: AbstractSItem, id: String = item.storageKey) {
        val previous = Sertraline.itemMap.put(id, item)
        if (previous != null && previous !== item) {
            warningS(
                "[SertralineItemAPI] Registered item '$id' overwrote an existing entry " +
                    "(${previous.sItemType}:${previous.key}); use a distinct id if this was unintended"
            )
        }
    }

    /** 更新已注册物品，返回被替换的旧物品。 */
    public fun update(id: String, item: AbstractSItem): AbstractSItem? =
        Sertraline.itemMap.put(id, item)

    /** 移除物品，返回被移除的旧物品。 */
    public fun unregister(id: String): AbstractSItem? =
        Sertraline.itemMap.remove(id)

    /** 当前注册物品数量。 */
    public fun getItemCount(): Int = Sertraline.itemMap.size

    /**
     * 解析一个物品引用（不查询注册表）。
     * 裸 ID 解析为 Sertraline 物品，`minecraft::x` / `provider::x` 解析为对应来源。
     */
    public fun parse(reference: String): AbstractSItem? = SItemFactory.parse(reference)

    /** 构建物品；构建失败返回 AIR。 */
    public fun build(item: AbstractSItem, player: Player?): ItemStack = item.buildItem(player)
}
