package com.chargergreetings.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chargergreetings.app.audio.GreetingPlayer
import com.chargergreetings.app.core.ChargeKind
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.core.GreetingEngine
import com.chargergreetings.app.core.PowerState
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.power.PowerStatus
import com.chargergreetings.app.power.PowerWatcherService
import com.chargergreetings.app.util.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the settings screen renders. */
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

    val isTesting: Boolean = false,
    val message: String? = null,
    val recentLog: List<String> = emptyList()
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val engine = GreetingEngine(settings)
    private val player = GreetingPlayer(application)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Opening the app records the current power state but never speaks.
        // This is the rule that stops the app greeting you just because you
        // launched it while the charger happened to be in.
        val observed = PowerStatus.read(application)
        if (!settings.hasCompletedFirstRun) {
            Diagnostics.log(application, "first run: ${engine.baseline(observed)}")
            settings.hasCompletedFirstRun = true
        } else {
            engine.baseline(observed)
        }

        // Opening the app is also what starts the reliable watcher on a fresh
        // install, before any reboot has happened. See PowerWatcherService.
        if (settings.enabled) PowerWatcherService.start(application)

        refresh()
    }

    /** Re-reads live system state. Called on launch and whenever the app resumes. */
    fun refresh() {
        val context = getApplication<Application>()
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
                batteryOptimised = !PowerStatus.isIgnoringBatteryOptimizations(context),
                hasAudioOutput = PowerStatus.hasAudioOutput(context),
                deviceSilenced = PowerStatus.isSilenced(context),
                recentLog = Diagnostics.recent(context, limit = 12).reversed()
            )
        }
    }

    // --- preference writes --------------------------------------------------

    fun setEnabled(value: Boolean) {
        settings.enabled = value
        _state.update { it.copy(enabled = value) }
        // The watcher's notification is the visible cost of the feature being
        // on at all; turning the feature off removes it immediately.
        if (value) PowerWatcherService.start(getApplication()) else PowerWatcherService.stop(getApplication())
    }

    fun setPlayOnConnect(value: Boolean) {
        settings.playOnConnect = value
        _state.update { it.copy(playOnConnect = value) }
    }

    fun setPlayOnDisconnect(value: Boolean) {
        settings.playOnDisconnect = value
        _state.update { it.copy(playOnDisconnect = value) }
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
        _state.update { it.copy(respectSilentMode = value) }
    }

    fun resetToDefaults() {
        settings.resetToDefaults()
        refresh()
        showMessage("Settings restored to defaults")
    }

    // --- actions ------------------------------------------------------------

    /**
     * Plays a greeting on demand. Test playback deliberately bypasses the
     * cooldown and the silent-mode rule: the user pressed the button, so the
     * only thing that should stop it is a genuine audio failure.
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
}
