// Setup.cs -- per-user installer and uninstaller for Charger Greetings.
//
// Deliberately NOT an MSI and NOT a script:
//   * Per-user install into %LOCALAPPDATA%\Programs, so no UAC prompt, no
//     administrator, and nothing written outside the user's own profile.
//   * Registers under HKCU ...\Uninstall, so the app appears in
//     Settings > Apps > Installed apps and uninstalls the normal way.
//   * The payload is embedded in this executable, so the whole install is one
//     file with nothing to keep together.
//   * No PowerShell, no .vbs, no execution-policy bypass -- the pattern the
//     previous version used is a well-known malware signature and gets flagged.
//
// Run with /uninstall to remove. Run with /silent to install without UI.

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace ChargerGreetings.Installer
{
    internal static class Setup
    {
        private const string ProductName = "Charger Greetings";
        private const string ShortName = "ChargerGreetings";
        private const string Version = "1.0.0";
        private const string Publisher = "Charger Greetings";
        private const string ExeName = "ChargerGreetings.exe";
        private const string UninstallKey =
            @"Software\Microsoft\Windows\CurrentVersion\Uninstall\ChargerGreetings";

        private static string InstallDir
        {
            get
            {
                return Path.Combine(
                    Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                        "Programs"),
                    ShortName);
            }
        }

        private static string StartMenuShortcut
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.Programs),
                    ProductName + ".lnk");
            }
        }

        [STAThread]
        private static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool uninstall = HasSwitch(args, "/uninstall") || HasSwitch(args, "--uninstall");
            bool silent = HasSwitch(args, "/silent") || HasSwitch(args, "--silent");

            try
            {
                if (uninstall) return Uninstall(silent);
                return Install(silent);
            }
            catch (Exception ex)
            {
                if (!silent)
                    MessageBox.Show(
                        (uninstall ? "Uninstall" : "Install") + " could not finish.\n\n" + ex.Message,
                        ProductName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        // ----------------------------------------------------------- install
        private static int Install(bool silent)
        {
            if (!silent && !ShowWelcome()) return 2;

            StopRunningApp();

            string dir = InstallDir;
            Directory.CreateDirectory(dir);

            ExtractPayload(dir);

            string exePath = Path.Combine(dir, ExeName);
            if (!File.Exists(exePath))
                throw new FileNotFoundException("The application was not extracted correctly.");

            // Uninstall support: keep a copy of this installer next to the app.
            string setupCopy = Path.Combine(dir, "Uninstall.exe");
            try { File.Copy(Assembly.GetExecutingAssembly().Location, setupCopy, true); }
            catch (IOException) { /* already running from there; fine */ }

            CreateStartMenuShortcut(exePath);
            RegisterUninstall(dir, exePath, setupCopy);

            // Start with Windows, per-user. The app manages this value itself
            // afterwards; the installer just sets the sensible default.
            using (RegistryKey run = Registry.CurrentUser.CreateSubKey(
                       @"Software\Microsoft\Windows\CurrentVersion\Run"))
            {
                if (run != null) run.SetValue(ShortName, "\"" + exePath + "\" --startup",
                                              RegistryValueKind.String);
            }

            Process.Start(new ProcessStartInfo(exePath) { WorkingDirectory = dir });

            if (!silent)
                MessageBox.Show(
                    ProductName + " is installed and running.\n\n"
                    + "Look for the plug icon in the notification area — you may need to\n"
                    + "click the ^ arrow to see it. Right-click it to test the sounds.\n\n"
                    + "Connected:  मालिक, प्रणाम\n"
                    + "Removed:    फिर मिलते हैं, मालिक",
                    ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);

            return 0;
        }

        private static bool ShowWelcome()
        {
            using (Form form = new Form())
            {
                form.Text = ProductName + " Setup";
                form.FormBorderStyle = FormBorderStyle.FixedDialog;
                form.MaximizeBox = false;
                form.MinimizeBox = false;
                form.StartPosition = FormStartPosition.CenterScreen;
                form.ClientSize = new Size(440, 260);
                form.Font = new Font("Segoe UI", 9.75f);

                try
                {
                    form.Icon = Icon.ExtractAssociatedIcon(
                        Assembly.GetExecutingAssembly().Location);
                }
                catch { }

                Label title = new Label();
                title.Text = ProductName;
                title.Font = new Font("Segoe UI", 15f);
                title.Bounds = new Rectangle(24, 22, 392, 30);
                form.Controls.Add(title);

                Label body = new Label();
                body.Text =
                    "Plays a spoken greeting when you plug the charger in, and another "
                    + "when you unplug it.\n\n"
                    + "Installs for you only — no administrator rights needed.\n"
                    + "Location:  %LOCALAPPDATA%\\Programs\\" + ShortName + "\n\n"
                    + "It works completely offline and collects nothing.";
                body.Bounds = new Rectangle(24, 60, 392, 130);
                form.Controls.Add(body);

                Button install = new Button();
                install.Text = "Install";
                install.Bounds = new Rectangle(232, 206, 92, 32);
                install.DialogResult = DialogResult.OK;
                form.Controls.Add(install);

                Button cancel = new Button();
                cancel.Text = "Cancel";
                cancel.Bounds = new Rectangle(332, 206, 92, 32);
                cancel.DialogResult = DialogResult.Cancel;
                form.Controls.Add(cancel);

                form.AcceptButton = install;
                form.CancelButton = cancel;

                return form.ShowDialog() == DialogResult.OK;
            }
        }

        private static void ExtractPayload(string targetDir)
        {
            Assembly asm = Assembly.GetExecutingAssembly();
            using (Stream stream = asm.GetManifestResourceStream("ChargerGreetings.payload.zip"))
            {
                if (stream == null)
                    throw new InvalidOperationException(
                        "This installer is missing its payload and cannot be used.");

                using (ZipArchive zip = new ZipArchive(stream, ZipArchiveMode.Read))
                {
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        string destination = Path.Combine(targetDir, entry.FullName);

                        // Guard against a crafted archive escaping the target dir.
                        // The trailing separator matters: without it, a sibling
                        // folder named "...ChargerGreetingsEvil" would pass a
                        // bare StartsWith against "...ChargerGreetings".
                        string root = Path.GetFullPath(targetDir);
                        if (!root.EndsWith(Path.DirectorySeparatorChar.ToString()))
                            root += Path.DirectorySeparatorChar;
                        string fullTarget = Path.GetFullPath(destination);
                        if (!fullTarget.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                            throw new InvalidDataException("Refusing to extract outside the install folder.");

                        if (string.IsNullOrEmpty(entry.Name))
                        {
                            Directory.CreateDirectory(fullTarget);
                            continue;
                        }

                        Directory.CreateDirectory(Path.GetDirectoryName(fullTarget));
                        entry.ExtractToFile(fullTarget, true);
                    }
                }
            }
        }

        private static void CreateStartMenuShortcut(string exePath)
        {
            try
            {
                // WScript.Shell via late binding: no COM reference needed, and it
                // is the documented way to author a .lnk from managed code.
                Type shellType = Type.GetTypeFromProgID("WScript.Shell");
                if (shellType == null) return;

                object shell = Activator.CreateInstance(shellType);
                object shortcut = shellType.InvokeMember("CreateShortcut",
                    BindingFlags.InvokeMethod, null, shell, new object[] { StartMenuShortcut });
                Type sc = shortcut.GetType();

                sc.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut,
                                new object[] { exePath });
                sc.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut,
                                new object[] { Path.GetDirectoryName(exePath) });
                sc.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut,
                                new object[] { exePath + ",0" });
                sc.InvokeMember("Description", BindingFlags.SetProperty, null, shortcut,
                                new object[] { "Spoken greetings when the charger connects or disconnects" });
                sc.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
            }
            catch
            {
                // A missing Start Menu entry is cosmetic; never fail the install for it.
            }
        }

        private static void RegisterUninstall(string dir, string exePath, string setupCopy)
        {
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                if (key == null) return;
                long sizeKb = 0;
                try
                {
                    foreach (string f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                        sizeKb += new FileInfo(f).Length;
                    sizeKb /= 1024;
                }
                catch { }

                key.SetValue("DisplayName", ProductName);
                key.SetValue("DisplayVersion", Version);
                key.SetValue("Publisher", Publisher);
                key.SetValue("DisplayIcon", exePath + ",0");
                key.SetValue("InstallLocation", dir);
                key.SetValue("UninstallString", "\"" + setupCopy + "\" /uninstall");
                key.SetValue("QuietUninstallString", "\"" + setupCopy + "\" /uninstall /silent");
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                key.SetValue("EstimatedSize", (int)sizeKb, RegistryValueKind.DWord);
                key.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
            }
        }

        // --------------------------------------------------------- uninstall
        private static int Uninstall(bool silent)
        {
            if (!silent)
            {
                DialogResult answer = MessageBox.Show(
                    "Remove " + ProductName + "?\n\n"
                    + "Your settings and log will also be deleted.",
                    ProductName, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                if (answer != DialogResult.Yes) return 2;
            }

            StopRunningApp();

            // Startup entry
            try
            {
                using (RegistryKey run = Registry.CurrentUser.OpenSubKey(
                           @"Software\Microsoft\Windows\CurrentVersion\Run", true))
                {
                    if (run != null && run.GetValue(ShortName) != null)
                        run.DeleteValue(ShortName, false);
                }
            }
            catch { }

            try { Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false); } catch { }
            try { if (File.Exists(StartMenuShortcut)) File.Delete(StartMenuShortcut); } catch { }

            // Per-user data
            try
            {
                string data = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    ShortName);
                if (Directory.Exists(data)) Directory.Delete(data, true);
            }
            catch { }

            // Program files. Uninstall.exe is running from this folder, so it is
            // removed by a detached command after this process exits.
            string dir = InstallDir;
            try
            {
                foreach (string f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
                {
                    if (string.Equals(Path.GetFileName(f), "Uninstall.exe",
                                      StringComparison.OrdinalIgnoreCase)) continue;
                    try { File.Delete(f); } catch { }
                }
                foreach (string d in Directory.GetDirectories(dir))
                {
                    try { Directory.Delete(d, true); } catch { }
                }
            }
            catch { }

            ScheduleSelfDelete(dir);

            if (!silent)
                MessageBox.Show(ProductName + " has been removed.",
                    ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 0;
        }

        /// <summary>
        /// Removes the install folder once this process has exited. cmd.exe is
        /// used only here, only against our own folder, and with no elevation.
        /// </summary>
        private static void ScheduleSelfDelete(string dir)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.System), "cmd.exe");
                // ping, not timeout: `timeout` needs a real console and exits
                // immediately with "Input redirection is not supported" when
                // started with CreateNoWindow, which would fire rmdir while this
                // process still has Uninstall.exe locked. ping just waits.
                psi.Arguments = "/c ping -n 4 127.0.0.1 >nul & rmdir /s /q \"" + dir + "\"";
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                Process.Start(psi);
            }
            catch { }
        }

        // ------------------------------------------------------------- utils
        private static void StopRunningApp()
        {
            try
            {
                foreach (Process p in Process.GetProcessesByName(ShortName))
                {
                    try
                    {
                        p.CloseMainWindow();
                        if (!p.WaitForExit(2000)) p.Kill();
                        p.WaitForExit(3000);
                    }
                    catch { }
                }
                // Give the shell a moment to release the tray icon and file locks.
                Thread.Sleep(400);
            }
            catch { }
        }

        private static bool HasSwitch(string[] args, string name)
        {
            if (args == null) return false;
            foreach (string a in args)
                if (string.Equals(a, name, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }
    }
}
