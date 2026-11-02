package com.opencapture.openpocketcine.macro

/** Where the floating button may rest, Android-free so it tests on the JVM. */
object RadialMenuLogic {
    /**
     * Resting height for a button of [size]: far enough from the top and bottom that ring 1
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
