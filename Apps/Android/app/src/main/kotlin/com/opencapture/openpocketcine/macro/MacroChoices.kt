package com.opencapture.openpocketcine.macro

import kotlin.math.abs

/** The ring-2 functions every preset offers. */
enum class MacroFunction { ISO, EV, FPS }

/**
 * What the operator picked for one preset in the floating menu; null keeps the preset's own
 * setting. [iso] is read by the preset's exposure mode: an Auto ISO ceiling (`IsoLimit.rawValue`)
 * for an Auto preset, a fixed ISO index for a Manual one.
 */
data class MacroChoices(val iso: Int? = null, val evThirds: Int? = null, val fpsIndex: Int? = null) {
    fun get(function: MacroFunction): Int? = when (function) {
        MacroFunction.ISO -> iso
        MacroFunction.EV -> evThirds
        MacroFunction.FPS -> fpsIndex
    }

    fun with(function: MacroFunction, value: Int?): MacroChoices = when (function) {
        MacroFunction.ISO -> copy(iso = value)
        MacroFunction.EV -> copy(evThirds = value)
        MacroFunction.FPS -> copy(fpsIndex = value)
    }

    val isEmpty: Boolean get() = iso == null && evThirds == null && fpsIndex == null
}

/** One option on ring 3: [value] null is 原設定 (forget the choice). */
data class MacroOption(val value: Int?, val badge: String, val enabled: Boolean = true)

/**
 * The option lists and how choices fold into a preset, Android-free. Wire codes are duplicated
 * from OpenCine's `CameraCommands` / `IsoLimit` / `VideoFrameRate` so this file tests alone.
 */
object MacroChoiceLogic {
    /** `CameraCommands.EXPO_MANUAL`. */
    const val EXPO_MANUAL = 0x04

    /** `IsoLimit` raw codes: 800, 1600, 3200, 6400. */
    val ISO_CEILINGS = listOf(0x04 to 800, 0x05 to 1600, 0x06 to 3200, 0x07 to 6400)

    /** `CameraCommands` ISO indices: 400, 800, 1600, 3200. */
    val ISO_FIXED = listOf(0x05 to 400, 0x06 to 800, 0x07 to 1600, 0x08 to 3200)

    /** EV in thirds: ±2, ±1, ±⅔, ±⅓, 0. The camera takes ±3. */
    val EV_THIRDS = listOf(-6, -3, -2, -1, 0, 1, 2, 3, 6)

    /** `VideoFrameRate` indices of the normal-video rates: 24, 25, 30, 48, 50, 60. */
    val FPS = listOf(0x01 to 24, 0x02 to 25, 0x03 to 30, 0x04 to 48, 0x05 to 50, 0x06 to 60)

    /** True when the preset ends in Manual exposure (fixed ISO, no EV). */
    fun isManual(preset: MacroPreset): Boolean =
        preset.steps.filterIsInstance<MacroStep.ExposureMode>().lastOrNull()?.mode == EXPO_MANUAL

    /** EV only acts in Auto exposure. */
    fun evApplies(preset: MacroPreset): Boolean = !isManual(preset)

    fun evLabel(thirds: Int): String {
        if (thirds == 0) return "0"
        val sign = if (thirds > 0) "+" else "−"
        val whole = abs(thirds) / 3
        val frac = when (abs(thirds) % 3) { 1 -> "⅓"; 2 -> "⅔"; else -> "" }
        return sign + (if (whole > 0) "$whole" else "") + frac
    }

    fun fpsLabel(index: Int): String = FPS.firstOrNull { it.first == index }?.second?.toString() ?: "?"

    fun isoLabel(preset: MacroPreset, value: Int): String =
        if (isManual(preset)) ISO_FIXED.firstOrNull { it.first == value }?.second?.toString() ?: "?"
        else "≤" + (ISO_CEILINGS.firstOrNull { it.first == value }?.second?.toString() ?: "?")

    /** What ring 2 shows under each function: the remembered value, or null for 原設定. */
    fun summary(preset: MacroPreset, choices: MacroChoices, function: MacroFunction): String? {
        val v = choices.get(function) ?: return null
        return when (function) {
            MacroFunction.ISO -> isoLabel(preset, v)
            MacroFunction.EV -> evLabel(v)
            MacroFunction.FPS -> fpsLabel(v) + "p"
        }
    }

    /**
     * Ring 3 for [function] under [preset], 原設定 first. ISO follows the preset's exposure mode
     * and never switches it. Values the camera has said it lacks are greyed out; until it says,
     * ISO stays open and frame rates stay closed (a FORMAT SET without the camera's table is refused).
     */
    fun options(preset: MacroPreset, function: MacroFunction, camera: MacroCameraState): List<MacroOption> {
        val reset = MacroOption(null, "↺")
        return listOf(reset) + when (function) {
            MacroFunction.ISO ->
                if (isManual(preset)) {
                    ISO_FIXED.map { (index, iso) ->
                        MacroOption(index, "$iso",
                            camera.availableIsoIndices.isEmpty() || index in camera.availableIsoIndices)
                    }
                } else {
                    ISO_CEILINGS.map { (raw, iso) -> MacroOption(raw, "≤$iso") }
                }
            MacroFunction.EV -> EV_THIRDS.map { MacroOption(it, evLabel(it)) }
            MacroFunction.FPS -> FPS.map { (index, fps) -> MacroOption(index, "$fps", index in camera.availableFps) }
        }
    }

    /**
     * [preset] with [choices] folded in. ISO replaces the preset's ISO step (or follows its
     * exposure step); frame rate goes before exposure, since a new rate can move the shutter;
     * EV goes last and only into an Auto preset.
     */
    fun apply(preset: MacroPreset, choices: MacroChoices): MacroPreset {
        if (choices.isEmpty) return preset
        val steps = preset.steps.toMutableList()
        choices.iso?.let { value ->
            val iso = if (isManual(preset)) MacroStep.IsoFixed(value) else MacroStep.IsoCeiling(value)
            val at = steps.indexOfFirst { it is MacroStep.IsoCeiling || it is MacroStep.IsoFixed }
            if (at >= 0) {
                steps[at] = iso
            } else {
                val expo = steps.indexOfLast { it is MacroStep.ExposureMode }
                steps.add(if (expo >= 0) expo + 1 else steps.size, iso)
            }
        }
        choices.fpsIndex?.let { index ->
            val expo = steps.indexOfFirst { it is MacroStep.ExposureMode }
            steps.add(if (expo >= 0) expo else steps.size, MacroStep.FrameRate(index))
        }
        choices.evThirds?.let { if (evApplies(preset)) steps += MacroStep.Ev(it) }
        return preset.copy(steps = steps)
    }
}
