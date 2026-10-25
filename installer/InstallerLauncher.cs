using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

// IExpress 解压后启动的安装器入口。
// IExpress 直接运行 .bat/.cmd 会调用不存在的 command.com，因此这里使用
// 一个极小的 WinExe 来启动同目录的 install.ps1。
static class Program
{
    [STAThread]
    static void Main()
    {
        try
        {
            string dir = AppDomain.CurrentDomain.BaseDirectory;
            string script = Path.Combine(dir, "install.ps1");
            if (!File.Exists(script))
            {
                throw new FileNotFoundException(
                    "Installer payload is incomplete: install.ps1 is missing.", script);
            }

            string powershell = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.System),
                @"WindowsPowerShell\v1.0\powershell.exe");
            if (!File.Exists(powershell))
            {
                powershell = "powershell.exe";
            }

            string arguments = "-NoProfile -NoLogo -ExecutionPolicy Bypass -File \"" + script + "\"";
            ProcessStartInfo psi = new ProcessStartInfo(powershell, arguments);
            psi.WorkingDirectory = dir;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;

            using (Process process = Process.Start(psi))
            {
                if (process == null)
                {
                    throw new InvalidOperationException("Unable to start PowerShell.");
                }
                process.WaitForExit();
                Environment.ExitCode = process.ExitCode;
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                ex.Message,
                "UniVM Setup",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            Environment.ExitCode = 1;
        }
    }
}
