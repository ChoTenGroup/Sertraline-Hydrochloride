package io.github.zzzyyylllty.sertraline.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfIncreaseCounterTest {

    @Test
    fun zeroStepAtSingleValueTerminatesAfterOneResult() {
        val counter = TemplateManager.SelfIncreaseCounter(7, 7, 0, 1)

        assertEquals("7", counter.next())
        assertNull(counter.next())
    }
}
