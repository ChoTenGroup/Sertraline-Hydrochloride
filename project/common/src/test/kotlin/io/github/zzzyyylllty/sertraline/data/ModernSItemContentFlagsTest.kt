package io.github.zzzyyylllty.sertraline.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernSItemContentFlagsTest {

    @Test
    fun placeholderFlagTracksDirectNestedConfigMutation() {
        val item = ModernSItem("test")
        assertFalse(item.hasPlaceholders)

        item.config["display"] = linkedMapOf("name" to "\${name}$")

        assertTrue(item.hasPlaceholders)
    }

    @Test
    fun dynamicFlagTracksDirectNestedDataMutation() {
        val item = ModernSItem("test")
        assertFalse(item.hasDynamics)

        item.data["sertraline"] = linkedMapOf("dynamics" to linkedMapOf("name" to "value"))

        assertTrue(item.hasDynamics)
    }
}
