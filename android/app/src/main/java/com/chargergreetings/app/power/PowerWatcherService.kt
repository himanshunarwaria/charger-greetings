package com.chargergreetings.app.power

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chargergreetings.app.MainActivity
import com.chargergreetings.app.R
import com.chargergreetings.app.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The reliable path for catching charger connect/disconnect while the app is
 * closed.
 *
 * ### Why this exists
 * `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` are **not** on
 * Android's implicit-broadcast exemption list -- confirmed both against the
 * official docs and live, on real Android 16 hardware, where the system
 * explicitly logged refusing delivery to a manifest-registered receiver in a
 * background app ("Background execution not allowed"). No battery-
 * optimisation exemption fixes that; it is a different, non-overridable
 * restriction. See [PowerEventReceiver] for the full account.
 *
 * Android's own documented alternative for exactly this situation is to
 * register the receiver **dynamically**, via `Context.registerReceiver()`,
 * from a component that is actually alive. A foreground service is the
 * standard, Play-policy-compliant way to keep one alive continuously enough
 * to guarantee that.
 *
 * ### What this costs the user
 * A quiet, minimum-importance, permanent notification while the feature is
 * on -- Android requires a foreground service to show one, there is no way
 * around that. It is silent, has no sound or vibration, and disappears the
 * instant the feature is switched off. This is a real, honest trade-off
 * against the "no unnecessary background services" ambition the app started
 * with: given the platform's actual behaviour, *some* long-lived component is
 * required for the app to work as advertised at all.
 *
 * ### Lifecycle
 * Started from [BootReceiver] on boot and from [MainActivity] on open, and
 * stopped the moment the user turns the master switch off. `START_STICKY` so
 * the system tries to bring it back if it is killed under memory pressure,
 * though Android 12+ imposes further limits on that too -- this is best
 * effort, not a guarantee, and is stated as such in the settings screen.
 */
class PowerWatcherService : Service() {

    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerDynamicReceiver()
        Diagnostics.log(this, "PowerWatcherService started (dynamic receiver active)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate() already did the work; nothing extra to do per start,
        // but returning START_STICKY here is what actually takes effect.
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
        receiver = null
        Diagnostics.log(this, "PowerWatcherService stopped")
        super.onDestroy()
    }

    private fun registerDynamicReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val greeting = PowerEventHandler.actionToGreeting(intent.action) ?: return
                // A dynamically-registered receiver's onReceive still has a
                // short execution budget; hop to the service's own scope so
                // playback (which may include a configured delay) is not cut
                // short the way it would be here.
                scope.launch { PowerEventHandler.handle(applicationContext, greeting) }
            }
        }
        receiver = r

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
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

        /** Starts the service if it is not already running. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, PowerWatcherService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the service. Safe to call even if it is not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PowerWatcherService::class.java))
        }
    }
}
