package com.opencapture.openpocketcine.macro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private val labels = FloatingMacroLogic.MenuLabels("縮放", "放大", "縮小", "設定")
private val live = MacroCameraState(live = true, colorMode = 0x17, zoomFactor = 1.0, expoMode = 0x01)
private val presets = listOf(
    MacroMenuEntry("1", MacroPreset("A", listOf(MacroStep.ExposureMode(0x01)))),
    MacroMenuEntry("2", MacroPreset("B", listOf(MacroStep.ExposureMode(0x04)))),
    MacroMenuEntry("3", MacroPreset("C", listOf(MacroStep.ColorMode(0x41)))),
)

/** Ring geometry at density 1: ring 1 44–140, ring 2 146–214, ring 3 220–286, lock from 312. */
private fun tracker(facingLeftEdge: Boolean = true, camera: MacroCameraState = live): RadialTracker {
    val cx = if (facingLeftEdge) 30f else 1050f
    return RadialTracker(
        FloatingMacroLogic.buildMenu(presets, camera, null, labels),
        cx, 1200f, if (facingLeftEdge) 0.0 else PI, RadialTracker.ringRadii(1f), minArc = 52f,
    )
}

private fun RadialTracker.at(degrees: Double, r: Float) {
    val a = Math.toRadians(degrees)
    move(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
}

/** Centre angle (degrees) of the sector holding [node] on [ring]. */
private fun RadialTracker.centerOf(ring: Int, index: Int): Double =
    Math.toDegrees(sectors().filter { it.ring == ring }[index].center)

class RadialTrackerTest {
    @Test
    fun ringOneIsAFanOfPresetsZoomAndSettings() {
        val t = tracker()
        val ring = t.sectors()
        assertEquals(5, ring.size) // 3 presets, 縮放, 設定
        assertTrue(ring.all { abs(Math.toDegrees(it.center)) < 80 })
        t.at(t.centerOf(0, 3), 100f)
        assertEquals(listOf(3), t.path)
        assertTrue(t.hovered!!.isGroup)
        assertEquals(5 + 2, t.sectors().size) // 放大 / 縮小 appear
    }

    @Test
    fun walkingOutToASpeedSelectsIt() {
        val t = tracker()
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 0), 180f)
        assertEquals(listOf(3, 0), t.path)
        assertEquals(5 + 2 + 7, t.sectors().size)
        t.at(t.centerOf(2, 5), 250f)
        assertEquals(listOf(3, 0, 5), t.path)
        assertEquals(RadialAction.Zoom(zoomIn = true, level = 6), t.hovered!!.action)
    }

    @Test
    fun innerSelectionIsStickyWhileTheOuterRingFollowsTheAngle() {
        val t = tracker()
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 1), 180f) // 縮小
        val speeds = (0 until 7).map { t.centerOf(2, it) }
        // The speeds fan out wider than 縮放's own sector, yet ring 1 keeps 縮放.
        t.at(speeds.first(), 250f)
        assertEquals(listOf(3, 1, 0), t.path)
        t.at(speeds.last(), 250f)
        assertEquals(listOf(3, 1, 6), t.path)
        assertEquals(RadialAction.Zoom(zoomIn = false, level = 7), t.hovered!!.action)
    }

    @Test
    fun movingInwardDropsTheOuterRings() {
        val t = tracker()
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 0), 180f)
        t.at(t.centerOf(2, 2), 250f)
        t.at(t.centerOf(1, 0), 180f)
        assertEquals(listOf(3, 0), t.path)
        t.at(t.centerOf(0, 0), 100f)
        assertEquals(listOf(0), t.path)
        t.at(0.0, 20f) // the dead zone around the button cancels
        assertTrue(t.path.isEmpty())
        assertNull(t.hovered)
    }

    @Test
    fun aLeafOnRingOneStaysSelectedPastItsEdge() {
        val t = tracker()
        val a = t.centerOf(0, 0)
        t.at(a, 100f)
        t.at(a, 260f) // overshoot: a quick flick still means this preset
        assertEquals(listOf(0), t.path)
        assertIs<RadialAction.Preset>(t.hovered!!.action)
    }

    @Test
    fun aFastFlickThatSkipsRingOneStillPicksByAngle() {
        val t = tracker()
        t.at(t.centerOf(0, 3), 250f)
        assertEquals(3, t.path.first())
    }

    @Test
    fun lockZoneOnlyPastAZoomSpeed() {
        val t = tracker()
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 0), 180f)
        val speed = t.centerOf(2, 3)
        t.at(speed, 250f)
        assertFalse(t.inLockZone)
        t.at(speed, 330f)
        assertTrue(t.inLockZone)
        assertEquals(listOf(3, 0, 3), t.path) // still the same speed
        t.at(speed, 270f)
        assertFalse(t.inLockZone)
    }

    @Test
    fun rightEdgeMirrorsTheFan() {
        val t = tracker(facingLeftEdge = false)
        assertTrue(t.sectors().all { cos(it.center) < 0 })
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 1), 180f)
        t.at(t.centerOf(2, 0), 250f)
        assertEquals(RadialAction.Zoom(zoomIn = false, level = 1), t.hovered!!.action)
    }

    @Test
    fun childRingStaysInsideTheFan() {
        val t = tracker()
        t.at(t.centerOf(0, 4), 100f) // 設定 is a leaf: no ring 2
        assertEquals(5, t.sectors().size)
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 1), 180f)
        assertTrue(t.sectors().all { abs(Math.toDegrees(it.center)) <= 80.0 + 1e-6 })
    }

    @Test
    fun angleDiffWrapsAroundPi() {
        assertTrue(abs(RadialTracker.angleDiff(PI - 0.1, -PI + 0.1) + 0.2) < 1e-9)
        assertTrue(abs(RadialTracker.angleDiff(0.1, 2 * PI - 0.1) - 0.2) < 1e-9)
    }

    @Test
    fun restingHeightKeepsRingOneOnScreen() {
        assertEquals(320, RadialMenuLogic.restY(0, 160, 2400, 400))
        assertEquals(1920, RadialMenuLogic.restY(2300, 160, 2400, 400))
        assertEquals(1000, RadialMenuLogic.restY(1000, 160, 2400, 400))
        assertEquals(0, RadialMenuLogic.restY(-10, 160, 600, 400))
    }
}

class MenuBuildTest {
    @Test
    fun zoomWorksWhileRecordingButPresetsDoNot() {
        val nodes = FloatingMacroLogic.buildMenu(presets, live.copy(recording = true), null, labels)
        assertTrue(nodes.take(3).none { it.enabled })
        val zoom = nodes[3]
        assertTrue(zoom.enabled)
        assertEquals(listOf("放大", "縮小"), zoom.children.map { it.label })
        assertEquals((1..7).map { "$it" }, zoom.children[0].children.map { it.badge })
        assertTrue(nodes[4].action == RadialAction.Settings)
    }

    @Test
    fun offlineGreysZoomToo() {
        val nodes = FloatingMacroLogic.buildMenu(presets, live.copy(live = false), null, labels)
        assertFalse(nodes[3].enabled)
        assertFalse(nodes[3].children[0].children[0].enabled)
    }

    @Test
    fun aLatchedZoomShowsOnTheButton() {
        val latched = ZoomState(zoomIn = true, level = 2, latched = true)
        assertEquals(FloatingTone.ZOOMING, FloatingMacroLogic.tone(live, null, FloatingTone.DONE, latched))
        assertEquals(FloatingTone.READY, FloatingMacroLogic.tone(live, null, null, latched.copy(latched = false)))
    }
}

class ZoomSpeedsTest {
    @Test
    fun levelsDoubleAtTheirStatedPace() {
        assertTrue(abs(ZoomSpeeds.ratePerSecond(1) - ln(2.0) / 16) < 1e-12)
        val (m, end) = ZoomSpeeds.step(1.0, zoomIn = true, level = 7, dtSeconds = 1.0, anchor = 1.0, maxFactor = 12.0)
        assertTrue(abs(m - 2.0) < 1e-9)
        assertFalse(end)
        assertTrue(ZoomSpeeds.ratePerSecond(7) > ZoomSpeeds.ratePerSecond(6))
    }

    @Test
    fun stepsClampAtBothEnds() {
        // From 2×, max 4×: magnification tops out at 2.
        val (up, upEnd) = ZoomSpeeds.step(1.9, zoomIn = true, level = 7, dtSeconds = 1.0, anchor = 2.0, maxFactor = 4.0)
        assertEquals(2.0, up, 1e-9)
        assertTrue(upEnd)
        // Zooming out from 2× bottoms out at 1×, i.e. magnification 0.5.
        val (down, downEnd) = ZoomSpeeds.step(0.6, zoomIn = false, level = 7, dtSeconds = 1.0, anchor = 2.0, maxFactor = 4.0)
        assertEquals(0.5, down, 1e-9)
        assertTrue(downEnd)
    }
}

private class FakeZoomPort(var factor: Double = 1.0, val max: Double = 4.0) : ZoomPort {
    val updates = mutableListOf<Double>()
    var ends = 0
    override fun currentFactor() = factor
    override fun maxFactor() = max
    override fun update(magnification: Double) { updates += magnification }
    override fun end() { ends += 1 }
}

class ZoomDriverTest {
    @Test
    fun holdZoomsPauseFreezesStopEnds() = runTest {
        val port = FakeZoomPort()
        val z = ZoomDriver(port, backgroundScope, tickMs = 10, now = { testScheduler.currentTime })
        z.hold(zoomIn = true, level = 7)
        delay(300)
        assertTrue(port.updates.last() > 1.1)
        z.pause()
        assertNull(z.state.value)
        val frozen = port.updates.size
        delay(100)
        assertEquals(frozen, port.updates.size)
        z.stop()
        assertEquals(1, port.ends)
    }

    @Test
    fun aLatchedZoomStopsItselfAtTheEnd() = runTest {
        val port = FakeZoomPort(factor = 3.9, max = 4.0)
        val z = ZoomDriver(port, backgroundScope, tickMs = 10, now = { testScheduler.currentTime })
        z.hold(zoomIn = true, level = 7)
        z.latch()
        assertEquals(true, z.state.value?.latched)
        delay(400)
        assertNull(z.state.value)
        assertEquals(1, port.ends)
    }

    @Test
    fun latchWithoutAHeldSpeedJustStops() = runTest {
        val port = FakeZoomPort()
        val z = ZoomDriver(port, backgroundScope, tickMs = 10, now = { testScheduler.currentTime })
        z.hold(zoomIn = false, level = 3)
        z.pause()
        z.latch()
        assertNull(z.state.value)
        assertEquals(1, port.ends)
    }
}
