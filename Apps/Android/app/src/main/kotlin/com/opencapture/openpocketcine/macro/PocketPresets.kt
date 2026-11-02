package com.opencapture.openpocketcine.macro

import android.view.KeyEvent
import com.opencapture.opencine.dji.looksLikePocket3
import com.opencapture.opencine.vocabulary.CameraCommands
import com.opencapture.opencine.vocabulary.CameraModel
import com.opencapture.opencine.vocabulary.IsoLimit

/**
 * One camera's presets, in floating-menu order. Keys "1".."9" also bind the keypad: number row,
 * numpad and F-keys all work, so most macro keypads need no remapping.
 */
class PresetSet(val title: String, val menu: List<MacroMenuEntry>) {
    val bindings: Map<Int, MacroPreset> = buildMap {
        menu.forEach { entry ->
            val n = entry.key.toIntOrNull()?.takeIf { it in 1..9 } ?: return@forEach
            put(KeyEvent.KEYCODE_1 + n - 1, entry.preset)
            put(KeyEvent.KEYCODE_NUMPAD_1 + n - 1, entry.preset)
            put(KeyEvent.KEYCODE_F1 + n - 1, entry.preset)
        }
    }
}

/**
 * Presets per camera, picked by the connected body's name. Edit here to add a key.
 *
 * Pocket 4 Pro facts these rely on (from the reverse-engineered protocol in OpenCine):
 * - D-Log2 exists only at 1×, so the main preset zooms to 1× before asking for D-Log2, and the
 *   tele preset uses D-Log (the session also hops D-Log2 → D-Log on its own when zooming).
 * - In Auto exposure only the ISO ceiling is settable; the floor is the camera's base ISO.
 * - Newer 4P firmware gives D-Log2 Auto ISO with a 400 floor and an 800 / 1600 / 3200 ceiling.
 *   The ceiling is assumed to use the same `IsoLimit` codes as other color modes; that is not
 *   yet confirmed against a capture. If the camera refuses it, use key 3 instead.
 *
 * Pocket 3: one lens and Normal / HLG / D-Log M (OpenCine translates the color bytes for it).
 * Its Auto ISO floor is 50, so only the ceiling changes. No zoom step: its zoom is digital and
 * not part of what these presets are for.
 */
object PocketPresets {
    private val p4pMain = MacroPreset(
        name = "主攝 D-Log2",
        steps = listOf(
            MacroStep.Zoom(1.0),
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG2),
            MacroStep.ExposureMode(CameraCommands.EXPO_AUTO),
            MacroStep.IsoCeiling(IsoLimit.Max3200.rawValue),
        ),
    )

    /** Fallback for firmware without D-Log2 Auto ISO: Manual at D-Log2's native ISO 1600. */
    private val p4pMainFixedIso = MacroPreset(
        name = "主攝 D-Log2 固定 ISO",
        steps = listOf(
            MacroStep.Zoom(1.0),
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG2),
            MacroStep.ExposureMode(CameraCommands.EXPO_MANUAL),
            MacroStep.IsoFixed(checkNotNull(CameraCommands.isoIndexFromValue(1600))),
        ),
    )

    private val p4pTele = MacroPreset(
        name = "長焦 D-Log",
        steps = listOf(
            MacroStep.Zoom(3.0),
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG),
            MacroStep.ExposureMode(CameraCommands.EXPO_AUTO),
            MacroStep.IsoCeiling(IsoLimit.Max6400.rawValue),
        ),
    )

    val pocket4Pro = PresetSet(
        "Pocket 4 Pro",
        listOf(MacroMenuEntry("1", p4pMain), MacroMenuEntry("2", p4pTele), MacroMenuEntry("3", p4pMainFixedIso)),
    )

    private val p3LogLow = MacroPreset(
        name = "D-Log M ISO≤1600",
        steps = listOf(
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG_M),
            MacroStep.ExposureMode(CameraCommands.EXPO_AUTO),
            MacroStep.IsoCeiling(IsoLimit.Max1600.rawValue),
        ),
    )

    private val p3LogHigh = MacroPreset(
        name = "D-Log M ISO≤6400",
        steps = listOf(
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG_M),
            MacroStep.ExposureMode(CameraCommands.EXPO_AUTO),
            MacroStep.IsoCeiling(IsoLimit.Max6400.rawValue),
        ),
    )

    private val p3Normal = MacroPreset(
        name = "一般色彩 自動",
        steps = listOf(
            MacroStep.ColorMode(CameraCommands.COLOR_NORMAL),
            MacroStep.ExposureMode(CameraCommands.EXPO_AUTO),
        ),
    )

    val pocket3 = PresetSet(
        "Pocket 3",
        listOf(MacroMenuEntry("1", p3LogLow), MacroMenuEntry("2", p3LogHigh), MacroMenuEntry("3", p3Normal)),
    )

    /** Every set, for the settings page. */
    val all: List<PresetSet> = listOf(pocket4Pro, pocket3)

    /** The set for a connected body; Pocket 4 Pro when the name says nothing else. */
    fun forCamera(name: String?): PresetSet =
        if (name != null && CameraModel.looksLikePocket3(name)) pocket3 else pocket4Pro
}
