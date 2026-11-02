package com.opencapture.openpocketcine.macro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** What a leaf of the radial menu does. */
sealed interface RadialAction {
    /** Runs a whole preset on release. */
    data class Preset(val preset: MacroPreset) : RadialAction

    data object Settings : RadialAction

    /**
     * Zooms while the finger rests on it (speed [level] 1..7); releasing in the lock zone past
     * its outer edge keeps zooming until the floating button is tapped.
     */
    data class Zoom(val zoomIn: Boolean, val level: Int) : RadialAction
}

/** One sector. A node with [children] opens the next ring; releasing on it does nothing. */
data class RadialNode(
    val badge: String,
    val label: String,
    val action: RadialAction? = null,
    val children: List<RadialNode> = emptyList(),
    val enabled: Boolean = true,
    val current: Boolean = false,
) {
    val isGroup: Boolean get() = children.isNotEmpty()
}

/** Radii of the three rings and the lock zone, in px; [RadialTracker.ringRadii] builds them. */
data class RingRadii(val bands: List<Pair<Float, Float>>, val lockStart: Float) {
    val dead: Float get() = bands.first().first
}

/** A sector as drawn: ring (0-based), angles in radians, radii in px, and whether it is selected. */
data class SectorShape(
    val ring: Int,
    val center: Double,
    val sweep: Double,
    val inner: Float,
    val outer: Float,
    val node: RadialNode,
    val selected: Boolean,
)

/**
 * Follows one finger through a radial menu of up to three rings, Android-free so it tests on
 * the JVM. Angles are screen-space radians (0 = right, clockwise), as from `atan2(dy, dx)`.
 *
 * Ring 1 is a fan opening toward the screen from the button's edge. A deeper ring appears for a
 * selected group, centred on its parent's direction. A ring's selection is sticky: moving out to
 * a deeper ring keeps the parent, so children may spread wider than their parent's sector.
 */
class RadialTracker(
    /** Replaced while open when enabled/current flags change; the shape stays the same. */
    var root: List<RadialNode>,
    val cx: Float,
    val cy: Float,
    /** 0 when the fan opens to the right (button on the left edge), PI when to the left. */
    val facing: Double,
    val radii: RingRadii,
    /** Smallest arc a child sector gets at its ring's middle, in px (about one fingertip). */
    private val minArc: Float,
) {
    /** Selected index on each ring, outermost last; empty in the dead zone or outside the fan. */
    var path: List<Int> = emptyList()
        private set

    /** The finger is past the outer edge of a selected zoom speed. */
    var inLockZone: Boolean = false
        private set

    /** The deepest selected node, or null. */
    val hovered: RadialNode?
        get() = nodeAt(path)

    /** Moves the finger to (x, y); true when the selection or the lock state changed. */
    fun move(x: Float, y: Float): Boolean {
        val before = path to inLockZone
        val dx = (x - cx).toDouble()
        val dy = (y - cy).toDouble()
        val r = hypot(dx, dy).toFloat()
        val angle = atan2(dy, dx)
        val ring = ringAt(r)
        path = when {
            r < radii.dead -> emptyList()
            ring == 0 -> listOfNotNull(pick(root, angles(0, root.size), angle))
            else -> {
                // A fast flick may skip ring 1 entirely: pick it by angle on the way out.
                if (path.isEmpty()) path = listOfNotNull(pick(root, angles(0, root.size), angle))
                deeper(ring, angle)
            }
        }
        val leaf = hovered?.action
        inLockZone = leaf is RadialAction.Zoom && r >= radii.lockStart
        return before != (path to inLockZone)
    }

    /**
     * The finger is on ring [ring] (1 or 2). Rings inside it keep their selection (picked by
     * angle only when a fast move skipped them); the finger's own ring follows the angle. A ring
     * is reachable only through a selected group on the ring before it.
     */
    private fun deeper(ring: Int, angle: Double): List<Int> {
        var p = path
        for (k in 1..ring) {
            if (p.size < k) return p
            val parent = nodeAt(p.take(k)) ?: return p.take(k - 1)
            if (!parent.isGroup) return p.take(k)
            if (k < ring && p.size > k) continue
            val child = pick(parent.children, angles(k, parent.children.size, p.take(k)), angle)
            p = if (child == null) p.take(k) else p.take(k) + child
        }
        return p.take(ring + 1)
    }

    /** Every sector currently shown, ring by ring. */
    fun sectors(): List<SectorShape> {
        val out = mutableListOf<SectorShape>()
        var nodes = root
        for (k in radii.bands.indices) {
            if (nodes.isEmpty()) break
            val (inner, outer) = radii.bands[k]
            val (centers, sweep) = angles(k, nodes.size)
            nodes.forEachIndexed { i, node ->
                out += SectorShape(k, centers[i], sweep, inner, outer, node, path.getOrNull(k) == i)
            }
            val sel = path.getOrNull(k) ?: break
            nodes = nodes[sel].children
        }
        return out
    }

    private fun nodeAt(p: List<Int>): RadialNode? {
        var nodes = root
        var node: RadialNode? = null
        for (i in p) {
            node = nodes.getOrNull(i) ?: return null
            nodes = node.children
        }
        return node
    }

    private fun ringAt(r: Float): Int {
        radii.bands.forEachIndexed { k, (_, outer) -> if (r < outer) return k }
        return radii.bands.lastIndex // past the last ring still counts as the last ring
    }

    private fun pick(nodes: List<RadialNode>, layout: Pair<List<Double>, Double>, angle: Double): Int? {
        val (centers, sweep) = layout
        return centers.indexOfFirst { abs(angleDiff(angle, it)) <= sweep / 2 }.takeIf { it >= 0 && it < nodes.size }
    }

    /** Sector centres and width for ring [k] holding [count] nodes, under the current path. */
    private fun angles(k: Int, count: Int, p: List<Int> = path): Pair<List<Double>, Double> {
        val n = maxOf(1, count)
        if (k == 0) {
            val sweep = SPREAD / n
            return List(count) { facing - SPREAD / 2 + sweep * (it + 0.5) } to sweep
        }
        // Centred on the parent's direction, kept inside the fan.
        val (inner, outer) = radii.bands[k]
        val mid = (inner + outer) / 2
        val sweep = min(SPREAD / n, maxOf((minArc / mid).toDouble(), Math.toRadians(MIN_CHILD_DEGREES)))
        val parentCenter = parentCenter(k, p)
        val half = sweep * n / 2
        val lo = facing - SPREAD / 2
        val hi = facing + SPREAD / 2
        val start = (parentCenter - half).coerceIn(lo, hi - sweep * n)
        return List(count) { start + sweep * (it + 0.5) } to sweep
    }

    private fun parentCenter(k: Int, p: List<Int>): Double {
        var nodes = root
        var center = facing
        for (ring in 0 until k) {
            val i = p.getOrNull(ring) ?: return center
            center = angles(ring, nodes.size, p).first[i]
            nodes = nodes[i].children
        }
        return center
    }

    companion object {
        /** The fan's opening: under 180° keeps the end sectors clear of the edge. */
        val SPREAD = Math.toRadians(160.0)
        private const val MIN_CHILD_DEGREES = 12.0

        /** a - b folded into (-PI, PI]. */
        fun angleDiff(a: Double, b: Double): Double {
            var d = (a - b) % (2 * PI)
            if (d <= -PI) d += 2 * PI
            if (d > PI) d -= 2 * PI
            return d
        }

        /**
         * Ring radii in px for a density and a scale (under 1 on short screens, so the third
         * ring fits). The lock zone starts a little past the last ring.
         */
        fun ringRadii(density: Float, scale: Float = 1f): RingRadii {
            fun px(dp: Int) = dp * density * scale
            return RingRadii(
                bands = listOf(px(44) to px(140), px(146) to px(214), px(220) to px(286)),
                lockStart = px(286 + 26),
            )
        }

        /** Room the full menu needs from the button centre, in dp, for [ringRadii] scale 1. */
        const val FULL_REACH_DP = 286 + 26 + 24
    }
}
