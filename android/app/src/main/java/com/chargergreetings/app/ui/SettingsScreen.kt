package com.chargergreetings.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chargergreetings.app.audio.SoundChoice
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.core.PowerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The whole user interface: one scrolling screen.
 *
 * Ordering follows what a user actually opens this for:
 *   1. Is it working right now?   (status headline, always first)
 *   2. What is stopping it?       (setup card, only when something really is)
 *   3. Turn things on and off.
 *   4. Choose and hear the sounds.
 *   5. Fine detail, then diagnostics and troubleshooting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onConnectChange: (Boolean) -> Unit,
    onDisconnectChange: (Boolean) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onDelayChange: (Int) -> Unit,
    onSilentModeChange: (Boolean) -> Unit,
    onTest: (Greeting) -> Unit,
    onStopPreview: () -> Unit,
    onPickSound: (Greeting) -> Unit,
    onClearSound: (Greeting) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onResetDefaults: () -> Unit,
    onClearLog: () -> Unit,
    onMessageShown: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Charger Greetings") },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(state)

            SetupCard(
                state = state,
                onOpenBatterySettings = onOpenBatterySettings,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onOpenAutoStartSettings = onOpenAutoStartSettings
            )

            if (state.respectSilentMode && state.deviceSilenced) {
                WarningCard(
                    icon = { Icon(Icons.Filled.NotificationsOff, contentDescription = null) },
                    title = "Your phone is silenced",
                    body = "Greetings are paused because the ringer is off, media volume " +
                        "is at zero, or Do Not Disturb is on. Turn off " +
                        "\"Respect silent mode\" below to play anyway."
                )
            }

            if (!state.hasAudioOutput) {
                WarningCard(
                    icon = { Icon(Icons.Filled.PowerOff, contentDescription = null) },
                    title = "No audio output",
                    body = "Android reports no speaker or headphones available right now."
                )
            }

            SectionCard(title = "Monitoring") {
                SettingSwitch(
                    label = "Charge sound monitoring",
                    description = "The master switch. Stays on until you turn it off.",
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SettingSwitch(
                    label = "When the charger is connected",
                    description = state.connectedSound?.label ?: "",
                    checked = state.playOnConnect,
                    enabled = state.enabled,
                    onCheckedChange = onConnectChange
                )
                SettingSwitch(
                    label = "When the charger is removed",
                    description = state.disconnectedSound?.label ?: "",
                    checked = state.playOnDisconnect,
                    enabled = state.enabled,
                    onCheckedChange = onDisconnectChange
                )
            }

            SectionCard(title = "Sounds") {
                SoundRow(
                    title = "Connected sound",
                    choice = state.connectedSound,
                    isTesting = state.isTesting,
                    onPick = { onPickSound(Greeting.CONNECTED) },
                    onClear = { onClearSound(Greeting.CONNECTED) },
                    onTest = { onTest(Greeting.CONNECTED) }
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                SoundRow(
                    title = "Disconnected sound",
                    choice = state.disconnectedSound,
                    isTesting = state.isTesting,
                    onPick = { onPickSound(Greeting.DISCONNECTED) },
                    onClear = { onClearSound(Greeting.DISCONNECTED) },
                    onTest = { onTest(Greeting.DISCONNECTED) }
                )

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onStopPreview,
                    enabled = state.isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.Stop, contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Stop preview")
                }

                Spacer(Modifier.height(16.dp))
                SliderSetting(
                    label = "Volume",
                    valueLabel = "${state.volumePercent}%",
                    spokenValue = "${state.volumePercent} percent",
                    value = state.volumePercent.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChange = { onVolumeChange(it.roundToInt()) }
                )
            }

            SectionCard(title = "Behaviour") {
                SliderSetting(
                    label = "Delay before playing",
                    valueLabel = if (state.delayMs == 0) "None"
                    else "%.1f s".format(state.delayMs / 1000f),
                    spokenValue = if (state.delayMs == 0) "no delay"
                    else "${state.delayMs} milliseconds",
                    value = state.delayMs.toFloat(),
                    valueRange = 0f..3000f,
                    steps = 11,
                    onValueChange = { onDelayChange((it / 250f).roundToInt() * 250) }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingSwitch(
                    label = "Respect silent mode",
                    description = "Stay quiet when the ringer is off or Do Not Disturb is on",
                    checked = state.respectSilentMode,
                    onCheckedChange = onSilentModeChange
                )
            }

            DiagnosticsCard(state.diagnostics, state.recentLog, onClearLog, onResetDefaults)

            TroubleshootingCard()

            Text(
                "Works entirely offline. No account, no analytics, no internet permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun StatusCard(state: SettingsUiState) {
    val plugged = state.powerState == PowerState.PLUGGED

    val (headline, detail, good) = when (state.status) {
        MonitoringStatus.ACTIVE -> Triple(
            "Monitoring active",
            if (plugged) "Plugged in via ${state.chargeKind.label}" else "Waiting for the charger",
            true
        )
        MonitoringStatus.INACTIVE -> Triple(
            "Monitoring off",
            "Turn on the master switch below to start",
            false
        )
        MonitoringStatus.SETUP_REQUIRED -> Triple(
            "Setup needed",
            "Monitoring will stop after a few hours until this is fixed",
            false
        )
        MonitoringStatus.PERMISSION_REQUIRED -> Triple(
            "Permission needed",
            "Notifications are blocked, so the monitor cannot show its status",
            false
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (good) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$headline. $detail"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (good) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        state.status == MonitoringStatus.ACTIVE && plugged -> Icons.Filled.Power
                        state.status == MonitoringStatus.ACTIVE -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.Warning
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.clearAndSetSemantics { }) {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Only appears when something genuinely needs doing. An always-present nag card
 * trains users to ignore it, which defeats the purpose.
 */
@Composable
private fun SetupCard(
    state: SettingsUiState,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit
) {
    val needsBattery = state.batteryOptimised
    val needsNotifications = !state.notificationsAllowed
    if (!needsBattery && !needsNotifications && !state.needsOemGuidance) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryAlert, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Keep monitoring alive", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))

            if (needsBattery) {
                Text(
                    "Battery optimisation is on. This is the main reason charge " +
                        "sounds stop working after a few hours: Android will not let " +
                        "the app restart its monitor in the background while this is " +
                        "enabled.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onOpenBatterySettings) {
                    Text("Allow unrestricted battery use")
                }
            }

            if (needsNotifications) {
                Text(
                    "Notifications are blocked. Monitoring still runs, but Android " +
                        "requires a visible notification for it, and without one the " +
                        "system is far more likely to shut the monitor down.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onOpenNotificationSettings) {
                    Text("Allow notifications")
                }
            }

            state.oemGuidance?.let { guidance ->
                Spacer(Modifier.height(4.dp))
                Text(
                    guidance,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onOpenAutoStartSettings) {
                    Text("Open auto-start settings")
                }
            }
        }
    }
}

@Composable
private fun SoundRow(
    title: String,
    choice: SoundChoice?,
    isTesting: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            choice?.label ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = if (choice?.available == false) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (choice?.available == false && choice.problem != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                choice.problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                Text("Choose")
            }
            OutlinedButton(
                onClick = onTest,
                enabled = !isTesting,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Play")
            }
            if (choice?.isCustom == true) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    info: DiagnosticsInfo,
    recentLog: List<String>,
    onClearLog: () -> Unit,
    onResetDefaults: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    DiagnosticRow("Monitoring", if (info.monitoringEnabled) "Enabled" else "Disabled")
                    DiagnosticRow("Service", if (info.serviceRunning) "Running" else "Not running")
                    DiagnosticRow("Charging state", info.chargingState)
                    DiagnosticRow("Last event", info.lastEvent ?: "None yet")
                    DiagnosticRow("Last event at", formatTime(info.lastEventAt))
                    DiagnosticRow("Last sound played", formatTime(info.lastPlaybackAt))
                    DiagnosticRow("Service started", formatTime(info.lastServiceStartAt))
                    DiagnosticRow("Service stopped", formatTime(info.lastServiceStopAt))
                    DiagnosticRow("Restored after boot", formatTime(info.lastBootRestoreAt))
                    DiagnosticRow("Last auto-recovery", formatTime(info.lastRecoveryAt))
                    DiagnosticRow("Notifications", if (info.notificationsAllowed) "Allowed" else "Blocked")
                    DiagnosticRow(
                        "Battery optimisation",
                        if (info.batteryOptimised) "ON (will cause failures)" else "Off (good)"
                    )
                    DiagnosticRow("Connected sound", info.connectedSound)
                    DiagnosticRow("Disconnected sound", info.disconnectedSound)
                    DiagnosticRow("Last error", info.lastError ?: "None")
                    DiagnosticRow("App", info.appVersion)
                    DiagnosticRow("Android", info.androidVersion)
                    DiagnosticRow("Device", info.device)

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Recent activity",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    if (recentLog.isEmpty()) {
                        Text(
                            "Nothing yet. Plug the charger in and this will show what happened.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        recentLog.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onClearLog) { Text("Clear log") }
                        TextButton(onClick = onResetDefaults) { Text("Reset settings") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun TroubleshootingCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Troubleshooting",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    TroubleItem(
                        "It stopped working after a few hours",
                        "Almost always battery optimisation. Set this app to " +
                            "Unrestricted in Android's battery settings, and on Xiaomi, " +
                            "Oppo, Vivo, OnePlus or Samsung also enable Auto-start. " +
                            "Without the battery exemption Android forbids the app from " +
                            "restarting its own monitor in the background."
                    )
                    TroubleItem(
                        "It stopped after I force-stopped the app",
                        "This one cannot be fixed by any app. When you Force stop an " +
                            "app from Settings, Android puts it in a stopped state and " +
                            "delivers it no broadcasts at all, including the boot " +
                            "broadcast, until you open the app manually again. Open the " +
                            "app once and it resumes."
                    )
                    TroubleItem(
                        "It did not resume after restarting my phone",
                        "Open the app once. If it then works until the next reboot, " +
                            "your manufacturer is blocking boot start: enable Auto-start " +
                            "for this app in the settings linked above."
                    )
                    TroubleItem(
                        "The test button works but real charging does not",
                        "That means audio is fine and background execution is not. " +
                            "Check the battery and auto-start settings above."
                    )
                    TroubleItem(
                        "It plays twice, or plays when it should not",
                        "Send the Recent activity list above; every decision and the " +
                            "reason for it is recorded there."
                    )
                }
            }
        }
    }
}

@Composable
private fun TroubleItem(question: String, answer: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(question, style = MaterialTheme.typography.bodyMedium)
        Text(
            answer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WarningCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = if (checked) "On" else "Off"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueLabel: String,
    spokenValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = spokenValue
            }
        )
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) "Never"
    else SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(millis))
