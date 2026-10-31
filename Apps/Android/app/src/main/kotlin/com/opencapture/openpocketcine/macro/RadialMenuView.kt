package com.opencapture.openpocketcine.macro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** One sector: a short key badge, a name wrapped onto up to three lines, and its state. */
data class RadialItem(
    val badge: String,
    val name: String,
    val enabled: Boolean,
    val current: Boolean,
)

/**
 * Draws the fan-shaped menu around the floating button and tracks the finger over it.
 *
 * The view fills a full-screen overlay window. While the press that opened it is still down,
 * the window is not touchable and the button forwards raw coordinates to [track]; after a tap
 * the window turns touchable ([tapMode]) and the view takes touches itself.
 */
class RadialMenuView(context: Context) : View(context) {
    /** A sector index picked by a tap, or null for the center or outside (close). */
    var onPick: (Int?) -> Unit = {}

    var tapMode = false

    private var items: List<RadialItem> = emptyList()
    private var status: String? = null
    private var anchorRawX = 0f
    private var anchorRawY = 0f
    private var layout: RadialLayout? = null
    private var finger: Pair<Float, Float>? = null
    private var hovered: Int? = null

    private val density = resources.displayMetrics.density
    val inner = 44 * density
    val outer = 160 * density

    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(20f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
    }
    private val statusBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 20, 20, 24) }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.argb(230, 235, 70, 60)
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 7 * density), 0f)
    }
    private val path = Path()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val location = IntArray(2)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /** Opens around the button center (screen coordinates). */
    fun open(anchorX: Float, anchorY: Float, items: List<RadialItem>, status: String?) {
        anchorRawX = anchorX
        anchorRawY = anchorY
        tapMode = false
        finger = null
        hovered = null
        layout = null
        update(items, status)
    }

    /** Camera state changed while open: redraw enabled/current states and the status line. */
    fun update(items: List<RadialItem>, status: String?) {
        this.items = items
        this.status = status
        invalidate()
    }

    /** Moves the trail to the finger (screen coordinates); ticks once per newly hovered sector. */
    fun track(rawX: Float, rawY: Float): Int? {
        val l = currentLayout() ?: return null
        getLocationOnScreen(location)
        val x = rawX - location[0]
        val y = rawY - location[1]
        finger = x to y
        val hit = RadialMenuLogic.hit(l, x, y)
        if (hit != hovered) {
            hovered = hit
            if (hit != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
        return hit
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!tapMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> track(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> {
                val picked = track(event.rawX, event.rawY)
                performClick()
                onPick(picked)
            }
            MotionEvent.ACTION_CANCEL -> onPick(null)
        }
        return true
    }

    /** Picking happens in [onTouchEvent]; a click itself (e.g. from TalkBack) just closes. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layout = null
    }

    /** Needs the window laid out: the first moves after the press may arrive before that. */
    private fun currentLayout(): RadialLayout? {
        layout?.let { return it }
        if (width == 0 || items.isEmpty()) return null
        getLocationOnScreen(location)
        return RadialMenuLogic.layout(
            anchorRawX - location[0], anchorRawY - location[1], width, items.size, inner, outer,
        ).also { layout = it }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = currentLayout() ?: return
        outerRect.set(l.cx - l.outer, l.cy - l.outer, l.cx + l.outer, l.cy + l.outer)
        innerRect.set(l.cx - l.inner, l.cy - l.inner, l.cx + l.inner, l.cy + l.inner)
        val gap = Math.toRadians(1.5)
        items.forEachIndexed { i, item ->
            val start = Math.toDegrees(l.angles[i] - l.sweep / 2 + gap).toFloat()
            val sweep = Math.toDegrees(l.sweep - 2 * gap).toFloat()
            path.reset()
            path.arcTo(outerRect, start, sweep, true)
            path.arcTo(innerRect, start + sweep, -sweep)
            path.close()
            wedge.color = when {
                i == hovered && item.enabled -> HOVER
                i == hovered -> HOVER_DISABLED
                else -> SECTOR
            }
            wedge.alpha = if (item.enabled) wedge.alpha else (wedge.alpha * 0.55f).toInt()
            canvas.drawPath(path, wedge)
            if (item.current) {
                ring.color = CURRENT
                ring.strokeWidth = 3 * density
                canvas.drawPath(path, ring)
            }
            drawLabel(canvas, l, i, item)
        }
        finger?.let { (fx, fy) ->
            if (!tapMode) canvas.drawLine(l.cx, l.cy, fx, fy, trail)
        }
        drawStatus(canvas, l)
    }

    private fun drawLabel(canvas: Canvas, l: RadialLayout, i: Int, item: RadialItem) {
        val mid = (l.inner + l.outer) / 2
        val x = l.cx + mid * cos(l.angles[i]).toFloat()
        val y = l.cy + mid * sin(l.angles[i]).toFloat()
        val maxWidth = (mid * l.sweep * 0.9).toFloat()
        val lines = wrap(item.name, maxWidth)
        val alpha = if (item.enabled) 255 else 120
        badgePaint.alpha = alpha
        namePaint.alpha = alpha
        val lineHeight = namePaint.fontSpacing
        val block = badgePaint.fontSpacing + lineHeight * lines.size
        var baseline = y - block / 2 - badgePaint.ascent()
        canvas.drawText(item.badge, x, baseline, badgePaint)
        baseline += badgePaint.descent() - namePaint.ascent()
        lines.forEach { line ->
            canvas.drawText(line, x, baseline, namePaint)
            baseline += lineHeight
        }
    }

    /** Words onto lines that fit the sector's arc, at most three. */
    private fun wrap(text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && namePaint.measureText(candidate) > maxWidth) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines.take(3)
    }

    /** Why presets are greyed out, or the last result, beside the fan on the screen side. */
    private fun drawStatus(canvas: Canvas, l: RadialLayout) {
        val text = status ?: return
        val pad = 10 * density
        val textWidth = statusPaint.measureText(text)
        val towardRight = cos(l.facing) > 0
        val left = if (towardRight) l.cx + l.outer + 12 * density else l.cx - l.outer - 12 * density - textWidth - 2 * pad
        val top = l.cy - statusPaint.fontSpacing / 2 - pad
        canvas.drawRoundRect(left, top, left + textWidth + 2 * pad, top + statusPaint.fontSpacing + 2 * pad,
            12 * density, 12 * density, statusBack)
        canvas.drawText(text, left + pad, top + pad - statusPaint.ascent(), statusPaint)
    }

    private companion object {
        val SECTOR = Color.argb(225, 32, 32, 40)
        val HOVER = Color.argb(240, 33, 110, 210)
        val HOVER_DISABLED = Color.argb(230, 90, 90, 100)
        val CURRENT = Color.argb(255, 70, 190, 100)
    }
}
