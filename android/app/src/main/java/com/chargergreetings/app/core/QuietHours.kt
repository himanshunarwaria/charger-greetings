package com.chargergreetings.app.core

/**
 * Quiet-hours window, stored as minutes past midnight.
 *
 * The whole reason this is its own type with its own tests is the
 * crossing-midnight case: 23:00 to 07:00 is the schedule people actually want,
 * and the naive `start <= now && now < end` check silently never matches it.
 */
data class QuietHours(
    val enabled: Boolean,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int
) {

    /**
     * True when [minuteOfDay] falls inside the window.
     *
     * Start is inclusive, end is exclusive, so 23:00-07:00 covers 23:00 through
     * 06:59 and stops exactly at 07:00.
     */
    fun contains(minuteOfDay: Int): Boolean {
        if (!enabled) return false
        val now = ((minuteOfDay % DAY) + DAY) % DAY
        val start = ((startMinuteOfDay % DAY) + DAY) % DAY
        val end = ((endMinuteOfDay % DAY) + DAY) % DAY

        // Equal start and end means a zero-length window, not "always".
        // Treating it as always-quiet would silently disable every sound.
        if (start == end) return false

        return if (start < end) {
            now >= start && now < end          // same-day window
        } else {
            now >= start || now < end          // wraps past midnight
        }
    }

    fun describe(): String =
        if (!enabled) "Off" else "${format(startMinuteOfDay)} to ${format(endMinuteOfDay)}"

    companion object {
        const val DAY = 24 * 60

        val DISABLED = QuietHours(enabled = false, startMinuteOfDay = 23 * 60, endMinuteOfDay = 7 * 60)

        fun format(minuteOfDay: Int): String {
            val m = ((minuteOfDay % DAY) + DAY) % DAY
            return "%02d:%02d".format(m / 60, m % 60)
        }
    }
}
