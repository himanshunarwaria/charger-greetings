package com.chargergreetings.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rules that the "stops after a few hours / after reboot" fix
 * depends on. These sit alongside GreetingEngineTest and cover the scenarios
 * introduced by the service + watchdog architecture rather than the original
 * event rules.
 *
 * Plain JVM tests: [GreetingEngine] deliberately has no Android types.
 */
class BaselineAndRecoveryTest {

    private class FakeStore(
        override var lastKnownState: PowerState = PowerState.UNKNOWN,
        override var lastGreetingAt: Long = 0L
    ) : GreetingStore

    private var now = 1_000_000L
    private val store = FakeStore()
    private val engine = GreetingEngine(store) { now }
    private val defaults = GreetingConfig(cooldownMs = 2_500L)

    private fun connect(observed: PowerState = PowerState.PLUGGED) =
        engine.onPowerEvent(Greeting.CONNECTED, observed, defaults, silenced = false)

    private fun disconnect(observed: PowerState = PowerState.UNPLUGGED) =
        engine.onPowerEvent(Greeting.DISCONNECTED, observed, defaults, silenced = false)

    private fun assertSpeaks(expected: Greeting, decision: GreetingEngine.Decision) {
        assertTrue(
            "expected to speak $expected but got $decision",
            decision is GreetingEngine.Decision.Speak && decision.greeting == expected
        )
    }

    private fun assertSilent(decision: GreetingEngine.Decision) {
        assertTrue("expected silence but got $decision", decision is GreetingEngine.Decision.Silent)
    }

    // --- service restart must never speak -----------------------------------

    @Test
    fun `service restart while plugged in does not speak`() {
        // Simulates: charging overnight, OEM kills the service at 3am, the
        // watchdog restarts it at 3:15am. Restoring must be silent.
        store.lastKnownState = PowerState.PLUGGED
        engine.baseline(PowerState.PLUGGED)
        assertEquals(PowerState.PLUGGED, store.lastKnownState)
        assertEquals(0L, store.lastGreetingAt)
    }

    @Test
    fun `service restart while unplugged does not speak`() {
        store.lastKnownState = PowerState.UNPLUGGED
        engine.baseline(PowerState.UNPLUGGED)
        assertEquals(PowerState.UNPLUGGED, store.lastKnownState)
        assertEquals(0L, store.lastGreetingAt)
    }

    @Test
    fun `repeated restarts never accumulate a greeting`() {
        store.lastKnownState = PowerState.PLUGGED
        repeat(20) { engine.baseline(PowerState.PLUGGED) }
        assertEquals(0L, store.lastGreetingAt)
    }

    // --- state changing while the monitor was dead --------------------------

    @Test
    fun `plugging in while the service was dead is picked up as baseline not a greeting`() {
        // The service was killed while unplugged; by the time the watchdog
        // restarts it the user has already plugged in. We must not fire a
        // greeting for something we did not observe happening.
        store.lastKnownState = PowerState.UNPLUGGED
        engine.baseline(PowerState.PLUGGED)
        assertEquals(PowerState.PLUGGED, store.lastKnownState)
        assertEquals(0L, store.lastGreetingAt)

        // ...and the next genuine unplug still works normally.
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
    }

    @Test
    fun `after restore the very next real transition still speaks`() {
        store.lastKnownState = PowerState.PLUGGED
        engine.baseline(PowerState.PLUGGED)
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
        now += 5_000
        assertSpeaks(Greeting.CONNECTED, connect())
    }

    // --- reboot sequences ---------------------------------------------------

    @Test
    fun `reboot while plugged in then unplug speaks once`() {
        store.lastKnownState = PowerState.UNKNOWN
        engine.baseline(PowerState.PLUGGED)          // BootReceiver restore
        assertSilent(connect())                       // stray duplicate broadcast
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
    }

    @Test
    fun `reboot while unplugged then plug in speaks once`() {
        store.lastKnownState = PowerState.UNKNOWN
        engine.baseline(PowerState.UNPLUGGED)
        assertSpeaks(Greeting.CONNECTED, connect())
        assertSilent(connect())                       // repeat delivery
    }

    @Test
    fun `unknown reading during early boot does not clobber a good baseline`() {
        // The sticky battery broadcast can be briefly unavailable at boot.
        store.lastKnownState = PowerState.PLUGGED
        engine.baseline(PowerState.UNKNOWN)
        assertEquals(PowerState.PLUGGED, store.lastKnownState)
    }

    // --- long-running behaviour ---------------------------------------------

    @Test
    fun `cooldown does not block a transition hours later`() {
        // Guards against a regression where a stale lastGreetingAt could
        // permanently suppress greetings on a long-lived service.
        store.lastKnownState = PowerState.UNPLUGGED
        assertSpeaks(Greeting.CONNECTED, connect())

        now += 4 * 60 * 60 * 1000L                    // four hours later
        assertSpeaks(Greeting.DISCONNECTED, disconnect())
    }

    @Test
    fun `many transitions over a long session each speak exactly once`() {
        store.lastKnownState = PowerState.UNPLUGGED
        var spoken = 0
        repeat(24) { hour ->
            now += 60 * 60 * 1000L
            val decision = if (hour % 2 == 0) connect() else disconnect()
            if (decision is GreetingEngine.Decision.Speak) spoken++
        }
        assertEquals("every alternating transition should speak", 24, spoken)
    }
}
