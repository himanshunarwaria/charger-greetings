// Settings.cs -- per-user preferences, stored as a plain INI file.
//
// INI rather than JSON/XML on purpose: no serializer dependency, identical
// behaviour on .NET Framework and .NET 8, and a user can open it in Notepad
// to see exactly what the app stores about them. Nothing here leaves the
// machine.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace ChargerGreetings
{
    internal sealed class Settings
    {
        // --- feature flags -------------------------------------------------
        public bool Enabled = true;
        public bool PlayOnConnect = true;
        public bool PlayOnDisconnect = true;

        /// <summary>0-100. Applied by scaling the PCM, not the system mixer.</summary>
        public int Volume = 80;

        /// <summary>Optional pause before the greeting, 0-5000 ms.</summary>
        public int DelayMs = 0;

        /// <summary>
        /// How long a new power state must hold before it counts. Absorbs the
        /// contact bounce of a barrel jack or a USB-C cable being reseated.
        /// </summary>
        public int DebounceMs = 700;

        /// <summary>Minimum gap between two greetings, whatever the cause.</summary>
        public int CooldownMs = 2500;

        /// <summary>Stay silent when Windows is in a focus/quiet-hours session.</summary>
        public bool RespectQuietHours = true;

        public bool StartWithWindows = true;

        // --- persistence ---------------------------------------------------
        public static Settings Load()
        {
            Settings s = new Settings();
            try
            {
                if (!File.Exists(AppInfo.SettingsPath)) return s;

                Dictionary<string, string> map = new Dictionary<string, string>(
                    StringComparer.OrdinalIgnoreCase);

                foreach (string raw in File.ReadAllLines(AppInfo.SettingsPath))
                {
                    string line = raw.Trim();
                    if (line.Length == 0 || line[0] == '#' || line[0] == ';' || line[0] == '[')
                        continue;
                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;
                    map[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim();
                }

                s.Enabled = GetBool(map, "Enabled", s.Enabled);
                s.PlayOnConnect = GetBool(map, "PlayOnConnect", s.PlayOnConnect);
                s.PlayOnDisconnect = GetBool(map, "PlayOnDisconnect", s.PlayOnDisconnect);
                s.RespectQuietHours = GetBool(map, "RespectQuietHours", s.RespectQuietHours);
                s.StartWithWindows = GetBool(map, "StartWithWindows", s.StartWithWindows);

                s.Volume = Clamp(GetInt(map, "Volume", s.Volume), 0, 100);
                s.DelayMs = Clamp(GetInt(map, "DelayMs", s.DelayMs), 0, 5000);
                s.DebounceMs = Clamp(GetInt(map, "DebounceMs", s.DebounceMs), 100, 5000);
                s.CooldownMs = Clamp(GetInt(map, "CooldownMs", s.CooldownMs), 0, 60000);
            }
            catch (Exception ex)
            {
                // A damaged settings file must not stop the app; fall back to
                // defaults and say so in the log.
                Logger.Error("Could not read settings, using defaults", ex);
                return new Settings();
            }
            return s;
        }

        public void Save()
        {
            try
            {
                StringBuilder sb = new StringBuilder();
                sb.AppendLine("# " + AppInfo.ProductName + " settings");
                sb.AppendLine("# Stored locally. Nothing in this file is ever transmitted.");
                sb.AppendLine();
                sb.AppendLine("[General]");
                sb.AppendLine("Enabled=" + Bool(Enabled));
                sb.AppendLine("PlayOnConnect=" + Bool(PlayOnConnect));
                sb.AppendLine("PlayOnDisconnect=" + Bool(PlayOnDisconnect));
                sb.AppendLine("Volume=" + Volume.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("DelayMs=" + DelayMs.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("RespectQuietHours=" + Bool(RespectQuietHours));
                sb.AppendLine("StartWithWindows=" + Bool(StartWithWindows));
                sb.AppendLine();
                sb.AppendLine("[Advanced]");
                sb.AppendLine("DebounceMs=" + DebounceMs.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("CooldownMs=" + CooldownMs.ToString(CultureInfo.InvariantCulture));

                // Write to a temp file then swap, so a crash mid-write cannot
                // leave a half-written settings file behind.
                string tmp = AppInfo.SettingsPath + ".tmp";
                File.WriteAllText(tmp, sb.ToString(), new UTF8Encoding(false));
                if (File.Exists(AppInfo.SettingsPath)) File.Delete(AppInfo.SettingsPath);
                File.Move(tmp, AppInfo.SettingsPath);
            }
            catch (Exception ex)
            {
                Logger.Error("Could not save settings", ex);
            }
        }

        public Settings Clone()
        {
            return (Settings)MemberwiseClone();
        }

        // --- helpers -------------------------------------------------------
        private static string Bool(bool v) { return v ? "true" : "false"; }

        private static bool GetBool(Dictionary<string, string> map, string key, bool fallback)
        {
            string v;
            if (!map.TryGetValue(key, out v)) return fallback;
            v = v.Trim().ToLowerInvariant();
            if (v == "true" || v == "1" || v == "yes" || v == "on") return true;
            if (v == "false" || v == "0" || v == "no" || v == "off") return false;
            return fallback;
        }

        private static int GetInt(Dictionary<string, string> map, string key, int fallback)
        {
            string v;
            int parsed;
            if (map.TryGetValue(key, out v) &&
                int.TryParse(v.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out parsed))
                return parsed;
            return fallback;
        }

        private static int Clamp(int v, int lo, int hi)
        {
            if (v < lo) return lo;
            if (v > hi) return hi;
            return v;
        }
    }
}
