# Reliability: root cause, architecture, and test matrix

Written for the v1.1.0 fix to "works for a few hours, then goes quiet, and never
comes back after a reboot."

---

## 1. Root cause

Two separate defects produced the two symptoms.

### Why it died after ~3–4 hours

`PowerWatcherService` was started from exactly **two** places: app launch, and
`BOOT_COMPLETED`. Nothing else ever started it.

So the failure ran like this:

1. The system or an OEM battery manager kills the service (memory pressure,
   Doze, or a vendor "app cleaner" — on Motorola, Xiaomi, Oppo, Vivo, OnePlus
   and Samsung this is routine, not exceptional).
2. `START_STICKY` asks Android to recreate it. **This is a request, not a
   contract**, and aggressive OEM builds ignore it outright.
3. Nothing else in the app was watching. Monitoring stayed dead until the user
   happened to open the app again.

There was **no recovery path at all**. That is the whole bug. The 3–4 hour
figure is simply how long it typically takes for a device to get around to
killing a background process.

### Why it did not survive a reboot

`BootReceiver` did call `PowerWatcherService.start()`, but:

- **`startForeground()` was called unguarded in `onCreate()`.** On Android 12+
  it throws `ForegroundServiceStartNotAllowedException` when the app is in the
  background without an exemption. An unhandled throw there crashes the app and
  leaves monitoring dead with no diagnosis and no retry.
- **The receiver did not handle `LOCKED_BOOT_COMPLETED`**, so on devices that
  send it first the restore could be missed.
- **OEM auto-start managers gate `BOOT_COMPLETED` entirely** for apps not on
  their allowlist. Nothing in the app told the user this or offered to fix it.

### The underlying constraint that makes all of this hard

`ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` are **not** on Android's
implicit-broadcast exemption list. A manifest receiver alone genuinely does not
work. Verified against the
[official exemption list](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
and observed live on an Android 16 device, where the system logged:

```
skipped by policy at enqueue: Background execution not allowed:
receiving Intent { act=android.intent.action.ACTION_POWER_CONNECTED ... }
to com.chargergreetings.app/.power.PowerEventReceiver
```

Hence the foreground service. It is not gold-plating; it is the only supported
way to receive these broadcasts while the app is closed.

---

## 2. Architecture

```
        ┌──────────────── MonitoringController ────────────────┐
        │  the ONE place that starts or stops monitoring       │
        └──┬───────────────┬────────────────┬─────────────────┘
           │               │                │
   master toggle      BootReceiver     WatchdogWorker
   (user action)   (BOOT_COMPLETED)   (every 15 min)
           │               │                │
           └───────────────┴────────────────┘
                           │
                  PowerWatcherService  ← foreground, START_STICKY
                           │
              dynamically-registered receiver
                           │
                   PowerEventHandler
                           │
                    GreetingEngine  ← pure Kotlin, 29 unit tests total
                           │
                    GreetingPlayer
```

**Every start and stop funnels through `MonitoringController`.** That single
choke point is what prevents duplicate services, duplicate receivers and
duplicate workers.

### Why each layer is there

| Layer | Why |
|---|---|
| **Foreground service** | The only supported way to receive the power broadcasts with the app closed. Holds the dynamically-registered receiver. |
| **`WatchdogWorker` (15 min)** | The actual fix for the 3–4 hour failure. WorkManager is the one scheduler that survives process death, app-standby buckets *and* reboots, because it persists to disk and the system rebuilds it on boot. |
| **`BootReceiver`** | `BOOT_COMPLETED` **is** on the exemption list, so it fires even though the app has not been opened since boot. Receiving it also grants a temporary allowlist window that permits starting a foreground service from the background. |
| **`GreetingEngine`** | Zero Android types, so all decision rules are unit-testable. A receiver may run in a brand-new process every time, so no decision state lives in memory. |

### Why the 15-minute watchdog interval is not a compromise

WorkManager clamps periodic work to a 15-minute floor. That is fine, because
**the watchdog is not how events are detected** — the service's receiver does
that, instantly. The watchdog only *repairs* the service if it has died. Worst
case is ~15 minutes of downtime after an OEM kill, versus the old behaviour of
"dead until you reopen the app." Nothing polls; WorkManager batches this with
other system work.

### The load-bearing dependency: battery optimisation

On Android 12+ an app that is **not** exempt from battery optimisation **cannot
start a foreground service from the background**. That single rule decides
whether the watchdog can actually repair the service.

So the battery-optimisation exemption is not a nicety — without it the recovery
path is legally blocked and the user is back to reopening the app by hand. This
is why the setup card treats it as **required**, and why `SETUP_REQUIRED` is a
first-class status rather than a hint.

---

## 3. Files changed

| File | Change |
|---|---|
| `power/WatchdogWorker.kt` | **New.** Periodic self-healing restart. |
| `power/MonitoringController.kt` | **New.** Single entry point for start/stop/restore. |
| `power/SetupAdvisor.kt` | **New.** Battery/notification/OEM auto-start status and navigation. |
| `audio/SoundLibrary.kt` | **New.** SAF custom sounds with persistable URI permissions and availability checks. |
| `power/PowerWatcherService.kt` | Guarded `startForeground`; register receiver *before* the notification; `onTaskRemoved`; `isRunning()`; explicit FGS type; scope cancellation. |
| `power/BootReceiver.kt` | `LOCKED_BOOT_COMPLETED`; `goAsync()`; exception-safe; routes through the controller. |
| `power/PowerEventHandler.kt` | Records last event / last playback / last error for diagnostics. |
| `audio/GreetingPlayer.kt` | Custom-URI playback with fallback to the bundled clip; `stop()`; overlap prevention. |
| `core/SettingsRepository.kt` | Sound URIs, service state, diagnostics timestamps. |
| `ui/SettingsViewModel.kt` | Status resolution, diagnostics, sound picking, setup navigation, upgrade-path watchdog scheduling. |
| `ui/SettingsScreen.kt` | Status headline, setup card, sound pickers, stop-preview, diagnostics panel, troubleshooting. |
| `MainActivity.kt` | SAF `OpenDocument` launcher (not `GetContent` — see below). |
| `AndroidManifest.xml` | `LOCKED_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. |
| `test/.../BaselineAndRecoveryTest.kt` | **New.** 10 tests for restart/reboot silence. |

`OpenDocument`, not `GetContent`: only `OpenDocument` returns a URI that
`takePersistableUriPermission` accepts. `GetContent` gives a one-shot grant that
stops working after a reboot — which would silently break every custom sound
overnight, exactly the class of bug this release exists to kill.

---

## 4. Permissions

| Permission | Why it is genuinely needed |
|---|---|
| `RECEIVE_BOOT_COMPLETED` | Restore monitoring after a restart. Without it the app cannot survive a reboot at all. |
| `FOREGROUND_SERVICE` | Required for any foreground service. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ requires a typed FGS permission. `specialUse` is the correct type: watching a hardware state change fits no other predefined category. |
| `POST_NOTIFICATIONS` | Android 13+ requires it for the service's status notification to be visible. The service runs either way; denying it only hides the notification. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Shows the one-tap exemption dialog. Load-bearing: without the exemption the watchdog cannot restart the service in the background. **Play policy:** restricted to apps whose core function needs continuous background execution — true here, and must be declared if published. |

**Not requested:** `INTERNET`, `WAKE_LOCK`, storage permissions, accessibility
service, device admin. The app makes no network call; foreground-service state
already prevents suspension; SAF grants per-file access without a storage
permission.

---

## 5. Test results

### Automated — all passing

| Suite | Result |
|---|---|
| `GreetingEngineTest` | **19/19 pass** |
| `BaselineAndRecoveryTest` (new) | **10/10 pass** |
| `:app:assembleDebug` | Success — 18.5 MB |
| `:app:assembleRelease` (R8 + shrink) | Success — **1.4 MB** |
| `:app:lintDebug` | **0 errors** |

The 10 new tests specifically cover the silence guarantees this fix depends on:

- Service restart while plugged in / unplugged does not speak
- 20 repeated restarts never accumulate a greeting
- State changed while the service was dead becomes a baseline, not a greeting
- Reboot while plugged / unplugged, then the first real transition, speaks once
- An `UNKNOWN` reading during early boot does not clobber a good baseline
- Cooldown does not block a transition four hours later
- 24 alternating transitions over a simulated day each speak exactly once

### Manual matrix — requires a physical device

Not yet run: the test phone was disconnected during this work. Every row below
needs a real device.

| # | Scenario | How to test | Expected |
|---|---|---|---|
| 1 | App open, connect/disconnect | Plug and unplug | One sound each |
| 2 | App backgrounded | Home, then plug | One sound |
| 3 | Swiped from Recents | Swipe away, plug | One sound; notification stays |
| 4 | Screen locked | Lock, plug | One sound |
| 5 | **4+ hours idle** | Leave overnight, then plug | One sound — *the key regression test* |
| 6 | Doze | `adb shell dumpsys deviceidle force-idle`, plug | One sound |
| 7 | Battery saver on | Enable, plug | One sound |
| 8 | Reboot while unplugged | Restart, then plug | One sound, none at boot |
| 9 | Reboot while plugged | Restart plugged in | **No sound at boot**; unplug then speaks |
| 10 | Process killed | `adb shell am kill <pkg>`, wait ≤15 min | Watchdog restarts it |
| 11 | Force stop | Settings → Force stop | **Stops working — expected, documented** |
| 12 | Notifications denied | Deny, toggle on | `PERMISSION_REQUIRED` status shown |
| 13 | Battery optimisation on | Leave on | `SETUP_REQUIRED` + setup card |
| 14 | Rapid connect/disconnect | Plug/unplug fast ×5 | Debounce + cooldown; no pile-up |
| 15 | Custom sound picked | Choose a file, test | Plays the custom sound |
| 16 | Custom sound deleted | Delete the file, plug | Falls back to built-in; UI flags it |
| 17 | Custom sound after reboot | Pick, reboot, plug | Still plays (persistable URI) |
| 18 | Silent mode / DND | Enable, plug | Silent when the option is on |
| 19 | Bluetooth connected | Connect, plug | Plays through the route |
| 20 | Monitoring disabled | Toggle off | Notification gone, no sounds, settings kept |
| 21 | App updated | Install v1.1.0 over v1.0.0 | `MY_PACKAGE_REPLACED` restores; watchdog scheduled |

Useful ADB:

```bash
adb shell dumpsys battery set ac 1      # simulate connect
adb shell dumpsys battery set ac 0      # simulate disconnect
adb shell dumpsys battery reset         # ALWAYS finish with this
adb shell am kill com.chargergreetings.app          # normal process death
adb shell dumpsys deviceidle force-idle             # Doze
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.chargergreetings.app
adb shell dumpsys activity services com.chargergreetings.app | grep isForeground
adb shell run-as com.chargergreetings.app cat files/diagnostics.log
```

Note `dumpsys battery set` is a *simulation*; a real cable is still the test
that counts.

---

## 6. Remaining limitations — stated honestly

These cannot be engineered away.

1. **Force stop wins.** After Settings → Force stop, Android delivers the app no
   broadcasts at all — including `BOOT_COMPLETED` — until it is opened manually.
   No app can bypass this, and any app claiming otherwise is wrong. The
   troubleshooting section says so plainly.

2. **OEM battery managers can still kill it.** Xiaomi/MIUI, Oppo/ColorOS,
   Vivo/Funtouch, OnePlus, Samsung and Motorola all ship killers beyond stock
   Android. The watchdog repairs within ~15 minutes *if* the app is
   battery-exempt. Without auto-start allowed, some ROMs also block boot restore
   entirely. The setup card walks the user to the right screen per brand.

3. **Without the battery-optimisation exemption, background recovery is blocked
   by policy**, not by a bug. The app degrades to "reopen it once" and says so.

4. **The 15-minute watchdog floor** is a WorkManager limit. Worst-case downtime
   after a kill is ~15 minutes.

5. **`specialUse` FGS requires justification on Google Play.** Fine for direct
   distribution; needs a declaration if published.

6. **Doze can delay** the broadcast by a few hundred milliseconds. It is not
   dropped.

---

## 7. User setup steps

1. Open the app once after installing. (Android delivers a newly installed app
   no broadcasts until it is launched manually.)
2. Turn on **Charge sound monitoring**.
3. Allow notifications when asked.
4. If the setup card appears, tap **Allow unrestricted battery use** and accept.
   **This is the step that keeps it alive overnight.**
5. On Xiaomi/Oppo/Vivo/OnePlus/Samsung/Motorola, also tap **Open auto-start
   settings** and enable auto-start.
6. Test both sounds, then plug the charger in to confirm.

---

## 8. Production-readiness checklist

| Item | Status |
|---|---|
| Root cause identified and fixed | ✅ No recovery path → `WatchdogWorker` |
| Reboot restore | ✅ `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED`, guarded |
| No false sound on boot/restart | ✅ 10 unit tests prove it |
| One sound per real transition | ✅ Contradiction + duplicate + cooldown checks |
| Settings survive reboot | ✅ SharedPreferences with `commit()` on critical keys |
| Custom sounds survive reboot | ✅ Persistable URI permissions |
| Missing sound handled | ✅ Falls back to built-in, flagged in UI |
| Master toggle stops everything | ✅ Service stopped, watchdog cancelled, prefs kept |
| No duplicate services/receivers/workers | ✅ Single controller + unique work name |
| Resources released | ✅ Receiver, scope, MediaPlayer, audio focus |
| Battery cost | ✅ No polling, no wake locks, no alarms |
| Permissions justified | ✅ Five, each documented above |
| Builds clean | ✅ Debug, release (R8), lint 0 errors |
| Unit tests | ✅ 29/29 |
| **Physical device testing** | ⬜ **Not yet run — device was disconnected** |
| Long-duration (overnight) test | ⬜ **The single most important remaining test** |
