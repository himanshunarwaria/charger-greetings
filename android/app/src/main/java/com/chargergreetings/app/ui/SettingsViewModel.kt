package com.chargergreetings.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chargergreetings.app.audio.GreetingPlayer
import com.chargergreetings.app.audio.SoundChoice
import com.chargergreetings.app.audio.SoundLibrary
import com.chargergreetings.app.core.ChargeKind
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.core.PowerState
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.power.MonitoringController
import com.chargergreetings.app.power.PowerStatus
import com.chargergreetings.app.power.PowerWatcherService
import com.chargergreetings.app.power.WatchdogWorker
import com.chargergreetings.app.power.SetupAdvisor
import com.chargergreetings.app.util.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Overall health, shown as one headline so the user knows where they stand. */
enum class MonitoringStatus { ACTIVE, INACTIVE, SETUP_REQUIRED, PERMISSION_REQUIRED }

/** Everything the diagnostics panel shows. All local, nothing transmitted. */
data class DiagnosticsInfo(
    val monitoringEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val lastEvent: String? = null,
    val lastEventAt: Long = 0L,
    val lastPlaybackAt: Long = 0L,
    val lastServiceStartAt: Long = 0L,
    val lastServiceStopAt: Long = 0L,
    val lastBootRestoreAt: Long = 0L,
    val lastRecoveryAt: Long = 0L,
    val lastError: String? = null,
    val chargingState: String = "",
    val notificationsAllowed: Boolean = true,
    val batteryOptimised: Boolean = false,
    val connectedSound: String = "",
    val disconnectedSound: String = "",
    val appVersion: String = "",
    val androidVersion: String = "",
    val device: String = ""
)

data class SettingsUiState(
    val enabled: Boolean = true,
    val playOnConnect: Boolean = true,
    val playOnDisconnect: Boolean = true,
    val volumePercent: Int = 80,
    val delayMs: Int = 0,
    val respectSilentMode: Boolean = true,

    val powerState: PowerState = PowerState.UNKNOWN,
    val chargeKind: ChargeKind = ChargeKind.NONE,
    val batteryOptimised: Boolean = false,
    val hasAudioOutput: Boolean = true,
    val deviceSilenced: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val needsOemGuidance: Boolean = false,
    val oemGuidance: String? = null,

    val status: MonitoringStatus = MonitoringStatus.INACTIVE,
    val connectedSound: SoundChoice? = null,
    val disconnectedSound: SoundChoice? = null,

    val isTesting: Boolean = false,
    val message: String? = null,
    val recentLog: List<String> = emptyList(),
    val diagnostics: DiagnosticsInfo = DiagnosticsInfo()
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val player = GreetingPlayer(application)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Opening the app records the current power state but never speaks.
        // This is the rule that stops the app greeting you merely because you
        // launched it while the charger happened to be in.
        if (!settings.hasCompletedFirstRun) {
            MonitoringController.baselineSilently(application, "first run")
            settings.hasCompletedFirstRun = true
        } else {
            MonitoringController.baselineSilently(application, "app opened")
        }

        if (settings.enabled) {
            // Always (re)schedule the watchdog, even when the service is up.
            // Without this an existing user upgrading from a build that had no
            // watchdog would never get one scheduled, because the "service is
            // down" branch below would not run. enqueue() uses KEEP, so this
            // can never stack duplicate work.
            WatchdogWorker.enqueue(application)

            // Self-heal on open: if monitoring should be on but the service is
            // not up (an OEM killed it while the app was closed), restart it
            // now. Being in the foreground makes this start always permitted.
            if (!PowerWatcherService.isRunning(application)) {
                Diagnostics.log(application, "App opened: service was down, restarting")
                MonitoringController.enable(application)
            }
        }

        refresh()
    }

    /** Re-reads live system state. Called on launch and on every resume. */
    fun refresh() {
        val context = getApplication<Application>()
        val serviceRunning = PowerWatcherService.isRunning(context)
        val notificationsAllowed = SetupAdvisor.notificationsAllowed(context)
        val batteryOptimised = SetupAdvisor.isBatteryOptimised(context)

        _state.update {
            it.copy(
                enabled = settings.enabled,
                playOnConnect = settings.playOnConnect,
                playOnDisconnect = settings.playOnDisconnect,
                volumePercent = settings.volumePercent,
                delayMs = settings.delayMs,
                respectSilentMode = settings.respectSilentMode,
                powerState = PowerStatus.read(context),
                chargeKind = PowerStatus.chargeKind(context),
                batteryOptimised = batteryOptimised,
                hasAudioOutput = PowerStatus.hasAudioOutput(context),
                deviceSilenced = PowerStatus.isSilenced(context),
                notificationsAllowed = notificationsAllowed,
                needsOemGuidance = SetupAdvisor.needsOemAutoStartGuidance(),
                oemGuidance = SetupAdvisor.oemGuidance(),
                status = resolveStatus(serviceRunning, notificationsAllowed, batteryOptimised),
                connectedSound = SoundLibrary.choiceFor(context, Greeting.CONNECTED),
                disconnectedSound = SoundLibrary.choiceFor(context, Greeting.DISCONNECTED),
                recentLog = Diagnostics.recent(context, limit = 15).reversed(),
                diagnostics = buildDiagnostics(serviceRunning, notificationsAllowed, batteryOptimised)
            )
        }
    }

    private fun resolveStatus(
        serviceRunning: Boolean,
        notificationsAllowed: Boolean,
        batteryOptimised: Boolean
    ): MonitoringStatus = when {
        !settings.enabled -> MonitoringStatus.INACTIVE
        // Battery optimisation left on is the single biggest cause of the app
        // dying overnight, so it is surfaced as a real status, not a hint.
        batteryOptimised -> MonitoringStatus.SETUP_REQUIRED
        !notificationsAllowed -> MonitoringStatus.PERMISSION_REQUIRED
        serviceRunning -> MonitoringStatus.ACTIVE
        else -> MonitoringStatus.SETUP_REQUIRED
    }

    private fun buildDiagnostics(
        serviceRunning: Boolean,
        notificationsAllowed: Boolean,
        batteryOptimised: Boolean
    ): DiagnosticsInfo {
        val context = getApplication<Application>()
        val connected = SoundLibrary.choiceFor(context, Greeting.CONNECTED)
        val disconnected = SoundLibrary.choiceFor(context, Greeting.DISCONNECTED)
        return DiagnosticsInfo(
            monitoringEnabled = settings.enabled,
            serviceRunning = serviceRunning,
            lastEvent = settings.lastEventDescription,
            lastEventAt = settings.lastEventAt,
            lastPlaybackAt = settings.lastPlaybackAt,
            lastServiceStartAt = settings.lastServiceStartAt,
            lastServiceStopAt = settings.lastServiceStopAt,
            lastBootRestoreAt = settings.lastBootRestoreAt,
            lastRecoveryAt = settings.lastRecoveryAt,
            lastError = settings.lastError,
            chargingState = describeCharging(),
            notificationsAllowed = notificationsAllowed,
            batteryOptimised = batteryOptimised,
            connectedSound = describeSound(connected),
            disconnectedSound = describeSound(disconnected),
            appVersion = appVersion(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    private fun describeSound(choice: SoundChoice): String = when {
        !choice.isCustom -> SoundLibrary.BUILT_IN_LABEL
        choice.available -> choice.label
        else -> "${choice.label} (UNAVAILABLE)"
    }

    private fun describeCharging(): String {
        val context = getApplication<Application>()
        return when (PowerStatus.read(context)) {
            PowerState.PLUGGED -> "Plugged in (${PowerStatus.chargeKind(context).label})"
            PowerState.UNPLUGGED -> "On battery"
            PowerState.UNKNOWN -> "Unknown"
        }
    }

    private fun appVersion(): String = try {
        val context = getApplication<Application>()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${context.packageName})"
    } catch (e: Exception) {
        "unknown"
    }

    // --- preference writes --------------------------------------------------

    fun setEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (value) {
            val failure = MonitoringController.enable(context)
            if (failure != null) showMessage(failure)
        } else {
            MonitoringController.disable(context)
        }
        refresh()
    }

    fun setPlayOnConnect(value: Boolean) {
        settings.playOnConnect = value
        refresh()
    }

    fun setPlayOnDisconnect(value: Boolean) {
        settings.playOnDisconnect = value
        refresh()
    }

    fun setVolume(value: Int) {
        settings.volumePercent = value
        _state.update { it.copy(volumePercent = value) }
    }

    fun setDelay(value: Int) {
        settings.delayMs = value
        _state.update { it.copy(delayMs = value) }
    }

    fun setRespectSilentMode(value: Boolean) {
        settings.respectSilentMode = value
        refresh()
    }

    fun resetToDefaults() {
        settings.resetToDefaults()
        refresh()
        showMessage("Settings restored to defaults")
    }

    // --- custom sounds ------------------------------------------------------

    /** Called with the URI returned by the system document picker. */
    fun onSoundPicked(greeting: Greeting, uri: Uri?) {
        val context = getApplication<Application>()
        if (uri == null) return

        val problem = SoundLibrary.takePersistableAccess(context, uri)
        if (problem != null) {
            showMessage(problem)
            return
        }

        // Release the previous grant so we do not accumulate permissions the
        // user can never see or revoke.
        settings.soundUriFor(greeting)?.let { previous ->
            if (previous != uri.toString()) {
                runCatching { SoundLibrary.releaseAccess(context, Uri.parse(previous)) }
            }
        }

        settings.setSoundUriFor(greeting, uri.toString())
        Diagnostics.log(context, "Custom sound set for ${greeting.name}")
        refresh()
    }

    /** Reverts a greeting to the bundled clip. */
    fun clearSound(greeting: Greeting) {
        val context = getApplication<Application>()
        settings.soundUriFor(greeting)?.let { previous ->
            runCatching { SoundLibrary.releaseAccess(context, Uri.parse(previous)) }
        }
        settings.setSoundUriFor(greeting, null)
        Diagnostics.log(context, "Custom sound cleared for ${greeting.name}")
        refresh()
    }

    // --- actions ------------------------------------------------------------

    /**
     * Plays a greeting on demand. Test playback bypasses the cooldown and the
     * silent-mode rule: the user pressed the button, so only a real audio
     * failure should stop it.
     */
    fun test(greeting: Greeting) {
        if (_state.value.isTesting) return
        _state.update { it.copy(isTesting = true, message = null) }

        viewModelScope.launch {
            val problem = player.play(greeting, settings.volumePercent)
            _state.update {
                it.copy(
                    isTesting = false,
                    message = problem?.let { p -> "Could not play the greeting: $p" }
                )
            }
        }
    }

    fun stopPreview() {
        player.stop()
        _state.update { it.copy(isTesting = false) }
    }

    fun openBatterySettings() {
        SetupAdvisor.requestIgnoreBatteryOptimisation(getApplication())
    }

    fun openNotificationSettings() {
        SetupAdvisor.openNotificationSettings(getApplication())
    }

    fun openAutoStartSettings() {
        SetupAdvisor.openAutoStartSettings(getApplication())
    }

    fun clearLog() {
        Diagnostics.clear(getApplication())
        refresh()
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    override fun onCleared() {
        // The preview player belongs to the ViewModel; make sure a preview
        // cannot outlive the screen and leak audio focus.
        player.stop()
        super.onCleared()
    }
}
