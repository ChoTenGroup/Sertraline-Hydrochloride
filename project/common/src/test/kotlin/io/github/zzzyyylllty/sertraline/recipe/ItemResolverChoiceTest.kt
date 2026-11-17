package io.github.zzzyyylllty.sertraline.recipe

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemResolverChoiceTest {

    @Test
    fun nestedUnionChoicesArePreservedWhenMerged() {
        val nested = UnionRecipeChoice(
            listOf(
                UnsupportedChoice("nested-1"),
                UnsupportedChoice("nested-2")
            )
        )

        val merged = ItemResolver.mergeRecipeChoices(
            listOf(nested, UnsupportedChoice("outer"))
        ) as UnionRecipeChoice

        assertEquals(listOf("nested-1", "nested-2", "outer"), merged.choices.map { (it as UnsupportedChoice).id })

        val cloned = merged.clone() as UnionRecipeChoice
        assertEquals(listOf("nested-1", "nested-2", "outer"), cloned.choices.map { (it as UnsupportedChoice).id })
    }

    @Test
    fun unsupportedChoiceIsRetained() {
        val merged = ItemResolver.mergeRecipeChoices(listOf(UnsupportedChoice("custom"))) as UnionRecipeChoice
        assertEquals("custom", (merged.choices.single() as UnsupportedChoice).id)
    }

    private class UnsupportedChoice(val id: String) : RecipeChoice {
        @Suppress("DEPRECATION")
        override fun getItemStack(): ItemStack = throw UnsupportedOperationException()

        override fun test(item: ItemStack): Boolean = false

        override fun clone(): RecipeChoice = UnsupportedChoice(id)
    }
}
