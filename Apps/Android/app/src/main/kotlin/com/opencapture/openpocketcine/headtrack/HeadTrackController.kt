package com.opencapture.openpocketcine.headtrack

import android.content.Context
import android.os.SystemClock
import com.opencapture.opencine.vocabulary.ConnectionPhase
import com.opencapture.openpocketcine.AppModel
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fork (feat/headtrack-f2): head tracking. While the camera is live and the head tracker is connected
 * and enabled, the gimbal follows the head pose relative to the last recenter, kept inside safe limits
 * so it never reaches its stops. The tracker picks one of three modes:
 *
 * 1. Angle: timed relative steps toward the target (Motion Control commands), one per send interval.
 *    Accurate, but the Pocket 3 locks out app gimbal control after a few minutes of these.
 * 2. Stick: joystick throw proportional to head turn rate. Never locks, but open loop: drifts.
 * 3. Hybrid (default): joystick channel, throw proportional to the gap between the target angle and
 *    the live gimbal angle predicted ~200 ms ahead (closed loop with latency compensation), with the
 *    throw slew-limited so it never reverses within one tick. Stick signs are fixed (+1 both axes,
 *    verified on the Pocket 3); the old auto sign probe misread oscillation as a reversed axis.
 *
 * A recenter (the tracker's button, or the first pose) makes the current head direction straight
 * ahead: the gimbal is driven to pan 0°, tilt 0°.
 */
class HeadTrackController(
    context: Context,
    private val model: AppModel,
    private val scope: CoroutineScope,
) {
    private val link = HeadTrackLink(context.applicationContext) { connected ->
        scope.launch {
            model.session.presentControlNote(if (connected) "頭追已連線" else "頭追已斷線")
            if (!connected) release()
        }
    }
    private var loop: Job? = null
    private var lastSeq: Int? = null
    private var yaw0 = 0.0
    private var pitch0 = 0.0
    private var lastSpeed: Int? = null
    private var lastInterval: Int? = null
    private var lastMode: Int? = null
    @Volatile private var tickMs = INTERVAL_MS[DEFAULT_INTERVAL - 1]

    // Joystick drive state.
    private var stickActive = false
    private var homing = false
    private var prevHeadYaw: Double? = null
    private var prevHeadPitch: Double? = null
    private var prevHeadAt = 0L
    // Hybrid damping state: last sent throw (for slew limiting) and the gimbal's estimated velocity.
    private var lastCx = 0.0
    private var lastCy = 0.0
    private var prevLive: Pair<Double, Double>? = null
    private var prevLiveAt = 0L
    private var velX = 0.0
    private var velY = 0.0

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            model.session.phaseFlow.collect { phase ->
                if (phase == ConnectionPhase.LIVE) link.start() else {
                    link.stop()
                    lastSeq = null
                }
            }
        }
        scope.launch {
            while (isActive) {
                tick()
                delay(tickMs)
            }
        }
    }

    fun close() {
        loop?.cancel()
        loop = null
        link.stop()
        release()
    }

    private fun release() {
        model.session.headTrackStop()
        if (stickActive) {
            stickActive = false
            model.session.headTrackStickEnd()
        }
        prevHeadYaw = null
        prevHeadPitch = null
        lastCx = 0.0
        lastCy = 0.0
        prevLive = null
        velX = 0.0
        velY = 0.0
    }

    private fun tick() {
        val pose = link.latest
        if (pose == null || !pose.enabled || SystemClock.elapsedRealtime() - pose.atMs > STALE_MS) {
            lastSeq = null
            if (stickActive) release()
            return
        }
        val mode = pose.modeLevel.takeIf { it in 1..MODE_NAMES.size } ?: DEFAULT_MODE
        if (mode != lastMode) {
            if (lastMode != null) {
                release()
                scope.launch { model.session.presentControlNote("頭追模式：${MODE_NAMES[mode - 1]}") }
            }
            model.session.headTrackNote("mode=$mode (${MODE_NAMES[mode - 1]})")
            lastMode = mode
            homing = mode == MODE_STICK
        }
        if (pose.recenterSeq != lastSeq) {
            lastSeq = pose.recenterSeq
            yaw0 = pose.yawDeg
            pitch0 = pose.pitchDeg
            homing = mode == MODE_STICK
        }
        val speed = pose.speedLevel.takeIf { it in 1..SPEED_DEG_PER_S.size } ?: DEFAULT_SPEED
        if (speed != lastSpeed) {
            if (lastSpeed != null) {
                scope.launch { model.session.presentControlNote("頭追速度 $speed（${SPEED_DEG_PER_S[speed - 1].toInt()}°/s）") }
            }
            model.session.headTrackNote("speed=$speed (${SPEED_DEG_PER_S[speed - 1].toInt()}°/s, raw=${pose.speedLevel})")
            lastSpeed = speed
        }
        val interval = pose.intervalLevel.takeIf { it in 1..INTERVAL_MS.size } ?: DEFAULT_INTERVAL
        if (interval != lastInterval) {
            if (lastInterval != null) {
                scope.launch { model.session.presentControlNote("傳輸間隔 $interval（${INTERVAL_MS[interval - 1] / 1000.0} 秒）") }
            }
            lastInterval = interval
        }
        val headYaw = pose.yawDeg - yaw0
        val headPitch = pose.pitchDeg - pitch0
        val yaw = headYaw.coerceIn(PAN_MIN_DEG, PAN_MAX_DEG)
        val pitch = headPitch.coerceIn(TILT_MIN_DEG, TILT_MAX_DEG)
        val speedDps = SPEED_DEG_PER_S[speed - 1]

        if (mode == MODE_ANGLE) {
            tickMs = INTERVAL_MS[interval - 1]
            val stepSeconds = tickMs / 1000.0
            model.session.headTrackToward(yaw, pitch, GAIN, stepSeconds, speedDps * stepSeconds)
            return
        }

        tickMs = STICK_TICK_MS
        val live = model.session.headTrackLivePose() ?: return
        val cap = (speedDps / STICK_FULL_DPS).coerceAtMost(1.0)
        val now = SystemClock.elapsedRealtime()
        // Estimate gimbal velocity from successive live poses (low-passed), used to predict where the
        // gimbal will be once the command's ~200 ms round trip has passed.
        val pl = prevLive
        val ldt = (now - prevLiveAt) / 1000.0
        if (pl != null && ldt in 0.02..0.5) {
            velX += VEL_ALPHA * ((live.first - pl.first) / ldt - velX)
            velY += VEL_ALPHA * ((live.second - pl.second) / ldt - velY)
        }
        prevLive = live
        prevLiveAt = now
        var cx: Double
        var cy: Double
        val tag: String
        if (mode == MODE_HYBRID || homing) {
            val tYaw = if (homing) 0.0 else yaw
            val tPitch = if (homing) 0.0 else pitch
            cx = closedLoop(tYaw - (live.first + velX * LATENCY_S), cap)
            cy = closedLoop(tPitch - (live.second + velY * LATENCY_S), cap)
            cx = slew(lastCx, cx)
            cy = slew(lastCy, cy)
            tag = if (homing) "home" else "hybrid"
            if (homing && abs(live.first) < 2.0 && abs(live.second) < 2.0) homing = false
        } else {
            // Pure stick: throw from head turn rate, no position feedback except the safe limits.
            val py = prevHeadYaw
            val pp = prevHeadPitch
            val dt = (now - prevHeadAt) / 1000.0
            val rateYaw = if (py != null && dt in 0.005..0.5) (headYaw - py) / dt else 0.0
            val ratePitch = if (pp != null && dt in 0.005..0.5) (headPitch - pp) / dt else 0.0
            cx = (rateYaw / STICK_FULL_DPS).coerceIn(-cap, cap)
            cy = (ratePitch / STICK_FULL_DPS).coerceIn(-cap, cap)
            if (abs(rateYaw) < STICK_RATE_DEADBAND_DPS) cx = 0.0
            if (abs(ratePitch) < STICK_RATE_DEADBAND_DPS) cy = 0.0
            if ((live.first >= PAN_MAX_DEG && cx > 0) || (live.first <= PAN_MIN_DEG && cx < 0)) cx = 0.0
            if ((live.second >= TILT_MAX_DEG && cy > 0) || (live.second <= TILT_MIN_DEG && cy < 0)) cy = 0.0
            tag = "stick rate=${fmt(rateYaw)},${fmt(ratePitch)}"
        }
        prevHeadYaw = headYaw
        prevHeadPitch = headPitch
        prevHeadAt = now

        lastCx = cx
        lastCy = cy
        if (cx == 0.0 && cy == 0.0) {
            if (stickActive) {
                stickActive = false
                model.session.headTrackStickEnd()
            }
            return
        }
        stickActive = true
        model.session.headTrackStick(
            cx,
            cy,
            "$tag want=${fmt(yaw)},${fmt(pitch)} live=${fmt(live.first)},${fmt(live.second)} " +
                "vel=${fmt(velX)},${fmt(velY)} throw=${fmt(cx)},${fmt(cy)} spd=$speed cap=${fmt(cap)}",
        )
    }

    private fun closedLoop(err: Double, cap: Double): Double {
        if (abs(err) < STICK_DEADBAND_DEG) return 0.0
        val t = (err * STICK_KP).coerceIn(-cap, cap)
        // A minimum throw only far from the target; near it, a forced push overshoots with the latency.
        return if (abs(t) < STICK_MIN_THROW && abs(err) >= STICK_MIN_THROW_ERR_DEG) STICK_MIN_THROW * sign(err) else t
    }

    /** Limits how far the throw moves per tick, so it ramps through zero instead of flipping. */
    private fun slew(prev: Double, want: Double): Double =
        want.coerceIn(prev - STICK_SLEW_PER_TICK, prev + STICK_SLEW_PER_TICK)

    private fun fmt(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)

    companion object {
        const val MODE_ANGLE = 1
        const val MODE_STICK = 2
        const val MODE_HYBRID = 3
        val MODE_NAMES = arrayOf("純角度", "純搖桿", "綜合")
        const val DEFAULT_MODE = MODE_HYBRID

        // Angle mode: send interval per level (ms); each step lasts exactly one interval. The tracker
        // picks the level (four clicks cycle 1→4); firmware without levels reports 0 → default.
        val INTERVAL_MS = longArrayOf(100L, 200L, 300L, 500L)
        const val DEFAULT_INTERVAL = 2
        const val GAIN = 0.8

        // Joystick modes.
        const val STICK_TICK_MS = 40L
        const val STICK_FULL_DPS = 120.0       // assumed gimbal speed at full linear throw
        const val STICK_KP = 1.0 / 45.0        // full throw at 45° gap (was 25°: oscillated)
        const val STICK_DEADBAND_DEG = 1.5
        const val STICK_MIN_THROW = 0.05
        const val STICK_MIN_THROW_ERR_DEG = 4.0
        const val STICK_SLEW_PER_TICK = 0.08   // 0 → full throw takes ~0.5 s at 40 ms ticks
        const val LATENCY_S = 0.2              // command → live pose round trip, assumed
        const val VEL_ALPHA = 0.3              // low-pass on the gimbal velocity estimate
        const val STICK_RATE_DEADBAND_DPS = 3.0

        const val STALE_MS = 300L

        // Speed cap per level (degrees per second). The tracker's button picks the level (triple
        // click cycles 1→4); firmware without levels reports 0 → DEFAULT_SPEED.
        val SPEED_DEG_PER_S = doubleArrayOf(45.0, 80.0, 120.0, 180.0)
        const val DEFAULT_SPEED = 3

        // Safe limits, inside what the Pocket 3 accepts in video mode (SET refuses past about −43°).
        const val PAN_MIN_DEG = -43.0
        const val PAN_MAX_DEG = 80.0
        const val TILT_MIN_DEG = -40.0
        const val TILT_MAX_DEG = 65.0
    }
}
