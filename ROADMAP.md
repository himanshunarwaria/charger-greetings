# Improvement roadmap

---

## Essential before release

Things that are genuinely blocking, in order.

1. **Listen to both greetings.** Press the two test buttons. This is the only
   requirement I could not verify — I can measure the audio but not hear it.
   If either phrase or pronunciation is wrong, replace the source MP3 in the
   project root and re-run `tools/AudioPrep`; everything downstream is automatic.

2. **Build and run the Android app once.** The project is complete but has never
   been compiled — this machine has no JDK, Gradle or Android SDK. Open
   `android/` in Android Studio, sync, and run `./gradlew :app:testDebugUnitTest`
   before anything else. Expect the sync to offer newer AGP/Kotlin versions;
   take them together, not one at a time.

3. **Test on real Android hardware**, specifically: app closed, app swiped from
   Recents, and after a reboot. These are the three cases where OEM behaviour
   actually differs, and they are the whole point of the app.

4. **Decide what to do about the installer.** Defender quarantined and deleted
   `ChargerGreetings-Setup.exe` on launch — confirmed, not hypothetical (see
   §2b of the test checklist). The app itself runs fine and is currently
   deployed from the portable zip. Three options, in order of preference:
   *sign the installer* (below); *add a Defender exclusion* for
   `%LOCALAPPDATA%\Programs\ChargerGreetings` (needs admin, and only helps on
   your machines); or *ship the portable zip only* and drop the installer.
   Until one of these is chosen, the uninstall path is untested because the
   installer never ran.

5. **Decide on Windows code signing.** This is now the highest-leverage item —
   it fixes the installer block and the SmartScreen prompt in one step. If the
   app is just for you, skip it and use the portable zip. If anyone else will
   run it, an OV certificate (~$200/yr) is the honest fix; EV clears SmartScreen
   immediately. See the README.

6. **Set the Android `versionCode`/`versionName` policy** before the first
   release, and generate the release keystore. Back the keystore up: losing it
   means you can never update a published app.

---

## Recommended next

Worth doing, none of it blocking.

1. **Windows: modern .NET build.** The source already compiles unchanged against
   `net8.0-windows`. Once you install the .NET SDK, produce that variant too and
   decide which one you ship. .NET Framework 4.8 stays the better default for
   distribution (no runtime install, 214 KB), so this is about future-proofing
   rather than fixing anything.

2. **Android instrumentation tests.** The decision rules are covered by unit
   tests, but `PowerEventReceiver`, `GreetingPlayer` and the Compose screen are
   not. A handful of Espresso/Compose tests plus a `BroadcastReceiver` test with
   a shadow context would close the gap.

3. **Per-charge-type greetings.** The app already knows whether power arrived by
   AC, USB, wireless or dock — it just does not act on it. A different greeting
   for wireless charging is a small change and a genuinely nice touch.

4. **Battery-level awareness.** "मालिक, प्रणाम — battery is at 20 %" is a natural
   extension, and the battery level is already in the same sticky intent the app
   reads. Needs either recorded number clips or TTS.

5. **Windows: a first-run walkthrough.** The settings window currently opens on
   first launch, which is fine, but a two-step "here is where the icon lives,
   press this to test" would reduce the "is it even running?" question.

6. **Android: a home-screen widget or Quick Settings tile** for the master
   switch. Currently you must open the app to pause greetings.

7. **Localised UI strings.** The app UI is English; the greetings are Hindi. A
   Hindi `values-hi/strings.xml` would be consistent. The resource
   configuration already declares `en` and `hi`.

8. **Icon polish by a designer.** The generated plug mark is clean and works at
   16 px, but it is geometry, not craft. A designer would improve it in an hour.

---

## Optional premium

Ideas, not obligations.

1. **Custom greeting recording in-app.** Record or import your own phrases, with
   the same BS.1770 normalisation applied automatically so user clips match the
   built-in ones. The mastering code in `tools/AudioPrep` is already written and
   could be ported.

2. **Multiple voices / greeting packs.** Time-of-day variants ("शुभ प्रभात,
   मालिक" before noon), or a rotating set so it does not become monotonous.

3. **Text-to-speech fallback.** Type any phrase, have the device speak it. Kills
   the need for recordings entirely, at the cost of a synthetic voice.

4. **Cross-device pairing.** Same greeting on laptop and phone, configured once.
   Would require either a network permission or a local-only pairing scheme —
   worth being very careful here, since "no network access" is currently one of
   the app's better properties.

5. **Charging analytics, locally.** How often you charge, at what battery level,
   for how long — rendered on-device with nothing transmitted.

6. **macOS and Linux builds.** The decision logic is small and portable; only
   the power-detection and audio layers are platform-specific. macOS would use
   `IOPSNotificationCreateRunLoopSource`, Linux `upower`/D-Bus.

7. **Accessibility beyond the basics.** Haptic feedback on Android as a silent
   alternative to the greeting; a high-contrast theme variant on Windows beyond
   the system high-contrast passthrough that exists now.

8. **Signed, reproducible builds** with a CI pipeline that produces the APK, AAB,
   Windows exe and installer from a tag, so releases stop depending on one
   machine's toolchain.
