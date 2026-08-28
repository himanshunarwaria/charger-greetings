# Test checklist

Categories, and it matters which is which:

* **✅ Verified** — actually executed on this machine and passed.
* **🟡 Reviewed** — code path written and read against the platform contract,
  but not executable here (needs physical hardware or a second device).
* **⬜ Needs a human** — requires ears, a cable, or a phone.
* **❌ Failed** — and why.

Reproduce with `windows\build.ps1 -Test` (transcript at
`build/test/test-results.txt`) and, in `android/`,
`.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintDebug`.

---

## 1. Windows — automated (36/36 passed)

Windows 11 26200, .NET Framework 4.8.1, real audio hardware present.

### Audio assets

| # | Test | Result |
|---|---|---|
| 1 | Connect clip loads | ✅ |
| 2 | Connect clip is 16-bit mono 44.1 kHz | ✅ |
| 3 | Connect clip duration is sane (0.5–5 s) | ✅ |
| 4 | Disconnect clip loads | ✅ |
| 5 | Volume 100 returns the buffer untouched | ✅ |
| 6 | Volume 0 produces digital silence | ✅ |
| 7 | Volume 50 reduces peak without zeroing it | ✅ |
| 8 | **Missing file** is reported, not crashed on | ✅ |
| 9 | **Non-WAV data** is reported | ✅ |
| 10 | **Truncated file** is reported | ✅ |

### Settings

| # | Test | Result |
|---|---|---|
| 11 | Save/load round trip preserves every field | ✅ |
| 12 | A corrupt settings file falls back to defaults | ✅ |
| 13 | Out-of-range values are clamped (`Volume=999` → 100) | ✅ |

### Greeting rules

| # | Test | Result |
|---|---|---|
| 14 | Launching **while charging** plays nothing | ✅ |
| 15 | Launch records the baseline power source | ✅ |
| 16 | Normal connect → exactly one connect greeting | ✅ |
| 17 | Normal disconnect → exactly one disconnect greeting | ✅ |
| 18 | **Rapid cable reseat** (off→on inside debounce) plays nothing | ✅ |
| 19 | State stays correct after a bounce | ✅ |
| 20 | **20 duplicate events** → exactly one greeting | ✅ |
| 21 | A second real change inside the cooldown is suppressed | ✅ |
| 22 | Greetings resume once the cooldown expires | ✅ |
| 23 | Master switch off silences everything | ✅ |
| 24 | Connect direction can be switched off independently | ✅ |
| 25 | Disconnect still plays when connect is off | ✅ |
| 26 | **Sleep → wake, no change** plays nothing | ✅ |
| 27 | **Unplugged during sleep** plays nothing on wake | ✅ |
| 28 | Wake re-baselines to the new source | ✅ |
| 29 | Configured delay is honoured | ✅ |
| 30 | Greeting plays after the delay | ✅ |
| 31 | Delayed greeting is cancelled if the state reverts | ✅ |
| 32 | An `Unknown` power reading is ignored | ✅ |
| 33 | A missing clip is reported, not crashed on | ✅ |
| 34 | **An event raised on a background thread still greets** | ✅ |

> Test 34 is a regression test for a real defect found during live verification —
> see W-14 in [AUDIT.md](AUDIT.md). It was confirmed to **fail** against the
> pre-fix code and pass after, so it genuinely guards the behaviour.

### Real audio device

| # | Test | Result |
|---|---|---|
| 35 | `waveOut` accepts and completes the real clip (at volume 0) | ✅ |
| 36 | Real playback reports no error | ✅ |

---

## 2. Windows — verified by inspection or build

| Test | Result | Note |
|---|---|---|
| App compiles with zero warnings at `/warn:4` | ✅ | |
| `ChargerGreetings.exe` produced, 214 KB | ✅ | |
| Installer produced, 316 KB | ✅ | |
| Installer payload is a valid zip containing exe + both clips | ✅ | Verified by opening the embedded resource |
| Mastered WAV headers are well-formed RIFF/PCM | ✅ | 1 ch, 44100 Hz, 16-bit |
| Both clips measure exactly −18.4 LUFS | ✅ | 0.00 LU apart |
| No clipped samples in either shipping clip | ✅ | |
| True peak ≤ −1.0 dBTP in both clips | ✅ | |
| Install via `ChargerGreetings-Setup.exe` | ❌ | **Blocked and deleted by Defender "block at first sight"** — see §4 |
| Deploy via portable zip | ✅ | Extracted to `%LOCALAPPDATA%\Programs\ChargerGreetings`, runs correctly |
| Uninstall round trip | ⬜ | Not exercised — the installer never ran. Logic reviewed; W-16 fixed. |
| Tray icon appearance and menu | ⬜ | Needs a look |
| Audio device removed mid-session | 🟡 | `waveOutGetNumDevs()==0` path returns a friendly message; not physically tested |
| Audio device changed between greetings | 🟡 | Device re-resolved on every playback (`WAVE_MAPPER`), so it follows the default |
| Desktop with no battery | 🟡 | `GetSystemPowerStatus` returns AC; status line says "unknown (no battery reported)" |
| UPS-reported power (`PoHot`) | 🟡 | Falls back to `GetSystemPowerStatus` |
| Real hibernate cycle | 🟡 | Suspend/resume tested via simulated edges (#26–28); a real hibernate was not performed |

---

## 3. Windows — verified on real hardware

Run on this machine on 2026-08-24. Evidence is the app's own log at
`%LOCALAPPDATA%\ChargerGreetings\charger-greetings.log`.

| Test | Result | Evidence |
|---|---|---|
| **Real charger connected → "मालिक, प्रणाम" plays** | ✅ | `00:33:18 Power change seen: Battery -> AC` → `00:33:22 Playing: मालिक, प्रणाम` |
| **Real charger removed → "फिर मिलते हैं, मालिक" plays** | ✅ | `00:33:30 AC -> Battery` → `00:33:31 Playing: फिर मिलते हैं, मालिक` |
| Exactly one greeting per real transition | ✅ | No duplicate `Playing:` lines for either event |
| Precise detection API is the one actually used | ✅ | `Precise notifications: yes`, every event logged `via PowerSettingNotification` |
| Both clips load with the expected format | ✅ | `power_connected.wav (1.58s, 44100 Hz, 1ch)`, `power_disconnected.wav (1.49s, 44100 Hz, 1ch)` |
| Launching **while on battery** plays nothing | ✅ | `00:32:44 Baseline power source at launch: Battery (silent)` |
| Launching **while charging** plays nothing | ✅ | `02:20:41 Baseline power source at launch: AC (silent)` |
| `--startup` mode stays in the tray, no window | ✅ | `02:20:41 Started by Windows; staying in the tray.` |
| **Starts automatically at Windows sign-in** | ✅ | Unprompted launch at `08:19:27` with `--startup`, ~6 h after the last manual start — the `Run` key firing on a real sign-in |
| Single-instance guard | ✅ | Second launch: `Another instance is already running; exiting.` |
| Stale startup entry repaired when the app moves | ✅ | `Startup entry pointed at an old location; updating it.` |
| Run key written per-user, no admin | ✅ | `HKCU\…\Run\ChargerGreetings` → `"…\ChargerGreetings.exe" --startup` |
| Settings file written with defaults | ✅ | `%LOCALAPPDATA%\ChargerGreetings\settings.ini` |
| Test buttons play both greetings | ✅ | Three `Test:` entries logged at 00:32:49–00:33:01 |
| Memory footprint | ✅ | ~40 MB working set, 5 threads, idle between events |

**Observation, not a failure:** the gap between *"confirming in 700 ms"* and
*"Power source confirmed"* measured ~3.0 s on connect and ~1.3 s on disconnect,
rather than the configured 700 ms. No duplicate or lost events resulted. The
most likely cause is WinForms `WM_TIMER` jitter during the burst of system
activity that follows an AC transition on a laptop. Lower `DebounceMs` in
`settings.ini` if it bothers you — but shortening it also shortens the window
that absorbs a reseated cable.

---

## 4. Antivirus behaviour — observed, not theoretical

| Binary | Outcome |
|---|---|
| `ChargerGreetings.exe` (the app) | ✅ Runs |
| `ChargerGreetings-Setup.exe` (installer) | ❌ **Quarantined and deleted on launch** |
| Test runner built as a standalone `.exe` | ❌ Quarantined during an earlier build |

Defender refused the installer with *"the file contains a virus or potentially
unwanted software"* and deleted it. Nothing appears in `Get-MpThreatDetection`
or `Get-MpThreat`, and no ASR rules are configured — this is **cloud-delivered
"block at first sight"** reacting to an unsigned binary with no reputation, not
a signature match.

Two consequences were handled:

* The **build no longer produces a test executable at all** — the suite is
  compiled in-memory and invoked directly, so a build can never fail for this
  reason.
* The app was deployed from `ChargerGreetings-portable.zip` instead, which works
  and is now running.

---

## 5. Android — build and tests (verified)

Toolchain installed and used on this machine: Android Studio 2026.1.3.7
(JetBrains Runtime 25), SDK platform 37, build-tools 37.0.0, Gradle 9.5.0,
AGP 9.3.0.

| Task | Result | Detail |
|---|---|---|
| `:app:testDebugUnitTest` | ✅ | **29/29 pass**, 0 failures, 0 errors |
| `:app:assembleDebug` | ✅ | `app-debug.apk`, 18.4 MB |
| `:app:assembleRelease` | ✅ | `app-release-unsigned.apk`, **1.3 MB** — R8 shrinks it ~14× |
| `:app:lintDebug` | ✅ | 0 errors, 20 warnings |
| `clean` + full rebuild from scratch | ✅ | Reproducible, not an incremental fluke |
| Manifest sanity (`aapt2 dump badging`) | ✅ | `com.chargergreetings.app`, minSdk 24, targetSdk 36, compileSdk 37 |
| Only one real permission in the built APK | ✅ | `RECEIVE_BOOT_COMPLETED` (plus AGP's injected signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which grants nothing) |

### Unit tests, individually

All 19 in `app/src/test/.../GreetingEngineTest.kt`. Plain JVM — no emulator.

| # | Test | Result |
|---|---|---|
| 1 | Connecting from battery speaks the connect greeting | ✅ |
| 2 | Disconnecting from AC speaks the disconnect greeting | ✅ |
| 3 | `baseline()` never speaks and records the state | ✅ |
| 4 | `baseline()` ignores an unknown reading | ✅ |
| 5 | An event matching the baseline is silent (**app opened while charging**) | ✅ |
| 6 | Duplicate broadcasts → exactly one greeting | ✅ |
| 7 | A broadcast contradicted by the battery service is ignored (**cable pulled straight back out**) | ✅ |
| 8 | An unknown observed state trusts the broadcast | ✅ |
| 9 | **Rapid reconnect** inside the cooldown speaks once | ✅ |
| 10 | State is still tracked while the cooldown suppresses speech | ✅ |
| 11 | Greetings resume once the cooldown expires | ✅ |
| 12 | A **backwards clock** does not lock out greetings forever | ✅ |
| 13 | Master switch off silences everything | ✅ |
| 14 | Each direction switches independently | ✅ |
| 15 | A suppressed greeting does not start the cooldown | ✅ |
| 16 | Silent mode respected when asked | ✅ |
| 17 | Silent mode ignored when the user opts out | ✅ |
| 18 | Zero cooldown allows back-to-back greetings | ✅ |
| 19 | **Unplugging while the phone is off** does not greet on boot | ✅ |

### Build issues found and fixed along the way

| Issue | Fix |
|---|---|
| Gradle 8.9 cannot run on the JDK 25 that Studio 2026.1 bundles | Re-pinned to Gradle 9.5.0 / AGP 9.3.0 |
| AGP 9 rejects `org.jetbrains.kotlin.android` outright (built-in Kotlin) | Removed that plugin |
| …but removing *both* Kotlin plugins fails: Compose compiler plugin still required | Kept `org.jetbrains.kotlin.plugin.compose` only |
| Compose BOM 2026.08.00 requires compileSdk ≥ 37 | Bumped compileSdk to 37, left targetSdk at 36 |
| **Lint error:** `<exclude>` outside any `<include>` path in the backup rules | Removed the invalid excludes; corrected the comments, which had over-claimed |
| Lint `PropertyEscape` false-positives on `local.properties` | Downgraded that one check to a warning; all others still fail the build |

---

## 6. Android — emulator-verified and device-pending

Run against the **signed release APK** v1.2.1 on an Android 36 emulator
(Google APIs, x86_64). ✅ here means observed on that build; ⬜ still needs a
real phone, which is where OEM power management lives.

| Test | Status | Note |
|---|---|---|
| Installs and launches on a phone | ✅ | Emulator; the v1.2.0 build failed this outright |
| Connect / disconnect with **AC charger** | ✅ | Each fires exactly once |
| Connect / disconnect over **USB** | ⬜ | |
| Connect / disconnect on a **wireless charger** | ⬜ | |
| Works with the app closed | ✅ | Emulator; open it once first — see README |
| Works with the app swiped from Recents | ⬜ | |
| Survives a **reboot** — plug in after restarting | ✅ | Emulator; service restored, app never opened |
| **Does not** greet when unplugged while the phone was off | ✅ | Emulator; baselines silently on boot |
| Opening the app while charging plays nothing | ✅ | Emulator; baselines silently on open |
| Force-stop → no greetings until reopened | ⬜ | Expected Android behaviour, not a bug |
| Silent mode / DND suppresses greetings | ⬜ | |
| Media volume at zero suppresses greetings | ⬜ | |
| Music ducks rather than stops | ⬜ | Audio focus is transient-may-duck |
| Bluetooth headphones connected | ⬜ | 20 ms lead pad exists for this |
| Dark mode and dynamic colour | ⬜ | |
| TalkBack reads every control | ⬜ | Semantics written for all controls |
| Battery-optimisation warning appears and its button opens the right screen | ⬜ | |

---

## 7. Both platforms — needs ears

| Test | Status |
|---|---|
| Both clips reach the speakers when tested | ✅ — three `Test:` entries logged, no playback errors |
| Both clips play on real charger events (Windows) | ✅ — see §3 |
| **"मालिक, प्रणाम" is the correct phrase, pronounced correctly** | ⬜ |
| **"फिर मिलते हैं, मालिक" is the correct phrase, pronounced correctly** | ⬜ |
| The two greetings sound equally loud | ⬜ (measured identical at −18.4 LUFS) |
| Neither clip clicks at the start or end | ⬜ (8 ms / 30 ms fades applied) |
| Volume is comfortable at the default 80 % | ⬜ |

> The audio path is confirmed end to end — both clips have played on this
> machine, through the test buttons and on real charger events, with no errors.
> What remains is a judgement I cannot make: I can measure level, silence,
> clipping and format, but I cannot hear whether the words and pronunciation are
> right. That is the last open item.
