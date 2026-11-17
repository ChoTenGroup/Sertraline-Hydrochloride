package io.github.zzzyyylllty.sertraline.data

/**
 * Recipe type enum
 */
enum class RecipeType {
    CRAFTING,
    FURNACE,
    BLASTING,
    SMOKING,
    CAMPFIRE,
    STONECUTTING,
    SMITHING_TRANSFORM,
    SMITHING_TRIM
}

/**
 * Recipe format: shaped/shapeless (only valid for CRAFTING)
 */
enum class RecipeFormat {
    SHAPED, SHAPELESS
}

/**
 * How the recipe is provided
 */
enum class RecipeProvider {
    SIMPLE,  // Bukkit API
    COMPLEX  // NMS injection (reflection on RecipeManager via NMSRecipeFactory)
}

/**
 * Input component filter mode
 */
enum class ComponentsMode {
    REMOVE, KEEP
}

/**
 * Input options: control how input items are processed during crafting
 */
data class InputOptions(
    val dataFilter: List<String> = emptyList(),
    val components: ComponentFilter? = null
)

data class ComponentFilter(
    val mode: ComponentsMode = ComponentsMode.REMOVE,
    val elements: List<String> = emptyList()
)

/**
 * Recipe ingredient
 */
sealed class RecipeIngredient {
    /**
     * A specific item, e.g. sertraline:doe_card / minecraft:stone / itemsadder:some_item
     * @param exact true = exact stack matching for SIMPLE recipes. COMPLEX/NMS recipes cannot express
     *   component-level exact matching and reject this option. Only use for deterministic items without
     *   player-dependent placeholders.
     */
    data class Item(val itemId: String, val amount: Int = 1, val exact: Boolean = false) : RecipeIngredient()

    /** A tag, e.g. minecraft:planks / #minecraft:planks */
    data class Tag(val tagId: String, val amount: Int = 1) : RecipeIngredient()

    /** Choice among several */
    data class Choice(val options: List<RecipeIngredient>) : RecipeIngredient()
}

/** Validates the subset representable by the vanilla NMS Ingredient type. */
internal fun validateNmsIngredient(ingredient: RecipeIngredient) {
    when (ingredient) {
        is RecipeIngredient.Item -> {
            require(ingredient.amount == 1) {
                "Recipe ingredient '${ingredient.itemId}' amount ${ingredient.amount} is unsupported; each recipe slot consumes one item"
            }
            require(!ingredient.exact) {
                "Exact ingredient '${ingredient.itemId}' is unsupported by COMPLEX/NMS recipes; use provider: SIMPLE"
            }
        }
        is RecipeIngredient.Tag -> require(ingredient.amount == 1) {
            "Recipe tag '${ingredient.tagId}' amount ${ingredient.amount} is unsupported; each recipe slot consumes one item"
        }
        is RecipeIngredient.Choice -> ingredient.options.forEach(::validateNmsIngredient)
    }
}

/**
 * Recipe result item
 */
data class RecipeResult(
    val itemId: String,
    val count: Int = 1,
    val functions: List<RecipeFunction> = emptyList()
)

/**
 * Result function (executed after crafting)
 */
sealed class RecipeFunction {
    data class Kether(val script: String) : RecipeFunction()
    data class JavaScript(val script: String) : RecipeFunction()
    data class Command(val command: String) : RecipeFunction()
}

/**
 * Recipe data
 */
sealed class RecipeData {
    abstract val id: String
    abstract val type: RecipeType
    abstract val provider: RecipeProvider
    abstract val result: RecipeResult
    abstract val inputOptions: InputOptions

    data class Shaped(
        override val id: String,
        override val result: RecipeResult,
        override val inputOptions: InputOptions,
        override val provider: RecipeProvider = RecipeProvider.SIMPLE,
        val pattern: List<String>,
        val key: Map<Char, RecipeIngredient>,
        val showNotification: Boolean = true,
        val group: String? = null
    ) : RecipeData() {
        override val type: RecipeType = RecipeType.CRAFTING
    }

    data class Shapeless(
        override val id: String,
        override val result: RecipeResult,
        override val inputOptions: InputOptions,
        override val provider: RecipeProvider = RecipeProvider.SIMPLE,
        val ingredients: List<RecipeIngredient>,
        val showNotification: Boolean = true,
        val group: String? = null
    ) : RecipeData() {
        override val type: RecipeType = RecipeType.CRAFTING
    }

    data class Cooking(
        override val id: String,
        override val result: RecipeResult,
        override val inputOptions: InputOptions,
        override val type: RecipeType,
        override val provider: RecipeProvider = RecipeProvider.SIMPLE,
        val ingredient: RecipeIngredient,
        val experience: Float = 0.1f,
        val cookingTime: Int = 200,
        val group: String? = null
    ) : RecipeData()

    data class Stonecutting(
        override val id: String,
        override val result: RecipeResult,
        override val inputOptions: InputOptions,
        override val provider: RecipeProvider = RecipeProvider.SIMPLE,
        val ingredient: RecipeIngredient,
        val group: String? = null
    ) : RecipeData() {
        override val type: RecipeType = RecipeType.STONECUTTING
    }

    data class Smithing(
        override val id: String,
        override val result: RecipeResult,
        override val inputOptions: InputOptions,
        override val type: RecipeType,
        override val provider: RecipeProvider = RecipeProvider.SIMPLE,
        val template: RecipeIngredient,
        val base: RecipeIngredient,
        val addition: RecipeIngredient,
        val group: String? = null
    ) : RecipeData()
}
