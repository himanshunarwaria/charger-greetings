// Loudness.cs
//
// ITU-R BS.1770-4 integrated loudness (LUFS) and true-peak (dBTP) measurement.
//
// Perceived loudness is what the brief asks us to match between the two
// greetings. Raw peak or RMS would not do it: the two clips have different
// spectral content and lengths, so matching their peaks leaves one audibly
// quieter. BS.1770 is the broadcast standard for exactly this problem.
//
// Implementation follows the spec:
//   1. K-weighting  = high-shelf ("head") filter + RLB high-pass, cascaded.
//      Coefficients are derived parametrically from the sample rate so the
//      measurement is correct at 44.1 kHz, not just at the 48 kHz the spec
//      tabulates.
//   2. Mean square over 400 ms blocks with 75 % overlap (100 ms hop).
//   3. Two-stage gating: absolute -70 LUFS, then relative -10 LU.
//
// True peak uses 4x oversampling with a windowed-sinc polyphase FIR, which is
// the minimum the spec allows for material at these sample rates.

using System;
using System.Collections.Generic;

namespace AudioPrep
{
    internal struct Biquad
    {
        public double B0, B1, B2, A1, A2;
        private double _x1, _x2, _y1, _y2;

        public double Process(double x)
        {
            double y = B0 * x + B1 * _x1 + B2 * _x2 - A1 * _y1 - A2 * _y2;
            _x2 = _x1; _x1 = x;
            _y2 = _y1; _y1 = y;
            return y;
        }

        /// <summary>Stage 1 of K-weighting: +4 dB high shelf around 1681 Hz.</summary>
        public static Biquad KWeightingShelf(int sampleRate)
        {
            const double f0 = 1681.974450955533;
            const double G = 3.999843853973347;
            const double Q = 0.7071752369554196;

            double K = Math.Tan(Math.PI * f0 / sampleRate);
            double Vh = Math.Pow(10.0, G / 20.0);
            double Vb = Math.Pow(Vh, 0.4996667741545416);
            double a0 = 1.0 + K / Q + K * K;

            Biquad b = new Biquad();
            b.B0 = (Vh + Vb * K / Q + K * K) / a0;
            b.B1 = 2.0 * (K * K - Vh) / a0;
            b.B2 = (Vh - Vb * K / Q + K * K) / a0;
            b.A1 = 2.0 * (K * K - 1.0) / a0;
            b.A2 = (1.0 - K / Q + K * K) / a0;
            return b;
        }

        /// <summary>Stage 2 of K-weighting: RLB high-pass at ~38 Hz.</summary>
        public static Biquad KWeightingHighPass(int sampleRate)
        {
            const double f0 = 38.13547087602444;
            const double Q = 0.5003270373238773;

            double K = Math.Tan(Math.PI * f0 / sampleRate);
            double denom = 1.0 + K / Q + K * K;

            Biquad b = new Biquad();
            b.B0 = 1.0;
            b.B1 = -2.0;
            b.B2 = 1.0;
            b.A1 = 2.0 * (K * K - 1.0) / denom;
            b.A2 = (1.0 - K / Q + K * K) / denom;
            return b;
        }
    }

    internal static class Loudness
    {
        /// <summary>Integrated loudness in LUFS. Returns double.NegativeInfinity for silence.</summary>
        public static double MeasureLufs(float[] interleaved, int channels, int sampleRate)
        {
            int frames = interleaved.Length / channels;
            if (frames == 0) return double.NegativeInfinity;

            // K-weight every channel independently.
            double[][] weighted = new double[channels][];
            for (int c = 0; c < channels; c++)
            {
                weighted[c] = new double[frames];
                Biquad shelf = Biquad.KWeightingShelf(sampleRate);
                Biquad hp = Biquad.KWeightingHighPass(sampleRate);
                for (int i = 0; i < frames; i++)
                    weighted[c][i] = hp.Process(shelf.Process(interleaved[i * channels + c]));
            }

            int blockSize = (int)Math.Round(sampleRate * 0.400);   // 400 ms
            int hop = (int)Math.Round(sampleRate * 0.100);         // 75 % overlap
            if (frames < blockSize)
            {
                // Clip shorter than one gating block: measure the whole thing as
                // a single block so short UI sounds still get a usable number.
                blockSize = frames;
                hop = frames;
            }

            List<double> blockPower = new List<double>();
            for (int start = 0; start + blockSize <= frames; start += hop)
            {
                double z = 0.0;
                for (int c = 0; c < channels; c++)
                {
                    double sum = 0.0;
                    double[] w = weighted[c];
                    for (int i = start; i < start + blockSize; i++) sum += w[i] * w[i];
                    // Channel weight G = 1.0 for L/R/C; this tool only handles
                    // mono/stereo material, so no surround weighting is needed.
                    z += sum / blockSize;
                }
                blockPower.Add(z);
            }
            if (blockPower.Count == 0) return double.NegativeInfinity;

            // Stage 1 gate: absolute -70 LUFS.
            const double absoluteGate = -70.0;
            List<double> aboveAbsolute = new List<double>();
            foreach (double z in blockPower)
                if (BlockLoudness(z) > absoluteGate) aboveAbsolute.Add(z);
            if (aboveAbsolute.Count == 0) return double.NegativeInfinity;

            // Stage 2 gate: relative -10 LU below the ungated-above-absolute mean.
            double meanAbs = 0.0;
            foreach (double z in aboveAbsolute) meanAbs += z;
            meanAbs /= aboveAbsolute.Count;
            double relativeGate = BlockLoudness(meanAbs) - 10.0;

            double sumGated = 0.0;
            int nGated = 0;
            foreach (double z in aboveAbsolute)
            {
                if (BlockLoudness(z) > relativeGate) { sumGated += z; nGated++; }
            }
            if (nGated == 0) return BlockLoudness(meanAbs);

            return BlockLoudness(sumGated / nGated);
        }

        private static double BlockLoudness(double meanSquare)
        {
            if (meanSquare <= 0.0) return double.NegativeInfinity;
            return -0.691 + 10.0 * Math.Log10(meanSquare);
        }

        /// <summary>Sample peak in dBFS.</summary>
        public static double MeasureSamplePeakDb(float[] samples)
        {
            double peak = 0.0;
            for (int i = 0; i < samples.Length; i++)
            {
                double a = Math.Abs(samples[i]);
                if (a > peak) peak = a;
            }
            return peak <= 0.0 ? double.NegativeInfinity : 20.0 * Math.Log10(peak);
        }

        /// <summary>True peak in dBTP via 4x oversampling (BS.1770 Annex 2).</summary>
        public static double MeasureTruePeakDb(float[] interleaved, int channels)
        {
            double peak = MeasureTruePeakLinear(interleaved, channels);
            return peak <= 0.0 ? double.NegativeInfinity : 20.0 * Math.Log10(peak);
        }

        public static double MeasureTruePeakLinear(float[] interleaved, int channels)
        {
            const int oversample = 4;
            const int tapsPerPhase = 12;                 // 48-tap prototype
            double[][] phases = BuildPolyphase(oversample, tapsPerPhase);

            int frames = interleaved.Length / channels;
            double peak = 0.0;

            for (int c = 0; c < channels; c++)
            {
                for (int n = 0; n < frames; n++)
                {
                    for (int p = 0; p < oversample; p++)
                    {
                        double acc = 0.0;
                        double[] h = phases[p];
                        for (int k = 0; k < tapsPerPhase; k++)
                        {
                            int idx = n - k + tapsPerPhase / 2;
                            if (idx < 0 || idx >= frames) continue;
                            acc += h[k] * interleaved[idx * channels + c];
                        }
                        double a = Math.Abs(acc);
                        if (a > peak) peak = a;
                    }
                }
            }

            // Never report below the sample peak (guards against FIR edge effects).
            for (int i = 0; i < interleaved.Length; i++)
            {
                double a = Math.Abs(interleaved[i]);
                if (a > peak) peak = a;
            }
            return peak;
        }

        /// <summary>Windowed-sinc interpolation filter split into polyphase branches.</summary>
        private static double[][] BuildPolyphase(int oversample, int tapsPerPhase)
        {
            int total = oversample * tapsPerPhase;
            double[] proto = new double[total];
            double sum = 0.0;
            for (int i = 0; i < total; i++)
            {
                double t = i - (total - 1) / 2.0;
                double x = t / oversample;
                double sinc = (Math.Abs(x) < 1e-9) ? 1.0 : Math.Sin(Math.PI * x) / (Math.PI * x);
                // Blackman window keeps stopband rejection high enough that the
                // measured true peak is not inflated by ringing.
                double w = 0.42 - 0.5 * Math.Cos(2.0 * Math.PI * i / (total - 1))
                                + 0.08 * Math.Cos(4.0 * Math.PI * i / (total - 1));
                proto[i] = sinc * w;
                sum += proto[i];
            }
            // Normalise so a DC input passes through at unity.
            for (int i = 0; i < total; i++) proto[i] *= oversample / sum;

            double[][] phases = new double[oversample][];
            for (int p = 0; p < oversample; p++)
            {
                phases[p] = new double[tapsPerPhase];
                for (int k = 0; k < tapsPerPhase; k++)
                {
                    int idx = k * oversample + p;
                    phases[p][k] = idx < total ? proto[idx] : 0.0;
                }
            }
            return phases;
        }
    }
}
