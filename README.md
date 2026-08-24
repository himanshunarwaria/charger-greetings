# Charger Greetings

Plays a spoken greeting the moment the charger is connected, and another when
it is removed.

> **मालिक, प्रणाम** — when power is connected
> **फिर मिलते हैं, मालिक** — when power is removed

Two apps, one product: a Windows tray utility and an Android app. Both work
completely offline, store nothing but your own settings, and have no accounts,
analytics, ads or network access of any kind.

---

## Status

| | Built | Tests | Verified on real hardware |
|---|---|---|---|
| **Windows** | ✅ 214 KB exe + installer | ✅ 36/36 | ✅ real charger events, sign-in startup |
| **Android** | ✅ 1.3 MB release APK | ✅ 19/19 | ⬜ needs your phone |

---

## Contents

```
ChargerGreetings/
├── AUDIT.md              What was found in the original folder and what changed
├── ARCHITECTURE.md       How both apps are put together, and why
├── ROADMAP.md            What to do before release, next, and later
├── TEST-CHECKLIST.md     Every test, with results
├── assets/               Mastered audio, source recordings, branding
├── android/              Android app (Kotlin + Jetpack Compose)
├── windows/              Windows app (C#, WinForms tray)
├── tools/                Audio mastering and icon generation
├── build/                Windows build output
└── dist/                 Windows installer and portable zip
```

---

## Windows

### Install

> **Already done on this machine.** The app is deployed to
> `%LOCALAPPDATA%\Programs\ChargerGreetings`, running, and set to start at
> sign-in. It was verified against real charger events — see
> [TEST-CHECKLIST.md](TEST-CHECKLIST.md).

**Portable (what was used, and what currently works):** unzip
`dist/ChargerGreetings-portable.zip` anywhere and run `ChargerGreetings.exe`.
Keep the `audio` folder next to the .exe. Turn on *Start with Windows* in
settings if you want it to persist.

**Installer:** `dist/ChargerGreetings-Setup.exe` installs per-user into
`%LOCALAPPDATA%\Programs\ChargerGreetings` — no UAC prompt, no administrator —
adds a Start Menu shortcut, and registers in **Settings → Apps → Installed
apps**.

⚠️ **On this machine Defender quarantined and deleted the installer on launch.**
That is a reputation block on an unsigned binary, not a signature match — see
the next section. Until it is signed, use the portable zip.

### About the SmartScreen / Defender warning

The build is **not code-signed**. In practice this turned out to be stronger
than a warning: Defender's *block at first sight* deleted
`ChargerGreetings-Setup.exe` outright, reporting *"the file contains a virus or
potentially unwanted software"*. Nothing shows in `Get-MpThreatDetection` and no
ASR rules are set, which confirms it is a cloud reputation verdict on an
unsigned, never-before-seen binary — not a detection of anything in the file.

The installer is the natural target: it extracts an embedded executable, writes
to a Programs folder, and sets `Run` and `Uninstall` registry keys. The app
executable itself runs without complaint.

**Workarounds, best first:**

1. **Sign the binaries.** Fixes this and SmartScreen permanently.
2. **Use the portable zip.** No installer, no block. This is what is deployed.
3. **Add a Defender exclusion** for `%LOCALAPPDATA%\Programs\ChargerGreetings`
   (requires administrator). Only helps on machines you control.

If you see the milder *"Windows protected your PC"* SmartScreen dialog instead,
click **More info → Run anyway**.

### Use

The plug icon lives in the notification area (click the **^** arrow if you do
not see it). **Right-click** for the menu, **double-click** for settings.

* **Coloured icon** — armed · **Grey icon** — paused

Settings: master switch, per-direction switches, volume, delay before speaking,
respect-quiet-hours, start with Windows, and a test button for each sound.

### Uninstall

**If you installed with the installer:** *Settings → Apps → Installed apps →
Charger Greetings → Uninstall*, or run `Uninstall.exe` from the install folder.

**If you are on the portable deployment** (the current state on this machine —
the installer was blocked, so there is no `Uninstall.exe` and no Apps entry),
remove it by hand:

```powershell
Get-Process ChargerGreetings -EA SilentlyContinue | Stop-Process
Remove-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name ChargerGreetings
Remove-Item "$env:LOCALAPPDATA\Programs\ChargerGreetings" -Recurse -Force
Remove-Item "$env:LOCALAPPDATA\ChargerGreetings" -Recurse -Force   # settings + log
```

That is the complete footprint — one folder for the program, one for your data,
one registry value. Nothing else is written anywhere.

### Build from source

Needs nothing but Windows itself — no SDK download.

```powershell
cd windows
.\build.ps1 -Test
```

This compiles the app with the C# compiler built into .NET Framework, generates
the icons, stages the audio, runs the 36-test behaviour suite, and produces the
installer and portable zip.

> **Why .NET Framework 4.8 and not .NET 8?** It is an OS component on every
> Windows 10/11 machine, so the app runs with no runtime install and weighs
> 214 KB instead of ~70 MB self-contained — and it builds with the compiler
> already on the machine. The source is written so it also compiles unchanged
> against `net8.0-windows` if you have the .NET SDK.

---

## Android

### Status

The project **builds and its tests pass**. Android Studio 2026.1, the SDK
(platform 37, build-tools 37.0.0) and Gradle 9.5.0 were installed and used to
verify:

```
:app:testDebugUnitTest   19/19 pass
:app:assembleDebug       app-debug.apk             18.4 MB
:app:assembleRelease     app-release-unsigned.apk   1.3 MB   (R8 shrinks it 14x)
:app:lintDebug           0 errors, 20 warnings
```

What has **not** happened: it has never run on a phone. That is the next step
and it needs your device.

### Run it on your phone

Full instructions, including the `adb` commands and the OEM battery-manager
quirks, are in [android/README.md](android/README.md). The short version:

1. Phone: **Settings → About phone** → tap **Build number** ×7, then
   **Developer options → USB debugging** on. Plug in, accept the prompt.
2. Open `android/` in Android Studio, pick your phone, press **Run ▶**
   (or `.\gradlew.bat :app:installDebug`).
3. **Open the app once.** Android keeps a newly installed app in the *stopped
   state* and delivers it no broadcasts until it is launched manually. Skip this
   and you will conclude it is broken when it is not.
4. Press both test buttons, then close the app and swipe it out of Recents.
5. Plug the charger in, then pull it out.

### Build

```bash
cd android
./gradlew :app:testDebugUnitTest     # 19 unit tests for the decision rules
./gradlew :app:assembleDebug         # debug APK
./gradlew :app:bundleRelease         # release AAB (needs signing, below)
```

> The pinned toolchain (AGP 9.3.0, Gradle 9.5.0, Compose BOM 2026.08.00,
> compileSdk 37) was chosen to work with the JDK 25 that Android Studio 2026.1
> bundles, and was verified by actually building. If Studio offers to change
> versions, read the *Toolchain* section of `android/README.md` first — in
> particular, AGP 9 **must not** have the `org.jetbrains.kotlin.android` plugin
> applied.

### Release signing

No key material is included or invented. To sign:

```bash
keytool -genkeypair -v -keystore charger-greetings-release.jks \
        -keyalg RSA -keysize 4096 -validity 10000 -alias release
```

Copy `keystore.properties.template` to `keystore.properties`, fill it in, and
build. Without that file the release build still succeeds — it just produces an
unsigned artifact. `keystore.properties`, `*.jks` and `*.keystore` are in
`.gitignore`.

### Permissions

Exactly one: `RECEIVE_BOOT_COMPLETED`, so the charger state can be re-baselined
after a restart. There is deliberately no `INTERNET`, no `WAKE_LOCK`, no
`FOREGROUND_SERVICE` and no notification permission. (The APK also carries
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level self-permission
that AGP injects automatically for apps targeting API 33+; it grants nothing.)

---

## Limitations, honestly

### Android

| Limitation | Detail |
|---|---|
| **Force stop kills it** | If you force-stop the app from Settings, Android delivers no broadcasts until you open it again. This is by design and no app can work around it. |
| **OEM battery managers** | Xiaomi/MIUI, Oppo/ColorOS, Vivo, OnePlus, Samsung and Huawei ship aggressive killers that suppress background receivers. The app detects this and offers a one-tap route to the right settings screen. On some ROMs you must also enable "Autostart" manually. |
| **Charging type** | AC, USB, wireless and dock all trigger the greeting. The app shows which one it saw but does not treat them differently. |
| **Unplugged while off** | If you remove the charger while the phone is powered down, there is no greeting on boot — booting is not something you did to the cable. |
| **Doze** | On a deeply dozing device the broadcast can be delayed by a few hundred milliseconds. It is not dropped. |

### Windows

| Limitation | Detail |
|---|---|
| **Desktops without a battery or UPS** | Windows reports "always on AC", so there is nothing to detect. The status line says so plainly. |
| **Sleep and hibernate** | Waking up never greets, even if the cable moved while the machine was asleep. The state is re-baselined silently instead. |
| **Do Not Disturb** | Detected through the documented per-user notifications switch, which Windows 11 clears during DND. There is no supported API for Focus Assist state, so this is best-effort and fails open (it plays). |
| **Debounce latency** | Measured 1.3–3.0 s between event and greeting against a configured 700 ms, apparently `WM_TIMER` jitter during the activity burst after an AC transition. No events lost or duplicated. |
| **Unsigned build** | See the SmartScreen section above. |

---

## Troubleshooting

**Nothing plays when I plug in.**

1. Is the icon coloured, not grey? (Windows) Is "Play greetings" on? (Android)
2. Press a test button. If the test works but real events do not, it is a
   background-execution problem — on Android, open the battery settings link in
   the app; on Windows, check Task Manager → Startup apps.
3. If the test also fails, check your audio output device.
4. Read the log. Windows: **Settings → Open log folder**. Android: the *Recent
   activity* card. It states the reason for every skipped greeting — cooldown,
   silent mode, switched off, duplicate event.

**It played twice.** It should not; that is what the cooldown exists for. Send
the log — the reason will be in it.

**It plays when I don't want it to (meetings).** Turn on "Respect silent mode"
(Android) or "Stay silent while Windows notifications are off" (Windows), or use
the master switch.

**The greeting is too quiet / too loud.** Volume slider. The clips are mastered
to a fixed level, so the slider is the only thing you need.

**Windows: it stopped working after I moved the folder.** It repairs its own
startup entry on next launch. Launch it once from the new location.

---

## Privacy

* No network permission on Android; no network code on Windows.
* No accounts, analytics, ads, telemetry or crash reporting.
* Settings are stored locally: `%LOCALAPPDATA%\ChargerGreetings\settings.ini` on
  Windows, private SharedPreferences on Android. Both are plain text.
* The diagnostic log contains power-state transitions and decisions only. It is
  capped in size, never leaves the device, and can be cleared from the UI.
* No secrets or signing keys are stored anywhere in this repository.

---

## Product name

The folder already established **Charger Greetings**, so that was kept — it is
accurate, pronounceable and already what the existing install is called. The
alternatives considered were *Pranaam* (more distinctive, but narrows the
product to one phrase) and *PlugTone* (generic, and wrong for a spoken
greeting).

The mark is a power plug on an indigo→violet tile, generated from a single
source (`tools/IconGen`) for Windows and drawn as a matching adaptive vector on
Android, so the two apps read as one product.
