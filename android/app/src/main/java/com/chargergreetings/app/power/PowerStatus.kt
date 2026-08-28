package com.chargergreetings.app.power

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.chargergreetings.app.core.ChargeKind
import com.chargergreetings.app.core.PowerState

/**
 * Reads the live power and audio state from the system.
 *
 * Everything here is a pull, never a poll: the sticky `ACTION_BATTERY_CHANGED`
 * broadcast is already cached by the framework, so asking for it costs a binder
 * call and nothing else. The app never registers a repeating alarm, never runs
 * a service and never wakes itself up to check anything.
 */
object PowerStatus {

    /**
     * The current power source.
     *
     * Reads `EXTRA_PLUGGED` rather than `EXTRA_STATUS`, because "plugged in" and
     * "charging" are different things: a phone at 100 % on the charger reports
     * `BATTERY_STATUS_FULL`, not `CHARGING`, and the user would still expect the
     * greeting when they attach the cable.
     */
    fun read(context: Context): PowerState {
        val intent = stickyBattery(context) ?: return PowerState.UNKNOWN
        // -1 means the extra was absent; 0 means genuinely unplugged.
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return when {
            plugged < 0 -> PowerState.UNKNOWN
            plugged == 0 -> PowerState.UNPLUGGED
            else -> PowerState.PLUGGED
        }
    }

    /**
     * How power is arriving. AC, USB and wireless all produce the same greeting;
     * this exists so the settings screen can tell the user what it can see, and
     * so the diagnostic log is useful when something misbehaves.
     */
    fun chargeKind(context: Context): ChargeKind {
        val intent = stickyBattery(context) ?: return ChargeKind.NONE
        return when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            0 -> ChargeKind.NONE
            BatteryManager.BATTERY_PLUGGED_AC -> ChargeKind.AC
            BatteryManager.BATTERY_PLUGGED_USB -> ChargeKind.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargeKind.WIRELESS
            // BATTERY_PLUGGED_DOCK is API 33+; compare numerically so the code
            // still compiles and behaves on older platforms.
            DOCK_PLUG_VALUE -> ChargeKind.DOCK
            else -> ChargeKind.OTHER
        }
    }

    /**
     * True when the user has told the device to be quiet: ringer on silent or
     * vibrate, or an active Do Not Disturb filter.
     *
     * The greeting plays on the *media* stream, which is not silenced by the
     * ringer switch — so without this check the app would happily talk during a
     * meeting. Honouring it is opt-out, not opt-in.
     */
    fun isSilenced(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerSilent = when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE -> true
            else -> false
        }
        if (ringerSilent) return true

        // Media volume at zero is an explicit "I want no sound" too.
        val mediaVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
        if (mediaVolume <= 0) return true

        val notifications =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val filter = notifications?.currentInterruptionFilter
        return filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
            filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
    }

    /** True when there is at least one usable audio output route. */
    fun hasAudioOutput(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).isNotEmpty()
        } else {
            true
        }
    }

    /**
     * Whether the OS has exempted this app from Doze-style battery optimisation.
     * Querying needs no permission; only *requesting* the exemption does, which
     * is why the UI opens the Settings screen instead.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }


    /** A battery reading reduced to the only two fields the alert cares about. */
    data class BatteryReading(val level: Int, val plugged: Boolean)

    /**
     * Extracts level and plug state from an ACTION_BATTERY_CHANGED intent.
     *
     * Level is computed from EXTRA_LEVEL/EXTRA_SCALE rather than assuming a
     * 0-100 scale: a few devices report a different scale and would otherwise
     * produce nonsense percentages.
     */
    fun readBatteryLevel(intent: Intent): BatteryReading? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return BatteryReading(percent, plugged)
    }

    /** Reads the current battery percentage from the sticky broadcast. */
    fun currentBattery(context: Context): BatteryReading? =
        stickyBattery(context)?.let { readBatteryLevel(it) }

    private fun stickyBattery(context: Context): Intent? = try {
        // A null receiver returns the cached sticky intent without registering
        // anything, so there is nothing to unregister and nothing left running.
        context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    } catch (_: Exception) {
        null
    }

    /** `BatteryManager.BATTERY_PLUGGED_DOCK`, inlined for pre-33 compatibility. */
    private const val DOCK_PLUG_VALUE = 8
}
