package io.github.zzzyyylllty.sertraline.data

import io.github.zzzyyylllty.sertraline.data.external.ExternalItemData
import io.github.zzzyyylllty.sertraline.util.jsonUtils
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.Material

/**
 * 物品的唯一创建与恢复入口。
 *
 * 所有构造 `AbstractSItem` 的路径都必须经过这里，原因：
 * 1. Gson 不会按 `sItemType` 自动选择实现类，多态恢复必须显式分发；
 * 2. `shouldAdapt` 是 `@Transient` 的运行时策略，反序列化后是 JVM 默认值，
 *    只能由本工厂按来源重新赋值。
 */
object SItemFactory {

    const val TYPE_SERTRALINE = "sertraline"
    const val TYPE_MINECRAFT = "minecraft"
    const val TYPE_EXTERNAL = "plugin"

    /** 无命名空间前缀时视为 Sertraline 物品。 */
    private const val NAMESPACE_SEPARATOR = "::"

    private val minecraftAliases = setOf("minecraft", "mc", "vanilla")
    private val sertralineAliases = setOf("sertraline", "depazitems", "depaz")

    // ---------------------------------------------------------------- 创建

    /** 创建 Sertraline 物品。 */
    fun sertraline(
        key: String,
        data: LinkedHashMap<String, Any?> = linkedMapOf(),
        config: LinkedHashMap<String, Any?> = linkedMapOf(),
        shouldAdapt: Boolean = true,
    ): ModernSItem = ModernSItem(key, data, config).also { it.shouldAdapt = shouldAdapt }

    /** 创建原版物品。 */
    fun minecraft(
        key: String,
        minecraftId: String,
        data: LinkedHashMap<String, Any?> = linkedMapOf(),
        config: LinkedHashMap<String, Any?> = linkedMapOf(),
        shouldAdapt: Boolean = false,
    ): MinecraftSItem = MinecraftSItem(key, minecraftId, TYPE_MINECRAFT, data, config)
        .also { it.shouldAdapt = shouldAdapt }

    /** 创建外部插件物品。 */
    fun external(
        externalItemData: ExternalItemData,
        data: LinkedHashMap<String, Any?> = linkedMapOf(),
        config: LinkedHashMap<String, Any?> = linkedMapOf(),
        shouldAdapt: Boolean = true,
    ): ExternalPluginSItem = ExternalPluginSItem(
        key = externalItemData.id,
        sItemType = TYPE_EXTERNAL,
        data = data,
        config = config,
        externalItemData = externalItemData
    ).also { it.shouldAdapt = shouldAdapt }

    // ---------------------------------------------------------------- 解析

    /** 物品来源类别，供各处解析逻辑共用，避免别名集合散落成多份。 */
    enum class SourceKind { SERTRALINE, MINECRAFT, EXTERNAL }

    /**
     * 按命名空间判定来源类别。
     *
     * 这是全项目唯一的命名空间别名表：`OmniItem`、`itemSource`、配方解析都应调用它，
     * 而不是各自维护一份 `minecraft/mc/vanilla` 列表。
     */
    fun classify(namespace: String?): SourceKind {
        // 允许 null：Gson 可以绕过 Kotlin 非空检查把字段填成 null，这里必须兜住而不是 NPE
        val lowered = namespace?.trim()?.lowercase() ?: return SourceKind.EXTERNAL
        return when (lowered) {
            in sertralineAliases -> SourceKind.SERTRALINE
            in minecraftAliases -> SourceKind.MINECRAFT
            else -> SourceKind.EXTERNAL
        }
    }

    /**
     * 把 `sItemType::id` 形式的引用解析成具体物品。
     *
     * - 无 `::` 的裸 ID 按 Sertraline 物品处理（保持既有语义）；
     * - `minecraft::stone` 解析为原版物品；
     * - `provider::id` 解析为外部插件物品。
     *
     * 只解析结构，不查询注册表；是否已注册由调用方判断。
     */
    fun parse(reference: String): AbstractSItem? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return null

        val index = trimmed.indexOf(NAMESPACE_SEPARATOR)
        if (index < 0) return sertraline(trimmed)

        val namespace = trimmed.substring(0, index).trim().lowercase()
        val localId = trimmed.substring(index + NAMESPACE_SEPARATOR.length).trim()
        if (localId.isEmpty()) return null

        return when {
            namespace.isEmpty() -> null
            namespace in sertralineAliases -> sertraline(localId)
            namespace in minecraftAliases -> minecraft(localId, localId.uppercase())
            else -> external(ExternalItemData(source = namespace, id = localId))
        }
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 从 JSON 恢复物品。
     *
     * 旧数据没有 `sItemType` 字段，默认按 [ModernSItem] 恢复，保证既有数据库与缓存可读。
     *
     * 恢复后统一走 [validate]：Gson 用反射填字段，**不调用 Kotlin 构造函数、不执行默认值表达式**，
     * 因此 `data` / `config` / `externalItemData` 在 JSON 缺字段时会变成 `null` 并绕过非空检查。
     * 不在这里兜底的话，坏数据会在第一个 `storageKey` / `hasPlaceholders` 访问处变成 NPE。
     */
    fun deserialize(json: String?): AbstractSItem? {
        if (json.isNullOrBlank()) return null

        val root = runCatching {
            jsonUtils.fromJson(json, com.google.gson.JsonObject::class.java)
        }.getOrNull() ?: return null

        // 分派必须与 classify/parse 一致地忽略大小写与首尾空白，否则 "Minecraft" 会被
        // 静默降级成 ModernSItem，并永久丢掉 minecraftId
        val type = runCatching {
            root.get("sItemType")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()
        }.getOrNull()

        val key = runCatching {
            root.get("key")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

        if (type != null && type !in setOf(TYPE_SERTRALINE, TYPE_MINECRAFT, TYPE_EXTERNAL)) {
            warningS("[SItemFactory] Unknown sItemType '$type' for item '$key', falling back to $TYPE_SERTRALINE")
        }

        val item: AbstractSItem = runCatching {
            when (type) {
                TYPE_MINECRAFT -> jsonUtils.fromJson(json, MinecraftSItem::class.java)
                TYPE_EXTERNAL -> jsonUtils.fromJson(json, ExternalPluginSItem::class.java)
                else -> jsonUtils.fromJson(json, ModernSItem::class.java)
            }
        }.getOrNull() ?: run {
            warningS("[SItemFactory] Failed to deserialize item '$key'")
            return null
        }

        // `validate` used to run *outside* any guard. Gson fills missing fields with null even for
        // non-null Kotlin types, so `item.minecraftId.isBlank()` threw an NPE that escaped
        // `deserialize()` instead of the item being skipped with a warning — one malformed JSON
        // aborted the whole load/build path. Everything touching potentially-null Gson state is
        // inside the guard now.
        val validated = runCatching { validate(item) }.getOrElse { error ->
            warningS(
                "[SItemFactory] Item '$key' is not recoverable " +
                    "(${error.javaClass.simpleName}: ${error.message}), skipping"
            )
            null
        } ?: return null

        return runCatching { restoreRuntimeState(validated) }.getOrElse { error ->
            warningS("[SItemFactory] Failed to restore runtime state of '$key' (${error.message}), skipping")
            null
        }
    }

    /**
     * 补回 Gson 反射路径跳过的非空约束。
     *
     * 返回 null 表示数据不可恢复，调用方应当跳过它而不是让它进入注册表。
     */
    private fun validate(item: AbstractSItem): AbstractSItem? {
        if (item.data == null || item.config == null) {
            warningS("[SItemFactory] Item '${item.key}' has null data/config, skipping")
            return null
        }
        // `key` is the registry key. A null/blank one used to be accepted and produced an
        // unreachable entry (itemMap[null]) instead of a skipped item.
        if (item.key.let { it.isBlank() }) {
            warningS("[SItemFactory] Item with a null/blank key is not recoverable, skipping")
            return null
        }
        when (item) {
            is ExternalPluginSItem -> {
                val external = item.externalItemData
                if (external == null || external.source.isNullOrBlank() || external.id.isNullOrBlank()) {
                    warningS("[SItemFactory] External item '${item.key}' has incomplete externalItemData, skipping")
                    return null
                }
            }
            is MinecraftSItem -> {
                // Null-tolerant on purpose: Gson may have written null into this non-null field.
                if (item.minecraftId.isNullOrBlank()) {
                    warningS("[SItemFactory] Minecraft item '${item.key}' has a null/blank minecraftId, skipping")
                    return null
                }
            }
            else -> Unit
        }
        return item
    }

    /** 单独恢复一个已构造对象（例如来自缓存的实例）的运行时状态。 */
    fun restoreRuntimeState(item: AbstractSItem): AbstractSItem {
        when (item) {
            is MinecraftSItem -> {
                item.shouldAdapt = false
                // `shouldAdapt = false` means the whole Adapter pipeline (lore / visual / name) is
                // skipped, so any presentation config on a vanilla item would be silently ignored.
                // Say so instead of letting the author wonder why nothing renders.
                val hasPresentationConfig = item.config.containsKey("visual") ||
                    item.config.containsKey("lore") ||
                    item.data.containsKey("xbuilder") ||
                    item.data.containsKey("minecraft")
                if (hasPresentationConfig) {
                    warningS(
                        "[SItemFactory] Minecraft item '${item.key}' declares visual/lore/xbuilder data but " +
                            "vanilla items never enter the Adapter pipeline; those keys are ignored"
                    )
                }
            }
            is ExternalPluginSItem -> item.shouldAdapt = true
            is ModernSItem -> item.shouldAdapt = true
            // No `else` used to be a silent hole for any future AbstractSItem implementation: it
            // kept the JVM default and never entered the Adapter pipeline without any diagnostic.
            else -> warningS(
                "[SItemFactory] Unknown AbstractSItem implementation '${item.javaClass.name}' for " +
                    "'${item.key}'; shouldAdapt was left unchanged"
            )
        }
        return item
    }
}
