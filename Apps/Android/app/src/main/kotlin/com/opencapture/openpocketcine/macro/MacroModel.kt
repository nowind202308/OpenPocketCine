package com.opencapture.openpocketcine.macro

import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow

/**
 * One camera write in a preset. Values use the camera's own wire codes (the same ones the
 * session's setters take), so a step is exactly one existing setter call.
 */
sealed interface MacroStep {
    /** Short label for the on-screen note when this step fails. */
    val label: String

    /** Hybrid zoom factor (Pocket 4 Pro: 1.0 main, 3.0 tele). The session hops D-Log2 → D-Log itself. */
    data class Zoom(val factor: Double) : MacroStep {
        override val label get() = "鏡頭 ${factor}×"
    }

    /** Color profile code (`CameraCommands.COLOR_*`). D-Log2 is accepted only at 1×. */
    data class ColorMode(val mode: Int) : MacroStep {
        override val label get() = "色彩模式"
    }

    /** Exposure mode code (`CameraCommands.EXPO_AUTO` / `EXPO_MANUAL`). */
    data class ExposureMode(val mode: Int) : MacroStep {
        override val label get() = "曝光模式"
    }

    /** Auto ISO ceiling as `IsoLimit.rawValue`. Only in Auto exposure, and not in D-Log2 (no Auto ISO there). */
    data class IsoCeiling(val raw: Int) : MacroStep {
        override val label get() = "ISO 上限"
    }

    /** Fixed ISO as a `CameraCommands` ISO index (e.g. 0x07 = 1600). Needs Manual exposure. */
    data class IsoFixed(val index: Int) : MacroStep {
        override val label get() = "ISO"
    }
}

/** A named, ordered list of writes bound to one key. Order matters: zoom before color, Auto before ISO ceiling. */
data class MacroPreset(val name: String, val steps: List<MacroStep>)

/** The camera facts a macro needs, decoupled from the session so the runner tests on the JVM. */
data class MacroCameraState(
    val live: Boolean = false,
    val recording: Boolean = false,
    val colorMode: Int = -1,
    val zoomFactor: Double? = null,
    val expoMode: Int = -1,
    val isoLimit: Int = -1,
    val isoIndex: Int = -1,
    /** Legal color codes the camera advertised; empty until it does. */
    val availableColorModes: List<Int> = emptyList(),
)

/** What the runner drives. The app implements it over the live session. */
interface MacroCamera {
    val state: StateFlow<MacroCameraState>

    /** Fire the one existing setter for [step]; confirmation arrives later through [state]. */
    fun send(step: MacroStep)

    /** Re-read the Auto ISO ceiling from the camera; the session sets it optimistically on write. */
    suspend fun refreshIsoCeiling(): Boolean = true
}

internal const val ZOOM_TOLERANCE = 0.15

/** `CameraCommands.COLOR_DLOG2`, duplicated so this file stays free of the OpenCine modules. */
internal const val COLOR_DLOG2_CODE = 0x41

/** True when the camera already reports what [step] asks for. */
internal fun MacroStep.isSatisfiedBy(s: MacroCameraState): Boolean = when (this) {
    is MacroStep.Zoom -> s.zoomFactor?.let { abs(it - factor) < ZOOM_TOLERANCE } ?: false
    is MacroStep.ColorMode -> s.colorMode == mode
    is MacroStep.ExposureMode -> s.expoMode == mode
    is MacroStep.IsoCeiling -> s.isoLimit == raw
    is MacroStep.IsoFixed -> s.isoIndex == index
}

/** Null when the camera can take [step]; otherwise why not, for the on-screen note. */
internal fun MacroStep.unsupportedReason(s: MacroCameraState): String? = when (this) {
    is MacroStep.ColorMode ->
        if (s.availableColorModes.isNotEmpty() && mode !in s.availableColorModes) "相機目前不提供此色彩模式" else null
    is MacroStep.IsoCeiling ->
        if (s.colorMode == COLOR_DLOG2_CODE) "D-Log2 沒有自動 ISO，請改用固定 ISO" else null
    else -> null
}
