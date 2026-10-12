#requires -Version 7.2
[CmdletBinding()]
param(
    [string] $AppPath,
    [string] $MediaPath,
    [Parameter(Mandatory)][string] $WinAppPath,
    [Parameter(Mandatory)][string] $OutputRoot,
    [switch] $PreflightOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Commands and JSON shapes are pinned to the actual v0.7.1 documentation:
# https://github.com/microsoft/winappCli/blob/v0.7.1/docs/ui-automation.md
# https://github.com/microsoft/winappCli/blob/v0.7.1/plugins/winapp/skills/winapp-ui-automation/references/ui-json-envelope.md
# Raw CLI output, UIA trees, application stdout, paths and exception messages are
# deliberately not retained. The output directory is a fixed PNG/JSON allowlist.
$allowedArtifacts = @('report.json', 'events.jsonl', 'preflight.png', 'preflight-smoke.png',
    'moving-1.png', 'moving-2.png', 'settings-1.png', 'settings-2.png', 'settings-3.png',
    'paused-1.png', 'paused-2.png', 'resumed-1.png', 'resumed-2.png', 'fullscreen.png', 'windowed.png',
    'player-responsiveness.json')
$script:root = $null
$script:process = $null
$script:probe = $null
$script:privateRoot = $null
$script:fixtureStream = $null
$script:hosts = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$script:stage = 'output-validation'
$script:completed = [System.Collections.Generic.List[string]]::new()
$script:commands = [System.Collections.Generic.List[object]]::new()
$script:clock = [System.Diagnostics.Stopwatch]::StartNew()
$script:checks = [ordered]@{ interactiveDesktop = $false; uia = $false; capture = $false; actualVideoPixels = $false }
$script:oldWorkflow = $env:WINAPP_UI_WORKFLOW_ID
$script:oldTelemetry = $env:WINAPP_CLI_TELEMETRY_OPTOUT

function Stop-Smoke([string] $Code, [switch] $EnvironmentBlocked) {
    $errorObject = [System.InvalidOperationException]::new($Code)
    $errorObject.Data['smokeCode'] = $Code
    $errorObject.Data['smokeStatus'] = $(if ($EnvironmentBlocked) { 'environment-blocked' } else { 'failed' })
    throw $errorObject
}

function Assert-ExternalPath([string] $Value) {
    if (-not [System.IO.Path]::IsPathFullyQualified($Value)) { Stop-Smoke 'absolute_path_required' }
    $path = [System.IO.Path]::GetFullPath($Value)
    $item = [System.IO.DirectoryInfo]::new($path)
    while ($null -ne $item) {
        if ($item.Exists -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { Stop-Smoke 'reparse_path_rejected' }
        $marker = Join-Path $item.FullName '.git'
        if ([System.IO.File]::Exists($marker) -or [System.IO.File]::Exists((Join-Path $marker 'HEAD'))) { Stop-Smoke 'private_output_inside_git' }
        $item = $item.Parent
    }
    return $path
}

function Artifact-Path([string] $Name) {
    if ($Name -notin $allowedArtifacts) { Stop-Smoke 'artifact_name_not_allowed' }
    if ($Name.EndsWith('.png', [StringComparison]::Ordinal)) { return Join-Path (Join-Path $script:root 'screenshots') $Name }
    return Join-Path $script:root $Name
}

function Write-Json([string] $Name, $Value) {
    $text = $Value | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText((Artifact-Path $Name), $text + "`n", [System.Text.UTF8Encoding]::new($false))
}

function Complete-Step([string] $Name, $Details = @{}) {
    $script:completed.Add($Name)
    $entry = [ordered]@{ kind = 'step'; step = $Name; elapsedMilliseconds = $script:clock.ElapsedMilliseconds; status = 'passed'; details = $Details }
    [System.IO.File]::AppendAllText((Artifact-Path 'events.jsonl'), (($entry | ConvertTo-Json -Depth 4 -Compress) + "`n"), [System.Text.UTF8Encoding]::new($false))
}

function Record-Observation([string] $Name, $Details) {
    $entry = [ordered]@{ kind = 'observation'; observation = $Name; elapsedMilliseconds = $script:clock.ElapsedMilliseconds; details = $Details }
    [System.IO.File]::AppendAllText((Artifact-Path 'events.jsonl'), (($entry | ConvertTo-Json -Depth 4 -Compress) + "`n"), [System.Text.UTF8Encoding]::new($false))
}

function Invoke-WinApp([string[]] $Arguments, [switch] $PlainText) {
    $start = [System.Diagnostics.ProcessStartInfo]::new($WinAppPath)
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $command = [System.Diagnostics.Process]::Start($start)
    try {
        $stdout = $command.StandardOutput.ReadToEndAsync()
        $stderr = $command.StandardError.ReadToEndAsync()
        if (-not $command.WaitForExit(15000)) {
            $command.Kill($true)
            Stop-Smoke 'winapp_command_timeout'
        }
        $raw = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        $code = 'ok'
        if ($command.ExitCode -ne 0) {
            $code = 'winapp_command_failed'
            try {
                $envelope = $errorText | ConvertFrom-Json -AsHashtable
                if ($envelope.error.code -match '^[a-z_]{1,64}$') { $code = $envelope.error.code }
            } catch { }
        }
        $verb = if ($Arguments[0] -eq 'ui' -and $Arguments.Count -gt 1) { $Arguments[1] } else { 'version' }
        $entry = [ordered]@{ kind = 'command'; stage = $script:stage; verb = $verb; durationMilliseconds = $watch.ElapsedMilliseconds; exitCode = $command.ExitCode; code = $code }
        $script:commands.Add($entry)
        [System.IO.File]::AppendAllText((Artifact-Path 'events.jsonl'), (($entry | ConvertTo-Json -Compress) + "`n"), [System.Text.UTF8Encoding]::new($false))
        if ($command.ExitCode -ne 0) {
            $environmentCodes = @('no_interactive_desktop', 'foreground_not_target', 'desktop_coordination_unavailable', 'capture_failed', 'no_target')
            Stop-Smoke $code -EnvironmentBlocked:($code -in $environmentCodes -or $script:stage -eq 'preflight')
        }
        if ($raw.Length -gt 1048576 -or $errorText.Length -gt 1048576) { Stop-Smoke 'winapp_output_too_large' }
        if ($PlainText) { return $raw }
        try { return ($raw | ConvertFrom-Json -AsHashtable) } catch { Stop-Smoke 'winapp_json_contract_mismatch' }
    }
    finally { $command.Dispose() }
}

function Wait-Condition([scriptblock] $Predicate, [string] $Code, [int] $TimeoutMilliseconds = 8000) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.ElapsedMilliseconds -lt $TimeoutMilliseconds) {
        $value = & $Predicate
        if ($value) { return $value }
        if ($script:process -and $script:process.HasExited) { Stop-Smoke 'app_exited_early' }
        Start-Sleep -Milliseconds 100
    }
    Stop-Smoke $Code
}

# In-memory P/Invoke helper, PNG encoding and pixel decoder. No extra assembly,
# installed capture package, persistent desktop hook, or application patch.
$nativeSource = @'
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace Fileway.PlayerSmoke {
    [StructLayout(LayoutKind.Sequential)] public struct Bounds {
        public int Left, Top, Right, Bottom;
        public int Width { get { return Right - Left; } }
        public int Height { get { return Bottom - Top; } }
    }
    [StructLayout(LayoutKind.Sequential)] struct BitmapInfo {
        public uint Size; public int Width, Height; public ushort Planes, BitCount;
        public uint Compression, SizeImage; public int XPels, YPels; public uint Used, Important;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct WindowClass {
        public uint Style; public IntPtr Procedure; public int ClassExtra, WindowExtra;
        public IntPtr Instance, Icon, Cursor, Background;
        public string MenuName, ClassName;
    }
    [StructLayout(LayoutKind.Sequential)] struct Message {
        public IntPtr Window; public uint Id; public UIntPtr WParam; public IntPtr LParam;
        public uint Time; public int X, Y; public uint Private;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct ProcessEntry {
        public uint Size,Usage,ProcessId; public UIntPtr DefaultHeap; public uint ModuleId,Threads,ParentProcessId;
        public int Priority; public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)] public string Executable;
    }
    delegate bool EnumProc(IntPtr window, IntPtr argument);
    delegate IntPtr WindowProc(IntPtr window, uint message, UIntPtr wParam, IntPtr lParam);

    public sealed class Image {
        public readonly int Width, Height; public readonly byte[] Pixels;
        public Image(int width, int height, byte[] pixels) {
            if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long)width * height > 16777216 || pixels.Length != width * height * 4)
                throw new InvalidOperationException("capture_bounds_invalid");
            Width=width; Height=height; Pixels=pixels;
        }
        bool Color(double x, double y, int r, int g, int b, int tolerance) {
            int px=Math.Min(Width-1, Math.Max(0, (int)x)), py=Math.Min(Height-1, Math.Max(0, (int)y));
            int offset=(py*Width+px)*4;
            return Math.Abs(Pixels[offset+2]-r)<=tolerance && Math.Abs(Pixels[offset+1]-g)<=tolerance && Math.Abs(Pixels[offset]-b)<=tolerance;
        }
        public int DecodeFrame(int frameCount) {
            double scale=Math.Min(Width/160.0, Height/90.0);
            double x=(Width-160*scale)/2, y=(Height-90*scale)/2;
            if (scale<2 || !Color(x+20*scale,y+37*scale,240,48,48,48)
                || !Color(x+140*scale,y+37*scale,48,232,72,48)
                || !Color(x+20*scale,y+52*scale,56,80,240,48)
                || !Color(x+140*scale,y+52*scale,240,224,48,48)) return -1;
            int counter=0;
            for(int bit=0;bit<16;bit++) {
                double sx=x+(50+bit*4)*scale, sy=y+45*scale;
                if(Color(sx,sy,240,240,240,48)) counter|=1<<bit;
                else if(!Color(sx,sy,24,24,24,32)) return -1;
            }
            if(counter>=frameCount) return -1;
            // Hue, not brightness: the HUD gradient may dim the lower marker.
            int mx=Math.Min(Width-1,(int)(x+(16+counter%128)*scale)), my=Math.Min(Height-1,(int)(y+65*scale));
            int offset=(my*Width+mx)*4;
            if(Pixels[offset+2]-Pixels[offset+1]<55 || Pixels[offset]-Pixels[offset+1]<55) return -1;
            return counter;
        }
        static void BigEndian(Stream stream, uint value) {
            stream.WriteByte((byte)(value>>24)); stream.WriteByte((byte)(value>>16)); stream.WriteByte((byte)(value>>8)); stream.WriteByte((byte)value);
        }
        static void PngChunk(Stream stream, string type, byte[] bytes) {
            BigEndian(stream,(uint)bytes.Length);
            byte[] kind=Encoding.ASCII.GetBytes(type); stream.Write(kind,0,4); stream.Write(bytes,0,bytes.Length);
            uint crc=0xffffffff;
            foreach(byte value in kind) { crc^=value; for(int i=0;i<8;i++) crc=(crc>>1)^((crc&1)!=0?0xedb88320u:0); }
            foreach(byte value in bytes) { crc^=value; for(int i=0;i<8;i++) crc=(crc>>1)^((crc&1)!=0?0xedb88320u:0); }
            BigEndian(stream,crc^0xffffffff);
        }
        public void SavePng(string path) {
            using(var output=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.None)) {
                output.Write(new byte[]{137,80,78,71,13,10,26,10},0,8);
                using(var header=new MemoryStream()) {
                    BigEndian(header,(uint)Width); BigEndian(header,(uint)Height);
                    header.Write(new byte[]{8,2,0,0,0},0,5); PngChunk(output,"IHDR",header.ToArray());
                }
                using(var compressed=new MemoryStream()) {
                    using(var zlib=new ZLibStream(compressed,CompressionLevel.Fastest,true)) {
                        byte[] row=new byte[1+Width*3];
                        for(int y=0;y<Height;y++) {
                            for(int x=0;x<Width;x++) { int src=(y*Width+x)*4, dst=1+x*3; row[dst]=Pixels[src+2]; row[dst+1]=Pixels[src+1]; row[dst+2]=Pixels[src]; }
                            zlib.Write(row,0,row.Length);
                        }
                    }
                    PngChunk(output,"IDAT",compressed.ToArray());
                }
                PngChunk(output,"IEND",Array.Empty<byte>());
            }
        }
    }

    public static class Native {
        [DllImport("user32.dll")] static extern IntPtr OpenInputDesktop(uint flags,bool inherit,uint access);
        [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
        [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern bool GetUserObjectInformationW(IntPtr handle,int index,StringBuilder value,uint length,out uint needed);
        [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback,IntPtr argument);
        [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr window);
        [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window,out uint process);
        [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr window,uint command);
        [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr window,uint flags);
        [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window,out Bounds rectangle);
        [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowTextW(IntPtr window,StringBuilder text,int size);
        [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowExW(IntPtr parent,IntPtr after,string className,string title);
        [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr window);
        [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr window,IntPtr dc);
        [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleDC(IntPtr dc);
        [DllImport("gdi32.dll")] static extern IntPtr CreateDIBSection(IntPtr dc,ref BitmapInfo info,uint usage,out IntPtr bits,IntPtr section,uint offset);
        [DllImport("gdi32.dll")] static extern IntPtr SelectObject(IntPtr dc,IntPtr item);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr item);
        [DllImport("gdi32.dll")] static extern bool DeleteDC(IntPtr dc);
        [DllImport("gdi32.dll")] static extern bool BitBlt(IntPtr dc,int x,int y,int width,int height,IntPtr source,int sourceX,int sourceY,uint operation);
        [DllImport("gdi32.dll")] static extern bool GdiFlush();
        [DllImport("dwmapi.dll")] static extern int DwmFlush();
        [DllImport("user32.dll")] public static extern bool PostMessageW(IntPtr window,uint message,UIntPtr wParam,IntPtr lParam);
        [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr window,uint flags);
        [StructLayout(LayoutKind.Sequential)] struct MonitorInfo { public uint Size; public Bounds Monitor,Work; public uint Flags; }
        [DllImport("user32.dll")] static extern bool GetMonitorInfoW(IntPtr monitor,ref MonitorInfo info);
        [DllImport("kernel32.dll")] static extern IntPtr CreateToolhelp32Snapshot(uint flags,uint process);
        [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern bool Process32FirstW(IntPtr snapshot,ref ProcessEntry entry);
        [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern bool Process32NextW(IntPtr snapshot,ref ProcessEntry entry);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

        public static string DesktopBlocker() {
            if(!Environment.UserInteractive || System.Diagnostics.Process.GetCurrentProcess().SessionId==0) return "noninteractive_session";
            IntPtr desktop=OpenInputDesktop(0,false,1);
            if(desktop==IntPtr.Zero) return "input_desktop_unavailable";
            try {
                var name=new StringBuilder(256); uint needed;
                if(!GetUserObjectInformationW(desktop,2,name,512,out needed)) return "input_desktop_query_failed";
                return name.ToString().Equals("Default",StringComparison.OrdinalIgnoreCase)?null:"locked_or_secure_desktop";
            } finally { CloseDesktop(desktop); }
        }
        public static uint ProcessId(IntPtr window) { uint process; GetWindowThreadProcessId(window,out process); return process; }
        public static int[] ChildHosts(int parent) {
            IntPtr snapshot=CreateToolhelp32Snapshot(2,0);
            if(snapshot==new IntPtr(-1)) throw new InvalidOperationException("process_snapshot_unavailable");
            try {
                var result=new List<int>(); var entry=new ProcessEntry { Size=(uint)Marshal.SizeOf<ProcessEntry>() };
                if(!Process32FirstW(snapshot,ref entry)) throw new InvalidOperationException("process_snapshot_unavailable");
                do { if(entry.ParentProcessId==parent&&entry.Executable.Equals("fileway-host.exe",StringComparison.OrdinalIgnoreCase)) result.Add((int)entry.ProcessId); }
                while(Process32NextW(snapshot,ref entry));
                return result.ToArray();
            } finally { CloseHandle(snapshot); }
        }
        public static IntPtr[] Windows(int process) {
            var windows=new List<IntPtr>();
            EnumWindows((window,argument)=>{ if(ProcessId(window)==process && IsWindowVisible(window)) windows.Add(window); return true; },IntPtr.Zero);
            return windows.ToArray();
        }
        public static string Title(IntPtr window) { var text=new StringBuilder(512); GetWindowTextW(window,text,512); return text.ToString(); }
        public static Bounds Rectangle(IntPtr window) {
            Bounds value; if(!GetWindowRect(window,out value)||value.Width<1||value.Height<1) throw new InvalidOperationException("window_bounds_unavailable"); return value;
        }
        public static IntPtr Player(int process) {
            IntPtr found=IntPtr.Zero;
            foreach(var window in Windows(process)) if(FindWindowExW(window,IntPtr.Zero,"STATIC","Fileway video")!=IntPtr.Zero) {
                if(found!=IntPtr.Zero) throw new InvalidOperationException("player_window_ambiguous"); found=window;
            }
            return found;
        }
        public static IntPtr Surface(int process,string suffix) {
            IntPtr found=IntPtr.Zero;
            foreach(var window in Windows(process)) if(Title(window).EndsWith(suffix,StringComparison.OrdinalIgnoreCase)) {
                if(found!=IntPtr.Zero) throw new InvalidOperationException("surface_window_ambiguous"); found=window;
            }
            return found;
        }
        public static bool InFamily(IntPtr foreground,IntPtr owner) {
            for(int i=0;i<32 && foreground!=IntPtr.Zero;i++) {
                IntPtr root=GetAncestor(foreground,2);
                if(foreground==owner||root==owner) return true;
                foreground=GetWindow(root==IntPtr.Zero?foreground:root,4);
            }
            return false;
        }
        public static void AssertCaptureSafe(IntPtr target,Bounds rectangle) {
            string blocker=DesktopBlocker(); if(blocker!=null) throw new InvalidOperationException(blocker);
            if(!InFamily(GetForegroundWindow(),target)) throw new InvalidOperationException("foreground_outside_test_app");
            uint process=ProcessId(target); bool obscured=false;
            // EnumWindows visits top-level windows in z-order. Never retain a
            // screen region obscured by another process, even a topmost prompt.
            EnumWindows((window,argument)=> {
                if(window==target) return false;
                Bounds other;
                if(IsWindowVisible(window)&&ProcessId(window)!=process&&GetWindowRect(window,out other)
                    &&other.Left<rectangle.Right&&other.Right>rectangle.Left&&other.Top<rectangle.Bottom&&other.Bottom>rectangle.Top) { obscured=true; return false; }
                return true;
            },IntPtr.Zero);
            if(obscured) throw new InvalidOperationException("foreign_window_obscures_capture");
        }
        public static Image Capture(IntPtr target) {
            Bounds rectangle=Rectangle(target); AssertCaptureSafe(target,rectangle);
            int width=rectangle.Width,height=rectangle.Height;
            if(width>8192||height>8192||(long)width*height>16777216) throw new InvalidOperationException("capture_bounds_invalid");
            IntPtr screen=GetDC(IntPtr.Zero),memory=IntPtr.Zero,bitmap=IntPtr.Zero,previous=IntPtr.Zero;
            try {
                if(screen==IntPtr.Zero) throw new InvalidOperationException("screen_dc_unavailable");
                memory=CreateCompatibleDC(screen); var info=new BitmapInfo { Size=40,Width=width,Height=-height,Planes=1,BitCount=32 };
                IntPtr bits; bitmap=CreateDIBSection(screen,ref info,0,out bits,IntPtr.Zero,0);
                if(memory==IntPtr.Zero||bitmap==IntPtr.Zero||bits==IntPtr.Zero) throw new InvalidOperationException("capture_surface_unavailable");
                previous=SelectObject(memory,bitmap); DwmFlush();
                if(!BitBlt(memory,0,0,width,height,screen,rectangle.Left,rectangle.Top,0x40cc0020)) throw new InvalidOperationException("screen_capture_failed");
                GdiFlush(); var pixels=new byte[checked(width*height*4)]; Marshal.Copy(bits,pixels,0,pixels.Length);
                AssertCaptureSafe(target,rectangle);
                return new Image(width,height,pixels);
            } finally {
                if(previous!=IntPtr.Zero) SelectObject(memory,previous);
                if(bitmap!=IntPtr.Zero) DeleteObject(bitmap);
                if(memory!=IntPtr.Zero) DeleteDC(memory);
                if(screen!=IntPtr.Zero) ReleaseDC(IntPtr.Zero,screen);
            }
        }
        public static bool IsFullscreen(IntPtr window) {
            var info=new MonitorInfo { Size=(uint)Marshal.SizeOf<MonitorInfo>() };
            if(!GetMonitorInfoW(MonitorFromWindow(window,2),ref info)) throw new InvalidOperationException("monitor_bounds_unavailable");
            Bounds bounds=Rectangle(window);
            return Math.Abs(bounds.Left-info.Monitor.Left)<=1&&Math.Abs(bounds.Top-info.Monitor.Top)<=1
                &&Math.Abs(bounds.Right-info.Monitor.Right)<=1&&Math.Abs(bounds.Bottom-info.Monitor.Bottom)<=1;
        }
    }

    public sealed class Probe : IDisposable {
        readonly ManualResetEvent ready=new ManualResetEvent(false); readonly Thread thread;
        readonly WindowProc procedure; public IntPtr Window { get; private set; }
        [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern ushort RegisterClassW(ref WindowClass windowClass);
        [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr CreateWindowExW(uint extended,string className,string title,uint style,int x,int y,int width,int height,IntPtr parent,IntPtr menu,IntPtr instance,IntPtr parameter);
        [DllImport("user32.dll")] static extern IntPtr DefWindowProcW(IntPtr window,uint message,UIntPtr wParam,IntPtr lParam);
        [DllImport("user32.dll")] static extern int GetMessageW(out Message message,IntPtr window,uint minimum,uint maximum);
        [DllImport("user32.dll")] static extern bool TranslateMessage(ref Message message);
        [DllImport("user32.dll")] static extern IntPtr DispatchMessageW(ref Message message);
        [DllImport("user32.dll")] static extern void PostQuitMessage(int code);
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
        [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr window);
        [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr window,IntPtr dc);
        [DllImport("user32.dll")] static extern int FillRect(IntPtr dc,ref Bounds bounds,IntPtr brush);
        [DllImport("user32.dll")] static extern bool ValidateRect(IntPtr window,IntPtr rectangle);
        [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern IntPtr GetModuleHandleW(string name);
        [DllImport("gdi32.dll")] static extern IntPtr CreateSolidBrush(uint color);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr item);
        public Probe() {
            procedure=Procedure; thread=new Thread(Run); thread.IsBackground=true; thread.SetApartmentState(ApartmentState.STA); thread.Start();
            if(!ready.WaitOne(5000)||Window==IntPtr.Zero) throw new InvalidOperationException("desktop_probe_unavailable");
        }
        void Run() {
            try {
                string name="FilewaySmokeProbe"+Guid.NewGuid().ToString("N"); IntPtr instance=GetModuleHandleW(null);
                var windowClass=new WindowClass { Procedure=Marshal.GetFunctionPointerForDelegate(procedure),ClassName=name,Instance=instance };
                if(RegisterClassW(ref windowClass)==0) { ready.Set(); return; }
                Window=CreateWindowExW(0x88,name,"Fileway synthetic capture preflight",0x90000000,32,32,144,96,IntPtr.Zero,IntPtr.Zero,instance,IntPtr.Zero);
                if(Window!=IntPtr.Zero) { Paint(Window); SetForegroundWindow(Window); }
                ready.Set(); Message message;
                if(Window!=IntPtr.Zero) while(GetMessageW(out message,IntPtr.Zero,0,0)>0) { TranslateMessage(ref message); DispatchMessageW(ref message); }
            } catch { Window=IntPtr.Zero; ready.Set(); }
        }
        void Paint(IntPtr window) {
            IntPtr dc=GetDC(window);
            try { uint[] colors={0x3030f0,0x48e830,0xf05038};
                for(int i=0;i<3;i++) { var rectangle=new Bounds { Left=i*48,Top=0,Right=(i+1)*48,Bottom=96 }; IntPtr brush=CreateSolidBrush(colors[i]);
                    try { FillRect(dc,ref rectangle,brush); } finally { DeleteObject(brush); }
                }
            } finally { ReleaseDC(window,dc); ValidateRect(window,IntPtr.Zero); }
        }
        IntPtr Procedure(IntPtr window,uint message,UIntPtr wParam,IntPtr lParam) {
            if(message==0x0f) { Paint(window); return IntPtr.Zero; }
            if(message==0x14) return new IntPtr(1);
            if(message==2) { PostQuitMessage(0); return IntPtr.Zero; }
            return DefWindowProcW(window,message,wParam,lParam);
        }
        public void Dispose() { if(Window!=IntPtr.Zero) Native.PostMessageW(Window,0x10,UIntPtr.Zero,IntPtr.Zero); thread.Join(3000); ready.Dispose(); }
    }
}
'@

function Get-Surface([string] $Role) {
    $processId = $script:process.Id
    if ($Role -eq 'player') { return [Fileway.PlayerSmoke.Native]::Player($processId) }
    $suffixes = if ($Role -eq 'settings') { @(' 播放设置', ' playback settings') } else { @(' 播放控制', ' playback controls') }
    foreach ($suffix in $suffixes) {
        $window = [Fileway.PlayerSmoke.Native]::Surface($processId, $suffix)
        if ($window -ne [IntPtr]::Zero) { return $window }
    }
    return [IntPtr]::Zero
}

function Find-Elements($Value) {
    if ($Value -is [System.Collections.IDictionary]) {
        if ($Value.Contains('selector') -and $Value.Contains('type')) { $Value }
        foreach ($child in $Value.Values) { Find-Elements $child }
    } elseif ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        foreach ($child in $Value) { Find-Elements $child }
    }
}

function Find-Control([IntPtr] $Window, [string] $NamePattern, [string] $Type = 'Button', [switch] $Optional) {
    $tree = Invoke-WinApp @('ui', 'inspect', '-w', $Window.ToInt64().ToString(), '--depth', '12', '--json')
    if (-not $tree.Contains('windows')) { Stop-Smoke 'inspect_windows_contract_missing' }
    $elements = @(Find-Elements $tree | Where-Object { $_.type -eq $Type -and $_.name -match $NamePattern -and $_.isEnabled -and -not $_.isOffscreen })
    if ($elements.Count -eq 0 -and $Optional) { return $null }
    if ($elements.Count -ne 1) { Stop-Smoke 'control_missing_or_ambiguous' }
    return $elements[0]
}

function Wait-Control([IntPtr] $Window, [string] $NamePattern, [string] $Type = 'Button') {
    return Wait-Condition { Find-Control $Window $NamePattern $Type -Optional } 'expected_control_state_not_observed'
}

function Invoke-Control([IntPtr] $Window, [string] $NamePattern) {
    $element = Wait-Control $Window $NamePattern
    $result = Invoke-WinApp @('ui', 'invoke', $element.selector, '-w', $Window.ToInt64().ToString(), '--action', 'invoke', '--json')
    if ($result.performedAction -ne 'invoke' -or $result.hwnd -ne $Window.ToInt64()) { Stop-Smoke 'invoke_effect_contract_mismatch' }
}

function Send-Key([IntPtr] $Window, [string] $Key) {
    $null = Invoke-WinApp @('ui', 'send-keys', $Key, '-w', $Window.ToInt64().ToString(), '--via', 'send-input', '--json')
}

function Show-Hud([IntPtr] $Player) {
    Send-Key $Player 'f10'
    $null = Wait-Condition { $window = Get-Surface 'hud'; if ($window -ne [IntPtr]::Zero) { $window } } 'hud_not_visible'
}

function Capture-Frame([IntPtr] $Player, [int] $FrameCount) {
    $image = [Fileway.PlayerSmoke.Native]::Capture($Player)
    $number = $image.DecodeFrame($FrameCount)
    if ($number -lt 0) { return $null }
    return @{ image = $image; frame = $number }
}

function Save-Frame([IntPtr] $Player, [int] $FrameCount, [string] $Name) {
    $frame = Wait-Condition { Capture-Frame $Player $FrameCount } 'actual_video_pixels_not_decodable' 5000
    $frame.image.SavePng((Artifact-Path $Name))
    return $frame.frame
}

function Validate-Fixture([string] $Path) {
    $path = Assert-ExternalPath $Path
    if ([System.IO.Path]::GetExtension($path) -ine '.avi') { Stop-Smoke 'synthetic_avi_required' }
    $manifestPath = "$path.fixture.json"
    if (-not [System.IO.File]::Exists($path) -or -not [System.IO.File]::Exists($manifestPath)) { Stop-Smoke 'synthetic_fixture_manifest_missing' }
    if ((Get-Item -LiteralPath $path).Attributes -band [System.IO.FileAttributes]::ReparsePoint) { Stop-Smoke 'reparse_fixture_rejected' }
    if ((Get-Item -LiteralPath $manifestPath).Attributes -band [System.IO.FileAttributes]::ReparsePoint) { Stop-Smoke 'reparse_manifest_rejected' }
    if ((Get-Item -LiteralPath $manifestPath).Length -gt 4096) { Stop-Smoke 'fixture_manifest_too_large' }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json -AsHashtable
    $keys = @('schemaVersion', 'fixtureId', 'width', 'height', 'framesPerSecond', 'frameCount', 'byteLength', 'sha256')
    if ($manifest.Count -ne $keys.Count -or @($manifest.Keys | Where-Object { $_ -notin $keys }).Count -ne 0) { Stop-Smoke 'fixture_manifest_schema_mismatch' }
    # A caller-authored manifest alone is not provenance. Pin the deterministic
    # 180-second byte hash as well, so arbitrary local media cannot be admitted.
    if ($manifest.schemaVersion -ne 1 -or $manifest.fixtureId -ne 'fileway-player-smoke-v1' -or $manifest.width -ne 160 -or $manifest.height -ne 90 -or $manifest.framesPerSecond -ne 12 -or
        $manifest.frameCount -ne 2160 -or $manifest.byteLength -ne 93364072 -or
        $manifest.sha256 -cne '6549098053b18f0b25d8aad3119b37464f3e955cc1b12c553671555bbcc4593e') { Stop-Smoke 'fixture_contract_mismatch' }
    # Keep a read-share-only handle open through playback: another writer cannot
    # replace verified bytes between hashing and the native engine's open.
    $script:fixtureStream = [System.IO.FileStream]::new($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    if ((Get-Item -LiteralPath $path).Length -ne $manifest.byteLength -or (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash -ine $manifest.sha256) { Stop-Smoke 'fixture_hash_mismatch' }
    return @{ path = $path; frameCount = [int]$manifest.frameCount }
}

function Copy-SafeDiagnostics {
    $path = Join-Path $script:privateRoot 'diagnostics\player-responsiveness.json'
    if (-not [System.IO.File]::Exists($path)) { return $false }
    if ((Get-Item -LiteralPath $path).Length -gt 262144) { Stop-Smoke 'diagnostic_report_too_large' }
    $report = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json -AsHashtable
    $allowed = @('Format', 'EventsRecorded', 'ContendedWrites', 'HitTests', 'SnapshotApplications', 'ProbePending', 'ProbeAgeMicroseconds', 'Entries')
    if ($report.Count -ne $allowed.Count -or @($report.Keys | Where-Object { $_ -notin $allowed }).Count -ne 0 -or $report.Format -ne 1 -or $report.Entries.Count -gt 512) { Stop-Smoke 'diagnostic_schema_mismatch' }
    foreach ($key in $allowed | Where-Object { $_ -ne 'Entries' }) {
        if ($report[$key] -isnot [long] -and $report[$key] -isnot [int] -and $report[$key] -isnot [bool]) { Stop-Smoke 'diagnostic_nonnumeric_data' }
    }
    foreach ($entry in $report.Entries) {
        if ($entry.Count -ne 4 -or @($entry.Keys | Where-Object { $_ -notin @('Sequence', 'ElapsedMicroseconds', 'Event', 'Value') }).Count -ne 0) { Stop-Smoke 'diagnostic_entry_schema_mismatch' }
        foreach ($value in $entry.Values) { if ($value -isnot [long] -and $value -isnot [int]) { Stop-Smoke 'diagnostic_nonnumeric_entry' } }
    }
    Write-Json 'player-responsiveness.json' $report
    return $true
}

$exitCode = 1
try {
    $candidateRoot = Assert-ExternalPath $OutputRoot
    $reusePreflight = $false
    if (Test-Path -LiteralPath $candidateRoot) {
        if (-not (Test-Path -LiteralPath $candidateRoot -PathType Container)) { Stop-Smoke 'output_directory_must_be_new_or_empty' }
        $existing = @(Get-ChildItem -Force -Recurse -LiteralPath $candidateRoot)
        if ($existing.Count -ne 0) {
            if ($PreflightOnly) { Stop-Smoke 'output_directory_must_be_new_or_empty' }
            $previousPath = Join-Path $candidateRoot 'report.json'
            $previous = if (Test-Path -LiteralPath $previousPath -PathType Leaf) { Get-Content -Raw -LiteralPath $previousPath | ConvertFrom-Json -AsHashtable } else { @{} }
            $priorNames = @('report.json', 'events.jsonl', 'screenshots', 'screenshots\preflight.png')
            if ($previous.scope -ne 'preflight' -or $previous.status -ne 'environment-ready') { Stop-Smoke 'output_is_not_completed_preflight' }
            foreach ($item in $existing) {
                $relative = [System.IO.Path]::GetRelativePath($candidateRoot, $item.FullName)
                if ($relative -notin $priorNames -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { Stop-Smoke 'output_contains_unexpected_artifact' }
            }
            $reusePreflight = $true
        }
    } else { $null = New-Item -ItemType Directory -Path $candidateRoot }
    $script:root = $candidateRoot
    $null = New-Item -ItemType Directory -Path (Join-Path $script:root 'screenshots') -Force
    $scope = if ($PreflightOnly) { 'preflight' } else { 'native-smoke' }
    Write-Json 'report.json' ([ordered]@{ schemaVersion = 1; scope = $scope; status = 'running'; nativePlaybackVerified = $false; checks = $script:checks })
    if (-not $IsWindows) { Stop-Smoke 'windows_required' -EnvironmentBlocked }
    if (-not [System.IO.Path]::IsPathFullyQualified($WinAppPath) -or -not [System.IO.File]::Exists($WinAppPath)) { Stop-Smoke 'pinned_winapp_missing' -EnvironmentBlocked }
    $env:WINAPP_CLI_TELEMETRY_OPTOUT = '1'
    $env:WINAPP_UI_WORKFLOW_ID = [Guid]::NewGuid().ToString('N')
    Add-Type -TypeDefinition $nativeSource
    $script:stage = 'preflight'
    $blocker = [Fileway.PlayerSmoke.Native]::DesktopBlocker()
    if ($blocker) { Stop-Smoke $blocker -EnvironmentBlocked }
    $version = Invoke-WinApp @('--version') -PlainText
    if ($version -notmatch '(?<!\d)0\.7\.1(?!\d)') { Stop-Smoke 'winapp_version_mismatch' -EnvironmentBlocked }
    $script:probe = [Fileway.PlayerSmoke.Probe]::new()
    $probeWindow = $script:probe.Window
    $tree = Invoke-WinApp @('ui', 'inspect', '-w', $probeWindow.ToInt64().ToString(), '--depth', '1', '--json')
    if (-not $tree.Contains('windows') -or @($tree.windows | Where-Object { $_.hwnd -eq $probeWindow.ToInt64() -and $_.elementCount -gt 0 }).Count -ne 1) { Stop-Smoke 'preflight_uia_unavailable' -EnvironmentBlocked }
    $script:checks.uia = $true
    $null = [Fileway.PlayerSmoke.Native]::Capture($probeWindow)
    $probeImage = if ($reusePreflight) { 'preflight-smoke.png' } else { 'preflight.png' }
    $null = Invoke-WinApp @('ui', 'screenshot', '-w', $probeWindow.ToInt64().ToString(), '--capture-screen', '--output', (Artifact-Path $probeImage), '--json')
    Add-Type -AssemblyName System.Drawing.Common
    $bitmap = [System.Drawing.Bitmap]::new((Artifact-Path $probeImage))
    try {
        if ($bitmap.Width -ne 144 -or $bitmap.Height -ne 96) { Stop-Smoke 'preflight_capture_bounds_mismatch' -EnvironmentBlocked }
        $colors = @(@(240,48,48), @(48,232,72), @(56,80,240))
        for ($index = 0; $index -lt 3; $index++) {
            $pixel = $bitmap.GetPixel(24 + $index * 48, 48)
            if ([Math]::Abs($pixel.R - $colors[$index][0]) -gt 16 -or [Math]::Abs($pixel.G - $colors[$index][1]) -gt 16 -or [Math]::Abs($pixel.B - $colors[$index][2]) -gt 16) { Stop-Smoke 'preflight_capture_pixels_mismatch' -EnvironmentBlocked }
        }
    } finally { $bitmap.Dispose() }
    $script:checks.interactiveDesktop = $true
    $script:checks.capture = $true
    $script:probe.Dispose(); $script:probe = $null
    Write-Json 'report.json' ([ordered]@{ schemaVersion = 1; scope = $scope; status = $(if ($PreflightOnly) { 'environment-ready' } else { 'running' }); code = 'preflight_ok'; checks = $script:checks; nativePlaybackVerified = $false })
    if ($PreflightOnly) { $exitCode = 0 }
    else {

    $script:stage = 'launch'
    if (-not $AppPath -or -not [System.IO.Path]::IsPathFullyQualified($AppPath) -or -not [System.IO.File]::Exists($AppPath)) { Stop-Smoke 'built_app_missing' }
    if (-not $MediaPath) { Stop-Smoke 'synthetic_media_path_required' }
    $fixture = Validate-Fixture $MediaPath
    $script:privateRoot = Assert-ExternalPath ([System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), 'FilewayPlayerSmoke-' + [Guid]::NewGuid().ToString('N')))
    $null = New-Item -ItemType Directory -Path $script:privateRoot
    $start = [System.Diagnostics.ProcessStartInfo]::new($AppPath)
    $start.UseShellExecute = $false
    $start.WorkingDirectory = [System.IO.Path]::GetDirectoryName($AppPath)
    $start.ArgumentList.Add($fixture.path)
    $start.Environment['FILEWAY_WINDOWS_DATA_ROOT'] = $script:privateRoot
    $start.Environment['FILEWAY_PLAYER_RESPONSIVENESS_DIAGNOSTICS'] = '1'
    $script:process = [System.Diagnostics.Process]::Start($start)
    $player = Wait-Condition { $window = Get-Surface 'player'; if ($window -ne [IntPtr]::Zero) { $window } } 'native_player_not_created' 30000
    $hostIds = @([Fileway.PlayerSmoke.Native]::ChildHosts($script:process.Id))
    if ($hostIds.Count -ne 1) { Stop-Smoke 'owned_host_not_uniquely_observed' }
    $ownedHost = [System.Diagnostics.Process]::GetProcessById($hostIds[0])
    $null = $ownedHost.Handle
    $expectedHost = Join-Path ([System.IO.Path]::GetDirectoryName($AppPath)) 'fileway-host.exe'
    if (-not [System.IO.Path]::GetFullPath($ownedHost.MainModule.FileName).Equals([System.IO.Path]::GetFullPath($expectedHost), [StringComparison]::OrdinalIgnoreCase) -or
        $ownedHost.StartTime -lt $script:process.StartTime) { $ownedHost.Dispose(); Stop-Smoke 'owned_host_identity_mismatch' }
    $script:hosts.Add($ownedHost)
    Complete-Step 'launch'

    $script:stage = 'moving-frame'
    # Bring the test player to the foreground through a documented, HWND-scoped
    # key action, then prove two independently decoded frames advance.
    Show-Hud $player
    $first = Save-Frame $player $fixture.frameCount 'moving-1.png'
    Start-Sleep -Milliseconds 1100
    $second = Save-Frame $player $fixture.frameCount 'moving-2.png'
    if ($second -le $first) { Stop-Smoke 'video_frame_did_not_advance' }
    $script:checks.actualVideoPixels = $true
    Complete-Step 'moving-frame' @{ firstFrame = $first; secondFrame = $second }

    $script:stage = 'settings'
    for ($iteration = 1; $iteration -le 3; $iteration++) {
        Show-Hud $player
        $hud = Get-Surface 'hud'
        Invoke-Control $hud '^播放速度(?: |$)'
        $settings = Wait-Condition { $window = Get-Surface 'settings'; if ($window -ne [IntPtr]::Zero) { $window } } 'settings_window_not_opened'
        $null = Wait-Control $settings '^播放速度$' 'Slider'
        if ([Fileway.PlayerSmoke.Native]::GetForegroundWindow() -ne $settings) { Stop-Smoke 'settings_not_foreground' }
        # Nonactivating screen-DC capture preserves the live settings foreground
        # and its actual layering over video. Never screenshot/foreground player
        # through winapp while this light-dismiss surface is open.
        $image = [Fileway.PlayerSmoke.Native]::Capture($player)
        if (-not [Fileway.PlayerSmoke.Native]::IsWindow($settings) -or [Fileway.PlayerSmoke.Native]::GetForegroundWindow() -ne $settings) { Stop-Smoke 'settings_closed_during_capture' }
        $image.SavePng((Artifact-Path "settings-$iteration.png"))
        Send-Key $settings 'esc'
        $null = Wait-Condition { -not [Fileway.PlayerSmoke.Native]::IsWindow($settings) } 'settings_window_not_closed'
        Complete-Step "settings-cycle-$iteration"
    }

    $script:stage = 'pause'
    Show-Hud $player
    $hud = Get-Surface 'hud'
    Invoke-Control $hud '^暂停播放$'
    $null = Wait-Control $hud '^开始播放$'
    Start-Sleep -Milliseconds 600
    $first = Save-Frame $player $fixture.frameCount 'paused-1.png'
    Start-Sleep -Milliseconds 1100
    $second = Save-Frame $player $fixture.frameCount 'paused-2.png'
    if ($second -ne $first) { Stop-Smoke 'paused_video_still_advancing' }
    Complete-Step 'pause' @{ firstFrame = $first; secondFrame = $second }

    $script:stage = 'resume'
    Invoke-Control $hud '^开始播放$'
    $null = Wait-Control $hud '^暂停播放$'
    $first = Save-Frame $player $fixture.frameCount 'resumed-1.png'
    Start-Sleep -Milliseconds 1100
    $second = Save-Frame $player $fixture.frameCount 'resumed-2.png'
    if ($second -le $first) { Stop-Smoke 'resumed_video_did_not_advance' }
    Complete-Step 'resume' @{ firstFrame = $first; secondFrame = $second }

    $script:stage = 'fullscreen'
    Show-Hud $player
    $hud = Get-Surface 'hud'
    if ([Fileway.PlayerSmoke.Native]::IsFullscreen($player)) { Stop-Smoke 'initial_fullscreen_state_unexpected' }
    Invoke-Control $hud '^全屏$'
    $null = Wait-Condition { [Fileway.PlayerSmoke.Native]::IsFullscreen($player) } 'fullscreen_bounds_not_reached'
    $null = Save-Frame $player $fixture.frameCount 'fullscreen.png'
    Show-Hud $player
    Invoke-Control (Get-Surface 'hud') '^退出全屏$'
    $null = Wait-Condition { -not [Fileway.PlayerSmoke.Native]::IsFullscreen($player) } 'windowed_bounds_not_restored'
    $null = Save-Frame $player $fixture.frameCount 'windowed.png'
    Complete-Step 'fullscreen-button-cycle'
    Send-Key $player 'f11'
    $null = Wait-Condition { [Fileway.PlayerSmoke.Native]::IsFullscreen($player) } 'f11_fullscreen_not_reached'
    Send-Key $player 'f11'
    $null = Wait-Condition { -not [Fileway.PlayerSmoke.Native]::IsFullscreen($player) } 'f11_windowed_not_restored'
    Complete-Step 'fullscreen-key-cycle'

    $script:stage = 'close'
    Show-Hud $player
    $hud = Get-Surface 'hud'
    Invoke-Control $hud '^关闭视频窗口$'
    $null = Wait-Condition { -not [Fileway.PlayerSmoke.Native]::IsWindow($player) -and -not [Fileway.PlayerSmoke.Native]::IsWindow($hud) -and (Get-Surface 'settings') -eq [IntPtr]::Zero } 'player_surfaces_not_closed' 15000
    Complete-Step 'close'
    $script:stage = 'application-shutdown'
    $script:process.Refresh()
    if (-not $script:process.CloseMainWindow()) { Stop-Smoke 'application_close_not_delivered' }
    if (-not $script:process.WaitForExit(15000)) { Stop-Smoke 'application_exit_timeout' }
    Record-Observation 'application-exit' @{ exitCode = $script:process.ExitCode }
    if ($script:process.ExitCode -ne 0) { Stop-Smoke 'application_exit_code_nonzero' }
    foreach ($ownedHost in $script:hosts) {
        if (-not $ownedHost.WaitForExit(15000)) { Stop-Smoke 'owned_host_exit_timeout' }
        Record-Observation 'owned-host-exit' @{ exitCode = $ownedHost.ExitCode }
        if ($ownedHost.ExitCode -ne 0) { Stop-Smoke 'owned_host_exit_code_nonzero' }
    }
    Complete-Step 'application-and-host-shutdown' @{ appExitCode = 0; ownedHostCount = $script:hosts.Count; hostExitCode = 0 }
    $hasDiagnostics = Copy-SafeDiagnostics
    Write-Json 'report.json' ([ordered]@{ schemaVersion = 1; scope = 'native-smoke'; status = 'passed'; code = 'smoke_ok'; checks = $script:checks; completedSteps = @($script:completed); elapsedMilliseconds = $script:clock.ElapsedMilliseconds; nativePlaybackVerified = $true; diagnosticReportPresent = $hasDiagnostics; latencyMeaning = 'CLI wall-clock and observed effects only; hardware performance is not inferred' })
    $exitCode = 0
    }
}
catch {
    $status = 'failed'; $code = 'unexpected_harness_error'
    $exception = $_.Exception
    while ($exception -and -not $exception.Data.Contains('smokeCode')) { $exception = $exception.InnerException }
    if ($exception) { $status = $exception.Data['smokeStatus']; $code = $exception.Data['smokeCode'] }
    elseif ($script:stage -eq 'preflight') { $status = 'environment-blocked'; $code = 'preflight_native_unavailable' }
    else {
        $captureCodes = @('noninteractive_session', 'input_desktop_unavailable', 'input_desktop_query_failed', 'locked_or_secure_desktop', 'foreground_outside_test_app', 'foreign_window_obscures_capture', 'screen_dc_unavailable', 'capture_surface_unavailable', 'screen_capture_failed', 'capture_bounds_invalid', 'process_snapshot_unavailable')
        $inner = $_.Exception
        while ($inner) {
            if ($inner.Message -in $captureCodes) { $status = 'environment-blocked'; $code = $inner.Message; break }
            $inner = $inner.InnerException
        }
    }
    if ($script:root -and (Test-Path -LiteralPath $script:root -PathType Container)) {
        Write-Json 'report.json' ([ordered]@{ schemaVersion = 1; scope = $(if ($PreflightOnly) { 'preflight' } else { 'native-smoke' }); status = $status; code = $code; failedStage = $script:stage; checks = $script:checks; nativePlaybackVerified = $false; completedSteps = @($script:completed) })
    }
    Write-Host "Player smoke $status at $($script:stage): $code"
    $exitCode = if ($status -eq 'environment-blocked') { 2 } else { 1 }
}
finally {
    if ($script:probe) { try { $script:probe.Dispose() } catch { } }
    if ($script:process) {
        try {
            if (-not $script:process.HasExited) {
                $script:process.Refresh()
                $null = $script:process.CloseMainWindow()
                if (-not $script:process.WaitForExit(5000)) { $script:process.Kill($true) }
            }
        } catch { }
        try { $script:process.Dispose() } catch { }
    }
    foreach ($ownedHost in $script:hosts) {
        try { if (-not $ownedHost.HasExited) { $ownedHost.Kill() } } catch { }
        try { $ownedHost.Dispose() } catch { }
    }
    if ($script:fixtureStream) { try { $script:fixtureStream.Dispose() } catch { } }
    # Private application state is intentionally separate from the upload root.
    # No database or startup log is copied; leave failed-run state for local review.
    $env:WINAPP_UI_WORKFLOW_ID = $script:oldWorkflow
    $env:WINAPP_CLI_TELEMETRY_OPTOUT = $script:oldTelemetry
}
exit $exitCode
