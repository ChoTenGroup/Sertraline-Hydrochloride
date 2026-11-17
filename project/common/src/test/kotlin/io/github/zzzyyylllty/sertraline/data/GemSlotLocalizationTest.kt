package io.github.zzzyyylllty.sertraline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GemSlotLocalizationTest {

    @Test
    fun itemConfigurationOverridesLanguageText() {
        val result = resolveGemSlotLabel("ruby", "Name", "<red>Ruby Socket") {
            fail("Language fallback should not be queried when the item defines a name")
            ""
        }

        assertEquals("<red>Ruby Socket", result)
    }

    @Test
    fun missingItemTextFallsBackToLanguageNode() {
        val result = resolveGemSlotLabel("ruby", "Desc", null) { node ->
            assertEquals("Gem_Slot_ruby_Desc", node)
            "<gray>Fits a ruby."
        }

        assertEquals("<gray>Fits a ruby.", result)
    }

    @Test
    fun missingLanguageNodeUsesNameOrEmptyDescription() {
        assertEquals("ruby", resolveGemSlotLabel("ruby", "Name", null) { it })
        assertEquals("", resolveGemSlotLabel("ruby", "Desc", null) { it })
    }
}
