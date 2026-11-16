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
import android.widget.Toast
import androidx.core.content.getSystemService
import com.opencapture.openpocketcine.R

/**
 * The floating button and its radial menu, drawn over every app with plain views.
 *
 * - Press and drag toward a sector, release: runs it (the press's own gesture, no aiming).
 *   Dragging onto a group opens its ring; releasing on a group, or back on the button, cancels.
 * - A preset runs on ring 1; further out are 套用 / ISO / EV / 幀率, and a ring-3 value is
 *   remembered for that preset and applied at once (keypad keys then use it too).
 * - 縮放 > 放大/縮小 > speed sets the zoom up without moving the lens (the button shows "+3").
 *   Then a tap on the button starts it and the next tap stops it; a press while it runs stops
 *   it at once. 縮放 > 解除 clears it.
 * - Tap with no zoom set up: the menu stays open; tap through the rings.
 * - Long-press without moving: the button lifts and can be dragged to another edge; let go past
 *   the edge and it folds to a thin tab there (the record key hides). A tap on the tab opens it.
 * - While the storyboard companion (M2) shows the picture in its own screen the button folds to
 *   the tab by itself and opens again when M2 lets go; a tap opens it there for that while.
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
    /** Zoom set up from the menu and run on a tap; null makes the zoom ring inert. */
    private val zoom: ZoomDriver?,
    /** Starts or stops recording (fork, storyboard M2); null hides the record button. */
    private val onRecord: (() -> Unit)? = null,
    /** Remembered ISO / EV / frame rate per preset, shown on rings 2 and 3. */
    private val choices: (MacroPreset) -> MacroChoices = { MacroChoices() },
    /** A ring-3 pick: remember it and apply the preset. */
    private val onChoose: (MacroPreset, MacroFunction, Int?) -> Unit = { _, _, _ -> },
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

    /** The open button's resting place; the tab sits on the same edge, at the same height. */
    private var restX = 0
    private var restY = 0
    private val tabWidth = dp(18)
    private val tabHeight = dp(64)
    private var manualCollapsed = MacroPrefs.collapsed(context)
    private var autoCollapsed = false
    private var openedDuringAuto = false
    private val collapsed: Boolean
        get() = FloatingMacroLogic.collapsed(manualCollapsed, autoCollapsed, openedDuringAuto)
    private var recordShown = false
    private var lastTone = FloatingTone.OFFLINE
    private var lastArmed: ZoomArm? = null

    /** One tap starts or stops recording; it follows the main button, below it (above near the bottom). */
    private val recordSize = dp(52)
    private val recordGap = dp(10)
    private val recordButton: TextView? = onRecord?.let { record ->
        TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            elevation = dp(6).toFloat()
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                record()
            }
            // Fork: long-press says which build is installed.
            setOnLongClickListener {
                Toast.makeText(context, buildLabel(), Toast.LENGTH_LONG).show()
                true
            }
        }
    }
    private val recordParams = overlayParams(recordSize, recordSize, touchable = true)

    private val radial = RadialMenuView(context).apply {
        onTapRelease = ::finish
    }
    private val labels = FloatingMacroLogic.MenuLabels(
        zoom = context.getString(R.string.macro_menu_zoom),
        zoomIn = context.getString(R.string.macro_menu_zoom_in),
        zoomOut = context.getString(R.string.macro_menu_zoom_out),
        disarm = context.getString(R.string.macro_menu_zoom_disarm),
        settings = context.getString(R.string.macro_menu_settings),
        apply = context.getString(R.string.macro_menu_apply),
        original = context.getString(R.string.macro_menu_original),
        fps = context.getString(R.string.macro_menu_fps),
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
        // A tap (or TalkBack's click): an armed zoom runs or stops, otherwise the menu stays open.
        button.setOnClickListener { if (collapsed) openFromTab() else tap() }
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
        restX = saved?.first ?: FloatingMacroLogic.snapX(screen.first, buttonSize, screen.first)
        restY = restY(saved?.second ?: (screen.second / 3))
        buttonParams.x = restX
        buttonParams.y = restY
        windows.addView(button, buttonParams)
        shown = true
        applyFold()
    }

    /** M2's operate screen came to the front (true) or went away (false). */
    fun setAutoCollapsed(on: Boolean) {
        if (autoCollapsed == on) return
        autoCollapsed = on
        openedDuringAuto = false
        applyFold()
    }

    private fun openFromTab() {
        if (manualCollapsed) {
            manualCollapsed = false
            MacroPrefs.setCollapsed(context, false)
        }
        if (autoCollapsed) openedDuringAuto = true
        button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        applyFold()
    }

    /** Lays the button out open at its resting place, or as a tab on that edge. */
    private fun applyFold() {
        if (!shown) return
        val screen = screenSize()
        if (collapsed) {
            closeRadial()
            val onRight = restX + buttonSize / 2 >= screen.first / 2
            buttonParams.width = tabWidth
            buttonParams.height = tabHeight
            buttonParams.x = if (onRight) screen.first - tabWidth else 0
            buttonParams.y = FloatingMacroLogic.clampY(restY + (buttonSize - tabHeight) / 2, tabHeight, screen.second)
            button.contentDescription = context.getString(R.string.macro_tab_description)
        } else {
            buttonParams.width = buttonSize
            buttonParams.height = buttonSize
            buttonParams.x = restX
            buttonParams.y = restY
            button.contentDescription = context.getString(R.string.macro_button_description)
        }
        windows.updateViewLayout(button, buttonParams)
        paintButton()
        syncRecord()
    }

    /** The record key shows only with the open button. */
    private fun syncRecord() {
        val view = recordButton ?: return
        val want = shown && !collapsed
        if (want && !recordShown) {
            renderRecord(view, lastCamera)
            placeRecord()
            windows.addView(view, recordParams)
            recordShown = true
        } else if (!want && recordShown) {
            windows.removeView(view)
            recordShown = false
        }
    }

    fun remove() {
        zoom?.stop()
        closeRadial()
        if (shown) windows.removeView(button)
        if (recordShown) recordButton?.let { windows.removeView(it) }
        recordShown = false
        shown = false
    }

    /** Rotation or a fold: keep the button against an edge, with room for the fan. */
    fun onScreenChanged() {
        if (!shown) return
        closeRadial()
        val screen = screenSize()
        restX = FloatingMacroLogic.snapX(restX, buttonSize, screen.first)
        restY = restY(restY)
        applyFold()
    }

    fun render(tone: FloatingTone, camera: MacroCameraState, running: String?, note: String?, armed: ZoomArm?) {
        lastCamera = camera
        lastRunning = running
        lastNote = note
        lastTone = tone
        lastArmed = armed
        paintButton()
        if (radialShown) radial.update(items(), status())
        if (recordShown) recordButton?.let { renderRecord(it, camera) }
    }

    private fun paintButton() {
        val tone = lastTone
        val armed = lastArmed
        val style = styleFor(tone)
        if (collapsed) {
            // A thin tab in the button's colour, so recording or a failure still shows.
            val onRight = buttonParams.x > 0
            button.text = if (onRight) "‹" else "›"
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val r = dp(10).toFloat()
            button.background = GradientDrawable().apply {
                setColor(style.color)
                cornerRadii = if (onRight) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setStroke(dp(1), Color.argb(170, 255, 255, 255))
            }
            return
        }
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val zoomTone = tone == FloatingTone.ARMED || tone == FloatingTone.ZOOMING
        button.text = if (zoomTone && armed != null) FloatingMacroLogic.zoomBadge(armed) else context.getString(style.glyph)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(style.color)
            // An armed zoom gets an orange ring, so a tap's meaning is visible before it lands.
            if (tone == FloatingTone.ARMED) {
                setStroke(dp(4), Color.argb(255, 240, 150, 30))
            } else {
                setStroke(dp(2), Color.argb(200, 255, 255, 255))
            }
        }
    }

    private fun renderRecord(view: TextView, camera: MacroCameraState) {
        val recording = camera.live && camera.recording
        // Stays enabled offline so long-press still shows the build; the record action checks LIVE.
        view.text = context.getString(if (recording) R.string.macro_record_stop else R.string.macro_record_start)
        view.contentDescription = view.text
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    !camera.live -> Color.argb(150, 90, 90, 90)
                    recording -> Color.argb(235, 200, 30, 30)
                    else -> Color.argb(210, 40, 40, 40)
                },
            )
            setStroke(dp(2), if (recording) Color.WHITE else Color.argb(230, 220, 50, 50))
        }
    }

    /** Below the main button, or above it when there is no room. */
    private fun placeRecord() {
        val screenH = screenSize().second
        val left = buttonParams.x + (buttonSize - recordSize) / 2
        val below = buttonParams.y + buttonSize + recordGap
        recordParams.x = left
        recordParams.y = if (below + recordSize <= screenH) below else buttonParams.y - recordGap - recordSize
    }

    private fun followRecord() {
        val view = recordButton ?: return
        if (!recordShown) return
        placeRecord()
        windows.updateViewLayout(view, recordParams)
    }

    private fun items(): List<RadialNode> =
        FloatingMacroLogic.buildMenu(shownMenu, lastCamera, lastRunning, labels, zoom?.armed?.value, choices)

    private fun status(): String? = FloatingMacroLogic.blockReason(lastCamera, lastRunning) ?: lastNote

    private fun openRadial(tapMode: Boolean) {
        if (!shown || collapsed) return
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

    private fun closeRadial() {
        if (!radialShown) return
        windows.removeView(radial)
        radialParams.flags = radialParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        radialShown = false
    }

    /**
     * The end of a press or a tap: act on the leaf it ended on. A blocked preset is still sent,
     * so the runner says why; a greyed zoom entry (camera offline) does nothing.
     */
    private fun finish(node: RadialNode?) {
        closeRadial()
        when (val action = node?.action) {
            is RadialAction.Preset -> onRun(action.preset)
            RadialAction.Settings -> onOpenSettings()
            is RadialAction.Zoom -> if (node.enabled) zoom?.arm(action.zoomIn, action.level)
            RadialAction.ZoomDisarm -> zoom?.disarm()
            is RadialAction.Choose -> if (node.enabled) onChoose(action.preset, action.function, action.value)
            null -> Unit
        }
    }

    /** A tap on the button: run or stop an armed zoom; without one, open the menu to tap through. */
    private fun tap() {
        val z = zoom
        if (z != null && z.armed.value != null && !radialShownInTapMode()) {
            closeRadial()
            if (z.toggle()) button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        if (radialShownInTapMode()) closeRadial() else openRadial(tapMode = true)
    }

    private fun radialShownInTapMode() = radialShown && radial.tapMode

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
        /** This press stopped a running zoom; it does nothing else. */
        private var stoppedZoom = false
        private val lift = Runnable {
            if (travelled) return@Runnable
            moving = true
            closeRadial()
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (collapsed) {
                // The tab only opens: a still release is its click.
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY }
                    MotionEvent.ACTION_UP -> if (FloatingMacroLogic.isTap(event.rawX - downX, event.rawY - downY, slop * 3)) view.performClick()
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stoppedZoom = zoom?.running?.value == true
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
                    // With zoom armed a tap runs it, so the fan waits for a drag instead of flashing.
                    if (zoom?.armed?.value == null) openRadial(tapMode = false)
                    handler.postDelayed(lift, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (stoppedZoom) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!travelled && !FloatingMacroLogic.isTap(dx, dy, slop)) {
                        travelled = true
                        if (!moving) {
                            handler.removeCallbacks(lift)
                            openRadial(tapMode = false)
                        }
                    }
                    if (moving) {
                        buttonParams.x = startX + dx.toInt()
                        buttonParams.y = startY + dy.toInt()
                        windows.updateViewLayout(button, buttonParams)
                        followRecord()
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
                            finish(node)
                        }
                        // A still release is a click: the fan already open switches to tap mode.
                        else -> view.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(lift)
                    if (stoppedZoom) return true
                    if (moving) settle() else closeRadial()
                }
            }
            return true
        }

        private fun settle() {
            val screen = screenSize()
            val putAway = FloatingMacroLogic.draggedOffEdge(buttonParams.x, buttonSize, screen.first)
            restX = FloatingMacroLogic.snapX(buttonParams.x, buttonSize, screen.first)
            restY = restY(buttonParams.y)
            MacroPrefs.setButtonPosition(context, restX, restY)
            moving = false
            if (putAway) {
                manualCollapsed = true
                MacroPrefs.setCollapsed(context, true)
                Toast.makeText(context, R.string.macro_collapsed_hint, Toast.LENGTH_SHORT).show()
            }
            applyFold()
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
        FloatingTone.ARMED -> ToneStyle(Color.argb(225, 33, 99, 186), R.string.macro_glyph_ready)
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

    private fun buildLabel(): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return context.getString(R.string.macro_record_build, version)
    }

    private fun screenSize(): Pair<Int, Int> =
        context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
