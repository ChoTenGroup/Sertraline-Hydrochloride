package io.github.zzzyyylllty.sertraline.manager

import io.github.zzzyyylllty.sertraline.data.AbstractSItem
import io.github.zzzyyylllty.sertraline.data.SItemFactory

class ItemManager {

    val public = PublicManager()
    val privateManager = PrivateManager()

    object Vars {
        const val MANAGER = "@smanager_manager"
        const val SUB = "@smanager_sub"
        const val SELECTED = "@smanager_selected"
        const val UUID = "@smanager_uuid"
    }

    fun getItem(type: ManagerType, sub: SubManagerType, id: String, uuid: String? = null): AbstractSItem? {
        return when (type) {
            ManagerType.PUBLIC -> public.getItem(id, sub)
            ManagerType.PRIVATE -> {
                val resolvedUuid = resolvePrivateUuid(uuid, null)
                privateManager.getItem(resolvedUuid, normalizePrivateItemId(id), sub)
            }
        }
    }

    fun createItem(type: ManagerType, sub: SubManagerType, id: String, data: Map<String, Any?>, uuid: String? = null) {
        if (type == ManagerType.PUBLIC && sub == SubManagerType.PERSISTENT) {
            throw UnsupportedOperationException("Creating public-persistent items is not yet supported")
        }
        val normalizedId = if (type == ManagerType.PRIVATE) normalizePrivateItemId(id) else id
        val item = SItemFactory.sertraline(key = normalizedId, data = LinkedHashMap(data), config = linkedMapOf())
        when (type) {
            ManagerType.PUBLIC -> {
                public.createTemporary(id, item)
            }
            ManagerType.PRIVATE -> {
                val resolvedUuid = resolvePrivateUuid(uuid, null)
                privateManager.createItem(resolvedUuid, normalizedId, item, sub)
            }
        }
    }

    fun deleteItem(type: ManagerType, sub: SubManagerType, id: String, uuid: String? = null) {
        when (type) {
            ManagerType.PUBLIC -> public.remove(id, sub)
            ManagerType.PRIVATE -> {
                val resolvedUuid = resolvePrivateUuid(uuid, null)
                privateManager.deleteItem(resolvedUuid, normalizePrivateItemId(id), sub)
            }
        }
    }

    fun resolvePrivateUuid(uuid: String?, playerUuid: String?): String {
        if (!uuid.isNullOrBlank()) return uuid
        if (!playerUuid.isNullOrBlank()) return playerUuid
        val autoUuid = ManagerConfig.autoUuid
        if (autoUuid.isNotBlank()) return autoUuid
        throw IllegalStateException("No UUID available for private manager operation. Use 'smanager switch <uuid>' or specify a player.")
    }

    fun preReload() {
        public.preReload()
    }

    fun postReload() {
        public.postReload()
    }

    fun shutdown() {
        privateManager.shutdown()
    }

    /** Evict a player's private persistent cache on quit (DB data is safe). */
    fun evictPlayer(uuid: String) {
        privateManager.evict(uuid)
    }
}
