package com.opencapture.openpocketcine.macro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.getSystemService
import com.opencapture.openpocketcine.R

/**
 * The floating button and its menu, drawn over every app with plain views (no Compose host
 * needed inside a service). Sized for gloved hands: a 64 dp button and 60 dp menu rows.
 * All decisions come from [FloatingMacroLogic]; this class only draws and forwards touches.
 */
class FloatingMacroOverlay(
    private val context: Context,
    private val menu: List<MacroMenuEntry>,
    private val onRun: (MacroPreset) -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    private val windows = checkNotNull(context.getSystemService<WindowManager>())
    private val density = context.resources.displayMetrics.density
    private val buttonSize = dp(64)
    private val gap = dp(8)

    private val button = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        contentDescription = context.getString(R.string.macro_button_description)
        elevation = dp(6).toFloat()
    }
    private val buttonParams = overlayParams(buttonSize, buttonSize, focusOutside = false)

    private val header = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(12), dp(16), dp(8))
    }
    private val rows: List<TextView> = menu.map { entry ->
        TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(60)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(ROW_COLOR, dp(10))
            setOnClickListener {
                closeMenu()
                onRun(entry.preset)
            }
        }
    }
    // ACTION_OUTSIDE only closes the menu; it is never a click, so there is no performClick to call.
    @SuppressLint("ClickableViewAccessibility")
    private val menuView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL_COLOR, dp(16))
        setPadding(dp(8), dp(4), dp(8), dp(8))
        addView(header)
        rows.forEach { row ->
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) })
        }
        addView(footer(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
        // Touching anywhere else closes the menu, so it never sits over the frame by accident.
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) closeMenu()
            false
        }
    }
    private val menuWidth = dp(280)
    private val menuParams = overlayParams(menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, focusOutside = true)
    private var menuShown = false
    private var shown = false

    private var lastCamera = MacroCameraState()
    private var lastRunning: String? = null
    private var lastNote: String? = null

    init {
        button.setOnClickListener { if (menuShown) closeMenu() else openMenu() }
        button.setOnTouchListener(DragListener())
    }

    fun show() {
        if (shown) return
        val screen = screenSize()
        val saved = MacroPrefs.buttonPosition(context)
        buttonParams.x = saved?.first ?: FloatingMacroLogic.snapX(screen.first, buttonSize, screen.first)
        buttonParams.y = FloatingMacroLogic.clampY(saved?.second ?: (screen.second / 3), buttonSize, screen.second)
        windows.addView(button, buttonParams)
        shown = true
    }

    fun remove() {
        closeMenu()
        if (shown) windows.removeView(button)
        shown = false
    }

    /** Rotation or a fold: keep the button against an edge and on screen. */
    fun onScreenChanged() {
        if (!shown) return
        closeMenu()
        val screen = screenSize()
        buttonParams.x = FloatingMacroLogic.snapX(buttonParams.x, buttonSize, screen.first)
        buttonParams.y = FloatingMacroLogic.clampY(buttonParams.y, buttonSize, screen.second)
        windows.updateViewLayout(button, buttonParams)
    }

    fun render(tone: FloatingTone, camera: MacroCameraState, running: String?, note: String?) {
        lastCamera = camera
        lastRunning = running
        lastNote = note
        val style = styleFor(tone)
        button.text = context.getString(style.glyph)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(style.color)
            setStroke(dp(2), Color.argb(200, 255, 255, 255))
        }
        if (menuShown) renderMenu()
    }

    private fun renderMenu() {
        val block = FloatingMacroLogic.blockReason(lastCamera, lastRunning)
        header.text = block ?: lastNote ?: context.getString(R.string.macro_menu_hint)
        menu.forEachIndexed { i, entry ->
            val row = rows[i]
            val current = FloatingMacroLogic.isCurrent(entry.preset, lastCamera)
            row.text = context.getString(
                if (current) R.string.macro_menu_row_current else R.string.macro_menu_row,
                entry.key, entry.preset.name,
            )
            row.isEnabled = block == null
            row.alpha = if (block == null) 1f else 0.4f
        }
    }

    private fun openMenu() {
        if (menuShown || !shown) return
        renderMenu()
        val screen = screenSize()
        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.second, View.MeasureSpec.AT_MOST),
        )
        menuParams.x = FloatingMacroLogic.menuX(buttonParams.x, buttonSize, menuWidth, screen.first)
        menuParams.y = FloatingMacroLogic.menuY(buttonParams.y, buttonSize, menuView.measuredHeight, screen.second, gap)
        windows.addView(menuView, menuParams)
        menuShown = true
    }

    private fun closeMenu() {
        if (!menuShown) return
        windows.removeView(menuView)
        menuShown = false
    }

    private fun footer(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(footerButton(R.string.macro_menu_settings) { closeMenu(); onOpenSettings() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        addView(footerButton(R.string.macro_menu_close) { closeMenu() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun footerButton(label: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        text = context.getString(label)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        background = rounded(FOOTER_COLOR, dp(10))
        setOnClickListener { onClick() }
    }

    /** Drag moves the button; a release within touch slop is a click (so TalkBack and taps agree). */
    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = buttonParams.x; startY = buttonParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && !FloatingMacroLogic.isTap(dx, dy, slop)) {
                        dragging = true
                        closeMenu()
                    }
                    if (dragging) {
                        buttonParams.x = startX + dx.toInt()
                        buttonParams.y = startY + dy.toInt()
                        windows.updateViewLayout(button, buttonParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.performClick()
                    } else {
                        val screen = screenSize()
                        buttonParams.x = FloatingMacroLogic.snapX(buttonParams.x, buttonSize, screen.first)
                        buttonParams.y = FloatingMacroLogic.clampY(buttonParams.y, buttonSize, screen.second)
                        windows.updateViewLayout(button, buttonParams)
                        MacroPrefs.setButtonPosition(context, buttonParams.x, buttonParams.y)
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    private class ToneStyle(val color: Int, val glyph: Int)

    private fun styleFor(tone: FloatingTone): ToneStyle = when (tone) {
        FloatingTone.OFFLINE -> ToneStyle(Color.argb(170, 90, 90, 90), R.string.macro_glyph_offline)
        FloatingTone.READY -> ToneStyle(Color.argb(220, 33, 99, 186), R.string.macro_glyph_ready)
        FloatingTone.RECORDING -> ToneStyle(Color.argb(220, 150, 40, 40), R.string.macro_glyph_recording)
        FloatingTone.RUNNING -> ToneStyle(Color.argb(230, 214, 140, 20), R.string.macro_glyph_running)
        FloatingTone.DONE -> ToneStyle(Color.argb(230, 40, 150, 70), R.string.macro_glyph_done)
        FloatingTone.FAILED -> ToneStyle(Color.argb(240, 200, 40, 40), R.string.macro_glyph_failed)
    }

    private fun overlayParams(width: Int, height: Int, focusOutside: Boolean) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (focusOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun screenSize(): Pair<Int, Int> =
        context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        val PANEL_COLOR = Color.argb(235, 20, 20, 24)
        val ROW_COLOR = Color.argb(255, 44, 44, 52)
        val FOOTER_COLOR = Color.argb(255, 32, 32, 38)
    }
}
