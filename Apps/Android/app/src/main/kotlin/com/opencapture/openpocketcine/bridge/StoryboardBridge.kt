package com.opencapture.openpocketcine.bridge

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.opencapture.openpocketcine.macro.MacroCameraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android side of the storyboard bridge (fork-only, Android-only; see `docs/PARITY.md`).
 *
 * - Camera events go to M2 only: the intent names M2's package.
 * - Commands are accepted only from M2, checked with the sender identity Android 14 adds to a
 *   broadcast that opts in. Older Android, or an unnamed sender, is ignored.
 * - Lives as long as the activity that owns the camera session, like the floating macro button.
 */
class StoryboardBridge(
    private val context: Context,
    private val camera: StateFlow<MacroCameraState>,
    /** Toggles recording through the app's one shutter path. */
    private val pressShutter: () -> Unit,
    private val scope: CoroutineScope,
) {
    private val logic = StoryboardBridgeLogic(System::currentTimeMillis, SystemClock::elapsedRealtime)
    private var job: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            if (sentFromPackage != M2_PACKAGE) {
                Log.w(TAG, "command ignored from ${sentFromPackage ?: "unnamed sender"}")
                return
            }
            val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: return
            val command = runCatching { toMap(JSONObject(payload)) }.getOrNull() ?: return
            val decision = logic.onCommand(command, camera.value)
            if (decision.press) pressShutter()
            send(decision.reply)
        }
    }

    fun start() {
        if (job != null) return
        job = scope.launch { camera.collect { state -> logic.onCamera(state).forEach(::send) } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_COMMAND), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        job?.cancel()
        job = null
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun send(message: Map<String, Any?>) {
        val intent = Intent(ACTION_EVENT)
            .setPackage(M2_PACKAGE)
            .putExtra(EXTRA_PAYLOAD, JSONObject(message).toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Lets M2 check that the event really came from this app.
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            context.sendBroadcast(intent, null, options)
        } else {
            context.sendBroadcast(intent)
        }
    }

    companion object {
        private const val TAG = "StoryboardBridge"
        const val M2_PACKAGE = "tw.nowind.sbcards"
        const val ACTION_EVENT = "tw.nowind.sbcards.action.CAMERA_EVENT"
        const val ACTION_COMMAND = "com.opencapture.openpocketcine.action.STORYBOARD_COMMAND"
        const val EXTRA_PAYLOAD = "payload"

        private fun toMap(o: JSONObject): Map<String, Any?> =
            o.keys().asSequence().associateWith { k -> unwrap(o.opt(k)) }

        private fun unwrap(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
            else -> v
        }
    }
}
