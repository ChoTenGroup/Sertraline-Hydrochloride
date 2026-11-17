package io.github.zzzyyylllty.sertraline.manager

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateItemIdCandidatesTest {

    @Test
    fun normalizedAndLegacyIdsAreCheckedTogether() {
        assertEquals(listOf("__sword", "sword"), privateItemIdCandidates("sword"))
        assertEquals(listOf("__sword", "sword"), privateItemIdCandidates("__sword"))
    }
}
