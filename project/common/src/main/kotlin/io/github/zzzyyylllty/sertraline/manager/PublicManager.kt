package io.github.zzzyyylllty.sertraline.manager

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import java.util.concurrent.ConcurrentHashMap

class PublicManager {

    private val temporaryCache = ConcurrentHashMap<String, AbstractSItem>()

    fun getItem(id: String, sub: SubManagerType): AbstractSItem? {
        return when (sub) {
            SubManagerType.PERSISTENT -> Sertraline.itemMap[id]
            SubManagerType.TEMPORARY -> temporaryCache[id] ?: Sertraline.itemMap[id]
        }
    }

    fun getItem(id: String): AbstractSItem? {
        return Sertraline.itemMap[id] ?: temporaryCache[id]
    }

    fun getAll(sub: SubManagerType): Map<String, AbstractSItem> {
        return when (sub) {
            SubManagerType.PERSISTENT -> Sertraline.itemMap
            SubManagerType.TEMPORARY -> temporaryCache
        }
    }

    @Synchronized
    fun createTemporary(id: String, item: AbstractSItem) {
        // Both insertions must be checked atomically, and rollback must remove only the entry inserted by this call.
        if (temporaryCache.putIfAbsent(id, item) != null) {
            throw IllegalStateException("Item $id already exists in public manager")
        }
        if (Sertraline.itemMap.putIfAbsent(id, item) != null) {
            // The registry may already contain an entry with this ID (possibly a persistent item), so rollback must not call remove(id),
            // or it could delete an entry unrelated to this call.
            temporaryCache.remove(id, item)
            throw IllegalStateException("Item $id already exists in public manager")
        }
    }

    @Synchronized
    fun remove(id: String, sub: SubManagerType): Boolean {
        return when (sub) {
            SubManagerType.PERSISTENT -> {
                if (!ManagerConfig.allowDeletePublicPersistent) {
                    throw IllegalStateException("Deleting public-persistent items is not allowed")
                }
                Sertraline.itemMap.remove(id) != null
            }
            SubManagerType.TEMPORARY -> {
                // Update the registry only when this entry exists in the temporary table.
                // remove(key, value) ensures that only the same instance is deleted, protecting persistent items.
                val removed = temporaryCache.remove(id) ?: return false
                Sertraline.itemMap.remove(id, removed)
                true
            }
        }
    }

    @Synchronized
    internal fun publish(registry: ConcurrentHashMap<String, AbstractSItem>) {
        registry.putAll(temporaryCache)
        Sertraline.itemMap = registry
    }

    @Synchronized
    internal fun clearTemporary() {
        temporaryCache.clear()
    }
}
