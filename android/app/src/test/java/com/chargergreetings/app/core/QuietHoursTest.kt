package com.chargergreetings.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quiet hours, with the midnight-crossing case as the main event.
 *
 * A naive `start <= now && now < end` check silently never matches an overnight
 * window, which is the only window most people actually want. These tests exist
 * to make that failure impossible to reintroduce.
 */
class QuietHoursTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    private fun window(startHour: Int, endHour: Int) =
        QuietHours(enabled = true, startMinuteOfDay = at(startHour), endMinuteOfDay = at(endHour))

    // --- same-day windows ---------------------------------------------------

    @Test
    fun `same day window covers only its own range`() {
        val w = window(9, 17)                       // 09:00 - 17:00
        assertFalse(w.contains(at(8, 59)))
        assertTrue(w.contains(at(9)))               // start is inclusive
        assertTrue(w.contains(at(12)))
        assertTrue(w.contains(at(16, 59)))
        assertFalse(w.contains(at(17)))             // end is exclusive
        assertFalse(w.contains(at(23)))
    }

    // --- overnight windows: the case that matters ---------------------------

    @Test
    fun `overnight window covers the night, not the day`() {
        val w = window(23, 7)                       // 23:00 - 07:00
        assertTrue(w.contains(at(23)))              // start
        assertTrue(w.contains(at(23, 30)))
        assertTrue(w.contains(at(0)))               // midnight
        assertTrue(w.contains(at(3)))               // the middle of the night
        assertTrue(w.contains(at(6, 59)))
        assertFalse(w.contains(at(7)))              // end is exclusive
        assertFalse(w.contains(at(12)))             // midday must be loud
        assertFalse(w.contains(at(22, 59)))
    }

    @Test
    fun `overnight window one minute either side of midnight`() {
        val w = window(23, 7)
        assertTrue(w.contains(at(23, 59)))
        assertTrue(w.contains(at(0, 1)))
    }

    @Test
    fun `late night to early morning short window`() {
        val w = QuietHours(enabled = true, startMinuteOfDay = at(1), endMinuteOfDay = at(2))
        assertFalse(w.contains(at(0, 59)))
        assertTrue(w.contains(at(1, 30)))
        assertFalse(w.contains(at(2)))
    }

    // --- degenerate and disabled cases --------------------------------------

    @Test
    fun `disabled window never matches`() {
        val w = QuietHours(enabled = false, startMinuteOfDay = at(23), endMinuteOfDay = at(7))
        assertFalse(w.contains(at(3)))
        assertFalse(w.contains(at(23, 30)))
    }

    @Test
    fun `equal start and end is treated as off, not as always`() {
        // The dangerous reading: wrapping logic would make this match everything
        // and silently kill every sound the app makes.
        val w = QuietHours(enabled = true, startMinuteOfDay = at(22), endMinuteOfDay = at(22))
        assertFalse(w.contains(at(22)))
        assertFalse(w.contains(at(3)))
        assertFalse(w.contains(at(12)))
    }

    @Test
    fun `out of range minutes are normalised rather than crashing`() {
        val w = window(23, 7)
        assertTrue(w.contains(at(24)))              // 24:00 == 00:00
        assertTrue(w.contains(-60))                 // -01:00 == 23:00
    }

    @Test
    fun `formatting is zero padded 24 hour`() {
        assertEquals("00:00", QuietHours.format(0))
        assertEquals("07:00", QuietHours.format(at(7)))
        assertEquals("23:30", QuietHours.format(at(23, 30)))
    }

    @Test
    fun `describe reads sensibly in both states`() {
        assertEquals("Off", QuietHours.DISABLED.describe())
        assertEquals("23:00 to 07:00", window(23, 7).describe())
    }
}
