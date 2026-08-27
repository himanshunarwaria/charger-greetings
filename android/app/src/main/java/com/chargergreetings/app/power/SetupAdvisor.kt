package com.chargergreetings.app.power

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.chargergreetings.app.util.Diagnostics

/**
 * Tells the user what still needs doing for monitoring to survive, and takes
 * them to the right system screen.
 *
 * ### Why battery optimisation is treated as required, not optional
 * On Android 12+ an app that is *not* exempt from battery optimisation cannot
 * start a foreground service from the background. That single rule is what
 * decides whether [WatchdogWorker] can repair the service after an OEM kills
 * it. Without the exemption the recovery path is legally blocked and the user
 * has to reopen the app by hand -- which is the failure this app was reported
 * for. So this is load-bearing, not a nicety.
 *
 * ### What this deliberately does not do
 * No accessibility service, no device admin, no root, no "unrelated invasive
 * permission" shortcuts. Every action below opens a normal Settings screen and
 * leaves the decision to the user.
 */
object SetupAdvisor {

    /** True when Android will let us run without Doze deferring us. */
    fun isBatteryOptimised(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * True when the ongoing service notification is specifically blocked even
     * though notifications overall are allowed. Worth calling out separately:
     * the service still runs, but the user loses the only visible sign of it.
     */
    fun watcherChannelBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel("power_watcher") ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Opens the battery-optimisation exemption prompt.
     *
     * Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which shows a single
     * system dialog, and falls back to the full settings list if the OEM has
     * removed it. Note this needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * permission declared; Google Play restricts that to apps whose core
     * function genuinely requires it, which is the case here and must be
     * declared as such if this is ever published to Play.
     */
    fun requestIgnoreBatteryOptimisation(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:" + context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchFirstAvailable(context, listOf(direct, list))
    }

    fun openNotificationSettings(context: Context) {
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intents += Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        intents += appDetailsIntent(context)
        launchFirstAvailable(context, intents)
    }

    fun openAppDetails(context: Context) = launchFirstAvailable(context, listOf(appDetailsIntent(context)))

    /**
     * Opens the manufacturer's auto-start / background-activity screen.
     *
     * These are private OEM activities: undocumented, renamed between versions,
     * and absent entirely on some builds. Every one is therefore attempted
     * defensively and falls back to the standard app-details page, which always
     * exists. We never assume a launch succeeded.
     */
    fun openAutoStartSettings(context: Context) {
        val candidates = oemAutoStartIntents().map {
            Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } + appDetailsIntent(context)
        launchFirstAvailable(context, candidates)
    }

    /** True when this device is from a manufacturer known to need extra setup. */
    fun needsOemAutoStartGuidance(): Boolean = oemGuidance() != null

    /**
     * Brand-specific, human-readable instructions. Kept as text because the OEM
     * screens differ so much that a generic "allow background activity" prompt
     * is useless on these devices.
     */
    fun oemGuidance(): String? {
        val brand = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "MIUI/HyperOS: open Settings > Apps > Charger Greetings, turn on " +
                    "Autostart, and set Battery saver to \"No restrictions\". " +
                    "Then lock the app in Recents (swipe down on its card)."
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "ColorOS/OxygenOS: Settings > Battery > Background power consumption " +
                    "(or App battery usage) > allow this app to run in the background, " +
                    "and enable Auto-start."
            brand.contains("vivo") || brand.contains("iqoo") ->
                "Funtouch/OriginOS: Settings > Battery > High background power " +
                    "consumption > allow this app, and enable Auto-start in " +
                    "Settings > Apps > Permissions."
            brand.contains("huawei") || brand.contains("honor") ->
                "EMUI/MagicOS: Settings > Apps > Charger Greetings > Battery > " +
                    "set App launch to Manage manually, and enable all three " +
                    "(Auto-launch, Secondary launch, Run in background)."
            brand.contains("samsung") ->
                "One UI: Settings > Apps > Charger Greetings > Battery > set to " +
                    "Unrestricted. Also check Settings > Battery > Background usage " +
                    "limits and make sure this app is not in \"Sleeping apps\"."
            brand.contains("motorola") || brand.contains("lenovo") ->
                "Motorola: Settings > Apps > Charger Greetings > Battery > set to " +
                    "Unrestricted. Moto devices also have Settings > Battery > " +
                    "Battery optimisation -- set this app to Not optimised."
            brand.contains("asus") ->
                "ZenUI: open the Mobile Manager app > PowerMaster > Auto-start " +
                    "manager, and allow this app."
            brand.contains("transsion") || brand.contains("tecno") ||
                brand.contains("infinix") || brand.contains("itel") ->
                "Settings > Apps > Charger Greetings > allow Autostart, and set " +
                    "battery usage to Unrestricted in the Power Manager."
            else -> null
        }
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Known OEM auto-start activities. Undocumented and version-dependent, so
     * these are tried in order and any failure just moves to the next.
     */
    private fun oemAutoStartIntents(): List<ComponentName> = listOf(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        ComponentName(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"
        ),
        ComponentName(
            "com.asus.mobilemanager",
            "com.asus.mobilemanager.autostart.AutoStartActivity"
        ),
        ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        ComponentName(
            "com.transsion.phonemaster",
            "com.transsion.phonemaster.appmanager.AutoStartActivity"
        )
    )

    /**
     * Tries each intent in turn, returning on the first that actually starts.
     * @return true if something opened.
     */
    private fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // Expected: this OEM screen does not exist on this build.
            } catch (e: SecurityException) {
                // Some OEM activities exist but refuse external callers.
                Diagnostics.log(context, "Settings screen refused: " + e.message)
            } catch (e: Exception) {
                Diagnostics.log(context, "Settings screen failed: " + e.message)
            }
        }
        Diagnostics.log(context, "No matching settings screen could be opened")
        return false
    }
}
