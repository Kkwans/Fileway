# Installer helper: only current-user processes located inside the selected installation root.
param([Parameter(Mandatory)][string] $InstallRoot, [switch] $RequestClose)
$ErrorActionPreference = 'Stop'
try {
    $root = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\') + '\'
    if ($root.Length -le 3) { exit 11 }
    $session = [Diagnostics.Process]::GetCurrentProcess().SessionId
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    function Get-OwnedProcesses {
        $candidates = Get-CimInstance Win32_Process -Filter "Name='Fileway.App.exe' OR Name='fileway-host.exe'"
        foreach ($candidate in $candidates) {
            if ($candidate.SessionId -ne $session -or -not $candidate.ExecutablePath) { continue }
            $path = [IO.Path]::GetFullPath($candidate.ExecutablePath)
            if (-not $path.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { continue }
            $owner = Invoke-CimMethod -InputObject $candidate -MethodName GetOwnerSid
            if ($owner.ReturnValue -eq 0 -and $owner.Sid -eq $sid) { $candidate }
        }
    }
    $running = @(Get-OwnedProcesses)
    if ($running.Count -eq 0) { exit 0 }
    if (-not $RequestClose) { exit 10 }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class FilewayInstallerClose {
    private delegate bool EnumProc(IntPtr window, IntPtr state);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc callback, IntPtr state);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetWindowTextW(IntPtr window, StringBuilder text, int count);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern uint RegisterWindowMessageW(string name);
    [DllImport("user32.dll")] private static extern bool PostMessageW(IntPtr window, uint message, UIntPtr wparam, IntPtr lparam);
    public static void Request(uint process) {
        uint exitMessage = RegisterWindowMessageW("Fileway.Preview.RequestExit.v1");
        EnumProc callback = (window, state) => {
            uint observed; GetWindowThreadProcessId(window, out observed);
            if (observed != process) return true;
            var title = new StringBuilder(256); GetWindowTextW(window, title, title.Capacity);
            // Includes hidden main windows; never close unrelated media/other application windows.
            if (title.ToString() == "\u6816\u5377" || title.ToString() == "Fileway") {
                if (exitMessage != 0) PostMessageW(window, exitMessage, UIntPtr.Zero, IntPtr.Zero);
                PostMessageW(window, 0x0010, UIntPtr.Zero, IntPtr.Zero); // Older versions use WM_CLOSE.
            }
            return true;
        };
        EnumWindows(callback, IntPtr.Zero);
        GC.KeepAlive(callback);
    }
}
'@
    foreach ($app in $running | Where-Object Name -eq 'Fileway.App.exe') {
        # Re-observe identity immediately before posting; a recycled PID is not authority.
        $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($app.ProcessId)"
        if ($current -and $current.CreationDate -eq $app.CreationDate -and $current.ExecutablePath -eq $app.ExecutablePath) {
            [FilewayInstallerClose]::Request([uint32]$app.ProcessId)
        }
    }
    $deadline = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Milliseconds 400
        if (@(Get-OwnedProcesses).Count -eq 0) { exit 0 }
    } while ($deadline.Elapsed.TotalSeconds -lt 30)
    # An unresponsive app or orphan core is reported, never force-killed.
    exit 12
} catch { exit 11 }
