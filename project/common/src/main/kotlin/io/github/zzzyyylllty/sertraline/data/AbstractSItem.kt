package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.config.ConfigUtil
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Unified abstraction for all item sources (Sertraline, Minecraft, and external plugins).
 *
 * - [key] is the source-local ID without a namespace prefix.
 * - [sItemType] identifies the source and serves as the serialized type discriminator.
 * - [storageKey] is unique across sources and is used as the registry index.
 * - [data] / [config] are fields shared by all implementations.
 * - [shouldAdapt] is a runtime policy that is **not serialized** and is assigned by SItemFactory during creation and restoration.
 *   It controls only the build-time Sertraline Adapter pipeline and does not affect load-time itemModule processing.
 */
interface AbstractSItem {
    val key: String
    val sItemType: String
    val data: LinkedHashMap<String, Any?>
    val config: LinkedHashMap<String, Any?>

    /** ID unique across sources. For Sertraline items, this is the same as [key]. */
    val storageKey: String

    /** Whether to enter the build-time Sertraline Adapter pipeline. Runtime state; not serialized. */
    val shouldAdapt: Boolean

    /**
     * Whether the value contains a `${...}$` placeholder.
     * Implementations may override this property to cache the result; the default implementation recalculates it each time.
     */
    val hasPlaceholders: Boolean
        get() = configContainsPlaceholders(config) || configContainsPlaceholders(data)

    /** Whether the item contains `sertraline:dynamics` data. */
    val hasDynamics: Boolean
        get() = getDeepData("sertraline:dynamics") != null

    /**
     * Reads a value from [data] using `namespace:section` syntax (and `.` for deeper paths).
     */
    fun getDeepData(location: String): Any? {
        val colonIndex = location.indexOf(':')

        if (colonIndex <= 0) {
            // minecraft -> data["minecraft"]
            return data[location]
        }

        val major = location.substring(0, colonIndex)
        val majorData = data[major] as? Map<*, *> ?: return null
        val section = location.substring(colonIndex + 1)

        return if ('.' in section) {
            // minecraft:food.saturation deep read
            ConfigUtil.getDeep(majorData, section)
        } else {
            // minecraft:food shallow read
            majorData[section]
        }
    }

    fun getMajorData(location: String): Any? = data[location] as? Map<*, *>?

    fun setDeepData(location: String, value: Any?) {
        val colonIndex = location.indexOf(':')
        if (colonIndex <= 0) {
            data[location] = value
            return
        }

        val major = location.substring(0, colonIndex)
        val segments = location.substring(colonIndex + 1).split('.')
        if (segments.any { it.isEmpty() }) return

        @Suppress("UNCHECKED_CAST")
        val majorData = (data[major] as? Map<String, Any?>)?.toMutableMap() ?: linkedMapOf()
        var current = majorData
        for (segment in segments.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            val child = (current[segment] as? Map<String, Any?>)?.toMutableMap() ?: linkedMapOf()
            current[segment] = child
            current = child
        }
        current[segments.last()] = value
        data[major] = majorData
    }

    fun setMajorData(location: String, value: Any?) {
        data[location] = value
    }

    /** Serializes to JSON, writing fields from the concrete runtime type. */
    fun serialize(): String? = jsonUtils.toJson(this)

    /** Deep-copies the item while preserving its concrete subtype and source. The copy does not share [data] / [config]. */
    fun deepCopy(): AbstractSItem

    /**
     * The item's base material.
     *
     * An external source may not have a corresponding Bukkit [Material]. Returns [Material.AIR] when it cannot be determined and **does not throw**.
     */
    fun getMaterial(): Material

    /**
     * Builds the item. The source first creates the base ItemStack; the Sertraline Adapter pipeline runs afterward when [shouldAdapt] is true.
     * Returns an AIR ItemStack if construction fails; never returns null.
     */
    fun buildItem(player: Player?): ItemStack
}
