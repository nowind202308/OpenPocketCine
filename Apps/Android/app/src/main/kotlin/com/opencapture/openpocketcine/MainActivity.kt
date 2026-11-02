package com.opencapture.openpocketcine

import com.opencapture.opencine.appkit.MonitorRemoteShutter
import com.opencapture.opencine.cameracontrols.LocalMonitorRemoteShutter
import com.opencapture.opencine.cameracontrols.LocalMonitorDeliveryCoordinator
import com.opencapture.opencine.cameracontrols.MonitorOperatorDeliveryChrome
import com.opencapture.opencine.cameracontrols.rememberMonitorDeliveryCoordinator
import com.opencapture.openpocketcine.media.PocketMediaShare
import com.opencapture.opencine.vocabulary.MediaHTTP
import com.opencapture.opencine.appkit.DiscoveryRestart
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.opencapture.opencine.appkit.MonitorActivityLaunch
import com.opencapture.opencine.appkit.MonitorAppRoot
import com.opencapture.opencine.appkit.MonitorLaunchBrand
import com.opencapture.opencine.appkit.MonitorReportsOffer
import com.opencapture.opencine.appkit.retainedMonitorRoot
import androidx.compose.ui.graphics.Color
import com.opencapture.opencine.appkit.applyMonitorSystemBars
import com.opencapture.opencine.appkit.LocalOperatorHaptics
import com.opencapture.opencine.appkit.rememberOperatorHaptics
import com.opencapture.opencine.cameracontrols.MonitorLegalDocumentScreen
import com.opencapture.opencine.cameracontrols.MonitorLegalPage
import com.opencapture.opencine.runtime.MonitorHDRDisplay
import com.opencapture.opencine.vocabulary.ConnectionPhase
import com.opencapture.openpocketcine.diagnostics.AppDiagnostics
import com.opencapture.openpocketcine.macro.FloatingMacroLogic
import com.opencapture.openpocketcine.macro.FloatingMacroService
import com.opencapture.openpocketcine.macro.MacroAttachment
import com.opencapture.openpocketcine.macro.MacroChoiceLogic
import com.opencapture.openpocketcine.macro.MacroHub
import com.opencapture.openpocketcine.macro.MacroKeypad
import com.opencapture.openpocketcine.macro.MacroPrefs
import com.opencapture.openpocketcine.macro.MacroPreset
import com.opencapture.openpocketcine.macro.MacroRunner
import com.opencapture.openpocketcine.macro.MacroSettingsActivity
import com.opencapture.openpocketcine.macro.PocketPresets
import com.opencapture.openpocketcine.macro.SessionBleProbe
import com.opencapture.openpocketcine.macro.SessionMacroCamera
import com.opencapture.openpocketcine.macro.SessionZoomPort
import com.opencapture.openpocketcine.macro.ZoomDriver
import com.opencapture.openpocketcine.macro.note
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.opencapture.openpocketcine.diagnostics.DiagnosticCenter
import com.opencapture.openpocketcine.multiview.MultiviewQualificationPolicy
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencapture.opencine.appkit.AppShell
import com.opencapture.opencine.appkit.ShellBrand
import com.opencapture.opencine.appkit.ShellActions
import com.opencapture.opencine.appkit.PairingWizard
import com.opencapture.opencine.platform.LocalVPNProbe
import com.opencapture.openpocketcine.diagnostics.ManualProblemReportDialog
import com.opencapture.opencine.appkit.DiscoveryPermissions
import com.opencapture.openpocketcine.pairing.PairingCallouts
import com.opencapture.opencine.appkit.PermissionGate
import com.opencapture.openpocketcine.pairing.rememberPermissionGate
import com.opencapture.openpocketcine.pairing.startupBackdrop

open class MainActivity : ComponentActivity() {
    private val launch = MonitorActivityLaunch()
    internal lateinit var model: AppModel
        private set
    /** The media-button remote shutter (A2-32); the shared live surface arms it while the monitor is in front. */
    private lateinit var remoteShutter: MonitorRemoteShutter
    /** Bluetooth macro keypad: one key applies a whole preset (lens, color, exposure, ISO). */
    private val macroScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var macroKeypad: MacroKeypad
    /** What this activity lends the floating button (B plan); detached on destroy. */
    private lateinit var macroAttachment: MacroAttachment
    /** Bluetooth control probe for the settings page: can a BLE-only controller change settings? */
    private lateinit var bleProbe: SessionBleProbe
    /** Continuous zoom from the floating menu's 縮放 ring. */
    private lateinit var macroZoom: ZoomDriver
    /** The overlay-permission hint shows once per activity, not on every reconnect. */
    private var macroPermissionHinted = false
    var hideSystemNavigation = false
    var playbackHidesSystemNavigation = false

    fun updateSystemBars() {
        applyMonitorSystemBars(window, hideSystemNavigation || playbackHidesSystemNavigation)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !launch.firstFrameDrawn }
        super.onCreate(savedInstanceState)
        // One model per task: a recreated activity keeps the session; finishing closes it.
        model = retainedMonitorRoot { createModel().also(AppModel::prepareStartup) }
        model.gimbalGamepad.ensureListening(this, model)
        remoteShutter = MonitorRemoteShutter(applicationContext, "OpenPocketCineRemoteShutter")
        val macroCamera = SessionMacroCamera(model, macroScope)
        val macroRunner = MacroRunner(
            camera = macroCamera,
            scope = macroScope,
            onResult = {
                model.session.presentControlNote(it.note())
                MacroHub.report(it)
            },
        )
        macroZoom = ZoomDriver(SessionZoomPort(model), macroScope)
        // Every trigger runs a preset with the ISO / EV / frame rate picked for it in the menu.
        val macroChoices = { preset: MacroPreset -> MacroPrefs.choices(this, preset.name) }
        val runMacro = { preset: MacroPreset ->
            macroRunner.run(MacroChoiceLogic.apply(preset, macroChoices(preset)))
        }
        macroKeypad = MacroKeypad(
            bindings = { currentPresets().bindings },
            isActive = { model.session.phase == ConnectionPhase.LIVE },
            run = { runMacro(it) },
            zoomKeys = setOf(KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_F10),
            toggleZoom = {
                if (!macroZoom.toggle()) model.session.presentControlNote(getString(R.string.macro_zoom_not_armed))
            },
        )
        // Keypad and floating menu share this runner, so only one preset is ever in flight.
        macroAttachment = MacroAttachment(
            menu = { currentPresets().menu },
            camera = macroCamera.state,
            running = macroRunner.running,
            zoom = macroZoom,
            choices = macroChoices,
            remember = { preset, choices -> MacroPrefs.setChoices(this, preset.name, choices) },
            start = runMacro,
        )
        // A dropped link ends any floating zoom, latched or held.
        macroScope.launch {
            model.session.phaseFlow.collect { if (it != ConnectionPhase.LIVE) macroZoom.stop() }
        }
        MacroHub.attach(macroAttachment)
        bleProbe = SessionBleProbe(model, macroScope) { macroRunner.isRunning }
        MacroHub.bleProbe = bleProbe
        FloatingMacroService.dismissed = false
        macroScope.launch { model.session.phaseFlow.collect { updateFloatingMacro() } }
        publishMacroShortcut()
        launch.setContent(this) {
            OpenPocketCineTheme {
                val haptics = rememberOperatorHaptics { model.hapticsEnabled }
                // One app-scoped delivery owner: a run outlives the media screen that began it.
                val delivery = rememberMonitorDeliveryCoordinator()
                CompositionLocalProvider(LocalOperatorHaptics provides haptics, LocalMonitorDeliveryCoordinator provides delivery,
                    LocalMonitorRemoteShutter provides remoteShutter) {
                    OpenPocketCineApp(model)
                    Overlay(model)
                    MonitorOperatorDeliveryChrome(delivery, PocketMediaShare, { MediaHTTP.playbackMIMEType(it.name) })
                }
            }
        }
    }

    /** The activity's root model; the debug review activity scripts its camera port. */
    protected open fun createModel(): AppModel {
        val localReview = MonitorReview.decode(intent)
        // The staged review seeds its saved cameras before the model reads the store.
        if (localReview != null) MonitorReview.prepare(applicationContext, localReview)
        return AppModel(applicationContext, discovers = localReview == null, reviewLaunch = localReview).also { model ->
            if (localReview != null) model.launchReview { MonitorReview.start(applicationContext, model, localReview) }
        }
    }

    /** Drawn over the app; empty outside the debug review activity. */
    @Composable
    protected open fun Overlay(model: AppModel) {}

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        updateSystemBars()
    }

    override fun onPause() {
        if (::remoteShutter.isInitialized) remoteShutter.disarm()
        if (::model.isInitialized) model.shell.setSceneActive(false)
        AppDiagnostics.manualReport.setForeground(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        AppDiagnostics.manualReport.setForeground(true)
        if (::model.isInitialized) {
            model.shell.setSceneActive(true)
            model.gimbalGamepad.ensureListening(this, model)
        }
        // Returning from the overlay-permission screen lands here.
        if (::macroAttachment.isInitialized) updateFloatingMacro()
    }

    /**
     * Starts the floating macro service while this activity is in front with a live camera
     * (Android refuses a foreground-service start from the background), and stops it when it is
     * turned off or its permission is gone. A dropped link alone leaves it running, greyed out.
     */
    private fun updateFloatingMacro() {
        val enabled = MacroPrefs.floatingEnabled(this) && !FloatingMacroService.dismissed
        val granted = MacroPrefs.overlayGranted(this)
        val live = model.session.phase == ConnectionPhase.LIVE
        if (FloatingMacroLogic.shouldStop(enabled, granted, attached = true)) {
            FloatingMacroService.stop(this)
            if (enabled && !granted && live && !macroPermissionHinted) {
                macroPermissionHinted = true
                model.session.presentControlNote(getString(R.string.macro_permission_hint))
            }
            return
        }
        val inFront = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (inFront && FloatingMacroLogic.shouldStart(enabled, granted, attached = true, live = live)) {
            FloatingMacroService.start(this)
        }
    }

    /** Presets for the connected body (BLE name and model name both say "Pocket 3" on one). */
    private fun currentPresets() = PocketPresets.forCamera(
        model.session.connectedCamera?.let { "${it.name} ${it.model.name}" },
    )

    /** Long-press the app icon → the floating button's settings; follows the .debug package id. */
    private fun publishMacroShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "floating_macro")
            .setShortLabel(getString(R.string.macro_shortcut_short))
            .setLongLabel(getString(R.string.macro_shortcut_long))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MacroSettingsActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        // Rate-limited or refused by the launcher: the floating menu's Settings still opens it.
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(this, shortcut) }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::model.isInitialized && model.gimbalGamepad.onMotion(event, model)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::macroKeypad.isInitialized &&
            macroKeypad.onKey(event.keyCode, event.action, event.repeatCount)) return true
        if (::model.isInitialized && model.gimbalGamepad.onKey(event, model)) return true
        // Only an armed shutter (live view in front, opted in) consumes volume and media keys.
        if (::remoteShutter.isInitialized && remoteShutter.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::remoteShutter.isInitialized) remoteShutter.close()
        if (::macroZoom.isInitialized) macroZoom.stop()
        if (::macroAttachment.isInitialized) MacroHub.detach(macroAttachment)
        if (::bleProbe.isInitialized && MacroHub.bleProbe === bleProbe) MacroHub.bleProbe = null
        macroScope.cancel()
        super.onDestroy()
    }
}

@Composable
private fun OpenPocketCineApp(model: AppModel) {
    val activity = LocalActivity.current
    lateinit var discovery: PermissionGate
    val enableBluetooth =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (discovery.granted) model.session.startScan()
        }
    fun requestBluetoothOn() {
        // A staged SIMULATED review has no camera services and never asks for a real radio.
        if (model.reviewLaunch != null) return
        // Without Nearby devices the enable prompt throws; Bluetooth settings still work.
        runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .recoverCatching { activity?.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { DiagnosticCenter.log("warning", "ble", "enable", "bluetooth enable unavailable ${it.javaClass.simpleName}") }
    }
    discovery = rememberPermissionGate("discovery", DiscoveryPermissions.discovery()) {
        beginDiscovery(model) { requestBluetoothOn() }
    }

    LaunchedEffect(Unit) {
        if (model.reviewLaunch == null && discovery.granted) {
            beginDiscovery(model, onBluetoothOff = { requestBluetoothOn() })
        } else if (model.reviewLaunch == null && !discovery.needsSettings) {
            discovery.request()
        }
    }

    val shell by model.shell.state.collectAsState()
    val showLive = shell.monitor != null
    LaunchedEffect(model.hdrDisplayActive, activity) {
        MonitorHDRDisplay.apply(activity, enabled = model.hdrDisplayActive)
    }
    val hideNavigation = (showLive && model.shellState.cover == null) || model.showsMultiview
    LaunchedEffect(activity, hideNavigation) {
        (activity as? MainActivity)?.hideSystemNavigation = hideNavigation
        (activity as? MainActivity)?.updateSystemBars()
    }

    MonitorAppRoot(
        brand = MonitorLaunchBrand(R.drawable.opc_app_logo, "OpenPocketCine", Color(0xFF0E0E0E), BrandColors.ink, OpcFonts.sora),
        keepScreenAwake = model.keepScreenAwake,
        reports = MonitorReportsOffer(AppDiagnostics.consent, AppDiagnostics.reporting, AppDiagnostics.host.product) {
            model.privacyOpen = true
        },
        reportsAllowed = model.reviewLaunch == null && !showLive && !model.showsMultiview && !model.privacyOpen,
        splash = com.opencapture.opencine.appkit.rememberMonitorLaunchSplash(enabled = model.reviewLaunch == null),
        // Knowing about screen recording only dims the HDR boost.
        onScreenRecording = { model.screenCaptured = it },
    ) {
        Box(Modifier.fillMaxSize().startupBackdrop().reviewMetadata(model.reviewLaunch)) {
            val stage = model.multiview?.takeIf { !showLive }
            // The stage is a page over the shell, which keeps its own state underneath.
            Box(if (stage != null) Modifier.clearAndSetSemantics { } else Modifier) {
                LinkExperience(
                    model = model,
                    discovery = discovery,
                    onEnableBluetooth = { requestBluetoothOn() },
                )
            }
            stage?.let { com.opencapture.openpocketcine.multiview.MultiviewScreen(model, it, onClose = model::closeMultiview) }
            if (model.privacyOpen && !model.showsMultiview) {
                MonitorLegalDocumentScreen(PocketLegalDocuments, MonitorLegalPage.PRIVACY) { model.privacyOpen = false }
            }
        }
    }
}

/**
 * The pair wizard in OpenPocketCine's words (the Android pair page it has always shown): the four
 * Pocket and Nano pages, and Nearby devices or Bluetooth as the primary action while discovery
 * cannot run.
 */
internal fun pairingWizard(
    label: String,
    joinedNetwork: String?,
    vpnActive: Boolean,
    granted: Boolean,
    needsSettings: Boolean,
    radioOn: Boolean,
) = PairingWizard(
    bodies = listOf(
        "Turn the camera on and keep the phone nearby. Pocket and Nano both appear. Choose the one you want.",
        "If the camera shows Approve, tap it on that camera's screen. First-time pairing can wait up to 90 seconds.",
        "We read the camera's network over Bluetooth, then join its Wi-Fi for you.",
        "Exposure, LUTs and scopes go live as soon as the video link is up.",
    ),
    approveLines = listOf("Look for an Approve / pairing prompt", "Tap it on the camera screen"),
    joinLines = listOf("Leave the camera on. It brings up its own Wi-Fi", "On 5.8 GHz that can take about a minute; we keep trying"),
    label = label,
    joinedNetwork = joinedNetwork,
    vpnActive = vpnActive,
    blocker = when {
        !granted && needsSettings -> "Open Settings"
        !granted -> "Allow ${DiscoveryPermissions.settingsName(DiscoveryPermissions.discovery()).lowercase()}"
        !radioOn -> "Turn Bluetooth on"
        else -> null
    },
)

/** DJI `startScan` clears the session's reconnect target, so it never interrupts an attempt. */
private val DISCOVERY_RESTART = DiscoveryRestart.WHEN_IDLE

/** Permission granted or Bluetooth on: look again when the integration allows it now. */
private fun beginDiscovery(model: AppModel, onBluetoothOff: () -> Unit = {}) {
    if (DISCOVERY_RESTART.allows(model.cameras.state.value, model.isLive)) model.session.startScan()
    if (!model.session.radioOn.value) onBluetoothOff()
}

@Composable
private fun LinkExperience(
    model: AppModel,
    discovery: PermissionGate,
    onEnableBluetooth: () -> Unit,
) {
    val context = LocalContext.current
    val state by model.cameras.state.collectAsState()
    // No saved camera opens the pair page, as at first launch.
    val pairing = state.saved.isEmpty() || model.pairing
    var showReportingPrivacy by remember { mutableStateOf(false) }
    val phase by model.session.phaseFlow.collectAsState()
    val radioOn by model.session.radioOn.collectAsState()
    // The DJI integration declares its nearby tap (pair at once) and setup naming (none).
    AppShell(model.shell, ShellBrand("OpenPocketCine"),
        ShellActions(
            diagnostics = { DiagnosticCenter.shareReport(context, model.session) },
            reportProblem = { model.problemReportOpen = true },
            multiview = if (MultiviewQualificationPolicy.tileCount > 1) model::openMultiview else null,
        ),
        monitor = { LiveViewScreen(model) },
        page = { ShellCoverPage(model, it) },
        coverPresentation = com.opencapture.opencine.appkit.ShellCoverPresentation.SHELL,
        pairingFlow = pairingWizard(
            label = PairingWizard.phaseLabel(
                when {
                    state.attempts.isEmpty() -> ConnectionPhase.SCANNING
                    phase == ConnectionPhase.IDLE -> ConnectionPhase.CONNECTING_GATT
                    else -> phase
                },
            ),
            joinedNetwork = model.session.joinedSSID, vpnActive = LocalVPNProbe.isActive(context),
            granted = discovery.granted, needsSettings = discovery.needsSettings, radioOn = radioOn,
        ).flow(
            model.cameras, onDone = model.shell::endPairing, rescan = model.session::startScan,
            // A staged SIMULATED review never requests real permissions or radios; the typed
            // local startup guard's sibling for the pairing wizard's blocker step.
            onBlocker = { if (model.reviewLaunch == null) { if (!discovery.granted) discovery.request() else onEnableBluetooth() } },
            content = { PairingCallouts(model, discovery, onEnableBluetooth) },
        ))
    if (model.problemReportOpen) {
        ManualProblemReportDialog(model = model, onClose = { model.problemReportOpen = false }, onPrivacy = { showReportingPrivacy = true })
    }
    if (showReportingPrivacy) {
        Dialog(onDismissRequest = { showReportingPrivacy = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            MonitorLegalDocumentScreen(PocketLegalDocuments, MonitorLegalPage.PRIVACY) { showReportingPrivacy = false }
        }
    }
}
