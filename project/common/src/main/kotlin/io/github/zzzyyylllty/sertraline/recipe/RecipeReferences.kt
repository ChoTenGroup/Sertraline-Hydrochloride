package io.github.zzzyyylllty.sertraline.recipe

import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

/**
 * 配方物品引用的来源类别。
 */
enum class RecipeSourceKind { MINECRAFT, SERTRALINE, EXTERNAL }

/**
 * 一个配方物品引用的解析结果。
 *
 * @property kind 来源类别
 * @property id 原版为材质名、Sertraline 为裸物品 ID、外部为 provider 内的 ID
 * @property provider 外部来源的 provider 名，其余为空串
 * @property material 原版来源解析出的材质，仅 [RecipeSourceKind.MINECRAFT] 时非空
 * @property raw 原始引用字符串，用于日志
 */
data class RecipeReference(
    val kind: RecipeSourceKind,
    val id: String,
    val provider: String = "",
    val material: Material? = null,
    val raw: String,
) {
    /** 用于日志的统一写法。 */
    val describe: String
        get() = when (kind) {
            RecipeSourceKind.MINECRAFT -> "minecraft:${material?.name ?: id}"
            RecipeSourceKind.SERTRALINE -> "sertraline:$id"
            RecipeSourceKind.EXTERNAL -> "$provider:$id"
        }
}

/** 已告警过的裸名，避免每条配方、每次 reload 重复刷屏。 */
private val warnedBareFallbacks = ConcurrentHashMap.newKeySet<String>()

/**
 * 解析配方中的一个物品引用。
 *
 * **裸名（不含 `:`）的语义与 [SItemFactory.parse] 相反**，这是为了向后兼容：
 * 旧配方里 `stone`、`grass_block` 表示原版材质，而 `doe_card` 这类不存在的材质
 * 一直是被当作 Sertraline 物品使用的。所以裸名先按原版材质解析，
 * 解析不到才告警并回落到 Sertraline 物品。
 *
 * 带命名空间的引用不做回落：`minecraft:xxx` 找不到材质就是配置错误，
 * 静默改成 Sertraline 物品只会掩盖问题。
 *
 * @return 解析结果；引用为空或显式原版材质不存在时返回 null
 */
fun resolveRecipeReference(raw: String): RecipeReference? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val idx = trimmed.indexOf(':')
    if (idx == -1) return resolveBare(trimmed)

    val namespace = trimmed.substring(0, idx).trim()
    val id = trimmed.substring(idx + 1).trim()
    // ":stone" 这种空命名空间按裸名处理
    if (namespace.isEmpty()) return resolveBare(id)
    if (id.isEmpty()) return null

    return when (SItemFactory.classify(namespace)) {
        SItemFactory.SourceKind.MINECRAFT -> {
            val material = matchMaterial(id) ?: return null
            RecipeReference(RecipeSourceKind.MINECRAFT, id, material = material, raw = trimmed)
        }
        SItemFactory.SourceKind.SERTRALINE ->
            RecipeReference(RecipeSourceKind.SERTRALINE, id, raw = trimmed)
        SItemFactory.SourceKind.EXTERNAL ->
            RecipeReference(RecipeSourceKind.EXTERNAL, id, provider = namespace.lowercase(), raw = trimmed)
    }
}

/**
 * 裸名解析：原版优先，失败回落 Sertraline。
 */
private fun resolveBare(name: String): RecipeReference? {
    matchMaterial(name)?.let {
        return RecipeReference(RecipeSourceKind.MINECRAFT, name, material = it, raw = name)
    }
    if (warnedBareFallbacks.add(name.lowercase())) {
        warningS(
            "[Recipe] '$name' is not a vanilla material, falling back to Sertraline item '$name'. " +
                "Write it as 'sertraline:$name' to silence this warning."
        )
    }
    return RecipeReference(RecipeSourceKind.SERTRALINE, name, raw = name)
}

/**
 * `Material.matchMaterial` 对非法输入不会抛异常，但**不**用 `runCatching` 兜它：
 * `runCatching` 会连 `NoClassDefFoundError` 之类的 `Error` 一并吞掉，一旦 `Material`
 * 初始化失败，所有原版配料都会静默回落成 Sertraline 查找。
 * 这类错误必须暴露出来。
 */
private fun matchMaterial(name: String): Material? =
    try {
        Material.matchMaterial(name)
    } catch (e: Exception) {
        null
    }
