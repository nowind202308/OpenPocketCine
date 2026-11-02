package com.opencapture.openpocketcine.macro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The camera side of continuous zoom: the camera's own held zoom, which ramps the lens itself at
 * one of seven native speeds, so every speed moves smoothly.
 */
interface ZoomPort {
    fun currentFactor(): Double

    fun maxFactor(): Double

    /**
     * Starts or refreshes a held zoom at [level] 1..7. False when the camera side holds the write
     * back for now (a D-Log2 body still hopping to D-Log, or the colour locked while recording).
     */
    fun rate(zoomIn: Boolean, level: Int): Boolean

    /** Stops the lens where it is (and lets a D-Log2 body restore its colour). */
    fun stopRate()
}

/** A zoom set up in advance: direction and speed 1..7. */
data class ZoomArm(val zoomIn: Boolean, val level: Int)

object ZoomSpeeds {
    /** The camera's native held-zoom speeds. */
    const val LEVELS = 7
}

/**
 * Zoom set up first, run on demand: the menu [arm]s a direction and speed without moving the
 * lens; a tap on the floating button or the keypad's zoom key [toggle]s the run. A run ends on
 * the next toggle, at the end of the range, after the camera keeps refusing it, or on [stop]; the
 * arm stays for the next take until [disarm].
 *
 * While running, the held command is refreshed every [refreshMs] so the camera keeps moving.
 */
class ZoomDriver(
    private val port: ZoomPort,
    private val scope: CoroutineScope,
    private val refreshMs: Long = 120,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long refusals may last (a D-Log2 hop takes a moment) before the run gives up. */
    private val refusalLimitMs: Long = 3_000,
) {
    private val _armed = MutableStateFlow<ZoomArm?>(null)
    private val _running = MutableStateFlow(false)

    /** What a toggle will run; null when nothing is set up. */
    val armed: StateFlow<ZoomArm?> = _armed.asStateFlow()

    /** The lens is moving. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    fun arm(zoomIn: Boolean, level: Int) {
        stop()
        _armed.value = ZoomArm(zoomIn, level.coerceIn(1, ZoomSpeeds.LEVELS))
    }

    fun disarm() {
        stop()
        _armed.value = null
    }

    /** Starts the armed zoom, or stops a running one. False when nothing is armed. */
    fun toggle(): Boolean {
        if (_running.value) {
            stop()
            return true
        }
        val arm = _armed.value ?: return false
        start(arm)
        return true
    }

    /** Ends a run where it is; the arm stays. */
    fun stop() {
        val wasRunning = job != null
        job?.cancel()
        job = null
        _running.value = false
        if (wasRunning) port.stopRate()
    }

    private fun atEnd(arm: ZoomArm): Boolean {
        val factor = port.currentFactor()
        return if (arm.zoomIn) factor >= port.maxFactor() - END_SLACK else factor <= 1.0 + END_SLACK
    }

    private fun start(arm: ZoomArm) {
        _running.value = true
        job = scope.launch {
            var refusedSince: Long? = null
            while (isActive) {
                if (atEnd(arm)) break
                if (port.rate(arm.zoomIn, arm.level)) {
                    refusedSince = null
                } else {
                    val since = refusedSince ?: now().also { refusedSince = it }
                    if (now() - since >= refusalLimitMs) break
                }
                delay(refreshMs)
            }
            if (isActive) stop()
        }
    }

    private companion object {
        /** The reported factor settles a little short of the ends. */
        const val END_SLACK = 0.02
    }
}
