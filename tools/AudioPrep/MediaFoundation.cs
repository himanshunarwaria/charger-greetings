// MediaFoundation.cs
//
// Minimal Media Foundation interop used to decode an arbitrary audio file
// (MP3, WAV, M4A...) into 16-bit PCM without any third-party dependency.
//
// Why Media Foundation rather than ffmpeg/NAudio:
//   * mfplat.dll / mfreadwrite.dll ship with every supported Windows build,
//     so asset preparation needs no downloads and no unvetted binaries.
//   * The Source Reader transparently handles ID3 tags, CBR/VBR and any
//     codec Windows can already play.
//
// Interop notes:
//   COM vtable slots are assigned in *declaration order*. Methods this tool
//   never calls are declared as zero-argument placeholders named _slotNN --
//   the parameter list is irrelevant for a slot that is never invoked, but
//   the slot itself must exist so the following methods land on the right
//   vtable index. Do not reorder or remove them.

using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace AudioPrep
{
    /// <summary>Decoded audio: interleaved float samples in the range [-1, 1].</summary>
    internal sealed class DecodedAudio
    {
        public float[] Samples;      // interleaved
        public int SampleRate;
        public int Channels;

        public int FrameCount { get { return Channels > 0 ? Samples.Length / Channels : 0; } }
        public double Duration { get { return SampleRate > 0 ? (double)FrameCount / SampleRate : 0.0; } }
    }

    internal static class MediaFoundation
    {
        // ---------------------------------------------------------------- GUIDs
        private static readonly Guid MFMediaType_Audio =
            new Guid("73647561-0000-0010-8000-00AA00389B71");
        private static readonly Guid MFAudioFormat_PCM =
            new Guid("00000001-0000-0010-8000-00AA00389B71");

        private static readonly Guid MF_MT_MAJOR_TYPE =
            new Guid("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
        private static readonly Guid MF_MT_SUBTYPE =
            new Guid("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
        private static readonly Guid MF_MT_AUDIO_NUM_CHANNELS =
            new Guid("37e48bf5-645e-4c5b-89de-ada9e29b696a");
        private static readonly Guid MF_MT_AUDIO_SAMPLES_PER_SECOND =
            new Guid("5faeeae7-0290-4c31-9e8a-c534f68d9dba");
        private static readonly Guid MF_MT_AUDIO_BITS_PER_SAMPLE =
            new Guid("f2deb57f-40fa-4764-aa33-ed4f2d1ff669");

        private const uint MF_VERSION = 0x00020070;   // MF_SDK_VERSION<<16 | MF_API_VERSION
        private const uint MFSTARTUP_FULL = 0;

        private const uint MF_SOURCE_READER_ALL_STREAMS = 0xFFFFFFFE;
        private const uint MF_SOURCE_READER_FIRST_AUDIO_STREAM = 0xFFFFFFFD;
        private const uint MF_SOURCE_READERF_ENDOFSTREAM = 0x00000002;

        // ------------------------------------------------------------- Exports
        [DllImport("mfplat.dll", ExactSpelling = true)]
        private static extern int MFStartup(uint version, uint flags);

        [DllImport("mfplat.dll", ExactSpelling = true)]
        private static extern int MFShutdown();

        [DllImport("mfplat.dll", ExactSpelling = true)]
        private static extern int MFCreateMediaType(out IntPtr ppMFType);

        [DllImport("mfreadwrite.dll", ExactSpelling = true, CharSet = CharSet.Unicode)]
        private static extern int MFCreateSourceReaderFromURL(
            string pwszURL, IntPtr pAttributes, out IMFSourceReader ppSourceReader);

        // ---------------------------------------------------------- Interfaces
        [ComImport, Guid("2cd2d921-c447-44a7-a13c-4adabfc247e3"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMFAttributes
        {
            void _slot01();                                             // GetItem
            void _slot02();                                             // GetItemType
            void _slot03();                                             // CompareItem
            void _slot04();                                             // Compare
            void GetUINT32([In, MarshalAs(UnmanagedType.LPStruct)] Guid key, out uint value);
            void _slot06();                                             // GetUINT64
            void _slot07();                                             // GetDouble
            void GetGUID([In, MarshalAs(UnmanagedType.LPStruct)] Guid key, out Guid value);
            void _slot09();                                             // GetStringLength
            void _slot10();                                             // GetString
            void _slot11();                                             // GetAllocatedString
            void _slot12();                                             // GetBlobSize
            void _slot13();                                             // GetBlob
            void _slot14();                                             // GetAllocatedBlob
            void _slot15();                                             // GetUnknown
            void _slot16();                                             // SetItem
            void _slot17();                                             // DeleteItem
            void _slot18();                                             // DeleteAllItems
            void SetUINT32([In, MarshalAs(UnmanagedType.LPStruct)] Guid key, uint value);
            void _slot20();                                             // SetUINT64
            void _slot21();                                             // SetDouble
            void SetGUID([In, MarshalAs(UnmanagedType.LPStruct)] Guid key,
                         [In, MarshalAs(UnmanagedType.LPStruct)] Guid value);
            void _slot23();                                             // SetString
            void _slot24();                                             // SetBlob
            void _slot25();                                             // SetUnknown
            void _slot26();                                             // LockStore
            void _slot27();                                             // UnlockStore
            void _slot28();                                             // GetCount
            void _slot29();                                             // GetItemByIndex
            void _slot30();                                             // CopyAllItems
        }

        // IMFSample: the 30 IMFAttributes slots, then its own.
        [ComImport, Guid("c40a00f2-b93a-4d80-ae8c-5a1c634f58e4"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMFSample
        {
            void _a01(); void _a02(); void _a03(); void _a04(); void _a05();
            void _a06(); void _a07(); void _a08(); void _a09(); void _a10();
            void _a11(); void _a12(); void _a13(); void _a14(); void _a15();
            void _a16(); void _a17(); void _a18(); void _a19(); void _a20();
            void _a21(); void _a22(); void _a23(); void _a24(); void _a25();
            void _a26(); void _a27(); void _a28(); void _a29(); void _a30();

            void _slot31();                                             // GetSampleFlags
            void _slot32();                                             // SetSampleFlags
            void _slot33();                                             // GetSampleTime
            void _slot34();                                             // SetSampleTime
            void _slot35();                                             // GetSampleDuration
            void _slot36();                                             // SetSampleDuration
            void _slot37();                                             // GetBufferCount
            void _slot38();                                             // GetBufferByIndex
            void ConvertToContiguousBuffer(out IMFMediaBuffer ppBuffer);
            // remaining slots unused
        }

        [ComImport, Guid("045FA593-8799-42b8-BC8D-8968C6453507"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMFMediaBuffer
        {
            void Lock(out IntPtr ppbBuffer, out uint pcbMaxLength, out uint pcbCurrentLength);
            void Unlock();
            void GetCurrentLength(out uint pcbCurrentLength);
            void _slot04();                                             // SetCurrentLength
            void _slot05();                                             // GetMaxLength
        }

        [ComImport, Guid("70ae66f2-c809-4e4f-8915-bdcb406b7993"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IMFSourceReader
        {
            void _slot01();                                             // GetStreamSelection
            void SetStreamSelection(uint dwStreamIndex,
                                    [MarshalAs(UnmanagedType.Bool)] bool fSelected);
            void _slot03();                                             // GetNativeMediaType
            void GetCurrentMediaType(uint dwStreamIndex, out IntPtr ppMediaType);
            void SetCurrentMediaType(uint dwStreamIndex, IntPtr pdwReserved, IntPtr pMediaType);
            void _slot06();                                             // SetCurrentPosition
            [PreserveSig]
            int ReadSample(uint dwStreamIndex, uint dwControlFlags,
                           out uint pdwActualStreamIndex, out uint pdwStreamFlags,
                           out long pllTimestamp, out IMFSample ppSample);
            // remaining slots unused
        }

        // ------------------------------------------------------------- Decoding
        private static bool _started;

        public static void Startup()
        {
            if (_started) return;
            int hr = MFStartup(MF_VERSION, MFSTARTUP_FULL);
            if (hr < 0) throw new COMException("MFStartup failed", hr);
            _started = true;
        }

        public static void Shutdown()
        {
            if (!_started) return;
            MFShutdown();
            _started = false;
        }

        /// <summary>
        /// Decodes any Windows-supported audio file to interleaved float PCM.
        /// The native sample rate and channel count are preserved; only the
        /// sample format is forced to 16-bit PCM so no resampler is required.
        /// </summary>
        public static DecodedAudio Decode(string path)
        {
            Startup();

            IMFSourceReader reader;
            int hr = MFCreateSourceReaderFromURL(path, IntPtr.Zero, out reader);
            if (hr < 0)
                throw new COMException("Cannot open '" + path + "' (unsupported or corrupt)", hr);

            try
            {
                reader.SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, false);
                reader.SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM, true);

                // Ask the reader for uncompressed 16-bit PCM.
                IntPtr pType;
                hr = MFCreateMediaType(out pType);
                if (hr < 0) throw new COMException("MFCreateMediaType failed", hr);
                try
                {
                    IMFAttributes attr = (IMFAttributes)Marshal.GetObjectForIUnknown(pType);
                    attr.SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
                    attr.SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
                    attr.SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
                    Marshal.ReleaseComObject(attr);

                    reader.SetCurrentMediaType(
                        MF_SOURCE_READER_FIRST_AUDIO_STREAM, IntPtr.Zero, pType);
                }
                finally { Marshal.Release(pType); }

                // Read back what the decoder actually produces.
                int sampleRate, channels;
                IntPtr pActual;
                reader.GetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, out pActual);
                try
                {
                    IMFAttributes attr = (IMFAttributes)Marshal.GetObjectForIUnknown(pActual);
                    uint sr, ch;
                    attr.GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, out sr);
                    attr.GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, out ch);
                    Marshal.ReleaseComObject(attr);
                    sampleRate = (int)sr;
                    channels = (int)ch;
                }
                finally { Marshal.Release(pActual); }

                List<byte> pcm = new List<byte>(1 << 20);
                byte[] scratch = new byte[1 << 16];

                while (true)
                {
                    uint actualIndex, flags;
                    long timestamp;
                    IMFSample sample;

                    hr = reader.ReadSample(MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0,
                                           out actualIndex, out flags, out timestamp, out sample);
                    if (hr < 0) throw new COMException("ReadSample failed", hr);
                    if ((flags & MF_SOURCE_READERF_ENDOFSTREAM) != 0)
                    {
                        if (sample != null) Marshal.ReleaseComObject(sample);
                        break;
                    }
                    if (sample == null) continue;   // gap / format change tick

                    IMFMediaBuffer buffer;
                    sample.ConvertToContiguousBuffer(out buffer);
                    IntPtr data;
                    uint maxLen, curLen;
                    buffer.Lock(out data, out maxLen, out curLen);
                    try
                    {
                        if (curLen > scratch.Length) scratch = new byte[curLen];
                        Marshal.Copy(data, scratch, 0, (int)curLen);
                        for (int i = 0; i < (int)curLen; i++) pcm.Add(scratch[i]);
                    }
                    finally
                    {
                        buffer.Unlock();
                        Marshal.ReleaseComObject(buffer);
                        Marshal.ReleaseComObject(sample);
                    }
                }

                // 16-bit signed LE -> float
                byte[] raw = pcm.ToArray();
                int count = raw.Length / 2;
                float[] samples = new float[count];
                for (int i = 0; i < count; i++)
                {
                    short s = (short)(raw[i * 2] | (raw[i * 2 + 1] << 8));
                    samples[i] = s / 32768f;
                }

                DecodedAudio result = new DecodedAudio();
                result.Samples = samples;
                result.SampleRate = sampleRate;
                result.Channels = channels;
                return result;
            }
            finally
            {
                Marshal.ReleaseComObject(reader);
            }
        }
    }
}
