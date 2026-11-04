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
 * tracker is connected and enabled, every [TICK_MS] it steers the gimbal toward the head pose
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
                delay(TICK_MS)
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
        val yaw = (pose.yawDeg - yaw0).coerceIn(PAN_MIN_DEG, PAN_MAX_DEG)
        val pitch = (pose.pitchDeg - pitch0).coerceIn(TILT_MIN_DEG, TILT_MAX_DEG)
        model.session.headTrackToward(yaw, pitch, GAIN, STEP_SECONDS)
    }

    companion object {
        const val TICK_MS = 100L
        const val STALE_MS = 300L
        const val GAIN = 0.6
        const val STEP_SECONDS = 0.2

        // Safe limits, inside what the Pocket 3 accepts in video mode (SET refuses past about −43°).
        const val PAN_MIN_DEG = -43.0
        const val PAN_MAX_DEG = 80.0
        const val TILT_MIN_DEG = -40.0
        const val TILT_MAX_DEG = 65.0
    }
}
