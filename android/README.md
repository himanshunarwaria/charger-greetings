# Charger Greetings — Android

Kotlin · Jetpack Compose · Material 3 · minSdk 24 · targetSdk 36 · compileSdk 37

> **Verified building.** `:app:testDebugUnitTest` (29/29 pass) and
> `:app:assembleDebug` both succeed on this machine.

## Toolchain

Pinned to a set that was actually built, not guessed:

| Component | Version | Why this one |
|---|---|---|
| AGP | 9.3.0 | Current stable. Requires Gradle 9.5+ and JDK 17+. |
| Gradle | 9.5.0 | First line that runs on **JDK 25**, which is what Android Studio 2026.1 bundles. Gradle 8.x will not start on JDK 25 at all. |
| Kotlin | supplied by AGP | AGP 9 compiles Kotlin itself — see below. |
| Compose BOM | 2026.08.00 | Ships Compose 1.12.x. |
| compileSdk | 37 | Compose 1.12's AAR metadata refuses anything lower. |
| targetSdk | 36 | Runtime behaviour is opted into separately and deliberately. |
| minSdk | 24 | Oldest release where the manifest power-broadcast path, AudioAttributes and Doze all behave as this app needs. |

### Two AGP 9 gotchas that cost real time

Both were hit and fixed here; if you regenerate or upgrade this project, expect
them again.

1. **Do not apply `org.jetbrains.kotlin.android`.** AGP 9 has built-in Kotlin
   support, and applying the Kotlin Android plugin alongside it is a *fatal*
   error, not a warning: `AgpWithBuiltInKotlinAppliedCheck` fails the build with
   *"no longer required for Kotlin support since AGP 9.0"*.
2. **You still need `org.jetbrains.kotlin.plugin.compose`.** Removing both
   Kotlin plugins seems like the logical next step, and it fails with
   *"the Compose Compiler Gradle plugin is required when compose is enabled"*.

So exactly one Kotlin plugin is applied, and it is not the obvious one. There is
also no top-level `kotlin { }` block under AGP 9 — the JVM target comes from
`android { compileOptions { } }`.

---

## Build

### Android Studio (recommended for the first build)

1. **File → Open** → select this `android/` folder.
2. Let Gradle sync. The wrapper (`gradlew`, `gradlew.bat`,
   `gradle/wrapper/gradle-wrapper.jar`) is already generated, and
   `local.properties` already points at the SDK.
3. **Run ▶**, or **Build → Generate Signed App Bundle / APK**.

If Studio offers to upgrade AGP or Kotlin, be careful: the pinned versions were
chosen together and verified to build (see *Toolchain* below). In particular,
**do not let it re-add the `org.jetbrains.kotlin.android` plugin** — under
AGP 9 that is a hard error, not a warning.

### Command line

Once the wrapper exists:

```bash
./gradlew :app:testDebugUnitTest     # 29 unit tests, no emulator needed
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease       # signed if keystore.properties exists
./gradlew :app:bundleRelease         # AAB for Play
./gradlew :app:lint                  # Android Lint
```

---

## Run it on your phone

### 1. Put the phone in developer mode

1. **Settings → About phone** → tap **Build number** seven times.
2. **Settings → System → Developer options** → turn on **USB debugging**.
   (Samsung: *Settings → Developer options*. Xiaomi: also enable
   *Install via USB* and *USB debugging (Security settings)*.)
3. Plug the phone into the PC. Accept the **"Allow USB debugging?"** prompt on
   the phone — tick *Always allow from this computer*.

Confirm the PC can see it:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

You want a line ending in `device`. `unauthorized` means the prompt was not
accepted; `offline` usually means replugging the cable fixes it.

### 2. Build and install

In Android Studio: pick your phone in the device dropdown, press **Run ▶**.

Or from the command line, once the wrapper exists:

```powershell
.\gradlew.bat :app:installDebug
```

### 3. Verify it actually works

Order matters here — the first step is what registers the app with the system.

1. **Open the app once.** Android keeps a newly installed app in the *stopped
   state* and delivers it no broadcasts until it is launched manually. Nothing
   works before this, and it is the single most common reason a power-event app
   appears broken.
2. Press both **test** buttons. You should hear each greeting.
3. **Close the app and swipe it out of Recents.**
4. **Plug the charger in** → "मालिक, प्रणाम".
5. **Unplug it** → "फिर मिलते हैं, मालिक".
6. Reopen the app and read the **Recent activity** card. Every decision is
   logged there, including the reason for anything skipped.

If step 4 is silent but step 2 worked, it is background execution, not audio:
use the battery-optimisation button on the app's warning card, and on Xiaomi /
Oppo / Vivo / OnePlus also enable **Autostart** for the app.

### 4. Faking power events (no cable needed)

Useful for UI work and for the emulator, which cannot produce a real AC event:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $adb shell dumpsys battery set ac 1        # pretend the charger went in
& $adb shell dumpsys battery set ac 0        # pretend it came out
& $adb shell dumpsys battery set usb 1       # USB charging
& $adb shell dumpsys battery set wireless 1  # wireless charging
& $adb shell dumpsys battery reset           # hand control back to the hardware
```

**Always finish with `reset`**, or the phone keeps believing whatever you last
told it.

Caveat worth knowing: `dumpsys battery set` changes the battery *state*, and on
most builds that does fire `ACTION_POWER_CONNECTED`, but it is a simulation.
A real cable is still the test that counts.

### 5. Watch the log live

```powershell
& $adb logcat -s ChargerGreetings:D
```

Every decision the app makes appears here and in the in-app Recent activity card.

---

## Release signing

No key material is included, invented or hard-coded anywhere.

```bash
keytool -genkeypair -v -keystore charger-greetings-release.jks \
        -keyalg RSA -keysize 4096 -validity 10000 -alias release
```

Then `cp keystore.properties.template keystore.properties` and fill it in.

`app/build.gradle.kts` reads that file if it exists and configures release
signing from it. **If it does not exist the release build still succeeds** — it
just produces an unsigned artifact rather than failing.

`keystore.properties`, `*.jks` and `*.keystore` are all in `.gitignore`. Back the
keystore up: lose it and you can never update a published app.

---

## Layout

```
app/src/main/java/com/chargergreetings/app/
├── MainActivity.kt              one screen; opening it never speaks
├── core/
│   ├── GreetingEngine.kt        ★ all decision rules — zero Android types
│   ├── PowerState.kt            PowerState, ChargeKind, Greeting
│   └── SettingsRepository.kt    SharedPreferences + GreetingStore
├── power/
│   ├── PowerEventReceiver.kt    ACTION_POWER_CONNECTED / _DISCONNECTED
│   ├── BootReceiver.kt          re-baselines after reboot / app update
│   └── PowerStatus.kt           sticky battery intent, silent mode, Doze status
├── audio/GreetingPlayer.kt      MediaPlayer + transient ducking audio focus
├── ui/                          Compose settings screen, theme, view model
└── util/Diagnostics.kt          size-capped local log

app/src/test/…/GreetingEngineTest.kt        19 JVM tests for the rules
app/src/test/…/BaselineAndRecoveryTest.kt   10 JVM tests for restart/reboot
```

`GreetingEngine` is the piece worth reading. It has no Android imports at all,
because a `BroadcastReceiver` can run in a fresh process for every event — so
none of the decision state can live in memory. Everything goes through
`GreetingStore`, which makes the rules a pure function of (event, stored state,
config) and therefore testable without an emulator.

---

## Permissions

One:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Deliberately absent, and each for a reason:

| Not requested | Why not |
|---|---|
| `INTERNET` | The app makes no network calls. Its absence is a guarantee, not a promise. |
| `WAKE_LOCK` | Playback finishes inside the broadcast's own grace window. |
| `FOREGROUND_SERVICE` | There is no service. Nothing runs between events. |
| `POST_NOTIFICATIONS` | The app never posts a notification. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Play restricts it to app categories this is not in. The battery screen is opened with a plain `Settings` intent instead, which needs no permission. |

---

## How it survives the app being closed

`ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED` are on Android's
exemption list from the Android 8 implicit-broadcast restrictions. A
manifest-declared receiver is therefore still launched — in a new process if
needed — when the app is not running and has been swiped from Recents.

What that does **not** survive:

* **Force stop.** Android puts the app in the stopped state and delivers no
  broadcasts until the user opens it again. No app can work around this.
* **Aggressive OEM battery managers.** MIUI, ColorOS, FuntouchOS, OneUI and EMUI
  all ship killers beyond stock Doze. The app detects the Doze exemption state
  and offers a one-tap route to the right settings screen; some ROMs additionally
  need "Autostart" enabled by hand.

Both limits are surfaced in the UI rather than hidden.

---

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

Twenty tests covering: normal connect/disconnect, duplicate broadcasts, a
broadcast contradicted by the battery service (cable pulled straight back out),
rapid reconnect, cooldown expiry, a backwards system clock, each preference
switch, silent mode, and the reboot sequence.

What they cannot cover, and needs a real device: whether the broadcast actually
arrives with the app closed on *your* phone. That is the one thing worth testing
by hand, and it is the first item in `../TEST-CHECKLIST.md`.
