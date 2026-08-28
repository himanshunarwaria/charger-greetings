package com.chargergreetings.app.power

import android.content.Context
import com.chargergreetings.app.core.SettingsRepository
import java.util.Calendar

/**
 * The single place that decides whether an *automatic* sound should stay quiet.
 *
 * Having one function for this is the point: quiet hours and silent mode must
 * apply identically to connect, disconnect and the battery alert. Three copies
 * of this check would drift.
 *
 * Manual previews deliberately do not go through here -- the user pressed a
 * button and expects to hear something.
 */
object SoundSuppression {

    /** Why a sound was suppressed, or null when it may play. */
    fun reasonToStayQuiet(context: Context, nowMinuteOfDay: Int = currentMinuteOfDay()): String? {
        val settings = SettingsRepository(context)

        if (settings.quietHours.contains(nowMinuteOfDay)) {
            return "quiet hours (${settings.quietHours.describe()})"
        }

        // Silent/vibrate and DND are read through PowerStatus, which also
        // treats media volume at zero as "the user wants no sound".
        if (settings.respectSilentMode && PowerStatus.isSilenced(context)) {
            return "device is silenced"
        }

        return null
    }

    fun isSuppressed(context: Context): Boolean = reasonToStayQuiet(context) != null

    fun currentMinuteOfDay(): Int {
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }
}
