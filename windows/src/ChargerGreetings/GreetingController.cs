// GreetingController.cs -- decides whether a power event deserves a greeting.
//
// Everything that makes this app feel reliable rather than twitchy lives here:
//
//   Baseline on startup   The source at launch is recorded silently, so opening
//                         the app (or signing in) while already charging never
//                         triggers a greeting.
//   Debounce              A new source must hold for DebounceMs before it counts.
//                         Reseating a barrel jack or a loose USB-C cable produces
//                         a burst of transitions; only the settled result speaks.
//   Confirmation re-read  When the debounce expires the state is read again from
//                         Windows. If it bounced back, nothing plays.
//   Cooldown              A hard floor between greetings, so even a pathological
//                         event storm cannot stack voices.
//   Sleep suppression     Suspend/resume re-baselines silently. Waking up is not
//                         a power event the user performed.
//
// All state transitions happen on the UI thread via the captured
// SynchronizationContext, so no locking is required.

using System;
using System.Globalization;
using System.Threading;
using System.Windows.Forms;

// Both namespaces define Timer. The WinForms one is the right choice here:
// its Tick fires on the UI thread, which is what lets this whole class stay
// lock-free.
using UiTimer = System.Windows.Forms.Timer;

namespace ChargerGreetings
{
    internal sealed class GreetingController : IDisposable
    {
        private readonly PowerWatcher _watcher;
        private readonly IGreetingAudioOutput _player;
        private readonly AudioLibrary _audio;

        /// <summary>
        /// Owns the thread affinity for every state transition.
        ///
        /// A plain <see cref="SynchronizationContext"/> capture is not usable
        /// here: WinForms only installs one once a control handle exists, and
        /// this controller is constructed before the tray icon. Capturing there
        /// would silently yield null, and the SystemEvents fallback path (which
        /// raises on its own thread) would then drive the WinForms timers below
        /// cross-thread -- where they simply never tick.
        ///
        /// Creating a control and forcing its handle makes the marshalling
        /// target explicit and independent of construction order.
        /// </summary>
        private readonly Control _marshal;

        private Settings _settings;

        private readonly UiTimer _debounceTimer;
        private readonly UiTimer _delayTimer;

        private PowerSource _stableSource = PowerSource.Unknown;
        private PowerSource _pendingSource = PowerSource.Unknown;
        private PowerSource _queuedForPlayback = PowerSource.Unknown;

        private DateTime _lastGreetingUtc = DateTime.MinValue;
        private DateTime _suppressUntilUtc = DateTime.MinValue;

        /// <summary>Raised whenever the tray UI should refresh.</summary>
        public event EventHandler StatusChanged;

        /// <summary>Raised with a user-facing message when playback fails.</summary>
        public event EventHandler<string> PlaybackProblem;

        public PowerSource CurrentSource { get { return _stableSource; } }
        public AudioLibrary Audio { get { return _audio; } }
        public bool PreciseDetection { get { return _watcher.UsingPowerSettingNotification; } }

        public GreetingController(Settings settings, PowerWatcher watcher,
                                  IGreetingAudioOutput player, AudioLibrary audio)
        {
            _settings = settings;
            _watcher = watcher;
            _player = player;
            _audio = audio;

            _marshal = new Control();
            // Touching Handle forces creation now, on the thread that will pump
            // messages, so BeginInvoke works from any thread afterwards.
            IntPtr forceHandleCreation = _marshal.Handle;
            GC.KeepAlive(forceHandleCreation);

            _debounceTimer = new UiTimer();
            _debounceTimer.Tick += OnDebounceElapsed;

            _delayTimer = new UiTimer();
            _delayTimer.Tick += OnDelayElapsed;

            _watcher.PowerSourceRead += OnPowerSourceRead;
            _watcher.Suspending += OnSuspending;
            _watcher.Resumed += OnResumed;
        }

        /// <summary>Records the power source at launch without making a sound.</summary>
        public void Initialize()
        {
            _stableSource = _watcher.ReadCurrentSource();
            // A short grace window covers the burst of events Windows delivers
            // during sign-in, before the desktop has settled.
            _suppressUntilUtc = DateTime.UtcNow.AddSeconds(3);
            Logger.Info("Baseline power source at launch: " + _stableSource + " (silent)");
            RaiseStatusChanged();
        }

        public void UpdateSettings(Settings settings)
        {
            _settings = settings;
        }

        // ------------------------------------------------------- event intake
        private void OnPowerSourceRead(object sender, PowerSourceEventArgs e)
        {
            // Signals arrive from two threads (our message window and the
            // SystemEvents worker). Funnel both onto the UI thread.
            PowerSourceEventArgs captured = e;
            RunOnUi(delegate { ProcessRead(captured); });
        }

        /// <summary>
        /// Runs <paramref name="action"/> on the thread that owns the timers.
        /// Everything that touches controller state goes through here, which is
        /// why none of it needs locking.
        /// </summary>
        private void RunOnUi(Action action)
        {
            Control marshal = _marshal;
            if (marshal == null || marshal.IsDisposed || !marshal.IsHandleCreated)
            {
                action();
                return;
            }

            if (!marshal.InvokeRequired)
            {
                action();
                return;
            }

            try
            {
                marshal.BeginInvoke(action);
            }
            catch (ObjectDisposedException)
            {
                // Shutting down; the event no longer matters.
            }
            catch (InvalidOperationException)
            {
                // Handle destroyed between the check and the call.
            }
        }

        private void ProcessRead(PowerSourceEventArgs e)
        {
            if (e.Source == PowerSource.Unknown)
            {
                Logger.Info("Ignored power read: source unknown (" + e.Origin + ")");
                return;
            }

            if (e.Source == _stableSource)
            {
                // Either nothing changed, or a bounce came back to where it
                // started before the debounce expired. Either way: cancel.
                if (_pendingSource != PowerSource.Unknown)
                {
                    Logger.Info("Bounce absorbed: returned to " + _stableSource
                                + " before debounce expired.");
                    _pendingSource = PowerSource.Unknown;
                    _debounceTimer.Stop();
                }
                return;
            }

            if (e.Source == _pendingSource) return;   // duplicate signal, timer already running

            _pendingSource = e.Source;
            _debounceTimer.Stop();
            _debounceTimer.Interval = Math.Max(50, _settings.DebounceMs);
            _debounceTimer.Start();
            Logger.Info("Power change seen: " + _stableSource + " -> " + e.Source
                        + " via " + e.Origin + "; confirming in "
                        + _settings.DebounceMs + " ms");
        }

        private void OnDebounceElapsed(object sender, EventArgs e)
        {
            _debounceTimer.Stop();

            PowerSource candidate = _pendingSource;
            _pendingSource = PowerSource.Unknown;
            if (candidate == PowerSource.Unknown) return;

            // Ask Windows again rather than trusting the queued event: this is
            // what makes rapid reconnection produce one greeting, not several.
            PowerSource actual = _watcher.ReadCurrentSource();
            if (actual == PowerSource.Unknown) actual = candidate;

            if (actual == _stableSource)
            {
                Logger.Info("Debounce expired but state returned to " + _stableSource
                            + "; nothing to announce.");
                return;
            }

            _stableSource = actual;
            Logger.Info("Power source confirmed: " + _stableSource);
            RaiseStatusChanged();
            ConsiderGreeting(_stableSource);
        }

        private void OnSuspending(object sender, EventArgs e)
        {
            Logger.Info("System suspending; greetings paused.");
            _debounceTimer.Stop();
            _pendingSource = PowerSource.Unknown;
            _player.Stop();
        }

        private void OnResumed(object sender, EventArgs e)
        {
            RunOnUi(HandleResume);
        }

        private void HandleResume()
        {
            // Re-baseline silently. If the charger was pulled while the machine
            // slept, that is not something to greet on wake -- the user is not
            // standing at the machine performing the action.
            PowerSource now = _watcher.ReadCurrentSource();
            _pendingSource = PowerSource.Unknown;
            _debounceTimer.Stop();

            if (now != PowerSource.Unknown && now != _stableSource)
                Logger.Info("Power source changed during sleep (" + _stableSource
                            + " -> " + now + "); re-baselined without a greeting.");

            if (now != PowerSource.Unknown) _stableSource = now;
            _suppressUntilUtc = DateTime.UtcNow.AddSeconds(5);
            RaiseStatusChanged();
        }

        // ---------------------------------------------------------- greeting
        private void ConsiderGreeting(PowerSource source)
        {
            if (!_settings.Enabled)
            {
                Logger.Info("Greeting skipped: app disabled.");
                return;
            }

            if (DateTime.UtcNow < _suppressUntilUtc)
            {
                Logger.Info("Greeting skipped: inside the post-resume/startup grace window.");
                return;
            }

            bool wanted = source == PowerSource.AC
                ? _settings.PlayOnConnect
                : _settings.PlayOnDisconnect;
            if (!wanted)
            {
                Logger.Info("Greeting skipped: this direction is switched off.");
                return;
            }

            double sinceLast = (DateTime.UtcNow - _lastGreetingUtc).TotalMilliseconds;
            if (sinceLast < _settings.CooldownMs)
            {
                Logger.Info("Greeting skipped: cooldown ("
                            + sinceLast.ToString("0", CultureInfo.InvariantCulture)
                            + " ms since last, need " + _settings.CooldownMs + " ms).");
                return;
            }

            if (_settings.RespectQuietHours && QuietHours.IsActive())
            {
                Logger.Info("Greeting skipped: Windows notifications are silenced.");
                return;
            }

            if (_settings.DelayMs > 0)
            {
                _queuedForPlayback = source;
                _delayTimer.Stop();
                _delayTimer.Interval = _settings.DelayMs;
                _delayTimer.Start();
                return;
            }

            Speak(source, false);
        }

        private void OnDelayElapsed(object sender, EventArgs e)
        {
            _delayTimer.Stop();
            PowerSource queued = _queuedForPlayback;
            _queuedForPlayback = PowerSource.Unknown;
            if (queued == PowerSource.Unknown) return;

            // The user may have pulled the cable again during the delay.
            if (_watcher.ReadCurrentSource() != queued)
            {
                Logger.Info("Delayed greeting cancelled: state changed during the delay.");
                return;
            }
            Speak(queued, false);
        }

        /// <summary>Plays a greeting on demand from the tray menu or settings window.</summary>
        public void PlayTest(PowerSource source)
        {
            Speak(source, true);
        }

        private void Speak(PowerSource source, bool isTest)
        {
            WavFile clip = _audio.Get(source);
            if (clip == null)
            {
                string problem = _audio.GetError(source);
                Logger.Warn("Cannot play " + source + " greeting: " + problem);
                RaiseProblem(problem);
                return;
            }

            if (!isTest) _lastGreetingUtc = DateTime.UtcNow;

            Logger.Info((isTest ? "Test: " : "Playing: ")
                        + (source == PowerSource.AC ? AppInfo.ConnectedPhrase
                                                    : AppInfo.DisconnectedPhrase)
                        + " at volume " + _settings.Volume);

            _player.Play(clip, _settings.Volume, delegate (string error)
            {
                if (error == null) return;
                // Called back on the audio worker thread.
                RunOnUi(delegate { RaiseProblem(error); });
            });
        }

        private void RaiseStatusChanged()
        {
            if (StatusChanged != null) StatusChanged(this, EventArgs.Empty);
        }

        private void RaiseProblem(string message)
        {
            if (PlaybackProblem != null) PlaybackProblem(this, message);
        }

        public void Dispose()
        {
            _watcher.PowerSourceRead -= OnPowerSourceRead;
            _watcher.Suspending -= OnSuspending;
            _watcher.Resumed -= OnResumed;
            _debounceTimer.Dispose();
            _delayTimer.Dispose();
            if (_marshal != null) _marshal.Dispose();
        }
    }
}
