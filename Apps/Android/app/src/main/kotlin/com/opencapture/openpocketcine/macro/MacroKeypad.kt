package com.opencapture.openpocketcine.macro

/**
 * Maps key codes from a Bluetooth macro keypad (HID keyboard mode) to presets. Pure Kotlin:
 * the activity passes `KeyEvent` fields in, so this tests without Android.
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
) {
    fun onKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val preset = bindings()[keyCode] ?: return false
        if (!isActive()) return false
        if (action == ACTION_DOWN && repeatCount == 0) run(preset)
        return true
    }

    companion object {
        /** `KeyEvent.ACTION_DOWN`; duplicated so this file stays Android-free. */
        const val ACTION_DOWN = 0
    }
}
