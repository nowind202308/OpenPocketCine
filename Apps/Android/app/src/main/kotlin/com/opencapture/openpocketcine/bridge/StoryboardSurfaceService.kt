package com.opencapture.openpocketcine.bridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.Surface
import com.opencapture.opencine.dji.PocketCameraSession
import com.opencapture.opencine.vocabulary.ConnectionPhase

/**
 * Process-wide owner of the live decoder's output choice. The activity lends its session;
 * the live view and [StoryboardSurfaceService] both go through [arbiter].
 */
object StoryboardSurfaceHub {
    @Volatile
    var session: PocketCameraSession? = null

    val arbiter = SurfaceArbiter<Surface> { surface -> session?.attachSurface(surface) }

    /** Called by the live view instead of `session.attachSurface`. */
    fun attachLocal(session: PocketCameraSession, surface: Surface?) {
        this.session = session
        arbiter.setLocal(surface)
    }
}

/**
 * Lends the live picture to the storyboard companion (M2, `tw.nowind.sbcards`): M2 binds,
 * hands over its own Surface, and the decoder renders there while this app's live view is
 * not on screen. No re-encode, no keyframe request. Wire contract: docs/storyboard-bridge.md.
 *
 * Only M2's package may call (checked against the binder caller's UID on every transaction).
 * A death link on M2's token drops the borrowed surface if M2's process dies.
 */
class StoryboardSurfaceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var token: IBinder? = null
    private var borrowed: Surface? = null
    private val death = IBinder.DeathRecipient { release("m2 died") }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val callers = packageManager.getPackagesForUid(getCallingUid()).orEmpty()
            if (StoryboardBridge.M2_PACKAGE !in callers) {
                Log.w(TAG, "refused surface call from ${callers.joinToString()}")
                return false
            }
            data.enforceInterface(DESCRIPTOR)
            if (code == DIAG) {
                reply?.writeNoException()
                reply?.writeInt(status())
                reply?.writeInt(WIRE_VERSION)
                reply?.writeString(diagnostics())
                return true
            }
            val result = when (code) {
                ATTACH -> {
                    val clientToken = data.readStrongBinder()
                    val surface = if (data.readInt() != 0) Surface.CREATOR.createFromParcel(data) else null
                    attach(clientToken, surface)
                }
                DETACH -> {
                    release("m2 detach")
                    RESULT_OK
                }
                STATUS -> status()
                else -> return super.onTransact(code, data, reply, flags)
            }
            reply?.writeNoException()
            reply?.writeInt(result)
            reply?.writeInt(WIRE_VERSION)
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        release("unbind")
        return false
    }

    override fun onDestroy() {
        release("service destroyed")
        super.onDestroy()
    }

    private fun status(): Int {
        val session = StoryboardSurfaceHub.session ?: return RESULT_NO_SESSION
        return if (session.phaseFlow.value == ConnectionPhase.LIVE) RESULT_OK else RESULT_NOT_LIVE
    }

    /** One line for M2's test screen: phase, output choice, decoder input and errors. */
    private fun diagnostics(): String {
        val session = StoryboardSurfaceHub.session ?: return "no session"
        val d = session.decoder
        return "phase=${session.phaseFlow.value.name.lowercase()} out=${StoryboardSurfaceHub.arbiter.describe()} " +
            "in=${d.framesEnqueued.get()} shown=${d.framesPresented.get()} err=${d.decoderErrors.get()}"
    }

    @Synchronized
    private fun attach(clientToken: IBinder?, surface: Surface?): Int {
        if (surface == null || !surface.isValid) return RESULT_BAD_SURFACE
        val old = borrowed
        token?.let { runCatching { it.unlinkToDeath(death, 0) } }
        token = clientToken
        clientToken?.let { runCatching { it.linkToDeath(death, 0) } }
        borrowed = surface
        StoryboardSurfaceHub.arbiter.setExternal(surface)
        old?.release()
        Log.i(TAG, "m2 borrowed the live picture")
        return status()
    }

    @Synchronized
    private fun release(why: String) {
        val surface = borrowed ?: return
        borrowed = null
        token?.let { runCatching { it.unlinkToDeath(death, 0) } }
        token = null
        // Synchronous: M2 calls DETACH from surfaceDestroyed and waits for this to return.
        StoryboardSurfaceHub.arbiter.setExternal(null)
        main.post { surface.release() }
        Log.i(TAG, "m2 returned the live picture ($why)")
    }

    companion object {
        private const val TAG = "StoryboardSurface"
        const val DESCRIPTOR = "com.opencapture.openpocketcine.storyboard.Surface"
        const val WIRE_VERSION = 1
        const val ATTACH = IBinder.FIRST_CALL_TRANSACTION
        const val DETACH = IBinder.FIRST_CALL_TRANSACTION + 1
        const val STATUS = IBinder.FIRST_CALL_TRANSACTION + 2
        const val DIAG = IBinder.FIRST_CALL_TRANSACTION + 3
        const val RESULT_OK = 0
        const val RESULT_NO_SESSION = 1
        const val RESULT_NOT_LIVE = 2
        const val RESULT_BAD_SURFACE = 3
    }
}
