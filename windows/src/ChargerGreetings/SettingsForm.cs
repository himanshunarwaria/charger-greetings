// SettingsForm.cs -- the single settings window.
//
// Built in code rather than with the designer so the whole layout is reviewable
// in one file and there is no .resx to drift out of sync.
//
// UX decisions:
//   * Changes save the moment they are made. This is a utility with eight
//     settings; an OK/Cancel round trip would be ceremony for its own sake.
//   * The status block is the first thing you see, because the question a user
//     actually opens this window to answer is "is it working?".
//   * Every problem is stated in plain language with the fix attached.

using System;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.Windows.Forms;

namespace ChargerGreetings
{
    internal sealed class SettingsForm : Form
    {
        private readonly Settings _settings;
        private readonly GreetingController _controller;
        private readonly Action _onSettingsChanged;

        private Label _statusPower;
        private Label _statusDetection;
        private Label _statusAudio;
        private Label _statusStartup;

        private CheckBox _enabled;
        private CheckBox _onConnect;
        private CheckBox _onDisconnect;
        private CheckBox _quietHours;
        private CheckBox _startup;
        private TrackBar _volume;
        private Label _volumeValue;
        private TrackBar _delay;
        private Label _delayValue;

        private bool _loading;

        public SettingsForm(Settings settings, GreetingController controller,
                            Action onSettingsChanged)
        {
            _settings = settings;
            _controller = controller;
            _onSettingsChanged = onSettingsChanged;

            BuildUi();
            LoadValues();
            RefreshStatus();

            _controller.StatusChanged += OnControllerStatusChanged;
        }

        // ------------------------------------------------------------ layout
        private void BuildUi()
        {
            Text = AppInfo.ProductName;
            Icon = AppResources.WindowIcon;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(470, 616);
            Font = new Font("Segoe UI", 9.75f, FontStyle.Regular, GraphicsUnit.Point);
            AutoScaleMode = AutoScaleMode.Dpi;
            KeyPreview = true;

            int x = 22;
            int width = ClientSize.Width - x * 2;
            int y = 20;

            // --- header ---
            PictureBox logo = new PictureBox();
            logo.Image = AppResources.WindowIcon.ToBitmap();
            logo.SizeMode = PictureBoxSizeMode.Zoom;
            logo.Bounds = new Rectangle(x, y, 40, 40);
            logo.TabStop = false;
            logo.AccessibleName = AppInfo.ProductName + " logo";
            Controls.Add(logo);

            Label title = MakeLabel(AppInfo.ProductName, x + 52, y + 1, width - 52, 24);
            title.Font = new Font("Segoe UI", 14f, FontStyle.Regular, GraphicsUnit.Point);
            Label subtitle = MakeLabel("Version " + AppInfo.Version + " · runs entirely offline",
                                       x + 52, y + 24, width - 52, 18);
            subtitle.ForeColor = Theme.Subtle;
            y += 58;

            y = AddDivider(x, y, width);

            // --- status ---
            Label statusHeading = MakeLabel("Status", x, y, width, 20);
            statusHeading.Font = new Font(Font, FontStyle.Bold);
            y += 26;

            _statusPower = MakeLabel("", x, y, width, 19); y += 21;
            _statusDetection = MakeLabel("", x, y, width, 19); y += 21;
            _statusAudio = MakeLabel("", x, y, width, 19); y += 21;
            _statusStartup = MakeLabel("", x, y, width, 19); y += 27;

            y = AddDivider(x, y, width);

            // --- behaviour ---
            Label behaviourHeading = MakeLabel("Greetings", x, y, width, 20);
            behaviourHeading.Font = new Font(Font, FontStyle.Bold);
            y += 26;

            _enabled = MakeCheck("Play greetings", x, y, width);
            _enabled.AccessibleDescription =
                "Master switch. When off, no greeting plays for any power change.";
            _enabled.CheckedChanged += OnAnyChanged;
            y += 28;

            _onConnect = MakeCheck("When the charger is connected  —  “मालिक, प्रणाम”",
                                   x + 20, y, width - 20);
            _onConnect.CheckedChanged += OnAnyChanged;
            y += 26;

            _onDisconnect = MakeCheck("When the charger is removed  —  “फिर मिलते हैं, मालिक”",
                                      x + 20, y, width - 20);
            _onDisconnect.CheckedChanged += OnAnyChanged;
            y += 32;

            // --- volume ---
            MakeLabel("Volume", x, y, 120, 20);
            _volumeValue = MakeLabel("", x + width - 60, y, 60, 20);
            _volumeValue.TextAlign = ContentAlignment.TopRight;
            y += 22;

            _volume = new TrackBar();
            _volume.Bounds = new Rectangle(x - 6, y, width + 12, 40);
            _volume.Minimum = 0;
            _volume.Maximum = 100;
            _volume.TickFrequency = 10;
            _volume.SmallChange = 5;
            _volume.LargeChange = 10;
            _volume.AccessibleName = "Greeting volume";
            _volume.Scroll += OnAnyChanged;
            Controls.Add(_volume);
            y += 44;

            // --- delay ---
            MakeLabel("Delay before speaking", x, y, 220, 20);
            _delayValue = MakeLabel("", x + width - 90, y, 90, 20);
            _delayValue.TextAlign = ContentAlignment.TopRight;
            y += 22;

            _delay = new TrackBar();
            _delay.Bounds = new Rectangle(x - 6, y, width + 12, 40);
            _delay.Minimum = 0;
            _delay.Maximum = 30;                 // 30 steps of 100 ms = 3 s
            _delay.TickFrequency = 5;
            _delay.AccessibleName = "Delay before the greeting plays";
            _delay.Scroll += OnAnyChanged;
            Controls.Add(_delay);
            y += 46;

            y = AddDivider(x, y, width);

            // --- test ---
            Button testConnect = MakeButton("Test connect sound", x, y, (width - 10) / 2, 34);
            testConnect.Click += delegate { _controller.PlayTest(PowerSource.AC); };
            Button testDisconnect = MakeButton("Test disconnect sound",
                                               x + (width - 10) / 2 + 10, y, (width - 10) / 2, 34);
            testDisconnect.Click += delegate { _controller.PlayTest(PowerSource.Battery); };
            y += 44;

            y = AddDivider(x, y, width);

            // --- system ---
            _startup = MakeCheck("Start automatically when I sign in to Windows", x, y, width);
            _startup.AccessibleDescription =
                "Adds a per-user startup entry. No administrator rights are needed.";
            _startup.CheckedChanged += OnStartupChanged;
            y += 28;

            _quietHours = MakeCheck("Stay silent while Windows notifications are off", x, y, width);
            _quietHours.AccessibleDescription =
                "Respects Do Not Disturb / Focus session settings.";
            _quietHours.CheckedChanged += OnAnyChanged;
            y += 36;

            // --- footer ---
            Button log = MakeButton("Open log folder", x, y, 140, 32);
            log.Click += delegate { OpenLogFolder(); };

            Button reset = MakeButton("Reset defaults", x + 150, y, 130, 32);
            reset.Click += delegate { ResetDefaults(); };

            Button close = MakeButton("Close", x + width - 100, y, 100, 32);
            close.Click += delegate { Close(); };
            AcceptButton = close;
            CancelButton = close;

            Theme.Apply(this);

            // Re-apply accent colours that Theme.Apply flattens.
            title.ForeColor = Theme.Foreground;
            subtitle.ForeColor = Theme.Subtle;
            statusHeading.ForeColor = Theme.Foreground;
            behaviourHeading.ForeColor = Theme.Foreground;
        }

        private Label MakeLabel(string text, int x, int y, int w, int h)
        {
            Label l = new Label();
            l.Text = text;
            l.Bounds = new Rectangle(x, y, w, h);
            l.AutoSize = false;
            Controls.Add(l);
            return l;
        }

        private CheckBox MakeCheck(string text, int x, int y, int w)
        {
            CheckBox c = new CheckBox();
            c.Text = text;
            c.Bounds = new Rectangle(x, y, w, 24);
            c.AutoSize = false;
            c.AccessibleName = text;
            Controls.Add(c);
            return c;
        }

        private Button MakeButton(string text, int x, int y, int w, int h)
        {
            Button b = new Button();
            b.Text = text;
            b.Bounds = new Rectangle(x, y, w, h);
            b.AccessibleName = text;
            b.UseVisualStyleBackColor = true;
            Controls.Add(b);
            return b;
        }

        private int AddDivider(int x, int y, int w)
        {
            Panel p = new Panel();
            p.Bounds = new Rectangle(x, y, w, 1);
            p.BackColor = Theme.Divider;
            Controls.Add(p);
            return y + 16;
        }

        // ------------------------------------------------------------- values
        private void LoadValues()
        {
            _loading = true;
            _enabled.Checked = _settings.Enabled;
            _onConnect.Checked = _settings.PlayOnConnect;
            _onDisconnect.Checked = _settings.PlayOnDisconnect;
            _quietHours.Checked = _settings.RespectQuietHours;
            _volume.Value = Math.Max(0, Math.Min(100, _settings.Volume));
            _delay.Value = Math.Max(0, Math.Min(30, _settings.DelayMs / 100));
            _startup.Checked = StartupRegistration.IsEnabled();
            _loading = false;
            UpdateDependentUi();
        }

        private void OnAnyChanged(object sender, EventArgs e)
        {
            if (_loading) return;

            _settings.Enabled = _enabled.Checked;
            _settings.PlayOnConnect = _onConnect.Checked;
            _settings.PlayOnDisconnect = _onDisconnect.Checked;
            _settings.RespectQuietHours = _quietHours.Checked;
            _settings.Volume = _volume.Value;
            _settings.DelayMs = _delay.Value * 100;
            _settings.Save();

            UpdateDependentUi();
            if (_onSettingsChanged != null) _onSettingsChanged();
        }

        private void OnStartupChanged(object sender, EventArgs e)
        {
            if (_loading) return;

            bool wanted = _startup.Checked;
            if (!StartupRegistration.SetEnabled(wanted))
            {
                MessageBox.Show(this,
                    "Windows would not let the startup entry be changed.\n\n"
                    + "You can set this yourself in Settings > Apps > Startup.",
                    AppInfo.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                _loading = true;
                _startup.Checked = StartupRegistration.IsEnabled();
                _loading = false;
            }

            _settings.StartWithWindows = _startup.Checked;
            _settings.Save();
            RefreshStatus();
        }

        private void UpdateDependentUi()
        {
            // The two direction switches are meaningless while the master is off.
            _onConnect.Enabled = _enabled.Checked;
            _onDisconnect.Enabled = _enabled.Checked;

            _volumeValue.Text = _volume.Value.ToString(CultureInfo.InvariantCulture) + "%";
            _volumeValue.ForeColor = Theme.Subtle;

            _delayValue.Text = _delay.Value == 0
                ? "None"
                : (_delay.Value / 10.0).ToString("0.0", CultureInfo.InvariantCulture) + " s";
            _delayValue.ForeColor = Theme.Subtle;
        }

        // ------------------------------------------------------------- status
        private void OnControllerStatusChanged(object sender, EventArgs e)
        {
            if (IsDisposed || !IsHandleCreated) return;
            if (InvokeRequired) { BeginInvoke((Action)RefreshStatus); return; }
            RefreshStatus();
        }

        private void RefreshStatus()
        {
            PowerSource source = _controller.CurrentSource;
            switch (source)
            {
                case PowerSource.AC:
                    _statusPower.Text = "Power  ·  plugged in";
                    _statusPower.ForeColor = Theme.Good;
                    break;
                case PowerSource.Battery:
                    _statusPower.Text = "Power  ·  running on battery";
                    _statusPower.ForeColor = Theme.Foreground;
                    break;
                default:
                    _statusPower.Text = "Power  ·  unknown (no battery reported)";
                    _statusPower.ForeColor = Theme.Subtle;
                    break;
            }

            if (_controller.PreciseDetection)
            {
                _statusDetection.Text = "Detection  ·  live system notifications";
                _statusDetection.ForeColor = Theme.Good;
            }
            else
            {
                _statusDetection.Text = "Detection  ·  fallback mode (still event-driven)";
                _statusDetection.ForeColor = Theme.Subtle;
            }

            if (!_controller.Audio.IsHealthy)
            {
                string problem = _controller.Audio.ConnectedError ?? _controller.Audio.DisconnectedError;
                _statusAudio.Text = "Audio  ·  " + problem;
                _statusAudio.ForeColor = Theme.Bad;
            }
            else if (!WavePlayer.HasOutputDevice)
            {
                _statusAudio.Text = "Audio  ·  no output device connected";
                _statusAudio.ForeColor = Theme.Bad;
            }
            else
            {
                _statusAudio.Text = "Audio  ·  both greetings loaded";
                _statusAudio.ForeColor = Theme.Good;
            }

            bool startsWithWindows = StartupRegistration.IsEnabled();
            _statusStartup.Text = startsWithWindows
                ? "Startup  ·  will start when you sign in"
                : "Startup  ·  will not start automatically";
            _statusStartup.ForeColor = startsWithWindows ? Theme.Good : Theme.Subtle;
        }

        // -------------------------------------------------------------- misc
        private void ResetDefaults()
        {
            DialogResult answer = MessageBox.Show(this,
                "Put every setting back to its default?",
                AppInfo.ProductName, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (answer != DialogResult.Yes) return;

            Settings defaults = new Settings();
            _settings.Enabled = defaults.Enabled;
            _settings.PlayOnConnect = defaults.PlayOnConnect;
            _settings.PlayOnDisconnect = defaults.PlayOnDisconnect;
            _settings.Volume = defaults.Volume;
            _settings.DelayMs = defaults.DelayMs;
            _settings.DebounceMs = defaults.DebounceMs;
            _settings.CooldownMs = defaults.CooldownMs;
            _settings.RespectQuietHours = defaults.RespectQuietHours;
            _settings.Save();

            LoadValues();
            if (_onSettingsChanged != null) _onSettingsChanged();
        }

        private void OpenLogFolder()
        {
            try
            {
                Process.Start("explorer.exe", "/select,\"" + AppInfo.LogPath + "\"");
            }
            catch (Exception ex)
            {
                Logger.Error("Could not open the log folder", ex);
                MessageBox.Show(this, "The log is at:\n\n" + AppInfo.LogPath,
                    AppInfo.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Escape) Close();
            base.OnKeyDown(e);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            _controller.StatusChanged -= OnControllerStatusChanged;
            base.OnFormClosed(e);
        }
    }
}
