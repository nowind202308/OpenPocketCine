package com.opencapture.openpocketcine.macro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Where each sector of the radial menu sits. Angles are radians in screen space (0 = right,
 * clockwise because y grows downward), matching both `atan2(dy, dx)` and Canvas arcs.
 */
data class RadialLayout(
    val cx: Float,
    val cy: Float,
    val inner: Float,
    val outer: Float,
    /** Center angle of each sector, in menu order. */
    val angles: List<Double>,
    /** Angular width of one sector. */
    val sweep: Double,
    /** Which way the fan opens: 0 (right) from a left-edge button, PI from a right-edge one. */
    val facing: Double,
)

/**
 * Geometry of the radial menu, Android-free so it tests on the JVM. The button rests on a side
 * edge, so the menu is a fan opening toward the screen; a full ring would be half off screen.
 */
object RadialMenuLogic {
    /** The fan's total opening. Under 180° keeps the end sectors clear of the edge. */
    const val SPREAD_DEGREES = 160.0

    fun layout(cx: Float, cy: Float, screenWidth: Int, count: Int, inner: Float, outer: Float): RadialLayout {
        val facing = if (cx < screenWidth / 2f) 0.0 else PI
        val spread = Math.toRadians(SPREAD_DEGREES)
        val sweep = spread / max(1, count)
        val angles = (0 until count).map { facing - spread / 2 + sweep * (it + 0.5) }
        return RadialLayout(cx, cy, inner, outer, angles, sweep, facing)
    }

    /**
     * The sector under (x, y), or null for the center (cancel) or outside the fan. Past the
     * outer ring still counts: a quick flick overshoots, and direction is what it means.
     */
    fun hit(layout: RadialLayout, x: Float, y: Float): Int? {
        val dx = (x - layout.cx).toDouble()
        val dy = (y - layout.cy).toDouble()
        if (hypot(dx, dy) < layout.inner) return null
        val angle = atan2(dy, dx)
        val index = layout.angles.indexOfFirst { abs(angleDiff(angle, it)) <= layout.sweep / 2 }
        return index.takeIf { it >= 0 }
    }

    /** a - b folded into (-PI, PI]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * PI)
        if (d <= -PI) d += 2 * PI
        if (d > PI) d -= 2 * PI
        return d
    }

    /**
     * Resting height for a button of [size]: far enough from the top and bottom that the fan
     * (radius [outer] around the button center) stays on screen. Falls back to a plain clamp
     * on a screen too short for that.
     */
    fun restY(y: Int, size: Int, screenHeight: Int, outer: Int): Int {
        val min = outer - size / 2
        val max = screenHeight - outer - size / 2
        if (min > max) return FloatingMacroLogic.clampY(y, size, screenHeight)
        return y.coerceIn(min, max)
    }
}
