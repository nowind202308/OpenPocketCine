package com.opencapture.openpocketcine.macro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.getSystemService
import com.opencapture.openpocketcine.R

/**
 * The floating button and its radial menu, drawn over every app with plain views.
 *
 * - Press and drag toward a sector, release: runs it (the press's own gesture, no aiming).
 *   Dragging onto a group opens its ring; releasing on a group, or back on the button, cancels.
 * - 縮放 > 放大/縮小 > speed: zooms while the finger rests on a speed; releasing stops, and
 *   releasing in the lock zone past the speed keeps zooming until the button is tapped.
 * - Tap: the menu stays open; tap through the rings (a tapped speed zooms continuously).
 * - Long-press without moving: the button lifts and can be dragged to another edge.
 *
 * The last sector opens these settings. All decisions come from [FloatingMacroLogic] and
 * [RadialTracker]; this class only draws and routes touches.
 */
class FloatingMacroOverlay(
    private val context: Context,
    /** Read when the fan opens, so it follows the connected camera. */
    private val menu: () -> List<MacroMenuEntry>,
    private val onRun: (MacroPreset) -> Unit,
    private val onOpenSettings: () -> Unit,
    /** Continuous zoom; null hides nothing but makes the zoom ring inert. */
    private val zoom: ZoomDriver?,
) {
    private val windows = checkNotNull(context.getSystemService<WindowManager>())
    private val density = context.resources.displayMetrics.density
    private val buttonSize = dp(64)

    private val button = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        contentDescription = context.getString(R.string.macro_button_description)
        elevation = dp(6).toFloat()
    }
    private val buttonParams = overlayParams(buttonSize, buttonSize, touchable = true)

    private val radial = RadialMenuView(context).apply {
        onTapRelease = { node, lock -> finish(node, lock, tapped = true) }
        onHover = ::hover
    }
    private val labels = FloatingMacroLogic.MenuLabels(
        zoom = context.getString(R.string.macro_menu_zoom),
        zoomIn = context.getString(R.string.macro_menu_zoom_in),
        zoomOut = context.getString(R.string.macro_menu_zoom_out),
        settings = context.getString(R.string.macro_menu_settings),
    )
    private val radialParams = overlayParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, touchable = false,
    ).apply { flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS }
    private var radialShown = false
    /** The rows of the open fan; a pick always maps to what was drawn. */
    private var shownMenu: List<MacroMenuEntry> = emptyList()
    private var shown = false

    private var lastCamera = MacroCameraState()
    private var lastRunning: String? = null
    private var lastNote: String? = null

    init {
        // A tap (or TalkBack's click) leaves the fan open; the press already drew it.
        button.setOnClickListener { if (radialShown && radial.tapMode) closeRadial() else openRadial(tapMode = true) }
        attachPressListener()
    }

    /** [PressListener] calls `performClick()` on a still release, so taps and TalkBack agree. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressListener() {
        button.setOnTouchListener(PressListener())
    }

    fun show() {
        if (shown) return
        val screen = screenSize()
        val saved = MacroPrefs.buttonPosition(context)
        buttonParams.x = saved?.first ?: FloatingMacroLogic.snapX(screen.first, buttonSize, screen.first)
        buttonParams.y = restY(saved?.second ?: (screen.second / 3))
        windows.addView(button, buttonParams)
        shown = true
    }

    fun remove() {
        zoom?.stop()
        closeRadial()
        if (shown) windows.removeView(button)
        shown = false
    }

    /** Rotation or a fold: keep the button against an edge, with room for the fan. */
    fun onScreenChanged() {
        if (!shown) return
        stopHeldZoom()
        closeRadial()
        val screen = screenSize()
        buttonParams.x = FloatingMacroLogic.snapX(buttonParams.x, buttonSize, screen.first)
        buttonParams.y = restY(buttonParams.y)
        windows.updateViewLayout(button, buttonParams)
    }

    fun render(tone: FloatingTone, camera: MacroCameraState, running: String?, note: String?, zoomState: ZoomState?) {
        lastCamera = camera
        lastRunning = running
        lastNote = note
        val style = styleFor(tone)
        button.text = if (tone == FloatingTone.ZOOMING && zoomState != null) {
            context.getString(R.string.macro_glyph_zooming, if (zoomState.zoomIn) "+" else "−", zoomState.level)
        } else {
            context.getString(style.glyph)
        }
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(style.color)
            setStroke(dp(2), Color.argb(200, 255, 255, 255))
        }
        if (radialShown) radial.update(items(), status())
    }

    private fun items(): List<RadialNode> =
        FloatingMacroLogic.buildMenu(shownMenu, lastCamera, lastRunning, labels)

    private fun status(): String? = FloatingMacroLogic.blockReason(lastCamera, lastRunning) ?: lastNote

    private fun openRadial(tapMode: Boolean) {
        if (!shown) return
        if (!radialShown) {
            shownMenu = menu()
            val center = buttonCenter()
            radial.open(center.first, center.second, items(), status())
            windows.addView(radial, radialParams)
            radialShown = true
        }
        if (tapMode) enterTapMode()
    }

    /** After a tap the fan takes touches itself; until then the press stays with the button. */
    private fun enterTapMode() {
        radial.tapMode = true
        radialParams.flags = radialParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        windows.updateViewLayout(radial, radialParams)
    }

    /** A zoom held by the finger ends with the press; a latched one keeps going. */
    private fun stopHeldZoom() {
        if (zoom?.state?.value?.latched != true) zoom?.stop()
    }

    private fun closeRadial() {
        if (!radialShown) return
        windows.removeView(radial)
        radialParams.flags = radialParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        radialShown = false
    }

    /** Press mode only: resting on a speed zooms at it; leaving a speed holds the zoom where it is. */
    private fun hover(node: RadialNode?) {
        if (radial.tapMode) return
        val z = zoom ?: return
        val action = node?.action
        if (action is RadialAction.Zoom && node.enabled) {
            z.hold(action.zoomIn, action.level)
        } else if (z.state.value?.latched != true) {
            z.pause()
        }
    }

    /**
     * The end of a press or a tap: run the leaf it ended on. A blocked preset is still sent, so
     * the runner says why. Zoom stops unless it ended in the lock zone, or was a tapped speed.
     */
    private fun finish(node: RadialNode?, lock: Boolean, tapped: Boolean) {
        val action = node?.action
        if (action is RadialAction.Zoom && node.enabled && zoom != null) {
            if (tapped) zoom.hold(action.zoomIn, action.level)
            if (lock || tapped) zoom.latch() else zoom.stop()
            closeRadial()
            return
        }
        stopHeldZoom()
        closeRadial()
        when (action) {
            is RadialAction.Preset -> onRun(action.preset)
            RadialAction.Settings -> onOpenSettings()
            else -> Unit
        }
    }

    /**
     * Routes one press on the button: a drag selects in the fan, a still release is a click,
     * and a still long-press lifts the button to move it.
     */
    private inner class PressListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val handler = Handler(Looper.getMainLooper())
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var travelled = false
        private var moving = false
        /** This press stopped a latched zoom; it does nothing else. */
        private var stoppedZoom = false
        private val lift = Runnable {
            if (travelled) return@Runnable
            moving = true
            closeRadial()
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stoppedZoom = zoom?.state?.value?.latched == true
                    if (stoppedZoom) {
                        zoom?.stop()
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        return true
                    }
                    downX = event.rawX; downY = event.rawY
                    startX = buttonParams.x; startY = buttonParams.y
                    travelled = false
                    moving = false
                    // The fan shows at once; this press may still turn out to be a tap or a lift.
                    openRadial(tapMode = false)
                    handler.postDelayed(lift, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (stoppedZoom) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!travelled && !FloatingMacroLogic.isTap(dx, dy, slop)) {
                        travelled = true
                        if (!moving) handler.removeCallbacks(lift)
                    }
                    if (moving) {
                        buttonParams.x = startX + dx.toInt()
                        buttonParams.y = startY + dy.toInt()
                        windows.updateViewLayout(button, buttonParams)
                    } else if (radialShown) {
                        radial.track(event.rawX, event.rawY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(lift)
                    if (stoppedZoom) return true
                    when {
                        moving -> settle()
                        travelled -> {
                            val node = if (radialShown) radial.track(event.rawX, event.rawY) else null
                            finish(node, radial.inLockZone, tapped = false)
                        }
                        // A still release is a click: the fan already open switches to tap mode.
                        else -> view.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(lift)
                    if (stoppedZoom) return true
                    stopHeldZoom()
                    if (moving) settle() else closeRadial()
                }
            }
            return true
        }

        private fun settle() {
            val screen = screenSize()
            buttonParams.x = FloatingMacroLogic.snapX(buttonParams.x, buttonSize, screen.first)
            buttonParams.y = restY(buttonParams.y)
            windows.updateViewLayout(button, buttonParams)
            MacroPrefs.setButtonPosition(context, buttonParams.x, buttonParams.y)
            moving = false
        }
    }

    private fun restY(y: Int): Int =
        RadialMenuLogic.restY(y, buttonSize, screenSize().second, radial.ringOneOuter.toInt())

    private fun buttonCenter(): Pair<Float, Float> {
        val at = IntArray(2)
        button.getLocationOnScreen(at)
        return (at[0] + buttonSize / 2f) to (at[1] + buttonSize / 2f)
    }

    private class ToneStyle(val color: Int, val glyph: Int)

    private fun styleFor(tone: FloatingTone): ToneStyle = when (tone) {
        FloatingTone.OFFLINE -> ToneStyle(Color.argb(170, 90, 90, 90), R.string.macro_glyph_offline)
        FloatingTone.READY -> ToneStyle(Color.argb(220, 33, 99, 186), R.string.macro_glyph_ready)
        FloatingTone.RECORDING -> ToneStyle(Color.argb(220, 150, 40, 40), R.string.macro_glyph_recording)
        FloatingTone.RUNNING -> ToneStyle(Color.argb(230, 214, 140, 20), R.string.macro_glyph_running)
        FloatingTone.ZOOMING -> ToneStyle(Color.argb(235, 120, 60, 200), R.string.macro_glyph_running)
        FloatingTone.DONE -> ToneStyle(Color.argb(230, 40, 150, 70), R.string.macro_glyph_done)
        FloatingTone.FAILED -> ToneStyle(Color.argb(240, 200, 40, 40), R.string.macro_glyph_failed)
    }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun screenSize(): Pair<Int, Int> =
        context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
