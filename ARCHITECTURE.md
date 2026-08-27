# Architecture

Both apps solve the same problem and share the same shape:

```
  power event  ──▶  reconcile with reality  ──▶  decide  ──▶  speak
   (system)          (read the truth)          (rules)      (audio)
```

The decision layer is the part worth engineering. Detecting power is a couple of
API calls; playing a WAV is a couple more. What makes the product feel reliable
rather than twitchy is everything between: debounce, deduplication, cooldown,
sleep suppression, and never greeting for something the user did not do.

So on both platforms the decision rules are isolated in one class with no
platform types in it, and everything else is a thin adapter around them.

---

## Windows

**Stack:** C# / WinForms tray app, .NET Framework 4.8 (also compiles against
`net8.0-windows` unchanged). No third-party dependencies.

```
Program.cs              single instance, lifetime, crash handling
 └── TrayApp            NotifyIcon, context menu, balloons
      └── SettingsForm  the one window
 └── GreetingController ★ the rules
      ├── PowerWatcher        two event sources → one state read
      ├── IGreetingAudioOutput
      │    └── WavePlayer     winmm waveOut + per-playback volume
      ├── AudioLibrary        loads and caches the two clips
      └── Settings            INI in %LOCALAPPDATA%
```

### Power detection

Two independent event sources, never a poll:

1. **`RegisterPowerSettingNotification(GUID_ACDC_POWER_SOURCE)`** — the primary.
   Windows posts `WM_POWERBROADCAST` to a message-only window at the exact
   moment the power source changes, and only then. Works on laptops, desktops
   and UPS-reported systems.
2. **`SystemEvents.PowerModeChanged`** — the safety net, and the source of the
   Suspend/Resume edges. Its `StatusChange` fires for battery-percentage ticks
   too, so it is never trusted on its own: it only prompts a re-read.

Both funnel into `GetSystemPowerStatus`, and `GreetingController` discards any
read that matches what it already believes.

### Why these choices

| Decision | Why |
|---|---|
| **.NET Framework 4.8** | An OS component on every Win10/11 machine: no runtime install, 214 KB instead of ~70 MB, and it builds with the compiler already in Windows. |
| **WinForms, no main window** | A background utility with a taskbar button is noise. The tray icon carries state (colour = armed, grey = paused). |
| **`waveOut` P/Invoke** | `System.Media.SoundPlayer` has no volume control; WPF `MediaPlayer` needs a dispatcher and is fragile inside a WinForms loop. `waveOut` is the smallest supported API that does the job, and it re-resolves the default device on every open — so swapping to headphones between greetings just works. |
| **Volume by scaling PCM** | `waveOutSetVolume` is optional for a driver to implement and silently does nothing on some devices. Arithmetic on the buffer behaves identically everywhere. |
| **INI settings** | No serializer dependency, identical on both target frameworks, and the user can read exactly what is stored about them in Notepad. |
| **`HKCU\…\Run`** | Per-user, no admin, visible in Task Manager → Startup apps. The previous version's `.vbs` + `powershell -ExecutionPolicy Bypass` is a malware signature. |
| **UI-thread state machine** | Every transition is marshalled onto one thread through a control the controller owns, so it needs no locks at all. It owns a `Control` rather than capturing `SynchronizationContext.Current` because WinForms only installs a context once a control handle exists — and the controller is built before the tray icon, so the capture would silently be null. See W-14 in `AUDIT.md`. |

### Installer

A self-contained C# executable with the app folder embedded as a zip resource.
Installs per-user into `%LOCALAPPDATA%\Programs`, registers under
`HKCU\…\Uninstall` so it appears in Settings → Apps, and copies itself as
`Uninstall.exe`. No MSI, no WiX, no script — one file, no admin.

---

## Android

**Stack:** Kotlin (via AGP 9), Jetpack Compose + Material 3, minSdk 24,
targetSdk 36, compileSdk 37.

```
MainActivity            one screen, Compose
 └── SettingsViewModel  UI state; baselines silently on open

MonitoringController ★  the ONE place that starts or stops monitoring
 ├── master toggle          (user action)
 ├── BootReceiver           BOOT_COMPLETED / LOCKED_BOOT_COMPLETED
 └── WatchdogWorker         WorkManager, every 15 min

PowerWatcherService     foreground, START_STICKY
 └── dynamically-registered receiver
      └── PowerEventHandler
           ├── GreetingEngine ★ the rules -- pure Kotlin, zero Android types
           ├── PowerStatus      reads the sticky battery intent
           └── GreetingPlayer   MediaPlayer + transient ducking audio focus
```

### Why a foreground service, not a manifest receiver

The obvious design -- a manifest receiver for `ACTION_POWER_CONNECTED` -- does
not work, and this was learned the hard way. Those two actions are **not** on
Android's implicit-broadcast exemption list. On a real Android 16 device the
system logs `skipped by policy at enqueue: Background execution not allowed`
and refuses delivery. Android's documented alternative is to register the
receiver dynamically from a component that is alive, which is what the service
is for. The manifest receiver is retained only as a free best-effort bonus on
whatever device/OS combination still lets it through.

### Why a watchdog

Starting the service from app-launch and boot alone left no recovery path: when
an OEM battery manager killed it at 3am, monitoring stayed dead until the user
reopened the app. `START_STICKY` is a request, not a contract. `WatchdogWorker`
is the repair mechanism; WorkManager is used because it is the one scheduler
that survives process death, standby buckets and reboots.

Full analysis, permission justifications and the test matrix:
[android/RELIABILITY.md](android/RELIABILITY.md).

### The central constraint

**A `BroadcastReceiver` may run in a brand-new process every time.** No
in-memory state survives between events. So `GreetingEngine` holds nothing
itself -- it reads and writes every piece of state through `GreetingStore`, and
the SharedPreferences implementation uses `commit()` (not `apply()`) for the
values that must not be lost if the process dies the instant the receiver
returns.

That constraint is also what makes the design testable: an engine with no
in-memory state and no Android types is just a function of (event, stored state,
config), which is what the 29 JVM unit tests exercise.

### The rules, in order

1. **Contradiction check.** Broadcast says "connected" but the battery service
   says unplugged? The cable bounced. Record the truth, say nothing.
2. **Duplicate check.** Already in the claimed state -> repeat delivery.
3. **Preferences.** Master switch, then the per-direction switch.
4. **Cooldown.** A hard floor between greetings.
5. **Silent mode**, if the user asked for it.

State is recorded even when the decision is silence, so the *next* event is
judged against the truth rather than a stale value.

### Why these choices

| Decision | Why |
|---|---|
| **Foreground service** | The only supported way to receive the power broadcasts with the app closed. Costs one silent, minimum-importance notification. |
| **WorkManager watchdog, 15 min** | Repairs the service after an OEM kill. Not the detection path, so the 15-minute floor costs at most 15 minutes of downtime after a kill instead of "dead until reopened". |
| **Battery-optimisation exemption treated as required** | On Android 12+ a non-exempt app cannot start a foreground service from the background, so without it the watchdog is blocked by policy and cannot repair anything. |
| **`goAsync()` in receivers** | A receiver gets ~10 s of grace; the clip is ~1.6 s. Playback is wrapped in a 7 s timeout with `finish()` in a `finally`, so a wedged audio stack cannot leak the `PendingResult`. |
| **SharedPreferences, not DataStore** | A receiver's decision window is short and synchronous. DataStore's Flow API would mean blocking on a coroutine or racing the receiver's lifetime. |
| **SAF `OpenDocument`, not `GetContent`** | Only `OpenDocument` returns a URI that `takePersistableUriPermission` accepts. `GetContent` gives a one-shot grant that silently stops working after a reboot. |
| **Bundled WAV in `res/raw` as the default** | Works offline with no permission and no file picker, and is the fallback whenever a custom sound goes missing. |
| **`USAGE_ASSISTANCE_SONIFICATION`** | It is a UI sound: should not show as "now playing", should not route to a call. Silent-mode behaviour is then an explicit user setting rather than an accident of stream routing. |
| **Transient ducking audio focus** | Music dips for 1.6 s instead of stopping; podcast apps keep their position. |
| **Compose + Material 3** | Dynamic colour on Android 12+, dark mode for free, and semantics for accessibility that would have been hand-rolled with Views. |

---

## Shared design rules

Both apps follow the same five, and the tests exist to prove each one:

1. **Never greet for something the user did not do.** App launch, sign-in, boot
   and wake all re-baseline silently.
2. **One physical action, one greeting.** Debounce, then re-read reality, then a
   cooldown floor.
3. **Fail loudly to the log, quietly to the user.** Every skipped greeting
   records its reason. Failures surface as a balloon or a status line, never a
   modal that steals focus from whatever you were doing.
4. **Nothing leaves the machine.** No network code, no telemetry, no accounts.
5. **The decision layer knows nothing about the platform.** It is the only part
   worth testing hard, so it is the part with no dependencies.

---

## Tooling

`tools/AudioPrep` — decodes the source MP3s through Windows Media Foundation,
measures ITU-R BS.1770-4 integrated loudness and 4× oversampled true peak, then
masters both clips to a *shared* target level with pure gain (no limiting).
Two-pass by necessity: the final level cannot be chosen until both clips' peak
headroom is known, because they must end up identical.

`tools/IconGen` — draws the plug mark once and exports multi-resolution Windows
`.ico` files (DIB below 128 px, PNG above) plus Android launcher bitmaps. The
Android adaptive icon is a hand-written vector using the same proportions, so
both platforms show the same mark.
