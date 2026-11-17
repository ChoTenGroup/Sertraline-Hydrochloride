package io.github.zzzyyylllty.sertraline.recipe

import io.github.zzzyyylllty.sertraline.data.SItemFactory
import io.github.zzzyyylllty.sertraline.logger.warningS
import org.bukkit.Material
import java.util.concurrent.ConcurrentHashMap

/**
 * Source category of a recipe item reference.
 */
enum class RecipeSourceKind { MINECRAFT, SERTRALINE, EXTERNAL }

/**
 * Parsed result of a recipe item reference.
 *
 * @property kind The source category.
 * @property id The material name for vanilla items, the bare item ID for Sertraline items, or the provider-local ID for external items.
 * @property provider The provider name for external sources; empty for other sources.
 * @property material The resolved vanilla material; non-null only for [RecipeSourceKind.MINECRAFT].
 * @property raw The original reference string, used for logging.
 */
data class RecipeReference(
    val kind: RecipeSourceKind,
    val id: String,
    val provider: String = "",
    val material: Material? = null,
    val raw: String,
) {
    /** Canonical representation used for logging. */
    val describe: String
        get() = when (kind) {
            RecipeSourceKind.MINECRAFT -> "minecraft:${material?.name ?: id}"
            RecipeSourceKind.SERTRALINE -> "sertraline:$id"
            RecipeSourceKind.EXTERNAL -> "$provider:$id"
        }
}

/** Bare names already warned about, to avoid repeating the warning for every recipe and reload. */
private val warnedBareFallbacks = ConcurrentHashMap.newKeySet<String>()

/**
 * Resolves an item reference in a recipe.
 *
 * **Bare names (without `:`) have the opposite meaning from [SItemFactory.parse]** for backward compatibility:
 * in older recipes, `stone` and `grass_block` referred to vanilla materials, while names such as `doe_card` that are not materials
 * have always referred to Sertraline items. Therefore, resolve bare names as vanilla materials first, then warn and fall back to a Sertraline item.
 *
 * Namespaced references do not fall back: if `minecraft:xxx` does not resolve to a material, the configuration is invalid.
 * Silently treating it as a Sertraline item would only hide the problem.
 *
 * @return The parsed result, or null if the reference is empty or an explicitly specified vanilla material does not exist.
 */
fun resolveRecipeReference(raw: String): RecipeReference? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val idx = trimmed.indexOf(':')
    if (idx == -1) return resolveBare(trimmed)

    val namespace = trimmed.substring(0, idx).trim()
    val id = trimmed.substring(idx + 1).trim()
    // Treat an empty namespace such as ":stone" as a bare name.
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
 * Resolves a bare name by trying a vanilla material first, then falling back to Sertraline.
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
 * `Material.matchMaterial` does not throw for invalid input, but do **not** wrap it in `runCatching`:
 * `runCatching` would also swallow `Error` types such as `NoClassDefFoundError`. If `Material`
 * initialization fails, all vanilla ingredients would silently fall back to Sertraline lookup.
 * Errors of this kind must remain visible.
 */
private fun matchMaterial(name: String): Material? =
    try {
        Material.matchMaterial(name)
    } catch (e: Exception) {
        null
    }
