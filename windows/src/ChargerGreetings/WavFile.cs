// WavFile.cs -- RIFF/WAVE reader for 8/16-bit PCM.
//
// The app ships its own assets, so this only has to understand the format we
// produce. It still validates rather than trusts: a truncated or swapped file
// must produce a clear message, not a crash or a burst of noise.

using System;
using System.IO;
using System.Text;

namespace ChargerGreetings
{
    internal sealed class WavFile
    {
        public ushort Channels;
        public uint SampleRate;
        public ushort BitsPerSample;
        public byte[] Pcm;

        public double Duration
        {
            get
            {
                int bytesPerFrame = Channels * (BitsPerSample / 8);
                if (bytesPerFrame == 0 || SampleRate == 0) return 0.0;
                return (double)Pcm.Length / bytesPerFrame / SampleRate;
            }
        }

        /// <summary>
        /// Reads a PCM WAV file. Throws <see cref="InvalidDataException"/> with a
        /// message suitable for showing to a user if the file is unusable.
        /// </summary>
        public static WavFile Load(string path)
        {
            if (!File.Exists(path))
                throw new FileNotFoundException("Audio file is missing: " + path, path);

            byte[] bytes = File.ReadAllBytes(path);
            if (bytes.Length < 44)
                throw new InvalidDataException("Audio file is too small to be a valid WAV.");

            if (Encoding.ASCII.GetString(bytes, 0, 4) != "RIFF" ||
                Encoding.ASCII.GetString(bytes, 8, 4) != "WAVE")
                throw new InvalidDataException("Audio file is not a WAV file.");

            WavFile wav = new WavFile();
            bool haveFormat = false;
            int pos = 12;

            // Walk the chunk list -- real WAV files often carry LIST/fact chunks
            // between "fmt " and "data", so fixed offsets are not safe.
            while (pos + 8 <= bytes.Length)
            {
                string id = Encoding.ASCII.GetString(bytes, pos, 4);
                uint size = BitConverter.ToUInt32(bytes, pos + 4);
                int body = pos + 8;

                if (body + (long)size > bytes.Length)
                    size = (uint)Math.Max(0, bytes.Length - body);   // tolerate truncation

                if (id == "fmt " && size >= 16)
                {
                    ushort formatTag = BitConverter.ToUInt16(bytes, body);
                    wav.Channels = BitConverter.ToUInt16(bytes, body + 2);
                    wav.SampleRate = BitConverter.ToUInt32(bytes, body + 4);
                    wav.BitsPerSample = BitConverter.ToUInt16(bytes, body + 14);

                    // 1 = PCM, 0xFFFE = WAVE_FORMAT_EXTENSIBLE (PCM in practice here).
                    if (formatTag != 1 && formatTag != 0xFFFE)
                        throw new InvalidDataException(
                            "Audio file is compressed; only uncompressed PCM WAV is supported.");
                    haveFormat = true;
                }
                else if (id == "data")
                {
                    wav.Pcm = new byte[size];
                    Array.Copy(bytes, body, wav.Pcm, 0, (int)size);
                }

                pos = body + (int)size;
                if ((size & 1) != 0) pos++;      // chunks are word-aligned
            }

            if (!haveFormat) throw new InvalidDataException("Audio file has no format header.");
            if (wav.Pcm == null || wav.Pcm.Length == 0)
                throw new InvalidDataException("Audio file contains no audio data.");
            if (wav.Channels < 1 || wav.Channels > 2)
                throw new InvalidDataException("Audio file must be mono or stereo.");
            if (wav.BitsPerSample != 16 && wav.BitsPerSample != 8)
                throw new InvalidDataException("Audio file must be 8-bit or 16-bit PCM.");
            if (wav.SampleRate < 8000 || wav.SampleRate > 192000)
                throw new InvalidDataException("Audio file has an unsupported sample rate.");

            return wav;
        }

        /// <summary>
        /// Returns a copy of the PCM scaled to the given volume (0-100).
        /// </summary>
        /// <remarks>
        /// Scaling the samples is deliberate. <c>waveOutSetVolume</c> is optional
        /// for a driver to implement and silently does nothing on some devices,
        /// whereas arithmetic on the buffer behaves identically everywhere.
        /// The curve is squared because loudness perception is roughly
        /// logarithmic -- a linear slider would feel like it does nothing until
        /// the last 20 %.
        /// </remarks>
        public byte[] GetScaledPcm(int volumePercent)
        {
            if (volumePercent >= 100) return Pcm;

            double v = volumePercent <= 0 ? 0.0 : volumePercent / 100.0;
            double gain = v * v;

            byte[] outBuf = new byte[Pcm.Length];

            if (BitsPerSample == 16)
            {
                for (int i = 0; i + 1 < Pcm.Length; i += 2)
                {
                    short s = (short)(Pcm[i] | (Pcm[i + 1] << 8));
                    int scaled = (int)Math.Round(s * gain);
                    if (scaled > 32767) scaled = 32767;
                    if (scaled < -32768) scaled = -32768;
                    outBuf[i] = (byte)(scaled & 0xFF);
                    outBuf[i + 1] = (byte)((scaled >> 8) & 0xFF);
                }
            }
            else
            {
                // 8-bit WAV samples are unsigned with 128 as the zero point.
                for (int i = 0; i < Pcm.Length; i++)
                {
                    int centred = Pcm[i] - 128;
                    int scaled = (int)Math.Round(centred * gain) + 128;
                    if (scaled > 255) scaled = 255;
                    if (scaled < 0) scaled = 0;
                    outBuf[i] = (byte)scaled;
                }
            }
            return outBuf;
        }
    }
}
