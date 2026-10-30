package com.opencapture.openpocketcine.macro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

private const val DLOG = 0x17
private const val DLOG2 = 0x41
private const val AUTO = 0x01
private const val MANUAL = 0x04

/** Camera stand-in: applies each write after the camera "reports" it, like the real session. */
private class FakeCamera(initial: MacroCameraState) : MacroCamera {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<MacroCameraState> = flow
    val sent = mutableListOf<MacroStep>()
    /** Steps the camera silently ignores (never reports back). */
    var ignore: (MacroStep) -> Boolean = { false }
    /** Optional side effect after a write lands, e.g. recording starts. */
    var after: (MacroStep) -> Unit = {}

    override fun send(step: MacroStep) {
        sent += step
        if (ignore(step)) return
        val s = flow.value
        flow.value = when (step) {
            // Like the session: leaving 1× from D-Log2 hops color to D-Log first.
            is MacroStep.Zoom -> s.copy(
                zoomFactor = step.factor,
                colorMode = if (step.factor > 1.0 && s.colorMode == DLOG2) DLOG else s.colorMode,
            )
            is MacroStep.ColorMode -> s.copy(colorMode = step.mode)
            is MacroStep.ExposureMode -> s.copy(expoMode = step.mode)
            is MacroStep.IsoCeiling -> s.copy(isoLimit = step.raw)
            is MacroStep.IsoFixed -> s.copy(isoIndex = step.index)
        }
        after(step)
    }
}

private val main = MacroPreset(
    "主攝",
    listOf(MacroStep.Zoom(1.0), MacroStep.ColorMode(DLOG2), MacroStep.ExposureMode(MANUAL), MacroStep.IsoFixed(0x07)),
)
private val tele = MacroPreset(
    "長焦",
    listOf(MacroStep.Zoom(3.0), MacroStep.ColorMode(DLOG), MacroStep.ExposureMode(AUTO), MacroStep.IsoCeiling(0x07)),
)
private val liveMain = MacroCameraState(
    live = true, colorMode = DLOG2, zoomFactor = 1.0, expoMode = MANUAL, isoIndex = 0x07,
    availableColorModes = listOf(DLOG, DLOG2),
)

class MacroRunnerTest {
    private fun runner(cam: FakeCamera, scope: kotlinx.coroutines.CoroutineScope) =
        MacroRunner(cam, scope, onResult = {}, stepTimeoutMs = 1_000, zoomTimeoutMs = 1_000, settleMs = 10)

    @Test
    fun teleAppliesEveryWriteInOrder() = runTest {
        val cam = FakeCamera(liveMain)
        val result = runner(cam, this).execute(tele)
        assertIs<MacroResult.Done>(result)
        // Color is already D-Log after the zoom hop, so it is skipped rather than resent.
        assertEquals(listOf(MacroStep.Zoom(3.0), MacroStep.ExposureMode(AUTO), MacroStep.IsoCeiling(0x07)), cam.sent)
        assertEquals(DLOG, cam.flow.value.colorMode)
    }

    @Test
    fun alreadyMatchingPresetSendsNothing() = runTest {
        val cam = FakeCamera(liveMain)
        assertIs<MacroResult.Done>(runner(cam, this).execute(main))
        assertTrue(cam.sent.isEmpty())
    }

    @Test
    fun refusesWhileRecordingOrDisconnected() = runTest {
        val recording = FakeCamera(liveMain.copy(recording = true))
        assertIs<MacroResult.Refused>(runner(recording, this).execute(tele))
        assertTrue(recording.sent.isEmpty())
        val offline = FakeCamera(liveMain.copy(live = false))
        assertIs<MacroResult.Refused>(runner(offline, this).execute(tele))
    }

    @Test
    fun stopsWhenCameraNeverConfirms() = runTest {
        val cam = FakeCamera(liveMain).apply { ignore = { it is MacroStep.ExposureMode } }
        val result = runner(cam, this).execute(tele)
        assertEquals(MacroStep.ExposureMode(AUTO), assertIs<MacroResult.Stopped>(result).step)
        // Nothing after the failed step is sent.
        assertFalse(cam.sent.any { it is MacroStep.IsoCeiling })
    }

    @Test
    fun stopsIfRecordingStartsMidRun() = runTest {
        val cam = FakeCamera(liveMain)
        cam.after = { if (it is MacroStep.Zoom) cam.flow.value = cam.flow.value.copy(recording = true) }
        assertIs<MacroResult.Stopped>(runner(cam, this).execute(tele))
        assertEquals(1, cam.sent.size)
    }

    @Test
    fun isoCeilingRefusedInDLog2() = runTest {
        val bad = MacroPreset("錯誤", listOf(MacroStep.IsoCeiling(0x06)))
        val cam = FakeCamera(liveMain)
        val result = runner(cam, this).execute(bad)
        assertIs<MacroResult.Stopped>(result)
        assertTrue(cam.sent.isEmpty())
    }

    @Test
    fun unsupportedColorStopsBeforeSending() = runTest {
        val cam = FakeCamera(liveMain.copy(availableColorModes = listOf(DLOG)))
        val result = runner(cam, this).execute(MacroPreset("x", listOf(MacroStep.ColorMode(DLOG2 + 1))))
        assertIs<MacroResult.Stopped>(result)
        assertTrue(cam.sent.isEmpty())
    }

    @Test
    fun cameraRollbackAfterLastStepIsReported() = runTest {
        val cam = FakeCamera(liveMain)
        val background = backgroundScope
        // Optimistic ISO ceiling that the camera reverts shortly after, as the session's pin does.
        cam.after = {
            if (it is MacroStep.IsoCeiling) background.launch {
                delay(20)
                cam.flow.value = cam.flow.value.copy(isoLimit = 0x06)
            }
        }
        val result = MacroRunner(cam, this, {}, 1_000, 1_000, settleMs = 50).execute(tele)
        assertEquals(MacroStep.IsoCeiling(0x07), assertIs<MacroResult.Stopped>(result).step)
    }
}

class MacroKeypadTest {
    private val preset = MacroPreset("p", emptyList())

    @Test
    fun firesOncePerPressAndConsumesBoundKeysOnly() {
        val fired = mutableListOf<MacroPreset>()
        val keypad = MacroKeypad(mapOf(8 to preset), isActive = { true }, run = { fired += it })
        assertTrue(keypad.onKey(8, MacroKeypad.ACTION_DOWN, 0))
        assertTrue(keypad.onKey(8, MacroKeypad.ACTION_DOWN, 1)) // auto-repeat: consumed, not refired
        assertTrue(keypad.onKey(8, 1, 0)) // release consumed
        assertFalse(keypad.onKey(9, MacroKeypad.ACTION_DOWN, 0))
        assertEquals(1, fired.size)
    }

    @Test
    fun passesKeysThroughWhenNotLive() {
        val fired = mutableListOf<MacroPreset>()
        val keypad = MacroKeypad(mapOf(8 to preset), isActive = { false }, run = { fired += it })
        assertFalse(keypad.onKey(8, MacroKeypad.ACTION_DOWN, 0))
        assertTrue(fired.isEmpty())
    }
}
