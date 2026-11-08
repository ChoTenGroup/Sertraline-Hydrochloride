package io.github.zzzyyylllty.sertraline.listener.sertraline.featureloader

import io.github.zzzyyylllty.sertraline.Sertraline.levels
import io.github.zzzyyylllty.sertraline.data.Level
import io.github.zzzyyylllty.sertraline.event.FeatureLoadEvent
import io.github.zzzyyylllty.sertraline.logger.warningS
import taboolib.common.platform.event.SubscribeEvent

/**
 * Handles the sertraline:level feature
 * Resolves a level ID into the actual Level object
 */
@SubscribeEvent
fun getFeatureLevel(e: FeatureLoadEvent) {
    if (e.feature != "sertraline:level") return

    // The item does not define the level feature, so skip it (not an error)
    val raw = e.content ?: return

    val levelId = when (raw) {
        is String -> raw
        is Number -> raw.toString()
        else -> {
            warningS("Invalid level value for item ${e.sItemKey}: ${e.content}")
            e.result = e.content
            return
        }
    }

    // Find the corresponding level (context 模式优先候选表，未命中回读 live)
    val level = e.context?.let { it.levels[levelId] ?: it.liveLevels[levelId] } ?: levels[levelId]
    if (level == null) {
        warningS("Level '$levelId' not found for item ${e.sItemKey}")
        // Return the original value and let default handling continue
        e.result = levelId
        return
    }

    // Set the result to the level ID (string)
    e.result = levelId

    // Also store the level reference in itemCache（context 模式写候选，发布时合并）
    val cacheSink: MutableMap<String, Map<String, Any?>> =
        e.context?.itemCache ?: io.github.zzzyyylllty.sertraline.Sertraline.itemCache
    val cache = e.sItemKey?.let { cacheSink[it] }?.toMutableMap() ?: mutableMapOf()
    cache["level"] = level
    cache["levelId"] = levelId
    e.sItemKey?.let { cacheSink[it] = cache }
}