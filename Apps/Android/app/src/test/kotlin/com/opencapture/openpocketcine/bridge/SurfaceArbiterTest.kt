package com.opencapture.openpocketcine.bridge

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class SurfaceArbiterTest {
    private val applied = mutableListOf<String?>()
    private val arbiter = SurfaceArbiter<String> { applied += it }

    @Test
    fun borrowedPictureShowsOnlyWhileTheAppViewIsGone() {
        arbiter.setLocal("app")
        arbiter.setExternal("m2")
        assertFalse(arbiter.showingExternal)
        // App goes behind M2: its surface dies and the decoder moves to M2's, never to null.
        arbiter.setLocal(null)
        assertTrue(arbiter.showingExternal)
        // Back to the app: its new surface wins at once.
        arbiter.setLocal("app2")
        assertEquals(listOf<String?>("app", "m2", "app2"), applied)
    }

    @Test
    fun releasingTheBorrowedSurfaceFallsBackBeforeReturning() {
        arbiter.setExternal("m2")
        assertEquals("m2", applied.last())
        assertEquals(null, arbiter.setExternal(null))
        assertEquals(listOf<String?>("m2", null), applied)
    }

    @Test
    fun repeatedSameChoiceDoesNotReapply() {
        arbiter.setLocal("app")
        arbiter.setLocal("app")
        arbiter.setExternal(null)
        assertEquals(listOf<String?>("app"), applied)
    }

    @Test
    fun inTheBackgroundTheBorrowedSurfaceWinsEvenIfTheAppKeepsItsOwn() {
        arbiter.setLocal("app")
        arbiter.setExternal("m2")
        arbiter.setForeground(false)
        assertTrue(arbiter.showingExternal)
        arbiter.setForeground(true)
        assertEquals(listOf<String?>("app", "m2", "app"), applied)
    }
}
