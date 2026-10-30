package com.opencapture.openpocketcine.macro

import android.view.KeyEvent
import com.opencapture.opencine.vocabulary.CameraCommands
import com.opencapture.opencine.vocabulary.IsoLimit

/**
 * Presets and key bindings. Edit here to add a key: one [MacroPreset] plus one line in [bindings].
 *
 * Pocket 4 Pro facts these rely on (from the reverse-engineered protocol in OpenCine):
 * - D-Log2 exists only at 1×, so the main preset zooms to 1× before asking for D-Log2, and the
 *   tele preset uses D-Log (the session also hops D-Log2 → D-Log on its own when zooming).
 * - D-Log2 has no Auto ISO, so the main preset uses Manual exposure at a fixed ISO. 1600 is
 *   D-Log2's native base ISO; change [MAIN_ISO] to 800 or 3200 if you prefer.
 * - In Auto exposure only the ISO ceiling is settable; the floor is the camera's base ISO.
 */
object PocketPresets {
    private const val MAIN_ISO = 1600

    val main = MacroPreset(
        name = "主攝 D-Log2",
        steps = listOf(
            MacroStep.Zoom(1.0),
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG2),
            MacroStep.ExposureMode(CameraCommands.EXPO_MANUAL),
            MacroStep.IsoFixed(checkNotNull(CameraCommands.isoIndexFromValue(MAIN_ISO))),
        ),
    )

    val tele = MacroPreset(
        name = "長焦 D-Log",
        steps = listOf(
            MacroStep.Zoom(3.0),
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG),
            MacroStep.ExposureMode(CameraCommands.EXPO_AUTO),
            MacroStep.IsoCeiling(IsoLimit.Max6400.rawValue),
        ),
    )

    /** Number row, numpad and F-keys all work, so most macro keypads need no remapping. */
    val bindings: Map<Int, MacroPreset> = mapOf(
        KeyEvent.KEYCODE_1 to main,
        KeyEvent.KEYCODE_NUMPAD_1 to main,
        KeyEvent.KEYCODE_F1 to main,
        KeyEvent.KEYCODE_2 to tele,
        KeyEvent.KEYCODE_NUMPAD_2 to tele,
        KeyEvent.KEYCODE_F2 to tele,
    )
}
