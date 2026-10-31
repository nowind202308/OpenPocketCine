package com.opencapture.openpocketcine.macro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadialMenuLogicTest {
    private val inner = 100f
    private val outer = 400f

    /** A point [r] px from the center along [angle]. */
    private fun at(l: RadialLayout, angle: Double, r: Float = 250f) =
        (l.cx + r * cos(angle)).toFloat() to (l.cy + r * sin(angle)).toFloat()

    @Test
    fun fanOpensTowardTheScreenFromEitherEdge() {
        val left = RadialMenuLogic.layout(50f, 1200f, 1080, 4, inner, outer)
        val right = RadialMenuLogic.layout(1030f, 1200f, 1080, 4, inner, outer)
        assertEquals(0.0, left.facing)
        assertEquals(PI, right.facing)
        // Every sector of the left fan points right of the button, and the reverse.
        assertTrue(left.angles.all { cos(it) > 0 })
        assertTrue(right.angles.all { cos(it) < 0 })
    }

    @Test
    fun sectorsAreEvenAndInOrderTopToBottom() {
        val l = RadialMenuLogic.layout(50f, 1200f, 1080, 4, inner, outer)
        assertEquals(Math.toRadians(40.0), l.sweep, 1e-9)
        // Screen y grows downward, so increasing angle on the left fan runs top to bottom.
        assertTrue(l.angles.zipWithNext().all { (a, b) -> b > a })
        assertTrue(sin(l.angles.first()) < 0 && sin(l.angles.last()) > 0)
    }

    @Test
    fun hitPicksTheSectorUnderTheFinger() {
        val l = RadialMenuLogic.layout(50f, 1200f, 1080, 4, inner, outer)
        l.angles.forEachIndexed { i, a ->
            val (x, y) = at(l, a)
            assertEquals(i, RadialMenuLogic.hit(l, x, y))
            // Near a sector's edge still picks it.
            val (ex, ey) = at(l, a + l.sweep * 0.45)
            assertEquals(i, RadialMenuLogic.hit(l, ex, ey))
        }
    }

    @Test
    fun centerCancelsAndBehindTheFanMisses() {
        val l = RadialMenuLogic.layout(50f, 1200f, 1080, 4, inner, outer)
        assertNull(RadialMenuLogic.hit(l, 60f, 1210f)) // inside the dead zone
        val (bx, by) = at(l, PI) // straight back toward the edge
        assertNull(RadialMenuLogic.hit(l, bx, by))
    }

    @Test
    fun overshootPastTheRingStillCounts() {
        val l = RadialMenuLogic.layout(1030f, 1200f, 1080, 3, inner, outer)
        val (x, y) = at(l, l.angles[1], r = outer * 2)
        assertEquals(1, RadialMenuLogic.hit(l, x, y))
    }

    @Test
    fun angleDiffWrapsAroundPi() {
        assertTrue(abs(RadialMenuLogic.angleDiff(PI - 0.1, -PI + 0.1) + 0.2) < 1e-9)
        assertTrue(abs(RadialMenuLogic.angleDiff(0.1, 2 * PI - 0.1) - 0.2) < 1e-9)
    }

    @Test
    fun restingHeightKeepsTheFanOnScreen() {
        assertEquals(320, RadialMenuLogic.restY(0, 160, 2400, 400))
        assertEquals(1920, RadialMenuLogic.restY(2300, 160, 2400, 400))
        assertEquals(1000, RadialMenuLogic.restY(1000, 160, 2400, 400))
        // A screen too short for the fan falls back to staying on screen.
        assertEquals(0, RadialMenuLogic.restY(-10, 160, 600, 400))
    }
}
