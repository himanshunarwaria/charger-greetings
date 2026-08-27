package com.chargergreetings.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.chargergreetings.app.audio.SoundLibrary
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.ui.SettingsViewModel
import com.chargergreetings.app.util.Diagnostics

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

    /**
     * Which greeting the in-flight document pick is for. Held here rather than
     * passed through the contract because ActivityResultContracts.OpenDocument
     * carries no user payload back.
     */
    private var pickingFor: Greeting? = null

    /**
     * OpenDocument, not GetContent: only OpenDocument returns a URI that can be
     * given long-term access via takePersistableUriPermission. GetContent hands
     * back a one-shot grant that stops working after a reboot, which would
     * quietly break every custom sound the next morning.
     */
    private val pickSound =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = pickingFor
            pickingFor = null
            if (target != null) viewModel.onSoundPicked(target, uri)
        }

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
                    onStopPreview = viewModel::stopPreview,
                    onPickSound = ::launchSoundPicker,
                    onClearSound = viewModel::clearSound,
                    onOpenBatterySettings = viewModel::openBatterySettings,
                    onOpenNotificationSettings = viewModel::openNotificationSettings,
                    onOpenAutoStartSettings = viewModel::openAutoStartSettings,
                    onResetDefaults = viewModel::resetToDefaults,
                    onClearLog = viewModel::clearLog,
                    onMessageShown = viewModel::dismissMessage
                )
            }
        }
    }
    /**
     * Opens the system document picker for one greeting's sound.
     * Battery-optimisation and auto-start screens are handled by SetupAdvisor
     * via the view model, so all the OEM-specific fallbacks live in one place.
     */
    private fun launchSoundPicker(greeting: Greeting) {
        pickingFor = greeting
        try {
            pickSound.launch(SoundLibrary.PICKER_MIME_TYPES)
        } catch (e: Exception) {
            pickingFor = null
            Diagnostics.log(this, "Could not open the file picker: " + e.message)
        }
    }
}
