package com.opencapture.openpocketcine.macro

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit

/** The floating button's own small preference file: on/off and where the operator left it. */
object MacroPrefs {
    private const val FILE = "openpocketcine.macro"
    private const val FLOATING_ENABLED = "floatingEnabled"
    private const val BUTTON_X = "buttonX"
    private const val BUTTON_Y = "buttonY"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** On by default: the button still needs the overlay permission before it appears. */
    fun floatingEnabled(context: Context): Boolean = prefs(context).getBoolean(FLOATING_ENABLED, true)

    fun setFloatingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(FLOATING_ENABLED, enabled) }
    }

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Last resting position in px, or null before the first drag. */
    fun buttonPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        val x = p.getInt(BUTTON_X, -1)
        val y = p.getInt(BUTTON_Y, -1)
        return if (x < 0 || y < 0) null else x to y
    }

    fun setButtonPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit { putInt(BUTTON_X, x).putInt(BUTTON_Y, y) }
    }
}
