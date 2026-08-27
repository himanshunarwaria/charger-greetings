package com.chargergreetings.app.power

import android.content.Context
import android.content.Intent
import com.chargergreetings.app.audio.GreetingPlayer
import com.chargergreetings.app.core.Greeting
import com.chargergreetings.app.core.GreetingEngine
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.util.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single place that turns a power-change [Intent] into a decision and,
 * possibly, a spoken greeting.
 *
 * Shared by [PowerWatcherService] (the reliable path -- see its class comment
 * for why it exists) and [PowerEventReceiver] (kept as a best-effort second
 * path; costs nothing, and the dedup/cooldown logic in [GreetingEngine] makes
 * it safe if both ever fire for the same physical event).
 */
object PowerEventHandler {

    private const val PLAYBACK_BUDGET_MS = 7_000L
    private const val OK = "ok"

    fun actionToGreeting(action: String?): Greeting? = when (action) {
        Intent.ACTION_POWER_CONNECTED -> Greeting.CONNECTED
        Intent.ACTION_POWER_DISCONNECTED -> Greeting.DISCONNECTED
        else -> null
    }

    /** Evaluates the engine's rules and, if warranted, plays the greeting. Suspends until done. */
    suspend fun handle(context: Context, claimed: Greeting) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        val engine = GreetingEngine(settings)

        val observed = PowerStatus.read(appContext)
        val kind = PowerStatus.chargeKind(appContext)
        val silenced = PowerStatus.isSilenced(appContext)

        val decision = engine.onPowerEvent(
            claimed = claimed,
            observed = observed,
            config = settings.config(),
            silenced = silenced
        )

        // Recorded for the diagnostics panel so a user can see what the app
        // decided and why, without needing adb or a developer.
        settings.lastEventAt = System.currentTimeMillis()

        when (decision) {
            is GreetingEngine.Decision.Silent -> {
                settings.lastEventDescription =
                    "${claimed.name} (${kind.label}) - ${decision.reason}"
                Diagnostics.log(appContext, "${claimed.name} (${kind.label}) -> ${decision.reason}")
            }

            is GreetingEngine.Decision.Speak -> {
                settings.lastEventDescription = "${claimed.name} (${kind.label}) - played"
                Diagnostics.log(appContext, "${claimed.name} (${kind.label}) -> speaking")
                speak(appContext, decision.greeting, settings)
            }
        }
    }

    private suspend fun speak(context: Context, greeting: Greeting, settings: SettingsRepository) {
        try {
            val delayMs = settings.delayMs
            if (delayMs > 0) delay(delayMs.toLong())

            val outcome = withTimeoutOrNull(PLAYBACK_BUDGET_MS) {
                GreetingPlayer(context).play(greeting, settings.volumePercent) ?: OK
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
}
