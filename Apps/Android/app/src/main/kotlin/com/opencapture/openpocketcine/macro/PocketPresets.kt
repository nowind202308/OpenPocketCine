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
 * - In Auto exposure only the ISO ceiling is settable; the floor is the camera's base ISO.
 * - Newer 4P firmware gives D-Log2 Auto ISO with a 400 floor and an 800 / 1600 / 3200 ceiling.
 *   The ceiling is assumed to use the same `IsoLimit` codes as other color modes; that is not
 *   yet confirmed against a capture. If the camera refuses it, use [mainFixedIso] instead.
 */
object PocketPresets {
    val main = MacroPreset(
        name = "主攝 D-Log2",
        steps = listOf(
            MacroStep.Zoom(1.0),
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG2),
            MacroStep.ExposureMode(CameraCommands.EXPO_AUTO),
            MacroStep.IsoCeiling(IsoLimit.Max3200.rawValue),
        ),
    )

    /** Fallback for firmware without D-Log2 Auto ISO: Manual at D-Log2's native ISO 1600. */
    val mainFixedIso = MacroPreset(
        name = "主攝 D-Log2 固定 ISO",
        steps = listOf(
            MacroStep.Zoom(1.0),
            MacroStep.ColorMode(CameraCommands.COLOR_DLOG2),
            MacroStep.ExposureMode(CameraCommands.EXPO_MANUAL),
            MacroStep.IsoFixed(checkNotNull(CameraCommands.isoIndexFromValue(1600))),
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

    /** The floating menu's rows, top to bottom, each with the keypad key that runs it. */
    val menu: List<MacroMenuEntry> = listOf(
        MacroMenuEntry("1", main),
        MacroMenuEntry("2", tele),
        MacroMenuEntry("3", mainFixedIso),
    )

    /** Number row, numpad and F-keys all work, so most macro keypads need no remapping. */
    val bindings: Map<Int, MacroPreset> = mapOf(
        KeyEvent.KEYCODE_1 to main,
        KeyEvent.KEYCODE_NUMPAD_1 to main,
        KeyEvent.KEYCODE_F1 to main,
        KeyEvent.KEYCODE_2 to tele,
        KeyEvent.KEYCODE_NUMPAD_2 to tele,
        KeyEvent.KEYCODE_F2 to tele,
        KeyEvent.KEYCODE_3 to mainFixedIso,
        KeyEvent.KEYCODE_NUMPAD_3 to mainFixedIso,
        KeyEvent.KEYCODE_F3 to mainFixedIso,
    )
}
