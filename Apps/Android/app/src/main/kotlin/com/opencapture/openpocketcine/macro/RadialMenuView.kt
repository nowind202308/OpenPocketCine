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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the radial menu (up to three rings) around the floating button and follows the finger.
 * All geometry and selection live in [RadialTracker]; this view draws its sectors.
 *
 * The view fills a full-screen overlay window. While the press that opened it is still down,
 * the window is not touchable and the button forwards raw coordinates to [track]; after a tap
 * the window turns touchable ([tapMode]) and the view takes touches itself.
 */
class RadialMenuView(context: Context) : View(context) {
    /** Tap mode: a release on a leaf or outside the menu; null node closes. */
    var onTapRelease: (RadialNode?) -> Unit = {}

    var tapMode = false

    private var nodes: List<RadialNode> = emptyList()
    private var status: String? = null
    private var anchorRawX = 0f
    private var anchorRawY = 0f
    private var tracker: RadialTracker? = null
    private var finger: Pair<Float, Float>? = null

    private val density = resources.displayMetrics.density

    /** Ring-1 outer radius at full size; the button's resting height keeps ring 1 on screen. */
    val ringOneOuter: Float get() = RadialTracker.ringRadii(density).bands.first().second

    val hovered: RadialNode? get() = tracker?.hovered

    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(19f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
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
    fun open(anchorX: Float, anchorY: Float, nodes: List<RadialNode>, status: String?) {
        anchorRawX = anchorX
        anchorRawY = anchorY
        tapMode = false
        finger = null
        tracker = null
        update(nodes, status)
    }

    /** Camera state changed while open: new enabled/current flags and status line, same selection. */
    fun update(nodes: List<RadialNode>, status: String?) {
        this.nodes = nodes
        this.status = status
        tracker?.root = nodes
        invalidate()
    }

    /** Moves the finger (screen coordinates); ticks once per newly selected sector. */
    fun track(rawX: Float, rawY: Float): RadialNode? {
        val t = currentTracker() ?: return null
        getLocationOnScreen(location)
        val x = rawX - location[0]
        val y = rawY - location[1]
        finger = x to y
        if (t.move(x, y) && t.hovered != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        return t.hovered
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!tapMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> track(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> {
                val node = track(event.rawX, event.rawY)
                performClick()
                // A group opens its ring and keeps the menu up; anything else is final.
                if (node?.isGroup != true) onTapRelease(node)
            }
            MotionEvent.ACTION_CANCEL -> onTapRelease(null)
        }
        return true
    }

    /** Selection happens in [onTouchEvent]; a click itself (e.g. from TalkBack) just closes. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tracker = null
    }

    /** Needs the window laid out: the first moves after the press may arrive before that. */
    private fun currentTracker(): RadialTracker? {
        tracker?.let { return it }
        if (width == 0 || nodes.isEmpty()) return null
        getLocationOnScreen(location)
        val cx = anchorRawX - location[0]
        val cy = anchorRawY - location[1]
        val facing = if (cx < width / 2f) 0.0 else PI
        // Shrink on short screens so the third ring fits beside the button.
        val reach = RadialTracker.FULL_REACH_DP * density
        val across = if (facing == 0.0) width - cx else cx
        val vertical = max(cy, height - cy)
        val scale = (min(across, vertical) / reach).coerceIn(0.6f, 1f)
        return RadialTracker(nodes, cx, cy, facing, RadialTracker.ringRadii(density, scale), 52 * density)
            .also { tracker = it }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = currentTracker() ?: return
        val gap = Math.toRadians(1.2)
        for (s in t.sectors()) {
            outerRect.set(t.cx - s.outer, t.cy - s.outer, t.cx + s.outer, t.cy + s.outer)
            innerRect.set(t.cx - s.inner, t.cy - s.inner, t.cx + s.inner, t.cy + s.inner)
            val start = Math.toDegrees(s.center - s.sweep / 2 + gap).toFloat()
            val sweep = Math.toDegrees(s.sweep - 2 * gap).toFloat()
            path.reset()
            path.arcTo(outerRect, start, sweep, true)
            path.arcTo(innerRect, start + sweep, -sweep)
            path.close()
            wedge.color = when {
                s.selected && s.node.enabled -> HOVER
                s.selected -> HOVER_DISABLED
                s.ring > 0 -> SECTOR_DEEP
                else -> SECTOR
            }
            if (!s.node.enabled) wedge.alpha = (wedge.alpha * 0.55f).toInt()
            canvas.drawPath(path, wedge)
            if (s.node.current) {
                ring.color = CURRENT
                ring.strokeWidth = 3 * density
                canvas.drawPath(path, ring)
            }
            drawLabel(canvas, t, s)
        }
        finger?.let { (fx, fy) -> if (!tapMode) canvas.drawLine(t.cx, t.cy, fx, fy, trail) }
        drawStatus(canvas, t)
    }

    private fun drawLabel(canvas: Canvas, t: RadialTracker, s: SectorShape) {
        val mid = (s.inner + s.outer) / 2
        val x = t.cx + mid * cos(s.center).toFloat()
        val y = t.cy + mid * sin(s.center).toFloat()
        val lines = wrap(s.node.label, (mid * s.sweep * 0.9).toFloat())
        val alpha = if (s.node.enabled) 255 else 120
        badgePaint.alpha = alpha
        namePaint.alpha = alpha
        val lineHeight = namePaint.fontSpacing
        val block = badgePaint.fontSpacing + lineHeight * lines.size
        var baseline = y - block / 2 - badgePaint.ascent()
        canvas.drawText(s.node.badge, x, baseline, badgePaint)
        baseline += badgePaint.descent() - namePaint.ascent()
        lines.forEach { line ->
            canvas.drawText(line, x, baseline, namePaint)
            baseline += lineHeight
        }
    }

    /** Words onto lines that fit the sector's arc, at most three; CJK labels break per character. */
    private fun wrap(text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = if (' ' in text) text.split(' ') else text.map { it.toString() }
        val joiner = if (' ' in text) " " else ""
        val lines = mutableListOf<String>()
        var line = ""
        for (token in tokens) {
            val candidate = if (line.isEmpty()) token else line + joiner + token
            if (line.isNotEmpty() && namePaint.measureText(candidate) > maxWidth) {
                lines += line
                line = token
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines.take(3)
    }

    /** Why presets are greyed out, or the last result, beside the fan on the screen side. */
    private fun drawStatus(canvas: Canvas, t: RadialTracker) {
        val text = status ?: return
        val pad = 10 * density
        val textWidth = statusPaint.measureText(text)
        val ringOne = t.radii.bands.first().second
        val towardRight = cos(t.facing) > 0
        val left = if (towardRight) t.cx + ringOne * 0.25f else t.cx - ringOne * 0.25f - textWidth - 2 * pad
        val top = t.cy + ringOne + 12 * density
        canvas.drawRoundRect(left, top, left + textWidth + 2 * pad, top + statusPaint.fontSpacing + 2 * pad,
            12 * density, 12 * density, statusBack)
        canvas.drawText(text, left + pad, top + pad - statusPaint.ascent(), statusPaint)
    }

    private companion object {
        val SECTOR = Color.argb(225, 32, 32, 40)
        val SECTOR_DEEP = Color.argb(225, 44, 44, 56)
        val HOVER = Color.argb(240, 33, 110, 210)
        val HOVER_DISABLED = Color.argb(230, 90, 90, 100)
        val CURRENT = Color.argb(255, 70, 190, 100)
    }
}
