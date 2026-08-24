// QuietHours.cs -- best-effort "is the machine meant to be silent right now?"
//
// Honest limitation: Windows exposes no supported public API for Focus Assist /
// Do Not Disturb state. (The WNF-based tricks that circulate are undocumented
// and have broken across builds more than once, so they are not used here.)
//
// What this does instead is read the documented per-user notifications master
// switch, which Windows 11 clears while Do Not Disturb is on. That covers the
// case users actually care about -- "I told Windows to be quiet, so be quiet" --
// and fails safe: if the value cannot be read, greetings still play.

using System;
using Microsoft.Win32;

namespace ChargerGreetings
{
    internal static class QuietHours
    {
        private const string NotificationsKey =
            @"Software\Microsoft\Windows\CurrentVersion\Notifications\Settings";
        private const string ToastsEnabledValue = "NOC_GLOBAL_SETTING_TOASTS_ENABLED";

        public static bool IsActive()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(NotificationsKey))
                {
                    if (key == null) return false;
                    object raw = key.GetValue(ToastsEnabledValue);
                    if (raw == null) return false;
                    // 0 means notifications are suppressed.
                    return Convert.ToInt32(raw) == 0;
                }
            }
            catch (Exception ex)
            {
                Logger.Error("Could not read notification state; assuming not quiet", ex);
                return false;
            }
        }
    }
}
