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

/** What the floating button shows while zoom runs. */
data class ZoomState(val zoomIn: Boolean, val level: Int, val latched: Boolean)

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
 * Drives continuous zoom from the radial menu.
 *
 * - [hold] while the finger rests on a speed sector (changing sector changes speed live);
 * - [pause] when it leaves for a non-speed sector: the move stays open but stops changing;
 * - [stop] on an ordinary release, which ends the move;
 * - [latch] on a release in the lock zone: it keeps zooming until [stop] or the end of the range.
 */
class ZoomDriver(
    private val port: ZoomPort,
    private val scope: CoroutineScope,
    private val tickMs: Long = 40,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ZoomState?>(null)

    /** Null when no move is open, or paused. */
    val state: StateFlow<ZoomState?> = _state.asStateFlow()

    private var job: Job? = null
    private var anchor = 1.0
    private var magnification = 1.0
    private var moving: ZoomState? = null
    private var open = false

    fun hold(zoomIn: Boolean, level: Int) {
        if (!open) begin()
        moving = ZoomState(zoomIn, level, latched = false)
        _state.value = moving
    }

    fun pause() {
        moving = null
        _state.value = null
    }

    fun latch() {
        val m = moving ?: return stop()
        moving = m.copy(latched = true)
        _state.value = moving
    }

    fun stop() {
        job?.cancel()
        job = null
        moving = null
        _state.value = null
        if (open) port.end()
        open = false
    }

    private fun begin() {
        open = true
        anchor = port.currentFactor().coerceAtLeast(1.0)
        magnification = 1.0
        var last = now()
        job = scope.launch {
            while (isActive) {
                delay(tickMs)
                val t = now()
                val dt = (t - last).coerceIn(0, 200) / 1000.0
                last = t
                val m = moving ?: continue
                val (next, atEnd) = ZoomSpeeds.step(magnification, m.zoomIn, m.level, dt, anchor, port.maxFactor())
                if (next != magnification) {
                    magnification = next
                    port.update(magnification)
                }
                // A held move rests at the end until released; a latched one is done.
                if (atEnd && m.latched) {
                    stop()
                    break
                }
            }
        }
    }
}
