package com.opencapture.openpocketcine.macro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.opencapture.openpocketcine.MainActivity
import com.opencapture.openpocketcine.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the camera link usable while another app is in front, and hosts the floating button.
 *
 * Android blocks a backgrounded app's sockets on the camera's peer-to-peer Wi-Fi (OpenCine's
 * `noteSceneBecameInactive`); a `connectedDevice` foreground service keeps this process in a
 * foreground state so macro writes still reach the camera. The service owns no session: it
 * borrows the activity's through [MacroHub] and stops itself when the activity lets go.
 */
class FloatingMacroService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: FloatingMacroOverlay? = null
    private var watch: Job? = null
    private val flash = MutableStateFlow<FloatingTone?>(null)
    private var flashJob: Job? = null
    private var lastNote: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's stop: off until the app is opened again, not a changed setting.
            dismissed = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!MacroPrefs.overlayGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) watch = scope.launch { follow() }
        return START_NOT_STICKY
    }

    /** One overlay per attachment; none (activity gone) ends the service. */
    private suspend fun follow() {
        MacroHub.attachment.collectLatest { attachment ->
            overlay?.remove()
            overlay = null
            if (attachment == null) {
                stopSelf()
                return@collectLatest
            }
            val view = FloatingMacroOverlay(
                context = this,
                menu = attachment.menu,
                onRun = { attachment.run(it) },
                onOpenSettings = ::openSettings,
                zoom = attachment.zoom,
            )
            view.show()
            overlay = view
            scope.launch { MacroHub.results.collect { onResult(it) } }.also { results ->
                try {
                    val zoomState = attachment.zoom?.state ?: noZoom
                    combine(attachment.camera, attachment.running, flash, zoomState) { camera, running, flashed, zoom ->
                        RenderFrame(camera, running, zoom, FloatingMacroLogic.tone(camera, running, flashed, zoom))
                    }.collect { f -> view.render(f.tone, f.camera, f.running, lastNote, f.zoom) }
                } finally {
                    results.cancel()
                }
            }
        }
    }

    private fun onResult(result: MacroResult) {
        val feedback = FloatingMacroLogic.feedback(result)
        lastNote = result.note()
        vibrate(feedback.vibration)
        flashJob?.cancel()
        flash.value = feedback.tone
        flashJob = scope.launch {
            delay(FloatingMacroLogic.FLASH_MS)
            flash.value = null
        }
        // The note also lands in the notification, readable from the lock screen.
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification(lastNote))
    }

    private fun enterForeground(): Boolean = try {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(lastNote),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: RuntimeException) {
        // Android 12+ refuses a start from the background; 14+ also checks the type's prerequisites.
        Log.w(TAG, "floating macro: foreground start refused", e)
        false
    }

    private fun createChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.macro_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    private fun notification(note: String?): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FloatingMacroService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_macro_notification)
            .setContentTitle(getString(R.string.macro_notification_title))
            .setContentText(note ?: getString(R.string.macro_notification_idle))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.macro_notification_stop), stop)
            .build()
    }

    private fun openSettings() {
        // Allowed from the background: this app holds the overlay permission.
        startActivity(Intent(this, MacroSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun vibrate(pattern: List<Long>) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            getSystemService<Vibrator>()
        }
        if (vibrator?.hasVibrator() != true) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern.toLongArray(), -1))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    override fun onDestroy() {
        overlay?.remove()
        overlay = null
        scope.cancel()
        super.onDestroy()
    }

    private class RenderFrame(
        val camera: MacroCameraState,
        val running: String?,
        val zoom: ZoomState?,
        val tone: FloatingTone,
    )

    companion object {
        /** Stands in for a camera without zoom, so the render loop has one shape. */
        private val noZoom: StateFlow<ZoomState?> = MutableStateFlow(null)
        private const val TAG = "OpenPocketCineMacro"
        private const val CHANNEL_ID = "floating_macro"
        private const val NOTIFICATION_ID = 0x4D41
        private const val ACTION_STOP = "com.opencapture.openpocketcine.macro.STOP_FLOATING"

        /** Set by the notification's stop; cleared when the app's activity is created again. */
        @Volatile
        var dismissed = false

        /** Called by the activity while it is in front, so the foreground start is allowed. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FloatingMacroService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "floating macro: start refused", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingMacroService::class.java))
        }
    }
}
