package io.github.zzzyyylllty.sertraline.recipe

import io.github.zzzyyylllty.sertraline.data.RecipeIngredient
import io.github.zzzyyylllty.sertraline.data.validateNmsIngredient
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactRecipeIngredientTest {

    @Test
    fun nmsRejectsExactItemsNestedInChoices() {
        val ingredient = RecipeIngredient.Choice(
            listOf(
                RecipeIngredient.Item("minecraft:stone"),
                RecipeIngredient.Choice(listOf(RecipeIngredient.Item("minecraft:dirt", exact = true)))
            )
        )

        try {
            validateNmsIngredient(ingredient)
            fail("Expected NMS exact ingredient to be rejected")
        } catch (exception: IllegalArgumentException) {
            assertTrue(exception.message.orEmpty().contains("provider: SIMPLE"))
        }
    }
}
