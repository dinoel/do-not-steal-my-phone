package com.guard

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Single settings + status screen (spec §4.3). It never mutates the guard state
 * directly beyond sending arm/disarm/test commands to the service; the service
 * remains the single writer. State shown here is read from prefs and kept live
 * via a prefs change listener, so it stays consistent with the tile/notification.
 */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { GuardPrefs(this) }

    // ---- UI state (backed by prefs / system checks) -----------------------
    private var state by mutableStateOf(GuardState.UNARMED)
    private var sensitivity by mutableStateOf(GuardPrefs.DEFAULT_SENSITIVITY)
    private var graceSeconds by mutableStateOf(GuardPrefs.DEFAULT_GRACE_SECONDS)
    private var bootSurvival by mutableStateOf(false)
    private var lowPowerMode by mutableStateOf(false)
    private var watching by mutableStateOf(false)
    private var stayArmed by mutableStateOf(true)
    private var pauseWhileCharging by mutableStateOf(true)
    private var charging by mutableStateOf(false)
    private var flashStrobe by mutableStateOf(true)
    private var screenFlash by mutableStateOf(true)
    private var batteryExempt by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(false)
    private var logEntries by mutableStateOf<List<LogEntry>>(emptyList())

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshFromPrefs() }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshSystemStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshFromPrefs()
        prefs.registerListener(prefsListener)

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    GuardScreen(
                        modifier = Modifier.padding(padding),
                        state = state,
                        watching = watching,
                        charging = charging,
                        stayArmed = stayArmed,
                        pauseWhileCharging = pauseWhileCharging,
                        canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        sensitivity = sensitivity,
                        graceSeconds = graceSeconds,
                        bootSurvival = bootSurvival,
                        lowPowerMode = lowPowerMode,
                        flashStrobe = flashStrobe,
                        screenFlash = screenFlash,
                        batteryExempt = batteryExempt,
                        notificationsEnabled = notificationsEnabled,
                        logEntries = logEntries,
                        onToggleArm = {
                            GuardService.send(this, GuardService.ACTION_TOGGLE)
                        },
                        onSensitivity = { v ->
                            sensitivity = v
                            prefs.sensitivity = v
                        },
                        onGrace = { v ->
                            graceSeconds = v
                            prefs.graceSeconds = v
                        },
                        onStayArmed = { v ->
                            stayArmed = v
                            prefs.stayArmedAfterUnlock = v
                        },
                        onPauseWhileCharging = { v ->
                            pauseWhileCharging = v
                            prefs.pauseWhileCharging = v
                        },
                        onAddTile = ::addQuickTile,
                        onBootSurvival = { v ->
                            bootSurvival = v
                            prefs.bootSurvival = v
                        },
                        onLowPowerMode = { v ->
                            lowPowerMode = v
                            prefs.lowPowerMode = v
                        },
                        onFlashStrobe = { v ->
                            flashStrobe = v
                            prefs.flashStrobe = v
                        },
                        onScreenFlash = { v ->
                            screenFlash = v
                            prefs.screenFlash = v
                        },
                        onRequestBattery = ::requestBatteryExemption,
                        onRequestNotifications = ::requestNotifications,
                        onOpenSleepingApps = ::openAppSettings,
                        onTestSiren = {
                            GuardService.send(this, GuardService.ACTION_TEST_SIREN)
                        },
                        onClearLog = {
                            prefs.clearLog()
                            logEntries = emptyList()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFromPrefs()
        refreshSystemStatus()
    }

    override fun onDestroy() {
        prefs.unregisterListener(prefsListener)
        super.onDestroy()
    }

    private fun refreshFromPrefs() {
        state = prefs.state
        sensitivity = prefs.sensitivity
        graceSeconds = prefs.graceSeconds
        bootSurvival = prefs.bootSurvival
        lowPowerMode = prefs.lowPowerMode
        watching = prefs.watching
        stayArmed = prefs.stayArmedAfterUnlock
        pauseWhileCharging = prefs.pauseWhileCharging
        flashStrobe = prefs.flashStrobe
        screenFlash = prefs.screenFlash
        logEntries = prefs.logEntries()
    }

    private fun refreshSystemStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        notificationsEnabled = areNotificationsAllowed()
        val status = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        charging = (status?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun areNotificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun requestBatteryExemption() {
        // Standard system dialog; needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppSettings()
        }
    }

    /** Ask the system to add the Guard tile to Quick Settings (Android 13+). */
    private fun addQuickTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm?.requestAddTileService(
                ComponentName(this, GuardTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_shield),
                mainExecutor,
            ) { _: Int -> /* result ignored */ }
        }
    }

    /** Deep-link toward this app's settings so the user can exclude it from
     *  Samsung "Sleeping apps" (that toggle cannot be set programmatically). */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
    }
}

@Composable
private fun GuardScreen(
    modifier: Modifier = Modifier,
    state: GuardState,
    watching: Boolean,
    charging: Boolean,
    stayArmed: Boolean,
    pauseWhileCharging: Boolean,
    canAddTile: Boolean,
    sensitivity: Int,
    graceSeconds: Int,
    bootSurvival: Boolean,
    lowPowerMode: Boolean,
    flashStrobe: Boolean,
    screenFlash: Boolean,
    batteryExempt: Boolean,
    notificationsEnabled: Boolean,
    logEntries: List<LogEntry>,
    onToggleArm: () -> Unit,
    onSensitivity: (Int) -> Unit,
    onGrace: (Int) -> Unit,
    onStayArmed: (Boolean) -> Unit,
    onPauseWhileCharging: (Boolean) -> Unit,
    onAddTile: () -> Unit,
    onBootSurvival: (Boolean) -> Unit,
    onLowPowerMode: (Boolean) -> Unit,
    onFlashStrobe: (Boolean) -> Unit,
    onScreenFlash: (Boolean) -> Unit,
    onRequestBattery: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenSleepingApps: () -> Unit,
    onTestSiren: () -> Unit,
    onClearLog: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Guard", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // ---- Big arm/disarm button + status ----
        val statusText = when (state) {
            GuardState.UNARMED -> "Guard is OFF"
            GuardState.ARMED -> when {
                watching -> "ARMED — watching for motion"
                pauseWhileCharging && charging -> "ARMED — paused while charging (unplug to activate)"
                else -> "ARMED — lock the phone to start watching"
            }
            GuardState.TRIGGERED -> "MOTION DETECTED — unlock now!"
            GuardState.ALARM -> "ALARM SOUNDING"
        }
        Text(statusText, fontSize = 18.sp, fontWeight = FontWeight.Medium)

        Button(
            onClick = onToggleArm,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Text(
                if (state.isActive) "DISARM" else "ARM",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (canAddTile) {
            OutlinedButton(onClick = onAddTile, modifier = Modifier.fillMaxWidth()) {
                Text("Add Guard tile to Quick Settings")
            }
        }

        // ---- Behavior ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Behavior", fontWeight = FontWeight.Bold)
                ToggleRow(
                    title = "Stay armed after unlock",
                    subtitle = "Unlocking stops the siren but keeps guarding; it watches " +
                        "again whenever the screen locks. Turn off only with Disarm. " +
                        "When off, unlocking fully disarms.",
                    checked = stayArmed,
                    onChange = onStayArmed,
                )
                ToggleRow(
                    title = "Pause while charging",
                    subtitle = "While plugged in, the motion alarm is paused. Unplugging the " +
                        "charger immediately activates watching — for charging in public where " +
                        "a thief unplugs and walks off.",
                    checked = pauseWhileCharging,
                    onChange = onPauseWhileCharging,
                )
            }
        }

        // ---- Alarm effects ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Alarm effects", fontWeight = FontWeight.Bold)
                ToggleRow(
                    title = "Flashlight strobe",
                    subtitle = "Strobe the camera flash during the alarm.",
                    checked = flashStrobe,
                    onChange = onFlashStrobe,
                )
                ToggleRow(
                    title = "Screen flash",
                    subtitle = "Flash the whole screen white/black over the lock screen.",
                    checked = screenFlash,
                    onChange = onScreenFlash,
                )
            }
        }

        // ---- Sensitivity ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Sensitivity: $sensitivity", fontWeight = FontWeight.Medium)
                Text(
                    "Higher = a smaller tilt or gentler grab triggers the alarm.",
                    fontSize = 12.sp,
                )
                Slider(
                    value = sensitivity.toFloat(),
                    onValueChange = { onSensitivity(it.toInt()) },
                    valueRange = 0f..100f,
                )
            }
        }

        // ---- Grace period ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Grace period: ${graceSeconds}s", fontWeight = FontWeight.Medium)
                Text(
                    "Time to unlock after motion before the siren sounds.",
                    fontSize = 12.sp,
                )
                Slider(
                    value = graceSeconds.toFloat(),
                    onValueChange = { onGrace(it.toInt()) },
                    valueRange = GuardPrefs.MIN_GRACE_SECONDS.toFloat()..GuardPrefs.MAX_GRACE_SECONDS.toFloat(),
                    steps = (GuardPrefs.MAX_GRACE_SECONDS - GuardPrefs.MIN_GRACE_SECONDS) - 1,
                )
            }
        }

        // ---- System exemptions ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Required setup", fontWeight = FontWeight.Bold)

                ExemptionRow(
                    label = "Battery optimization exemption",
                    granted = batteryExempt,
                    actionText = "Grant",
                    onAction = onRequestBattery,
                )
                ExemptionRow(
                    label = "Notifications permission",
                    granted = notificationsEnabled,
                    actionText = "Enable",
                    onAction = onRequestNotifications,
                )
                ExemptionRow(
                    label = "Not in Samsung “Sleeping apps”",
                    granted = null, // can't be detected programmatically
                    actionText = "Open settings",
                    onAction = onOpenSleepingApps,
                )
                Text(
                    "In the app's settings, open Battery and make sure Guard is set to " +
                        "Unrestricted and is NOT in “Sleeping” or “Deep sleeping” apps.",
                    fontSize = 12.sp,
                )
            }
        }

        // ---- Boot survival ----
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Re-arm after reboot", fontWeight = FontWeight.Medium)
                    Text(
                        "If the phone restarts while armed, arm again automatically.",
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = bootSurvival, onCheckedChange = onBootSurvival)
            }
        }

        // ---- Low-power detection ----
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Battery-saver detection", fontWeight = FontWeight.Medium)
                    Text(
                        "Uses the low-power motion sensor instead of holding the CPU awake. " +
                            "Saves battery but is slower and less reliable — especially with the " +
                            "screen off. Leave OFF for dependable pickup detection. " +
                            "Takes effect next time you arm.",
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = lowPowerMode, onCheckedChange = onLowPowerMode)
            }
        }

        // ---- Test siren ----
        OutlinedButton(onClick = onTestSiren, modifier = Modifier.fillMaxWidth()) {
            Text("Test siren (plays all 4 sounds at max volume)")
        }

        // ---- Event log ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Event log", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onClearLog) { Text("Clear") }
                }
                if (logEntries.isEmpty()) {
                    Text("No events yet. Arm the guard to start logging.", fontSize = 12.sp)
                } else {
                    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    // Newest first; cap how many we render.
                    logEntries.asReversed().take(60).forEach { entry ->
                        Text(
                            text = "${timeFmt.format(Date(entry.timestamp))}  ${entry.message}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ExemptionRow(
    label: String,
    granted: Boolean?,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            val status = when (granted) {
                true -> "✓ Granted"
                false -> "✗ Not granted"
                null -> "Manual — please verify"
            }
            Text(status, fontSize = 12.sp)
        }
        if (granted != true) {
            OutlinedButton(onClick = onAction) { Text(actionText) }
        }
    }
}
