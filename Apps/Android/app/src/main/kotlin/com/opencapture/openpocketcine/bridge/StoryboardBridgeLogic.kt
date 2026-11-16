package com.opencapture.openpocketcine.bridge

import com.opencapture.openpocketcine.macro.MacroCameraState

/**
 * The storyboard field app (M2, package `tw.nowind.sbcards`) logs takes from this app's camera
 * state. This is the pure half of that bridge: it turns camera-state changes into event messages
 * and decides what an incoming command does. [StoryboardBridge] carries the messages over Android
 * broadcasts. Contract: `docs/storyboard-bridge.md` (wire version [VERSION]).
 *
 * Messages are maps of JSON-safe values. New fields may be added without a version bump; a
 * receiver ignores fields it does not know.
 */
class StoryboardBridgeLogic(
    /** Wall clock, ms since epoch: what M2 records as the phone time. */
    private val wallMs: () -> Long,
    /** Monotonic clock, ms: immune to clock changes, for measuring durations and latency. */
    private val monoMs: () -> Long,
) {
    private var lastLive: Boolean? = null
    private var lastRecording: Boolean? = null
    private var recordingSinceMono: Long? = null
    private var seq = 0L

    /** Messages for this camera state; empty when nothing M2 cares about changed. */
    fun onCamera(c: MacroCameraState): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        if (c.live != lastLive) {
            out += message("link", c, "connected" to c.live)
        }
        val wasRecording = lastRecording
        if (c.live && c.recording != wasRecording) {
            if (c.recording) {
                recordingSinceMono = monoMs()
                // First sight of a recording already running: M2 must not treat it as a fresh start.
                out += message("rec_start", c, "joined_late" to (wasRecording == null))
            } else if (wasRecording == true) {
                val since = recordingSinceMono
                out += message("rec_stop", c, "duration_ms" to since?.let { monoMs() - it })
                recordingSinceMono = null
            }
        }
        if (!c.live && lastRecording == true) {
            // The link dropped mid-take: the stop is unknown, M2 marks the end for correction.
            out += message("rec_lost", c)
            recordingSinceMono = null
        }
        lastLive = c.live
        lastRecording = if (c.live) c.recording else null
        return out
    }

    /** What an incoming command asks for, given the camera now. */
    fun onCommand(command: Map<String, Any?>, c: MacroCameraState): CommandDecision {
        val id = command["id"]?.toString()
        fun reply(ok: Boolean, reason: String, press: Boolean = false) =
            CommandDecision(press, message("cmd_result", c, "cmd_id" to id, "cmd" to command["cmd"], "ok" to ok, "reason" to reason))
        val version = (command["v"] as? Number)?.toInt()
        if (version != VERSION) return reply(false, "version")
        return when (command["cmd"]) {
            "ping" -> reply(true, "pong")
            "rec_start" -> when {
                !c.live -> reply(false, "not_live")
                c.recording -> reply(true, "already_recording")
                else -> reply(true, "sent", press = true)
            }
            "rec_stop" -> when {
                !c.live -> reply(false, "not_live")
                !c.recording -> reply(true, "already_stopped")
                else -> reply(true, "sent", press = true)
            }
            else -> reply(false, "unknown_cmd")
        }
    }

    private fun message(type: String, c: MacroCameraState, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "v" to VERSION, "type" to type, "seq" to ++seq,
            "phone_ms" to wallMs(), "mono_ms" to monoMs(),
            "camera" to cameraSnapshot(c),
        ).apply { extra.forEach { (k, v) -> put(k, v) } }

    companion object {
        const val VERSION = 1

        /** Every camera fact this app knows; M2 decides by its own config which to keep. */
        fun cameraSnapshot(c: MacroCameraState): Map<String, Any?> = linkedMapOf(
            "live" to c.live,
            "recording" to c.recording,
            "shooting_mode" to c.shootingMode,
            "resolution_code" to c.resolutionCode,
            "fps_index" to c.fpsIndex,
            "color_mode" to c.colorMode,
            "zoom" to c.zoomFactor,
            "expo_mode" to c.expoMode,
            "iso_limit" to c.isoLimit,
            "iso_index" to c.isoIndex,
            "ev_comp" to c.evComp,
        )
    }
}

/** [press] toggles the shutter once; [reply] goes back to the sender either way. */
data class CommandDecision(val press: Boolean, val reply: Map<String, Any?>)
