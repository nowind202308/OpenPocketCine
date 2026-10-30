package com.opencapture.openpocketcine.macro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How a run ended; also what the on-screen note says. */
sealed interface MacroResult {
    data class Done(val preset: String) : MacroResult
    data class Refused(val reason: String) : MacroResult
    data class Stopped(val preset: String, val step: MacroStep, val reason: String) : MacroResult
}

fun MacroResult.note(): String = when (this) {
    is MacroResult.Done -> "$preset 已套用"
    is MacroResult.Refused -> reason
    is MacroResult.Stopped -> "$preset：${step.label}$reason，已停止"
}

/**
 * Runs a preset one write at a time. Each step is sent only after the previous one is reported
 * back by the camera; a step the camera already matches is skipped. A run never starts while
 * recording (the camera locks color and mode then) and stops if recording starts or the link
 * drops mid-run. After the last step it waits [settleMs] and re-checks every step, because the
 * session applies some values optimistically and rolls them back if the camera refuses.
 */
class MacroRunner(
    private val camera: MacroCamera,
    private val scope: CoroutineScope,
    private val onResult: (MacroResult) -> Unit,
    private val stepTimeoutMs: Long = 4_000,
    private val zoomTimeoutMs: Long = 8_000,
    private val settleMs: Long = 1_500,
) {
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts [preset]; false (with a note) when another run is still going. */
    fun run(preset: MacroPreset): Boolean {
        if (isRunning) {
            onResult(MacroResult.Refused("上一組設定仍在套用中"))
            return false
        }
        job = scope.launch { onResult(execute(preset)) }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    internal suspend fun execute(preset: MacroPreset): MacroResult {
        val start = camera.state.value
        if (!start.live) return MacroResult.Refused("相機未連線")
        if (start.recording) return MacroResult.Refused("錄影中無法切換設定")

        for (step in preset.steps) {
            val now = camera.state.value
            if (step.isSatisfiedBy(now)) continue
            step.unsupportedReason(now)?.let { return MacroResult.Stopped(preset.name, step, "（$it）") }

            camera.send(step)
            val timeout = if (step is MacroStep.Zoom) zoomTimeoutMs else stepTimeoutMs
            val reached = withTimeoutOrNull(timeout) {
                camera.state.first { step.isSatisfiedBy(it) || it.recording || !it.live }
            } ?: return MacroResult.Stopped(preset.name, step, "未獲相機確認")
            if (!reached.live) return MacroResult.Stopped(preset.name, step, "時連線中斷")
            if (reached.recording) return MacroResult.Stopped(preset.name, step, "時開始錄影")

            // Best effort: a sent GET lets a refusal surface before the settle check below.
            // Not sending one (D-Log2 on the current OpenCine pin) leaves only that check.
            if (step is MacroStep.IsoCeiling) camera.refreshIsoCeiling()
        }

        delay(settleMs)
        val settled = camera.state.value
        preset.steps.firstOrNull { !it.isSatisfiedBy(settled) }?.let {
            return MacroResult.Stopped(preset.name, it, "被相機退回")
        }
        return MacroResult.Done(preset.name)
    }
}
