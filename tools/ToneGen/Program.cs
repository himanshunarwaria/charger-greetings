// ToneGen -- generates the app's built-in sound collection.
//
// Every clip here is synthesised from scratch: pure additive synthesis with an
// exponential decay envelope. That makes them unambiguously original and
// royalty-free, which matters because the app bundles them and ships them.
//
// Design rules shared by all clips:
//   * 44.1 kHz, 16-bit, mono -- identical to the two spoken greetings, so the
//     playback path never has to switch formats.
//   * Exponential decay, never a hard cut, and an explicit short fade at both
//     ends: a waveform that stops mid-cycle produces an audible click.
//   * Peak normalised to -3 dBFS. Short tonal clips measure very differently
//     from speech under BS.1770, so matching peaks (not LUFS) is the right
//     call here -- it keeps them from sounding louder than the voice clips.
//   * Kept under ~1 second. These are event confirmations, not music.

using System;
using System.IO;

namespace ToneGen
{
    public static class Program
    {
        const int Rate = 44100;

        public static int Generate(string outDir)
        {
            Directory.CreateDirectory(outDir);

            Write(outDir, "chime_up.wav", RisingChime());
            Write(outDir, "chime_down.wav", FallingChime());
            Write(outDir, "ding.wav", Ding());
            Write(outDir, "soft_beep.wav", SoftBeep());
            Write(outDir, "marimba.wav", Marimba());
            Write(outDir, "pebble.wav", Pebble());
            Write(outDir, "alert_ping.wav", AlertPing());

            Console.WriteLine("Built-in sounds written to " + outDir);
            return 0;
        }

        // Two ascending notes: reads as "connected".
        static float[] RisingChime()
        {
            var buf = new float[(int)(Rate * 0.85)];
            AddNote(buf, 0.00, 0.45, 659.25, 0.55);   // E5
            AddNote(buf, 0.16, 0.60, 987.77, 0.55);   // B5
            return Finish(buf);
        }

        // Two descending notes: reads as "disconnected".
        static float[] FallingChime()
        {
            var buf = new float[(int)(Rate * 0.85)];
            AddNote(buf, 0.00, 0.45, 987.77, 0.55);   // B5
            AddNote(buf, 0.16, 0.60, 659.25, 0.55);   // E5
            return Finish(buf);
        }

        // Single bright bell with an inharmonic partial, like a doorbell.
        static float[] Ding()
        {
            var buf = new float[(int)(Rate * 0.75)];
            AddPartial(buf, 0.0, 0.70, 1046.50, 0.60, 6.0);
            AddPartial(buf, 0.0, 0.50, 2637.02, 0.18, 9.0);
            return Finish(buf);
        }

        // Quiet, unobtrusive confirmation blip.
        static float[] SoftBeep()
        {
            var buf = new float[(int)(Rate * 0.28)];
            AddPartial(buf, 0.0, 0.26, 660.00, 0.60, 12.0);
            return Finish(buf);
        }

        // Wooden, percussive: strong odd harmonic and a fast decay.
        static float[] Marimba()
        {
            var buf = new float[(int)(Rate * 0.55)];
            AddPartial(buf, 0.0, 0.50, 523.25, 0.60, 9.0);
            AddPartial(buf, 0.0, 0.30, 2093.00, 0.22, 16.0);
            return Finish(buf);
        }

        // Very short, dry, low click-tone. Good when a chime feels too much.
        static float[] Pebble()
        {
            var buf = new float[(int)(Rate * 0.20)];
            AddPartial(buf, 0.0, 0.18, 392.00, 0.60, 22.0);
            AddPartial(buf, 0.0, 0.10, 784.00, 0.20, 30.0);
            return Finish(buf);
        }

        // Double tap, higher pitched: designed to cut through as an alert.
        static float[] AlertPing()
        {
            var buf = new float[(int)(Rate * 0.60)];
            AddPartial(buf, 0.00, 0.22, 1567.98, 0.55, 14.0);
            AddPartial(buf, 0.18, 0.35, 1567.98, 0.55, 14.0);
            return Finish(buf);
        }

        // A note is just a partial with a musical default decay.
        static void AddNote(float[] buf, double startSec, double lenSec, double hz, double amp)
        {
            AddPartial(buf, startSec, lenSec, hz, amp, 5.5);
        }

        // Adds one exponentially-decaying sine into the buffer.
        static void AddPartial(float[] buf, double startSec, double lenSec,
                               double hz, double amp, double decay)
        {
            int start = (int)(startSec * Rate);
            int len = (int)(lenSec * Rate);
            for (int i = 0; i < len; i++)
            {
                int idx = start + i;
                if (idx < 0 || idx >= buf.Length) continue;
                double t = (double)i / Rate;
                // A short raised-cosine attack stops the note starting on a
                // discontinuity, which would click on every play.
                double attack = t < 0.004 ? 0.5 - 0.5 * Math.Cos(Math.PI * t / 0.004) : 1.0;
                double env = Math.Exp(-decay * t) * attack;
                buf[idx] += (float)(Math.Sin(2.0 * Math.PI * hz * t) * amp * env);
            }
        }

        // Normalises to -3 dBFS and applies edge fades.
        static float[] Finish(float[] buf)
        {
            double peak = 0.0;
            for (int i = 0; i < buf.Length; i++) peak = Math.Max(peak, Math.Abs(buf[i]));
            if (peak > 0.0)
            {
                double target = Math.Pow(10.0, -3.0 / 20.0);
                double gain = target / peak;
                for (int i = 0; i < buf.Length; i++) buf[i] = (float)(buf[i] * gain);
            }

            int fade = (int)(Rate * 0.006);
            for (int i = 0; i < fade && i < buf.Length; i++)
            {
                double f = (double)i / fade;
                buf[i] *= (float)f;
                buf[buf.Length - 1 - i] *= (float)f;
            }
            return buf;
        }

        static void Write(string dir, string name, float[] samples)
        {
            string path = Path.Combine(dir, name);
            int dataBytes = samples.Length * 2;
            using (var fs = new FileStream(path, FileMode.Create, FileAccess.Write))
            using (var w = new BinaryWriter(fs))
            {
                w.Write(new char[] { 'R', 'I', 'F', 'F' });
                w.Write(36 + dataBytes);
                w.Write(new char[] { 'W', 'A', 'V', 'E' });
                w.Write(new char[] { 'f', 'm', 't', ' ' });
                w.Write(16);
                w.Write((short)1);
                w.Write((short)1);
                w.Write(Rate);
                w.Write(Rate * 2);
                w.Write((short)2);
                w.Write((short)16);
                w.Write(new char[] { 'd', 'a', 't', 'a' });
                w.Write(dataBytes);
                for (int i = 0; i < samples.Length; i++)
                {
                    double v = samples[i];
                    if (v > 1.0) v = 1.0;
                    if (v < -1.0) v = -1.0;
                    w.Write((short)Math.Round(v * 32767.0));
                }
            }
            Console.WriteLine("  " + name + "  " + (dataBytes / 1024) + " KB");
        }
    }
}
