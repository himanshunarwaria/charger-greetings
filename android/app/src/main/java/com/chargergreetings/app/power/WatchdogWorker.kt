package com.chargergreetings.app.power

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.util.Diagnostics
import java.util.concurrent.TimeUnit

/**
 * Periodic self-healing check. This is the fix for the "works for a few hours,
 * then goes quiet" failure.
 *
 * ### Why this is needed
 * [PowerWatcherService] used to be started from exactly two places: app launch
 * and BOOT_COMPLETED. If the system or an OEM battery manager killed the
 * service at 2am, *nothing* ever started it again. START_STICKY is a request,
 * not a guarantee -- and on aggressive OEM builds (Xiaomi, Oppo, Vivo,
 * OnePlus, Samsung, Motorola) it is frequently ignored outright. So monitoring
 * silently died and stayed dead until the user happened to reopen the app.
 *
 * WorkManager is the right tool for the repair job because it is the one
 * scheduling primitive on Android that genuinely survives process death,
 * app-standby buckets and reboots: it persists to disk and is rebuilt by the
 * system on boot.
 *
 * ### The 15-minute floor is deliberate, not a compromise
 * WorkManager clamps periodic work to a 15-minute minimum, and that is fine
 * here. This worker is *not* how power events are detected -- the service's
 * dynamically-registered receiver does that, instantly. This only repairs the
 * service if it has died. A worst case of ~15 minutes of downtime after an OEM
 * kill is a vastly better failure mode than "dead until you reopen the app".
 *
 * Nothing here polls: WorkManager batches this with other system work, so it
 * costs no meaningful battery.
 */
class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsRepository(context)

        // The user turned the feature off: cancel ourselves rather than keep
        // waking up forever. Belt and braces -- MonitoringController already
        // cancels this work, but a stale worker must not resurrect the service.
        if (!settings.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            return Result.success()
        }

        // Ask the ActivityManager rather than trusting our own flag: if the
        // process was killed outright, onDestroy never ran and the stored flag
        // would still read "running".
        if (PowerWatcherService.isRunning(context)) return Result.success()

        Diagnostics.log(context, "Watchdog: monitoring enabled but service not running; restarting")
        settings.lastRecoveryAt = System.currentTimeMillis()

        val failure = MonitoringController.startService(context)
        if (failure != null) {
            // Almost always ForegroundServiceStartNotAllowedException: Android
            // 12+ forbids starting a foreground service from the background
            // unless the app is exempt from battery optimisation. That is
            // precisely why the setup screen treats that exemption as required
            // rather than optional -- without it, this recovery path cannot run
            // and the user must reopen the app.
            settings.lastError = failure
            Diagnostics.log(context, "Watchdog: restart blocked -- $failure")
            // success(), not retry(): the block is a policy decision that will not
            // change on a backoff timer, and retrying would burn battery for
            // nothing. The next scheduled run tries again.
            return Result.success()
        }

        Diagnostics.log(context, "Watchdog: service restarted successfully")
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "charger_greetings_watchdog"

        /** Idempotent: KEEP means re-enqueueing never stacks duplicate work. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .addTag(UNIQUE_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Cancels only this app's watchdog, by unique name. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
