package io.github.zzzyyylllty.sertraline.item.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GemReturnRecoveryTest {

    private data class FakeGemStack(
        val id: String?,
        val amount: Int = 1,
        val air: Boolean = false,
        val gem: Boolean = true,
    )

    private val validGem: (FakeGemStack, String?) -> Boolean = { item, expectedId ->
        !item.air && item.amount == 1 && if (expectedId != null) {
            item.id == expectedId
        } else {
            item.id != null && item.gem
        }
    }

    @Test
    fun missingPayloadAndGemIdCannotSilentlyClearSlot() {
        var templateBuilds = 0
        val restored = resolveGemReturn<FakeGemStack>(
            itemPayload = null,
            gemId = null,
            decodePayload = { FakeGemStack("gem") },
            buildTemplate = { templateBuilds++; FakeGemStack("template") },
            isValid = validGem,
        )

        assertNull(restored)
        assertEquals(0, templateBuilds)
    }

    @Test
    fun invalidPayloadDoesNotReplaceOriginalGemWithTemplate() {
        var templateBuilds = 0
        val restored = resolveGemReturn(
            itemPayload = byteArrayOf(1),
            gemId = "gem",
            decodePayload = { FakeGemStack("different-gem") },
            buildTemplate = { templateBuilds++; FakeGemStack(it) },
            isValid = validGem,
        )

        assertNull(restored)
        assertEquals(0, templateBuilds)
    }

    @Test
    fun legacySocketWithoutPayloadFallsBackToTemplate() {
        val restored = resolveGemReturn(
            itemPayload = null,
            gemId = "gem",
            decodePayload = { FakeGemStack("decoded") },
            buildTemplate = { FakeGemStack("$it-template") },
            isValid = { item, expected -> item.id == "$expected-template" },
        )

        assertEquals(FakeGemStack("gem-template"), restored)
    }

    @Test
    fun airOrStackedPayloadCannotBeReturnedAsOneSocketedGem() {
        listOf(FakeGemStack("gem", air = true), FakeGemStack("gem", amount = 2)).forEach { invalid ->
            val restored = resolveGemReturn(
                itemPayload = byteArrayOf(1),
                gemId = "gem",
                decodePayload = { invalid },
                buildTemplate = { FakeGemStack(it) },
                isValid = validGem,
            )
            assertNull(restored)
        }
    }
}
