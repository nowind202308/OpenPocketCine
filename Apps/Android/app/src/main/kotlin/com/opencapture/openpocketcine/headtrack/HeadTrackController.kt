package com.opencapture.openpocketcine.headtrack

import android.os.SystemClock
import com.opencapture.opencine.vocabulary.ConnectionPhase
import android.content.Context
import com.opencapture.openpocketcine.AppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fork (feat/headtrack-f2): absolute-angle head tracking. While the camera is live and the head
 * tracker is connected and enabled, every send interval it steers the gimbal toward the head pose
 * relative to the last recenter, kept inside safe limits so the gimbal never reaches its stops.
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
            if (!connected) model.session.headTrackStop()
        }
    }
    private var loop: Job? = null
    private var lastSeq: Int? = null
    private var yaw0 = 0.0
    private var pitch0 = 0.0
    private var lastSpeed: Int? = null
    private var lastInterval: Int? = null
    @Volatile private var tickMs = INTERVAL_MS[DEFAULT_INTERVAL - 1]

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
    }

    private fun tick() {
        val pose = link.latest ?: return
        if (!pose.enabled || SystemClock.elapsedRealtime() - pose.atMs > STALE_MS) {
            lastSeq = null
            return
        }
        if (pose.recenterSeq != lastSeq) {
            lastSeq = pose.recenterSeq
            yaw0 = pose.yawDeg
            pitch0 = pose.pitchDeg
        }
        val speed = pose.speedLevel.takeIf { it in 1..SPEED_DEG_PER_S.size } ?: DEFAULT_SPEED
        if (speed != lastSpeed) {
            if (lastSpeed != null) {
                scope.launch { model.session.presentControlNote("頭追速度 $speed（${SPEED_DEG_PER_S[speed - 1].toInt()}°/s）") }
            }
            lastSpeed = speed
        }
        val interval = pose.intervalLevel.takeIf { it in 1..INTERVAL_MS.size } ?: DEFAULT_INTERVAL
        if (interval != lastInterval) {
            if (lastInterval != null) {
                scope.launch { model.session.presentControlNote("傳輸間隔 $interval（${INTERVAL_MS[interval - 1] / 1000.0} 秒）") }
            }
            lastInterval = interval
            tickMs = INTERVAL_MS[interval - 1]
        }
        val stepSeconds = tickMs / 1000.0
        val yaw = (pose.yawDeg - yaw0).coerceIn(PAN_MIN_DEG, PAN_MAX_DEG)
        val pitch = (pose.pitchDeg - pitch0).coerceIn(TILT_MIN_DEG, TILT_MAX_DEG)
        val maxStep = SPEED_DEG_PER_S[speed - 1] * stepSeconds
        model.session.headTrackToward(yaw, pitch, GAIN, stepSeconds, maxStep)
    }

    companion object {
        // Send interval per level (ms); each step lasts exactly one interval. The tracker picks the
        // level (four clicks cycle 1→4); firmware without levels reports 0 → DEFAULT_INTERVAL.
        val INTERVAL_MS = longArrayOf(100L, 200L, 300L, 500L)
        const val DEFAULT_INTERVAL = 2
        const val STALE_MS = 300L
        const val GAIN = 0.8

        // One step per tick and each step lasts exactly one tick: the next step is sent only after the
        // previous one has finished, so steps never overlap (overlapping 0.2 s steps every 0.1 s led
        // to the Pocket 3 locking out all app gimbal control, then a feed stall).

        // Speed cap per level (degrees per second of commanded motion). The tracker's button picks
        // the level (triple click cycles 1→4); firmware without levels reports 0 → DEFAULT_SPEED.
        val SPEED_DEG_PER_S = doubleArrayOf(45.0, 80.0, 120.0, 180.0)
        const val DEFAULT_SPEED = 3

        // Safe limits, inside what the Pocket 3 accepts in video mode (SET refuses past about −43°).
        const val PAN_MIN_DEG = -43.0
        const val PAN_MAX_DEG = 80.0
        const val TILT_MIN_DEG = -40.0
        const val TILT_MAX_DEG = 65.0
    }
}
