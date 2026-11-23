package io.github.zzzyyylllty.sertraline.util

import io.github.zzzyyylllty.sertraline.compat.PlatformCompat
import org.bukkit.Keyed
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

object ItemTagManager {

    val `clazz$ResourceLocation`: Class<*> get() = ItemReflections.clazz_ResourceLocation

    val `clazz$MinecraftKey`: Class<*> get() = ItemReflections.clazz_ResourceLocation

    // Spigot's net.minecraft.core.Registry is the older getId/byId interface.
    val `clazz$IRegistry` by lazy {
        getClazzCompat("net.minecraft.core.IRegistry", "net.minecraft.core.Registry")
    }

    val registryGetValueMethod by lazy {
        getDeclaredMethodCompat(`clazz$IRegistry`, listOf("getValue", "a"), null, `clazz$MinecraftKey`)
    }

    @Volatile
    private var vanillaItemTags: Map<String, List<String>> = emptyMap()
    private val customItemTags = ConcurrentHashMap<String, MutableList<String>>()

    internal fun clear() {
        vanillaItemTags = emptyMap()
        customItemTags.clear()
    }

    fun initializeVanillaTags() {
        // Resolve the public Tag interface lazily so the Legacy build can run on 1.12.
        val tagClass = try {
            Class.forName("org.bukkit.Tag", false, ItemTagManager::class.java.classLoader)
        } catch (_: ClassNotFoundException) {
            vanillaItemTags = emptyMap()
            return
        }
        val getValues = tagClass.getMethod("getValues")
        val tags = checkNotNull(PlatformCompat.getTags("items", Material::class.java) as? Iterable<*>) {
            "The Bukkit item tag registry is unavailable"
        }
        val snapshot = LinkedHashMap<String, List<String>>()
        for (tag in tags) {
            val key = (tag as Keyed).key.toString()
            val values = getValues.invoke(tag) as Iterable<*>
            val items = values.filterIsInstance<Keyed>().map { it.key.toString() }.sorted()
            if (items.isNotEmpty()) snapshot[key] = items
        }
        vanillaItemTags = snapshot
    }

    // Get all items for a given tag
    fun getItemsByTag(tagKey: String): List<String> {
        val vanillaItems = vanillaItemTags[tagKey] ?: emptyList()
        val customItems = customItemTags[tagKey] ?: emptyList()
        return (vanillaItems + customItems).distinct()
    }

    // Register a custom tag
    fun registerCustomTag(tagKey: String, items: List<String>) {
        customItemTags.computeIfAbsent(tagKey) { mutableListOf() }.addAll(items)
    }

    // Add an item to a custom tag
    fun addItemToCustomTag(itemKey: String, tagKey: String) {
        customItemTags.computeIfAbsent(tagKey) { mutableListOf() }.add(itemKey)
    }

    // Check whether an item has a given tag
    fun hasItemTag(itemKey: String, tagKey: String): Boolean {
        return getItemsByTag(tagKey).contains(itemKey)
    }

    // Get all tags
    fun getAllTags(): Set<String> {
        return (vanillaItemTags.keys + customItemTags.keys).toSet()
    }

    /**
     * Parse a tag string, supporting multiple formats and extracting the amount
     * @param tagString the tag string, e.g. "tag:planks 2", "#minecraft:planks", "minecraft:planks"
     * @return Pair(normalized tag Key, amount), amount defaults to 1
     */
    fun parseTagString(tagString: String): Pair<String, Int> {
        var normalized = tagString.trim()

        // strip the # prefix
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1)
        }

        // check whether it contains an amount (space-separated)
        val parts = normalized.split("\\s+".toRegex())
        val base = parts[0]
        val amount = if (parts.size > 1) {
            parts[1].toIntOrNull() ?: 1
        } else {
            1
        }

        // convert the tag: prefix to minecraft:
        var tagKey = base
        if (tagKey.startsWith("tag:")) {
            tagKey = "minecraft:" + tagKey.substring(4)
        }

        // if there is no namespace, add minecraft:
        if (!tagKey.contains(":")) {
            tagKey = "minecraft:$tagKey"
        }

        return Pair(tagKey, amount)
    }
}
