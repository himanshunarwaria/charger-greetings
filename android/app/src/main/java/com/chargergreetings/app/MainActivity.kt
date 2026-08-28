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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.chargergreetings.app.ui.ChargerGreetingsTheme
import com.chargergreetings.app.ui.SettingsScreen
import com.chargergreetings.app.ui.SettingsViewModel
import com.chargergreetings.app.ui.SoundPickerSheet
import com.chargergreetings.app.util.Diagnostics

/**
 * The app's only screen.
 *
 * Opening it never plays a sound: the view model records the current power and
 * battery state silently on construction.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    // Notifications: granted or not, the service still runs; this only controls
    // whether its status notification is visible.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Microphone, requested only at the moment the user taps Record. Nothing
     * else in the app can reach the recorder, so denying this costs only the
     * recording feature.
     */
    private val requestMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.startRecording() else viewModel.onMicrophoneDenied()
        }

    /**
     * OpenDocument, not GetContent: only OpenDocument returns a URI that
     * takePersistableUriPermission accepts. GetContent hands back a one-shot
     * grant that stops working after a reboot, which would silently break every
     * chosen sound overnight.
     */
    private val pickSound =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel.onFilePicked(uri)
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

        // Live system facts (power, battery, silent mode, battery optimisation)
        // all change while the user is away in Settings, so refresh on resume
        // rather than registering listeners we would have to tear down.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refresh()
                // Never leave a preview playing behind a closed screen.
                Lifecycle.Event.ON_STOP -> viewModel.stopPreview()
                else -> Unit
            }
        })

        setContent {
            ChargerGreetingsTheme {
                val state by viewModel.state.collectAsState()

                SettingsScreen(
                    state = state,
                    onMonitoringChange = viewModel::setMonitoringEnabled,
                    onSlotEnabledChange = viewModel::setSlotEnabled,
                    onSlotVolumeChange = viewModel::setSlotVolume,
                    onSlotLimitChange = viewModel::setSlotLimit,
                    onChangeSound = viewModel::openPicker,
                    onResetSound = viewModel::resetSlotSound,
                    onPreview = viewModel::preview,
                    onStopPreview = viewModel::stopPreview,
                    onBatteryThresholdChange = viewModel::setBatteryThreshold,
                    onRespectSilentChange = viewModel::setRespectSilentMode,
                    onQuietHoursEnabledChange = viewModel::setQuietHoursEnabled,
                    onQuietHoursStartChange = viewModel::setQuietHoursStart,
                    onQuietHoursEndChange = viewModel::setQuietHoursEnd,
                    onRestartMonitoring = viewModel::restartMonitoring,
                    onOpenBatterySettings = viewModel::openBatterySettings,
                    onOpenNotificationSettings = viewModel::openNotificationSettings,
                    onOpenAutoStartSettings = viewModel::openAutoStartSettings,
                    onClearLog = viewModel::clearLog,
                    onMessageShown = viewModel::dismissMessage
                )

                if (state.picker.isOpen) {
                    SoundPickerSheet(
                        state = state,
                        onDismiss = viewModel::closePicker,
                        onChooseBuiltIn = viewModel::chooseBuiltIn,
                        onBrowseFiles = ::launchFilePicker,
                        onChooseRecording = viewModel::chooseRecording,
                        onDeleteRecording = viewModel::deleteRecording,
                        onPreviewRecording = viewModel::previewRecording,
                        onStartRecording = ::requestRecording,
                        onPauseResumeRecording = viewModel::pauseOrResumeRecording,
                        onStopRecording = viewModel::stopRecording,
                        onSaveRecording = viewModel::saveRecording,
                        onCancelRecording = viewModel::cancelRecording,
                        onStopPreview = viewModel::stopPreview
                    )
                }
            }
        }
    }

    private fun launchFilePicker() {
        try {
            // Broad audio filter: Android decides what it can actually decode,
            // and an over-narrow list would hide files the device supports.
            pickSound.launch(arrayOf("audio/*"))
        } catch (e: Exception) {
            Diagnostics.log(this, "Could not open the file picker: " + e.message)
        }
    }

    /** Asks for the microphone only when recording is actually requested. */
    private fun requestRecording() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.startRecording()
        } else {
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
