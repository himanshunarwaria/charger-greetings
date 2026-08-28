package com.chargergreetings.app.core

/**
 * The three configurable sound slots.
 *
 * Introducing this enum is what stops the app growing three near-identical
 * copies of the picker, playback and settings code. Everything downstream --
 * storage keys, the picker sheet, the player, the UI section -- is written once
 * against a slot rather than once per event.
 */
enum class SoundSlot(
    val storageKey: String,
    val displayName: String,
    /** Bundled sound used when the user has not chosen anything else. */
    val defaultBuiltIn: String
) {
    CONNECTED("connected", "Charger connected", BuiltInSoundIds.GREETING_CONNECTED),
    DISCONNECTED("disconnected", "Charger disconnected", BuiltInSoundIds.GREETING_DISCONNECTED),
    BATTERY_ALERT("battery", "Battery level alert", BuiltInSoundIds.ALERT_PING);

    companion object {
        /** Maps the legacy two-event enum onto slots, so old code keeps working. */
        fun forGreeting(greeting: Greeting): SoundSlot = when (greeting) {
            Greeting.CONNECTED -> CONNECTED
            Greeting.DISCONNECTED -> DISCONNECTED
        }
    }
}

/**
 * Where a slot's audio comes from.
 *
 * Persisted as a short string so a stored value stays readable and stable
 * across app versions: "builtin:ding", "uri:content://...", "rec:1712345.m4a".
 */
sealed class SoundSource {

    /** One of the clips bundled in res/raw. */
    data class BuiltIn(val id: String) : SoundSource()

    /** A file the user picked through the Storage Access Framework. */
    data class FileUri(val uri: String) : SoundSource()

    /** A voice memo recorded in the app, stored in app-private storage. */
    data class Recording(val fileName: String) : SoundSource()

    fun serialise(): String = when (this) {
        is BuiltIn -> "builtin:$id"
        is FileUri -> "uri:$uri"
        is Recording -> "rec:$fileName"
    }

    companion object {
        fun parse(raw: String?): SoundSource? {
            if (raw.isNullOrBlank()) return null
            val index = raw.indexOf(':')
            if (index <= 0) return null
            val value = raw.substring(index + 1)
            if (value.isBlank()) return null
            return when (raw.substring(0, index)) {
                "builtin" -> BuiltIn(value)
                "uri" -> FileUri(value)
                "rec" -> Recording(value)
                else -> null
            }
        }
    }
}

/** Stable ids for the bundled clips. Must match the res/raw file names. */
object BuiltInSoundIds {
    const val GREETING_CONNECTED = "power_connected"
    const val GREETING_DISCONNECTED = "power_disconnected"
    const val CHIME_UP = "chime_up"
    const val CHIME_DOWN = "chime_down"
    const val DING = "ding"
    const val SOFT_BEEP = "soft_beep"
    const val MARIMBA = "marimba"
    const val PEBBLE = "pebble"
    const val ALERT_PING = "alert_ping"
}

/**
 * How long a sound is allowed to play.
 *
 * Deliberately a small fixed set rather than a free slider: the useful answers
 * are "all of it" or "just a moment", and a continuous control would invite
 * fiddling for no benefit.
 */
enum class PlaybackLimit(val millis: Long, val label: String) {
    FULL(0L, "Full sound"),
    THREE_SECONDS(3_000L, "3 seconds"),
    FIVE_SECONDS(5_000L, "5 seconds"),
    TEN_SECONDS(10_000L, "10 seconds");

    val isLimited: Boolean get() = millis > 0L

    companion object {
        fun fromMillis(value: Long): PlaybackLimit =
            entries.firstOrNull { it.millis == value } ?: FULL
    }
}

/** Everything one slot needs, read as a single snapshot. */
data class SlotConfig(
    val slot: SoundSlot,
    val enabled: Boolean,
    val source: SoundSource,
    val volumePercent: Int,
    val limit: PlaybackLimit
)
