package com.chargergreetings.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.chargergreetings.app.ui.ChargerGreetingsTheme
import com.chargergreetings.app.ui.SettingsScreen
import com.chargergreetings.app.ui.SettingsViewModel

/**
 * The app's only screen.
 *
 * Opening it never plays a greeting — the view model records the current power
 * state silently on construction. The activity exists purely to configure the
 * receiver that does the real work.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    // No-op callback: whether the user grants this or not, the app still
    // functions -- PowerWatcherService's foreground state works either way,
    // this only controls whether its status notification is visible.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Live system facts (power source, silent mode, battery optimisation)
        // can all change while the user is in Settings; refresh on every resume
        // rather than registering listeners we would have to tear down.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        })

        setContent {
            ChargerGreetingsTheme {
                val state by viewModel.state.collectAsState()
                SettingsScreen(
                    state = state,
                    onEnabledChange = viewModel::setEnabled,
                    onConnectChange = viewModel::setPlayOnConnect,
                    onDisconnectChange = viewModel::setPlayOnDisconnect,
                    onVolumeChange = viewModel::setVolume,
                    onDelayChange = viewModel::setDelay,
                    onSilentModeChange = viewModel::setRespectSilentMode,
                    onTest = viewModel::test,
                    onOpenBatterySettings = ::openBatteryOptimisationSettings,
                    onResetDefaults = viewModel::resetToDefaults,
                    onClearLog = viewModel::clearLog,
                    onMessageShown = viewModel::dismissMessage
                )
            }
        }
    }

    /**
     * Opens the battery-optimisation screen.
     *
     * Uses the plain settings intents rather than
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which would require the
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission — a permission Google
     * Play restricts to a short list of app categories this one is not in.
     *
     * Falls back through three increasingly generic targets, because OEM ROMs
     * are inconsistent about which of these screens exist.
     */
    private fun openBatteryOptimisationSettings() {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
            add(Intent(Settings.ACTION_SETTINGS))
        }

        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next one.
            } catch (_: SecurityException) {
                // Some ROMs guard these screens; fall through.
            }
        }
    }
}
