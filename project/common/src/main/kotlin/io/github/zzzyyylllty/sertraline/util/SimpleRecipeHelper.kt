package io.github.zzzyyylllty.sertraline.util

import io.github.zzzyyylllty.sertraline.data.*
import io.github.zzzyyylllty.sertraline.item.sertralineItemBuilder
import io.github.zzzyyylllty.sertraline.logger.infoS
import io.github.zzzyyylllty.sertraline.Sertraline
import io.github.zzzyyylllty.sertraline.logger.severeS
import io.github.zzzyyylllty.sertraline.logger.warningS
import io.github.zzzyyylllty.sertraline.recipe.ItemResolver
import io.github.zzzyyylllty.sertraline.recipe.NMSRecipeFactory
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.*
import org.bukkit.inventory.recipe.CraftingBookCategory
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * Sertraline recipe manager.
 * Registers/unregisters recipes using the Bukkit API, replacing the former NMS reflection approach.
 */
object SertralineRecipeManager {

    private val NAMESPACE = "sertraline"

    /** Registered Sertraline recipe key → RecipeData */
    private val registeredRecipes = ConcurrentHashMap<NamespacedKey, RecipeData>()

    /** Bukkit plugin instance, used for main-thread scheduling */
    private val bukkitPlugin: org.bukkit.plugin.Plugin by lazy {
        Bukkit.getPluginManager().getPlugin("Sertraline")
            ?: error("Sertraline Bukkit plugin not found")
    }

    /** Switch the operation to the main thread for execution (blocking the calling thread until complete) */
    private fun <T> sync(callable: () -> T): T {
        if (Bukkit.isPrimaryThread()) return callable()
        return Bukkit.getScheduler().callSyncMethod(bukkitPlugin, Callable(callable)).get()
    }

    // ==================== lifecycle ====================

    fun reload() {
        infoS("<green>Loaded recipes successfully.")
    }

    /**
     * Unregister all Sertraline recipes.
     */
    fun unregisterAll() {
        registeredRecipes.forEach { (key, recipeData) ->
            runCatching {
                sync {
                    when (recipeData.provider) {
                        RecipeProvider.SIMPLE -> Bukkit.removeRecipe(key)
                        RecipeProvider.COMPLEX -> NMSRecipeFactory.unregister(key.namespace, key.key)
                    }
                }
            }.onFailure { e ->
                warningS("Failed to unregister recipe $key: ${e.message}")
            }
        }
        registeredRecipes.clear()
    }

    /**
     * Transactional replace of all registered recipes with the given candidates (main-thread only).
     *
     * Order: snapshot old set → unregister old → register new. Single-recipe failures are counted
     * and do not abort the commit; if the commit itself fails unexpectedly, the previous set is
     * restored so the server keeps working recipes instead of ending up with none.
     *
     * @return Pair(successfully registered count, failed count)
     */
    fun commitRecipes(candidates: List<RecipeData>): Pair<Int, Int> {
        val oldEntries = registeredRecipes.toMap()
        try {
            unregisterAll()
            var loaded = 0
            var errors = 0
            for (recipe in candidates) {
                if (registerRecipe(recipe)) loaded++ else errors++
            }
            return Pair(loaded, errors)
        } catch (e: Throwable) {
            // 补偿：恢复旧注册集（registerRecipe 按相同 id 生成相同的 NamespacedKey）
            severeS("Recipe commit failed, restoring previous recipes: ${e.message}")
            registeredRecipes.clear()
            for ((_, recipeData) in oldEntries) {
                if (registerRecipe(recipeData)) continue
                warningS("Failed to restore recipe ${recipeData.id} during rollback")
            }
            return Pair(0, candidates.size)
        }
    }

    // ==================== registration ====================

    /**
     * Register a recipe to Bukkit (SIMPLE) or the NMS RecipeManager (COMPLEX).
     * @return true if registration succeeded
     */
    fun registerRecipe(recipe: RecipeData): Boolean {
        return runCatching {
            // Single source of truth for the path form; NMSRecipeFactory.register() applies the very
            // same normalization, so the key registered here always matches the one injected there
            // and the one unregisterAll() later passes back.
            val path = NMSRecipeFactory.normalizeRecipePath(recipe.id)
            val key = NamespacedKey(NAMESPACE, path)

            when (recipe.provider) {
                RecipeProvider.SIMPLE -> {
                    val bukkitRecipe = when (recipe) {
                        is RecipeData.Shaped -> buildShaped(key, recipe)
                        is RecipeData.Shapeless -> buildShapeless(key, recipe)
                        is RecipeData.Cooking -> buildCooking(key, recipe)
                        is RecipeData.Stonecutting -> buildStonecutting(key, recipe)
                        is RecipeData.Smithing -> buildSmithing(key, recipe)
                    } ?: return false

                    val added = sync { Bukkit.addRecipe(bukkitRecipe) }
                    if (added) registeredRecipes[key] = recipe
                    added
                }
                RecipeProvider.COMPLEX -> {
                    val added = sync { NMSRecipeFactory.register(recipe) }
                    if (added) registeredRecipes[key] = recipe
                    added
                }
            }
        }.onFailure { e ->
            severeS("Failed to register recipe ${recipe.id}: ${e.message}")
        }.getOrDefault(false)
    }

    /**
     * Get RecipeData by NamespacedKey.
     */
    fun getRecipeData(key: NamespacedKey): RecipeData? = registeredRecipes[key]

    /**
     * Get all registered recipe data.
     */
    fun getAllRecipeData(): Collection<RecipeData> = registeredRecipes.values

    /**
     * Unlock all registered Sertraline recipes for a player (needed for recipe book display).
     * Bukkit's Player.discoverRecipe has been removed in 1.21.4, so use the NMS RecipeBookServer unlock path instead.
     */
    fun unlockAllForPlayer(player: org.bukkit.entity.Player): Int {
        if (registeredRecipes.isEmpty()) return 0
        val ids = registeredRecipes.keys.map { "${it.namespace}:${it.key}" }
        return sync { NMSRecipeFactory.unlockForPlayer(player, ids) }
    }

    // ==================== Builder: Shaped ====================

    private fun buildShaped(key: NamespacedKey, recipe: RecipeData.Shaped): ShapedRecipe? {
        val result = buildResultStack(recipe.result) ?: return null
        val bukkit = ShapedRecipe(key, result)
        bukkit.shape(*recipe.pattern.toTypedArray())

        recipe.key.forEach { (char, ingredient) ->
            val choice = ItemResolver.resolve(ingredient)
            bukkit.setIngredient(char, choice)
        }

        recipe.group?.let { bukkit.setGroup(it) }
        bukkit.setCategory(CraftingBookCategory.MISC)
        trySetShowNotification(bukkit, recipe.showNotification)
        return bukkit
    }

    // ==================== Builder: Shapeless ====================

    private fun buildShapeless(key: NamespacedKey, recipe: RecipeData.Shapeless): ShapelessRecipe? {
        val result = buildResultStack(recipe.result) ?: return null
        val bukkit = ShapelessRecipe(key, result)

        recipe.ingredients.forEach { ingredient ->
            val choice = ItemResolver.resolve(ingredient)
            bukkit.addIngredient(choice)
        }

        recipe.group?.let { bukkit.setGroup(it) }
        bukkit.setCategory(CraftingBookCategory.MISC)
        trySetShowNotification(bukkit, recipe.showNotification)
        return bukkit
    }

    // ==================== Builder: Cooking ====================

    @Suppress("SpellCheckingInspection")
    private fun buildCooking(key: NamespacedKey, recipe: RecipeData.Cooking): CookingRecipe<*>? {
        val result = buildResultStack(recipe.result) ?: return null
        val choice = ItemResolver.resolve(recipe.ingredient)

        val bukkit: CookingRecipe<*> = when (recipe.type) {
            RecipeType.FURNACE -> FurnaceRecipe(key, result, choice, recipe.experience, recipe.cookingTime)
            RecipeType.BLASTING -> BlastingRecipe(key, result, choice, recipe.experience, recipe.cookingTime)
            RecipeType.SMOKING -> SmokingRecipe(key, result, choice, recipe.experience, recipe.cookingTime)
            RecipeType.CAMPFIRE -> CampfireRecipe(key, result, choice, recipe.experience, recipe.cookingTime)
            else -> return null
        }
        recipe.group?.let { bukkit.setGroup(it) }
        return bukkit
    }

    // ==================== Builder: Stonecutting ====================

    private fun buildStonecutting(key: NamespacedKey, recipe: RecipeData.Stonecutting): StonecuttingRecipe? {
        val result = buildResultStack(recipe.result) ?: return null
        val choice = ItemResolver.resolve(recipe.ingredient)
        val bukkit = StonecuttingRecipe(key, result, choice)
        recipe.group?.let { bukkit.setGroup(it) }
        return bukkit
    }

    // ==================== Builder: Smithing ====================

    private fun buildSmithing(key: NamespacedKey, recipe: RecipeData.Smithing): SmithingRecipe? {
        val result = buildResultStack(recipe.result) ?: return null
        val template = ItemResolver.resolve(recipe.template)
        val base = ItemResolver.resolve(recipe.base)
        val addition = ItemResolver.resolve(recipe.addition)

        val bukkit = when (recipe.type) {
            RecipeType.SMITHING_TRANSFORM -> SmithingTransformRecipe(key, result, template, base, addition)
            RecipeType.SMITHING_TRIM -> SmithingTrimRecipe(key, template, base, addition)
            else -> return null
        }
        return bukkit
    }

    // ==================== Result Builder ====================

    private fun buildResultStack(result: RecipeResult): ItemStack? {
        val ref = io.github.zzzyyylllty.sertraline.recipe.resolveRecipeReference(result.itemId)
        if (ref == null) {
            warningS("Recipe result '${result.itemId}' could not be resolved")
            return null
        }
        return when (ref.kind) {
            io.github.zzzyyylllty.sertraline.recipe.RecipeSourceKind.SERTRALINE -> {
                val item = Sertraline.itemMap[ref.id]
                if (item == null) {
                    warningS("Recipe result item 'sertraline:${ref.id}' not found in itemMap (missing item definition?)")
                    return null
                }
                sertralineItemBuilder(ref.id, null)?.apply { amount = result.count }
            }
            io.github.zzzyyylllty.sertraline.recipe.RecipeSourceKind.MINECRAFT -> {
                val mat = ref.material
                if (mat == null) {
                    warningS("Recipe result material '${ref.id}' is not a valid Minecraft material")
                    return null
                }
                ItemStack(mat, result.count)
            }
            io.github.zzzyyylllty.sertraline.recipe.RecipeSourceKind.EXTERNAL -> {
                val stack = ExternalItemHelper.buildNoPlayer(ref.provider, ref.id)
                if (stack == null) {
                    warningS("Recipe result external item '${ref.provider}:${ref.id}' not found via ExternalItemHelper")
                    return null
                }
                stack.apply { amount = result.count }
            }
        }
    }

    private fun parseId(id: String): Pair<String, String> {
        val idx = id.indexOf(':')
        return if (idx == -1) "minecraft" to id.lowercase()
        else id.substring(0, idx).lowercase() to id.substring(idx + 1)
    }

    /**
     * Try to call setShowNotification via reflection (Paper API may not have it in some builds).
     */
    private fun trySetShowNotification(recipe: Any, show: Boolean) {
        runCatching {
            recipe::class.java.getMethod("setShowNotification", Boolean::class.javaPrimitiveType).invoke(recipe, show)
        }
    }
}
