package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.config.ConfigUtil
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 所有物品来源（Sertraline / Minecraft / 外部插件）的统一抽象。
 *
 * - [key] 是来源内部的 ID，不带命名空间前缀。
 * - [sItemType] 是来源类别，同时作为序列化时的类型判别字段。
 * - [storageKey] 是跨来源唯一标识，供注册表索引使用。
 * - [data] / [config] 是所有实现共有的字段。
 * - [shouldAdapt] 是运行时策略，**不参与序列化**，由 SItemFactory 在创建和恢复时赋值；
 *   它只控制构建期的 Sertraline Adapter 管线，不影响加载期的 itemModule 处理。
 */
interface AbstractSItem {
    val key: String
    val sItemType: String
    val data: LinkedHashMap<String, Any?>
    val config: LinkedHashMap<String, Any?>

    /** 跨来源唯一 ID。Sertraline 物品直接等于 [key]。 */
    val storageKey: String

    /** 是否进入构建期的 Sertraline Adapter 管线。运行时状态，不序列化。 */
    val shouldAdapt: Boolean

    /**
     * 是否包含 `${...}$` 占位符。
     * 实现可覆盖此属性以缓存计算结果；默认实现每次现算。
     */
    val hasPlaceholders: Boolean
        get() = configContainsPlaceholders(config) || configContainsPlaceholders(data)

    /** 是否包含 `sertraline:dynamics` 动态数据。 */
    val hasDynamics: Boolean
        get() = getDeepData("sertraline:dynamics") != null

    /**
     * 按 `namespace:section`（可继续用 `.` 深层读取）从 [data] 取值。
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
        val split = location.split(":")
        if (split.size >= 2) {
            val major = split[0]
            val section = location.removePrefix("${split[0]}:")
            // 缺失时创建 major map，避免对没有该命名空间段的物品静默失败
            //（例如 LoreFormatUtil.generateLore 向无 sertraline 段的物品注入 defaultVars）
            @Suppress("UNCHECKED_CAST")
            val cvalue = (data[major] as? Map<*, *>)?.toMutableMap() ?: linkedMapOf<Any?, Any?>()
            cvalue[section] = value
            @Suppress("UNCHECKED_CAST")
            data[major] = cvalue as MutableMap<String, Any?>
        } else data[location] = value
    }

    fun setMajorData(location: String, value: Any?) {
        data[location] = value
    }

    /** 序列化为 JSON；写入的是运行时具体类型的字段。 */
    fun serialize(): String? = jsonUtils.toJson(this)

    /** 深拷贝，保留具体子类型与来源信息。副本不会共享 [data] / [config]。 */
    fun deepCopy(): AbstractSItem

    /**
     * 物品的基础材料。
     *
     * 外部来源未必有对应的 Bukkit [Material]；无法确定时返回 [Material.AIR]，**不抛异常**。
     */
    fun getMaterial(): Material

    /**
     * 构建物品。先由来源自身构建基础 ItemStack，[shouldAdapt] 为 true 时再走 Sertraline Adapter 管线。
     * 构建失败返回 AIR ItemStack，不返回 null。
     */
    fun buildItem(player: Player?): ItemStack
}
