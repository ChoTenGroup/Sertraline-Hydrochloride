package io.github.zzzyyylllty.sertraline.item

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentParentSnapshotTest {

    @Test
    fun capturesAndRestoresScalarParentWithoutLosingSiblings() {
        val original = linkedMapOf<String, Any?>("a" to linkedMapOf("b" to 5, "sibling" to 9))
        val snapshot = capturePersistentParentSnapshot(original, "a.b.c")

        assertEquals(PersistentParentSnapshot("a.b", true, 5), snapshot)

        val changed = linkedMapOf<String, Any?>("a" to linkedMapOf("b" to linkedMapOf("c" to 1), "sibling" to 9))
        restorePersistentMapNode(changed, snapshot!!.path, snapshot.present, snapshot.value)

        assertEquals(original, changed)
    }

    @Test
    fun missingParentIsRemovedAfterNestedAlteration() {
        val original = linkedMapOf<String, Any?>()
        val snapshot = capturePersistentParentSnapshot(original, "a.b")
        assertEquals(PersistentParentSnapshot("a", false, null), snapshot)

        val changed = linkedMapOf<String, Any?>("a" to linkedMapOf("b" to 1))
        restorePersistentMapNode(changed, snapshot!!.path, snapshot.present, snapshot.value)

        assertTrue(changed.isEmpty())
    }

    @Test
    fun missingVariablesRootIsRecordedAsAbsent() {
        val snapshot = capturePersistentParentSnapshot(null, "a.b")

        assertEquals(PersistentParentSnapshot("", false, null), snapshot)
        assertNull(capturePersistentParentSnapshot(emptyMap(), "a"))
        assertFalse(snapshot!!.present)
    }
}
