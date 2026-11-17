package io.github.zzzyyylllty.sertraline.item.gem

import io.github.zzzyyylllty.sertraline.data.AlterationOperation
import io.github.zzzyyylllty.sertraline.data.SDataAlteration
import io.github.zzzyyylllty.sertraline.item.RecordedAlteration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemAlterationRebaseTest {

    private val records = listOf(
        RecordedAlteration(
            alteration = SDataAlteration(
                "gem_ruby_socket_sertraline:vars.atk",
                "sertraline:vars.atk",
                AlterationOperation.SET,
                20,
            ),
            persisted = true,
            previous = 10,
            previousKnown = true,
            previousPresent = true,
            order = 0,
        ),
        RecordedAlteration(
            alteration = SDataAlteration(
                "gem_blue_socket_sertraline:vars.atk",
                "sertraline:vars.atk",
                AlterationOperation.ADD,
                3,
            ),
            persisted = true,
            previous = 20,
            previousKnown = true,
            previousPresent = true,
            order = 1,
        ),
    )

    @Test
    fun removingSetGemReplaysLaterAddGem() {
        val plan = planGemAlterationRebase(records, "ruby", listOf("ruby", "blue"))
        assertEquals(listOf("gem_ruby_socket_sertraline:vars.atk"), plan.removedRecords.map { it.alteration.name })
        assertEquals(listOf("gem_blue_socket_sertraline:vars.atk"), plan.replayRecords.map { it.alteration.name })
        assertFalse(plan.replayRecords.single().persisted)
        assertEquals(13, applyToBase(plan.replayRecords))
    }

    @Test
    fun removingAddGemReplaysEarlierSetGem() {
        val plan = planGemAlterationRebase(records, "blue", listOf("ruby", "blue"))
        assertEquals(listOf("gem_blue_socket_sertraline:vars.atk"), plan.removedRecords.map { it.alteration.name })
        assertEquals(listOf("gem_ruby_socket_sertraline:vars.atk"), plan.replayRecords.map { it.alteration.name })
        assertTrue(plan.replayRecords.single().persisted.not())
        assertEquals(20, applyToBase(plan.replayRecords))
    }

    private fun applyToBase(records: List<RecordedAlteration>): Any? {
        val vars = linkedMapOf<String, Any?>("atk" to 10)
        val sertraline = linkedMapOf<String, Any?>("vars" to vars)
        val data = linkedMapOf<String, Any?>("sertraline" to sertraline)
        records.forEach { it.alteration.applyTo(data) }
        return vars["atk"]
    }
}
