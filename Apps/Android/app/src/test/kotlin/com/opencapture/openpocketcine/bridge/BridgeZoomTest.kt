package com.opencapture.openpocketcine.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeZoomTest {
    private class Camera : BridgeZoomPort {
        var factor = 1.0
        var refusal: String? = null
        /** Position writes move the lens (false: a body that ignores them). */
        var obeysPosition = true
        val log = mutableListOf<String>()

        override fun factor() = factor
        override fun stops() = listOf(1.0, 3.0, 6.0, 12.0)
        override fun recording() = false
        override fun refusal(zoomIn: Boolean) = refusal
        override fun rate(zoomIn: Boolean, level: Int): Boolean {
            log += "rate ${if (zoomIn) "in" else "out"} $level"
            return true
        }
        override fun stopRate() { log += "stop" }
        override fun position(factor: Double) {
            log += "pos %.2f".format(factor)
            if (obeysPosition) this.factor = factor
        }
    }

    private val camera = Camera()
    private var clock = 0L
    private val zoom = BridgeZoom(camera) { clock }

    /** Runs ticks until idle or [untilMs]. */
    private fun run(untilMs: Long = Long.MAX_VALUE, each: () -> Unit = {}) {
        while (clock < untilMs) {
            val wait = zoom.tick() ?: return
            clock += wait
            each()
        }
    }

    @Test
    fun aHeldZoomStopsWhenM2StopsRepeatingIt() {
        zoom.hold(zoomIn = true, level = 4, limit = 0.0)
        run()
        assertEquals("rate in 4", camera.log.first())
        assertEquals("stop", camera.log.last())
        assertTrue(clock > BridgeZoom.DEADMAN_MS)
        assertFalse(zoom.moving)
    }

    @Test
    fun aRepeatedHoldKeepsRunningAndStopEndsIt() {
        zoom.hold(true, 2, 0.0)
        run(untilMs = 2_000) { zoom.hold(true, 2, 0.0) }
        assertTrue(zoom.moving)
        zoom.stop()
        assertEquals("stop", camera.log.last())
        assertEquals(1, camera.log.count { it == "stop" })
    }

    @Test
    fun aLimitedHoldStopsAtTheLimitAndLandsOnIt() {
        zoom.hold(true, 1, 2.95)
        run(untilMs = 5_000) {
            zoom.hold(true, 1, 2.95)
            if (camera.log.last().startsWith("rate")) camera.factor += 0.1
        }
        assertFalse(zoom.moving)
        assertEquals(listOf("stop", "pos 2.95"), camera.log.takeLast(2))
        assertEquals(2.95, camera.factor)
    }

    @Test
    fun aRefusedDirectionNeverStarts() {
        camera.refusal = "recording in D-Log2"
        zoom.hold(true, 3, 0.0)
        zoom.to(3.0, 2_000)
        assertNull(zoom.tick())
        assertTrue(camera.log.isEmpty())
    }

    @Test
    fun aRampReachesTheTargetInTheAskedTimeAtAnEvenRatio() {
        zoom.to(4.0, 2_000)
        var atHalf = 0.0
        run { if (clock == 1_000L) atHalf = camera.factor }
        assertEquals(4.0, camera.factor)
        assertTrue(clock in 2_000L..2_100L, "took $clock ms")
        // Half the time is half the doublings: 1× → 2× → 4×.
        assertEquals(2.0, atHalf, 0.1)
        assertFalse(camera.log.any { it.startsWith("rate") })
    }

    @Test
    fun zeroDurationGoesStraightThere() {
        zoom.to(6.0, 0)
        assertEquals(listOf("pos 6.00"), camera.log)
        assertFalse(zoom.moving)
    }

    @Test
    fun aTargetPastTheCeilingIsClamped() {
        zoom.to(40.0, 0)
        assertEquals(12.0, camera.factor)
    }

    @Test
    fun aRampFallsBackToHeldZoomWhenPositionWritesDoNotMoveTheLens() {
        camera.obeysPosition = false
        zoom.to(3.0, 3_000)
        run(untilMs = 20_000) { if (camera.log.last().startsWith("rate")) camera.factor += 0.05 }
        assertTrue(camera.log.any { it.startsWith("rate in") })
        assertEquals("stop", camera.log.last())
        assertFalse(zoom.moving)
        assertTrue(camera.factor > 2.5)
    }

    @Test
    fun fasterMovesPickHigherHeldLevels() {
        assertEquals(1, BridgeZoom.levelFor(1.0, 2.0, 10_000))
        assertEquals(7, BridgeZoom.levelFor(1.0, 12.0, 500))
        assertTrue(BridgeZoom.levelFor(1.0, 3.0, 1_000) > BridgeZoom.levelFor(1.0, 3.0, 5_000))
    }
}
