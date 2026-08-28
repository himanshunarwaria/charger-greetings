package com.chargergreetings.app.core

/**
 * Decides whether a battery-level reading should fire the one-shot alert.
 *
 * ### The problem this solves
 * `ACTION_BATTERY_CHANGED` is extremely chatty: it fires on level changes,
 * temperature changes, voltage changes, plug changes -- many times a minute
 * while charging. A naive `if (level >= threshold) play()` would fire on every
 * one of those, for as long as the battery sits at or above the threshold.
 *
 * So the alert is **edge-triggered**, not level-triggered, using a single
 * persisted `armed` flag:
 *
 * - Below the threshold (plugged or not)  -> re-arm.
 * - Plugged, at/above threshold, armed     -> fire once, then disarm.
 * - Plugged, at/above threshold, not armed -> silence.
 * - Unplugged                              -> always silent.
 *
 * That yields exactly one alert per genuine crossing, and a replay only after
 * the battery has actually dropped back below the threshold.
 *
 * Like [GreetingEngine] this is pure Kotlin with no Android types, so all of it
 * is unit-testable without a device.
 */
class BatteryAlertEngine(private val store: BatteryAlertStore) {

    sealed class Decision {
        /** Play the battery alert now. */
        data object Alert : Decision()

        /** Do nothing. [reason] goes straight into the diagnostics log. */
        data class Silent(val reason: String) : Decision()
    }

    /**
     * @param level      current battery percentage, 0..100
     * @param plugged    whether external power is connected right now
     * @param config     the user's alert settings
     * @param suppressed true when quiet hours or silent mode say stay quiet
     */
    fun onBatteryReading(
        level: Int,
        plugged: Boolean,
        config: BatteryAlertConfig,
        suppressed: Boolean
    ): Decision {
        if (!config.enabled) return Decision.Silent("battery alert is off")
        if (level < 0 || level > 100) return Decision.Silent("implausible level $level")

        // Arming is a statement about the LEVEL, not about the plug. Re-arming
        // merely because the cable came out would fire a fresh alert the next
        // time it went back in, even though the battery never dropped below the
        // threshold -- breaking the promise made at the top of this class, and
        // the brief's "only once when the battery reaches or crosses" rule.
        // Unplug at 100%, plug straight back in: nothing was reached, so the
        // alert must stay quiet.
        if (level < config.thresholdPercent && !store.batteryAlertArmed) {
            store.batteryAlertArmed = true
        }

        if (!plugged) return Decision.Silent("not charging")

        if (level < config.thresholdPercent) {
            return Decision.Silent("below threshold ($level% < ${config.thresholdPercent}%)")
        }

        // At or above the threshold, and charging.
        if (!store.batteryAlertArmed) {
            return Decision.Silent("already alerted at ${config.thresholdPercent}%")
        }

        // Disarm before returning: the caller may be killed the instant it
        // starts playing, and a lost write here means a duplicate alert.
        store.batteryAlertArmed = false

        if (suppressed) {
            // Deliberately still disarms. A suppressed alert is a missed alert,
            // never a queued one -- the brief is explicit that suppressed
            // sounds must not be replayed later.
            return Decision.Silent("suppressed (quiet hours or silent mode)")
        }

        return Decision.Alert
    }

    /**
     * Establishes the baseline without ever alerting. Called on service start,
     * boot restore and when the user changes the threshold.
     *
     * If the battery is already at or above the threshold we start *disarmed*,
     * which is what stops an alert firing merely because the phone rebooted
     * while sitting at 100% -- whether or not the cable is in.
     */
    fun baseline(level: Int, plugged: Boolean, config: BatteryAlertConfig): String {
        val armed = level < config.thresholdPercent
        store.batteryAlertArmed = armed
        return if (armed) {
            "battery alert armed (at $level%, threshold ${config.thresholdPercent}%)"
        } else {
            "battery alert held (already at $level%, will re-arm below ${config.thresholdPercent}%)"
        }
    }
}

/** The single persisted flag the alert needs to be edge-triggered. */
interface BatteryAlertStore {
    var batteryAlertArmed: Boolean
}

data class BatteryAlertConfig(
    val enabled: Boolean = false,
    val thresholdPercent: Int = 80
)
