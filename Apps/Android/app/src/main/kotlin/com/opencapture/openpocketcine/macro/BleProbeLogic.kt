package com.opencapture.openpocketcine.macro

import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * One write (or read) sent over the camera's Bluetooth link instead of Wi-Fi.
 *
 * [ackMs] is when a reply with the same DUML command arrived over Bluetooth; [appliedMs] is when
 * the camera's own status, which still arrives over Wi-Fi, showed the new value. The second is
 * the independent proof: the session never sets that value itself during the probe.
 */
data class BleProbeTrial(
    val name: String,
    val skipped: String? = null,
    val ackMs: Long? = null,
    /** First payload byte of the reply; DJI replies use 0x00 for success. */
    val ackCode: Int? = null,
    val appliedMs: Long? = null,
    /** False for a read: there is nothing to apply, the reply is the whole answer. */
    val isWrite: Boolean = true,
)

data class BleProbeObservation(
    val trials: List<BleProbeTrial>,
    /** Every frame that arrived over Bluetooth during the probe, by DUML key (set << 8 | id). */
    val bleFrames: Map<Int, Int>,
    /** Keys of the probe's own replies, so the rest can be told apart as camera pushes. */
    val replyKeys: Set<Int>,
    val firmware: String?,
)

enum class BleProbeVerdict { ACCEPTED, ACK_ONLY, REJECTED, NO_BLE_TRAFFIC, NOT_RUN }

/** What the settings page drives; the activity that owns the session provides it. */
interface BleProbeHost {
    val running: StateFlow<Boolean>
    val report: StateFlow<String?>
    fun start(): Boolean
}

/**
 * Decisions and wording for the Bluetooth control probe, Android-free so they test on the JVM.
 * The question it answers: can a Bluetooth-only controller (XIAO nRF52840) change Pocket 4P
 * settings, or does it need the camera's Wi-Fi like OpenPocketCine does today?
 */
object BleProbeLogic {
    const val NEUTRAL_EV_RAW = 0x10
    private const val EV_MIN_RAW = 0x07
    private const val EV_MAX_RAW = 0x19

    fun verdict(o: BleProbeObservation): BleProbeVerdict {
        val ran = o.trials.filter { it.skipped == null }
        val writes = ran.filter { it.isWrite }
        return when {
            ran.isEmpty() -> BleProbeVerdict.NOT_RUN
            writes.any { it.appliedMs != null } -> BleProbeVerdict.ACCEPTED
            ran.any { it.ackMs != null } -> BleProbeVerdict.ACK_ONLY
            o.bleFrames.isEmpty() -> BleProbeVerdict.NO_BLE_TRAFFIC
            else -> BleProbeVerdict.REJECTED
        }
    }

    /** Frames that were not replies to the probe: the camera pushes something over Bluetooth. */
    fun pushesOverBle(o: BleProbeObservation): Boolean = o.bleFrames.keys.any { it !in o.replyKeys }

    /** A Kelvin clearly different from the current one, so a change cannot be a coincidence. */
    fun whiteBalanceTarget(currentKelvin: Int): Int = if (currentKelvin in 4_700..5_300) 6_000 else 5_000

    /** One third of a stop away from the current EV, staying inside the camera's range. */
    fun evTarget(currentRaw: Int): Int {
        val raw = if (currentRaw in EV_MIN_RAW..EV_MAX_RAW) currentRaw else NEUTRAL_EV_RAW
        return if (raw < EV_MAX_RAW) raw + 1 else raw - 1
    }

    fun evThirds(raw: Int): Int = (if (raw in EV_MIN_RAW..EV_MAX_RAW) raw else NEUTRAL_EV_RAW) - NEUTRAL_EV_RAW

    fun report(o: BleProbeObservation): String = buildString {
        appendLine("藍牙控制實驗結果")
        appendLine("相機韌體：${o.firmware ?: "未知"}")
        appendLine()
        o.trials.forEach { t ->
            appendLine("【${t.name}】")
            if (t.skipped != null) {
                appendLine("  略過：${t.skipped}")
                return@forEach
            }
            val ack = t.ackMs?.let { ms ->
                val code = t.ackCode?.let { " 代碼 0x%02X".format(Locale.ROOT, it) } ?: ""
                "收到（${ms} ms$code）"
            } ?: "沒有收到"
            appendLine("  藍牙回覆：$ack")
            if (t.isWrite) appendLine("  相機實際套用：${t.appliedMs?.let { "是（${it} ms）" } ?: "否"}")
        }
        appendLine()
        val frames = o.bleFrames.entries.sortedBy { it.key }
            .joinToString("、") { (key, count) -> "%02X/%02X×%d".format(Locale.ROOT, key shr 8, key and 0xFF, count) }
        appendLine("藍牙收到的封包：${frames.ifEmpty { "無" }}")
        appendLine("相機透過藍牙主動推送資料：${if (pushesOverBle(o)) "有" else "沒有"}")
        appendLine()
        appendLine("結論：${conclusion(verdict(o))}")
    }

    fun conclusion(v: BleProbeVerdict): String = when (v) {
        BleProbeVerdict.ACCEPTED -> "相機接受透過藍牙改參數，XIAO nRF52840 可以走只用藍牙的路線。"
        BleProbeVerdict.ACK_ONLY -> "相機有回覆但沒有套用，可能拒絕了指令，請把回覆代碼一起貼回來判斷。"
        BleProbeVerdict.REJECTED -> "相機沒有回應藍牙送出的改參數指令，控制器需要走 Wi-Fi（ESP32-S3）。"
        BleProbeVerdict.NO_BLE_TRAFFIC -> "實驗期間藍牙沒有收到任何封包，無法判斷，請重新連線相機後再試一次。"
        BleProbeVerdict.NOT_RUN -> "沒有執行任何測試，請在相機已連線、未錄影時再試。"
    }
}
