// Program.cs -- entry point and process lifetime.
//
// Command line:
//   (none)        launched by the user  -> shows the settings window once
//   --startup     launched by Windows   -> stays silent in the tray
//   --settings    open settings on an already-running instance
//   --uninstall   remove the startup entry (used by the uninstaller)

using System;
using System.Threading;
using System.Windows.Forms;

namespace ChargerGreetings
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            bool launchedByWindows = HasSwitch(args, "--startup");

            if (HasSwitch(args, "--uninstall"))
            {
                StartupRegistration.SetEnabled(false);
                return 0;
            }

            // One tray icon, always. A second launch just surfaces the first.
            bool isFirstInstance;
            using (Mutex mutex = new Mutex(true, AppInfo.InstanceMutexName, out isFirstInstance))
            {
                if (!isFirstInstance)
                {
                    Logger.Info("Another instance is already running; exiting.");
                    if (!launchedByWindows)
                        MessageBox.Show(
                            AppInfo.ProductName + " is already running.\n\n"
                            + "Look for the plug icon in the notification area "
                            + "(you may need to click the ^ arrow to see it).",
                            AppInfo.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                // A crash in a background utility should leave a trace and a
                // readable message, never a silent disappearance.
                Application.ThreadException += delegate (object s, ThreadExceptionEventArgs e)
                {
                    Logger.Error("Unhandled UI exception", e.Exception);
                    ShowFatal(e.Exception);
                };
                AppDomain.CurrentDomain.UnhandledException += delegate (object s, UnhandledExceptionEventArgs e)
                {
                    Logger.Error("Unhandled exception", e.ExceptionObject as Exception);
                };

                return Run(args, launchedByWindows);
            }
        }

        private static int Run(string[] args, bool launchedByWindows)
        {
            Logger.Info("---- " + AppInfo.ProductName + " " + AppInfo.Version
                        + " starting (" + (launchedByWindows ? "sign-in" : "user") + ") ----");

            Settings settings = Settings.Load();

            // If the app was moved or reinstalled, the Run key may point at the
            // old path. Fix it silently rather than quietly stopping working.
            StartupRegistration.RepairIfStale();

            AudioLibrary audio = new AudioLibrary();
            WavePlayer player = new WavePlayer();
            PowerWatcher watcher = new PowerWatcher();
            GreetingController controller =
                new GreetingController(settings, watcher, player, audio);
            TrayApp tray = new TrayApp(settings, controller);

            try
            {
                watcher.Start();

                // Record the current power source WITHOUT playing anything. This
                // is what stops a greeting firing merely because you signed in
                // with the charger already connected.
                controller.Initialize();

                if (!audio.IsHealthy)
                {
                    string problem = audio.ConnectedError ?? audio.DisconnectedError;
                    Logger.Warn("Starting with an audio problem: " + problem);
                    tray.ShowBalloon("Sound files could not be loaded", problem,
                                     ToolTipIcon.Warning);
                }

                if (launchedByWindows)
                {
                    Logger.Info("Started by Windows; staying in the tray.");
                }
                else if (IsFirstRun(settings))
                {
                    // First manual launch: show the window so the user learns
                    // where the app lives and can test both sounds immediately.
                    tray.ShowSettings();
                    settings.Save();
                }
                else
                {
                    tray.ShowSettings();
                }

                Application.Run();
                return 0;
            }
            finally
            {
                Logger.Info("Shutting down.");
                tray.Dispose();
                controller.Dispose();
                watcher.Dispose();
                player.Dispose();
            }
        }

        /// <summary>True when no settings file has been written yet.</summary>
        private static bool IsFirstRun(Settings settings)
        {
            return !System.IO.File.Exists(AppInfo.SettingsPath);
        }

        private static bool HasSwitch(string[] args, string name)
        {
            if (args == null) return false;
            foreach (string a in args)
                if (string.Equals(a, name, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        private static void ShowFatal(Exception ex)
        {
            try
            {
                MessageBox.Show(
                    "Something went wrong and " + AppInfo.ProductName
                    + " had to stop what it was doing.\n\n"
                    + ex.Message + "\n\nDetails were written to:\n" + AppInfo.LogPath,
                    AppInfo.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        }
    }
}
