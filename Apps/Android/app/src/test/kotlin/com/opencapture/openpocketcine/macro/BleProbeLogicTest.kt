package com.opencapture.openpocketcine.macro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleProbeLogicTest {
    private val wbKey = 0x022C
    private val evKey = 0x022E

    private fun obs(vararg trials: BleProbeTrial, frames: Map<Int, Int> = emptyMap()) =
        BleProbeObservation(trials.toList(), frames, setOf(wbKey, evKey), "01.02.03")

    @Test
    fun appliedWriteMeansAcceptedEvenWithoutAReply() {
        // The status over Wi-Fi is the proof; a missing Bluetooth reply does not undo it.
        val o = obs(BleProbeTrial("wb", appliedMs = 300), frames = mapOf(0x0100 to 4))
        assertEquals(BleProbeVerdict.ACCEPTED, BleProbeLogic.verdict(o))
    }

    @Test
    fun replyWithoutChangeIsAckOnly() {
        val o = obs(BleProbeTrial("wb", ackMs = 80, ackCode = 0xE0), frames = mapOf(wbKey to 1))
        assertEquals(BleProbeVerdict.ACK_ONLY, BleProbeLogic.verdict(o))
        assertTrue(BleProbeLogic.report(o).contains("0xE0"))
    }

    @Test
    fun aReadReplyAloneIsNotAcceptance() {
        val o = obs(
            BleProbeTrial("wb"),
            BleProbeTrial("iso read", ackMs = 50, isWrite = false),
            frames = mapOf(0x020F to 1),
        )
        assertEquals(BleProbeVerdict.ACK_ONLY, BleProbeLogic.verdict(o))
    }

    @Test
    fun silenceWithTrafficIsRejectedAndSilenceWithoutTrafficIsUnknown() {
        assertEquals(BleProbeVerdict.REJECTED,
            BleProbeLogic.verdict(obs(BleProbeTrial("wb"), frames = mapOf(0x0100 to 3))))
        assertEquals(BleProbeVerdict.NO_BLE_TRAFFIC, BleProbeLogic.verdict(obs(BleProbeTrial("wb"))))
    }

    @Test
    fun allSkippedIsNotRun() {
        val o = obs(BleProbeTrial("wb", skipped = "相機未連線"), BleProbeTrial("ev", skipped = "相機未連線"))
        assertEquals(BleProbeVerdict.NOT_RUN, BleProbeLogic.verdict(o))
        assertTrue(BleProbeLogic.report(o).contains("略過：相機未連線"))
    }

    @Test
    fun pushesAreFramesThatAreNotProbeReplies() {
        assertFalse(BleProbeLogic.pushesOverBle(obs(frames = mapOf(wbKey to 1, evKey to 1))))
        assertTrue(BleProbeLogic.pushesOverBle(obs(frames = mapOf(wbKey to 1, 0x0100 to 2))))
    }

    @Test
    fun targetsAlwaysDifferFromTheCurrentValue() {
        assertEquals(6_000, BleProbeLogic.whiteBalanceTarget(5_000))
        assertEquals(5_000, BleProbeLogic.whiteBalanceTarget(5_600))
        assertEquals(5_000, BleProbeLogic.whiteBalanceTarget(-1))
        assertEquals(0x11, BleProbeLogic.evTarget(0x10))
        assertEquals(0x18, BleProbeLogic.evTarget(0x19)) // at +3 it steps down instead
        assertEquals(0x11, BleProbeLogic.evTarget(-1)) // unknown counts as 0
        assertEquals(0, BleProbeLogic.evThirds(-1))
        assertEquals(-3, BleProbeLogic.evThirds(0x0D))
    }

    @Test
    fun reportListsFramesAsSetSlashId() {
        val o = obs(BleProbeTrial("wb", ackMs = 90, ackCode = 0, appliedMs = 400), frames = mapOf(wbKey to 1, 0x0100 to 2))
        val text = BleProbeLogic.report(o)
        assertTrue(text.contains("01/00×2"))
        assertTrue(text.contains("02/2C×1"))
        assertTrue(text.contains("代碼 0x00"))
        assertTrue(text.contains("XIAO nRF52840"))
    }
}
