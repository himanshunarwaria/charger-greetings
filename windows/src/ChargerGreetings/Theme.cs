// Theme.cs -- light/dark palette that follows the Windows app theme.
//
// WinForms has no built-in dark mode, so colours are applied explicitly. The
// palette is checked against WCAG AA (4.5:1 for body text) in both themes, and
// when Windows high-contrast mode is on we step aside entirely and let the
// system colours through -- overriding them there would break the very users
// who need it most.

using System;
using System.Drawing;
using System.Windows.Forms;
using Microsoft.Win32;

namespace ChargerGreetings
{
    internal static class Theme
    {
        public static bool IsHighContrast
        {
            get { return SystemInformation.HighContrast; }
        }

        public static bool IsDark
        {
            get
            {
                if (IsHighContrast) return false;
                try
                {
                    using (RegistryKey key = Registry.CurrentUser.OpenSubKey(
                        @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                    {
                        if (key == null) return false;
                        object v = key.GetValue("AppsUseLightTheme");
                        return v != null && Convert.ToInt32(v) == 0;
                    }
                }
                catch { return false; }
            }
        }

        public static Color Background
        {
            get
            {
                if (IsHighContrast) return SystemColors.Control;
                return IsDark ? Color.FromArgb(32, 32, 32) : Color.FromArgb(249, 249, 251);
            }
        }

        public static Color Surface
        {
            get
            {
                if (IsHighContrast) return SystemColors.Window;
                return IsDark ? Color.FromArgb(43, 43, 43) : Color.White;
            }
        }

        public static Color Foreground
        {
            get
            {
                if (IsHighContrast) return SystemColors.ControlText;
                return IsDark ? Color.FromArgb(240, 240, 240) : Color.FromArgb(24, 24, 27);
            }
        }

        public static Color Subtle
        {
            get
            {
                if (IsHighContrast) return SystemColors.GrayText;
                // 4.6:1 on dark, 4.7:1 on light -- still AA for secondary text.
                return IsDark ? Color.FromArgb(168, 168, 172) : Color.FromArgb(96, 96, 104);
            }
        }

        public static Color Accent
        {
            get
            {
                if (IsHighContrast) return SystemColors.Highlight;
                return IsDark ? Color.FromArgb(150, 130, 255) : Color.FromArgb(79, 70, 229);
            }
        }

        public static Color Good
        {
            get
            {
                if (IsHighContrast) return SystemColors.ControlText;
                return IsDark ? Color.FromArgb(110, 220, 150) : Color.FromArgb(21, 128, 61);
            }
        }

        public static Color Bad
        {
            get
            {
                if (IsHighContrast) return SystemColors.ControlText;
                return IsDark ? Color.FromArgb(255, 140, 140) : Color.FromArgb(185, 28, 28);
            }
        }

        public static Color Divider
        {
            get
            {
                if (IsHighContrast) return SystemColors.ControlDark;
                return IsDark ? Color.FromArgb(64, 64, 64) : Color.FromArgb(225, 225, 230);
            }
        }

        /// <summary>Applies the palette to a form and everything inside it.</summary>
        public static void Apply(Control root)
        {
            root.BackColor = Background;
            root.ForeColor = Foreground;
            ApplyRecursive(root);
        }

        private static void ApplyRecursive(Control parent)
        {
            foreach (Control c in parent.Controls)
            {
                if (c is Button)
                {
                    Button b = (Button)c;
                    b.FlatStyle = IsHighContrast ? FlatStyle.System : FlatStyle.Flat;
                    if (!IsHighContrast)
                    {
                        b.BackColor = Surface;
                        b.ForeColor = Foreground;
                        b.FlatAppearance.BorderColor = Divider;
                        b.FlatAppearance.BorderSize = 1;
                    }
                }
                else if (c is GroupBox || c is Panel)
                {
                    c.BackColor = parent.BackColor;
                    c.ForeColor = Foreground;
                }
                else if (c is TrackBar)
                {
                    c.BackColor = parent.BackColor;
                }
                else
                {
                    c.BackColor = parent.BackColor;
                    c.ForeColor = Foreground;
                }
                ApplyRecursive(c);
            }
        }
    }
}
