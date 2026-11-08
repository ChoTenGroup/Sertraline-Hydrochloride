package io.github.zzzyyylllty.sertraline.event

import io.github.zzzyyylllty.sertraline.config.LoadContext
import taboolib.platform.type.BukkitProxyEvent

/**
 * 单个物品定义解析事件。
 *
 * [context] 非 null 时表示处于 reload 候选构建阶段：监听器应把结果写入
 * [LoadContext]（见 itemModuleSave），而不是写 live `Sertraline.itemMap`。
 * null 时为兼容入口（外部调用方手动 fire），行为与旧版一致。
 */
class ItemLoadEvent(
    val itemKey: String,
    val arguments: Map<String, Any?>,
    var itemData: MutableMap<String, Any?>,
    val context: LoadContext? = null,
) : BukkitProxyEvent()

/**
 * 物品 feature 解析事件。
 *
 * [context] 非 null 时 feature 解析（tier/type/level/mapping 查找）应优先读取
 * [LoadContext] 的候选表；null 时读 live registry。
 */
class FeatureLoadEvent(
    val sItemKey: String?,
    val content: Any?, // The content of this node
    val feature: String,
    var result: Any?,
    val context: LoadContext? = null,
) : BukkitProxyEvent()
