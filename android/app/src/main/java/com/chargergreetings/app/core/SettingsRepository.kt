package com.chargergreetings.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Preferences and engine state, stored locally in SharedPreferences.
 *
 * SharedPreferences rather than DataStore on purpose: a [android.content.BroadcastReceiver]
 * has a very short, synchronous window to make a decision, and DataStore's Flow
 * API would mean either blocking on a coroutine or racing the receiver's
 * lifetime. SharedPreferences reads are already in-memory after the first load
 * and are safe to touch directly here.
 *
 * Nothing in this file leaves the device.
 */
class SettingsRepository(context: Context) : GreetingStore, BatteryAlertStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- user preferences ---------------------------------------------------

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var playOnConnect: Boolean
        get() = prefs.getBoolean(KEY_ON_CONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_ON_CONNECT, value) }

    var playOnDisconnect: Boolean
        get() = prefs.getBoolean(KEY_ON_DISCONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_ON_DISCONNECT, value) }

    /** 0..100. Applied to the MediaPlayer, not the device's media volume. */
    var volumePercent: Int
        get() = prefs.getInt(KEY_VOLUME, 80).coerceIn(0, 100)
        set(value) = prefs.edit { putInt(KEY_VOLUME, value.coerceIn(0, 100)) }

    /** Optional pause before speaking, 0..3000 ms. */
    var delayMs: Int
        get() = prefs.getInt(KEY_DELAY, 0).coerceIn(0, MAX_DELAY_MS)
        set(value) = prefs.edit { putInt(KEY_DELAY, value.coerceIn(0, MAX_DELAY_MS)) }

    var respectSilentMode: Boolean
        get() = prefs.getBoolean(KEY_RESPECT_SILENT, true)
        set(value) = prefs.edit { putBoolean(KEY_RESPECT_SILENT, value) }

    /** Minimum gap between two greetings, whatever caused them. */
    var cooldownMs: Long
        get() = prefs.getLong(KEY_COOLDOWN, 2_500L).coerceIn(0L, 60_000L)
        set(value) = prefs.edit { putLong(KEY_COOLDOWN, value.coerceIn(0L, 60_000L)) }

    // --- engine state -------------------------------------------------------

    override var lastKnownState: PowerState
        get() = when (prefs.getInt(KEY_LAST_STATE, PowerState.UNKNOWN.ordinal)) {
            PowerState.PLUGGED.ordinal -> PowerState.PLUGGED
            PowerState.UNPLUGGED.ordinal -> PowerState.UNPLUGGED
            else -> PowerState.UNKNOWN
        }
        // commit(), not apply(): a receiver's process can be killed the instant
        // it returns, and losing this write would mean a duplicate greeting.
        @Suppress("ApplySharedPref")
        set(value) {
            prefs.edit().putInt(KEY_LAST_STATE, value.ordinal).commit()
        }

    override var lastGreetingAt: Long
        get() = prefs.getLong(KEY_LAST_GREETING_AT, 0L)
        @Suppress("ApplySharedPref")
        set(value) {
            prefs.edit().putLong(KEY_LAST_GREETING_AT, value).commit()
        }

    /** True until the app has been opened once. */
    var hasCompletedFirstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_RUN_DONE, value) }


    // --- custom sounds (Storage Access Framework) --------------------------
    //
    // Stored as the string form of a content:// URI the user picked. Null or
    // blank means "use the bundled clip". Durable access across reboots comes
    // from takePersistableUriPermission at pick time; SoundLibrary handles the
    // read-back and the case where the file is later moved or deleted.

    var connectedSoundUri: String?
        get() = prefs.getString(KEY_URI_CONNECTED, null)?.ifBlank { null }
        set(value) = prefs.edit { putString(KEY_URI_CONNECTED, value ?: "") }

    var disconnectedSoundUri: String?
        get() = prefs.getString(KEY_URI_DISCONNECTED, null)?.ifBlank { null }
        set(value) = prefs.edit { putString(KEY_URI_DISCONNECTED, value ?: "") }

    fun soundUriFor(greeting: Greeting): String? = when (greeting) {
        Greeting.CONNECTED -> connectedSoundUri
        Greeting.DISCONNECTED -> disconnectedSoundUri
    }

    fun setSoundUriFor(greeting: Greeting, uri: String?) {
        when (greeting) {
            Greeting.CONNECTED -> connectedSoundUri = uri
            Greeting.DISCONNECTED -> disconnectedSoundUri = uri
        }
    }

    // --- service state and diagnostics -------------------------------------
    //
    // These let the watchdog and the diagnostics panel answer "is it actually
    // running?" instead of guessing. serviceRunning uses commit(), not apply():
    // the watchdog may read it from a different process moments after the
    // service process died, and an unflushed apply() would read as stale.

    @Suppress("ApplySharedPref")
    var serviceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) { prefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).commit() }

    var lastServiceStartAt: Long
        get() = prefs.getLong(KEY_LAST_SERVICE_START, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SERVICE_START, value) }

    var lastServiceStopAt: Long
        get() = prefs.getLong(KEY_LAST_SERVICE_STOP, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SERVICE_STOP, value) }

    var lastBootRestoreAt: Long
        get() = prefs.getLong(KEY_LAST_BOOT_RESTORE, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_BOOT_RESTORE, value) }

    var lastEventDescription: String?
        get() = prefs.getString(KEY_LAST_EVENT, null)?.ifBlank { null }
        set(value) = prefs.edit { putString(KEY_LAST_EVENT, value ?: "") }

    var lastEventAt: Long
        get() = prefs.getLong(KEY_LAST_EVENT_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_EVENT_AT, value) }

    var lastPlaybackAt: Long
        get() = prefs.getLong(KEY_LAST_PLAYBACK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_PLAYBACK, value) }

    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)?.ifBlank { null }
        set(value) = prefs.edit { putString(KEY_LAST_ERROR, value ?: "") }

    var lastRecoveryAt: Long
        get() = prefs.getLong(KEY_LAST_RECOVERY, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_RECOVERY, value) }


    // --- per-slot sound configuration ---------------------------------------
    //
    // Keys are namespaced by slot ("sound_connected_volume" etc.) so the three
    // sections are genuinely independent: changing one can never touch another.
    // Legacy single-value keys are migrated on first read, below.

    fun slotConfig(slot: SoundSlot): SlotConfig = SlotConfig(
        slot = slot,
        enabled = slotEnabled(slot),
        source = slotSource(slot),
        volumePercent = slotVolume(slot),
        limit = slotLimit(slot)
    )

    fun slotEnabled(slot: SoundSlot): Boolean = when (slot) {
        // The two charger events keep their original keys so an upgrading user
        // does not silently lose their on/off choices.
        SoundSlot.CONNECTED -> playOnConnect
        SoundSlot.DISCONNECTED -> playOnDisconnect
        SoundSlot.BATTERY_ALERT -> prefs.getBoolean(KEY_BATTERY_ENABLED, false)
    }

    fun setSlotEnabled(slot: SoundSlot, value: Boolean) = when (slot) {
        SoundSlot.CONNECTED -> playOnConnect = value
        SoundSlot.DISCONNECTED -> playOnDisconnect = value
        SoundSlot.BATTERY_ALERT -> prefs.edit { putBoolean(KEY_BATTERY_ENABLED, value) }
    }

    fun slotSource(slot: SoundSlot): SoundSource {
        val stored = prefs.getString(key(slot, "source"), null)
        SoundSource.parse(stored)?.let { return it }

        // Migration: v1.0 stored a bare content URI under uri_connected /
        // uri_disconnected. Read it once so an upgrading user keeps their pick.
        val legacy = when (slot) {
            SoundSlot.CONNECTED -> prefs.getString(KEY_URI_CONNECTED, null)
            SoundSlot.DISCONNECTED -> prefs.getString(KEY_URI_DISCONNECTED, null)
            SoundSlot.BATTERY_ALERT -> null
        }
        if (!legacy.isNullOrBlank()) return SoundSource.FileUri(legacy)

        return SoundSource.BuiltIn(slot.defaultBuiltIn)
    }

    fun setSlotSource(slot: SoundSlot, source: SoundSource) {
        prefs.edit { putString(key(slot, "source"), source.serialise()) }
        // Clear the legacy key so the migration above cannot resurrect an old
        // pick after the user has explicitly chosen something new.
        when (slot) {
            SoundSlot.CONNECTED -> prefs.edit { remove(KEY_URI_CONNECTED) }
            SoundSlot.DISCONNECTED -> prefs.edit { remove(KEY_URI_DISCONNECTED) }
            SoundSlot.BATTERY_ALERT -> Unit
        }
    }

    fun resetSlotSource(slot: SoundSlot) =
        setSlotSource(slot, SoundSource.BuiltIn(slot.defaultBuiltIn))

    fun slotVolume(slot: SoundSlot): Int =
        prefs.getInt(key(slot, "volume"), volumePercent).coerceIn(0, 100)

    fun setSlotVolume(slot: SoundSlot, value: Int) =
        prefs.edit { putInt(key(slot, "volume"), value.coerceIn(0, 100)) }

    fun slotLimit(slot: SoundSlot): PlaybackLimit =
        PlaybackLimit.fromMillis(prefs.getLong(key(slot, "limit"), 0L))

    fun setSlotLimit(slot: SoundSlot, limit: PlaybackLimit) =
        prefs.edit { putLong(key(slot, "limit"), limit.millis) }

    private fun key(slot: SoundSlot, field: String) = "sound_${slot.storageKey}_$field"

    // --- quiet hours ---------------------------------------------------------

    var quietHours: QuietHours
        get() = QuietHours(
            enabled = prefs.getBoolean(KEY_QUIET_ENABLED, false),
            startMinuteOfDay = prefs.getInt(KEY_QUIET_START, 23 * 60),
            endMinuteOfDay = prefs.getInt(KEY_QUIET_END, 7 * 60)
        )
        set(value) = prefs.edit {
            putBoolean(KEY_QUIET_ENABLED, value.enabled)
            putInt(KEY_QUIET_START, value.startMinuteOfDay)
            putInt(KEY_QUIET_END, value.endMinuteOfDay)
        }

    // --- battery alert -------------------------------------------------------

    var batteryThresholdPercent: Int
        get() = prefs.getInt(KEY_BATTERY_THRESHOLD, 80).coerceIn(1, 100)
        set(value) = prefs.edit { putInt(KEY_BATTERY_THRESHOLD, value.coerceIn(1, 100)) }

    fun batteryAlertConfig() = BatteryAlertConfig(
        enabled = slotEnabled(SoundSlot.BATTERY_ALERT),
        thresholdPercent = batteryThresholdPercent
    )

    // commit(), not apply(): this is the flag that prevents a duplicate alert,
    // and the process can be killed the instant playback starts.
    @Suppress("ApplySharedPref")
    override var batteryAlertArmed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_ARMED, true)
        set(value) { prefs.edit().putBoolean(KEY_BATTERY_ARMED, value).commit() }

    // --- helpers ------------------------------------------------------------

    fun config(): GreetingConfig = GreetingConfig(
        enabled = enabled,
        playOnConnect = playOnConnect,
        playOnDisconnect = playOnDisconnect,
        cooldownMs = cooldownMs,
        respectSilentMode = respectSilentMode
    )

    fun resetToDefaults() {
        prefs.edit {
            remove(KEY_ENABLED)
            remove(KEY_ON_CONNECT)
            remove(KEY_ON_DISCONNECT)
            remove(KEY_VOLUME)
            remove(KEY_DELAY)
            remove(KEY_RESPECT_SILENT)
            remove(KEY_COOLDOWN)
        }
    }

    companion object {
        const val MAX_DELAY_MS = 3_000

        private const val FILE = "charger_greetings"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_ON_CONNECT = "on_connect"
        private const val KEY_ON_DISCONNECT = "on_disconnect"
        private const val KEY_VOLUME = "volume"
        private const val KEY_DELAY = "delay_ms"
        private const val KEY_RESPECT_SILENT = "respect_silent"
        private const val KEY_COOLDOWN = "cooldown_ms"
        private const val KEY_LAST_STATE = "last_state"
        private const val KEY_LAST_GREETING_AT = "last_greeting_at"
        private const val KEY_FIRST_RUN_DONE = "first_run_done"
        private const val KEY_URI_CONNECTED = "uri_connected"
        private const val KEY_URI_DISCONNECTED = "uri_disconnected"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_LAST_SERVICE_START = "last_service_start"
        private const val KEY_LAST_SERVICE_STOP = "last_service_stop"
        private const val KEY_LAST_BOOT_RESTORE = "last_boot_restore"
        private const val KEY_LAST_EVENT = "last_event"
        private const val KEY_LAST_EVENT_AT = "last_event_at"
        private const val KEY_LAST_PLAYBACK = "last_playback"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_RECOVERY = "last_recovery"
        private const val KEY_QUIET_ENABLED = "quiet_enabled"
        private const val KEY_QUIET_START = "quiet_start"
        private const val KEY_QUIET_END = "quiet_end"
        private const val KEY_BATTERY_ENABLED = "battery_enabled"
        private const val KEY_BATTERY_THRESHOLD = "battery_threshold"
        private const val KEY_BATTERY_ARMED = "battery_armed"
    }
}
