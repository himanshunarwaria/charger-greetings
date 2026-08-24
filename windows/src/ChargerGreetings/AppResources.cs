// AppResources.cs -- icons embedded in the executable.
//
// The icons are compiled into the .exe rather than shipped as loose files so
// the tray icon can never go missing, and so a user moving the folder around
// cannot end up with a blank notification-area entry.

using System;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

namespace ChargerGreetings
{
    internal static class AppResources
    {
        private static Icon _active;
        private static Icon _paused;
        private static Icon _window;

        /// <summary>Colour icon: greetings are armed.</summary>
        public static Icon ActiveTrayIcon
        {
            get
            {
                if (_active == null) _active = LoadTray("ChargerGreetings.app.ico");
                return _active;
            }
        }

        /// <summary>Grey icon: greetings are switched off.</summary>
        public static Icon PausedTrayIcon
        {
            get
            {
                if (_paused == null) _paused = LoadTray("ChargerGreetings.app-off.ico");
                return _paused;
            }
        }

        /// <summary>Full-size icon for window title bars and Alt-Tab.</summary>
        public static Icon WindowIcon
        {
            get
            {
                if (_window == null) _window = Load("ChargerGreetings.app.ico", 32);
                return _window;
            }
        }

        private static Icon LoadTray(string name)
        {
            // Ask for the size Windows actually wants in the notification area;
            // it differs between 100 % and 200 % display scaling.
            Size wanted = SystemInformation.SmallIconSize;
            return Load(name, wanted.Width);
        }

        private static Icon Load(string resourceName, int size)
        {
            try
            {
                Assembly asm = Assembly.GetExecutingAssembly();
                using (Stream stream = asm.GetManifestResourceStream(resourceName))
                {
                    if (stream != null) return new Icon(stream, size, size);
                }
            }
            catch (Exception ex)
            {
                Logger.Error("Could not load icon resource " + resourceName, ex);
            }
            // A missing icon must not stop the app from working.
            return SystemIcons.Application;
        }
    }
}
