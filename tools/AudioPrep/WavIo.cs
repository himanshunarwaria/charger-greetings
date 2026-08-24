// WavIo.cs
//
// Minimal RIFF/WAVE writer for 16-bit PCM.
//
// The shipped assets are plain 16-bit PCM WAV rather than MP3 on purpose:
//   * Zero decode latency. The greeting must start the instant the power
//     event arrives; MP3 costs a decoder spin-up on both platforms.
//   * No codec dependency. Android MediaPlayer, Windows waveOut and
//     System.Media.SoundPlayer all handle PCM WAV natively and identically.
//   * The clips are ~2 s, so the size cost is ~180 KB each -- irrelevant for
//     an APK, and it buys deterministic playback.

using System;
using System.IO;
using System.Text;

namespace AudioPrep
{
    internal static class WavIo
    {
        /// <summary>Writes interleaved float samples as 16-bit PCM WAV with dithering-free rounding.</summary>
        public static void WritePcm16(string path, float[] interleaved, int sampleRate, int channels)
        {
            int frames = interleaved.Length / channels;
            int dataBytes = frames * channels * 2;

            using (FileStream fs = new FileStream(path, FileMode.Create, FileAccess.Write))
            using (BinaryWriter w = new BinaryWriter(fs))
            {
                w.Write(Encoding.ASCII.GetBytes("RIFF"));
                w.Write(36 + dataBytes);
                w.Write(Encoding.ASCII.GetBytes("WAVE"));

                w.Write(Encoding.ASCII.GetBytes("fmt "));
                w.Write(16);                                  // PCM chunk size
                w.Write((short)1);                            // WAVE_FORMAT_PCM
                w.Write((short)channels);
                w.Write(sampleRate);
                w.Write(sampleRate * channels * 2);           // byte rate
                w.Write((short)(channels * 2));               // block align
                w.Write((short)16);                           // bits per sample

                w.Write(Encoding.ASCII.GetBytes("data"));
                w.Write(dataBytes);

                for (int i = 0; i < frames * channels; i++)
                {
                    double v = interleaved[i];
                    if (v > 1.0) v = 1.0;
                    if (v < -1.0) v = -1.0;
                    // Asymmetric scaling avoids wrapping at exactly -1.0.
                    int s = (int)Math.Round(v * 32767.0);
                    if (s > 32767) s = 32767;
                    if (s < -32768) s = -32768;
                    w.Write((short)s);
                }
            }
        }
    }
}
