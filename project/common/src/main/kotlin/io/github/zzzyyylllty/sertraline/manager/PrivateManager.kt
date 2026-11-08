package io.github.zzzyyylllty.sertraline.manager

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.zzzyyylllty.sertraline.database.DatabaseManager
import io.github.zzzyyylllty.sertraline.database.PrivateItem
import io.github.zzzyyylllty.sertraline.data.ModernSItem
import io.github.zzzyyylllty.sertraline.data.deserializeSertraline
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import io.github.zzzyyylllty.sertraline.logger.warningS
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PrivateManager {

    private val temporaryCache = ConcurrentHashMap<String, ConcurrentHashMap<String, ModernSItem>>()

    // Caffeine-backed cache: evicts UUID entries after 30 min of no access, preventing
    // unbounded memory growth from offline players' persistent items.
    // Data is safe — the DB is the source of truth; eviction just drops the in-memory copy.
    //
    // !! Must stay LAZY !! PrivateManager is constructed by ItemManager, which is constructed by the
    // Sertraline object's static initializer (LifeCycle.CONST). Caffeine is a runtime-injected,
    // relocated dependency (io.github.zzzyyylllty.sertraline.dep.caffeine) that only appears at
    // LifeCycle.INIT, i.e. after CONST — building this eagerly throws NoClassDefFoundError and
    // prevents the plugin from loading at all.
    private val persistentCache: java.util.concurrent.ConcurrentMap<String, ConcurrentHashMap<String, ModernSItem>> by lazy {
        Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build<String, ConcurrentHashMap<String, ModernSItem>>()
            .asMap()
    }

    fun getItem(uuid: String, id: String, sub: SubManagerType): ModernSItem? {
        val normalizedId = normalizePrivateItemId(id)
        return when (sub) {
            SubManagerType.TEMPORARY -> temporaryCache[uuid]?.get(normalizedId)
            SubManagerType.PERSISTENT -> {
                // Write the DB load result back to the cache to avoid repeated getItem calls hitting the database
                val map = persistentCache.computeIfAbsent(uuid) { ConcurrentHashMap() }
                map[normalizedId] ?: loadFromDB(
                    uuid,
                    normalizedId,
                    id.takeUnless { it == normalizedId }
                )?.also { map[normalizedId] = it }
            }
        }
    }

    fun getAll(uuid: String, sub: SubManagerType): Map<String, ModernSItem> {
        return when (sub) {
            SubManagerType.TEMPORARY -> temporaryCache[uuid] ?: emptyMap()
            SubManagerType.PERSISTENT -> {
                val cached = persistentCache[uuid]
                if (cached != null) return cached
                loadAllFromDB(uuid)
                persistentCache[uuid] ?: emptyMap()
            }
        }
    }

    fun createItem(uuid: String, id: String, item: ModernSItem, sub: SubManagerType) {
        val normalizedId = normalizePrivateItemId(id)
        val normalizedItem = normalizeItem(normalizedId, item)
        when (sub) {
            SubManagerType.TEMPORARY -> {
                val map = temporaryCache.computeIfAbsent(uuid) { ConcurrentHashMap() }
                if (map.putIfAbsent(normalizedId, normalizedItem) != null) {
                    throw IllegalStateException("Item $normalizedId already exists in private/temporary for UUID $uuid")
                }
            }
            SubManagerType.PERSISTENT -> {
                val map = persistentCache.computeIfAbsent(uuid) { ConcurrentHashMap() }
                if (map.putIfAbsent(normalizedId, normalizedItem) != null) {
                    throw IllegalStateException("Item $normalizedId already exists in private/persistent for UUID $uuid")
                }
                // Upsert, not insert: the cache is evicted on quit (evict()), so re-creating an id
                // whose row still exists in the database used to hit a primary-key conflict and
                // abort with "Failed to save item …". The in-memory guard above is what enforces
                // "already exists" for this session.
                try {
                    saveOrUpdateToDB(uuid, normalizedId, normalizedItem, "")
                } catch (e: Exception) {
                    map.remove(normalizedId)
                    throw e
                }
            }
        }
    }

    /**
     * Replace a private item in the selected manager.
     * Persistent items are written with an update-by-key/upsert path so an existing
     * database row is never blindly inserted a second time.
     * @return the previous item, or null when the ID was not present
     */
    fun updateItem(uuid: String, id: String, item: ModernSItem, sub: SubManagerType): ModernSItem? {
        val normalizedId = normalizePrivateItemId(id)
        val normalizedItem = normalizeItem(normalizedId, item)
        return when (sub) {
            SubManagerType.TEMPORARY -> {
                val map = temporaryCache.computeIfAbsent(uuid) { ConcurrentHashMap() }
                map.put(normalizedId, normalizedItem)
            }
            SubManagerType.PERSISTENT -> {
                val map = persistentCache.computeIfAbsent(uuid) { ConcurrentHashMap() }
                val previous = map[normalizedId] ?: loadFromDB(uuid, normalizedId)
                try {
                    saveOrUpdateToDB(uuid, normalizedId, normalizedItem, "")
                    map[normalizedId] = normalizedItem
                    previous
                } catch (e: Exception) {
                    if (previous == null) {
                        map.remove(normalizedId)
                    } else {
                        map[normalizedId] = previous
                    }
                    throw e
                }
            }
        }
    }

    fun deleteItem(uuid: String, id: String, sub: SubManagerType): Boolean {
        val normalizedId = normalizePrivateItemId(id)
        val legacyId = id.takeUnless { it == normalizedId }
        return when (sub) {
            SubManagerType.TEMPORARY -> {
                temporaryCache[uuid]?.remove(normalizedId) != null
            }
            SubManagerType.PERSISTENT -> {
                val cacheRemoved = persistentCache[uuid]?.remove(normalizedId) != null
                // Used to return a hardcoded `true`: callers could not tell "deleted" from
                // "nothing existed", and a failed DB delete looked successful.
                val dbRemoved = deleteFromDB(uuid, normalizedId, legacyId)
                cacheRemoved || dbRemoved
            }
        }
    }

    fun clearAll(uuid: String) {
        temporaryCache.remove(uuid)
        persistentCache.remove(uuid)
    }

    /** Evict a UUID's persistent cache entry on player quit. DB data is untouched. */
    fun evict(uuid: String) {
        persistentCache.remove(uuid)
    }

    fun shutdown() {
        temporaryCache.clear()
        persistentCache.clear()
    }

    private fun loadFromDB(uuid: String, id: String, legacyId: String? = null): ModernSItem? {
        return try {
            val ids = sequenceOf(id, legacyId).filterNotNull().distinct()
            for (candidateId in ids) {
                val probe = PrivateItem(uuid = uuid, itemId = candidateId, itemData = "", subPath = "", createdAt = 0L)
                val result = DatabaseManager.privateItemMapper.findOneByKey(probe) ?: continue
                // 单条数据反序列化失败只跳过该条，不能让整个查找（含 legacy 回退）短路
                val restored = deserializeSertraline(result.itemData)
                if (restored == null) {
                    warningS("[PrivateManager] Item '$candidateId' for UUID $uuid is not a Sertraline item, skipping")
                    continue
                }
                return normalizeItem(id, restored)
            }
            null
        } catch (e: Exception) {
            warningS("[PrivateManager] Failed to load item '$id' (legacy=$legacyId) for UUID $uuid: ${e.message}")
            null
        }
    }

    private fun loadAllFromDB(uuid: String) {
        try {
            val items = DatabaseManager.privateItemMapper.findAll(uuid)
            val map = ConcurrentHashMap<String, ModernSItem>()
            for (pi in items) {
                try {
                    val normalizedId = normalizePrivateItemId(pi.itemId)
                    map[normalizedId] = normalizeItem(normalizedId, deserializeSertraline(pi.itemData) ?: continue)
                } catch (e: Exception) {
                    warningS("[PrivateManager] Failed to deserialize item '${pi.itemId}' for UUID $uuid: ${e.message}")
                }
            }
            persistentCache[uuid] = map
        } catch (e: Exception) {
            warningS("[PrivateManager] Failed to load all items for UUID $uuid: ${e.message}")
        }
    }

    private fun saveOrUpdateToDB(uuid: String, id: String, item: ModernSItem, subPath: String) {
        try {
            val json = item.serialize() ?: throw IllegalStateException("Failed to serialize item $id")
            val entity = PrivateItem(
                uuid = uuid,
                itemId = id,
                itemData = json,
                subPath = subPath,
                createdAt = System.currentTimeMillis()
            )
            DatabaseManager.privateItemMapper.upsertBatch(listOf(entity))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to update item $id in database: ${e.message}")
        }
    }

    /**
     * Deletes every database row for [id] (plus the pre-normalisation [legacyId], if any).
     *
     * @return true when at least one row existed and was removed. Returns false when nothing was
     * there (so the caller can report "not found") and on failure — a failure is warned about, not
     * silently reported as success.
     */
    private fun deleteFromDB(uuid: String, id: String, legacyId: String? = null): Boolean {
        return try {
            val ids = sequenceOf(id, legacyId).filterNotNull().distinct()
            var removed = false
            ids.forEach { candidateId ->
                val probe = PrivateItem(uuid = uuid, itemId = candidateId, itemData = "", subPath = "", createdAt = 0L)
                // deleteByKey() returns Unit, so existence has to be probed explicitly.
                if (DatabaseManager.privateItemMapper.existsByKey(probe)) {
                    DatabaseManager.privateItemMapper.deleteByKey(probe)
                    removed = true
                }
            }
            removed
        } catch (e: Exception) {
            warningS("[PrivateManager] Failed to delete item '$id' (legacy=$legacyId) for UUID $uuid: ${e.message}")
            false
        }
    }

    private fun normalizeItem(id: String, item: ModernSItem): ModernSItem {
        if (item.key == id) return item
        val copy = item.deepCopy()
        return ModernSItem(id, copy.data, copy.config)
    }
}
