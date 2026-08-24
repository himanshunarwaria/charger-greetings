// AppInfo.cs -- product identity and well-known paths.

using System;
using System.IO;
using System.Reflection;

namespace ChargerGreetings
{
    internal static class AppInfo
    {
        public const string ProductName = "Charger Greetings";
        public const string ShortName = "ChargerGreetings";
        public const string Version = "1.0.0";
        public const string Publisher = "Charger Greetings";

        /// <summary>Guards against a second copy of the tray app running.</summary>
        public const string InstanceMutexName = @"Local\ChargerGreetings.SingleInstance";

        /// <summary>
        /// Set by the test harness so the suite can point at a staged folder.
        /// Null in the shipping app, which always uses its own location.
        /// </summary>
        internal static string InstallDirectoryOverride = null;

        /// <summary>Folder the executable actually runs from.</summary>
        public static string InstallDirectory
        {
            get
            {
                if (!string.IsNullOrEmpty(InstallDirectoryOverride))
                    return InstallDirectoryOverride;
                string path = Assembly.GetExecutingAssembly().Location;
                return Path.GetDirectoryName(path);
            }
        }

        /// <summary>Per-user data: settings and the troubleshooting log.</summary>
        public static string DataDirectory
        {
            get
            {
                string dir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    ShortName);
                Directory.CreateDirectory(dir);
                return dir;
            }
        }

        public static string SettingsPath
        {
            get { return Path.Combine(DataDirectory, "settings.ini"); }
        }

        public static string LogPath
        {
            get { return Path.Combine(DataDirectory, "charger-greetings.log"); }
        }

        /// <summary>
        /// Audio lives beside the executable so the app is fully self-contained
        /// and works with no network and no per-user setup.
        /// </summary>
        public static string AudioDirectory
        {
            get { return Path.Combine(InstallDirectory, "audio"); }
        }

        public static string ConnectedSoundPath
        {
            get { return Path.Combine(AudioDirectory, "power_connected.wav"); }
        }

        public static string DisconnectedSoundPath
        {
            get { return Path.Combine(AudioDirectory, "power_disconnected.wav"); }
        }

        public const string ConnectedPhrase = "मालिक, प्रणाम";
        public const string DisconnectedPhrase = "फिर मिलते हैं, मालिक";
    }
}
