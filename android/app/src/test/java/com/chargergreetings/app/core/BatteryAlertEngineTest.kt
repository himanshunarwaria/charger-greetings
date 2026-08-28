package com.chargergreetings.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery alert must fire exactly once per genuine threshold crossing.
 *
 * ACTION_BATTERY_CHANGED is extremely chatty -- many times a minute while
 * charging, for temperature and voltage as well as level. A level-triggered
 * check would fire on every one of those readings for as long as the battery
 * sat at or above the threshold. These tests pin the edge-triggered behaviour.
 */
class BatteryAlertEngineTest {

    private class FakeStore(override var batteryAlertArmed: Boolean = true) : BatteryAlertStore

    private val store = FakeStore()
    private val engine = BatteryAlertEngine(store)
    private val enabled = BatteryAlertConfig(enabled = true, thresholdPercent = 80)

    private fun reading(level: Int, plugged: Boolean = true, suppressed: Boolean = false) =
        engine.onBatteryReading(level, plugged, enabled, suppressed)

    private fun assertAlerts(decision: BatteryAlertEngine.Decision) {
        assertTrue("expected an alert but got $decision", decision is BatteryAlertEngine.Decision.Alert)
    }

    private fun assertSilent(decision: BatteryAlertEngine.Decision) {
        assertTrue("expected silence but got $decision", decision is BatteryAlertEngine.Decision.Silent)
    }

    // --- the core contract --------------------------------------------------

    @Test
    fun `fires once when charging crosses the threshold`() {
        assertSilent(reading(78))
        assertSilent(reading(79))
        assertAlerts(reading(80))
    }

    @Test
    fun `does not repeat for every subsequent broadcast at the same level`() {
        assertAlerts(reading(80))
        repeat(50) { assertSilent(reading(80)) }
    }

    @Test
    fun `does not repeat as the battery keeps climbing`() {
        assertAlerts(reading(80))
        assertSilent(reading(81))
        assertSilent(reading(95))
        assertSilent(reading(100))
    }

    @Test
    fun `crossing straight past the threshold still fires exactly once`() {
        // Some devices jump several percent between readings.
        assertSilent(reading(70))
        assertAlerts(reading(85))
        assertSilent(reading(90))
    }

    // --- re-arming ----------------------------------------------------------

    @Test
    fun `re-arms only after dropping below the threshold`() {
        assertAlerts(reading(80))
        assertSilent(reading(80))
        assertSilent(reading(79))       // drops below: re-arms, but stays quiet now
        assertAlerts(reading(80))       // genuine second crossing
    }

    @Test
    fun `unplugging above the threshold does not re-arm`() {
        // Regression: pulling the cable used to re-arm on its own, so plugging
        // straight back in fired a second alert although the battery never
        // dropped below the threshold. Caught on an emulator, not by a test.
        assertAlerts(reading(80))
        assertSilent(reading(85, plugged = false))   // unplugged, still high
        assertSilent(reading(85))                    // back in: nothing was crossed
    }

    @Test
    fun `unplug, drain below the threshold, then charge past it alerts again`() {
        assertAlerts(reading(80))
        assertSilent(reading(85, plugged = false))
        assertSilent(reading(70, plugged = false))   // genuinely below: re-arms
        assertSilent(reading(75))                    // charging again, still under
        assertAlerts(reading(80))                    // a real second crossing
    }

    @Test
    fun `never alerts while unplugged`() {
        store.batteryAlertArmed = true
        assertSilent(reading(90, plugged = false))
        assertSilent(reading(100, plugged = false))
    }

    // --- start-up and reboot ------------------------------------------------

    @Test
    fun `baseline while already above the threshold holds the alert`() {
        // Rebooting at 100% on the charger must not fire immediately.
        engine.baseline(level = 100, plugged = true, config = enabled)
        assertFalse(store.batteryAlertArmed)
        assertSilent(reading(100))
    }

    @Test
    fun `baseline below the threshold arms for the coming crossing`() {
        engine.baseline(level = 40, plugged = true, config = enabled)
        assertTrue(store.batteryAlertArmed)
        assertAlerts(reading(80))
    }

    @Test
    fun `baseline above the threshold holds even when unplugged`() {
        // 95% with the cable out is still 95%: charging from here reaches
        // nothing new, so starting armed would fire on the next plug-in.
        engine.baseline(level = 95, plugged = false, config = enabled)
        assertFalse(store.batteryAlertArmed)
    }

    @Test
    fun `baseline below the threshold arms whether or not it is charging`() {
        engine.baseline(level = 40, plugged = false, config = enabled)
        assertTrue(store.batteryAlertArmed)
    }

    @Test
    fun `held baseline re-arms once the battery drops`() {
        engine.baseline(level = 100, plugged = true, config = enabled)
        assertSilent(reading(100))
        assertSilent(reading(75))       // drops below
        assertAlerts(reading(80))       // and back up: a real crossing
    }

    // --- settings and suppression -------------------------------------------

    @Test
    fun `disabled alert never fires`() {
        val off = BatteryAlertConfig(enabled = false, thresholdPercent = 80)
        assertSilent(engine.onBatteryReading(80, true, off, suppressed = false))
        assertSilent(engine.onBatteryReading(100, true, off, suppressed = false))
    }

    @Test
    fun `suppressed crossing is skipped, not queued for later`() {
        // Quiet hours or silent mode. The brief is explicit that a suppressed
        // sound is a missed sound, never a delayed one -- so it must still
        // disarm, or it would fire the moment quiet hours ended.
        assertSilent(reading(80, suppressed = true))
        assertFalse(store.batteryAlertArmed)
        assertSilent(reading(80))       // no catch-up alert afterwards
    }

    @Test
    fun `threshold of 100 percent works`() {
        val full = BatteryAlertConfig(enabled = true, thresholdPercent = 100)
        assertSilent(engine.onBatteryReading(99, true, full, false))
        assertTrue(engine.onBatteryReading(100, true, full, false) is BatteryAlertEngine.Decision.Alert)
    }

    @Test
    fun `threshold of 1 percent works`() {
        val low = BatteryAlertConfig(enabled = true, thresholdPercent = 1)
        engine.baseline(level = 0, plugged = true, config = low)
        assertTrue(engine.onBatteryReading(1, true, low, false) is BatteryAlertEngine.Decision.Alert)
    }

    @Test
    fun `implausible levels are ignored rather than trusted`() {
        assertSilent(reading(-1))
        assertSilent(reading(101))
        // and a nonsense reading must not have disarmed us
        assertAlerts(reading(80))
    }

    // --- a full realistic day -----------------------------------------------

    @Test
    fun `a full charge cycle produces exactly one alert`() {
        engine.baseline(level = 35, plugged = false, config = enabled)
        var alerts = 0

        // Plug in at 35%, charge to 100% with many readings at each level.
        for (level in 35..100) {
            repeat(4) {
                if (reading(level) is BatteryAlertEngine.Decision.Alert) alerts++
            }
        }
        // Sit at 100% for a while, then unplug and drain.
        repeat(20) { if (reading(100) is BatteryAlertEngine.Decision.Alert) alerts++ }
        for (level in 100 downTo 30) {
            if (reading(level, plugged = false) is BatteryAlertEngine.Decision.Alert) alerts++
        }

        assertTrue("expected exactly one alert, got $alerts", alerts == 1)
    }
}
