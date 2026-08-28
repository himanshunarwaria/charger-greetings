package com.chargergreetings.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.chargergreetings.app.core.PlaybackLimit
import com.chargergreetings.app.core.PowerState
import com.chargergreetings.app.core.QuietHours
import com.chargergreetings.app.core.SoundSlot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The whole interface: one scrolling screen.
 *
 * Layout follows what a user came here to do:
 *   1. Is it working?            (status headline)
 *   2. What is stopping it?      (setup card, only when something really is)
 *   3. The three sound sections  (identical shape, one shared component)
 *   4. General settings          (master switch, quiet hours, silent mode)
 *   5. Status detail and troubleshooting, folded away by default
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onMonitoringChange: (Boolean) -> Unit,
    onSlotEnabledChange: (SoundSlot, Boolean) -> Unit,
    onSlotVolumeChange: (SoundSlot, Int) -> Unit,
    onSlotLimitChange: (SoundSlot, PlaybackLimit) -> Unit,
    onChangeSound: (SoundSlot) -> Unit,
    onResetSound: (SoundSlot) -> Unit,
    onPreview: (SoundSlot) -> Unit,
    onStopPreview: () -> Unit,
    onBatteryThresholdChange: (Int) -> Unit,
    onRespectSilentChange: (Boolean) -> Unit,
    onQuietHoursEnabledChange: (Boolean) -> Unit,
    onQuietHoursStartChange: (Int) -> Unit,
    onQuietHoursEndChange: (Int) -> Unit,
    onRestartMonitoring: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
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

            SoundSection(
                title = "Charger connected",
                slotState = state.connected,
                masterEnabled = state.enabled,
                isPlaying = state.playingSlot == SoundSlot.CONNECTED,
                onEnabledChange = { onSlotEnabledChange(SoundSlot.CONNECTED, it) },
                onVolumeChange = { onSlotVolumeChange(SoundSlot.CONNECTED, it) },
                onLimitChange = { onSlotLimitChange(SoundSlot.CONNECTED, it) },
                onChangeSound = { onChangeSound(SoundSlot.CONNECTED) },
                onResetSound = { onResetSound(SoundSlot.CONNECTED) },
                onPreview = { onPreview(SoundSlot.CONNECTED) },
                onStopPreview = onStopPreview
            )

            SoundSection(
                title = "Charger disconnected",
                slotState = state.disconnected,
                masterEnabled = state.enabled,
                isPlaying = state.playingSlot == SoundSlot.DISCONNECTED,
                onEnabledChange = { onSlotEnabledChange(SoundSlot.DISCONNECTED, it) },
                onVolumeChange = { onSlotVolumeChange(SoundSlot.DISCONNECTED, it) },
                onLimitChange = { onSlotLimitChange(SoundSlot.DISCONNECTED, it) },
                onChangeSound = { onChangeSound(SoundSlot.DISCONNECTED) },
                onResetSound = { onResetSound(SoundSlot.DISCONNECTED) },
                onPreview = { onPreview(SoundSlot.DISCONNECTED) },
                onStopPreview = onStopPreview
            )

            SoundSection(
                title = "Battery level alert",
                slotState = state.battery,
                masterEnabled = state.enabled,
                isPlaying = state.playingSlot == SoundSlot.BATTERY_ALERT,
                onEnabledChange = { onSlotEnabledChange(SoundSlot.BATTERY_ALERT, it) },
                onVolumeChange = { onSlotVolumeChange(SoundSlot.BATTERY_ALERT, it) },
                onLimitChange = { onSlotLimitChange(SoundSlot.BATTERY_ALERT, it) },
                onChangeSound = { onChangeSound(SoundSlot.BATTERY_ALERT) },
                onResetSound = { onResetSound(SoundSlot.BATTERY_ALERT) },
                onPreview = { onPreview(SoundSlot.BATTERY_ALERT) },
                onStopPreview = onStopPreview
            ) {
                // Threshold control, only meaningful for this section.
                Spacer(Modifier.height(4.dp))
                Text(
                    "Alert at ${state.batteryThreshold}%",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Plays once when charging reaches this level. It will not " +
                        "play again until the battery drops below it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.batteryThreshold.toFloat(),
                    onValueChange = { onBatteryThresholdChange(it.roundToInt().coerceIn(1, 100)) },
                    valueRange = 1f..100f,
                    steps = 98,
                    enabled = state.battery.enabled,
                    modifier = Modifier.semantics {
                        contentDescription = "Battery alert level"
                        stateDescription = "${state.batteryThreshold} percent"
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.batteryThreshold == 80,
                        onClick = { onBatteryThresholdChange(80) },
                        enabled = state.battery.enabled,
                        label = { Text("80%") }
                    )
                    FilterChip(
                        selected = state.batteryThreshold == 100,
                        onClick = { onBatteryThresholdChange(100) },
                        enabled = state.battery.enabled,
                        label = { Text("100%") }
                    )
                }
            }

            GeneralSection(
                state = state,
                onMonitoringChange = onMonitoringChange,
                onRespectSilentChange = onRespectSilentChange,
                onQuietHoursEnabledChange = onQuietHoursEnabledChange,
                onQuietHoursStartChange = onQuietHoursStartChange,
                onQuietHoursEndChange = onQuietHoursEndChange,
                onRestartMonitoring = onRestartMonitoring
            )

            StatusDetailCard(state, onClearLog)
            TroubleshootingCard()

            Text(
                "Works entirely offline. No account, no analytics, no internet permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )
        }
    }
}

/**
 * One sound section. Used verbatim for all three slots; [extra] is the only
 * place a section differs (the battery threshold control).
 */
@Composable
private fun SoundSection(
    title: String,
    slotState: SlotUiState,
    masterEnabled: Boolean,
    isPlaying: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onLimitChange: (PlaybackLimit) -> Unit,
    onChangeSound: () -> Unit,
    onResetSound: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    extra: @Composable (ColumnScope.() -> Unit)? = null
) {
    val controlsEnabled = masterEnabled && slotState.enabled

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = slotState.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = masterEnabled,
                    modifier = Modifier.semantics {
                        stateDescription = if (slotState.enabled) "On" else "Off"
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                slotState.soundLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = if (!slotState.soundAvailable) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
            slotState.soundProblem?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChangeSound, modifier = Modifier.weight(1f)) {
                    Text("Change sound")
                }
                OutlinedButton(
                    onClick = if (isPlaying) onStopPreview else onPreview,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "Stop" else "Preview")
                }
            }

            if (slotState.isCustom) {
                TextButton(onClick = onResetSound) { Text("Reset to default sound") }
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Volume", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${slotState.volumePercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = slotState.volumePercent.toFloat(),
                onValueChange = { onVolumeChange(it.roundToInt()) },
                valueRange = 0f..100f,
                steps = 19,
                enabled = controlsEnabled,
                modifier = Modifier.semantics {
                    contentDescription = "$title volume"
                    stateDescription = "${slotState.volumePercent} percent"
                }
            )

            Text("Play for", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            // FlowRow, not Row: four chips do not fit one line on a phone, and a
            // Row squeezes the last one until "10 seconds" renders one letter
            // per line. Wrapping to a second line is the only readable option.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlaybackLimit.entries.forEach { limit ->
                    FilterChip(
                        selected = slotState.limit == limit,
                        onClick = { onLimitChange(limit) },
                        enabled = controlsEnabled,
                        label = { Text(limit.label, maxLines = 1, softWrap = false) }
                    )
                }
            }

            extra?.let {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                it()
            }
        }
    }
}

@Composable
private fun GeneralSection(
    state: SettingsUiState,
    onMonitoringChange: (Boolean) -> Unit,
    onRespectSilentChange: (Boolean) -> Unit,
    onQuietHoursEnabledChange: (Boolean) -> Unit,
    onQuietHoursStartChange: (Int) -> Unit,
    onQuietHoursEndChange: (Int) -> Unit,
    onRestartMonitoring: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "General",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            SettingSwitch(
                label = "Charge sound monitoring",
                description = "The master switch. Stays on until you turn it off.",
                checked = state.enabled,
                onCheckedChange = onMonitoringChange
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingSwitch(
                label = "Respect silent and vibrate mode",
                description = "Stay quiet when the ringer is off or Do Not Disturb is on",
                checked = state.respectSilentMode,
                onCheckedChange = onRespectSilentChange
            )

            SettingSwitch(
                label = "Quiet hours",
                description = if (state.quietHours.enabled) {
                    "No sounds between ${QuietHours.format(state.quietHours.startMinuteOfDay)} " +
                        "and ${QuietHours.format(state.quietHours.endMinuteOfDay)}"
                } else {
                    "Silence sounds during a nightly window"
                },
                checked = state.quietHours.enabled,
                onCheckedChange = onQuietHoursEnabledChange
            )

            if (state.quietHours.enabled) {
                HourPicker(
                    label = "Start",
                    minuteOfDay = state.quietHours.startMinuteOfDay,
                    onChange = onQuietHoursStartChange
                )
                HourPicker(
                    label = "End",
                    minuteOfDay = state.quietHours.endMinuteOfDay,
                    onChange = onQuietHoursEndChange
                )
                Text(
                    "Overnight windows work: 23:00 to 07:00 covers the night, not the day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.suppressionReason?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sounds are currently silenced: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRestartMonitoring) { Text("Restart monitoring") }
        }
    }
}

/**
 * Hour-granularity picker. Deliberately hours only: quiet hours are a blunt
 * instrument and a full time-picker dialog for "roughly bedtime" is more UI
 * than the feature is worth.
 */
@Composable
private fun HourPicker(label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    val hour = minuteOfDay / 60
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                QuietHours.format(minuteOfDay),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = hour.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 23) * 60) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.semantics {
                contentDescription = "$label hour"
                stateDescription = QuietHours.format(minuteOfDay)
            }
        )
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
        MonitoringStatus.DISABLED -> Triple(
            "Monitoring off", "Turn on the master switch in General below", false
        )
        MonitoringStatus.BATTERY_OPTIMISED -> Triple(
            "Battery optimisation may stop monitoring",
            "Android will not let the app restart itself in the background",
            false
        )
        MonitoringStatus.PERMISSION_REQUIRED -> Triple(
            "Permission required", "Notifications are blocked", false
        )
        MonitoringStatus.SOUND_UNAVAILABLE -> Triple(
            "Sound file unavailable", "One of your chosen sounds can no longer be opened", false
        )
        MonitoringStatus.SETUP_REQUIRED -> Triple(
            "Setup required", "The monitoring service is not running", false
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
                        !good -> Icons.Filled.Warning
                        plugged -> Icons.Filled.Power
                        else -> Icons.Filled.CheckCircle
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

/** Only shown when something genuinely needs doing; a permanent nag gets ignored. */
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
            Text("Keep monitoring alive", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            if (needsBattery) {
                Text(
                    "Battery optimisation is on. This is the main reason charge " +
                        "sounds stop working after a few hours.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onOpenBatterySettings) {
                    Text("Allow unrestricted battery use")
                }
            }

            if (needsNotifications) {
                Text(
                    "Notifications are blocked. Monitoring still runs, but Android " +
                        "requires a visible notification for it and is far more " +
                        "likely to shut it down without one.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onOpenNotificationSettings) { Text("Allow notifications") }
            }

            state.oemGuidance?.let { guidance ->
                Spacer(Modifier.height(4.dp))
                Text(guidance, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenAutoStartSettings) { Text("Open auto-start settings") }
            }
        }
    }
}

@Composable
private fun StatusDetailCard(state: SettingsUiState, onClearLog: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val info = state.diagnostics

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Status detail",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            // The three facts worth seeing without expanding anything.
            InfoRow("Monitoring service", if (info.serviceRunning) "Running" else "Not running")
            InfoRow("Last charging event", info.lastEvent ?: "None yet")
            InfoRow("Last sound played", formatTime(info.lastPlaybackAt))

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    InfoRow("Charging state", info.chargingState)
                    InfoRow(
                        "Battery level",
                        if (info.batteryLevel >= 0) "${info.batteryLevel}%" else "Unknown"
                    )
                    InfoRow("Last event at", formatTime(info.lastEventAt))
                    InfoRow("Service started", formatTime(info.lastServiceStartAt))
                    InfoRow("Restored after restart", formatTime(info.lastBootRestoreAt))
                    InfoRow("Last auto-recovery", formatTime(info.lastRecoveryAt))
                    InfoRow("Notifications", if (info.notificationsAllowed) "Allowed" else "Blocked")
                    InfoRow(
                        "Battery optimisation",
                        if (info.batteryOptimised) "On (may stop monitoring)" else "Off"
                    )
                    InfoRow("Last problem", info.lastError ?: "None")
                    InfoRow("App version", info.appVersion)
                    InfoRow("Android", info.androidVersion)
                    InfoRow("Device", info.device)

                    if (state.recentLog.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Recent activity", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        state.recentLog.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onClearLog) { Text("Clear") }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
                            "Unrestricted, and on Xiaomi, Oppo, Vivo, OnePlus or " +
                            "Samsung also enable Auto-start."
                    )
                    TroubleItem(
                        "It stopped after I force-stopped the app",
                        "No app can fix this. Force stop tells Android to deliver " +
                            "the app nothing at all, including the restart signal, " +
                            "until you open it again. Open it once and it resumes."
                    )
                    TroubleItem(
                        "Preview works but real charging does not",
                        "Audio is fine; background execution is not. Check the " +
                            "battery and auto-start settings above."
                    )
                    TroubleItem(
                        "My chosen sound stopped playing",
                        "The file was probably moved or deleted. The section will " +
                            "say so, and the built-in sound is used until you pick again."
                    )
                    TroubleItem(
                        "Nothing plays at night",
                        "Check whether quiet hours are on, and whether your phone is " +
                            "in silent or Do Not Disturb with \"Respect silent mode\" enabled."
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

private fun formatTime(millis: Long): String =
    if (millis <= 0L) "Never"
    else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
