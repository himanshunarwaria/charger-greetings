# Sound customisation and battery alert (v1.2.0)

What was added on top of the v1.1.0 reliability work, and the decisions behind it.

---

## 1. Features added

| Feature | Detail |
|---|---|
| **Three independent sound slots** | Charger connected, charger disconnected, battery alert. Each has its own toggle, sound, volume, duration limit, preview/stop and reset. Changing one cannot touch another. |
| **Three ways to choose a sound** | Nine bundled sounds, any audio file on the phone (SAF), or a voice recording made in the app. |
| **Voice recording** | Record, pause/resume, stop, preview, re-record, save, cancel, delete. Microphone requested only on tap. |
| **Playback controls** | Per-slot volume, and a duration limit of Full / 3 s / 5 s / 10 s. Never loops. |
| **Quiet hours** | Optional nightly window that correctly handles crossing midnight. |
| **Silent-mode behaviour** | "Respect silent and vibrate mode", now independent of quiet hours. |
| **Battery-level alert** | One threshold, 1–100%, with 80% and 100% shortcuts. Fires exactly once per genuine crossing. |
| **Status section** | Six plain-language states, last event, last sound played, whether the service is running, and direct buttons for every fix. |

---

## 2. Architecture decisions

### One slot abstraction instead of three copies

`SoundSlot` (CONNECTED / DISCONNECTED / BATTERY_ALERT) is what keeps this from
becoming three near-identical implementations. Storage keys, the picker sheet,
the player and the UI section are each written **once** against a slot:

```
SoundSlot ──► SettingsRepository.slotConfig(slot)   namespaced keys
          ──► SoundCatalog.resolve(slot, source)    one resolution path
          ──► GreetingPlayer.play(slot)             one playback path
          ──► SoundSection(...)                     one UI component, used 3x
```

The battery section is the same `SoundSection` composable as the other two; the
threshold slider is passed in as an `extra` slot-specific block. That is the only
place the three sections differ.

### `SoundSource` is a sealed type, persisted as a readable string

`builtin:ding`, `uri:content://…`, `rec:voice_1712345.m4a`. Readable in the
prefs file, stable across versions, and trivially migratable. v1.0's bare
`uri_connected` key is read once and upgraded, so an existing user keeps their
chosen file.

### The battery alert is edge-triggered, not level-triggered

`ACTION_BATTERY_CHANGED` fires many times a minute while charging — for
temperature and voltage as well as level. A naive `if (level >= threshold)`
would fire on every one of those for as long as the battery sat at or above the
threshold.

`BatteryAlertEngine` uses one persisted `armed` flag:

- unplugged, or below threshold → **re-arm**
- plugged, at/above threshold, armed → **fire once, disarm**
- plugged, at/above threshold, disarmed → **silent**

It disarms *before* playing, because the process can be killed the instant
playback starts and a lost write would mean a duplicate alert. `baseline()`
starts **disarmed** when already above the threshold, which is what stops a
reboot at 100% firing immediately.

The service also filters readings in memory: only a changed `(level, plugged)`
pair reaches the engine at all, so the log is not drowned.

### Quiet hours is its own tested type

The naive `start <= now && now < end` check silently never matches an overnight
window — and 23:00–07:00 is the only window most people want. `QuietHours` wraps
correctly, treats equal start/end as *off* rather than *always* (the dangerous
reading, which would kill every sound), and has nine tests pinning it.

### Suppression is decided in one place

`SoundSuppression.reasonToStayQuiet()` is the single function that answers
"should an automatic sound stay quiet?" — quiet hours **or** silent mode. All
three slots go through it, so the rules cannot drift apart.

Subtle bug found and fixed during review: `GreetingEngine` re-gated the combined
result behind `config.respectSilentMode`, which meant turning *off* "respect
silent mode" would have silently disabled *quiet hours* too. The handler now
passes `respectSilentMode = true` into the engine because the preference has
already been applied upstream.

Manual previews deliberately bypass all of this: the user pressed a button.

### Exactly one sound at a time, process-wide

The active `MediaPlayer` lives in a **companion-object** field, not an instance
field, because previews come from the ViewModel while event sounds come from the
service — two different instances that must still never overlap.

Second bug found in review: `MediaPlayer.stop()` fires no completion callback,
so when a charging event interrupted a preview, the preview's coroutine was left
suspended forever and the UI stayed stuck on "playing". The active player now
carries a finisher callback that is invoked on interruption (outside the lock,
since resuming a coroutine under a lock invites deadlock).

### Duration limit

A `Handler.postDelayed` that stops the clip and checks it is still the active
player first. Deliberately not a coroutine timer: it must fire even if the
calling scope is cancelled mid-clip. It stops cleanly and never loops.

---

## 3. Files created or modified

**Created**
| File | Purpose |
|---|---|
| `core/SoundSlot.kt` | `SoundSlot`, `SoundSource`, `PlaybackLimit`, `SlotConfig` |
| `core/QuietHours.kt` | Midnight-safe window logic |
| `core/BatteryAlertEngine.kt` | Edge-triggered one-shot alert |
| `audio/SoundCatalog.kt` | Built-in catalogue + resolution of any source |
| `audio/VoiceRecorder.kt` | MediaRecorder wrapper, app-private storage |
| `power/SoundSuppression.kt` | The single quiet/silent decision |
| `ui/SoundPickerSheet.kt` | One picker, used by all three slots |
| `tools/ToneGen/Program.cs` | Generates the bundled tones |
| `test/.../QuietHoursTest.kt` | 9 tests |
| `test/.../BatteryAlertEngineTest.kt` | 17 tests |
| `res/raw/*.wav` | 7 generated tones |

**Modified**
| File | Change |
|---|---|
| `core/SettingsRepository.kt` | Per-slot config, quiet hours, battery alert, v1.0 migration |
| `audio/GreetingPlayer.kt` | Slot-aware, duration limits, interruption finisher, `playFile` |
| `audio/SoundLibrary.kt` | Reduced to the SAF helpers; dead resolution path removed |
| `power/PowerEventHandler.kt` | Slot playback, suppression, battery path |
| `power/PowerWatcherService.kt` | `ACTION_BATTERY_CHANGED` + in-memory dedup |
| `power/MonitoringController.kt` | Baselines the battery alert too |
| `ui/SettingsViewModel.kt` | Slots, picker, recorder, quiet hours, per-slot preview |
| `ui/SettingsScreen.kt` | Three uniform sections, general section, status detail |
| `MainActivity.kt` | Picker sheet, mic permission, SAF launcher |
| `AndroidManifest.xml` | `RECORD_AUDIO` |

### Built-in sounds are original

All seven tones are synthesised from scratch by `tools/ToneGen` (additive
synthesis, exponential decay, peak-normalised to −3 dBFS, edge-faded to avoid
clicks). Nothing is sampled from a third party, so there is no licensing
obligation. The two spoken greetings are the user's own recordings.

---

## 4. Permissions

| Permission | Justification |
|---|---|
| `RECORD_AUDIO` | **New.** Voice recording only. Requested at the moment the user taps Record, never at launch. No service, receiver or worker can reach `VoiceRecorder`, so the app cannot record in the background even by accident. Recordings go to app-private storage. |
| `RECEIVE_BOOT_COMPLETED` | Restore monitoring after restart. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Required for the monitoring service on Android 14+. |
| `POST_NOTIFICATIONS` | Android 13+ requirement for the service's status notification. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Load-bearing: without the exemption Android forbids the background service restart the watchdog needs. |

**Not requested:** no storage permission (SAF grants per-file access without
one), no `INTERNET`, no accessibility service, no device admin, no overlay.

---

## 5. Test results

**55/55 unit tests pass.**

| Suite | Tests | Covers |
|---|---|---|
| `GreetingEngineTest` | 19 | Original charger event rules |
| `BaselineAndRecoveryTest` | 10 | Restart/reboot silence |
| `BatteryAlertEngineTest` | **17** | One-shot alert, re-arming, baseline, suppression |
| `QuietHoursTest` | **9** | Same-day and midnight-crossing windows |

Notable cases now pinned by tests:

- A full simulated charge cycle (35%→100%, many readings per level, sit at 100%,
  unplug, drain to 30%) produces **exactly one** alert.
- Reboot at 100% on the charger fires **nothing**.
- A suppressed crossing is skipped and **not** replayed when quiet hours end.
- 23:00–07:00 covers 03:00 and not 12:00; equal start/end is off, not always.

Builds: `assembleDebug` 18.9 MB, `assembleRelease` **1.9 MB** (R8), lint **0 errors**.

---

## 6. Physical-device tests still required

None of the below has run on hardware — the test phone was disconnected
throughout this work.

| Area | Test |
|---|---|
| Sound selection | Pick a built-in, a file, and a recording for each of the three slots independently |
| Persistence | Restart the app, then the phone; confirm a picked file still plays |
| Missing file | Delete the chosen file; confirm fallback + the section flags it |
| Recording | Record, pause, stop, preview, re-record, save, delete; deny the mic permission |
| Playback | Each duration limit; start one sound during another; rapid preview/stop; close the screen mid-preview |
| Battery alert | Cross the threshold while charging; repeated broadcasts at the same level; drop below and cross again; reboot at threshold; confirm silence while unplugged |
| Quiet hours | Same-day window, overnight window, confirm no catch-up sound afterwards |
| Silent mode | Normal / vibrate / silent, with the setting on and off |
| Reliability | Overnight run, Doze, battery saver, app killed, removed from Recents |

ADB helpers:

```bash
adb shell dumpsys battery set level 79      # then 80 to cross the threshold
adb shell dumpsys battery set ac 1
adb shell dumpsys battery reset             # ALWAYS finish with this
adb shell run-as com.chargergreetings.app cat files/diagnostics.log
```

---

## 7. Known limitations

1. **Force stop still wins.** Android delivers a force-stopped app nothing at
   all, including the boot broadcast, until it is opened manually. No app can
   work around this.
2. **OEM battery managers** can still kill the service; the watchdog repairs it
   within ~15 minutes *if* the app is battery-exempt.
3. **Quiet hours is hour-granularity.** A full time-picker felt like more UI
   than "roughly bedtime" warrants.
4. **Recording pause/resume** needs API 24+, which is this app's minSdk, so it
   is always available — but some OEM ROMs are unreliable about it.
5. **Some file pickers hand back non-persistable URIs** (certain cloud
   providers). The app detects this at pick time and says so, rather than
   letting the sound fail silently after the next reboot.
6. **`specialUse` foreground service** needs a Play declaration if published.

---

## 8. Production-readiness checklist

| Item | Status |
|---|---|
| Three sounds independently configurable | ✅ |
| Built-in / file / recording all work | ✅ (unit + build verified; device pending) |
| Custom access survives reboot | ✅ persistable URI permissions |
| Missing audio handled without crashing | ✅ falls back, flags in UI |
| Volume and duration per slot | ✅ |
| Quiet hours crosses midnight | ✅ 9 tests |
| Silent mode independent of quiet hours | ✅ bug found and fixed in review |
| Battery alert once per crossing | ✅ 17 tests |
| No false sound after boot/restore | ✅ battery baseline added |
| Only one sound at a time | ✅ process-wide active player |
| Interrupted playback resumes its caller | ✅ bug found and fixed in review |
| Mic only on user action, released properly | ✅ |
| No duplicate services/receivers/workers | ✅ single controller, unique work name |
| Master switch preserves settings | ✅ |
| Builds clean, lint 0 errors | ✅ |
| Unit tests | ✅ 55/55 |
| **Physical-device testing** | ⬜ **outstanding** |
