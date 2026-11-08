package io.github.zzzyyylllty.sertraline.listener.sertraline.featureloader

import io.github.zzzyyylllty.sertraline.Sertraline.tiers
import io.github.zzzyyylllty.sertraline.data.Tier
import io.github.zzzyyylllty.sertraline.event.FeatureLoadEvent
import io.github.zzzyyylllty.sertraline.logger.warningS
import taboolib.common.platform.event.SubscribeEvent

/**
 * Handles the sertraline:tier feature
 * Resolves a quality ID into the actual Tier object
 */
@SubscribeEvent
fun getFeatureTier(e: FeatureLoadEvent) {
    if (e.feature != "sertraline:tier") return

    // The item does not define the quality feature, so skip it (not an error)
    val raw = e.content ?: return

    val tierId = when (raw) {
        is String -> raw
        is Number -> raw.toString()
        else -> {
            warningS("Invalid tier value for item ${e.sItemKey}: ${e.content}")
            e.result = e.content
            return
        }
    }

    // Find the corresponding quality (context 模式优先候选表，未命中回读 live)
    val tier = e.context?.let { it.tiers[tierId] ?: it.liveTiers[tierId] } ?: tiers[tierId]
    if (tier == null) {
        warningS("Tier '$tierId' not found for item ${e.sItemKey}")
        // Return the original value and let default handling continue
        e.result = tierId
        return
    }

    // Set the result to the quality ID (string)
    e.result = tierId

    // Also store the quality reference in itemCache（context 模式写候选，发布时合并）
    val cacheSink: MutableMap<String, Map<String, Any?>> =
        e.context?.itemCache ?: io.github.zzzyyylllty.sertraline.Sertraline.itemCache
    val cache = e.sItemKey?.let { cacheSink[it] }?.toMutableMap() ?: mutableMapOf()
    cache["tier"] = tier
    cache["tierId"] = tierId
    e.sItemKey?.let { cacheSink[it] = cache }
}