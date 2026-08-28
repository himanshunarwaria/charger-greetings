package com.chargergreetings.app.power

import android.content.Context
import com.chargergreetings.app.core.BatteryAlertEngine
import com.chargergreetings.app.core.GreetingEngine
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.util.Diagnostics

/**
 * The single entry point for turning monitoring on and off.
 *
 * Everything that can start or stop monitoring -- the master toggle, the boot
 * receiver, the watchdog, first launch -- goes through here. Having exactly one
 * code path is what prevents the duplicate services, duplicate receivers and
 * duplicate workers that this app is specifically required to avoid.
 */
object MonitoringController {

    /**
     * Turns monitoring on: records the current charging state as the silent
     * baseline, starts the service, and schedules the watchdog.
     *
     * @return null on success, or a user-presentable reason it could not start.
     */
    fun enable(context: Context): String? {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        settings.enabled = true

        // Baseline BEFORE the receiver goes live. Without this, the very first
        // broadcast after enabling would be judged against a stale state and
        // could fire a greeting the user did not cause.
        baselineSilently(appContext, "monitoring enabled")

        // Watchdog first: if the service start fails (background-start
        // restriction), the watchdog is already scheduled to retry later.
        WatchdogWorker.enqueue(appContext)

        val failure = startService(appContext)
        if (failure != null) settings.lastError = failure
        return failure
    }

    /**
     * Turns monitoring off. Deliberately preserves every user preference --
     * chosen sounds, volume, per-direction toggles -- so re-enabling restores
     * the setup exactly. Only the master switch and the running machinery stop.
     */
    fun disable(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        settings.enabled = false

        // Cancel only this app's own uniquely-named work, never a blanket
        // cancelAll() which would clobber unrelated work.
        WatchdogWorker.cancel(appContext)
        PowerWatcherService.stop(appContext)

        Diagnostics.log(appContext, "Monitoring disabled by user")
    }

    /**
     * Restores monitoring after a reboot or app update, but only if the user
     * had it enabled. Never plays a sound: the current charging state becomes
     * the new baseline.
     */
    fun restoreAfterBoot(context: Context, reason: String) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)

        baselineSilently(appContext, reason)
        settings.lastBootRestoreAt = System.currentTimeMillis()

        if (!settings.enabled) {
            Diagnostics.log(appContext, "$reason: monitoring is off, nothing to restore")
            return
        }

        WatchdogWorker.enqueue(appContext)
        val failure = startService(appContext)
        if (failure != null) {
            settings.lastError = failure
            Diagnostics.log(appContext, "$reason: service start failed -- $failure")
        } else {
            Diagnostics.log(appContext, "$reason: monitoring restored")
        }
    }

    /**
     * Reads the charging state and stores it as the baseline without speaking.
     * This is the rule that stops boot, app launch and service restarts from
     * producing a phantom greeting.
     */
    fun baselineSilently(context: Context, reason: String) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        val engine = GreetingEngine(settings)
        val observed = PowerStatus.read(appContext)
        val note = engine.baseline(observed)
        Diagnostics.log(appContext, "$reason: $note")

        // The battery alert needs its own baseline for the same reason: without
        // it, rebooting while sitting at 100% on the charger would fire the
        // alert immediately, which the brief explicitly forbids.
        val battery = PowerStatus.currentBattery(appContext)
        if (battery != null) {
            val batteryNote = BatteryAlertEngine(settings).baseline(
                level = battery.level,
                plugged = battery.plugged,
                config = settings.batteryAlertConfig()
            )
            Diagnostics.log(appContext, "$reason: $batteryNote")
        }
    }

    /**
     * Starts the foreground service if it is not already up.
     *
     * @return null on success, or a human-readable failure reason. The common
     * failure is ForegroundServiceStartNotAllowedException on Android 12+ when
     * the app is in the background and not exempt from battery optimisation.
     */
    fun startService(context: Context): String? {
        val appContext = context.applicationContext
        if (PowerWatcherService.isRunning(appContext)) return null
        return PowerWatcherService.start(appContext)
    }
}
