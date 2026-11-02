package com.opencapture.openpocketcine.macro

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** One row of the floating menu: the preset and the keypad key that also runs it. */
data class MacroMenuEntry(val key: String, val preset: MacroPreset)

/**
 * What the activity lends the floating button while it owns a camera session: the menu, the
 * camera facts, whether a run is going, and how to start one. Every trigger (keypad, floating
 * menu) goes through the same [run], so the runner's safety checks exist once.
 */
class MacroAttachment(
    /** Read each time the menu opens, so it follows the connected camera. */
    val menu: () -> List<MacroMenuEntry>,
    val camera: StateFlow<MacroCameraState>,
    val running: StateFlow<String?>,
    /** Continuous zoom for the 縮放 ring; null where the camera cannot zoom. */
    val zoom: ZoomDriver? = null,
    /** What the operator picked for a preset in the menu (ISO, EV, frame rate). */
    val choices: (MacroPreset) -> MacroChoices = { MacroChoices() },
    private val remember: (MacroPreset, MacroChoices) -> Unit = { _, _ -> },
    /** Runs a preset with its remembered choices folded in. */
    private val start: (MacroPreset) -> Boolean,
) {
    fun run(preset: MacroPreset): Boolean = start(preset)

    /** A ring-3 pick: remember it for [preset] (null forgets it), then apply the preset at once. */
    fun choose(preset: MacroPreset, function: MacroFunction, value: Int?): Boolean {
        remember(preset, choices(preset).with(function, value))
        return run(preset)
    }
}

/**
 * The link between the activity, which owns the session and the runner, and the floating-button
 * service. Pure Kotlin so it tests on the JVM; the app uses the process-wide [MacroHub].
 */
open class MacroLink {
    private val _attachment = MutableStateFlow<MacroAttachment?>(null)
    private val _results = MutableSharedFlow<MacroResult>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Null while no activity holds a session: the service stops itself then. */
    val attachment: StateFlow<MacroAttachment?> = _attachment.asStateFlow()

    /** Every finished run, whichever trigger started it. */
    val results: SharedFlow<MacroResult> = _results.asSharedFlow()

    fun attach(attachment: MacroAttachment) {
        _attachment.value = attachment
    }

    /** Only the current owner can detach, so a recreated activity is never cut off by its predecessor. */
    fun detach(attachment: MacroAttachment) {
        _attachment.compareAndSet(attachment, null)
    }

    fun report(result: MacroResult) {
        _results.tryEmit(result)
    }
}

object MacroHub : MacroLink() {
    /** The Bluetooth control probe, lent by the activity that owns the session. */
    @Volatile
    var bleProbe: BleProbeHost? = null
}
