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

    private class PersistentItemCache : ConcurrentHashMap<String, ModernSItem>() {
        var fullyLoadedFromDatabase: Boolean = false
    }

    private val temporaryCache = ConcurrentHashMap<String, ConcurrentHashMap<String, ModernSItem>>()
    private val persistentLocks = Array(64) { Any() }

    // Caffeine-backed cache: evicts UUID entries after 30 min of no access, preventing
    // unbounded memory growth from offline players' persistent items.
    // Data is safe — the DB is the source of truth; eviction just drops the in-memory copy.
    //
    // !! Must stay LAZY !! PrivateManager is constructed by ItemManager, which is constructed by the
    // Sertraline object's static initializer (LifeCycle.CONST). Caffeine is a runtime-injected,
    // relocated dependency (io.github.zzzyyylllty.sertraline.dep.caffeine) that only appears at
    // LifeCycle.INIT, i.e. after CONST — building this eagerly throws NoClassDefFoundError and
    // prevents the plugin from loading at all.
    private val persistentCache: java.util.concurrent.ConcurrentMap<String, PersistentItemCache> by lazy {
        Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build<String, PersistentItemCache>()
            .asMap()
    }

    fun getItem(uuid: String, id: String, sub: SubManagerType): ModernSItem? {
        val normalizedId = normalizePrivateItemId(id)
        return when (sub) {
            SubManagerType.TEMPORARY -> temporaryCache[uuid]?.get(normalizedId)
            SubManagerType.PERSISTENT -> withPersistentLock(uuid) {
                // Write the DB load result back to the cache to avoid repeated getItem calls hitting the database
                val map = persistentCache.computeIfAbsent(uuid) { PersistentItemCache() }
                map[normalizedId] ?: loadFromDB(
                    uuid,
                    normalizedId,
                    privateItemIdCandidates(id).firstOrNull { it != normalizedId }
                )?.also { map[normalizedId] = it }
            }
        }
    }

    internal fun cachedIds(uuid: String, sub: SubManagerType): List<String> = when (sub) {
        SubManagerType.TEMPORARY -> temporaryCache[uuid]?.keys?.toList().orEmpty()
        SubManagerType.PERSISTENT -> persistentCache[uuid]?.keys?.toList().orEmpty()
    }

    fun getAll(uuid: String, sub: SubManagerType): Map<String, ModernSItem> {
        return when (sub) {
            SubManagerType.TEMPORARY -> temporaryCache[uuid] ?: emptyMap()
            SubManagerType.PERSISTENT -> withPersistentLock(uuid) {
                val cache = persistentCache.computeIfAbsent(uuid) { PersistentItemCache() }
                if (!cache.fullyLoadedFromDatabase) {
                    val loaded = loadAllFromDB(uuid)
                    if (loaded != null) {
                        cache.clear()
                        cache.putAll(loaded)
                        cache.fullyLoadedFromDatabase = true
                    }
                }
                cache
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
            SubManagerType.PERSISTENT -> withPersistentLock(uuid) {
                val map = persistentCache.computeIfAbsent(uuid) { PersistentItemCache() }
                if (map.containsKey(normalizedId)) {
                    throw IllegalStateException("Item $normalizedId already exists in private/persistent for UUID $uuid")
                }

                val existingId = privateItemIdCandidates(id).firstOrNull { candidateId ->
                    DatabaseManager.privateItemMapper.existsByKey(privateItemProbe(uuid, candidateId))
                }
                if (existingId != null) {
                    throw IllegalStateException("Item $existingId already exists in private/persistent for UUID $uuid")
                }

                if (map.putIfAbsent(normalizedId, normalizedItem) != null) {
                    throw IllegalStateException("Item $normalizedId already exists in private/persistent for UUID $uuid")
                }
                try {
                    insertToDB(uuid, normalizedId, normalizedItem, "")
                } catch (e: Exception) {
                    map.remove(normalizedId, normalizedItem)
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
            SubManagerType.PERSISTENT -> withPersistentLock(uuid) {
                val map = persistentCache.computeIfAbsent(uuid) { PersistentItemCache() }
                val legacyId = privateItemIdCandidates(id).firstOrNull { it != normalizedId }
                val previous = map[normalizedId] ?: loadFromDB(uuid, normalizedId, legacyId)
                try {
                    saveOrUpdateToDB(uuid, normalizedId, normalizedItem, "")
                    if (legacyId != null) deleteLegacyRowAfterMigration(uuid, legacyId)
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
        return when (sub) {
            SubManagerType.TEMPORARY -> {
                temporaryCache[uuid]?.remove(normalizedId) != null
            }
            SubManagerType.PERSISTENT -> withPersistentLock(uuid) {
                val cache = persistentCache[uuid]
                val cached = cache?.containsKey(normalizedId) == true
                val candidates = privateItemIdCandidates(id)
                val dbRemoved = deleteFromDB(uuid, candidates.first(), candidates.getOrNull(1))
                if (cached || dbRemoved) cache?.remove(normalizedId)
                cached || dbRemoved
            }
        }
    }

    fun clearAll(uuid: String) {
        temporaryCache.remove(uuid)
        withPersistentLock(uuid) { persistentCache.remove(uuid) }
    }

    /** Evict a UUID's persistent cache entry on player quit. DB data is untouched. */
    fun evict(uuid: String) {
        withPersistentLock(uuid) { persistentCache.remove(uuid) }
    }

    fun shutdown() {
        temporaryCache.clear()
        persistentCache.clear()
    }

    private fun loadFromDB(uuid: String, id: String, legacyId: String? = null): ModernSItem? {
        return try {
            val ids = sequenceOf(id, legacyId).filterNotNull().distinct()
            for (candidateId in ids) {
                val result = DatabaseManager.privateItemMapper.findOneByKey(privateItemProbe(uuid, candidateId)) ?: continue
                // Skip only the entry that fails to deserialize; do not abort the whole lookup, including the legacy fallback.
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

    private fun loadAllFromDB(uuid: String): Map<String, ModernSItem>? {
        return try {
            val items = DatabaseManager.privateItemMapper.findAll(uuid)
            val map = ConcurrentHashMap<String, ModernSItem>()
            // If old unprefixed and canonical rows coexist, the canonical value must win
            // regardless of database result order.
            for (pi in items.sortedBy { it.itemId == normalizePrivateItemId(it.itemId) }) {
                try {
                    val normalizedId = normalizePrivateItemId(pi.itemId)
                    map[normalizedId] = normalizeItem(normalizedId, deserializeSertraline(pi.itemData) ?: continue)
                } catch (e: Exception) {
                    warningS("[PrivateManager] Failed to deserialize item '${pi.itemId}' for UUID $uuid: ${e.message}")
                }
            }
            map
        } catch (e: Exception) {
            warningS("[PrivateManager] Failed to load all items for UUID $uuid: ${e.message}")
            null
        }
    }

    private fun insertToDB(uuid: String, id: String, item: ModernSItem, subPath: String) {
        try {
            DatabaseManager.privateItemMapper.insert(toPrivateItem(uuid, id, item, subPath))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create item $id in database: ${e.message}", e)
        }
    }

    private fun saveOrUpdateToDB(uuid: String, id: String, item: ModernSItem, subPath: String) {
        try {
            DatabaseManager.privateItemMapper.upsertBatch(listOf(toPrivateItem(uuid, id, item, subPath)))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to update item $id in database: ${e.message}")
        }
    }

    private fun toPrivateItem(uuid: String, id: String, item: ModernSItem, subPath: String): PrivateItem {
        val json = item.serialize() ?: throw IllegalStateException("Failed to serialize item $id")
        return PrivateItem(
            uuid = uuid,
            itemId = id,
            itemData = json,
            subPath = subPath,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun privateItemProbe(uuid: String, id: String): PrivateItem =
        PrivateItem(uuid = uuid, itemId = id, itemData = "", subPath = "", createdAt = 0L)

    private fun deleteLegacyRowAfterMigration(uuid: String, legacyId: String) {
        val probe = privateItemProbe(uuid, legacyId)
        try {
            if (DatabaseManager.privateItemMapper.existsByKey(probe)) {
                DatabaseManager.privateItemMapper.deleteByKey(probe)
                check(!DatabaseManager.privateItemMapper.existsByKey(probe)) {
                    "Legacy database row '$legacyId' still exists after migration"
                }
            }
        } catch (e: Exception) {
            // The canonical row is already saved. Keep it authoritative on later getAll loads.
            warningS("[PrivateManager] Failed to remove legacy item '$legacyId' for UUID $uuid after update: ${e.message}")
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
                    check(!DatabaseManager.privateItemMapper.existsByKey(probe)) {
                        "Database row '$candidateId' still exists after deletion"
                    }
                    removed = true
                }
            }
            removed
        } catch (e: Exception) {
            warningS("[PrivateManager] Failed to delete item '$id' (legacy=$legacyId) for UUID $uuid: ${e.message}")
            throw IllegalStateException("Failed to delete private item '$id' for UUID $uuid", e)
        }
    }

    private fun normalizeItem(id: String, item: ModernSItem): ModernSItem {
        if (item.key == id) return item
        val copy = item.deepCopy()
        return ModernSItem(id, copy.data, copy.config)
    }

    private inline fun <T> withPersistentLock(uuid: String, action: () -> T): T =
        synchronized(persistentLocks[(uuid.hashCode() and Int.MAX_VALUE) % persistentLocks.size], action)
}
