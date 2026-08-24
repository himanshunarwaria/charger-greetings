// TrayApp.cs -- the notification-area presence and menu.
//
// The app has no main window by design: it is a background utility, and a
// taskbar button for something you interact with twice a year is noise. The
// tray icon itself carries state (colour = armed, grey = paused) so the answer
// to "is it on?" is available at a glance without opening anything.

using System;
using System.Diagnostics;
using System.Windows.Forms;

namespace ChargerGreetings
{
    internal sealed class TrayApp : IDisposable
    {
        private readonly Settings _settings;
        private readonly GreetingController _controller;

        private NotifyIcon _tray;
        private ContextMenuStrip _menu;
        private ToolStripMenuItem _statusItem;
        private ToolStripMenuItem _enabledItem;
        private ToolStripMenuItem _startupItem;
        private SettingsForm _settingsForm;

        public TrayApp(Settings settings, GreetingController controller)
        {
            _settings = settings;
            _controller = controller;

            BuildMenu();

            _tray = new NotifyIcon();
            _tray.Icon = AppResources.ActiveTrayIcon;
            _tray.Visible = true;
            _tray.ContextMenuStrip = _menu;
            _tray.DoubleClick += delegate { ShowSettings(); };

            _controller.StatusChanged += delegate { RefreshTray(); };
            _controller.PlaybackProblem += OnPlaybackProblem;

            RefreshTray();
        }

        private void BuildMenu()
        {
            _menu = new ContextMenuStrip();
            _menu.Opening += delegate { RefreshMenu(); };
            // Renderer follows the system theme automatically in recent Windows.
            _menu.ShowImageMargin = false;

            _statusItem = new ToolStripMenuItem("Checking power…");
            _statusItem.Enabled = false;
            _menu.Items.Add(_statusItem);

            _menu.Items.Add(new ToolStripSeparator());

            _enabledItem = new ToolStripMenuItem("Play greetings");
            _enabledItem.CheckOnClick = true;
            _enabledItem.Click += OnToggleEnabled;
            _menu.Items.Add(_enabledItem);

            _menu.Items.Add(new ToolStripSeparator());

            ToolStripMenuItem testConnect = new ToolStripMenuItem(
                "Test  ·  " + AppInfo.ConnectedPhrase);
            testConnect.Click += delegate { _controller.PlayTest(PowerSource.AC); };
            _menu.Items.Add(testConnect);

            ToolStripMenuItem testDisconnect = new ToolStripMenuItem(
                "Test  ·  " + AppInfo.DisconnectedPhrase);
            testDisconnect.Click += delegate { _controller.PlayTest(PowerSource.Battery); };
            _menu.Items.Add(testDisconnect);

            _menu.Items.Add(new ToolStripSeparator());

            _startupItem = new ToolStripMenuItem("Start with Windows");
            _startupItem.CheckOnClick = true;
            _startupItem.Click += OnToggleStartup;
            _menu.Items.Add(_startupItem);

            ToolStripMenuItem settings = new ToolStripMenuItem("Settings…");
            settings.Click += delegate { ShowSettings(); };
            _menu.Items.Add(settings);

            _menu.Items.Add(new ToolStripSeparator());

            ToolStripMenuItem exit = new ToolStripMenuItem("Exit");
            exit.Click += delegate { ExitApp(); };
            _menu.Items.Add(exit);
        }

        private void RefreshMenu()
        {
            _enabledItem.Checked = _settings.Enabled;
            _startupItem.Checked = StartupRegistration.IsEnabled();

            switch (_controller.CurrentSource)
            {
                case PowerSource.AC:
                    _statusItem.Text = "Plugged in";
                    break;
                case PowerSource.Battery:
                    _statusItem.Text = "On battery";
                    break;
                default:
                    _statusItem.Text = "Power state unknown";
                    break;
            }

            if (!_controller.Audio.IsHealthy)
                _statusItem.Text += "  ·  sound file problem";
            else if (!_settings.Enabled)
                _statusItem.Text += "  ·  paused";
        }

        private void RefreshTray()
        {
            if (_tray == null) return;

            _tray.Icon = _settings.Enabled
                ? AppResources.ActiveTrayIcon
                : AppResources.PausedTrayIcon;

            string state;
            if (!_settings.Enabled) state = "Paused";
            else if (_controller.CurrentSource == PowerSource.AC) state = "Plugged in";
            else if (_controller.CurrentSource == PowerSource.Battery) state = "On battery";
            else state = "Power state unknown";

            if (!_controller.Audio.IsHealthy) state += " — sound file problem";

            // NotifyIcon.Text is capped at 63 characters by the shell.
            string text = AppInfo.ProductName + " — " + state;
            _tray.Text = text.Length > 63 ? text.Substring(0, 60) + "…" : text;
        }

        private void OnToggleEnabled(object sender, EventArgs e)
        {
            _settings.Enabled = _enabledItem.Checked;
            _settings.Save();
            _controller.UpdateSettings(_settings);
            RefreshTray();
            Logger.Info("Greetings " + (_settings.Enabled ? "enabled" : "paused") + " from tray.");
        }

        private void OnToggleStartup(object sender, EventArgs e)
        {
            bool wanted = _startupItem.Checked;
            if (!StartupRegistration.SetEnabled(wanted))
            {
                _startupItem.Checked = StartupRegistration.IsEnabled();
                ShowBalloon("Could not change the startup setting",
                            "You can set this in Settings > Apps > Startup.",
                            ToolTipIcon.Warning);
                return;
            }
            _settings.StartWithWindows = wanted;
            _settings.Save();
        }

        private void OnPlaybackProblem(object sender, string message)
        {
            // A balloon, not a modal dialog: a failed greeting is worth telling
            // the user about once, but it must never steal focus from whatever
            // they were doing when they plugged the cable in.
            ShowBalloon("Greeting could not play", message, ToolTipIcon.Warning);
        }

        public void ShowBalloon(string title, string message, ToolTipIcon icon)
        {
            try
            {
                if (_tray == null) return;
                _tray.BalloonTipTitle = title;
                _tray.BalloonTipText = message;
                _tray.BalloonTipIcon = icon;
                _tray.ShowBalloonTip(5000);
            }
            catch (Exception ex) { Logger.Error("Could not show balloon", ex); }
        }

        public void ShowSettings()
        {
            if (_settingsForm != null && !_settingsForm.IsDisposed)
            {
                if (_settingsForm.WindowState == FormWindowState.Minimized)
                    _settingsForm.WindowState = FormWindowState.Normal;
                _settingsForm.Activate();
                return;
            }

            _settingsForm = new SettingsForm(_settings, _controller, delegate
            {
                _controller.UpdateSettings(_settings);
                RefreshTray();
            });
            _settingsForm.FormClosed += delegate { _settingsForm = null; };
            _settingsForm.Show();
            _settingsForm.Activate();
        }

        private void ExitApp()
        {
            Logger.Info("Exit requested from tray.");
            Application.Exit();
        }

        public void Dispose()
        {
            if (_tray != null)
            {
                _tray.Visible = false;      // otherwise a ghost icon lingers
                _tray.Dispose();
                _tray = null;
            }
            if (_menu != null) { _menu.Dispose(); _menu = null; }
        }
    }
}
