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
        // 两步插入都要原子判定，且回滚时只撤掉「本次调用自己插入的」那一条
        if (temporaryCache.putIfAbsent(id, item) != null) {
            throw IllegalStateException("Item $id already exists in public manager")
        }
        if (Sertraline.itemMap.putIfAbsent(id, item) != null) {
            // 注册表里已有同 id 的条目（可能是持久物品），回滚时不能用 remove(id)，
            // 否则会把那条与本次调用无关的条目删掉
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
                // 只有临时表里确实存在该条目时才联动清理注册表；
                // 用 remove(key, value) 保证只删同一个实例，不会误删持久物品
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
