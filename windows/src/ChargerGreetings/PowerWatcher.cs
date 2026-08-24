// PowerWatcher.cs -- event-driven AC/DC power source detection.
//
// Two independent signals feed one state read. Neither polls.
//
//   1. RegisterPowerSettingNotification(GUID_ACDC_POWER_SOURCE) is the primary.
//      Windows posts WM_POWERBROADCAST to our hidden window at the moment the
//      power source changes, and *only* then. This is the precise API for the
//      job and it works on desktops and UPS-reported systems, not just laptops.
//
//   2. SystemEvents.PowerModeChanged is the safety net. Its StatusChange fires
//      for battery-percentage ticks too, so it is never trusted on its own --
//      it just prompts a re-read of the real state. It also gives us the
//      Suspend/Resume edges we need to stay quiet across sleep.
//
// Both paths converge on GetSystemPowerStatus, and the consumer is responsible
// for ignoring reads that match the state it already knows about.

using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

namespace ChargerGreetings
{
    internal enum PowerSource
    {
        Unknown = 0,
        Battery = 1,
        AC = 2
    }

    internal sealed class PowerSourceEventArgs : EventArgs
    {
        public PowerSource Source;
        public string Origin;      // which signal produced this read (for the log)
    }

    internal sealed class PowerWatcher : IDisposable
    {
        // ------------------------------------------------------------ interop
        private static readonly Guid GUID_ACDC_POWER_SOURCE =
            new Guid("5d3e9a59-e9d5-4b00-a6bd-ff34ff516548");

        private const int WM_POWERBROADCAST = 0x0218;
        private const int PBT_APMSUSPEND = 0x0004;
        private const int PBT_APMRESUMESUSPEND = 0x0007;
        private const int PBT_APMRESUMEAUTOMATIC = 0x0012;
        private const int PBT_POWERSETTINGCHANGE = 0x8013;
        private const int DEVICE_NOTIFY_WINDOW_HANDLE = 0x00000000;

        [StructLayout(LayoutKind.Sequential)]
        private struct SYSTEM_POWER_STATUS
        {
            public byte ACLineStatus;        // 0 offline, 1 online, 255 unknown
            public byte BatteryFlag;
            public byte BatteryLifePercent;
            public byte SystemStatusFlag;
            public int BatteryLifeTime;
            public int BatteryFullLifeTime;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS status);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr RegisterPowerSettingNotification(
            IntPtr recipient, ref Guid powerSettingGuid, int flags);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool UnregisterPowerSettingNotification(IntPtr handle);

        // ------------------------------------------------------------- events
        public event EventHandler<PowerSourceEventArgs> PowerSourceRead;
        public event EventHandler Suspending;
        public event EventHandler Resumed;

        // -------------------------------------------------------------- state
        private MessageWindow _window;
        private IntPtr _notifyHandle = IntPtr.Zero;
        private bool _disposed;

        public bool UsingPowerSettingNotification
        {
            get { return _notifyHandle != IntPtr.Zero; }
        }

        public void Start()
        {
            _window = new MessageWindow(this);

            Guid guid = GUID_ACDC_POWER_SOURCE;
            _notifyHandle = RegisterPowerSettingNotification(
                _window.Handle, ref guid, DEVICE_NOTIFY_WINDOW_HANDLE);

            if (_notifyHandle == IntPtr.Zero)
            {
                // Extremely unlikely on a supported Windows build, but the app
                // still works: PowerModeChanged alone covers AC/DC changes.
                Logger.Warn("RegisterPowerSettingNotification failed (error "
                            + Marshal.GetLastWin32Error()
                            + "); relying on SystemEvents only.");
            }

            SystemEvents.PowerModeChanged += OnPowerModeChanged;
            Logger.Info("Power watcher started. Precise notifications: "
                        + (UsingPowerSettingNotification ? "yes" : "no"));
        }

        /// <summary>
        /// Supplies the "what is the power source right now?" answer. Defaults to
        /// the real Windows API; the test harness substitutes a stub so the
        /// debounce and cooldown rules can be exercised without a physical cable.
        /// </summary>
        internal Func<PowerSource> SourceReader = ReadCurrent;

        /// <summary>Instance-level read, so consumers never bind to the hardware directly.</summary>
        public PowerSource ReadCurrentSource()
        {
            return SourceReader();
        }

        /// <summary>Feeds a synthetic reading through the normal pipeline. Test-only.</summary>
        internal void SimulateRead(PowerSource source, string origin)
        {
            Raise(source, origin);
        }

        /// <summary>Test-only sleep/wake edges.</summary>
        internal void SimulateSuspend()
        {
            if (Suspending != null) Suspending(this, EventArgs.Empty);
        }

        internal void SimulateResume()
        {
            if (Resumed != null) Resumed(this, EventArgs.Empty);
        }

        /// <summary>Reads the current power source straight from Windows.</summary>
        public static PowerSource ReadCurrent()
        {
            SYSTEM_POWER_STATUS status;
            if (!GetSystemPowerStatus(out status)) return PowerSource.Unknown;

            switch (status.ACLineStatus)
            {
                case 0: return PowerSource.Battery;
                case 1: return PowerSource.AC;
                default: return PowerSource.Unknown;
            }
        }

        private void Raise(PowerSource source, string origin)
        {
            EventHandler<PowerSourceEventArgs> handler = PowerSourceRead;
            if (handler == null) return;
            PowerSourceEventArgs args = new PowerSourceEventArgs();
            args.Source = source;
            args.Origin = origin;
            handler(this, args);
        }

        private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
        {
            switch (e.Mode)
            {
                case PowerModes.StatusChange:
                    // Fires for battery percentage too. Just re-read; the
                    // controller discards it if the source did not actually move.
                    Raise(ReadCurrent(), "PowerModeChanged");
                    break;
                case PowerModes.Suspend:
                    if (Suspending != null) Suspending(this, EventArgs.Empty);
                    break;
                case PowerModes.Resume:
                    if (Resumed != null) Resumed(this, EventArgs.Empty);
                    break;
            }
        }

        // The hidden window that receives WM_POWERBROADCAST.
        private sealed class MessageWindow : NativeWindow
        {
            private readonly PowerWatcher _owner;

            public MessageWindow(PowerWatcher owner)
            {
                _owner = owner;
                CreateParams cp = new CreateParams();
                cp.Caption = AppInfo.ProductName + " power listener";
                // A message-only window (HWND_MESSAGE) never appears anywhere in
                // the UI and still receives broadcast power notifications.
                cp.Parent = (IntPtr)(-3);
                CreateHandle(cp);
            }

            protected override void WndProc(ref Message m)
            {
                if (m.Msg == WM_POWERBROADCAST)
                {
                    int evt = m.WParam.ToInt32();
                    switch (evt)
                    {
                        case PBT_POWERSETTINGCHANGE:
                            HandleSettingChange(m.LParam);
                            break;
                        case PBT_APMSUSPEND:
                            if (_owner.Suspending != null)
                                _owner.Suspending(_owner, EventArgs.Empty);
                            break;
                        case PBT_APMRESUMESUSPEND:
                        case PBT_APMRESUMEAUTOMATIC:
                            if (_owner.Resumed != null)
                                _owner.Resumed(_owner, EventArgs.Empty);
                            break;
                    }
                    m.Result = (IntPtr)1;
                }
                base.WndProc(ref m);
            }

            private void HandleSettingChange(IntPtr lParam)
            {
                try
                {
                    // POWERBROADCAST_SETTING: GUID (16) + DWORD DataLength (4) + Data.
                    byte[] guidBytes = new byte[16];
                    Marshal.Copy(lParam, guidBytes, 0, 16);
                    Guid setting = new Guid(guidBytes);
                    if (setting != GUID_ACDC_POWER_SOURCE) return;

                    int dataLength = Marshal.ReadInt32(lParam, 16);
                    if (dataLength < 4) return;

                    // SYSTEM_POWER_CONDITION: 0 = PoAc, 1 = PoDc, 2 = PoHot (UPS).
                    int condition = Marshal.ReadInt32(lParam, 20);
                    PowerSource source;
                    if (condition == 0) source = PowerSource.AC;
                    else if (condition == 1) source = PowerSource.Battery;
                    else source = ReadCurrent();   // PoHot: ask the API directly

                    _owner.Raise(source, "PowerSettingNotification");
                }
                catch (Exception ex)
                {
                    Logger.Error("Failed to decode power notification", ex);
                    _owner.Raise(ReadCurrent(), "PowerSettingNotification(fallback)");
                }
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;

            SystemEvents.PowerModeChanged -= OnPowerModeChanged;

            if (_notifyHandle != IntPtr.Zero)
            {
                UnregisterPowerSettingNotification(_notifyHandle);
                _notifyHandle = IntPtr.Zero;
            }
            if (_window != null)
            {
                _window.DestroyHandle();
                _window = null;
            }
        }
    }
}
