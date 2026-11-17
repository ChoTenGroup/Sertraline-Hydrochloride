package io.github.zzzyyylllty.sertraline.event

import io.github.zzzyyylllty.sertraline.config.LoadContext
import taboolib.platform.type.BukkitProxyEvent

/**
 * Event for parsing a single item definition.
 *
 * A non-null [context] means this is the candidate build phase of a reload: listeners should write results to
 * [LoadContext] (see itemModuleSave) instead of the live `Sertraline.itemMap`.
 * A null context is the compatibility entry point for external callers that fire the event manually; behavior matches the previous version.
 */
class ItemLoadEvent(
    val itemKey: String,
    val arguments: Map<String, Any?>,
    var itemData: MutableMap<String, Any?>,
    val context: LoadContext? = null,
) : BukkitProxyEvent()

/**
 * Event for parsing item features.
 *
 * When [context] is non-null, feature parsing (tier/type/level/mapping lookup) should check the candidate tables in
 * [LoadContext] first; when it is null, read from the live registry.
 */
class FeatureLoadEvent(
    val sItemKey: String?,
    val content: Any?, // The content of this node
    val feature: String,
    var result: Any?,
    val context: LoadContext? = null,
) : BukkitProxyEvent()
