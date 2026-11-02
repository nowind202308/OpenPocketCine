package com.opencapture.openpocketcine.macro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

private const val AUTO = 0x01
private const val MANUAL = 0x04

private val autoPreset = MacroPreset(
    "D-Log M ISO≤1600",
    listOf(MacroStep.ColorMode(0x17), MacroStep.ExposureMode(AUTO), MacroStep.IsoCeiling(0x05)),
)
private val manualPreset = MacroPreset(
    "固定 ISO",
    listOf(MacroStep.Zoom(1.0), MacroStep.ExposureMode(MANUAL), MacroStep.IsoFixed(0x07)),
)
private val plainAuto = MacroPreset("一般", listOf(MacroStep.ColorMode(0x3F), MacroStep.ExposureMode(AUTO)))

private val camera = MacroCameraState(live = true, availableFps = listOf(0x01, 0x03, 0x06))

private val menuLabels = FloatingMacroLogic.MenuLabels("縮放", "放大", "縮小", "解除", "設定", "套用", "原設定", "幀率")

class MacroChoiceLogicTest {
    @Test
    fun noChoicesLeavesThePresetAlone() {
        assertSame(autoPreset, MacroChoiceLogic.apply(autoPreset, MacroChoices()))
    }

    @Test
    fun isoReplacesTheCeilingInAnAutoPreset() {
        val out = MacroChoiceLogic.apply(autoPreset, MacroChoices(iso = 0x06))
        assertEquals(MacroStep.IsoCeiling(0x06), out.steps.last())
        assertEquals(3, out.steps.size)
    }

    @Test
    fun isoIsAFixedValueInAManualPreset() {
        val out = MacroChoiceLogic.apply(manualPreset, MacroChoices(iso = 0x08))
        assertEquals(MacroStep.IsoFixed(0x08), out.steps.last())
    }

    @Test
    fun isoFollowsExposureWhenThePresetHadNone() {
        val out = MacroChoiceLogic.apply(plainAuto, MacroChoices(iso = 0x04))
        assertEquals(listOf<MacroStep>(MacroStep.ColorMode(0x3F), MacroStep.ExposureMode(AUTO), MacroStep.IsoCeiling(0x04)), out.steps)
    }

    @Test
    fun frameRateGoesBeforeExposureAndEvLast() {
        val out = MacroChoiceLogic.apply(autoPreset, MacroChoices(evThirds = -2, fpsIndex = 0x01))
        assertEquals(
            listOf(
                MacroStep.ColorMode(0x17), MacroStep.FrameRate(0x01), MacroStep.ExposureMode(AUTO),
                MacroStep.IsoCeiling(0x05), MacroStep.Ev(-2),
            ),
            out.steps,
        )
    }

    @Test
    fun evIsDroppedFromAManualPreset() {
        val out = MacroChoiceLogic.apply(manualPreset, MacroChoices(evThirds = 3))
        assertTrue(out.steps.none { it is MacroStep.Ev })
        assertFalse(MacroChoiceLogic.evApplies(manualPreset))
    }

    @Test
    fun isoOptionsFollowTheExposureMode() {
        val auto = MacroChoiceLogic.options(autoPreset, MacroFunction.ISO, camera).map { it.badge }
        assertEquals(listOf("↺", "≤800", "≤1600", "≤3200", "≤6400"), auto)
        val manual = MacroChoiceLogic.options(manualPreset, MacroFunction.ISO, camera).map { it.badge }
        assertEquals(listOf("↺", "400", "800", "1600", "3200"), manual)
    }

    @Test
    fun frameRatesTheCameraLacksAreGreyed() {
        val fps = MacroChoiceLogic.options(autoPreset, MacroFunction.FPS, camera)
        assertEquals(listOf("↺", "24", "25", "30", "48", "50", "60"), fps.map { it.badge })
        assertEquals(listOf("↺", "24", "30", "60"), fps.filter { it.enabled }.map { it.badge })
    }

    @Test
    fun evLabels() {
        assertEquals(
            listOf("−2", "−1", "−⅔", "−⅓", "0", "+⅓", "+⅔", "+1", "+2"),
            MacroChoiceLogic.EV_THIRDS.map { MacroChoiceLogic.evLabel(it) },
        )
        assertEquals("+1⅓", MacroChoiceLogic.evLabel(4))
    }

    @Test
    fun newStepsCheckAgainstTheCamera() {
        val s = MacroCameraState(live = true, evComp = 0x10 - 2, fpsIndex = 0x03, availableFps = listOf(0x03))
        assertTrue(MacroStep.Ev(-2).isSatisfiedBy(s))
        assertFalse(MacroStep.Ev(0).isSatisfiedBy(s))
        assertTrue(MacroStep.FrameRate(0x03).isSatisfiedBy(s))
        assertEquals("相機目前不提供此幀率", MacroStep.FrameRate(0x06).unsupportedReason(s))
    }
}

class ChoiceMenuTest {
    private val menu = listOf(MacroMenuEntry("1", autoPreset), MacroMenuEntry("2", manualPreset))

    @Test
    fun aPresetRunsOnRingOneAndOpensItsFunctions() {
        val node = FloatingMacroLogic.buildMenu(menu, camera, null, menuLabels)[0]
        assertIs<RadialAction.Preset>(node.action)
        assertEquals(listOf("▶", "ISO", "EV", "fps"), node.children.map { it.badge })
        assertIs<RadialAction.Preset>(node.children[0].action)
        // Functions themselves do nothing: releasing there cancels.
        assertTrue(node.children.drop(1).all { it.action == null && it.isGroup })
    }

    @Test
    fun theRememberedChoiceIsShownAndOutlined() {
        val picked = MacroChoices(iso = 0x06)
        val node = FloatingMacroLogic.buildMenu(menu, camera, null, menuLabels, choices = { if (it === autoPreset) picked else MacroChoices() })[0]
        val iso = node.children[1]
        assertEquals("≤3200", iso.label)
        assertTrue(iso.current)
        assertEquals(listOf("≤3200"), iso.children.filter { it.current }.map { it.badge })
        val ev = node.children[2]
        assertEquals("原設定", ev.label)
        assertEquals(listOf("↺"), ev.children.filter { it.current }.map { it.badge })
        val choose = iso.children[1].action
        assertEquals(RadialAction.Choose(autoPreset, MacroFunction.ISO, 0x04), choose)
    }

    @Test
    fun evIsGreyedForAManualPreset() {
        val node = FloatingMacroLogic.buildMenu(menu, camera, null, menuLabels)[1]
        assertFalse(node.children[2].enabled)
        assertTrue(node.children[2].children.none { it.enabled })
        assertTrue(node.children[1].enabled)
    }

    @Test
    fun recordingGreysEveryOption() {
        val node = FloatingMacroLogic.buildMenu(menu, camera.copy(recording = true), null, menuLabels)[0]
        assertTrue(node.children.none { it.enabled })
    }

    @Test
    fun choosingRemembersThenRuns() {
        var stored = MacroChoices(evThirds = 1)
        val ran = mutableListOf<MacroPreset>()
        val attachment = MacroAttachment(
            menu = { menu },
            camera = MutableStateFlow(camera),
            running = MutableStateFlow(null),
            choices = { stored },
            remember = { _, c -> stored = c },
            start = { ran += it; true },
        )
        assertTrue(attachment.choose(autoPreset, MacroFunction.FPS, 0x03))
        assertEquals(MacroChoices(evThirds = 1, fpsIndex = 0x03), stored)
        attachment.choose(autoPreset, MacroFunction.EV, null)
        assertEquals(MacroChoices(fpsIndex = 0x03), stored)
        assertEquals(listOf(autoPreset, autoPreset), ran)
    }
}
