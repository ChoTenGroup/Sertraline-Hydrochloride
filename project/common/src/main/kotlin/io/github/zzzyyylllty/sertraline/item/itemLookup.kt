package io.github.zzzyyylllty.sertraline.item

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.AbstractSItem

/**
 * Resolves an item template by **either** of its two identities.
 *
 * Items from non-Sertraline sources are registered under [AbstractSItem.storageKey]
 * (`minecraft::stone`, `<plugin>::<id>`) while the build pipeline stamps the bare
 * [AbstractSItem.key] into `sertraline_id`. Looking the stack's id up with a plain
 * `itemMap[id]` therefore missed every Minecraft / external item, so `rebuild()` threw
 * `NullPointerException("Item ... Not Exist")` and the material fallback silently produced a
 * grass block.
 *
 * Resolution order: exact registry key, then `storageKey`, then bare `key`. The last step keeps
 * stacks written before the `storageKey` change (and NBT carried across a plugin switch) working.
 */
fun resolveItemTemplate(id: String): AbstractSItem? {
    val map = Sertraline.itemMap
    map[id]?.let { return it }
    return map.values.firstOrNull { it.storageKey == id }
        ?: map.values.firstOrNull { it.key == id }
}