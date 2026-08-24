// WavePlayer.cs -- waveOut playback with per-playback volume.
//
// Why not System.Media.SoundPlayer: it offers no volume control at all.
// Why not WPF MediaPlayer: it drags in PresentationCore and needs a WPF
// dispatcher, which is fragile inside a WinForms message loop.
// waveOut is the smallest supported API that does exactly what is needed and
// re-resolves the default output device on every open -- so if the user swaps
// to headphones between greetings, the next greeting follows them.

using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace ChargerGreetings
{
    internal sealed class WavePlayer : IGreetingAudioOutput
    {
        // ------------------------------------------------------------ interop
        [StructLayout(LayoutKind.Sequential)]
        private struct WAVEHDR
        {
            public IntPtr lpData;
            public uint dwBufferLength;
            public uint dwBytesRecorded;
            public IntPtr dwUser;
            public uint dwFlags;
            public uint dwLoops;
            public IntPtr lpNext;
            public IntPtr reserved;
        }

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private class WAVEFORMATEX
        {
            public ushort wFormatTag;
            public ushort nChannels;
            public uint nSamplesPerSec;
            public uint nAvgBytesPerSec;
            public ushort nBlockAlign;
            public ushort wBitsPerSample;
            public ushort cbSize;
        }

        [DllImport("winmm.dll")] private static extern int waveOutGetNumDevs();

        [DllImport("winmm.dll")]
        private static extern int waveOutOpen(out IntPtr hWaveOut, int deviceId,
            WAVEFORMATEX format, IntPtr callback, IntPtr instance, int flags);

        [DllImport("winmm.dll")]
        private static extern int waveOutPrepareHeader(IntPtr hWaveOut, IntPtr header, int size);

        [DllImport("winmm.dll")]
        private static extern int waveOutWrite(IntPtr hWaveOut, IntPtr header, int size);

        [DllImport("winmm.dll")]
        private static extern int waveOutUnprepareHeader(IntPtr hWaveOut, IntPtr header, int size);

        [DllImport("winmm.dll")] private static extern int waveOutReset(IntPtr hWaveOut);
        [DllImport("winmm.dll")] private static extern int waveOutClose(IntPtr hWaveOut);

        private const int WAVE_MAPPER = -1;          // "whatever the default device is"
        private const int CALLBACK_EVENT = 0x00050000;
        private const uint WHDR_DONE = 0x00000001;
        private const int MMSYSERR_NOERROR = 0;
        private const int MMSYSERR_ALLOCATED = 4;
        private const int MMSYSERR_NODRIVER = 6;
        private const int WAVERR_BADFORMAT = 32;

        // ------------------------------------------------------------- state
        private readonly object _gate = new object();
        private Thread _worker;
        private IntPtr _handle = IntPtr.Zero;
        private volatile bool _stopRequested;

        /// <summary>True when an output device exists at all.</summary>
        public static bool HasOutputDevice
        {
            get { return waveOutGetNumDevs() > 0; }
        }

        /// <summary>
        /// Starts playback and returns immediately. Any greeting already playing
        /// is cut off first, so overlapping power events can never stack voices.
        /// </summary>
        /// <param name="onFinished">
        /// Invoked on the worker thread with null on success, or a
        /// user-presentable message on failure.
        /// </param>
        public void Play(WavFile wav, int volumePercent, Action<string> onFinished)
        {
            Stop();

            byte[] pcm = wav.GetScaledPcm(volumePercent);

            lock (_gate)
            {
                _stopRequested = false;
                _worker = new Thread(delegate () { Run(wav, pcm, onFinished); });
                _worker.IsBackground = true;
                _worker.Name = "ChargerGreetings.Audio";
                _worker.Start();
            }
        }

        private void Run(WavFile wav, byte[] pcm, Action<string> onFinished)
        {
            IntPtr buffer = IntPtr.Zero;
            IntPtr headerPtr = IntPtr.Zero;
            IntPtr device = IntPtr.Zero;
            EventWaitHandle done = null;
            bool prepared = false;
            string error = null;

            try
            {
                if (waveOutGetNumDevs() == 0)
                {
                    // Every audio endpoint is unplugged or disabled. Not an
                    // error worth a popup -- just nothing to play through.
                    error = "No audio output device is available right now.";
                    Logger.Warn("Playback skipped: no output device.");
                    return;
                }

                WAVEFORMATEX format = new WAVEFORMATEX();
                format.wFormatTag = 1;                                  // PCM
                format.nChannels = wav.Channels;
                format.nSamplesPerSec = wav.SampleRate;
                format.wBitsPerSample = wav.BitsPerSample;
                format.nBlockAlign = (ushort)(wav.Channels * (wav.BitsPerSample / 8));
                format.nAvgBytesPerSec = wav.SampleRate * format.nBlockAlign;
                format.cbSize = 0;

                done = new EventWaitHandle(false, EventResetMode.AutoReset);

                int rc = waveOutOpen(out device, WAVE_MAPPER, format,
                                     done.SafeWaitHandle.DangerousGetHandle(),
                                     IntPtr.Zero, CALLBACK_EVENT);
                if (rc != MMSYSERR_NOERROR)
                {
                    device = IntPtr.Zero;
                    error = DescribeError(rc);
                    Logger.Warn("waveOutOpen failed (" + rc + "): " + error);
                    return;
                }

                lock (_gate) { _handle = device; }

                buffer = Marshal.AllocHGlobal(pcm.Length);
                Marshal.Copy(pcm, 0, buffer, pcm.Length);

                WAVEHDR header = new WAVEHDR();
                header.lpData = buffer;
                header.dwBufferLength = (uint)pcm.Length;

                headerPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WAVEHDR)));
                Marshal.StructureToPtr(header, headerPtr, false);

                rc = waveOutPrepareHeader(device, headerPtr, Marshal.SizeOf(typeof(WAVEHDR)));
                if (rc != MMSYSERR_NOERROR)
                {
                    error = DescribeError(rc);
                    Logger.Warn("waveOutPrepareHeader failed (" + rc + ")");
                    return;
                }
                prepared = true;

                rc = waveOutWrite(device, headerPtr, Marshal.SizeOf(typeof(WAVEHDR)));
                if (rc != MMSYSERR_NOERROR)
                {
                    error = DescribeError(rc);
                    Logger.Warn("waveOutWrite failed (" + rc + ")");
                    return;
                }

                // Wait for the driver to report the buffer complete. The deadline
                // guarantees we release the device even if a driver never signals.
                int budgetMs = (int)(wav.Duration * 1000) + 5000;
                int waited = 0;
                while (!_stopRequested && waited < budgetMs)
                {
                    done.WaitOne(100);
                    waited += 100;
                    WAVEHDR current = (WAVEHDR)Marshal.PtrToStructure(headerPtr, typeof(WAVEHDR));
                    if ((current.dwFlags & WHDR_DONE) != 0) break;
                }
            }
            catch (Exception ex)
            {
                error = "Could not play the greeting: " + ex.Message;
                Logger.Error("Playback failed", ex);
            }
            finally
            {
                try
                {
                    if (device != IntPtr.Zero)
                    {
                        waveOutReset(device);
                        if (prepared)
                            waveOutUnprepareHeader(device, headerPtr, Marshal.SizeOf(typeof(WAVEHDR)));
                        waveOutClose(device);
                    }
                }
                catch (Exception ex) { Logger.Error("Audio teardown failed", ex); }

                if (headerPtr != IntPtr.Zero) Marshal.FreeHGlobal(headerPtr);
                if (buffer != IntPtr.Zero) Marshal.FreeHGlobal(buffer);
                if (done != null) done.Close();

                lock (_gate) { if (_handle == device) _handle = IntPtr.Zero; }

                if (onFinished != null)
                {
                    try { onFinished(error); } catch { }
                }
            }
        }

        /// <summary>Stops any greeting in progress and waits briefly for cleanup.</summary>
        public void Stop()
        {
            Thread worker;
            IntPtr device;
            lock (_gate)
            {
                worker = _worker;
                device = _handle;
                _stopRequested = true;
            }

            if (device != IntPtr.Zero)
            {
                try { waveOutReset(device); } catch { }
            }
            if (worker != null && worker.IsAlive)
            {
                // Bounded join: never let a stuck driver hang the UI thread.
                worker.Join(1500);
            }
        }

        private static string DescribeError(int code)
        {
            switch (code)
            {
                case MMSYSERR_ALLOCATED:
                    return "The audio device is in use by another program.";
                case MMSYSERR_NODRIVER:
                    return "No usable audio driver is installed.";
                case WAVERR_BADFORMAT:
                    return "The audio device does not accept this file's format.";
                default:
                    return "The audio device returned error " + code + ".";
            }
        }

        public void Dispose() { Stop(); }
    }
}
