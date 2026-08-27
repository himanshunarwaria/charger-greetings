package com.chargergreetings.app.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chargergreetings.app.util.Diagnostics

/**
 * Restores monitoring after a reboot or an app update.
 *
 * ### Why this is on the critical path
 * Without it, every reboot silently ended monitoring until the user next opened
 * the app -- one of the two failures this app was reported for.
 *
 * BOOT_COMPLETED *is* on Android's implicit-broadcast exemption list (unlike the
 * power broadcasts), so this receiver genuinely does fire even though the app
 * has not been opened since boot. Receiving it also places the app on a
 * temporary allowlist that permits starting a foreground service from the
 * background, which is what makes the restore actually work on Android 12+.
 *
 * ### The honest limitation
 * If the user force-stops the app from Settings, Android puts it in the stopped
 * state and delivers *no* broadcasts at all, including this one, until the app
 * is manually opened again. That is deliberate platform behaviour and no app
 * can work around it. Several OEMs (Xiaomi, Oppo, Vivo, OnePlus) additionally
 * gate BOOT_COMPLETED behind their own "auto-start" permission; the setup
 * screen walks the user to that setting per manufacturer.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val recognised = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_LOCKED_BOOT_COMPLETED ||
            action == QUICKBOOT_POWERON ||
            action == HTC_QUICKBOOT_POWERON
        if (!recognised) return

        val appContext = context.applicationContext
        val reason = action.substringAfterLast('.')

        // goAsync: starting a service and touching SharedPreferences is quick,
        // but a boot-time device is heavily contended and onReceive returning
        // early could see the process killed mid-restore.
        val pending = goAsync()
        try {
            // LOCKED_BOOT_COMPLETED arrives before the user unlocks, while
            // credential-encrypted storage is still unavailable. This receiver
            // is not directBootAware, so in practice we are only called for it
            // on devices without file-based encryption -- but guard anyway
            // rather than risk reading preferences that cannot be decrypted.
            if (action == ACTION_LOCKED_BOOT_COMPLETED) {
                Diagnostics.log(appContext, reason + ": waiting for unlock before restoring")
                return
            }

            MonitoringController.restoreAfterBoot(appContext, reason)
        } catch (e: Exception) {
            // Never let a boot receiver crash: on some OEM builds a crash here
            // gets the app flagged and excluded from future boot broadcasts.
            Diagnostics.log(appContext, reason + ": restore threw -- " + e.message)
        } finally {
            try {
                pending.finish()
            } catch (_: Exception) {
                // Nothing useful left to do if the result is already gone.
            }
        }
    }

    private companion object {
        const val ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
