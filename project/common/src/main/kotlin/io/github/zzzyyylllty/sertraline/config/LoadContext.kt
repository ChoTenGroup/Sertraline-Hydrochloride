package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.data.AbstractLoreFormat
import io.github.zzzyyylllty.sertraline.data.CraftingStation
import io.github.zzzyyylllty.sertraline.data.Level
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.Tier
import io.github.zzzyyylllty.sertraline.data.Type

/**
 * 单轮 reload 的候选 registry 构建上下文。
 *
 * loader / ItemLoadEvent / FeatureLoadEvent 在构建阶段读写本 context 的局部 map，
 * 而不是直接写 [io.github.zzzyyylllty.sertraline.Sertraline] 的 live registry；
 * 全部构建成功后由 reload 流程一次性发布（copy 到 live map），失败则丢弃 context，
 * live registry 保持上一轮的完整状态。
 *
 * live fallback：[itemMap]/[mappings]/[tiers] 等的 getter 在 candidate map 中无该键时
 * 回读 live registry —— 用于增量加载（未变更文件的条目仍留在 live map 中，构建
 * 阶段无需重新解析）。
 */
class LoadContext {

    // ---- 局部新增/覆盖条目（candidate） ----

    val items = LinkedHashMap<String, AbstractSItem>()
    val mappings = LinkedHashMap<String, MutableList<String>>()
    val tiers = LinkedHashMap<String, Tier>()
    val types = LinkedHashMap<String, Type>()
    val levels = LinkedHashMap<String, Level>()
    val loreFormats = LinkedHashMap<String, AbstractLoreFormat>()
    val craftingStations = LinkedHashMap<String, CraftingStation>()
    val templates = LinkedHashMap<String, Map<String, Any?>>()

    /** 本轮构建期间写入的 itemCache 条目（发布时合并回全局 itemCache）。 */
    val itemCache = LinkedHashMap<String, Map<String, Any?>>()

    /** 本轮解析到的 item id → 来源文件绝对路径（发布时才写入 live 索引）。 */
    val itemSourceFiles: LinkedHashMap<String, String> = LinkedHashMap()

    /** 本轮增量 item 加载实际解析过的文件绝对路径（增量发布合并判定用）。 */
    val parsedItemFiles = LinkedHashSet<String>()

    /** 本轮在工作区看到过的全部 item 文件（解析过或跳过）；不在其中 = 文件已被删除。 */
    val seenItemFiles = LinkedHashSet<String>()

    /** 本轮 item 加载是否走了增量模式（决定发布前是否合并 live 条目）。 */
    var itemsLoadedIncrementally = false

    // ---- live 回读入口 ----

    val liveItems: Map<String, AbstractSItem> get() = io.github.zzzyyylllty.sertraline.Sertraline.itemMap
    val liveMappings: Map<String, List<String>?> get() = io.github.zzzyyylllty.sertraline.Sertraline.mappings
    val liveTiers: Map<String, Tier> get() = io.github.zzzyyylllty.sertraline.Sertraline.tiers
    val liveTypes: Map<String, Type> get() = io.github.zzzyyylllty.sertraline.Sertraline.types
    val liveLevels: Map<String, Level> get() = io.github.zzzyyylllty.sertraline.Sertraline.levels
    val liveLoreFormats: Map<String, AbstractLoreFormat> get() = io.github.zzzyyylllty.sertraline.Sertraline.loreFormats
    val liveCraftingStations: Map<String, CraftingStation> get() = io.github.zzzyyylllty.sertraline.Sertraline.craftingStations
    val liveTemplates: Map<String, Map<String, Any?>> get() = TemplateManager.getAllTemplates()

    /**
     * 把候选内容发布到 live registry（整体替换语义）。
     *
     * 发布在 ReloadCoordinator 串行 gate 内执行，期间不会有第二轮 reload 并发进入；
     * loreFormats 保持既有的「引用整体替换」语义：packet 读侧持旧引用，
     * 直到下一次读取才看到新 map，无逐项填充的中间态。
     */
    fun publishToLive() {
        val s = io.github.zzzyyylllty.sertraline.Sertraline
        // Build every new map *first*, then swap the references one by one, with `itemMap` LAST.
        // The eight assignments are not a single atomic transaction (that would need one immutable
        // state object), but publishing the item table last means a reader that already sees the new
        // items is guaranteed to see the new mappings / loreFormats / tiers … that those items were
        // built against. Publishing items first (the old order) exposed items whose ids were not yet
        // resolvable through the aux tables.
        val newMappings = java.util.concurrent.ConcurrentHashMap<String, List<String>?>()
        for ((k, v) in mappings) newMappings[k] = v.toList()
        val newTiers = java.util.concurrent.ConcurrentHashMap(tiers)
        val newTypes = java.util.concurrent.ConcurrentHashMap(types)
        val newLevels = java.util.concurrent.ConcurrentHashMap(levels)
        val newStations = java.util.concurrent.ConcurrentHashMap(craftingStations)
        val newLoreFormats = java.util.concurrent.ConcurrentHashMap<String, AbstractLoreFormat>(loreFormats)
        // 本轮构建写入的 itemCache 条目替换全局缓存（原子引用替换，与上面各字段一致）
        // 通过工厂构造，保证替换后仍是有界的 Caffeine 缓存（Caffeine 在 LifeCycle.INIT 注入，此处已在其后）
        val newItemCache = s.newItemCacheMap()
        newItemCache.putAll(itemCache)

        s.mappings = newMappings
        s.tiers = newTiers
        s.types = newTypes
        s.levels = newLevels
        s.craftingStations = newStations
        s.loreFormats = newLoreFormats
        TemplateManager.publishTemplates(templates)
        s.itemCache = newItemCache
        // 来源索引与发布后的 itemMap 对齐（清理已删除条目的陈旧记录），
        // 并把本轮解析出的路径一并写入：候选构建阶段不再碰 live 索引。
        s.itemSourceFile.putAll(itemSourceFiles)
        s.itemSourceFile.keys.retainAll(items.keys)
        // 最后发布 itemMap：读到新物品的读侧一定已经看到与之配套的所有辅助表
        s.itemMap = java.util.concurrent.ConcurrentHashMap(items)
    }
}
