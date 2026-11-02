package com.opencapture.openpocketcine.macro

import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The camera side of continuous zoom: OpenCine's pinch path, which the app's two-finger zoom uses. */
interface ZoomPort {
    /** Zoom factor when a move starts (the pinch anchor). */
    fun currentFactor(): Double

    fun maxFactor(): Double

    /** Magnification relative to the anchor; the session clamps and paces the writes. */
    fun update(magnification: Double)

    /** Ends the move where it is (and lets a D-Log2 body restore its colour). */
    fun end()
}

/** A zoom set up in advance: direction and speed 1..7. */
data class ZoomArm(val zoomIn: Boolean, val level: Int)

/**
 * Speeds and the zoom step, Android-free. Level n takes [SECONDS_PER_DOUBLING]`[n-1]` seconds to
 * double (or halve) the zoom factor, so a level feels the same at 1× as at 6×.
 */
object ZoomSpeeds {
    val SECONDS_PER_DOUBLING = listOf(16.0, 10.0, 6.0, 4.0, 2.5, 1.5, 1.0)
    const val LEVELS = 7

    fun ratePerSecond(level: Int): Double = ln(2.0) / SECONDS_PER_DOUBLING[level.coerceIn(1, LEVELS) - 1]

    /**
     * The next magnification after [dtSeconds], kept between 1× and [maxFactor] for a move that
     * started at [anchor]. Returns the clamped value and whether it hit the end it moves toward.
     */
    fun step(
        magnification: Double,
        zoomIn: Boolean,
        level: Int,
        dtSeconds: Double,
        anchor: Double,
        maxFactor: Double,
    ): Pair<Double, Boolean> {
        val sign = if (zoomIn) 1.0 else -1.0
        val next = magnification * exp(sign * ratePerSecond(level) * dtSeconds)
        val lo = 1.0 / anchor.coerceAtLeast(1.0)
        val hi = maxFactor.coerceAtLeast(1.0) / anchor.coerceAtLeast(1.0)
        val clamped = next.coerceIn(lo, maxOf(lo, hi))
        val atEnd = if (zoomIn) clamped >= hi - 1e-6 else clamped <= lo + 1e-6
        return clamped to atEnd
    }
}

/**
 * Zoom set up first, run on demand: the menu [arm]s a direction and speed without moving the
 * lens; a tap on the floating button or the keypad's zoom key [toggle]s the run. A run ends on
 * the next toggle, at the end of the range, or on [stop]; the arm stays for the next take until
 * [disarm].
 */
class ZoomDriver(
    private val port: ZoomPort,
    private val scope: CoroutineScope,
    private val tickMs: Long = 40,
    private val now: () -> Long = System::currentTimeMillis,
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
        if (wasRunning) port.end()
    }

    private fun start(arm: ZoomArm) {
        val anchor = port.currentFactor().coerceAtLeast(1.0)
        var magnification = 1.0
        var last = now()
        _running.value = true
        job = scope.launch {
            while (isActive) {
                delay(tickMs)
                val t = now()
                val dt = (t - last).coerceIn(0, 200) / 1000.0
                last = t
                val (next, atEnd) = ZoomSpeeds.step(magnification, arm.zoomIn, arm.level, dt, anchor, port.maxFactor())
                if (next != magnification) {
                    magnification = next
                    port.update(magnification)
                }
                if (atEnd) {
                    stop()
                    break
                }
            }
        }
    }
}
