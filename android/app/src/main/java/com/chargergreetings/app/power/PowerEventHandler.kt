package com.chargergreetings.app.power

import android.content.Context
import android.content.Intent
import com.chargergreetings.app.audio.GreetingPlayer
import com.chargergreetings.app.core.BatteryAlertEngine
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.core.GreetingEngine
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.core.SoundSlot
import com.chargergreetings.app.util.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a power or battery event into a decision and, possibly, a sound.
 *
 * Shared by [PowerWatcherService] (the reliable path) and [PowerEventReceiver]
 * (a best-effort second path). The dedup and cooldown rules in [GreetingEngine]
 * make it safe if both ever fire for the same physical event.
 */
object PowerEventHandler {

    fun actionToGreeting(action: String?): Greeting? = when (action) {
        Intent.ACTION_POWER_CONNECTED -> Greeting.CONNECTED
        Intent.ACTION_POWER_DISCONNECTED -> Greeting.DISCONNECTED
        else -> null
    }

    /** Evaluates the charger-event rules and plays if warranted. */
    suspend fun handle(context: Context, claimed: Greeting) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        val engine = GreetingEngine(settings)

        val observed = PowerStatus.read(appContext)
        val kind = PowerStatus.chargeKind(appContext)

        // Suppression is evaluated here, not inside GreetingEngine, so the
        // engine stays free of Android types and the same rule applies to the
        // battery alert below.
        val quietReason = SoundSuppression.reasonToStayQuiet(appContext)

        val decision = engine.onPowerEvent(
            claimed = claimed,
            observed = observed,
            // respectSilentMode is forced true here on purpose. SoundSuppression
            // has ALREADY applied the user's silent-mode preference, and it also
            // covers quiet hours, which is an independent setting. Passing the
            // raw preference would make GreetingEngine re-gate the combined
            // result, so turning off "respect silent mode" would silently
            // disable quiet hours too.
            config = settings.config().copy(respectSilentMode = true),
            silenced = quietReason != null
        )

        settings.lastEventAt = System.currentTimeMillis()

        when (decision) {
            is GreetingEngine.Decision.Silent -> {
                val reason = quietReason?.let { "${decision.reason} ($it)" } ?: decision.reason
                settings.lastEventDescription = "${claimed.name} (${kind.label}) - $reason"
                Diagnostics.log(appContext, "${claimed.name} (${kind.label}) -> $reason")
            }

            is GreetingEngine.Decision.Speak -> {
                settings.lastEventDescription = "${claimed.name} (${kind.label}) - played"
                Diagnostics.log(appContext, "${claimed.name} (${kind.label}) -> playing")
                play(appContext, SoundSlot.forGreeting(decision.greeting), settings)
            }
        }
    }

    /**
     * Evaluates a battery-level reading. Called from the service's dynamically
     * registered ACTION_BATTERY_CHANGED receiver.
     */
    suspend fun handleBatteryLevel(context: Context, level: Int, plugged: Boolean) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)

        if (!settings.enabled) return

        val engine = BatteryAlertEngine(settings)
        val quietReason = SoundSuppression.reasonToStayQuiet(appContext)

        val decision = engine.onBatteryReading(
            level = level,
            plugged = plugged,
            config = settings.batteryAlertConfig(),
            suppressed = quietReason != null
        )

        when (decision) {
            is BatteryAlertEngine.Decision.Silent -> {
                // Deliberately not logged for every reading: BATTERY_CHANGED
                // fires many times a minute and would drown the log. Only the
                // interesting suppressions are recorded.
                if (quietReason != null) {
                    Diagnostics.log(appContext, "Battery $level% -> ${decision.reason}")
                }
            }

            is BatteryAlertEngine.Decision.Alert -> {
                settings.lastEventAt = System.currentTimeMillis()
                settings.lastEventDescription = "Battery reached $level% - played"
                Diagnostics.log(appContext, "Battery reached $level% -> playing alert")
                play(appContext, SoundSlot.BATTERY_ALERT, settings)
            }
        }
    }

    private suspend fun play(context: Context, slot: SoundSlot, settings: SettingsRepository) {
        try {
            val delayMs = settings.delayMs
            if (delayMs > 0) delay(delayMs.toLong())

            val outcome = withTimeoutOrNull(PLAYBACK_BUDGET_MS) {
                GreetingPlayer(context).play(slot) ?: OK
            }
            when (outcome) {
                OK -> {
                    settings.lastPlaybackAt = System.currentTimeMillis()
                    settings.lastError = null
                }
                null -> {
                    settings.lastError = "Playback timed out (audio system did not respond)"
                    Diagnostics.log(context, "playback timed out")
                }
                else -> {
                    settings.lastError = "Playback problem: $outcome"
                    Diagnostics.log(context, "playback problem: $outcome")
                }
            }
        } catch (e: Exception) {
            settings.lastError = "Playback failed: ${e.message}"
            Diagnostics.log(context, "playback threw: ${e.message}")
        }
    }

    private const val OK = "ok"

    /**
     * Ceiling well inside a receiver's ~10 s grace period, and long enough for
     * the 10-second playback limit plus a 3-second delay plus slow Bluetooth
     * routing.
     */
    private const val PLAYBACK_BUDGET_MS = 15_000L
}
