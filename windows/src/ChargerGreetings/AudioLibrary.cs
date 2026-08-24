// AudioLibrary.cs -- loads and caches the two greeting clips.
//
// Files are read once and held in memory (~270 KB total). That is deliberate:
// the greeting must start the instant a power event lands, and disk I/O on a
// machine that has just woken from sleep is exactly when a read is slowest.
// It also means a file deleted after startup still plays until restart, rather
// than failing silently at the worst moment.

using System;
using System.IO;

namespace ChargerGreetings
{
    internal sealed class AudioLibrary
    {
        private WavFile _connected;
        private WavFile _disconnected;

        public string ConnectedError { get; private set; }
        public string DisconnectedError { get; private set; }

        public bool IsHealthy
        {
            get { return ConnectedError == null && DisconnectedError == null; }
        }

        public AudioLibrary() { Reload(); }

        public void Reload()
        {
            _connected = TryLoad(AppInfo.ConnectedSoundPath, "connected");
            _disconnected = TryLoad(AppInfo.DisconnectedSoundPath, "disconnected");
        }

        private WavFile TryLoad(string path, string which)
        {
            try
            {
                WavFile wav = WavFile.Load(path);
                if (which == "connected") ConnectedError = null; else DisconnectedError = null;
                Logger.Info("Loaded " + which + " clip: " + Path.GetFileName(path) + " ("
                            + wav.Duration.ToString("0.00") + "s, " + wav.SampleRate + " Hz, "
                            + wav.Channels + "ch)");
                return wav;
            }
            catch (Exception ex)
            {
                string message = ex is FileNotFoundException
                    ? "Sound file is missing: " + Path.GetFileName(path)
                    : ex.Message;
                if (which == "connected") ConnectedError = message; else DisconnectedError = message;
                Logger.Error("Could not load " + which + " clip", ex);
                return null;
            }
        }

        public WavFile Get(PowerSource source)
        {
            return source == PowerSource.AC ? _connected : _disconnected;
        }

        public string GetError(PowerSource source)
        {
            return source == PowerSource.AC ? ConnectedError : DisconnectedError;
        }
    }
}
