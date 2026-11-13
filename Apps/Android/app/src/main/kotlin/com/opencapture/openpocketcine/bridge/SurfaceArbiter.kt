package com.opencapture.openpocketcine.bridge

/**
 * Decides which output the live decoder renders to when the storyboard companion (M2) borrows
 * the picture. The app's own live view always wins while it has a surface; the borrowed one is
 * used only while the app's view is gone (the app is behind M2). Generic so it tests on the JVM.
 *
 * [apply] runs synchronously inside the call that changed the choice, so a dying surface is
 * swapped away before its owner's callback returns (a dead output wedges MediaCodec).
 */
class SurfaceArbiter<T : Any>(private val apply: (T?) -> Unit) {
    private var local: T? = null
    private var external: T? = null
    private var applied: T? = null

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

    @get:Synchronized
    val showingExternal: Boolean get() = external != null && applied === external

    private fun update() {
        val want = local ?: external
        if (want === applied) return
        applied = want
        apply(want)
    }
}
