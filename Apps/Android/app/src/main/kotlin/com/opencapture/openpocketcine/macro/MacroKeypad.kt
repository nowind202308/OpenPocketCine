package com.opencapture.openpocketcine.macro

/**
 * Maps key codes from a Bluetooth macro keypad (HID keyboard mode) to commands. Pure Kotlin:
 * the activity passes `KeyEvent` fields in, so this tests without Android.
 *
 * The keypad only carries out what the phone has set up: preset keys run presets, and the zoom
 * key starts or stops the zoom armed in the floating menu.
 *
 * A bound key's press fires once (auto-repeat is ignored) and both its press and release are
 * consumed so they never reach the rest of the UI. Unbound keys, and every key while [isActive]
 * is false, pass through untouched.
 */
class MacroKeypad(
    /** Read on every key, so the bindings follow the connected camera. */
    private val bindings: () -> Map<Int, MacroPreset>,
    private val isActive: () -> Boolean,
    private val run: (MacroPreset) -> Unit,
    /** Key codes that start or stop the armed zoom. */
    private val zoomKeys: Set<Int> = emptySet(),
    private val toggleZoom: () -> Unit = {},
) {
    fun onKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val preset = bindings()[keyCode]
        val zoom = keyCode in zoomKeys
        if (preset == null && !zoom) return false
        if (!isActive()) return false
        if (action == ACTION_DOWN && repeatCount == 0) {
            if (preset != null) run(preset) else toggleZoom()
        }
        return true
    }

    companion object {
        /** `KeyEvent.ACTION_DOWN`; duplicated so this file stays Android-free. */
        const val ACTION_DOWN = 0
    }
}
