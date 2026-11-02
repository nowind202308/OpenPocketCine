package com.opencapture.openpocketcine.macro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private val labels = FloatingMacroLogic.MenuLabels("縮放", "放大", "縮小", "解除", "設定")
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
        assertEquals(5 + 3, t.sectors().size) // 放大 / 縮小 / 解除 appear
    }

    @Test
    fun walkingOutToASpeedSelectsIt() {
        val t = tracker()
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 0), 180f)
        assertEquals(listOf(3, 0), t.path)
        assertEquals(5 + 3 + 7, t.sectors().size)
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
    fun disarmIsALeafBesideTheDirections() {
        val t = tracker()
        t.at(t.centerOf(0, 3), 100f)
        t.at(t.centerOf(1, 2), 180f)
        assertEquals(RadialAction.ZoomDisarm, t.hovered!!.action)
        assertEquals(5 + 3, t.sectors().size) // no ring 3 under 解除
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
        assertEquals(listOf("放大", "縮小", "解除"), zoom.children.map { it.label })
        assertFalse(zoom.children[2].enabled) // nothing armed, nothing to clear
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
    fun theArmedSpeedIsMarkedAndDisarmEnabled() {
        val arm = ZoomArm(zoomIn = false, level = 4)
        val zoom = FloatingMacroLogic.buildMenu(presets, live, null, labels, arm)[3]
        assertEquals("−4", zoom.badge)
        assertTrue(zoom.current && zoom.children[1].current && !zoom.children[0].current)
        assertEquals(listOf(4), zoom.children[1].children.filter { it.current }.map { it.badge.toInt() })
        assertTrue(zoom.children[2].enabled)
    }

    @Test
    fun buttonShowsArmedThenZooming() {
        val arm = ZoomArm(zoomIn = true, level = 2)
        assertEquals(FloatingTone.ARMED, FloatingMacroLogic.tone(live, null, null, zoomRunning = false, armed = arm))
        assertEquals(FloatingTone.ZOOMING, FloatingMacroLogic.tone(live, null, FloatingTone.DONE, zoomRunning = true, armed = arm))
        // A result flash still shows over an idle arm; offline wins over the arm.
        assertEquals(FloatingTone.DONE, FloatingMacroLogic.tone(live, null, FloatingTone.DONE, armed = arm))
        assertEquals(FloatingTone.OFFLINE, FloatingMacroLogic.tone(live.copy(live = false), null, null, armed = arm))
    }
}

private class FakeZoomPort(var factor: Double = 1.0, val max: Double = 4.0, var accept: Boolean = true) : ZoomPort {
    val rates = mutableListOf<Pair<Boolean, Int>>()
    var stops = 0
    override fun currentFactor() = factor
    override fun maxFactor() = max
    override fun rate(zoomIn: Boolean, level: Int): Boolean {
        rates += zoomIn to level
        return accept
    }
    override fun stopRate() { stops += 1 }
}

class ZoomDriverTest {
    private fun kotlinx.coroutines.test.TestScope.driver(port: FakeZoomPort) =
        ZoomDriver(port, backgroundScope, refreshMs = 100, now = { testScheduler.currentTime }, refusalLimitMs = 1_000)

    @Test
    fun armingDoesNotMoveTheLens() = runTest {
        val port = FakeZoomPort()
        val z = driver(port)
        z.arm(zoomIn = true, level = 7)
        delay(500)
        assertTrue(port.rates.isEmpty())
        assertFalse(z.running.value)
        assertEquals(ZoomArm(true, 7), z.armed.value)
    }

    @Test
    fun toggleHoldsTheNativeSpeedThenStopsAndTheArmStays() = runTest {
        val port = FakeZoomPort()
        val z = driver(port)
        z.arm(zoomIn = true, level = 4)
        assertTrue(z.toggle())
        assertTrue(z.running.value)
        delay(450)
        assertTrue(port.rates.size >= 4) // refreshed while running
        assertTrue(port.rates.all { it == (true to 4) })
        assertTrue(z.toggle())
        assertFalse(z.running.value)
        assertEquals(1, port.stops)
        assertEquals(ZoomArm(true, 4), z.armed.value) // ready for the next take
        val frozen = port.rates.size
        delay(500)
        assertEquals(frozen, port.rates.size)
    }

    @Test
    fun aRunStopsItselfAtTheEndOfTheRange() = runTest {
        val port = FakeZoomPort(factor = 3.0, max = 4.0)
        val z = driver(port)
        z.arm(zoomIn = true, level = 7)
        z.toggle()
        delay(250)
        assertTrue(z.running.value)
        port.factor = 4.0
        delay(250)
        assertFalse(z.running.value)
        assertEquals(1, port.stops)
    }

    @Test
    fun zoomingOutAtOneTimesStopsAtOnce() = runTest {
        val port = FakeZoomPort(factor = 1.0)
        val z = driver(port)
        z.arm(zoomIn = false, level = 2)
        z.toggle()
        delay(50)
        assertFalse(z.running.value)
        assertTrue(port.rates.isEmpty())
    }

    @Test
    fun aRunGivesUpWhenTheCameraKeepsRefusing() = runTest {
        val port = FakeZoomPort(accept = false)
        val z = driver(port)
        z.arm(zoomIn = true, level = 3)
        z.toggle()
        delay(600)
        assertTrue(z.running.value) // a D-Log2 hop may still clear
        delay(800)
        assertFalse(z.running.value)
        assertEquals(1, port.stops)
    }

    @Test
    fun toggleWithoutAnArmDoesNothing() = runTest {
        val port = FakeZoomPort()
        val z = driver(port)
        assertFalse(z.toggle())
        assertFalse(z.running.value)
        assertEquals(0, port.stops)
    }

    @Test
    fun disarmStopsARunAndClears() = runTest {
        val port = FakeZoomPort(factor = 2.0)
        val z = driver(port)
        z.arm(zoomIn = false, level = 3)
        z.toggle()
        z.disarm()
        assertFalse(z.running.value)
        assertNull(z.armed.value)
        assertEquals(1, port.stops)
    }
}
