package com.chargergreetings.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chargergreetings.app.audio.GreetingPlayer
import com.chargergreetings.app.audio.SoundCatalog
import com.chargergreetings.app.audio.SoundLibrary
import com.chargergreetings.app.audio.VoiceRecorder
import com.chargergreetings.app.core.ChargeKind
import com.chargergreetings.app.core.PlaybackLimit
import com.chargergreetings.app.core.PowerState
import com.chargergreetings.app.core.QuietHours
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.core.SoundSlot
import com.chargergreetings.app.core.SoundSource
import com.chargergreetings.app.power.MonitoringController
import com.chargergreetings.app.power.PowerStatus
import com.chargergreetings.app.power.PowerWatcherService
import com.chargergreetings.app.power.SetupAdvisor
import com.chargergreetings.app.power.SoundSuppression
import com.chargergreetings.app.util.Diagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Overall health, shown as one plain-language headline. */
enum class MonitoringStatus {
    ACTIVE, DISABLED, PERMISSION_REQUIRED, BATTERY_OPTIMISED, SOUND_UNAVAILABLE, SETUP_REQUIRED
}

/** One configurable sound section. All three sections use this same shape. */
data class SlotUiState(
    val slot: SoundSlot,
    val enabled: Boolean = true,
    val soundLabel: String = "",
    val soundAvailable: Boolean = true,
    val soundProblem: String? = null,
    val isCustom: Boolean = false,
    val volumePercent: Int = 80,
    val limit: PlaybackLimit = PlaybackLimit.FULL
)

/** State of the in-app voice recorder. */
data class RecorderUiState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    /** A finished recording awaiting Save or Discard. */
    val pendingFileName: String? = null,
    val error: String? = null
)

/** Which slot the picker sheet is open for, and what it should show. */
data class PickerUiState(
    val slot: SoundSlot? = null,
    val savedRecordings: List<String> = emptyList()
) {
    val isOpen: Boolean get() = slot != null
}

data class DiagnosticsInfo(
    val monitoringEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val lastEvent: String? = null,
    val lastEventAt: Long = 0L,
    val lastPlaybackAt: Long = 0L,
    val lastServiceStartAt: Long = 0L,
    val lastBootRestoreAt: Long = 0L,
    val lastRecoveryAt: Long = 0L,
    val lastError: String? = null,
    val chargingState: String = "",
    val batteryLevel: Int = -1,
    val notificationsAllowed: Boolean = true,
    val batteryOptimised: Boolean = false,
    val appVersion: String = "",
    val androidVersion: String = "",
    val device: String = ""
)

data class SettingsUiState(
    val enabled: Boolean = true,
    val connected: SlotUiState = SlotUiState(SoundSlot.CONNECTED),
    val disconnected: SlotUiState = SlotUiState(SoundSlot.DISCONNECTED),
    val battery: SlotUiState = SlotUiState(SoundSlot.BATTERY_ALERT),
    val batteryThreshold: Int = 80,

    val respectSilentMode: Boolean = true,
    val quietHours: QuietHours = QuietHours.DISABLED,

    val powerState: PowerState = PowerState.UNKNOWN,
    val chargeKind: ChargeKind = ChargeKind.NONE,
    val batteryOptimised: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val deviceSilenced: Boolean = false,
    val needsOemGuidance: Boolean = false,
    val oemGuidance: String? = null,
    val suppressionReason: String? = null,

    val status: MonitoringStatus = MonitoringStatus.DISABLED,
    /** Which slot is previewing, so only that section shows Stop. */
    val playingSlot: SoundSlot? = null,
    val picker: PickerUiState = PickerUiState(),
    val recorder: RecorderUiState = RecorderUiState(),

    val message: String? = null,
    val recentLog: List<String> = emptyList(),
    val diagnostics: DiagnosticsInfo = DiagnosticsInfo()
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val player = GreetingPlayer(application)
    private val recorder = VoiceRecorder(application)

    /** Tracks the in-flight preview so a new one can cancel the previous. */
    private var previewJob: Job? = null

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Opening the app records the current power and battery state but never
        // plays. This is what stops a launch producing a phantom sound.
        val reason = if (!settings.hasCompletedFirstRun) "first run" else "app opened"
        MonitoringController.baselineSilently(application, reason)
        settings.hasCompletedFirstRun = true

        if (settings.enabled) {
            // KEEP semantics, so this can never stack duplicate work. Needed on
            // every open so an upgrade from a build without a watchdog still
            // gets one scheduled.
            com.chargergreetings.app.power.WatchdogWorker.enqueue(application)
            if (!PowerWatcherService.isRunning(application)) {
                Diagnostics.log(application, "App opened: service was down, restarting")
                MonitoringController.enable(application)
            }
        }

        refresh()
    }

    fun refresh() {
        val context = getApplication<Application>()
        val serviceRunning = PowerWatcherService.isRunning(context)
        val notificationsAllowed = SetupAdvisor.notificationsAllowed(context)
        val batteryOptimised = SetupAdvisor.isBatteryOptimised(context)
        val slots = SoundSlot.entries.associateWith { slotState(it) }
        val anyUnavailable = slots.values.any { it.enabled && !it.soundAvailable }

        _state.update {
            it.copy(
                enabled = settings.enabled,
                connected = slots.getValue(SoundSlot.CONNECTED),
                disconnected = slots.getValue(SoundSlot.DISCONNECTED),
                battery = slots.getValue(SoundSlot.BATTERY_ALERT),
                batteryThreshold = settings.batteryThresholdPercent,
                respectSilentMode = settings.respectSilentMode,
                quietHours = settings.quietHours,
                powerState = PowerStatus.read(context),
                chargeKind = PowerStatus.chargeKind(context),
                batteryOptimised = batteryOptimised,
                notificationsAllowed = notificationsAllowed,
                deviceSilenced = PowerStatus.isSilenced(context),
                needsOemGuidance = SetupAdvisor.needsOemAutoStartGuidance(),
                oemGuidance = SetupAdvisor.oemGuidance(),
                suppressionReason = SoundSuppression.reasonToStayQuiet(context),
                status = resolveStatus(
                    serviceRunning, notificationsAllowed, batteryOptimised, anyUnavailable
                ),
                picker = it.picker.copy(
                    savedRecordings = SoundCatalog.savedRecordings(context).map { f -> f.name }
                ),
                recentLog = Diagnostics.recent(context, limit = 15).reversed(),
                diagnostics = buildDiagnostics(serviceRunning, notificationsAllowed, batteryOptimised)
            )
        }
    }

    private fun slotState(slot: SoundSlot): SlotUiState {
        val context = getApplication<Application>()
        val config = settings.slotConfig(slot)
        val resolved = SoundCatalog.resolve(context, slot, config.source)
        return SlotUiState(
            slot = slot,
            enabled = config.enabled,
            soundLabel = resolved.label,
            soundAvailable = resolved.available,
            soundProblem = resolved.problem,
            isCustom = resolved.isCustom,
            volumePercent = config.volumePercent,
            limit = config.limit
        )
    }

    private fun resolveStatus(
        serviceRunning: Boolean,
        notificationsAllowed: Boolean,
        batteryOptimised: Boolean,
        anySoundUnavailable: Boolean
    ): MonitoringStatus = when {
        !settings.enabled -> MonitoringStatus.DISABLED
        // Ordered by how badly each breaks the app, most severe first.
        batteryOptimised -> MonitoringStatus.BATTERY_OPTIMISED
        !notificationsAllowed -> MonitoringStatus.PERMISSION_REQUIRED
        !serviceRunning -> MonitoringStatus.SETUP_REQUIRED
        anySoundUnavailable -> MonitoringStatus.SOUND_UNAVAILABLE
        else -> MonitoringStatus.ACTIVE
    }

    private fun buildDiagnostics(
        serviceRunning: Boolean,
        notificationsAllowed: Boolean,
        batteryOptimised: Boolean
    ): DiagnosticsInfo {
        val context = getApplication<Application>()
        val battery = PowerStatus.currentBattery(context)
        return DiagnosticsInfo(
            monitoringEnabled = settings.enabled,
            serviceRunning = serviceRunning,
            lastEvent = settings.lastEventDescription,
            lastEventAt = settings.lastEventAt,
            lastPlaybackAt = settings.lastPlaybackAt,
            lastServiceStartAt = settings.lastServiceStartAt,
            lastBootRestoreAt = settings.lastBootRestoreAt,
            lastRecoveryAt = settings.lastRecoveryAt,
            lastError = settings.lastError,
            chargingState = when (PowerStatus.read(context)) {
                PowerState.PLUGGED -> "Plugged in (${PowerStatus.chargeKind(context).label})"
                PowerState.UNPLUGGED -> "On battery"
                PowerState.UNKNOWN -> "Unknown"
            },
            batteryLevel = battery?.level ?: -1,
            notificationsAllowed = notificationsAllowed,
            batteryOptimised = batteryOptimised,
            appVersion = appVersion(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    private fun appVersion(): String = try {
        val context = getApplication<Application>()
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    // --- master switch ------------------------------------------------------

    fun setMonitoringEnabled(value: Boolean) {
        val context = getApplication<Application>()
        if (value) {
            MonitoringController.enable(context)?.let { showMessage(it) }
        } else {
            stopPreview()
            MonitoringController.disable(context)
        }
        refresh()
    }

    /** "Restart monitoring" button: stop, then start cleanly. */
    fun restartMonitoring() {
        val context = getApplication<Application>()
        PowerWatcherService.stop(context)
        MonitoringController.enable(context)?.let { showMessage(it) }
        showMessage("Monitoring restarted")
        refresh()
    }

    // --- per-slot settings --------------------------------------------------

    fun setSlotEnabled(slot: SoundSlot, value: Boolean) {
        settings.setSlotEnabled(slot, value)
        // Re-baseline the battery alert when it is switched on, so enabling it
        // while already above the threshold does not fire immediately.
        if (slot == SoundSlot.BATTERY_ALERT && value) rebaselineBatteryAlert()
        refresh()
    }

    fun setSlotVolume(slot: SoundSlot, value: Int) {
        settings.setSlotVolume(slot, value)
        refresh()
    }

    fun setSlotLimit(slot: SoundSlot, limit: PlaybackLimit) {
        settings.setSlotLimit(slot, limit)
        refresh()
    }

    fun resetSlotSound(slot: SoundSlot) {
        releaseCustomAccess(slot)
        settings.resetSlotSource(slot)
        Diagnostics.log(getApplication(), "Sound reset to default for ${slot.storageKey}")
        refresh()
    }

    fun setBatteryThreshold(percent: Int) {
        settings.batteryThresholdPercent = percent
        // Changing the threshold re-baselines: moving it below the current level
        // must not fire an alert straight away.
        rebaselineBatteryAlert()
        refresh()
    }

    private fun rebaselineBatteryAlert() {
        val context = getApplication<Application>()
        val battery = PowerStatus.currentBattery(context) ?: return
        com.chargergreetings.app.core.BatteryAlertEngine(settings).baseline(
            battery.level, battery.plugged, settings.batteryAlertConfig()
        )
    }

    // --- general settings ---------------------------------------------------

    fun setRespectSilentMode(value: Boolean) {
        settings.respectSilentMode = value
        refresh()
    }

    fun setQuietHoursEnabled(value: Boolean) {
        settings.quietHours = settings.quietHours.copy(enabled = value)
        refresh()
    }

    fun setQuietHoursStart(minuteOfDay: Int) {
        settings.quietHours = settings.quietHours.copy(startMinuteOfDay = minuteOfDay)
        refresh()
    }

    fun setQuietHoursEnd(minuteOfDay: Int) {
        settings.quietHours = settings.quietHours.copy(endMinuteOfDay = minuteOfDay)
        refresh()
    }

    // --- sound picker -------------------------------------------------------

    fun openPicker(slot: SoundSlot) {
        _state.update {
            it.copy(
                picker = PickerUiState(
                    slot = slot,
                    savedRecordings = SoundCatalog.savedRecordings(getApplication()).map { f -> f.name }
                )
            )
        }
    }

    fun closePicker() {
        // Cancelling the sheet must not leave the microphone open.
        if (recorder.isRecording) cancelRecording()
        stopPreview()
        _state.update { it.copy(picker = PickerUiState()) }
    }

    fun chooseBuiltIn(id: String) {
        val slot = _state.value.picker.slot ?: return
        releaseCustomAccess(slot)
        settings.setSlotSource(slot, SoundSource.BuiltIn(id))
        Diagnostics.log(getApplication(), "Built-in sound '$id' set for ${slot.storageKey}")
        closePicker()
        refresh()
    }

    /** Called with the URI returned by the system document picker. */
    fun onFilePicked(uri: Uri?) {
        val slot = _state.value.picker.slot ?: return
        if (uri == null) return
        val context = getApplication<Application>()

        val problem = SoundLibrary.takePersistableAccess(context, uri)
        if (problem != null) {
            showMessage(problem)
            return
        }

        releaseCustomAccess(slot)
        settings.setSlotSource(slot, SoundSource.FileUri(uri.toString()))
        Diagnostics.log(context, "File sound set for ${slot.storageKey}")
        closePicker()
        refresh()
    }

    fun chooseRecording(fileName: String) {
        val slot = _state.value.picker.slot ?: return
        releaseCustomAccess(slot)
        settings.setSlotSource(slot, SoundSource.Recording(fileName))
        Diagnostics.log(getApplication(), "Recording set for ${slot.storageKey}")
        closePicker()
        refresh()
    }

    fun deleteRecording(fileName: String) {
        val context = getApplication<Application>()
        // Any slot still pointing at this file falls back to its default rather
        // than being left pointing at nothing.
        SoundSlot.entries.forEach { slot ->
            val source = settings.slotSource(slot)
            if (source is SoundSource.Recording && source.fileName == fileName) {
                settings.resetSlotSource(slot)
            }
        }
        SoundCatalog.deleteRecording(context, fileName)
        refresh()
        _state.update {
            it.copy(
                picker = it.picker.copy(
                    savedRecordings = SoundCatalog.savedRecordings(context).map { f -> f.name }
                )
            )
        }
    }

    /**
     * Releases a persisted URI grant we are about to stop using, so the app does
     * not accumulate permissions the user can neither see nor revoke.
     */
    private fun releaseCustomAccess(slot: SoundSlot) {
        val current = settings.slotSource(slot)
        if (current !is SoundSource.FileUri) return
        // Another slot may still be using the same file.
        val stillUsed = SoundSlot.entries.any { other ->
            other != slot && (settings.slotSource(other) as? SoundSource.FileUri)?.uri == current.uri
        }
        if (stillUsed) return
        runCatching { SoundLibrary.releaseAccess(getApplication(), Uri.parse(current.uri)) }
    }

    // --- voice recording ----------------------------------------------------

    fun startRecording() {
        val failure = recorder.start()
        _state.update {
            it.copy(
                recorder = it.recorder.copy(
                    recording = failure == null,
                    paused = false,
                    pendingFileName = null,
                    error = failure
                )
            )
        }
    }

    fun pauseOrResumeRecording() {
        val current = _state.value.recorder
        val failure = if (current.paused) recorder.resume() else recorder.pause()
        _state.update {
            it.copy(
                recorder = it.recorder.copy(
                    paused = if (failure == null) !current.paused else current.paused,
                    error = failure
                )
            )
        }
    }

    /** Stops and keeps the file, leaving it pending Save or Discard. */
    fun stopRecording() {
        val file = recorder.stopAndSave()
        _state.update {
            it.copy(
                recorder = RecorderUiState(
                    recording = false,
                    pendingFileName = file?.name,
                    error = if (file == null) "That recording was too short to use." else null
                )
            )
        }
        refresh()
    }

    fun cancelRecording() {
        recorder.cancel()
        // Discard a finished-but-unsaved take too, so Cancel always leaves no trace.
        _state.value.recorder.pendingFileName?.let {
            SoundCatalog.deleteRecording(getApplication(), it)
        }
        _state.update { it.copy(recorder = RecorderUiState()) }
        refresh()
    }

    /** Assigns the pending recording to the slot the picker was opened for. */
    fun saveRecording() {
        val fileName = _state.value.recorder.pendingFileName ?: return
        _state.update { it.copy(recorder = RecorderUiState()) }
        chooseRecording(fileName)
    }

    fun onMicrophoneDenied() {
        _state.update {
            it.copy(
                recorder = it.recorder.copy(
                    error = "Recording needs microphone access. You can still choose " +
                        "a built-in sound or a file from your phone."
                )
            )
        }
    }

    // --- previews -----------------------------------------------------------

    /**
     * Previews a slot's sound. Manual previews deliberately ignore quiet hours
     * and silent mode: the user pressed the button and expects to hear it.
     */
    fun preview(slot: SoundSlot) {
        previewJob?.cancel()
        _state.update { it.copy(playingSlot = slot, message = null) }
        previewJob = viewModelScope.launch {
            val problem = player.play(slot)
            _state.update {
                it.copy(
                    playingSlot = null,
                    message = problem?.let { p -> "Could not play: $p" }
                )
            }
        }
    }

    /** Previews an arbitrary recording from the picker, before it is assigned. */
    fun previewRecording(fileName: String) {
        val slot = _state.value.picker.slot ?: return
        previewJob?.cancel()
        _state.update { it.copy(playingSlot = slot) }
        previewJob = viewModelScope.launch {
            val settingsSnapshot = settings.slotConfig(slot)
            // Temporarily resolve against the chosen recording without saving it,
            // by playing the file directly through the same player.
            val problem = player.playFile(
                SoundCatalog.recordingFile(getApplication(), fileName),
                settingsSnapshot.volumePercent,
                settingsSnapshot.limit
            )
            _state.update {
                it.copy(playingSlot = null, message = problem?.let { p -> "Could not play: $p" })
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        player.stop()
        _state.update { it.copy(playingSlot = null) }
    }

    // --- setup shortcuts ----------------------------------------------------

    fun openBatterySettings() = SetupAdvisor.requestIgnoreBatteryOptimisation(getApplication())
    fun openNotificationSettings() = SetupAdvisor.openNotificationSettings(getApplication())
    fun openAutoStartSettings() = SetupAdvisor.openAutoStartSettings(getApplication())

    fun clearLog() {
        Diagnostics.clear(getApplication())
        refresh()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    override fun onCleared() {
        // A preview must never outlive the screen, and the microphone must never
        // be left open by a rotation or a back press.
        previewJob?.cancel()
        player.stop()
        if (recorder.isRecording) recorder.cancel()
        super.onCleared()
    }
}
