// Logger.cs -- tiny size-capped local log.
//
// The log exists purely so a user can answer "why didn't it play?" without
// attaching a debugger. It is local-only, contains no personal data beyond
// power-state transitions, and self-truncates so it can never grow unbounded.

using System;
using System.Globalization;
using System.IO;
using System.Text;

namespace ChargerGreetings
{
    internal static class Logger
    {
        private const long MaxBytes = 128 * 1024;
        private static readonly object Gate = new object();

        public static void Info(string message) { Write("INFO ", message); }
        public static void Warn(string message) { Write("WARN ", message); }

        public static void Error(string message, Exception ex)
        {
            Write("ERROR", ex == null ? message : message + " :: " + ex.Message);
        }

        private static void Write(string level, string message)
        {
            try
            {
                lock (Gate)
                {
                    string path = AppInfo.LogPath;

                    // Rotate by truncation: a single 128 KB file is plenty of
                    // history and needs no cleanup logic elsewhere.
                    if (File.Exists(path) && new FileInfo(path).Length > MaxBytes)
                    {
                        string[] lines = File.ReadAllLines(path);
                        int keep = lines.Length / 2;
                        File.WriteAllLines(path, Slice(lines, lines.Length - keep, keep));
                    }

                    string stamp = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff",
                                                         CultureInfo.InvariantCulture);
                    File.AppendAllText(path, stamp + "  " + level + "  " + message
                                             + Environment.NewLine, Encoding.UTF8);
                }
            }
            catch
            {
                // Logging must never be able to take the app down.
            }
        }

        private static string[] Slice(string[] source, int start, int count)
        {
            string[] result = new string[count];
            Array.Copy(source, start, result, 0, count);
            return result;
        }
    }
}
