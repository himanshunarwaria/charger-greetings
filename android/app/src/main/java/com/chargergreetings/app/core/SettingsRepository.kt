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
class SettingsRepository(context: Context) : GreetingStore {

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
    }
}
