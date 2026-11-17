package io.github.zzzyyylllty.sertraline.manager

import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.SItemFactory
import java.util.concurrent.ConcurrentHashMap

class PublicManager {

    private val temporaryCache = ConcurrentHashMap<String, AbstractSItem>()
    private val survivalBuffer = LinkedHashMap<String, String>()

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

    fun preReload() {
        survivalBuffer.clear()
        temporaryCache.forEach { (id, item) ->
            // Strip sertraline:context before serialization — it may contain runtime objects
            // (Player, Event, etc.) that Gson cannot round-trip, causing silent data loss.
            val context = item.getDeepData("sertraline:context")
            if (context != null) item.setDeepData("sertraline:context", null)
            try {
                item.serialize()?.let { survivalBuffer[id] = it }
            } finally {
                // Always restore context so the live item remains usable until publishToLive replaces it
                if (context != null) item.setDeepData("sertraline:context", context)
            }
        }
        temporaryCache.clear()
    }

    fun postReload() {
        survivalBuffer.forEach { (id, json) ->
            try {
                val item = SItemFactory.deserialize(json) ?: return@forEach
                temporaryCache[id] = item
                Sertraline.itemMap[id] = item
            } catch (_: Exception) {
                // skip corrupted serialized items
            }
        }
        survivalBuffer.clear()
    }
}
