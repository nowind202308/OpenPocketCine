package com.opencapture.openpocketcine.macro

import kotlin.math.max

/** What the floating button shows; the view maps each to a color and a glyph. */
enum class FloatingTone { OFFLINE, READY, RECORDING, RUNNING, ARMED, ZOOMING, DONE, FAILED }

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

    /**
     * A preset run wins, then a running zoom, then a fresh result flash, then an armed zoom
     * (only on a live camera), then the camera's own state.
     */
    fun tone(
        camera: MacroCameraState,
        running: String?,
        flash: FloatingTone?,
        zoomRunning: Boolean = false,
        armed: ZoomArm? = null,
    ): FloatingTone = when {
        running != null -> FloatingTone.RUNNING
        zoomRunning -> FloatingTone.ZOOMING
        flash != null -> flash
        !camera.live -> FloatingTone.OFFLINE
        armed != null -> FloatingTone.ARMED
        camera.recording -> FloatingTone.RECORDING
        else -> FloatingTone.READY
    }

    fun feedback(result: MacroResult): FloatingFeedback = when (result) {
        is MacroResult.Done -> FloatingFeedback(FloatingTone.DONE, DONE_VIBRATION)
        is MacroResult.Refused, is MacroResult.Stopped -> FloatingFeedback(FloatingTone.FAILED, FAILED_VIBRATION)
    }

    /** Words the menu needs from resources, so the tree builds on the JVM. */
    data class MenuLabels(
        val zoom: String,
        val zoomIn: String,
        val zoomOut: String,
        val disarm: String,
        val settings: String,
        val apply: String,
        val original: String,
        val fps: String,
    )

    /**
     * Ring 1: the camera's presets, then 縮放, then settings. Releasing on a preset runs it with
     * its remembered choices; its ring 2 is 套用, ISO, EV, 幀率, and each function's ring 3 lists
     * 原設定 then the values (the remembered one outlined). Releasing on a function cancels. 縮放 opens 放大 / 縮小 (each with
     * speeds 1..7, the armed one outlined) and 解除. Presets grey out while blocked (still sent,
     * so the runner says why); setting up zoom only needs a live camera, recording or not.
     */
    fun buildMenu(
        menu: List<MacroMenuEntry>,
        camera: MacroCameraState,
        running: String?,
        labels: MenuLabels,
        armed: ZoomArm? = null,
        choices: (MacroPreset) -> MacroChoices = { MacroChoices() },
    ): List<RadialNode> {
        val block = blockReason(camera, running)
        val presets = menu.map { entry ->
            val preset = entry.preset
            val picked = choices(preset)
            RadialNode(
                badge = entry.key,
                label = preset.name,
                action = RadialAction.Preset(preset),
                enabled = block == null,
                current = isCurrent(MacroChoiceLogic.apply(preset, picked), camera),
                children = listOf(
                    RadialNode(badge = "▶", label = labels.apply, action = RadialAction.Preset(preset),
                        enabled = block == null),
                ) + MacroFunction.entries.map { functionNode(preset, it, picked, camera, block == null, labels) },
            )
        }
        fun speeds(zoomIn: Boolean) = (1..ZoomSpeeds.LEVELS).map { level ->
            RadialNode(
                badge = "$level", label = "", action = RadialAction.Zoom(zoomIn, level),
                enabled = camera.live, current = armed == ZoomArm(zoomIn, level),
            )
        }
        val zoom = RadialNode(
            badge = armed?.let { zoomBadge(it) } ?: "±", label = labels.zoom, enabled = camera.live,
            current = armed != null,
            children = listOf(
                RadialNode(badge = "+", label = labels.zoomIn, enabled = camera.live, children = speeds(true),
                    current = armed?.zoomIn == true),
                RadialNode(badge = "−", label = labels.zoomOut, enabled = camera.live, children = speeds(false),
                    current = armed?.zoomIn == false),
                RadialNode(badge = "✕", label = labels.disarm, action = RadialAction.ZoomDisarm, enabled = armed != null),
            ),
        )
        return presets + zoom + RadialNode(badge = "⚙", label = labels.settings, action = RadialAction.Settings)
    }

    private fun functionNode(
        preset: MacroPreset,
        function: MacroFunction,
        picked: MacroChoices,
        camera: MacroCameraState,
        open: Boolean,
        labels: MenuLabels,
    ): RadialNode {
        val applies = function != MacroFunction.EV || MacroChoiceLogic.evApplies(preset)
        val enabled = open && applies
        val chosen = picked.get(function)
        return RadialNode(
            badge = when (function) {
                MacroFunction.ISO -> "ISO"
                MacroFunction.EV -> "EV"
                MacroFunction.FPS -> "fps"
            },
            label = MacroChoiceLogic.summary(preset, picked, function)
                ?: if (function == MacroFunction.FPS) labels.fps else labels.original,
            enabled = enabled,
            current = chosen != null,
            children = MacroChoiceLogic.options(preset, function, camera).map { option ->
                RadialNode(
                    badge = option.badge,
                    label = if (option.value == null) labels.original else "",
                    action = RadialAction.Choose(preset, function, option.value),
                    enabled = enabled && option.enabled,
                    current = chosen == option.value,
                )
            },
        )
    }

    /** "+3" / "−5": direction and speed, on the button and the 縮放 sector. */
    fun zoomBadge(arm: ZoomArm): String = (if (arm.zoomIn) "+" else "−") + arm.level

    /** True when the camera already matches every step: the menu marks that row as current. */
    fun isCurrent(preset: MacroPreset, camera: MacroCameraState): Boolean =
        camera.live && preset.steps.isNotEmpty() && preset.steps.all { it.isSatisfiedBy(camera) }

    /** A release within [slop] px of the press is a tap, anything further a drag. */
    fun isTap(dx: Float, dy: Float, slop: Float): Boolean = dx * dx + dy * dy <= slop * slop

    /** After a drag the button rests against the nearer side edge, out of the frame's middle. */
    fun snapX(x: Int, size: Int, screenWidth: Int): Int =
        if (x + size / 2 < screenWidth / 2) 0 else max(0, screenWidth - size)

    fun clampY(y: Int, size: Int, screenHeight: Int): Int = y.coerceIn(0, max(0, screenHeight - size))

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
