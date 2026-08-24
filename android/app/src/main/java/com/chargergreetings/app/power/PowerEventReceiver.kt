package com.chargergreetings.app.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manifest-declared, best-effort second path.
 *
 * ### This is NOT the reliable mechanism -- read this before touching it
 * `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` are **not** on
 * Android's implicit-broadcast exemption list (verified against
 * https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions,
 * and confirmed live on a Pixel-class device running Android 16: the system
 * sends the broadcast and then explicitly logs
 * `skipped by policy at enqueue: Background execution not allowed` when
 * trying to deliver it to a manifest receiver in a background app). An
 * earlier version of this app assumed otherwise; that assumption was wrong.
 *
 * [PowerWatcherService] is the actual mechanism: it registers dynamically
 * with `Context.registerReceiver()` from a running foreground service, which
 * is not subject to this restriction.
 *
 * This receiver stays in the manifest anyway because it costs nothing and
 * costs nothing to leave: on OEM builds or Android versions where delivery
 * happens to still get through (the docs say "still work" in the background,
 * just not reliably), it is a free bonus. [PowerEventHandler] is idempotent
 * with respect to [PowerWatcherService] -- the dedup/cooldown logic in
 * `GreetingEngine` means a duplicate delivery of the same physical event is
 * silently absorbed, never a double greeting.
 */
class PowerEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val greeting = PowerEventHandler.actionToGreeting(intent.action) ?: return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                PowerEventHandler.handle(appContext, greeting)
            } finally {
                try { pending.finish() } catch (_: Exception) { }
            }
        }
    }
}
