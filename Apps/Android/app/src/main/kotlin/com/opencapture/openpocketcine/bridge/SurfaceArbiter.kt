package com.opencapture.openpocketcine.bridge

/**
 * Decides which output the live decoder renders to when the storyboard companion (M2) borrows
 * the picture. While this app is in the foreground its own live view wins; once it is not (M2 in
 * front), the borrowed surface wins even if the app's view still holds a surface (the live view
 * can keep its ImageReader alive in the background). Generic so it tests on the JVM.
 *
 * [apply] runs synchronously inside the call that changed the choice, so a dying surface is
 * swapped away before its owner's callback returns (a dead output wedges MediaCodec).
 */
class SurfaceArbiter<T : Any>(private val apply: (T?) -> Unit) {
    private var local: T? = null
    private var external: T? = null
    private var applied: T? = null
    private var foreground = true

    /** The app's own live view surface (null when it is destroyed). */
    @Synchronized
    fun setLocal(surface: T?) {
        local = surface
        update()
    }

    /** M2's borrowed surface (null when M2 lets go or dies). Returns the surface now rendered to. */
    @Synchronized
    fun setExternal(surface: T?): T? {
        external = surface
        update()
        return applied
    }

    /** This app's activity is resumed (its own view is what the operator sees). */
    @Synchronized
    fun setForeground(inFront: Boolean) {
        foreground = inFront
        update()
    }

    /** For diagnostics: which output is applied now. */
    @Synchronized
    fun describe(): String = when {
        applied == null -> "none"
        applied === external -> "external"
        else -> "local"
    } + (if (foreground) " fg" else " bg")

    @get:Synchronized
    val hasExternal: Boolean get() = external != null

    @get:Synchronized
    val showingExternal: Boolean get() = external != null && applied === external

    private fun update() {
        val want = if (foreground) local ?: external else external ?: local
        if (want === applied) return
        applied = want
        apply(want)
    }
}
