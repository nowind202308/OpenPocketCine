package com.opencapture.openpocketcine.macro

import android.os.SystemClock
import com.opencapture.opencine.dji.DjiNative
import com.opencapture.opencine.dji.DumlFrame
import com.opencapture.opencine.vocabulary.CameraCommands
import com.opencapture.opencine.vocabulary.CameraStatus
import com.opencapture.opencine.vocabulary.ConnectionPhase
import com.opencapture.openpocketcine.AppModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends a few harmless writes over the camera's Bluetooth link while the normal Wi-Fi session
 * stays up, then puts every value back through the normal (Wi-Fi) setters.
 *
 * Proof of a write comes from the camera's status over Wi-Fi changing to the value only the
 * Bluetooth write asked for. Encoding uses the same `DjiNative.command` the session uses for
 * both links, so only the transport differs.
 */
class SessionBleProbe(
    private val model: AppModel,
    private val scope: CoroutineScope,
    private val busy: () -> Boolean,
) : BleProbeHost {
    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _report = MutableStateFlow<String?>(null)
    override val report: StateFlow<String?> = _report.asStateFlow()

    private class Seen(val at: Long, val frame: DumlFrame)

    private val log = MutableStateFlow<List<Seen>>(emptyList())
    private var seq = PROBE_SEQ_BASE

    override fun start(): Boolean {
        if (_running.value) return false
        _running.value = true
        _report.value = null
        scope.launch {
            try {
                _report.value = BleProbeLogic.report(probe())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                _report.value = "實驗中斷：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _running.value = false
            }
        }
        return true
    }

    private suspend fun probe(): BleProbeObservation = coroutineScope {
        val session = model.session
        val start = session.status.value
        val blocked = when {
            session.phase != ConnectionPhase.LIVE -> "相機未連線"
            start.isRecording -> "錄影中"
            busy() -> "巨集正在套用"
            else -> null
        }
        val names = listOf("白平衡 5000K/6000K（寫入）", "曝光補償 ±1/3（寫入）", "讀取 ISO 上限（讀取）")
        if (blocked != null) {
            return@coroutineScope BleProbeObservation(
                names.map { BleProbeTrial(it, skipped = blocked) }, emptyMap(), emptySet(), start.firmware,
            )
        }

        log.value = emptyList()
        val listener = launch {
            session.ble.frames.collect { f -> log.update { it + Seen(SystemClock.elapsedRealtime(), f) } }
        }
        val replyKeys = mutableSetOf<Int>()
        val trials = mutableListOf<BleProbeTrial>()

        // 1. White balance: works in every exposure mode, and the Kelvin is easy to read back.
        val wbTarget = BleProbeLogic.whiteBalanceTarget(start.wbKelvin)
        val (k, t) = CameraCommands.clampWhiteBalanceCustom(wbTarget, start.wbTint)
        trials += write(names[0], DjiNative.CMD_SET_WB_CUSTOM, "$k\u001f$t", replyKeys) {
            it.wbMode == CameraCommands.WB_CUSTOM && it.wbKelvin == k
        }
        // Back through the normal Wi-Fi path, which is known to work.
        if (start.wbMode == CameraCommands.WB_AUTO) {
            model.setWhiteBalanceAuto()
        } else if (start.wbKelvin in 2_000..10_000) {
            model.setWhiteBalance(start.wbKelvin, start.wbTint)
        }
        delay(SETTLE_MS)

        // 2. EV: only Auto exposure takes it.
        trials += if (session.status.value.expoMode != CameraCommands.EXPO_AUTO) {
            BleProbeTrial(names[1], skipped = "相機不在自動曝光，曝光補償無效")
        } else {
            val evTarget = BleProbeLogic.evTarget(start.evComp)
            write(names[1], DjiNative.CMD_SET_EV, "$evTarget", replyKeys) { it.evComp == evTarget }
                .also {
                    model.setEv(BleProbeLogic.evThirds(start.evComp))
                    delay(SETTLE_MS)
                }
        }

        // 3. A read: a controller needs replies to confirm each step and draw the screen.
        trials += if (!CameraCommands.offersIsoAuto(session.status.value.colorMode)) {
            BleProbeTrial(names[2], skipped = "D-Log2 沒有自動 ISO 上限可讀", isWrite = false)
        } else {
            read(names[2], DjiNative.CMD_GET_ISO_LIMIT, replyKeys)
        }

        // A moment more to catch anything the camera pushes on its own.
        delay(SETTLE_MS)
        listener.cancel()
        val counts = log.value.groupingBy { it.frame.key }.eachCount()
        BleProbeObservation(trials, counts, replyKeys, start.firmware)
    }

    private suspend fun write(
        name: String,
        kind: Int,
        extra: String,
        replyKeys: MutableSet<Int>,
        applied: (CameraStatus) -> Boolean,
    ): BleProbeTrial = coroutineScope {
        val key = DjiNative.waitKey(kind)
        replyKeys += key
        val t0 = SystemClock.elapsedRealtime()
        val ack = async { awaitReply(key, t0) }
        val seen = async {
            withTimeoutOrNull(TIMEOUT_MS) { model.session.status.first(applied) }
                ?.let { SystemClock.elapsedRealtime() - t0 }
        }
        send(kind, extra)
        val reply = ack.await()
        BleProbeTrial(
            name = name,
            ackMs = reply?.let { it.at - t0 },
            ackCode = reply?.frame?.payload?.firstOrNull()?.toInt()?.and(0xFF),
            appliedMs = seen.await(),
        )
    }

    private suspend fun read(name: String, kind: Int, replyKeys: MutableSet<Int>): BleProbeTrial = coroutineScope {
        val key = DjiNative.waitKey(kind)
        replyKeys += key
        val t0 = SystemClock.elapsedRealtime()
        val ack = async { awaitReply(key, t0) }
        send(kind, null)
        val reply = ack.await()
        BleProbeTrial(
            name = name,
            ackMs = reply?.let { it.at - t0 },
            ackCode = reply?.frame?.payload?.firstOrNull()?.toInt()?.and(0xFF),
            isWrite = false,
        )
    }

    private suspend fun awaitReply(key: Int, since: Long): Seen? = withTimeoutOrNull(TIMEOUT_MS) {
        log.first { seen -> seen.any { it.at >= since && it.frame.key == key } }
            .first { it.at >= since && it.frame.key == key }
    }

    private fun send(kind: Int, extra: String?) {
        seq = if (seq >= PROBE_SEQ_BASE + 0xFF) PROBE_SEQ_BASE else seq + 1
        model.session.ble.send(DjiNative.command(kind, seq, extra))
    }

    private companion object {
        /** A sequence range of its own, so its replies are easy to spot in the camera's logs. */
        const val PROBE_SEQ_BASE = 0x7A00
        const val TIMEOUT_MS = 4_000L
        const val SETTLE_MS = 1_500L
    }
}
