package com.opencapture.openpocketcine.bridge

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** The camera side of M2's zoom controls, lent by the activity. */
interface BridgeZoomPort {
    /** Reported zoom factor (1.0 when unknown). */
    fun factor(): Double

    /** Zoom stops of the current format, ascending; the last is the ceiling. */
    fun stops(): List<Double>

    fun recording(): Boolean

    /** Why a zoom in that direction would be refused now, or null when it can run. */
    fun refusal(zoomIn: Boolean): String?

    /** Starts or refreshes the camera's held zoom at [level] 1..7; false while held back. */
    fun rate(zoomIn: Boolean, level: Int): Boolean

    fun stopRate()

    /** Position write: the lens goes straight to [factor]. */
    fun position(factor: Double)
}

/**
 * Zoom moves M2 asks for through [StoryboardSurfaceService]. Two kinds:
 *
 * - **hold**: the camera's own held zoom at a native speed 1..7. M2 repeats the request while the
 *   key is down; a hold that is not repeated within [DEADMAN_MS] stops by itself, so a lost
 *   release cannot leave the lens running. An optional limit stops the hold at a factor (M2 uses
 *   it to pause at the lens switch while recording) and lands there with one position write.
 * - **ramp**: position writes along a path from the current factor to a target over a duration,
 *   even in perceived speed (constant ratio per second). When the reported factor has not moved
 *   after [FALLBACK_AFTER_MS], the ramp falls back to a held zoom toward the target.
 *
 * Pure: the owner calls [tick] and waits the returned delay; null means idle.
 */
class BridgeZoom(val port: BridgeZoomPort, private val now: () -> Long) {
    private sealed interface Move

    private data class Hold(
        val zoomIn: Boolean, val level: Int, val limit: Double?,
        /** Asked for by M2's key (needs refreshing, lands on the limit) rather than a ramp's fallback. */
        val keyed: Boolean,
        var refreshedAt: Long, var refusedSince: Long? = null,
    ) : Move

    private data class Ramp(
        val from: Double, val to: Double, val startedAt: Long, val durationMs: Long,
        var checked: Boolean = false,
    ) : Move

    private var move: Move? = null

    val moving: Boolean get() = move != null

    /** Starts or refreshes a held zoom. [limit] ≤ 0 means none. */
    fun hold(zoomIn: Boolean, level: Int, limit: Double) {
        val speed = level.coerceIn(1, 7)
        val stopAt = limit.takeIf { it > 0.0 }
        val current = move
        if (current is Hold && current.keyed && current.zoomIn == zoomIn && current.level == speed && current.limit == stopAt) {
            current.refreshedAt = now()
            return
        }
        if (port.refusal(zoomIn) != null) return
        if (current != null) stop()
        move = Hold(zoomIn, speed, stopAt, keyed = true, refreshedAt = now())
    }

    /** Moves to [factor] over [durationMs]; 0 goes there at once (M2 tracking a drag). */
    fun to(factor: Double, durationMs: Long) {
        val target = factor.coerceIn(1.0, ceiling())
        val from = port.factor()
        if (port.refusal(target > from) != null) return
        if (move is Hold) port.stopRate()
        if (durationMs <= 0 || abs(target - from) < 0.01) {
            move = null
            port.position(target)
            return
        }
        move = Ramp(from, target, now(), durationMs)
    }

    fun stop() {
        val current = move ?: return
        move = null
        if (current is Hold) port.stopRate()
    }

    /** One step of the running move. Returns the delay to the next tick, or null when idle. */
    fun tick(): Long? = when (val current = move) {
        null -> null
        is Hold -> tickHold(current)
        is Ramp -> tickRamp(current)
    }

    private fun ceiling(): Double = port.stops().lastOrNull() ?: 1.0

    private fun tickHold(hold: Hold): Long? {
        val t = now()
        val factor = port.factor()
        if (hold.keyed && t - hold.refreshedAt > DEADMAN_MS) return end()
        val atEnd = if (hold.zoomIn) factor >= ceiling() - END_SLACK else factor <= 1.0 + END_SLACK
        if (atEnd) return end()
        val limit = hold.limit
        if (limit != null) {
            // Stop early by a margin that grows with the speed, then land on the limit exactly.
            val margin = LIMIT_MARGIN * hold.level
            val reached = if (hold.zoomIn) factor >= limit - margin else factor <= limit + margin
            if (reached) {
                end()
                if (hold.keyed) port.position(limit)
                return null
            }
        }
        if (port.rate(hold.zoomIn, hold.level)) {
            hold.refusedSince = null
        } else {
            val since = hold.refusedSince ?: t.also { hold.refusedSince = it }
            if (t - since >= REFUSAL_LIMIT_MS) return end()
        }
        return HOLD_REFRESH_MS
    }

    private fun tickRamp(ramp: Ramp): Long? {
        val elapsed = now() - ramp.startedAt
        if (!ramp.checked && elapsed >= FALLBACK_AFTER_MS) {
            ramp.checked = true
            val travelled = abs(port.factor() - ramp.from)
            if (travelled < abs(ramp.to - ramp.from) * STALLED_SHARE) return fallBack(ramp, elapsed)
        }
        if (elapsed >= ramp.durationMs) {
            port.position(ramp.to)
            if (ramp.checked) {
                move = null
                return null
            }
            // A short ramp ends before the stall check: wait for it.
            return (FALLBACK_AFTER_MS - elapsed).coerceAtLeast(1)
        }
        port.position(rampFactor(ramp.from, ramp.to, elapsed.toDouble() / ramp.durationMs))
        return RAMP_STEP_MS
    }

    /** Position writes did not move the lens: run the camera's held zoom to the target instead. */
    private fun fallBack(ramp: Ramp, elapsed: Long): Long? {
        val left = if (elapsed >= ramp.durationMs) ramp.durationMs else ramp.durationMs - elapsed
        val zoomIn = ramp.to > ramp.from
        move = Hold(zoomIn, levelFor(ramp.from, ramp.to, left), ramp.to, keyed = false, refreshedAt = now())
        return tick()
    }

    private fun end(): Long? {
        stop()
        return null
    }

    companion object {
        const val DEADMAN_MS = 700L
        const val HOLD_REFRESH_MS = 120L
        /** The session coalesces position writes to one per 50 ms. */
        const val RAMP_STEP_MS = 50L
        const val FALLBACK_AFTER_MS = 1_000L
        const val REFUSAL_LIMIT_MS = 3_000L
        private const val STALLED_SHARE = 0.02
        private const val END_SLACK = 0.02
        private const val LIMIT_MARGIN = 0.03

        /** Even perceived speed: the same ratio every second. */
        fun rampFactor(from: Double, to: Double, t: Double): Double =
            from * (to / from).pow(t.coerceIn(0.0, 1.0))

        /**
         * Held-zoom level for a fallback move, from the doublings per second it needs. The native
         * speeds are not measured yet: this spreads the seven levels over 0.1…2.5 doublings/s.
         */
        fun levelFor(from: Double, to: Double, leftMs: Long): Int {
            val doublings = abs(ln(to / from)) / ln(2.0)
            val perSecond = doublings / (leftMs / 1000.0)
            return LEVEL_CEILINGS.indexOfFirst { perSecond <= it }.let { if (it < 0) 7 else it + 1 }
        }

        private val LEVEL_CEILINGS = listOf(0.1, 0.2, 0.4, 0.7, 1.1, 1.7)
    }
}
