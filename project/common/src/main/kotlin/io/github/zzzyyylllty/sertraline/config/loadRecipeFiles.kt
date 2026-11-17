package io.github.zzzyyylllty.sertraline.config

import io.github.zzzyyylllty.sertraline.config.common.SertralineSettings
import io.github.zzzyyylllty.sertraline.data.*
import io.github.zzzyyylllty.sertraline.event.SertralineRecipeFileLoadPostEvent
import io.github.zzzyyylllty.sertraline.event.SertralineRecipeFileLoadPreEvent
import io.github.zzzyyylllty.sertraline.listener.unlockOnlinePlayers
import io.github.zzzyyylllty.sertraline.logger.infoL
import io.github.zzzyyylllty.sertraline.logger.severeL
import io.github.zzzyyylllty.sertraline.logger.warningL
import io.github.zzzyyylllty.sertraline.util.SertralineRecipeManager
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import java.io.File

/**
 * Loads recipe configuration files.
 * Format reference: recipes/sample.yml.
 */
fun loadRecipeFiles() {
    infoL("Recipe_Load")

    val candidates = prepareRecipeCandidates()
    val (loaded, errors) = commitRecipeCandidates(candidates)

    infoL("Recipe_Load_Complete", loaded, errors)

    // Recipe book: by default a player's recipe book only holds unlocked recipes (progress-based).
    // Sertraline recipes have no progress source, so they must be actively unlocked when the player joins
    // to become visible. Online players are likewise refreshed after a reload.
    unlockOnlinePlayers()
}

/**
 * Phase 1 (any thread): walk the recipes/ directory and parse and validate all candidate recipes
 * without touching the Bukkit recipe registry. Track parse failures per entry instead of failing the whole file.
 */
fun prepareRecipeCandidates(): List<RecipeData> {
    SertralineRecipeFileLoadPreEvent().call()
    val recipesFolder = File(getDataFolder(), "recipes")
    if (!recipesFolder.exists()) {
        warningL("Recipe_Load_Regen")
        releaseResourceFolder("recipes")
    }

    val candidates = mutableListOf<RecipeData>()
    val regex = SertralineSettings.fileLoadRecipe

    val files = safeWalkDirectory(recipesFolder, maxDepth = 10) { file ->
        file.isFile && regex.matches(file.name)
    }
    for (file in files) {
        candidates += parseRecipeFileCandidates(file, regex)
    }
    return candidates
}

/**
 * Phase 2 (main thread): replace all registered Bukkit recipes with the candidates.
 * Unregister the old set first and track candidate registration failures per entry; restore the old set if the overall commit fails.
 */
fun commitRecipeCandidates(candidates: List<RecipeData>): Pair<Int, Int> {
    return try {
        val result = SertralineRecipeManager.commitRecipes(candidates)
        SertralineRecipeFileLoadPostEvent(candidates.size).call()
        result
    } catch (e: Exception) {
        SertralineRecipeFileLoadPostEvent(candidates.size, e).call()
        throw e
    }
}

/**
 * Parses a single recipe file into a candidate list without registering it.
 * @return Pair(recipes parsed successfully, errors)
 */
private fun parseRecipeFileCandidates(file: File, regex: Regex): List<RecipeData> {
    if (file.isDirectory) {
        // Directory recursion is handled by safeWalkDirectory; return an empty list here.
        return emptyList()
    }

    if (!file.name.matches(regex)) {
        return emptyList()
    }

    return try {
        val raw = multiExtensionLoader(file) ?: return emptyList()
        val map = TemplateManager.resolveInMap(raw)

        val candidates = mutableListOf<RecipeData>()
        for ((recipeId, recipeData) in map.entries) {
            try {
                candidates += parseRecipe(recipeId, recipeData as? Map<String, Any?> ?: emptyMap())
            } catch (e: Exception) {
                severeL("Recipe_Load_Error_Parse", file.name, recipeId, e.message ?: "Unknown error")
            }
        }
        candidates
    } catch (e: Exception) {
        severeL("Recipe_Load_Error_File", file.name, e.message ?: "Unknown error")
        emptyList()
    }
}

// ==================== Helper extension functions ====================

private fun Map<String, Any?>.getOptional(key: String): Any? = this[key]
private fun Map<String, Any?>.getString(key: String): String? = this[key]?.toString()
private fun Map<String, Any?>.getInt(key: String): Int? = this[key]?.toString()?.toIntOrNull()
private fun Map<String, Any?>.getFloat(key: String): Float? = this[key]?.toString()?.toFloatOrNull()
private fun Map<String, Any?>.getBoolean(key: String): Boolean? = this[key]?.toString()?.toBooleanStrictOrNull()
private fun Map<String, Any?>.getMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
private fun Map<String, Any?>.getList(key: String): List<Any?>? = this[key] as? List<*>
private fun Map<String, Any?>.getFlexibleList(key: String): List<Any?> {
    val value = this[key] ?: return emptyList()
    return when (value) {
        is List<*> -> value.toList()
        else -> listOf(value)
    }
}

/**
 * Strictly parses a positive integer field (such as count/amount). If the field exists but is not a valid integer or is less than 1,
 * throws IllegalArgumentException instead of silently falling back to 1, which would hide configuration errors and change the loaded result.
 */
private fun requirePositiveInt(map: Map<String, Any?>, key: String, contextName: String): Int {
    val raw = map[key] ?: return 1
    val value = raw.toString().toIntOrNull()
        ?: throw IllegalArgumentException("$contextName: '$key' must be an integer, got '$raw'")
    if (value < 1) throw IllegalArgumentException("$contextName: '$key' must be >= 1, got $value")
    return value
}

/**
 * Strictly parses cooking time: if present, it must be a non-negative integer (0 is allowed for instant cooking).
 */
private fun requireNonNegativeInt(map: Map<String, Any?>, key: String, contextName: String, default: Int): Int {
    val raw = map[key] ?: return default
    val value = raw.toString().toIntOrNull()
        ?: throw IllegalArgumentException("$contextName: '$key' must be an integer, got '$raw'")
    if (value < 0) throw IllegalArgumentException("$contextName: '$key' must be >= 0, got $value")
    return value
}

/**
 * Strictly parses experience: if present, it must be a finite, non-negative number.
 */
private fun requireNonNegativeFloat(map: Map<String, Any?>, key: String, contextName: String, default: Float): Float {
    val raw = map[key] ?: return default
    val value = raw.toString().toFloatOrNull()
        ?: throw IllegalArgumentException("$contextName: '$key' must be a number, got '$raw'")
    if (!value.isFinite()) throw IllegalArgumentException("$contextName: '$key' must be finite, got '$raw'")
    if (value < 0) throw IllegalArgumentException("$contextName: '$key' must be >= 0, got $value")
    return value
}

// ==================== Main parsing function ====================

private fun parseRecipe(id: String, map: Map<String, Any?>): RecipeData {
    val typeStr = map.getString("type") ?: throw IllegalArgumentException("Recipe $id missing type")
    val type = runCatching {
        RecipeType.valueOf(typeStr.uppercase())
    }.getOrElse {
        throw IllegalArgumentException("Recipe $id has invalid type: $typeStr")
    }

    val formatStr = map.getString("format")
    val format = formatStr?.let { runCatching { RecipeFormat.valueOf(it.uppercase()) }.getOrNull() }

    val providerStr = map.getString("provider") ?: "SIMPLE"
    val provider = runCatching {
        RecipeProvider.valueOf(providerStr.uppercase())
    }.getOrElse {
        throw IllegalArgumentException("Recipe $id has invalid provider: $providerStr")
    }

    val inputOptions = parseInputOptions(map.getMap("input-options"))

    return when (type) {
        RecipeType.CRAFTING -> parseCrafting(id, provider, inputOptions, format, map)
        RecipeType.FURNACE, RecipeType.BLASTING, RecipeType.SMOKING, RecipeType.CAMPFIRE ->
            parseCooking(id, provider, inputOptions, type, map)
        RecipeType.STONECUTTING -> parseStonecutting(id, provider, inputOptions, map)
        RecipeType.SMITHING_TRANSFORM, RecipeType.SMITHING_TRIM ->
            parseSmithing(id, provider, inputOptions, type, map)
    }
}

// ==================== Input Options ====================

private fun parseInputOptions(map: Map<String, Any?>?): InputOptions {
    if (map == null) return InputOptions()

    val dataFilter = map.getList("data-filter")?.filterIsInstance<String>() ?: emptyList()

    val components = map.getMap("components")?.let { compMap ->
        val modeStr = compMap.getString("mode") ?: return@let null
        val mode = runCatching { ComponentsMode.valueOf(modeStr.uppercase()) }.getOrNull() ?: return@let null
        val elements = compMap.getList("elements")?.filterIsInstance<String>() ?: emptyList()
        ComponentFilter(mode, elements)
    }

    return InputOptions(dataFilter, components)
}

// ==================== CRAFTING ====================

private fun parseCrafting(
    id: String,
    provider: RecipeProvider,
    inputOptions: InputOptions,
    format: RecipeFormat?,
    map: Map<String, Any?>
): RecipeData {
    val resultMap = map.getMap("result") ?: throw IllegalArgumentException("Recipe $id missing result")
    val result = parseRecipeResult(resultMap)
    val group = map.getString("group")

    return when (format ?: RecipeFormat.SHAPED) {
        RecipeFormat.SHAPED -> {
            val patternList = map.getList("pattern")
                ?: throw IllegalArgumentException("Shaped recipe $id missing pattern")
            val pattern = patternList.filterIsInstance<String>()
            if (pattern.isEmpty() || pattern.size > 3) {
                throw IllegalArgumentException("Shaped recipe $id pattern must have 1-3 rows")
            }
            val keyMap = map.getMap("ingredients")
                ?: throw IllegalArgumentException("Shaped recipe $id missing ingredients")
            val key = keyMap.mapKeys { (keyStr, _) ->
                if (keyStr.length != 1) throw IllegalArgumentException("Shaped recipe $id key must be single char: '$keyStr'")
                keyStr[0]
            }.mapValues { (_, value) ->
                parseIngredient(value)
            }
            val showNotification = map.getBoolean("showNotification") ?: true
            RecipeData.Shaped(id, result, inputOptions, provider, pattern, key, showNotification, group)
        }
        RecipeFormat.SHAPELESS -> {
            val ingredientsList = map.getFlexibleList("ingredients")
            val ingredients = ingredientsList.map { parseIngredient(it) }
            if (ingredients.isEmpty()) {
                throw IllegalArgumentException("Shapeless recipe $id must have at least one ingredient")
            }
            val showNotification = map.getBoolean("showNotification") ?: true
            RecipeData.Shapeless(id, result, inputOptions, provider, ingredients, showNotification, group)
        }
    }
}

// ==================== Cooking ====================

private fun parseCooking(
    id: String,
    provider: RecipeProvider,
    inputOptions: InputOptions,
    type: RecipeType,
    map: Map<String, Any?>
): RecipeData.Cooking {
    val resultMap = map.getMap("result") ?: throw IllegalArgumentException("Recipe $id missing result")
    val result = parseRecipeResult(resultMap)
    val ingredient = parseIngredient(map["ingredient"] ?: throw IllegalArgumentException("Cooking recipe $id missing ingredient"))
    val experience = requireNonNegativeFloat(map, "experience", "Recipe $id", 0.1f)
    val cookingTime = requireNonNegativeInt(map, "cookingTime", "Recipe $id", requireNonNegativeInt(map, "time", "Recipe $id", 200))
    val group = map.getString("group")
    return RecipeData.Cooking(id, result, inputOptions, type, provider, ingredient, experience, cookingTime, group)
}

// ==================== Stonecutting ====================

private fun parseStonecutting(
    id: String,
    provider: RecipeProvider,
    inputOptions: InputOptions,
    map: Map<String, Any?>
): RecipeData.Stonecutting {
    val resultMap = map.getMap("result") ?: throw IllegalArgumentException("Recipe $id missing result")
    val result = parseRecipeResult(resultMap)
    val ingredient = parseIngredient(map["ingredient"] ?: throw IllegalArgumentException("Stonecutting recipe $id missing ingredient"))
    val group = map.getString("group")
    return RecipeData.Stonecutting(id, result, inputOptions, provider, ingredient, group)
}

// ==================== Smithing ====================

private fun parseSmithing(
    id: String,
    provider: RecipeProvider,
    inputOptions: InputOptions,
    type: RecipeType,
    map: Map<String, Any?>
): RecipeData.Smithing {
    val resultMap = map.getMap("result") ?: throw IllegalArgumentException("Recipe $id missing result")
    val result = parseRecipeResult(resultMap)
    val template = parseIngredient(map["template"] ?: throw IllegalArgumentException("Smithing recipe $id missing template"))
    val base = parseIngredient(map["base"] ?: throw IllegalArgumentException("Smithing recipe $id missing base"))
    val addition = parseIngredient(map["addition"] ?: throw IllegalArgumentException("Smithing recipe $id missing addition"))
    val group = map.getString("group")
    return RecipeData.Smithing(id, result, inputOptions, type, provider, template, base, addition, group)
}

// ==================== Result ====================

private fun parseRecipeResult(map: Map<String, Any?>): RecipeResult {
    val itemId = map.getString("id") ?: throw IllegalArgumentException("RecipeResult missing id")
    val count = requirePositiveInt(map, "count", "RecipeResult")
    val functions = parseFunctions(map.getFlexibleList("function"))
    return RecipeResult(itemId, count, functions)
}

private fun parseFunctions(list: List<Any?>): List<RecipeFunction> {
    return list.mapNotNull { item ->
        when (item) {
            is String -> RecipeFunction.Kether(item)
            is Map<*, *> -> {
                val funcMap = item as Map<String, Any?>
                val type = funcMap.getString("type")?.lowercase() ?: return@mapNotNull null
                when (type) {
                    "kether" -> funcMap["kether"]?.let { RecipeFunction.Kether(it.toString()) }
                    "javascript", "js" -> funcMap["script"]?.let { RecipeFunction.JavaScript(it.toString()) }
                        ?: funcMap["javascript"]?.let { RecipeFunction.JavaScript(it.toString()) }
                    "command" -> funcMap["command"]?.let { RecipeFunction.Command(it.toString()) }
                    else -> null
                }
            }
            else -> null
        }
    }
}

// ==================== Ingredient ====================

private fun parseIngredient(value: Any?): RecipeIngredient {
    return when (value) {
        is String -> parseIngredientFromString(value)
        is Map<*, *> -> {
            val map = value as Map<String, Any?>
            when {
                map.containsKey("options") -> {
                    val options = map.getList("options")?.map { parseIngredient(it) } ?: emptyList()
                    RecipeIngredient.Choice(options)
                }
                map.containsKey("itemId") -> {
                    RecipeIngredient.Item(
                        map.getString("itemId") ?: error("Ingredient missing itemId"),
                        requirePositiveInt(map, "amount", "Ingredient"),
                        map.getBoolean("exact") ?: false
                    )
                }
                map.containsKey("tagId") -> {
                    RecipeIngredient.Tag(
                        map.getString("tagId") ?: error("Ingredient missing tagId"),
                        requirePositiveInt(map, "amount", "Ingredient")
                    )
                }
                else -> throw IllegalArgumentException("Ingredient map must contain 'itemId', 'tagId', or 'options'")
            }
        }
        else -> throw IllegalArgumentException("Unsupported ingredient format: ${value?.javaClass?.simpleName}")
    }
}

private val INGREDIENT_SPLIT_REGEX = "\\s+".toRegex()

private fun parseIngredientFromString(str: String): RecipeIngredient {
    val trimmed = str.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("Ingredient string cannot be empty")

    val parts = trimmed.split(INGREDIENT_SPLIT_REGEX)
    val base = parts[0]
    val amount = if (parts.size > 1) {
        val parsed = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("Ingredient amount must be an integer, got '${parts[1]}'")
        if (parsed < 1) throw IllegalArgumentException("Ingredient amount must be >= 1, got $parsed")
        parsed
    } else 1

    return when {
        base.startsWith("#") || base.startsWith("tag:") -> RecipeIngredient.Tag(base, amount)
        // Keep bare names unchanged: resolveRecipeReference first parses them as vanilla materials, then falls back to Sertraline.
        // This keeps both bare vanilla material names and bare Sertraline item names working in existing recipes.
        else -> RecipeIngredient.Item(base, amount)
    }
}
