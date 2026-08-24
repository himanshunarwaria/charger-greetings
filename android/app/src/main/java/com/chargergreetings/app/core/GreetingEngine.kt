package com.chargergreetings.app.core

/**
 * Decides whether a power broadcast deserves a greeting.
 *
 * This class is deliberately free of every Android type. On Android a
 * [android.content.BroadcastReceiver] may run in a brand-new process each time,
 * so none of the decision state can live in memory — it is all read from and
 * written back to a [GreetingStore]. Keeping the rules pure means they can be
 * proved correct with ordinary JVM unit tests (see `GreetingEngineTest`)
 * instead of by plugging a cable in fifty times.
 *
 * The rules, in order:
 *
 *  1. **Contradiction check.** The broadcast says "connected", but the battery
 *     service already says "unplugged"? The cable bounced or was pulled back
 *     out. Record the truth, say nothing.
 *  2. **Duplicate check.** Android can deliver the same transition more than
 *     once, and some OEM ROMs are enthusiastic about it. If the state is
 *     already what the broadcast claims, this is a repeat.
 *  3. **Preferences.** Master switch, then the per-direction switch.
 *  4. **Cooldown.** A hard floor between greetings so a burst of real
 *     transitions still produces one voice, not a chorus.
 *  5. **Silent mode**, if the user asked us to respect it.
 */
class GreetingEngine(
    private val store: GreetingStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The outcome of handling one power broadcast. */
    sealed class Decision {
        /** Play this greeting now. */
        data class Speak(val greeting: Greeting) : Decision()

        /** Do nothing. [reason] is written to the diagnostic log verbatim. */
        data class Silent(val reason: String) : Decision()
    }

    /**
     * Records the current power state without ever making a sound.
     *
     * Called on app launch, on boot and after an app update. This is what stops
     * a greeting firing merely because you opened the app, signed in or
     * restarted the phone while it happened to be charging.
     */
    fun baseline(observed: PowerState): String {
        if (!observed.isKnown) return "baseline skipped: power state unavailable"
        val previous = store.lastKnownState
        store.lastKnownState = observed
        return "baselined silently: $previous -> $observed"
    }

    /**
     * @param claimed    what the broadcast asserts happened
     * @param observed   what the battery service reports right now; may be
     *                   [PowerState.UNKNOWN] if the sticky broadcast was absent
     * @param config     the user's current preferences
     * @param silenced   true when the device is in silent/vibrate or DND
     */
    fun onPowerEvent(
        claimed: Greeting,
        observed: PowerState,
        config: GreetingConfig,
        silenced: Boolean
    ): Decision {
        val expected = when (claimed) {
            Greeting.CONNECTED -> PowerState.PLUGGED
            Greeting.DISCONNECTED -> PowerState.UNPLUGGED
        }

        // 1. The broadcast and reality disagree: the cable is already back the
        //    other way. Trust the live reading and stay quiet.
        if (observed.isKnown && observed != expected) {
            store.lastKnownState = observed
            return Decision.Silent(
                "ignored: broadcast said ${expected.name.lowercase()} " +
                    "but the battery service reports ${observed.name.lowercase()}"
            )
        }

        // 2. Already in this state: a repeat delivery of a transition we handled.
        val previous = store.lastKnownState
        if (previous == expected) {
            return Decision.Silent("ignored: already ${expected.name.lowercase()}")
        }

        // The state genuinely moved. Record it even if we end up not speaking,
        // so the *next* event is judged against the truth.
        store.lastKnownState = expected

        // 3. Preferences.
        if (!config.enabled) return Decision.Silent("skipped: greetings are switched off")

        val wanted = when (claimed) {
            Greeting.CONNECTED -> config.playOnConnect
            Greeting.DISCONNECTED -> config.playOnDisconnect
        }
        if (!wanted) return Decision.Silent("skipped: this greeting is switched off")

        // 4. Cooldown. A negative elapsed time means the clock moved backwards
        //    (time zone change, NTP correction) — treat that as "long enough ago".
        val now = clock()
        val elapsed = now - store.lastGreetingAt
        if (elapsed in 0 until config.cooldownMs) {
            return Decision.Silent("skipped: only ${elapsed}ms since the last greeting")
        }

        // 5. Silent mode / Do Not Disturb.
        if (config.respectSilentMode && silenced) {
            return Decision.Silent("skipped: the device is silenced")
        }

        store.lastGreetingAt = now
        return Decision.Speak(claimed)
    }
}

/**
 * Persistent slice of state the engine needs. Backed by SharedPreferences in
 * the app and by a plain object in tests.
 */
interface GreetingStore {
    var lastKnownState: PowerState

    /** Wall-clock time of the last greeting actually played, in millis. */
    var lastGreetingAt: Long
}

/** Snapshot of the user's preferences at the moment an event arrives. */
data class GreetingConfig(
    val enabled: Boolean = true,
    val playOnConnect: Boolean = true,
    val playOnDisconnect: Boolean = true,
    val cooldownMs: Long = 2_500L,
    val respectSilentMode: Boolean = true
)
