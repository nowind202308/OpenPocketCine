package com.opencapture.openpocketcine.macro

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.opencapture.monitorui.l10n.Text
import com.opencapture.openpocketcine.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The floating button's own settings, opened from the app icon's long-press shortcut or the
 * floating menu. It lives outside the shared OpenCine settings so the fork stays a thin layer.
 */
class MacroSettingsActivity : ComponentActivity() {
    /** Bumped on every resume: permission screens return here, so statuses are re-read. */
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsPage(resumes)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes += 1
    }

    @Composable
    private fun SettingsPage(refresh: Int) {
        var enabled by remember { mutableStateOf(MacroPrefs.floatingEnabled(this)) }
        // Read again on each resume (refresh is the key).
        val overlay = remember(refresh) { MacroPrefs.overlayGranted(this) }
        var notifications by remember(refresh) { mutableStateOf(notificationsGranted()) }
        val battery = remember(refresh) { ignoresBatteryOptimizations() }
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notifications = it
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.macro_settings_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.macro_settings_intro), style = MaterialTheme.typography.bodyMedium)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.macro_settings_enable), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    MacroPrefs.setFloatingEnabled(this@MacroSettingsActivity, it)
                    if (!it) FloatingMacroService.stop(this@MacroSettingsActivity)
                })
            }
            HorizontalDivider()

            Requirement(
                title = stringResource(R.string.macro_settings_overlay),
                detail = stringResource(R.string.macro_settings_overlay_detail),
                granted = overlay,
                action = stringResource(R.string.macro_settings_open),
            ) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Requirement(
                    title = stringResource(R.string.macro_settings_notifications),
                    detail = stringResource(R.string.macro_settings_notifications_detail),
                    granted = notifications,
                    action = stringResource(R.string.macro_settings_allow),
                ) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            Requirement(
                title = stringResource(R.string.macro_settings_battery),
                detail = stringResource(R.string.macro_settings_battery_detail),
                granted = battery,
                action = stringResource(R.string.macro_settings_open),
            ) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            HorizontalDivider()

            Text(stringResource(R.string.macro_settings_presets), style = MaterialTheme.typography.titleMedium)
            PocketPresets.all.forEach { set ->
                Text(set.title, style = MaterialTheme.typography.titleSmall)
                set.menu.forEach { entry ->
                    Text(stringResource(R.string.macro_menu_row, entry.key, entry.preset.name),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(stringResource(R.string.macro_settings_keypad_zoom), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.macro_settings_usage), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            BleProbeSection()
        }
    }

    /** Can a Bluetooth-only controller (XIAO nRF52840) change settings? Runs over the live session. */
    @Composable
    private fun BleProbeSection() {
        val probe = MacroHub.bleProbe
        val running by (probe?.running ?: idle).collectAsState()
        val report by (probe?.report ?: noReport).collectAsState()
        Text(stringResource(R.string.ble_probe_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ble_probe_intro), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { probe?.start() }, enabled = probe != null && !running) {
                Text(stringResource(if (running) R.string.ble_probe_running else R.string.ble_probe_start))
            }
            val text = report
            if (text != null) {
                OutlinedButton(onClick = { copy(text) }) { Text(stringResource(R.string.ble_probe_copy)) }
            }
        }
        if (probe == null) {
            Text(stringResource(R.string.ble_probe_no_session), style = MaterialTheme.typography.bodySmall)
        }
        report?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }

    private fun copy(text: String) {
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("ble-probe", text))
    }

    @Composable
    private fun Requirement(title: String, detail: String, granted: Boolean, action: String, onAction: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                OutlinedButton(onClick = onAction) { Text(stringResource(R.string.macro_settings_done)) }
            } else {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }

    private companion object {
        val idle: StateFlow<Boolean> = MutableStateFlow(false)
        val noReport: StateFlow<String?> = MutableStateFlow(null)
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ignoresBatteryOptimizations(): Boolean =
        getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) == true
}
