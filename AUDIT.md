# Folder audit

Audit of `power plug sound/` performed before any code was written or changed.
Nothing in the original folder was deleted or modified — the new work lives
entirely under `ChargerGreetings/`.

---

## 1. What was there

| Path | Stack | State |
|---|---|---|
| `Charger-Greetings-ASUS-Windows-11/` | Windows PowerShell 5.1 + VBScript | Shipped as a ZIP-style installer. **Non-functional** — see W-1/W-2. |
| `files/` | Android, Kotlin + AppCompat Views | Six loose source files. Not a buildable project. |
| `*.mp3` (10 files, 6 unique) | MPEG-1 Layer III, 128 kbps CBR, 44.1 kHz mono | Usable recordings, two of which match the requested phrases. |

**Nothing insecure was found.** Neither codebase requests network access, embeds
a secret or key, contacts a server, or collects data. That property is preserved
in the rewrite.

### Audio inventory

Measured by decoding each file through Windows Media Foundation and applying
ITU-R BS.1770-4 (`tools/AudioPrep`). Full numbers in
[`assets/AUDIO-REPORT.md`](assets/AUDIO-REPORT.md).

| File | Duration | Integrated | True peak | Verdict |
|---|---|---|---|---|
| `मालिक, प्रणाम.mp3` | 1.97 s | −23.9 LUFS | −6.7 dBTP | **Selected** — matches the requested phrase, newest file |
| `फिर मिलते हैं, मालिक.mp3` | 1.79 s | −23.5 LUFS | −5.5 dBTP | **Selected** — matches the requested phrase, newest file |
| `namaste.mp3` | 1.24 s | −17.0 LUFS | −3.4 dBTP | Different phrase ("Namaste"), not what was asked for |
| `namaste 02.mp3` | 1.27 s | −24.4 LUFS | −8.2 dBTP | Duplicate of the copy inside `Charger-Greetings…/` |
| `fir milte h.mp3` | 1.48 s | −17.0 LUFS | −2.5 dBTP | Older take |
| `fir milte h 02.mp3` | 1.50 s | −26.6 LUFS | −10.3 dBTP | Older take, very quiet |

No clipping and negligible DC offset in any file — the recordings themselves are
clean. The problems were level, silence and container, not quality.

---

## 2. Findings

Severity: **Critical** = the feature cannot work · **High** = works but violates a
stated requirement · **Medium** = real defect or missing requirement ·
**Low** = polish · **Info** = noted, no action needed.

### Windows

| # | Finding | Sev | Why it matters | Solution | Done |
|---|---|---|---|---|---|
| W-1 | `ChargerGreetings.ps1` plays `namaste.wav` / `fir-milte-hain.wav`; **only `.mp3` files exist**. `System.Media.SoundPlayer` cannot play MP3 either. | **Critical** | The app could never have made a sound. `Test-Path` fails, `Play-Greeting` returns silently. | Ship PCM WAV assets; play through `waveOut`. | ✅ |
| W-2 | `Install.ps1` runs with `$ErrorActionPreference='Stop'` and copies `namaste.wav` at line 11 — a file that does not exist. | **Critical** | **Installation aborts before creating the startup shortcut.** The product never installed successfully. | New single-file installer with the payload embedded, so files cannot go missing. | ✅ |
| W-3 | The script is wired to the older *"Namaste"* recordings, not the requested "मालिक, प्रणाम" / "फिर मिलते हैं, मालिक". | High | Wrong words even if W-1/W-2 were fixed. | Correct clips selected and named by role. | ✅ |
| W-4 | Polls `PowerLineStatus` every 750 ms in an infinite loop. | High | ~115,000 wakeups/day, keeps the CPU out of deep idle, measurable battery drain — and the brief explicitly forbids polling. | `RegisterPowerSettingNotification(GUID_ACDC_POWER_SOURCE)` + `SystemEvents`, both purely event-driven. | ✅ |
| W-5 | Autostart is a `.vbs` in the Startup folder launching `powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden`. | High | This is a textbook malware persistence pattern and is heuristically flagged by Defender and most AV. | Per-user `HKCU\…\Run` value pointing at a real `.exe`. No script host, no bypass. | ✅ |
| W-6 | On resume from sleep the loop compares against the pre-sleep state and speaks. | High | Waking the laptop greets you for something that happened while it was asleep. Brief forbids this. | Suspend/resume re-baselines silently with a 5 s grace window. | ✅ |
| W-7 | `PlaySync()` blocks the polling loop for the clip's duration. | Medium | Power changes during playback are missed entirely. | Playback runs on its own thread; the detector is never blocked. | ✅ |
| W-8 | `$ErrorActionPreference = 'SilentlyContinue'` at the top of the watcher. | Medium | Every failure is silent by construction — nothing to diagnose with. | Size-capped local log; the settings window surfaces problems in plain language. | ✅ |
| W-9 | No entry in Apps & Features; removal requires finding `UNINSTALL.cmd`. | Medium | Users cannot uninstall the normal way. | `HKCU\…\Uninstall` registration with Display name, version, icon, size and quiet-uninstall string. | ✅ |
| W-10 | No settings of any kind, no UI, no way to test the sounds. | Medium | Six required settings absent; no way to verify it works. | Tray menu + settings window: enable, per-direction, volume, delay, silent-mode, startup, tests. | ✅ |
| W-11 | No tray icon or any visible presence. | Low | An invisible background process that can only be stopped via Task Manager. | Tray icon that also shows state (colour = armed, grey = paused). | ✅ |
| W-12 | `UNINSTALL.cmd` relies on `^|` escaping inside `-Command`. | Low | Fragile across shells and quoting contexts. | Replaced by a real uninstaller binary. | ✅ |
| W-13 | No debounce beyond a single 600 ms recheck. | Medium | A reseated barrel jack can produce several greetings. | Debounce + confirmation re-read + cooldown. Verified by test. | ✅ |

### Defects found in the new code during live verification

Recording these because they were real, and because the second one is the kind
of bug a test suite that only runs on a pumped thread will never see.

| # | Finding | Sev | Why it matters | Solution | Done |
|---|---|---|---|---|---|
| W-14 | `GreetingController` captured `SynchronizationContext.Current` in its constructor — but it is constructed *before* the tray icon, so no control handle existed yet, WinForms had installed no context, and the capture was **null**. | **High** | Events arriving on the `SystemEvents` worker thread (the fallback path used when `RegisterPowerSettingNotification` is unavailable) would then start WinForms timers from a thread with no message pump. Those timers never tick, so the greeting is lost **silently**. Latent on this machine because the precise API works here and delivers on the UI thread. | Controller now owns a `Control` with a forced handle and marshals through `BeginInvoke`, removing the dependency on construction order entirely. | ✅ |
| W-15 | Installer's zip-slip guard compared paths with a bare `StartsWith`. | Low | A sibling folder named `…ChargerGreetingsEvil` would pass the check. Not exploitable (we author the payload) but wrong. | Compare against the root **with a trailing separator**. | ✅ |
| W-16 | Uninstaller's deferred self-delete used `cmd /c timeout /t 3`. | Medium | `timeout` requires a real console and exits immediately with *"Input redirection is not supported"* under `CreateNoWindow`, so `rmdir` ran while `Uninstall.exe` was still locked — leaving the install folder behind. | Use `ping -n 4 127.0.0.1`, which waits correctly with no console. | ✅ |

> W-14 was caught by tracing why a log line saying *"confirming in 700 ms"* was
> followed by confirmation ~3 s later. The latency turned out to be unrelated
> timer jitter, but reading that code path is what exposed the null capture.
> Test 34 reproduces both conditions (no ambient context at construction, event
> raised from another thread) and was confirmed to fail against the pre-fix
> code.

### Android

| # | Finding | Sev | Why it matters | Solution | Done |
|---|---|---|---|---|---|
| A-1 | Not a project: six loose files, no Gradle, no resources, no icons, no manifest SDK levels. | High | Cannot be built. The README asked the user to hand-assemble it in Android Studio. | Complete Gradle project, Kotlin 2.0 + Compose, version catalogue, signing config, R8 rules. | ✅ |
| A-2 | No bundled audio — the user must pick a file with the storage picker. | High | Core feature depends on an external file and a persistable URI grant that can be revoked. Not offline-safe. | Both clips bundled in `res/raw`. Zero external dependencies. | ✅ |
| A-3 | One sound for both directions; disconnect defaults **off**. | High | The brief requires two distinct greetings. | Two clips, two independent switches, both on by default. | ✅ |
| A-4 | No `BOOT_COMPLETED` receiver. | High | After a reboot the app has no baseline; the next event is judged against stale state. | `BootReceiver` re-baselines silently on boot and after app update. | ✅ |
| A-5 | No debounce, dedup or cooldown. | High | Repeated broadcasts each start a new `MediaPlayer`. | `GreetingEngine` with persisted state, contradiction check, dedup and cooldown. 29 unit tests, all passing. | ✅ |
| A-6 | `android:exported="true"` on the power receiver. | Medium | Any app on the device could spoof a power event. No reason to be exported. | `android:exported="false"`. | ✅ |
| A-7 | `goAsync()` `PendingResult` can leak if `prepareAsync` never calls back. | Medium | Holds the process alive until the system kills it; logs an ANR-style warning. | Playback wrapped in `withTimeoutOrNull(7 s)`; `finish()` in a `finally`. | ✅ |
| A-8 | No audio focus request. | Medium | Rudely talks over music; podcast apps lose position. | Transient ducking focus, always abandoned. | ✅ |
| A-9 | No volume, delay, or silent/DND handling. | Medium | Four required settings missing; app talks during meetings. | All present; silent mode also checks media volume and DND filter. | ✅ |
| A-10 | Package is `com.example.chargesound`. | Info | `com.example` cannot be published to Google Play. | `com.chargergreetings.app`. | ✅ |
| A-11 | AppCompat Views, not Compose. | Low | Brief prefers Compose; Views layout also had no dark-mode or accessibility work. | Material 3 Compose, dynamic colour, dark mode, full semantics. | ✅ |
| A-12 | `USAGE_MEDIA` playback with no silent-mode check. | Low | Media stream is not silenced by the ringer, so it would speak on silent. | `USAGE_ASSISTANCE_SONIFICATION` + an explicit, user-controlled silent-mode rule. | ✅ |
| A-13 | No `minSdk` / `targetSdk` declared anywhere. | Medium | Undefined compatibility surface. | `minSdk 24`, `targetSdk 36`, `compileSdk 37`, rationale documented in `build.gradle.kts`. | ✅ |

### Defects found in the new Android code when it was first built

The project was written before any Android toolchain existed on this machine.
Installing one and actually compiling it surfaced these.

| # | Finding | Sev | Why it matters | Solution | Done |
|---|---|---|---|---|---|
| A-14 | Backup rules used `<exclude>` for `diagnostics.log` while only `<include>`-ing the prefs file. | Medium | **Lint error**, and it fails `lintVitalRelease` — so no release build was possible. Once any `<include>` is present everything unnamed is already excluded, making the `<exclude>` invalid. The comment also over-claimed that engine state was excluded; backup granularity is per *file*, not per key. | Removed the invalid excludes and corrected the comments to state what actually happens (state travels with the prefs, and is re-baselined on first open). | ✅ |
| A-15 | Pinned AGP 8.7.3 / Gradle 8.9. | **High** | Gradle 8.x **cannot start** on JDK 25, which is what Android Studio 2026.1 bundles. The first sync would have failed outright on a current machine. | Re-pinned to AGP 9.3.0 / Gradle 9.5.0, verified by building. | ✅ |
| A-16 | Applied `org.jetbrains.kotlin.android`. | **High** | AGP 9 has built-in Kotlin; applying that plugin is a *fatal* error, not a warning. | Removed it — but kept `org.jetbrains.kotlin.plugin.compose`, which is still required and whose absence is a different fatal error. | ✅ |
| A-17 | `compileSdk 35`; `kotlinOptions` block. | Medium | Compose 1.12 (from BOM 2026.08.00) refuses consumers below API 37; `kotlinOptions` is removed in AGP 9. | `compileSdk 37` (targetSdk left at 36); JVM target now comes from `compileOptions`. | ✅ |
| A-18 | Lint `PropertyEscape` fails on `local.properties`. | Low | It rejects a value byte-identical to the fix it suggests, so no escaping satisfies it. Machine-local, gitignored file with no bearing on the app. | Downgraded that single check to a warning; every other lint error still fails the build, release included. | ✅ |

### Audio

| # | Finding | Sev | Why it matters | Solution | Done |
|---|---|---|---|---|---|
| S-1 | The two chosen clips sit at −23.9 and −23.5 LUFS — quiet, and 0.4 LU apart. | Medium | Barely audible at normal system volume, and the two greetings feel unequal. | Normalised to a **common** −18.4 LUFS (0.00 LU apart) with a −1.0 dBTP ceiling. | ✅ |
| S-2 | 0.45 s and 0.28 s of trailing silence, plus leading silence. | Medium | The greeting feels laggy and the app seems slow to respond. | Trimmed to a 30 ms margin, with a 20 ms lead pad for slow audio endpoints. | ✅ |
| S-3 | 16,648 bytes of ID3v2 tag on ~40 KB files — around 40 % of each file. | Low | Pure waste; slows the first read. | Shipping assets are PCM WAV with no tag. | ✅ |
| S-4 | MP3 must be decoded before it can play. | Medium | Adds decoder spin-up to a feature whose entire value is immediacy. | 16-bit PCM WAV: no decode step on either platform. | ✅ |
| S-5 | 10 MP3 files, 6 unique; `namaste*.mp3` is a *different phrase* from the one requested. | Info | Easy to wire up the wrong recording — which is exactly what W-3 did. | Assets named by role (`power_connected` / `power_disconnected`); sources preserved alongside. | ✅ |
| S-6 | Peak-to-loudness ratio ~17 dB limits how loud the clips can go without a limiter. | Info | Target was −16 LUFS; −18.4 is the loudest reachable with pure gain. | Accepted deliberately — see *Audio decisions* below. | n/a |

### Cross-cutting

| # | Finding | Sev | Why it matters | Solution | Done |
|---|---|---|---|---|---|
| X-1 | No product name, icon or visual identity. | Low | Two unrelated-looking apps. | One name, one mark, one palette across both. | ✅ |
| X-2 | No tests anywhere. | Medium | Every behaviour rule was unverifiable. | 35 Windows behaviour tests (all passing) + 20 Android JVM unit tests. | ✅ |
| X-3 | No permission audit possible (no manifest SDK levels, no dependency manifest). | Medium | Unknown attack surface. | Android requests exactly one permission; Windows writes only under `HKCU` and `%LOCALAPPDATA%`. | ✅ |

---

## 3. Audio decisions

**The original recordings were kept.** They are clean — no clipping, no
meaningful DC offset, correct phrases. Only container and level were changed:

1. **Format: MP3 → 16-bit PCM WAV, 44.1 kHz mono.** The greeting must start the
   instant the cable moves; MP3 costs a decoder spin-up on both platforms. WAV
   also removes any codec-availability question. Cost: ~135 KB per clip, which
   is irrelevant in an APK and irrelevant on Windows.
2. **Loudness: normalised to a shared −18.4 LUFS.** Both clips land on the *same
   number* (0.00 LU apart), which is the actual requirement — "consistent
   perceived loudness". Measured with ITU-R BS.1770-4 K-weighting and two-stage
   gating, not peak or RMS, because the two clips differ in spectral content.
3. **Why −18.4 and not −16.** These are close-mic'd voice recordings with a
   ~17 dB peak-to-loudness ratio. Reaching −16 LUFS within a −1.0 dBTP ceiling
   would require a limiter, and limiting a voice risks audible distortion. The
   brief said *normalise without distortion*, so gain is applied as a single
   constant scale factor and the level stops where the peaks say it must. Both
   clips are still **~5 dB louder** than the originals, and the volume slider
   covers the rest.
4. **Trim and fades.** Silence trimmed to a 30 ms margin; 8 ms fade-in and 30 ms
   fade-out guarantee no click; a 20 ms lead pad stops Bluetooth and USB
   endpoints swallowing the first syllable while they wake.

**One thing I could not verify: pronunciation.** I can measure level, silence,
clipping and format, but I cannot listen. The two files whose names match the
requested phrases were selected on that basis and because they are the newest.
Please play both test buttons once and confirm the words are right.

---

## 4. What was preserved

Everything. The original `Charger-Greetings-ASUS-Windows-11/`, `files/` and all
ten `.mp3` files are untouched. The two selected source recordings are also
copied to `assets/power_connected.source.mp3` and
`assets/power_disconnected.source.mp3` so the mastering can be re-run or
reversed at any time.
