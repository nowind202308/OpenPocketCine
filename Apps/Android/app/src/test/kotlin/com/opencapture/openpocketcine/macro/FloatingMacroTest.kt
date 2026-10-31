package com.opencapture.openpocketcine.macro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

private const val DLOG = 0x17
private const val AUTO = 0x01

private val ready = MacroCameraState(live = true, colorMode = DLOG, zoomFactor = 3.0, expoMode = AUTO, isoLimit = 0x07)
private val tele = MacroPreset(
    "長焦",
    listOf(MacroStep.Zoom(3.0), MacroStep.ColorMode(DLOG), MacroStep.ExposureMode(AUTO), MacroStep.IsoCeiling(0x07)),
)

class FloatingMacroLogicTest {
    @Test
    fun toneRunningWinsThenFlashThenCamera() {
        assertEquals(FloatingTone.RUNNING, FloatingMacroLogic.tone(ready, "長焦", FloatingTone.FAILED))
        assertEquals(FloatingTone.FAILED, FloatingMacroLogic.tone(ready, null, FloatingTone.FAILED))
        assertEquals(FloatingTone.READY, FloatingMacroLogic.tone(ready, null, null))
        assertEquals(FloatingTone.OFFLINE, FloatingMacroLogic.tone(ready.copy(live = false), null, null))
        assertEquals(FloatingTone.RECORDING, FloatingMacroLogic.tone(ready.copy(recording = true), null, null))
    }

    @Test
    fun blockReasonMirrorsRunnerChecks() {
        assertNull(FloatingMacroLogic.blockReason(ready, null))
        assertEquals("相機未連線", FloatingMacroLogic.blockReason(ready.copy(live = false), null))
        assertEquals("錄影中無法切換設定", FloatingMacroLogic.blockReason(ready.copy(recording = true), null))
        assertEquals("正在套用：長焦", FloatingMacroLogic.blockReason(ready, "長焦"))
    }

    @Test
    fun failuresVibrateDifferentlyFromSuccess() {
        val done = FloatingMacroLogic.feedback(MacroResult.Done("長焦"))
        val stopped = FloatingMacroLogic.feedback(MacroResult.Stopped("長焦", MacroStep.Zoom(3.0), "未獲相機確認"))
        val refused = FloatingMacroLogic.feedback(MacroResult.Refused("錄影中無法切換設定"))
        assertEquals(FloatingTone.DONE, done.tone)
        assertEquals(FloatingTone.FAILED, stopped.tone)
        assertEquals(FloatingTone.FAILED, refused.tone)
        assertTrue(stopped.vibration.size > done.vibration.size)
    }

    @Test
    fun currentPresetNeedsEveryStepAndALiveCamera() {
        assertTrue(FloatingMacroLogic.isCurrent(tele, ready))
        assertFalse(FloatingMacroLogic.isCurrent(tele, ready.copy(zoomFactor = 1.0)))
        assertFalse(FloatingMacroLogic.isCurrent(tele, ready.copy(live = false)))
        assertFalse(FloatingMacroLogic.isCurrent(MacroPreset("空", emptyList()), ready))
    }

    @Test
    fun buttonSnapsToNearerEdgeAndStaysOnScreen() {
        assertEquals(0, FloatingMacroLogic.snapX(300, 160, 1080))
        assertEquals(920, FloatingMacroLogic.snapX(700, 160, 1080))
        assertEquals(0, FloatingMacroLogic.clampY(-50, 160, 2400))
        assertEquals(2240, FloatingMacroLogic.clampY(2390, 160, 2400))
        assertTrue(FloatingMacroLogic.isTap(3f, 4f, 10f))
        assertFalse(FloatingMacroLogic.isTap(30f, 0f, 10f))
    }

    @Test
    fun serviceStartsOnlyLiveButSurvivesADroppedLink() {
        assertTrue(FloatingMacroLogic.shouldStart(enabled = true, overlayGranted = true, attached = true, live = true))
        assertFalse(FloatingMacroLogic.shouldStart(enabled = true, overlayGranted = true, attached = true, live = false))
        assertFalse(FloatingMacroLogic.shouldStart(enabled = true, overlayGranted = false, attached = true, live = true))
        assertFalse(FloatingMacroLogic.shouldStop(enabled = true, overlayGranted = true, attached = true))
        assertTrue(FloatingMacroLogic.shouldStop(enabled = false, overlayGranted = true, attached = true))
        assertTrue(FloatingMacroLogic.shouldStop(enabled = true, overlayGranted = true, attached = false))
    }
}

class MacroLinkTest {
    private fun attachment(): MacroAttachment =
        MacroAttachment(emptyList(), MutableStateFlow(ready), MutableStateFlow(null)) { true }

    @Test
    fun onlyTheCurrentOwnerDetaches() {
        val link = MacroLink()
        val old = attachment()
        val new = attachment()
        link.attach(old)
        link.attach(new)
        link.detach(old) // a destroyed predecessor must not cut off the recreated activity
        assertSame(new, link.attachment.value)
        link.detach(new)
        assertNull(link.attachment.value)
    }
}

private class InstantCamera(initial: MacroCameraState) : MacroCamera {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<MacroCameraState> = flow
    override fun send(step: MacroStep) {
        val s = flow.value
        flow.value = when (step) {
            is MacroStep.Zoom -> s.copy(zoomFactor = step.factor)
            is MacroStep.ColorMode -> s.copy(colorMode = step.mode)
            is MacroStep.ExposureMode -> s.copy(expoMode = step.mode)
            is MacroStep.IsoCeiling -> s.copy(isoLimit = step.raw)
            is MacroStep.IsoFixed -> s.copy(isoIndex = step.index)
        }
    }
}

class MacroRunnerRunningStateTest {
    @Test
    fun runningIsClearedBeforeTheResultIsReported() = runTest {
        val cam = InstantCamera(ready.copy(zoomFactor = 1.0))
        val seenWhileReporting = mutableListOf<String?>()
        lateinit var runner: MacroRunner
        runner = MacroRunner(cam, this, onResult = { seenWhileReporting += runner.running.value },
            stepTimeoutMs = 1_000, zoomTimeoutMs = 1_000, settleMs = 10)
        assertTrue(runner.run(tele))
        assertEquals("長焦", runner.running.value)
        assertFalse(runner.run(tele)) // a second trigger while busy is refused
        testScheduler.advanceUntilIdle()
        assertNull(runner.running.value)
        // The busy refusal reports while still running; the finished run reports after clearing.
        assertEquals(listOf<String?>("長焦", null), seenWhileReporting)
    }
}
