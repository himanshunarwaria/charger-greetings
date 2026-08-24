// StartupRegistration.cs -- run-at-sign-in via the per-user Run key.
//
// This replaces the previous approach (a .vbs in the Startup folder that
// launched powershell.exe with -ExecutionPolicy Bypass -WindowStyle Hidden).
// That pattern is a textbook malware signature and is heuristically flagged by
// Defender and most third-party AV. A plain HKCU Run value pointing at a real
// signed-or-unsigned .exe is the ordinary, expected way for a desktop app to
// start with Windows:
//
//   * per-user, so no administrator rights are ever needed,
//   * visible and toggleable in Task Manager > Startup apps,
//   * no script interpreter, no execution-policy bypass, no hidden window host.

using System;
using Microsoft.Win32;
using System.Reflection;

namespace ChargerGreetings
{
    internal static class StartupRegistration
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string ValueName = "ChargerGreetings";

        /// <summary>The launch command written to the Run key.</summary>
        private static string LaunchCommand
        {
            get
            {
                string exe = Assembly.GetExecutingAssembly().Location;
                // --startup tells the app it was launched by Windows, not by the
                // user, so it stays quiet and never shows a window.
                return "\"" + exe + "\" --startup";
            }
        }

        public static bool IsEnabled()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKey))
                {
                    if (key == null) return false;
                    object value = key.GetValue(ValueName);
                    return value != null && !string.IsNullOrEmpty(value.ToString());
                }
            }
            catch (Exception ex)
            {
                Logger.Error("Could not read startup registration", ex);
                return false;
            }
        }

        /// <summary>
        /// True when the Run entry exists but points somewhere else -- which
        /// happens after the app is moved or reinstalled to a new folder.
        /// </summary>
        public static bool IsStale()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKey))
                {
                    if (key == null) return false;
                    object value = key.GetValue(ValueName);
                    if (value == null) return false;
                    return !string.Equals(value.ToString(), LaunchCommand,
                                          StringComparison.OrdinalIgnoreCase);
                }
            }
            catch { return false; }
        }

        public static bool SetEnabled(bool enabled)
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKey))
                {
                    if (key == null) return false;
                    if (enabled)
                    {
                        key.SetValue(ValueName, LaunchCommand, RegistryValueKind.String);
                        Logger.Info("Startup registration enabled.");
                    }
                    else
                    {
                        if (key.GetValue(ValueName) != null) key.DeleteValue(ValueName, false);
                        Logger.Info("Startup registration removed.");
                    }
                }
                return true;
            }
            catch (Exception ex)
            {
                Logger.Error("Could not change startup registration", ex);
                return false;
            }
        }

        /// <summary>Rewrites the Run entry if the executable has moved.</summary>
        public static void RepairIfStale()
        {
            if (IsEnabled() && IsStale())
            {
                Logger.Info("Startup entry pointed at an old location; updating it.");
                SetEnabled(true);
            }
        }
    }
}
