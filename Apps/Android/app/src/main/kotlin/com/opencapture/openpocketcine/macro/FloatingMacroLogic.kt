package com.opencapture.openpocketcine.macro

import kotlin.math.max

/** What the floating button shows; the view maps each to a color and a glyph. */
enum class FloatingTone { OFFLINE, READY, RECORDING, RUNNING, DONE, FAILED }

/** How a finished run is felt: the tone to flash and a vibration waveform (off/on ms pairs). */
data class FloatingFeedback(val tone: FloatingTone, val vibration: List<Long>)

/**
 * Decisions behind the floating button, kept free of Android so they test on the JVM: what the
 * button shows, what the menu allows, how a result is felt, and where the windows sit.
 */
object FloatingMacroLogic {
    /** How long a done/failed color stays on the button. */
    const val FLASH_MS = 2_500L

    /** One short tick: applied. */
    val DONE_VIBRATION = listOf(0L, 40L)

    /** Three firm pulses: refused or stopped, so it is felt even with the phone in a pocket. */
    val FAILED_VIBRATION = listOf(0L, 90L, 90L, 90L, 90L, 90L)

    /** Why the menu cannot start a preset now; null when it can. Mirrors the runner's own checks. */
    fun blockReason(camera: MacroCameraState, running: String?): String? = when {
        !camera.live -> "相機未連線"
        camera.recording -> "錄影中無法切換設定"
        running != null -> "正在套用：$running"
        else -> null
    }

    /** A run in progress wins, then a fresh result flash, then the camera's own state. */
    fun tone(camera: MacroCameraState, running: String?, flash: FloatingTone?): FloatingTone = when {
        running != null -> FloatingTone.RUNNING
        flash != null -> flash
        !camera.live -> FloatingTone.OFFLINE
        camera.recording -> FloatingTone.RECORDING
        else -> FloatingTone.READY
    }

    fun feedback(result: MacroResult): FloatingFeedback = when (result) {
        is MacroResult.Done -> FloatingFeedback(FloatingTone.DONE, DONE_VIBRATION)
        is MacroResult.Refused, is MacroResult.Stopped -> FloatingFeedback(FloatingTone.FAILED, FAILED_VIBRATION)
    }

    /** True when the camera already matches every step: the menu marks that row as current. */
    fun isCurrent(preset: MacroPreset, camera: MacroCameraState): Boolean =
        camera.live && preset.steps.isNotEmpty() && preset.steps.all { it.isSatisfiedBy(camera) }

    /** A release within [slop] px of the press is a tap, anything further a drag. */
    fun isTap(dx: Float, dy: Float, slop: Float): Boolean = dx * dx + dy * dy <= slop * slop

    /** After a drag the button rests against the nearer side edge, out of the frame's middle. */
    fun snapX(x: Int, size: Int, screenWidth: Int): Int =
        if (x + size / 2 < screenWidth / 2) 0 else max(0, screenWidth - size)

    fun clampY(y: Int, size: Int, screenHeight: Int): Int = y.coerceIn(0, max(0, screenHeight - size))

    /** The menu opens below the button, or above it when it would run off the bottom. */
    fun menuY(buttonY: Int, buttonSize: Int, menuHeight: Int, screenHeight: Int, gap: Int): Int {
        val below = buttonY + buttonSize + gap
        if (below + menuHeight <= screenHeight) return below
        return max(0, buttonY - gap - menuHeight)
    }

    /** The menu hangs from the same side edge as the button. */
    fun menuX(buttonX: Int, buttonSize: Int, menuWidth: Int, screenWidth: Int): Int =
        if (buttonX + buttonSize / 2 < screenWidth / 2) 0 else max(0, screenWidth - menuWidth)

    /**
     * Start the service only with a live camera, so it is always started from the foreground
     * (Android refuses a foreground service started from the background).
     */
    fun shouldStart(enabled: Boolean, overlayGranted: Boolean, attached: Boolean, live: Boolean): Boolean =
        enabled && overlayGranted && attached && live

    /** A dropped link alone does not stop it: the button greys out and recovers with the camera. */
    fun shouldStop(enabled: Boolean, overlayGranted: Boolean, attached: Boolean): Boolean =
        !enabled || !overlayGranted || !attached
}
