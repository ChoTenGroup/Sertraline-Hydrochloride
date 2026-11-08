package io.github.zzzyyylllty.sertraline.attribute

import io.github.zzzyyylllty.sertraline.debugMode.devLog
import io.github.zzzyyylllty.sertraline.item.itemSerializer
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit

object AttributeManager {
    // Concurrent on purpose: providers are registered/unregistered at runtime (reload, third-party
    // plugins) while a refresh is iterating. A plain LinkedHashMap threw
    // ConcurrentModificationException, or silently lost a provider, in that window. Iteration order
    // stays insertion order, which is what the provider contract documents.
    private val providers = java.util.Collections.synchronizedMap(LinkedHashMap<String, AttributeProvider>())

    fun register(provider: AttributeProvider) {
        providers[provider.name] = provider
        devLog("AttributeProvider registered: ${provider.name}")
    }

    fun unregister(name: String) {
        providers.remove(name)
    }

    fun unregisterAll() {
        providers.clear()
    }

    fun listProviders(): List<String> = synchronized(providers) { providers.keys.toList() }

    /**
     * Recomputes the player's attribute set from the items they currently wear.
     *
     * Runs on the **main thread**: the inventory is Bukkit state and [itemSerializer] touches
     * `ItemMeta`/NBT, neither of which may be read off-thread. The previous implementation wrapped
     * the whole read in `submitAsync`, so it could observe a half-applied inventory change or throw
     * from a region/thread-checking server. Providers are then invoked in the same tick.
     */
    fun refreshAttributes(player: Player) {
        submit {
            if (!player.isOnline) return@submit
            val inv = player.inventory
            val itemList = LinkedHashMap<String, ItemBound>()

            inv.itemInMainHand.let { bItem ->
                itemSerializer(bItem, player)?.let { itemList["mainhand"] = ItemBound(it, bItem) }
            }
            inv.itemInOffHand.let { bItem ->
                itemSerializer(bItem, player)?.let { itemList["offhand"] = ItemBound(it, bItem) }
            }
            inv.helmet?.let { bItem ->
                itemSerializer(bItem, player)?.let { itemList["helmet"] = ItemBound(it, bItem) }
            }
            inv.chestplate?.let { bItem ->
                itemSerializer(bItem, player)?.let { itemList["chestplate"] = ItemBound(it, bItem) }
            }
            inv.leggings?.let { bItem ->
                itemSerializer(bItem, player)?.let { itemList["leggings"] = ItemBound(it, bItem) }
            }
            inv.boots?.let { bItem ->
                itemSerializer(bItem, player)?.let { itemList["boots"] = ItemBound(it, bItem) }
            }

            for (provider in snapshotProviders()) {
                try {
                    provider.refreshAttributes(player, itemList)
                } catch (e: Throwable) {
                    // A failing provider used to be logged at debug level only, so a broken hook
                    // looked like "the player simply has less attack damage". Warn loudly instead —
                    // one bad provider must not silence the rest either.
                    warningS("AttributeProvider[${provider.name}] failed for ${player.name}: ${e.message}")
                    devLog("AttributeProvider[${provider.name}] stack trace follows")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun snapshotProviders(): List<AttributeProvider> =
        synchronized(providers) { providers.values.toList() }
}