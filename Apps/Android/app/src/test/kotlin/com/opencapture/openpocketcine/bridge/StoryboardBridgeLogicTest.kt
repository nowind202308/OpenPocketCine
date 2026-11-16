package com.opencapture.openpocketcine.bridge

import com.opencapture.openpocketcine.macro.MacroCameraState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The storyboard companion logs takes from these messages; the contract is `docs/storyboard-bridge.md`. */
class StoryboardBridgeLogicTest {
    private var mono = 1_000L
    private val logic = StoryboardBridgeLogic(wallMs = { 1_800_000_000_000L + mono }, monoMs = { mono })

    private val idle = MacroCameraState(live = true, recording = false)
    private val rolling = MacroCameraState(live = true, recording = true)
    private val offline = MacroCameraState(live = false, recording = false)

    private fun types(c: MacroCameraState) = logic.onCamera(c).map { it["type"] }

    @Test fun aTakeIsAStartThenAStopWithItsDuration() {
        assertEquals(listOf("link"), types(idle))
        val start = logic.onCamera(rolling).single()
        assertEquals("rec_start", start["type"])
        assertEquals(false, start["joined_late"])
        mono += 12_500
        val stop = logic.onCamera(idle).single()
        assertEquals("rec_stop", stop["type"])
        assertEquals(12_500L, stop["duration_ms"])
    }

    @Test fun unchangedStateSendsNothing() {
        logic.onCamera(idle)
        assertEquals(emptyList(), types(idle))
    }

    @Test fun aRecordingAlreadyRunningIsMarkedJoinedLate() {
        val first = logic.onCamera(rolling)
        assertEquals(listOf("link", "rec_start"), first.map { it["type"] })
        assertEquals(true, first[1]["joined_late"])
    }

    @Test fun losingTheLinkMidTakeIsALostStopNotAStop() {
        logic.onCamera(idle)
        logic.onCamera(rolling)
        assertEquals(listOf("link", "rec_lost"), types(offline))
        // Back live and idle: no stop for the take that was already reported lost.
        assertEquals(listOf("link"), types(idle))
    }

    @Test fun everyMessageCarriesVersionSequenceClocksAndCamera() {
        val a = logic.onCamera(idle).single()
        val b = logic.onCamera(rolling).single()
        assertEquals(StoryboardBridgeLogic.VERSION, a["v"])
        assertTrue((b["seq"] as Long) > (a["seq"] as Long))
        assertEquals(mono, b["mono_ms"])
        assertTrue(b["camera"] is Map<*, *>)
    }

    @Test fun commandsPressOnlyWhenTheyChangeTheRecordState() {
        fun cmd(name: String, v: Int = StoryboardBridgeLogic.VERSION) = mapOf("v" to v, "cmd" to name, "id" to "c1")

        assertTrue(logic.onCommand(cmd("rec_start"), idle).press)
        assertTrue(logic.onCommand(cmd("rec_stop"), rolling).press)

        val already = logic.onCommand(cmd("rec_start"), rolling)
        assertFalse(already.press)
        assertEquals("already_recording", already.reply["reason"])
        assertEquals("already_stopped", logic.onCommand(cmd("rec_stop"), idle).reply["reason"])

        val notLive = logic.onCommand(cmd("rec_start"), offline)
        assertFalse(notLive.press)
        assertEquals(false, notLive.reply["ok"])
        assertEquals("not_live", notLive.reply["reason"])

        assertEquals("version", logic.onCommand(cmd("rec_start", v = 99), idle).reply["reason"])
        assertEquals("unknown_cmd", logic.onCommand(cmd("format_card"), idle).reply["reason"])
        val pong = logic.onCommand(cmd("ping"), offline)
        assertEquals("pong", pong.reply["reason"])
        assertEquals("c1", pong.reply["cmd_id"])
    }
}
