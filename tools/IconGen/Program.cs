// IconGen -- draws the Charger Greetings mark and writes multi-resolution .ico
// files plus a PNG preview.
//
// Usage: IconGen.exe <outputDir>
//
// The mark is a power plug on a rounded gradient tile: instantly readable at
// 16 px in the notification area, and the same shape as the Android vector
// drawable so the two apps look like one product. Two variants are produced --
// the colour one for "watching" and a grey one for "paused" -- so the tray icon
// itself communicates state without needing a badge or a tooltip.

using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;

namespace IconGen
{
    // Public so the same source can either be compiled to an executable or
    // loaded in-memory with PowerShell's Add-Type by build.ps1.
    public static class Program
    {
        private static readonly int[] Sizes = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };

        // Brand palette. Indigo -> violet reads as "modern utility" and stays
        // distinguishable from the blue/green system icons around it in the tray.
        private static readonly Color BrandFrom = Color.FromArgb(0xFF, 0x63, 0x66, 0xF1);
        private static readonly Color BrandTo = Color.FromArgb(0xFF, 0x8B, 0x5C, 0xF6);
        private static readonly Color MutedFrom = Color.FromArgb(0xFF, 0x9C, 0xA3, 0xAF);
        private static readonly Color MutedTo = Color.FromArgb(0xFF, 0x6B, 0x72, 0x80);

        public static int Main(string[] args)
        {
            if (args.Length < 1)
            {
                Console.Error.WriteLine("usage: IconGen <outputDir>");
                return 2;
            }
            return Generate(args[0]);
        }

        /// <summary>Writes app.ico, app-off.ico and a PNG preview into <paramref name="outDir"/>.</summary>
        public static int Generate(string outDir)
        {
            try
            {
                Directory.CreateDirectory(outDir);

                WriteIco(Path.Combine(outDir, "app.ico"), BrandFrom, BrandTo);
                WriteIco(Path.Combine(outDir, "app-off.ico"), MutedFrom, MutedTo);

                using (Bitmap preview = Draw(512, BrandFrom, BrandTo))
                    preview.Save(Path.Combine(outDir, "icon-preview.png"), ImageFormat.Png);

                Console.WriteLine("Icons written to " + outDir);
                return 0;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FATAL: " + ex);
                return 3;
            }
        }

        /// <summary>
        /// Writes the legacy Android launcher bitmaps. Android 8+ uses the
        /// adaptive icon (vector, in res/mipmap-anydpi-v26), but minSdk 24 still
        /// needs real PNGs for Android 7.
        /// </summary>
        public static int GenerateAndroid(string resDir)
        {
            try
            {
                // density bucket -> launcher icon edge in px
                string[] buckets = { "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };
                int[] pixels = { 48, 72, 96, 144, 192 };

                for (int i = 0; i < buckets.Length; i++)
                {
                    string dir = Path.Combine(resDir, "mipmap-" + buckets[i]);
                    Directory.CreateDirectory(dir);

                    using (Bitmap square = Draw(pixels[i], BrandFrom, BrandTo, false))
                        square.Save(Path.Combine(dir, "ic_launcher.png"), ImageFormat.Png);

                    using (Bitmap round = Draw(pixels[i], BrandFrom, BrandTo, true))
                        round.Save(Path.Combine(dir, "ic_launcher_round.png"), ImageFormat.Png);
                }

                Console.WriteLine("Android launcher bitmaps written to " + resDir);
                return 0;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FATAL: " + ex);
                return 3;
            }
        }

        // ------------------------------------------------------------ drawing
        private static Bitmap Draw(int size, Color from, Color to)
        {
            return Draw(size, from, to, false);
        }

        private static Bitmap Draw(int size, Color from, Color to, bool circular)
        {
            Bitmap bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.Clear(Color.Transparent);

                float s = size;
                float radius = s * 0.22f;

                // Tile: rounded square, or a circle for Android's round launcher.
                using (GraphicsPath tile = circular ? Circle(0.5f, 0.5f, s - 1f)
                                                    : RoundedRect(0.5f, 0.5f, s - 1f, s - 1f, radius))
                using (LinearGradientBrush brush = new LinearGradientBrush(
                           new RectangleF(0, 0, s, s), from, to, 45f))
                {
                    g.FillPath(brush, tile);
                }

                // Plug, in white. Proportions are tuned so the two prongs stay
                // separated by at least one pixel even at 16 px.
                using (SolidBrush white = new SolidBrush(Color.FromArgb(0xFF, 0xFF, 0xFF, 0xFF)))
                {
                    float prongW = s * 0.105f;
                    float prongH = s * 0.235f;
                    float prongY = s * 0.140f;
                    float prongR = Math.Max(0.5f, prongW * 0.5f);

                    using (GraphicsPath p1 = RoundedRect(s * 0.335f, prongY, prongW, prongH, prongR))
                        g.FillPath(white, p1);
                    using (GraphicsPath p2 = RoundedRect(s * 0.560f, prongY, prongW, prongH, prongR))
                        g.FillPath(white, p2);

                    using (GraphicsPath body = RoundedRect(
                               s * 0.250f, s * 0.345f, s * 0.50f, s * 0.275f, s * 0.085f))
                        g.FillPath(white, body);

                    using (GraphicsPath cable = RoundedRect(
                               s * 0.443f, s * 0.600f, s * 0.114f, s * 0.215f, s * 0.057f))
                        g.FillPath(white, cable);
                }
            }
            return bmp;
        }

        private static GraphicsPath Circle(float x, float y, float diameter)
        {
            GraphicsPath path = new GraphicsPath();
            path.AddEllipse(x, y, diameter, diameter);
            return path;
        }

        private static GraphicsPath RoundedRect(float x, float y, float w, float h, float r)
        {
            r = Math.Min(r, Math.Min(w, h) / 2f);
            GraphicsPath path = new GraphicsPath();
            if (r <= 0.01f)
            {
                path.AddRectangle(new RectangleF(x, y, w, h));
                return path;
            }
            float d = r * 2f;
            path.AddArc(x, y, d, d, 180, 90);
            path.AddArc(x + w - d, y, d, d, 270, 90);
            path.AddArc(x + w - d, y + h - d, d, d, 0, 90);
            path.AddArc(x, y + h - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        // ---------------------------------------------------------- ICO output
        private static void WriteIco(string path, Color from, Color to)
        {
            using (FileStream fs = new FileStream(path, FileMode.Create, FileAccess.Write))
            using (BinaryWriter w = new BinaryWriter(fs))
            {
                byte[][] payloads = new byte[Sizes.Length][];
                for (int i = 0; i < Sizes.Length; i++)
                {
                    using (Bitmap bmp = Draw(Sizes[i], from, to))
                    {
                        // Windows accepts PNG-compressed entries from Vista on,
                        // but only the large ones benefit; small entries stay as
                        // DIBs for maximum compatibility with older shell paths.
                        payloads[i] = Sizes[i] >= 128 ? EncodePng(bmp) : EncodeDib(bmp);
                    }
                }

                w.Write((short)0);                       // reserved
                w.Write((short)1);                       // type: icon
                w.Write((short)Sizes.Length);

                int offset = 6 + 16 * Sizes.Length;
                for (int i = 0; i < Sizes.Length; i++)
                {
                    w.Write((byte)(Sizes[i] >= 256 ? 0 : Sizes[i]));
                    w.Write((byte)(Sizes[i] >= 256 ? 0 : Sizes[i]));
                    w.Write((byte)0);                    // palette size
                    w.Write((byte)0);                    // reserved
                    w.Write((short)1);                   // colour planes
                    w.Write((short)32);                  // bits per pixel
                    w.Write(payloads[i].Length);
                    w.Write(offset);
                    offset += payloads[i].Length;
                }

                for (int i = 0; i < Sizes.Length; i++) w.Write(payloads[i]);
            }
        }

        private static byte[] EncodePng(Bitmap bmp)
        {
            using (MemoryStream ms = new MemoryStream())
            {
                bmp.Save(ms, ImageFormat.Png);
                return ms.ToArray();
            }
        }

        /// <summary>BITMAPINFOHEADER + bottom-up BGRA pixels + AND mask.</summary>
        private static byte[] EncodeDib(Bitmap bmp)
        {
            int w = bmp.Width, h = bmp.Height;
            int xorSize = w * h * 4;
            int maskStride = ((w + 31) / 32) * 4;
            int andSize = maskStride * h;

            using (MemoryStream ms = new MemoryStream())
            using (BinaryWriter bw = new BinaryWriter(ms))
            {
                bw.Write(40);                            // biSize
                bw.Write(w);                             // biWidth
                bw.Write(h * 2);                         // biHeight: XOR + AND stacked
                bw.Write((short)1);                      // biPlanes
                bw.Write((short)32);                     // biBitCount
                bw.Write(0);                             // biCompression: BI_RGB
                bw.Write(xorSize + andSize);             // biSizeImage
                bw.Write(0); bw.Write(0);                // pixels-per-metre
                bw.Write(0); bw.Write(0);                // palette counts

                BitmapData data = bmp.LockBits(new Rectangle(0, 0, w, h),
                                               ImageLockMode.ReadOnly,
                                               PixelFormat.Format32bppArgb);
                try
                {
                    byte[] row = new byte[w * 4];
                    for (int y = h - 1; y >= 0; y--)     // DIBs are stored bottom-up
                    {
                        IntPtr src = new IntPtr(data.Scan0.ToInt64() + (long)y * data.Stride);
                        System.Runtime.InteropServices.Marshal.Copy(src, row, 0, row.Length);
                        bw.Write(row);
                    }
                }
                finally { bmp.UnlockBits(data); }

                // The 1-bpp AND mask is unused when the XOR bitmap carries alpha,
                // but the structure still has to be present and correctly sized.
                bw.Write(new byte[andSize]);
                return ms.ToArray();
            }
        }
    }
}
