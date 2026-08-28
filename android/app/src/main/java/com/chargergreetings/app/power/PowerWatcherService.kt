package com.chargergreetings.app.power

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chargergreetings.app.MainActivity
import com.chargergreetings.app.R
import com.chargergreetings.app.core.SettingsRepository
import com.chargergreetings.app.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds a dynamically-registered receiver for the power broadcasts, for as long
 * as monitoring is enabled.
 *
 * ### Why a foreground service at all
 * ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED are **not** on Android's
 * implicit-broadcast exemption list. Verified twice: against the official docs,
 * and live on an Android 16 device where the system logged
 * "skipped by policy at enqueue: Background execution not allowed" while
 * refusing to deliver to our manifest receiver. Android's own documented
 * alternative is to register the receiver dynamically from a component that is
 * actually alive, which is what this service is for.
 *
 * ### What it costs the user
 * One silent, minimum-importance, ongoing notification while monitoring is on.
 * Android requires this for any foreground service; there is no way around it.
 * It disappears the moment the master switch goes off.
 *
 * ### Reliability notes
 * - startForeground is wrapped: on Android 12+ it throws when started from the
 *   background without an exemption, and an unhandled throw in onCreate would
 *   crash the app and leave monitoring dead with no diagnosis.
 * - The receiver is registered *before* the notification, so a notification
 *   failure can never cost us the actual monitoring.
 * - [WatchdogWorker] repairs this service if the system or an OEM kills it.
 */
class PowerWatcherService : Service() {

    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Last (level, plugged) actually seen, so the flood of BATTERY_CHANGED
    // ticks that carry no change never reaches the engine or the log.
    private var lastBatteryReading: PowerStatus.BatteryReading? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsRepository(this)

        // Register first. Monitoring is the point of this service; the
        // notification is only the price Android charges for staying alive.
        registerDynamicReceiver()

        val notificationProblem = enterForeground()
        if (notificationProblem != null) {
            settings.lastError = notificationProblem
            Diagnostics.log(this, "Service foreground failed: " + notificationProblem)
            // Without foreground status Android kills this process shortly.
            // Stop cleanly so the watchdog can retry later under better
            // conditions, rather than lingering half-dead.
            stopSelf()
            return
        }

        settings.serviceRunning = true
        settings.lastServiceStartAt = System.currentTimeMillis()
        Diagnostics.log(this, "Service started (receiver active)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY asks Android to recreate this service if the process is
        // killed. It is a request, not a contract: OEM battery managers
        // routinely ignore it, which is exactly why WatchdogWorker exists.
        // A null intent means precisely that -- we were restarted after a kill.
        if (intent == null) {
            Diagnostics.log(this, "Service recreated by system after process death")
        }
        return START_STICKY
    }

    /**
     * Swiping the app out of Recents must not stop monitoring: that is the most
     * common way users "lose" this kind of app. The service keeps running; we
     * only make sure the watchdog is scheduled in case an OEM kills us anyway.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Diagnostics.log(this, "App removed from Recents; monitoring continues")
        WatchdogWorker.enqueue(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Diagnostics.log(this, "Receiver already unregistered: " + e.message)
            }
        }
        receiver = null
        scope.cancel()

        val settings = SettingsRepository(this)
        settings.serviceRunning = false
        settings.lastServiceStopAt = System.currentTimeMillis()

        Diagnostics.log(this, "Service stopped")
        super.onDestroy()
    }

    /**
     * Enters the foreground, trying progressively simpler calls.
     *
     * ### Why this is layered rather than a single call in a try/catch
     * startForegroundService() opens a hard five-second contract: this service
     * MUST successfully call startForeground(), or Android kills the process
     * with ForegroundServiceDidNotStartInTimeException. Catching a failure and
     * calling stopSelf() does NOT satisfy that contract -- it converts a
     * recoverable problem into a guaranteed crash, which is exactly the bug
     * this method now exists to prevent.
     *
     * So every fallback below is an attempt to satisfy the contract by *some*
     * means rather than to fail gracefully:
     *   1. Typed call with our own notification   (the normal path)
     *   2. Untyped call                           (older/odd platform behaviour)
     *   3. Untyped call with a bare-minimum notification built from a platform
     *      icon, in case our own notification or channel is what failed
     *
     * @return null on success, or a user-presentable reason all attempts failed.
     */
    private fun enterForeground(): String? {
        var lastFailure: Exception? = null

        // 1. The normal path: explicit type, our notification.
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return null
        } catch (e: Exception) {
            lastFailure = e
            Diagnostics.log(this, "startForeground (typed) failed: " + e.message)
        }

        // 2. Untyped. Some platform versions and OEM builds reject the typed
        //    overload while accepting the plain one.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Diagnostics.log(this, "startForeground succeeded without an explicit type")
            return null
        } catch (e: Exception) {
            lastFailure = e
            Diagnostics.log(this, "startForeground (untyped) failed: " + e.message)
        }

        // 3. Last resort: a notification that depends on nothing of ours, in
        //    case our drawable or channel is the problem. Satisfying the
        //    contract matters more here than how the notification looks.
        try {
            startForeground(NOTIFICATION_ID, buildFallbackNotification())
            Diagnostics.log(this, "startForeground succeeded with the fallback notification")
            return null
        } catch (e: Exception) {
            lastFailure = e
            Diagnostics.log(this, "startForeground (fallback) failed: " + e.message)
        }

        return describeStartFailure(lastFailure ?: IllegalStateException("unknown"))
    }

    /**
     * The simplest notification that can possibly work: a platform icon and no
     * dependency on our own resources, PendingIntent or channel settings.
     */
    private fun buildFallbackNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun registerDynamicReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            // ACTION_BATTERY_CHANGED can ONLY be received by a dynamically
            // registered receiver -- Android refuses it in a manifest entirely.
            // It is also very chatty (many times a minute while charging),
            // which is why BatteryAlertEngine is edge-triggered rather than
            // level-triggered, and why nothing here is logged per reading.
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val reading = PowerStatus.readBatteryLevel(intent) ?: return
                        // Cheap in-memory filter: only wake the coroutine when
                        // the level or plug state actually moved. Everything
                        // else is one of the many voltage/temperature ticks.
                        if (reading == lastBatteryReading) return
                        lastBatteryReading = reading
                        scope.launch {
                            PowerEventHandler.handleBatteryLevel(
                                applicationContext, reading.level, reading.plugged
                            )
                        }
                    }

                    else -> {
                        val greeting = PowerEventHandler.actionToGreeting(intent.action) ?: return
                        // onReceive has a short budget of its own; hand off to
                        // the service scope so a configured delay or duration
                        // limit cannot be cut short.
                        scope.launch { PowerEventHandler.handle(applicationContext, greeting) }
                    }
                }
            }
        }
        receiver = r

        // RECEIVER_NOT_EXPORTED is correct: these are protected system
        // broadcasts, and no other app has any business triggering us.
        ContextCompat.registerReceiver(
            this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun buildNotification(): Notification {
        ensureChannel()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(R.string.watcher_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watcher_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.watcher_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "power_watcher"
        private const val NOTIFICATION_ID = 1

        /**
         * Turns a foreground-service start failure into something a user can
         * act on. Matched by class name so this still compiles against SDKs
         * older than the one that introduced the exception.
         */
        private fun describeStartFailure(e: Exception): String =
            if (e::class.java.simpleName == "ForegroundServiceStartNotAllowedException") {
                "Android blocked starting the background monitor. " +
                    "Turn off battery optimisation for this app to allow it."
            } else {
                "Could not start the monitor: " + (e.message ?: e::class.java.simpleName)
            }

        /**
         * True if our service is actually alive right now.
         *
         * getRunningServices is deprecated for inspecting *other* apps but still
         * reliably reports your own, which is all we ask. Checked instead of the
         * stored flag because a process killed outright never runs onDestroy,
         * leaving that flag stale and the watchdog blind.
         */
        fun isRunning(context: Context): Boolean = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == PowerWatcherService::class.java.name }
        } catch (e: Exception) {
            Diagnostics.log(context, "isRunning query failed, using stored flag: " + e.message)
            SettingsRepository(context).serviceRunning
        }

        /** @return null on success, or a user-presentable failure reason. */
        fun start(context: Context): String? = try {
            ContextCompat.startForegroundService(
                context, Intent(context, PowerWatcherService::class.java)
            )
            null
        } catch (e: Exception) {
            describeStartFailure(e)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PowerWatcherService::class.java))
        }
    }
}
