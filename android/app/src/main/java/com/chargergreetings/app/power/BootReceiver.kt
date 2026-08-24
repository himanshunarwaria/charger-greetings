package com.chargergreetings.app.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chargergreetings.app.core.GreetingEngine
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.util.Diagnostics

/**
 * Re-baselines the stored power state after a restart or an app update.
 *
 * Without this, the first plug or unplug after every reboot would be judged
 * against whatever the state was before the phone went down — so unplugging the
 * charger while the phone was off and then booting would either produce a
 * greeting nobody asked for, or swallow the next real one.
 *
 * It deliberately never plays anything. Booting is not a power event the user
 * performed.
 *
 * Note that this receiver does not "start" anything: there is no service and no
 * scheduled work. [PowerEventReceiver] is registered in the manifest and is
 * woken by the system on demand, so the app costs nothing between events.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            QUICKBOOT_POWERON,
            HTC_QUICKBOOT_POWERON -> Unit
            else -> return
        }

        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        val engine = GreetingEngine(settings)

        val observed = PowerStatus.read(appContext)
        val note = engine.baseline(observed)

        Diagnostics.log(appContext, "${intent.action?.substringAfterLast('.')}: $note")

        // Get the reliable receiver running again from boot onward -- see
        // PowerWatcherService for why a manifest receiver alone is not enough.
        // BOOT_COMPLETED genuinely is on Android's exemption list, so this part
        // does fire even though the app was never opened this boot.
        if (settings.enabled) PowerWatcherService.start(appContext)
    }

    private companion object {
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
