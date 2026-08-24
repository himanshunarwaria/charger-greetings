package com.chargergreetings.app.ui

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
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Power
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
import androidx.compose.runtime.remember
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
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.core.PowerState
import kotlin.math.roundToInt

/**
 * The entire user interface: one scrolling screen.
 *
 * Ordering follows what a user actually came here to do:
 *   1. Is it working right now?  (status card, always first)
 *   2. Anything blocking it?     (warnings, only when real)
 *   3. Turn things on and off.
 *   4. Hear it.
 *   5. Fine detail, then diagnostics.
 *
 * Accessibility: every control has a semantic label and state description,
 * sliders announce a spoken value rather than a raw number, and the decorative
 * status icon is hidden from screen readers so the text is read once, not twice.
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
    onOpenBatterySettings: () -> Unit,
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

            if (state.batteryOptimised) {
                WarningCard(
                    icon = { Icon(Icons.Filled.BatteryAlert, contentDescription = null) },
                    title = "Battery optimisation is on",
                    body = "Android may stop the greeting from playing while the app is " +
                        "closed. Allowing unrestricted background activity fixes it.",
                    actionLabel = "Open battery settings",
                    onAction = onOpenBatterySettings
                )
            }

            if (state.respectSilentMode && state.deviceSilenced) {
                WarningCard(
                    icon = { Icon(Icons.Filled.NotificationsOff, contentDescription = null) },
                    title = "Your phone is silenced",
                    body = "Greetings are paused because the ringer is off, media volume " +
                        "is at zero, or Do Not Disturb is on. Turn off " +
                        "“Respect silent mode” below to play anyway."
                )
            }

            if (!state.hasAudioOutput) {
                WarningCard(
                    icon = { Icon(Icons.Filled.PowerOff, contentDescription = null) },
                    title = "No audio output",
                    body = "Android reports no speaker or headphones available right now."
                )
            }

            SectionCard(title = "Greetings") {
                SettingSwitch(
                    label = "Play greetings",
                    description = "The master switch for everything below",
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SettingSwitch(
                    label = "When the charger is connected",
                    description = "मालिक, प्रणाम",
                    checked = state.playOnConnect,
                    enabled = state.enabled,
                    onCheckedChange = onConnectChange
                )
                SettingSwitch(
                    label = "When the charger is removed",
                    description = "फिर मिलते हैं, मालिक",
                    checked = state.playOnDisconnect,
                    enabled = state.enabled,
                    onCheckedChange = onDisconnectChange
                )
            }

            SectionCard(title = "Sound") {
                SliderSetting(
                    label = "Volume",
                    valueLabel = "${state.volumePercent}%",
                    spokenValue = "${state.volumePercent} percent",
                    value = state.volumePercent.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChange = { onVolumeChange(it.roundToInt()) }
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onTest(Greeting.CONNECTED) },
                        enabled = !state.isTesting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test connect")
                    }
                    OutlinedButton(
                        onClick = { onTest(Greeting.DISCONNECTED) },
                        enabled = !state.isTesting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test remove")
                    }
                }
            }

            SectionCard(title = "Behaviour") {
                SliderSetting(
                    label = "Delay before speaking",
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

            SectionCard(title = "Recent activity") {
                if (state.recentLog.isEmpty()) {
                    Text(
                        "Nothing yet. Plug the charger in and this will show what happened.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.recentLog.forEach { line ->
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
    val headline = when (state.powerState) {
        PowerState.PLUGGED -> "Plugged in"
        PowerState.UNPLUGGED -> "On battery"
        PowerState.UNKNOWN -> "Power state unknown"
    }
    val detail = when {
        !state.enabled -> "Greetings are switched off"
        plugged -> "Charging over ${state.chargeKind.label}"
        state.powerState == PowerState.UNPLUGGED -> "Waiting for the charger"
        else -> "Android did not report a battery"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.enabled) MaterialTheme.colorScheme.primaryContainer
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
                        color = if (state.enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (plugged) Icons.Filled.Power else Icons.Filled.PowerOff,
                    // Decorative: the row already carries a merged description.
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

@Composable
private fun WarningCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
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
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
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
            if (description != null) {
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
