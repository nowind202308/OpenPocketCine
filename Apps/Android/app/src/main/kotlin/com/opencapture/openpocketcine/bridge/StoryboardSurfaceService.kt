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

    /**
     * The activity's answer to "M2 started or stopped borrowing": while M2 shows the picture the
     * scene stays active (the connectedDevice service keeps the camera network usable), so gimbal
     * commands are allowed and no foreground recovery runs on return.
     */
    @Volatile
    var onBorrowChanged: ((Boolean) -> Unit)? = null

    /** Gimbal stick lent by the activity (sensitivity, mirror, lock and mapping applied there). */
    @Volatile
    var gimbal: GimbalPort? = null

    /** Zoom moves for M2, lent by the activity over its session. */
    @Volatile
    var zoom: BridgeZoom? = null

    val borrowing: Boolean get() = arbiter.hasExternal

    /** Called by the live view instead of `session.attachSurface`. */
    fun attachLocal(session: PocketCameraSession, surface: Surface?) {
        this.session = session
        arbiter.setLocal(surface)
    }
}

interface GimbalPort {
    fun move(x: Float, y: Float)
    fun release()
    fun recenter()
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
    private var zoomTicking = false
    private val zoomTick = object : Runnable {
        override fun run() {
            val wait = StoryboardSurfaceHub.zoom?.tick()
            zoomTicking = wait != null
            if (wait != null) main.postDelayed(this, wait)
        }
    }

    /** Main thread: runs a zoom request and keeps its move ticking. */
    private fun zoom(request: (BridgeZoom) -> Unit) = main.post {
        val zoom = StoryboardSurfaceHub.zoom ?: return@post
        request(zoom)
        if (!zoomTicking) zoomTick.run()
    }

    private fun zoomQuery(reply: Parcel) {
        val zoom = StoryboardSurfaceHub.zoom
        val port = zoom?.port
        val stops = port?.stops().orEmpty()
        reply.writeFloat((port?.factor() ?: 1.0).toFloat())
        reply.writeInt(stops.size)
        stops.forEach { reply.writeFloat(it.toFloat()) }
        reply.writeInt((if (port?.recording() == true) FLAG_RECORDING else 0) or (if (zoom?.moving == true) FLAG_MOVING else 0))
        reply.writeString(if (port == null) REASON_NO_SESSION else port.refusal(true).orEmpty())
        reply.writeString(if (port == null) REASON_NO_SESSION else port.refusal(false).orEmpty())
    }

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
            if (code == ZOOM_QUERY) {
                reply?.writeNoException()
                reply?.writeInt(status())
                reply?.writeInt(WIRE_VERSION)
                reply?.let(::zoomQuery)
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
                PRESENTED -> {
                    // M2 shows the frames: feed its presentation stamps to the decoder so the
                    // watchdog and first-picture checks see a live picture.
                    val stamp = data.readLong()
                    if (StoryboardSurfaceHub.arbiter.showingExternal) StoryboardSurfaceHub.session?.decoder?.notePresented(stamp)
                    RESULT_OK
                }
                STICK -> {
                    val x = data.readFloat().coerceIn(-1f, 1f)
                    val y = data.readFloat().coerceIn(-1f, 1f)
                    main.post { StoryboardSurfaceHub.gimbal?.move(x, y) }
                    RESULT_OK
                }
                STICK_RELEASE -> {
                    main.post { StoryboardSurfaceHub.gimbal?.release() }
                    RESULT_OK
                }
                RECENTER -> {
                    main.post { StoryboardSurfaceHub.gimbal?.recenter() }
                    RESULT_OK
                }
                ZOOM_HOLD -> {
                    val zoomIn = data.readInt() != 0
                    val level = data.readInt()
                    val limit = data.readFloat().toDouble()
                    zoom { it.hold(zoomIn, level, limit) }
                    RESULT_OK
                }
                ZOOM_STOP -> {
                    zoom { it.stop() }
                    RESULT_OK
                }
                ZOOM_TO -> {
                    val factor = data.readFloat().toDouble()
                    val durationMs = data.readInt().coerceIn(0, MAX_RAMP_MS).toLong()
                    if (!factor.isNaN()) zoom { it.to(factor, durationMs) }
                    RESULT_OK
                }
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
        if (old == null) main.post { StoryboardSurfaceHub.onBorrowChanged?.invoke(true) }
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
        main.post {
            surface.release()
            StoryboardSurfaceHub.gimbal?.release()
            StoryboardSurfaceHub.zoom?.stop()
            StoryboardSurfaceHub.onBorrowChanged?.invoke(false)
        }
        Log.i(TAG, "m2 returned the live picture ($why)")
    }

    companion object {
        private const val TAG = "StoryboardSurface"
        const val DESCRIPTOR = "com.opencapture.openpocketcine.storyboard.Surface"
        const val WIRE_VERSION = 2
        const val ATTACH = IBinder.FIRST_CALL_TRANSACTION
        const val DETACH = IBinder.FIRST_CALL_TRANSACTION + 1
        const val STATUS = IBinder.FIRST_CALL_TRANSACTION + 2
        const val DIAG = IBinder.FIRST_CALL_TRANSACTION + 3
        /** One-way: `long` SurfaceTexture timestamp of a frame M2 displayed. */
        const val PRESENTED = IBinder.FIRST_CALL_TRANSACTION + 4
        /** One-way: `float x, float y` in -1…1 (right, up positive), as the on-screen stick. */
        const val STICK = IBinder.FIRST_CALL_TRANSACTION + 5
        const val STICK_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 6
        const val RECENTER = IBinder.FIRST_CALL_TRANSACTION + 7
        /** One-way: `int zoomIn, int level 1..7, float limit` (≤ 0: none). Repeat within 700 ms while held. */
        const val ZOOM_HOLD = IBinder.FIRST_CALL_TRANSACTION + 8
        const val ZOOM_STOP = IBinder.FIRST_CALL_TRANSACTION + 9
        /** One-way: `float factor, int durationMs` (0: at once). */
        const val ZOOM_TO = IBinder.FIRST_CALL_TRANSACTION + 10
        const val ZOOM_QUERY = IBinder.FIRST_CALL_TRANSACTION + 11
        const val FLAG_RECORDING = 1
        const val FLAG_MOVING = 2
        const val MAX_RAMP_MS = 120_000
        const val REASON_NO_SESSION = "no_session"
        const val REASON_NOT_LIVE = "not_live"
        const val REASON_NO_ZOOM = "no_zoom"
        const val REASON_LOCKED = "locked"
        const val REASON_RECORDING_COLOR_LOCK = "recording_color_lock"
        const val RESULT_OK = 0
        const val RESULT_NO_SESSION = 1
        const val RESULT_NOT_LIVE = 2
        const val RESULT_BAD_SURFACE = 3
    }
}
