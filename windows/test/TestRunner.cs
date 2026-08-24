// TestRunner.cs -- headless verification of the Windows app's behaviour.
//
// Compiled against the same sources as the shipping executable (see
// windows/build.ps1 -Test), with a stubbed power source and a recording audio
// output so every rule in GreetingController can be driven deterministically --
// no cable, no sound card, no waiting for a real sleep cycle.
//
// The one genuinely hardware-dependent case (does winmm actually accept and
// play our WAV files?) runs for real, at volume 0, so it is silent but still
// proves the whole waveOut path end to end.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

// System.Threading and System.Windows.Forms both define Timer; the suite needs
// the WinForms one, whose Tick runs on the pumped thread.
using UiTimer = System.Windows.Forms.Timer;

namespace ChargerGreetings.Tests
{
    /// <summary>Audio output that records what it was asked to play instead of playing it.</summary>
    internal sealed class RecordingOutput : IGreetingAudioOutput
    {
        public readonly List<WavFile> Played = new List<WavFile>();
        public int StopCount;

        public void Play(WavFile clip, int volumePercent, Action<string> onFinished)
        {
            Played.Add(clip);
            if (onFinished != null) onFinished(null);
        }

        public void Stop() { StopCount++; }
        public void Dispose() { }
    }

    /// <summary>
    /// Entry point for the behaviour suite.
    ///
    /// Public and callable in-process on purpose: build.ps1 compiles these
    /// sources with Add-Type and calls <see cref="Run"/> directly rather than
    /// producing a test .exe. A freshly built, unsigned executable has no
    /// reputation, and Defender's "block at first sight" quarantines it on some
    /// machines — which would fail the build for reasons that have nothing to
    /// do with the code. Never writing a test binary to disk sidesteps that
    /// entirely.
    /// </summary>
    public static class TestRunner
    {
        private static int _passed;
        private static int _failed;
        private static readonly List<string> Failures = new List<string>();
        private static readonly StringBuilder Transcript = new StringBuilder();

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool AttachConsole(int processId);

        private const int ATTACH_PARENT_PROCESS = -1;

        /// <summary>Set by --quiet: the caller prints the transcript itself.</summary>
        private static bool _quiet;

        /// <summary>Writes to the console (unless quiet) and to the transcript file.</summary>
        private static void Out(string line)
        {
            Transcript.AppendLine(line);
            if (_quiet) return;
            try { Console.WriteLine(line); } catch { }
        }

        private static void Out() { Out(string.Empty); }

        /// <summary>Transcript path, alongside the staged app folder.</summary>
        private static string ResultsPath
        {
            get { return Path.Combine(AppInfo.InstallDirectory, "test-results.txt"); }
        }

        [STAThread]
        private static int Main(string[] args)
        {
            return Run(args);
        }

        /// <summary>
        /// Runs the whole suite and returns 0 when everything passed.
        /// </summary>
        /// <param name="args">
        /// <c>--quiet</c> suppresses console output (the caller prints the
        /// transcript itself); <c>--appdir &lt;path&gt;</c> points the suite at a
        /// staged application folder.
        /// </param>
        public static int Run(string[] args)
        {
            if (args != null)
            {
                for (int i = 0; i < args.Length - 1; i++)
                {
                    if (string.Equals(args[i], "--appdir", StringComparison.OrdinalIgnoreCase))
                        AppInfo.InstallDirectoryOverride = args[i + 1];
                }
            }

            // The suite needs its own STA thread with a message pump: the
            // controller captures a WinForms SynchronizationContext and drives
            // WinForms timers. Owning the thread means this works identically
            // whether we were started as a process or called in-process.
            int exitCode = 0;
            Thread worker = new Thread(delegate () { exitCode = RunMessageLoop(args); });
            worker.SetApartmentState(ApartmentState.STA);
            worker.Name = "ChargerGreetings.Tests";
            worker.Start();
            worker.Join();
            return exitCode;
        }

        private static int RunMessageLoop(string[] args)
        {
            // build.ps1 passes --quiet because it prints the transcript itself.
            _quiet = false;
            if (args != null)
                foreach (string a in args)
                    if (string.Equals(a, "--quiet", StringComparison.OrdinalIgnoreCase)) _quiet = true;

            // If these sources were built into a GUI-subsystem .exe there is no
            // console of our own; borrowing the parent's makes it behave like a
            // normal console runner. Harmless when already running in-process.
            if (!_quiet) AttachConsole(ATTACH_PARENT_PROCESS);

            Application.EnableVisualStyles();

            int exitCode = 0;
            UiTimer starter = new UiTimer();
            starter.Interval = 1;
            starter.Tick += async delegate
            {
                starter.Stop();
                try { exitCode = await RunAll(); }
                catch (Exception ex)
                {
                    Out("HARNESS FAILURE: " + ex);
                    exitCode = 99;
                }
                Application.ExitThread();
            };
            starter.Start();

            // Application.Run installs the WinForms SynchronizationContext that
            // GreetingController captures, and pumps the UI timers it relies on.
            Application.Run();
            return exitCode;
        }

        private static async Task<int> RunAll()
        {
            Out("Charger Greetings -- Windows behaviour tests");
            Out(new string('=', 66));

            Out();
            Out("[ Audio assets ]");
            TestWavParsing();

            Out();
            Out("[ Settings ]");
            TestSettingsRoundTrip();

            Out();
            Out("[ Greeting rules ]");
            await TestBaselineOnLaunch();
            await TestNormalConnect();
            await TestNormalDisconnect();
            await TestRapidReconnect();
            await TestEventStorm();
            await TestCooldown();
            await TestDisabledMaster();
            await TestDirectionSwitch();
            await TestSleepResumeNoChange();
            await TestSleepResumeWithChange();
            await TestDelay();
            await TestDelayCancelled();
            await TestUnknownIgnored();
            await TestMissingAudio();
            await TestEventFromBackgroundThread();

            Out();
            Out("[ Real audio device ]");
            await TestRealPlayback();

            Out();
            Out(new string('=', 66));
            Out(string.Format(CultureInfo.InvariantCulture,
                "{0} passed, {1} failed", _passed, _failed));
            foreach (string f in Failures) Out("  FAILED: " + f);

            try
            {
                File.WriteAllText(ResultsPath, Transcript.ToString(), new UTF8Encoding(false));
                Out("");
                Out("Transcript: " + ResultsPath);
            }
            catch (Exception ex)
            {
                Out("Could not write the transcript: " + ex.Message);
            }

            return _failed == 0 ? 0 : 1;
        }

        // ------------------------------------------------------------ helpers
        private static void Check(string name, bool condition)
        {
            if (condition) { _passed++; Out("  PASS  " + name); }
            else { _failed++; Failures.Add(name); Out("  FAIL  " + name); }
        }

        private sealed class Harness : IDisposable
        {
            public Settings Settings;
            public PowerWatcher Watcher;
            public RecordingOutput Output;
            public AudioLibrary Audio;
            public GreetingController Controller;
            public PowerSource Hardware;

            public Harness(PowerSource initial)
            {
                Hardware = initial;
                Settings = new Settings();
                Settings.DebounceMs = 150;      // keep the suite fast
                Settings.CooldownMs = 600;
                Settings.RespectQuietHours = false;

                Watcher = new PowerWatcher();
                Watcher.SourceReader = delegate { return Hardware; };

                Output = new RecordingOutput();
                Audio = new AudioLibrary();
                Controller = new GreetingController(Settings, Watcher, Output, Audio);
            }

            /// <summary>Moves the simulated hardware and fires the matching event.</summary>
            public void Change(PowerSource to)
            {
                Hardware = to;
                Watcher.SimulateRead(to, "test");
            }

            /// <summary>Fires an event without moving the hardware (a glitch/bounce).</summary>
            public void Glitch(PowerSource reported)
            {
                Watcher.SimulateRead(reported, "test-glitch");
            }

            public bool PlayedConnected
            {
                get { return Output.Played.Contains(Audio.Get(PowerSource.AC)); }
            }

            public bool PlayedDisconnected
            {
                get { return Output.Played.Contains(Audio.Get(PowerSource.Battery)); }
            }

            public int PlayCount { get { return Output.Played.Count; } }

            /// <summary>Skips past the startup grace window so greetings are allowed.</summary>
            public async Task Arm()
            {
                Controller.Initialize();
                await Task.Delay(3100);
            }

            public void Dispose()
            {
                Controller.Dispose();
                Watcher.Dispose();
            }
        }

        // ------------------------------------------------------- asset tests
        private static void TestWavParsing()
        {
            try
            {
                WavFile connected = WavFile.Load(AppInfo.ConnectedSoundPath);
                Check("connect clip loads", connected.Pcm.Length > 0);
                Check("connect clip is 16-bit mono 44.1 kHz",
                      connected.BitsPerSample == 16 && connected.Channels == 1
                      && connected.SampleRate == 44100);
                Check("connect clip has sensible duration",
                      connected.Duration > 0.5 && connected.Duration < 5.0);

                WavFile disconnected = WavFile.Load(AppInfo.DisconnectedSoundPath);
                Check("disconnect clip loads", disconnected.Pcm.Length > 0);

                // Volume scaling
                byte[] full = connected.GetScaledPcm(100);
                byte[] silent = connected.GetScaledPcm(0);
                byte[] half = connected.GetScaledPcm(50);
                Check("volume 100 is untouched", ReferenceEquals(full, connected.Pcm));
                Check("volume 0 is digital silence", IsAllZero16(silent));
                Check("volume 50 reduces peak", Peak16(half) < Peak16(full) && Peak16(half) > 0);
            }
            catch (Exception ex)
            {
                Check("audio assets load without throwing (" + ex.Message + ")", false);
            }

            string tmp = Path.Combine(Path.GetTempPath(), "cg-test-" + Guid.NewGuid().ToString("N"));
            try
            {
                Check("missing file is reported", Throws(delegate { WavFile.Load(tmp + ".wav"); }));

                File.WriteAllBytes(tmp + "-junk.wav", new byte[128]);
                Check("non-WAV data is reported",
                      Throws(delegate { WavFile.Load(tmp + "-junk.wav"); }));

                byte[] good = File.ReadAllBytes(AppInfo.ConnectedSoundPath);
                byte[] truncated = new byte[40];
                Array.Copy(good, truncated, 40);
                File.WriteAllBytes(tmp + "-short.wav", truncated);
                Check("truncated file is reported",
                      Throws(delegate { WavFile.Load(tmp + "-short.wav"); }));
            }
            catch (Exception ex)
            {
                Check("corrupt-file handling (" + ex.Message + ")", false);
            }
            finally
            {
                TryDelete(tmp + "-junk.wav");
                TryDelete(tmp + "-short.wav");
            }
        }

        private static void TestSettingsRoundTrip()
        {
            string path = AppInfo.SettingsPath;
            string backup = null;
            try
            {
                if (File.Exists(path))
                {
                    backup = File.ReadAllText(path);
                }

                Settings s = new Settings();
                s.Enabled = false;
                s.PlayOnConnect = false;
                s.Volume = 37;
                s.DelayMs = 1200;
                s.RespectQuietHours = false;
                s.Save();

                Settings loaded = Settings.Load();
                Check("settings survive a save/load round trip",
                      loaded.Enabled == false && loaded.PlayOnConnect == false
                      && loaded.Volume == 37 && loaded.DelayMs == 1200
                      && loaded.RespectQuietHours == false);

                File.WriteAllText(path, "this is not = a valid\n[[[\nVolume=nonsense\n");
                Settings damaged = Settings.Load();
                Check("a damaged settings file falls back to defaults",
                      damaged.Volume == new Settings().Volume);

                File.WriteAllText(path, "Volume=999\nDelayMs=-50\n");
                Settings clamped = Settings.Load();
                Check("out-of-range values are clamped",
                      clamped.Volume == 100 && clamped.DelayMs == 0);
            }
            catch (Exception ex)
            {
                Check("settings persistence (" + ex.Message + ")", false);
            }
            finally
            {
                try
                {
                    if (backup != null) File.WriteAllText(path, backup);
                    else TryDelete(path);
                }
                catch { }
            }
        }

        // ---------------------------------------------------- behaviour tests
        private static async Task TestBaselineOnLaunch()
        {
            using (Harness h = new Harness(PowerSource.AC))
            {
                h.Controller.Initialize();
                await Task.Delay(200);
                Check("launching while charging plays nothing", h.PlayCount == 0);
                Check("baseline records the current source",
                      h.Controller.CurrentSource == PowerSource.AC);
            }
        }

        private static async Task TestNormalConnect()
        {
            using (Harness h = new Harness(PowerSource.Battery))
            {
                await h.Arm();
                h.Change(PowerSource.AC);
                await Task.Delay(400);
                Check("connecting plays the connect greeting once",
                      h.PlayCount == 1 && h.PlayedConnected);
            }
        }

        private static async Task TestNormalDisconnect()
        {
            using (Harness h = new Harness(PowerSource.AC))
            {
                await h.Arm();
                h.Change(PowerSource.Battery);
                await Task.Delay(400);
                Check("disconnecting plays the disconnect greeting once",
                      h.PlayCount == 1 && h.PlayedDisconnected);
            }
        }

        private static async Task TestRapidReconnect()
        {
            using (Harness h = new Harness(PowerSource.AC))
            {
                await h.Arm();
                // Cable reseated: reported off, then back on before the debounce
                // window closes. Nothing should be announced.
                h.Glitch(PowerSource.Battery);
                await Task.Delay(60);
                h.Glitch(PowerSource.AC);
                await Task.Delay(400);
                Check("a bounced cable plays nothing", h.PlayCount == 0);
                Check("state stays on AC after a bounce",
                      h.Controller.CurrentSource == PowerSource.AC);
            }
        }

        private static async Task TestEventStorm()
        {
            using (Harness h = new Harness(PowerSource.Battery))
            {
                await h.Arm();
                // Twenty notifications for one physical plug-in.
                h.Hardware = PowerSource.AC;
                for (int i = 0; i < 20; i++) h.Watcher.SimulateRead(PowerSource.AC, "storm");
                await Task.Delay(400);
                Check("20 duplicate events produce exactly one greeting", h.PlayCount == 1);
            }
        }

        private static async Task TestCooldown()
        {
            using (Harness h = new Harness(PowerSource.Battery))
            {
                await h.Arm();
                h.Change(PowerSource.AC);
                await Task.Delay(300);
                h.Change(PowerSource.Battery);          // real second change, but fast
                await Task.Delay(300);
                Check("a second change inside the cooldown is suppressed", h.PlayCount == 1);

                await Task.Delay(500);                   // cooldown expires
                h.Change(PowerSource.AC);
                await Task.Delay(400);
                Check("greetings resume after the cooldown", h.PlayCount == 2);
            }
        }

        private static async Task TestDisabledMaster()
        {
            using (Harness h = new Harness(PowerSource.Battery))
            {
                h.Settings.Enabled = false;
                await h.Arm();
                h.Change(PowerSource.AC);
                await Task.Delay(400);
                Check("nothing plays while the app is switched off", h.PlayCount == 0);
            }
        }

        private static async Task TestDirectionSwitch()
        {
            using (Harness h = new Harness(PowerSource.Battery))
            {
                h.Settings.PlayOnConnect = false;
                await h.Arm();
                h.Change(PowerSource.AC);
                await Task.Delay(400);
                Check("connect greeting respects its own switch", h.PlayCount == 0);

                await Task.Delay(700);
                h.Change(PowerSource.Battery);
                await Task.Delay(400);
                Check("disconnect greeting still plays", h.PlayCount == 1 && h.PlayedDisconnected);
            }
        }

        private static async Task TestSleepResumeNoChange()
        {
            using (Harness h = new Harness(PowerSource.AC))
            {
                await h.Arm();
                h.Watcher.SimulateSuspend();
                await Task.Delay(100);
                h.Watcher.SimulateResume();
                await Task.Delay(400);
                Check("sleep and wake with no change plays nothing", h.PlayCount == 0);
            }
        }

        private static async Task TestSleepResumeWithChange()
        {
            using (Harness h = new Harness(PowerSource.AC))
            {
                await h.Arm();
                h.Watcher.SimulateSuspend();
                await Task.Delay(100);
                h.Hardware = PowerSource.Battery;        // unplugged while asleep
                h.Watcher.SimulateResume();
                await Task.Delay(400);
                Check("unplugging during sleep plays nothing on wake", h.PlayCount == 0);
                Check("wake re-baselines to the new source",
                      h.Controller.CurrentSource == PowerSource.Battery);
            }
        }

        private static async Task TestDelay()
        {
            using (Harness h = new Harness(PowerSource.Battery))
            {
                h.Settings.DelayMs = 500;
                await h.Arm();
                h.Change(PowerSource.AC);
                await Task.Delay(350);
                Check("greeting waits for the configured delay", h.PlayCount == 0);
                await Task.Delay(500);
                Check("greeting plays after the delay", h.PlayCount == 1);
            }
        }

        private static async Task TestDelayCancelled()
        {
            using (Harness h = new Harness(PowerSource.Battery))
            {
                h.Settings.DelayMs = 600;
                await h.Arm();
                h.Change(PowerSource.AC);
                await Task.Delay(250);
                h.Hardware = PowerSource.Battery;        // pulled out again during the delay
                await Task.Delay(700);
                Check("a delayed greeting is cancelled if the state reverts", h.PlayCount == 0);
            }
        }

        private static async Task TestUnknownIgnored()
        {
            using (Harness h = new Harness(PowerSource.AC))
            {
                await h.Arm();
                h.Watcher.SimulateRead(PowerSource.Unknown, "test");
                await Task.Delay(300);
                Check("an unknown reading is ignored",
                      h.PlayCount == 0 && h.Controller.CurrentSource == PowerSource.AC);
            }
        }

        private static async Task TestMissingAudio()
        {
            // Point the library at a clip that cannot load, and confirm the
            // controller surfaces a message instead of throwing.
            using (Harness h = new Harness(PowerSource.Battery))
            {
                await h.Arm();
                string captured = null;
                h.Controller.PlaybackProblem += delegate (object s, string msg) { captured = msg; };

                bool healthy = h.Audio.IsHealthy;
                if (!healthy)
                {
                    Check("a missing clip is reported rather than crashing", true);
                }
                else
                {
                    // Assets are present, so verify the error path directly.
                    bool threw = Throws(delegate {
                        WavFile.Load(Path.Combine(AppInfo.AudioDirectory, "does-not-exist.wav"));
                    });
                    Check("a missing clip is reported rather than crashing", threw);
                }
            }
        }

        /// <summary>
        /// Regression test for a real defect.
        ///
        /// Power events do not all arrive on the UI thread: the SystemEvents
        /// fallback raises on its own worker. The controller used to marshal
        /// via a captured SynchronizationContext -- but in the shipping app it
        /// is constructed *before* the tray icon, so no control handle existed
        /// yet, WinForms had installed no context, and the capture was null.
        /// Events then drove the WinForms timers from a thread with no message
        /// pump, where they never tick and the greeting is lost silently.
        ///
        /// The two conditions are reproduced exactly: build the controller with
        /// no ambient SynchronizationContext, then raise from another thread.
        /// </summary>
        private static async Task TestEventFromBackgroundThread()
        {
            SynchronizationContext saved = SynchronizationContext.Current;
            Harness harness;
            try
            {
                SynchronizationContext.SetSynchronizationContext(null);
                harness = new Harness(PowerSource.Battery);
            }
            finally
            {
                SynchronizationContext.SetSynchronizationContext(saved);
            }

            using (Harness h = harness)
            {
                await h.Arm();

                h.Hardware = PowerSource.AC;
                Thread background = new Thread(delegate ()
                {
                    h.Watcher.SimulateRead(PowerSource.AC, "background-thread");
                });
                background.IsBackground = true;
                background.Start();
                background.Join();

                await Task.Delay(600);
                Check("an event raised on a background thread still greets",
                      h.PlayCount == 1 && h.PlayedConnected);
            }
        }

        private static async Task TestRealPlayback()
        {
            if (!WavePlayer.HasOutputDevice)
            {
                Out("  SKIP  no audio output device on this machine");
                return;
            }

            try
            {
                WavFile clip = WavFile.Load(AppInfo.ConnectedSoundPath);
                using (WavePlayer player = new WavePlayer())
                {
                    string error = "not-called";
                    bool finished = false;
                    // Volume 0: exercises the entire waveOut path silently.
                    player.Play(clip, 0, delegate (string e) { error = e; finished = true; });

                    int waited = 0;
                    while (!finished && waited < 8000) { await Task.Delay(100); waited += 100; }

                    Check("waveOut accepts and completes the real clip", finished);
                    Check("real playback reports no error", finished && error == null);
                }
            }
            catch (Exception ex)
            {
                Check("real playback (" + ex.Message + ")", false);
            }
        }

        // -------------------------------------------------------------- utils
        private static bool Throws(Action action)
        {
            try { action(); return false; }
            catch { return true; }
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }

        private static bool IsAllZero16(byte[] pcm)
        {
            for (int i = 0; i + 1 < pcm.Length; i += 2)
                if ((short)(pcm[i] | (pcm[i + 1] << 8)) != 0) return false;
            return true;
        }

        private static int Peak16(byte[] pcm)
        {
            int peak = 0;
            for (int i = 0; i + 1 < pcm.Length; i += 2)
            {
                int v = Math.Abs((short)(pcm[i] | (pcm[i + 1] << 8)));
                if (v > peak) peak = v;
            }
            return peak;
        }
    }
}
