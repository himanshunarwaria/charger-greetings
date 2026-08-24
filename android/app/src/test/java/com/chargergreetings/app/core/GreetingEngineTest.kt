package com.chargergreetings.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the decision rules.
 *
 * These are plain JVM tests — no emulator, no Robolectric — because
 * [GreetingEngine] deliberately has no Android dependencies. Every edge case
 * from the reliability checklist that can be expressed as a decision is here.
 *
 * Run with:  ./gradlew :app:testDebugUnitTest
 */
class GreetingEngineTest {

    /** In-memory [GreetingStore]; stands in for SharedPreferences. */
    private class FakeStore(
        override var lastKnownState: PowerState = PowerState.UNKNOWN,
        override var lastGreetingAt: Long = 0L
    ) : GreetingStore

    private var now = 1_000_000L
    private val store = FakeStore()
    private val engine = GreetingEngine(store) { now }

    private val defaults = GreetingConfig(cooldownMs = 2_500L)

    private fun connect(
        observed: PowerState = PowerState.PLUGGED,
        config: GreetingConfig = defaults,
        silenced: Boolean = false
    ) = engine.onPowerEvent(Greeting.CONNECTED, observed, config, silenced)

    private fun disconnect(
        observed: PowerState = PowerState.UNPLUGGED,
        config: GreetingConfig = defaults,
        silenced: Boolean = false
    ) = engine.onPowerEvent(Greeting.DISCONNECTED, observed, config, silenced)

    private fun assertSpeaks(expected: Greeting, decision: GreetingEngine.Decision) {
        assertTrue(
            "expected to speak $expected but got $decision",
            decision is GreetingEngine.Decision.Speak && decision.greeting == expected
        )
    }

    private fun assertSilent(decision: GreetingEngine.Decision) {
        assertTrue("expected silence but got $decision", decision is GreetingEngine.Decision.Silent)
    }

    // --- the happy paths ----------------------------------------------------

    @Test
    fun `connecting from battery speaks the connect greeting`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(Greeting.CONNECTED, connect())
        assertEquals(PowerState.PLUGGED, store.lastKnownState)
    }

    @Test
    fun `disconnecting from AC speaks the disconnect greeting`() {
        store.lastKnownState = PowerState.PLUGGED
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
        assertEquals(PowerState.UNPLUGGED, store.lastKnownState)
    }

    // --- launching and booting must be silent -------------------------------

    @Test
    fun `baseline never speaks and records the state`() {
        store.lastKnownState = PowerState.UNPLUGGED
        engine.baseline(PowerState.PLUGGED)
        assertEquals(PowerState.PLUGGED, store.lastKnownState)
        assertEquals(0L, store.lastGreetingAt)
    }

    @Test
    fun `baseline ignores an unknown reading`() {
        store.lastKnownState = PowerState.PLUGGED
        engine.baseline(PowerState.UNKNOWN)
        assertEquals(PowerState.PLUGGED, store.lastKnownState)
    }

    @Test
    fun `an event matching the baseline is silent`() {
        // This is the "app launched while already charging" case: the baseline
        // is PLUGGED, so a stray CONNECTED broadcast must not greet.
        store.lastKnownState = PowerState.PLUGGED
        assertSilent(connect())
    }

    // --- noisy hardware -----------------------------------------------------

    @Test
    fun `duplicate broadcasts produce exactly one greeting`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(Greeting.CONNECTED, connect())
        repeat(10) { assertSilent(connect()) }
    }

    @Test
    fun `a broadcast contradicted by the battery service is ignored`() {
        // The cable was pulled straight back out: the CONNECTED broadcast
        // arrives but the battery service already reports UNPLUGGED.
        store.lastKnownState = PowerState.UNPLUGGED
        assertSilent(connect(observed = PowerState.UNPLUGGED))
        assertEquals(PowerState.UNPLUGGED, store.lastKnownState)
    }

    @Test
    fun `an unknown observed state trusts the broadcast`() {
        // Right after boot the sticky battery intent can be missing. The
        // broadcast itself is still strong evidence, so we act on it.
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(Greeting.CONNECTED, connect(observed = PowerState.UNKNOWN))
    }

    @Test
    fun `rapid reconnect within the cooldown speaks only once`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(Greeting.CONNECTED, connect())

        now += 200
        assertSilent(disconnect())          // real change, but far too soon

        now += 200
        assertSilent(connect())             // and back again
    }

    @Test
    fun `state is still tracked while the cooldown suppresses speech`() {
        store.lastKnownState = PowerState.UNPLUGGED
        connect()
        now += 100
        disconnect()
        // Even though nothing was spoken, the engine must know we are unplugged,
        // otherwise the next connect would look like a duplicate.
        assertEquals(PowerState.UNPLUGGED, store.lastKnownState)
    }

    @Test
    fun `greetings resume once the cooldown expires`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(Greeting.CONNECTED, connect())
        now += 3_000
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
    }

    @Test
    fun `a backwards clock does not lock out greetings forever`() {
        store.lastKnownState = PowerState.UNPLUGGED
        connect()
        // Time zone change or NTP correction moves the clock back a day.
        now -= 86_400_000L
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
    }

    // --- preferences --------------------------------------------------------

    @Test
    fun `master switch off silences everything`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSilent(connect(config = defaults.copy(enabled = false)))
    }

    @Test
    fun `each direction can be switched off independently`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSilent(connect(config = defaults.copy(playOnConnect = false)))

        now += 5_000
        assertSpeaks(Greeting.DISCONNECTED, disconnect(config = defaults.copy(playOnConnect = false)))
    }

    @Test
    fun `a suppressed greeting does not start the cooldown`() {
        store.lastKnownState = PowerState.UNPLUGGED
        connect(config = defaults.copy(playOnConnect = false))
        now += 10
        // The connect was switched off, so the disconnect that follows almost
        // immediately should still be allowed to speak.
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
    }

    @Test
    fun `silent mode is respected when asked`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSilent(connect(silenced = true))
    }

    @Test
    fun `silent mode is ignored when the user opts out`() {
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(
            Greeting.CONNECTED,
            connect(config = defaults.copy(respectSilentMode = false), silenced = true)
        )
    }

    @Test
    fun `zero cooldown allows back to back greetings`() {
        val noCooldown = defaults.copy(cooldownMs = 0L)
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(Greeting.CONNECTED, connect(config = noCooldown))
        assertSpeaks(Greeting.DISCONNECTED, disconnect(config = noCooldown))
        assertSpeaks(Greeting.CONNECTED, connect(config = noCooldown))
    }

    // --- reboot sequence ----------------------------------------------------

    @Test
    fun `unplugging while the phone is off does not greet on boot`() {
        store.lastKnownState = PowerState.PLUGGED     // was charging when powered down
        engine.baseline(PowerState.UNPLUGGED)         // BootReceiver re-baselines
        assertEquals(PowerState.UNPLUGGED, store.lastKnownState)

        // And the next real connect still works.
        assertSpeaks(Greeting.CONNECTED, connect())
    }
}
