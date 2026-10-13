#requires -Version 7.2
[CmdletBinding()]
param(
    [string] $AppPath,
    [string] $MediaPath,
    [Parameter(Mandatory)][string] $WinAppPath,
    [Parameter(Mandatory)][string] $OutputRoot,
    [switch] $PreflightOnly,
    [switch] $CaptureShutdownStack
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
$script:shutdownDebugger = $null
$script:shutdownDebuggerOutput = $null
$script:shutdownDebuggerError = $null
$script:cdbPath = $null
$script:appStartTicks = 0L
$script:shutdownDebuggerAttached = $false
$script:debuggerAttachPhase = 0
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

    public sealed class ShutdownStage {
        public readonly int SchemaVersion=1, Stage, UiThread;
        internal ShutdownStage(int stage,int uiThread) { Stage=stage; UiThread=uiThread; }
    }

    // This is deliberately a narrow reader for the app's fixed numeric JSONL
    // contract, not a general JSON parser. Validate the whole bounded file
    // before returning any records; never return raw bytes, text, or paths.
    public static class ShutdownEvidence {
        const int MaximumBytes=4096, MaximumLines=16, MaximumLineLength=256;
        static InvalidDataException Invalid() { return new InvalidDataException("shutdown_evidence_invalid"); }
        public static ShutdownStage[] Read(string dataRoot) {
            if(string.IsNullOrEmpty(dataRoot)||!Path.IsPathFullyQualified(dataRoot)) throw Invalid();
            string directory=Path.Combine(dataRoot,"diagnostics");
            string path=Path.Combine(directory,"shutdown.jsonl");
            if(!File.Exists(path)) return Array.Empty<ShutdownStage>();
            for(var parent=new DirectoryInfo(directory);parent!=null;parent=parent.Parent)
                if((parent.Attributes&FileAttributes.ReparsePoint)!=0) throw Invalid();
            if((File.GetAttributes(path)&FileAttributes.ReparsePoint)!=0) throw Invalid();
            byte[] bytes=new byte[MaximumBytes+1]; int count=0;
            using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite)) {
                if(stream.Length>MaximumBytes) throw Invalid();
                while(count<bytes.Length) {
                    int read=stream.Read(bytes,count,bytes.Length-count);
                    if(read==0) break;
                    count+=read;
                }
            }
            if(count==0||count>MaximumBytes||bytes[count-1]!=(byte)'\n') throw Invalid();
            string[] lines=new UTF8Encoding(false,true).GetString(bytes,0,count).Split('\n');
            if(lines.Length-1>MaximumLines) throw Invalid();
            var records=new List<ShutdownStage>();
            for(int index=0;index<lines.Length-1;index++) {
                string line=lines[index];
                if(line.EndsWith("\r",StringComparison.Ordinal)) line=line.Substring(0,line.Length-1);
                if(line.Length>MaximumLineLength) throw Invalid();
                line=line.Trim(' ','\t');
                if(line.Length<2||line[0]!='{'||line[line.Length-1]!='}') throw Invalid();
                string[] fields=line.Substring(1,line.Length-2).Split(',');
                if(fields.Length!=3) throw Invalid();
                var values=new Dictionary<string,int>(StringComparer.Ordinal);
                foreach(string field in fields) {
                    string[] pair=field.Split(':');
                    if(pair.Length!=2) throw Invalid();
                    string key=pair[0].Trim(' ','\t'), value=pair[1].Trim(' ','\t');
                    if(key!="\"schemaVersion\""&&key!="\"stage\""&&key!="\"uiThread\"") throw Invalid();
                    if(values.ContainsKey(key)||value.Length<1||value.Length>2||(value.Length==2&&value[0]=='0')) throw Invalid();
                    int number=0;
                    foreach(char digit in value) {
                        if(digit<'0'||digit>'9') throw Invalid();
                        number=number*10+digit-'0';
                    }
                    values.Add(key,number);
                }
                int schema=values["\"schemaVersion\""], stage=values["\"stage\""], uiThread=values["\"uiThread\""];
                if(schema!=1||stage<1||stage>13||uiThread>1) throw Invalid();
                records.Add(new ShutdownStage(stage,uiThread));
            }
            return records.ToArray();
        }
    }

    public sealed class DebuggerFrame {
        public readonly int Index, OffsetKind;
        public readonly string Module, Symbol;
        public readonly ulong Offset;
        internal DebuggerFrame(int index,string module,string symbol,ulong offset,int offsetKind) {
            Index=index; Module=module; Symbol=symbol; Offset=offset; OffsetKind=offsetKind;
        }
    }
    public sealed class DebuggerParameter {
        public readonly int Index, Mapping;
        public readonly string Module;
        public readonly ulong Offset;
        internal DebuggerParameter(int index,int mapping,string module,ulong offset) { Index=index; Mapping=mapping; Module=module; Offset=offset; }
    }
    public sealed class DebuggerFault {
        public readonly uint ExceptionCode;
        public readonly int FastFailSubcode, ParameterCount;
        public readonly DebuggerFrame[] Frames;
        public readonly DebuggerParameter[] Parameters;
        internal DebuggerFault(uint code,int subcode,int parameterCount,DebuggerFrame[] frames,DebuggerParameter[] parameters) {
            ExceptionCode=code; FastFailSubcode=subcode; ParameterCount=parameterCount; Frames=frames; Parameters=parameters;
        }
    }
    public static class DebuggerEvidence {
        // Only shipped application/framework modules. Never retain an arbitrary
        // module name, source path, stack/register address, argument or message.
        static readonly string[] Modules={"Microsoft_UI_Xaml","Microsoft_UI_Input","Microsoft_UI_Windowing_Core",
            "Microsoft_UI_Composition","Microsoft_WindowsAppRuntime","Microsoft_InteractiveExperiences_Projection",
            "CoreMessaging","CoreUIComponents","inputhost","windows_ui","dcomp","twinapi_appcore",
            "combase","ole32","ntdll","KERNELBASE","KERNEL32","USER32","win32u","COMCTL32","ucrtbase","coreclr","Fileway_App",
            "Microsoft_Internal_FrameworkUdk","Microsoft_Internal_FrameworkUdk_System","Microsoft_WinUI",
            "libmpv_2","libwinpthread_1","libgcc_s_seh_1","UIAutomationCore","msctf"};
        // Explicit public names, not a character-class permission to export text.
        // Unlisted symbols retain only a validated module-relative offset from lm.
        static readonly string[] Symbols={"RtlFailFast2","RtlFailFast","RtlReportFatalFailure","RtlReportCriticalFailure",
            "RtlpHandleInvalidUserCallTarget","LdrpValidateUserCallTarget",
            "UserCallWinProcCheckWow","DispatchClientMessage","__fnDWORD",
            "KiUserCallbackDispatcher","KiUserCallbackDispatcherContinue","DefSubclassProc","CallNextSubclassProc",
            "RaiseFailFastException","RaiseException","RoFailFastWithErrorContext","RoFailFastWithErrorContextInternal2",
            "abort","terminate","_invoke_watson","__report_gsfailure","__GSHandlerCheck","DispatchMessageW",
            "DispatchMessageWorker","DestroyWindow","NtUserDestroyWindow","NtUserMessageCall","SendMessageW",
            "CallWindowProcW","DefWindowProcW","BaseThreadInitThunk","RtlUserThreadStart",
            "DirectUI::DesktopWindowImpl::CloseImpl","DirectUI::DesktopWindowImpl::PrepareToClose",
            "DirectUI::DesktopWindowImpl::Shutdown","DirectUI::Window::Close","DirectUI::Window::CloseImpl",
            "DirectUI::Window::RaiseClosed","DirectUI::DXamlCore::Deinitialize","DirectUI::DXamlCore::Shutdown",
            "DirectUI::ErrorHelper::ProcessUnhandledError","DirectUI::ErrorHelper::OriginateError",
            "Microsoft::WRL::Details::RaiseException","wil::details::ReportFailure","wil::details::FailFast_Unexpected",
            "wil::details::WilFailFast","wil::details::ReportFailure_FailFast"};
        static bool Hex(string text,out ulong value) {
            value=0;
            if(text.Length<1||text.Length>16) return false;
            foreach(char digit in text) if(!((digit>='0'&&digit<='9')||(digit>='a'&&digit<='f')||(digit>='A'&&digit<='F'))) return false;
            return ulong.TryParse(text,System.Globalization.NumberStyles.AllowHexSpecifier,System.Globalization.CultureInfo.InvariantCulture,out value);
        }
        static bool Address(string text,out ulong value) { return Hex(text.Replace("`",""),out value); }
        static bool SymbolToken(string name) {
            if(name.Length<1||name.Length>200) return false;
            foreach(char c in name) if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||"_:<>,.~$@".IndexOf(c)>=0)) return false;
            return true;
        }
        static readonly Dictionary<string,string> ModuleFiles=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase) {
            {"Microsoft.UI.Xaml.dll","Microsoft_UI_Xaml"}, {"Microsoft.UI.Input.dll","Microsoft_UI_Input"},
            {"Microsoft.UI.Windowing.Core.dll","Microsoft_UI_Windowing_Core"}, {"Microsoft.UI.Composition.dll","Microsoft_UI_Composition"},
            {"Microsoft.WindowsAppRuntime.dll","Microsoft_WindowsAppRuntime"}, {"Microsoft.InteractiveExperiences.Projection.dll","Microsoft_InteractiveExperiences_Projection"},
            {"Microsoft.Internal.FrameworkUdk.dll","Microsoft_Internal_FrameworkUdk"}, {"Microsoft.Internal.FrameworkUdk.System.dll","Microsoft_Internal_FrameworkUdk_System"},
            {"Microsoft.WinUI.dll","Microsoft_WinUI"}, {"Fileway.App.exe","Fileway_App"},
            {"libmpv-2.dll","libmpv_2"}, {"libwinpthread-1.dll","libwinpthread_1"}, {"libgcc_s_seh-1.dll","libgcc_s_seh_1"},
            {"UIAutomationCore.dll","UIAutomationCore"}, {"msctf.dll","msctf"},
            {"ntdll.dll","ntdll"}, {"user32.dll","USER32"}, {"kernel32.dll","KERNEL32"}, {"kernelbase.dll","KERNELBASE"},
            {"win32u.dll","win32u"}, {"comctl32.dll","COMCTL32"}, {"combase.dll","combase"}, {"ole32.dll","ole32"},
            {"coreclr.dll","coreclr"}, {"ucrtbase.dll","ucrtbase"}, {"CoreMessaging.dll","CoreMessaging"},
            {"CoreUIComponents.dll","CoreUIComponents"}, {"inputhost.dll","inputhost"}, {"windows.ui.dll","windows_ui"},
            {"dcomp.dll","dcomp"}, {"twinapi.appcore.dll","twinapi_appcore"}
        };
        static string Module(string name) {
            string module;
            if(ModuleFiles.TryGetValue(name,out module)) return module;
            return Array.Find(Modules,item=>item.Equals(name,StringComparison.OrdinalIgnoreCase));
        }

        public static bool HasReadyMarker(string path) {
            string text=ReadBounded(path,FileShare.ReadWrite);
            string[] lines=text.Split('\n');
            for(int index=0;index<lines.Length-1;index++) if(lines[index].TrimEnd('\r')=="FILEWAY_SHUTDOWN_DEBUGGER_READY") return true;
            return false;
        }
        public static DebuggerFault Read(string path) { return Parse(ReadBounded(path,FileShare.Read)); }
        static string ReadBounded(string path,FileShare share) {
            byte[] bytes=new byte[1048577]; int count=0;
            using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,share)) {
                if(stream.Length>1048576) throw new InvalidDataException("debugger_evidence_too_large");
                while(count<bytes.Length) { int read=stream.Read(bytes,count,bytes.Length-count); if(read==0) break; count+=read; }
            }
            if(count>1048576) throw new InvalidDataException("debugger_evidence_too_large");
            return new UTF8Encoding(false,true).GetString(bytes,0,count);
        }
        public static DebuggerFault Parse(string text) {
            if(text==null||text.Length>1048576) throw new InvalidDataException("debugger_evidence_too_large");
            string[] lines=text.Split('\n');
            if(lines.Length>4096) throw new InvalidDataException("debugger_evidence_too_many_lines");
            uint code=0; int subcode=-1,codeCount=0,parameterCount=-1; ulong faultAddress=0;
            var ranges=new Dictionary<string,ulong[]>(StringComparer.Ordinal);
            var unloadedRanges=new List<KeyValuePair<string,ulong[]>>(); bool unloaded=false;
            var parameters=new Dictionary<int,ulong>();
            foreach(string raw in lines) {
                if(raw.Length>2048) continue;
                string line=raw.Trim();
                string[] parts=line.Split((char[])null,StringSplitOptions.RemoveEmptyEntries);
                ulong number,end;
                if(line.StartsWith("ExceptionCode:",StringComparison.Ordinal)) {
                    if(parts.Length<2||!Hex(parts[1],out number)||number!=0xc0000409||++codeCount!=1)
                        throw new InvalidDataException("debugger_exception_not_expected");
                    code=(uint)number;
                }
                else if(line.StartsWith("NumberParameters:",StringComparison.Ordinal)) {
                    int count;
                    if(parameterCount!=-1||parts.Length!=2||!int.TryParse(parts[1],System.Globalization.NumberStyles.None,System.Globalization.CultureInfo.InvariantCulture,out count)||count<0||count>15)
                        throw new InvalidDataException("debugger_invalid_parameter_count");
                    parameterCount=count;
                }
                else if(line.StartsWith("Parameter[",StringComparison.Ordinal)) {
                    var match=System.Text.RegularExpressions.Regex.Match(line,@"^Parameter\[([0-9]|1[0-4])\]:[ \t]+([0-9a-fA-F]{1,16})$");
                    int index;
                    if(!match.Success||!int.TryParse(match.Groups[1].Value,out index)||!Hex(match.Groups[2].Value,out number)||parameters.ContainsKey(index))
                        throw new InvalidDataException("debugger_invalid_parameter");
                    parameters.Add(index,number);
                    if(index==0&&number<=65535) subcode=(int)number;
                }
                else if(line.StartsWith("ExceptionAddress:",StringComparison.Ordinal)) {
                    if(parts.Length>=2&&Address(parts[1],out number)) faultAddress=number;
                }
                if(line=="Unloaded modules:") unloaded=true;
                if(parts.Length>=3&&Address(parts[0],out number)&&Address(parts[1],out end)&&number<end) {
                    string module=Module(parts[2]);
                    if(module!=null) {
                        if(unloaded) {
                            if(unloadedRanges.Count>=64) throw new InvalidDataException("debugger_unloaded_modules_exceeded");
                            unloadedRanges.Add(new KeyValuePair<string,ulong[]>(module,new ulong[]{number,end}));
                        } else {
                            if(ranges.ContainsKey(module)) throw new InvalidDataException("debugger_duplicate_module");
                            ranges.Add(module,new ulong[]{number,end});
                        }
                    }
                }
            }
            if(code!=0xc0000409) throw new InvalidDataException("debugger_exception_not_expected");
            var frames=new List<DebuggerFrame>(); int nextIndex=0; ulong instruction=faultAddress;
            foreach(string raw in lines) {
                if(raw.Length>2048) continue;
                string[] parts=raw.Trim().Split((char[])null,StringSplitOptions.RemoveEmptyEntries);
                ulong index,ignored,returnAddress;
                if(parts.Length!=4||!Hex(parts[0],out index)||index>23||index<(ulong)nextIndex||(nextIndex==0&&index!=0)||
                    !Address(parts[1],out ignored)||!Address(parts[2],out returnAddress)) continue;
                ulong currentInstruction=index==(ulong)nextIndex?instruction:0;
                nextIndex=(int)index+1; instruction=returnAddress;
                string target=parts[3]; int bang=target.IndexOf('!'), plus=target.LastIndexOf("+0x",StringComparison.Ordinal);
                if(plus>=0&&bang>=plus) continue;
                string module=bang>=0?target.Substring(0,bang):plus>=0?target.Substring(0,plus):target;
                string known=Module(module);
                if(known==null) continue;
                string symbol=bang>=0?target.Substring(bang+1,(plus>=0?plus:target.Length)-bang-1):"";
                if(bang>=0&&!SymbolToken(symbol)) continue;
                ulong offset=0;
                if(plus>=0&&(!Hex(target.Substring(plus+3),out offset)||offset>uint.MaxValue)) continue;
                if(bang<0&&plus<0) continue;
                int kind=bang>=0?2:1;
                if(bang>=0&&!Array.Exists(Symbols,name=>name.Equals(symbol,StringComparison.Ordinal))) {
                    symbol=""; offset=0; kind=0;
                    ulong[] range;
                    if(ranges.TryGetValue(known,out range)&&currentInstruction>=range[0]&&currentInstruction<range[1]&&currentInstruction-range[0]<=uint.MaxValue) {
                        offset=currentInstruction-range[0]; kind=1;
                    }
                }
                frames.Add(new DebuggerFrame((int)index,known,symbol,offset,kind));
            }
            if(parameterCount>=0) {
                if(parameters.Count!=parameterCount) throw new InvalidDataException("debugger_parameter_count_mismatch");
                foreach(int index in parameters.Keys) if(index>=parameterCount) throw new InvalidDataException("debugger_parameter_count_mismatch");
            }
            var mapped=new List<DebuggerParameter>();
            for(int parameterIndex=1;parameterIndex<15;parameterIndex++) {
                ulong parameterValue;
                if(parameterCount<0||!parameters.TryGetValue(parameterIndex,out parameterValue)) continue;
                int mapping=0,matches=0; string module=""; ulong offset=0;
                foreach(var range in ranges) {
                    if(parameterValue>=range.Value[0]&&parameterValue<range.Value[1]&&parameterValue-range.Value[0]<=uint.MaxValue) {
                        matches++; mapping=1; module=range.Key; offset=parameterValue-range.Value[0];
                    }
                }
                foreach(var range in unloadedRanges) {
                    if(parameterValue>=range.Value[0]&&parameterValue<range.Value[1]&&parameterValue-range.Value[0]<=uint.MaxValue) {
                        matches++; mapping=2; module=range.Key; offset=parameterValue-range.Value[0];
                    }
                }
                if(matches!=1) { mapping=0; module=""; offset=0; }
                mapped.Add(new DebuggerParameter(parameterIndex,mapping,module,offset));
            }
            return new DebuggerFault(code,subcode,parameterCount,frames.ToArray(),mapped.ToArray());
        }
    }

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

function Invoke-Control([IntPtr] $Window, [string] $NamePattern, [string] $Type = 'Button') {
    $element = Wait-Control $Window $NamePattern $Type
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

function Assert-SdkDebugger {
    $candidate = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\Debuggers\x64\cdb.exe'
    if (-not [IO.File]::Exists($candidate)) { Stop-Smoke 'installed_sdk_debugger_missing' -EnvironmentBlocked }
    $signature = Get-AuthenticodeSignature -LiteralPath $candidate
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch '(^|,\s*)O=Microsoft Corporation(,|$)') {
        Stop-Smoke 'sdk_debugger_signature_invalid' -EnvironmentBlocked
    }
    $script:cdbPath = $candidate
}

function Start-ShutdownDebugger {
    if (-not $CaptureShutdownStack) { return }
    $script:debuggerAttachPhase = 1
    $script:process.Refresh()
    if ($script:process.HasExited -or $script:process.StartTime.ToUniversalTime().Ticks -ne $script:appStartTicks -or
        -not [string]::Equals($script:process.MainModule.FileName, [IO.Path]::GetFullPath($AppPath), [StringComparison]::OrdinalIgnoreCase)) {
        Stop-Smoke 'debugger_app_identity_mismatch'
    }
    $script:debuggerAttachPhase = 2
    # Relative dump name is interpreted only in the fresh private working directory.
    # gn preserves NOT HANDLED semantics. -pd prevents debugger cleanup from killing
    # the app. Neither WER nor any system-wide debugger/security setting is changed.
    $commands = 'sxe -c ".dump /mu /o shutdown.dmp; gn" -c2 ".dump /mu /o shutdown.dmp; gn" 0xc0000409' + "`n" +
        '.echo FILEWAY_SHUTDOWN_DEBUGGER_READY' + "`n" + 'g' + "`n"
    $commandFile = Join-Path $script:privateRoot 'shutdown-debugger.cmd'
    $log = Join-Path $script:privateRoot 'shutdown-debugger.log'
    [IO.File]::WriteAllText($commandFile, $commands, [Text.UTF8Encoding]::new($false))
    $start = [Diagnostics.ProcessStartInfo]::new($script:cdbPath)
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.WorkingDirectory = $script:privateRoot
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in @('-p', $script:process.Id.ToString(), '-pd', '-G', '-sins', '-noshell', '-y', $script:privateRoot,
        '-logo', $log, '-cf', $commandFile)) { $start.ArgumentList.Add($argument) }
    $script:debuggerAttachPhase = 3
    $script:shutdownDebugger = [Diagnostics.Process]::Start($start)
    $script:shutdownDebuggerOutput = $script:shutdownDebugger.StandardOutput.ReadToEndAsync()
    $script:shutdownDebuggerError = $script:shutdownDebugger.StandardError.ReadToEndAsync()
    $script:debuggerAttachPhase = 4
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.ElapsedMilliseconds -lt 10000) {
        if ($script:shutdownDebugger.HasExited -or $script:process.HasExited) { Stop-Smoke 'debugger_attach_failed' }
        if ([IO.File]::Exists($log)) {
            $size = (Get-Item -LiteralPath $log).Length
            if ($size -gt 1048576) { Stop-Smoke 'debugger_attach_output_too_large' }
            $ready = $false
            if ($size -gt 0) {
                try { $ready = [Fileway.PlayerSmoke.DebuggerEvidence]::HasReadyMarker($log) }
                catch {
                    $errorObject = $_.Exception
                    while ($errorObject.InnerException) { $errorObject = $errorObject.InnerException }
                    # Log creation may briefly deny sharing. Retry only these
                    # exact Win32 conditions under the original attach deadline.
                    if ($errorObject.HResult -notin @(-2147024864, -2147024863)) { throw }
                }
            }
            if ($ready) {
                $script:shutdownDebuggerAttached = $true
                Record-Observation 'shutdown-debugger' @{ tool = 'microsoft-sdk-cdb-x64'; attached = 1 }
                return
            }
        }
        Start-Sleep -Milliseconds 100
    }
    Stop-Smoke 'debugger_attach_timeout'
}

function Stop-ShutdownDebugger {
    if ($script:shutdownDebugger) {
        try {
            if (-not $script:shutdownDebugger.WaitForExit(5000)) { $script:shutdownDebugger.Kill($true); $null = $script:shutdownDebugger.WaitForExit(5000) }
        } catch { }
        try { $script:shutdownDebugger.Dispose() } catch { }
        $script:shutdownDebugger = $null
    }
}

function Record-SafeDebuggerEvidence {
    if (-not $script:shutdownDebuggerAttached) { return }
    Stop-ShutdownDebugger
    $dump = Join-Path $script:privateRoot 'shutdown.dmp'
    if (-not [IO.File]::Exists($dump)) { Record-Observation 'shutdown-debugger-result' @{ code = 'dump_missing' }; return }
    if ((Get-Item -LiteralPath $dump).Length -gt 67108864) { Record-Observation 'shutdown-debugger-result' @{ code = 'dump_too_large' }; return }
    $log = Join-Path $script:privateRoot 'shutdown-stack.log'
    $symbols = Join-Path $script:privateRoot 'symbols'
    $start = [Diagnostics.ProcessStartInfo]::new($script:cdbPath)
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.WorkingDirectory = $script:privateRoot
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in @('-z', $dump, '-sins', '-noshell', '-y', "srv*$symbols*https://msdl.microsoft.com/download/symbols",
        '-logo', $log, '-c', '.ecxr; .exr -1; kn 0n24; lm; q')) { $start.ArgumentList.Add($argument) }
    $analysis = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $analysis.StandardOutput.ReadToEndAsync(); $stderr = $analysis.StandardError.ReadToEndAsync()
        if (-not $analysis.WaitForExit(120000)) {
            $analysis.Kill($true); $null = $analysis.WaitForExit(5000)
            Record-Observation 'shutdown-debugger-result' @{ code = 'analysis_timeout' }; return
        }
        if ($analysis.ExitCode -ne 0) { Record-Observation 'shutdown-debugger-result' @{ code = 'analysis_failed' }; return }
        $fault = [Fileway.PlayerSmoke.DebuggerEvidence]::Read($log)
        Record-Observation 'shutdown-native-exception' @{ exceptionCode = [uint32]$fault.ExceptionCode; fastFailSubcode = [int]$fault.FastFailSubcode; parameterCount = [int]$fault.ParameterCount }
        foreach ($parameter in $fault.Parameters) {
            Record-Observation 'shutdown-native-parameter' @{ index = [int]$parameter.Index; mapping = [int]$parameter.Mapping; module = [string]$parameter.Module; offset = [uint64]$parameter.Offset }
        }
        foreach ($frame in $fault.Frames) {
            Record-Observation 'shutdown-native-frame' @{
                index = [int]$frame.Index; module = [string]$frame.Module; symbol = [string]$frame.Symbol
                offset = [uint64]$frame.Offset; offsetKind = [int]$frame.OffsetKind
            }
        }
    } catch { Record-Observation 'shutdown-debugger-result' @{ code = 'analysis_evidence_rejected' } }
    finally { $analysis.Dispose() }
}


function Record-SafeExitEvidence {
    # Only fixed-schema app diagnostics and matching-process numeric Windows
    # fault fields enter the existing allowlist. Never copy messages or paths.
    try { $null = Copy-SafeDiagnostics } catch { }
    try {
        # Reconstruct a numeric-only payload after the entire file validates.
        # A reached stage (including one after a catch) does not prove success.
        foreach ($record in [Fileway.PlayerSmoke.ShutdownEvidence]::Read($script:privateRoot)) {
            Record-Observation 'shutdown-stage' @{
                schemaVersion = [int]$record.SchemaVersion
                stage = [int]$record.Stage
                uiThread = [int]$record.UiThread
            }
        }
    } catch { }
    try {
        $path = Join-Path $script:privateRoot 'diagnostics\startup.log'
        if ([IO.File]::Exists($path) -and (Get-Item -LiteralPath $path).Length -le 32768) {
            $failures = @(Get-Content -Raw -LiteralPath $path | ConvertFrom-Json -AsHashtable)
            foreach ($failure in $failures | Select-Object -First 4) {
                $type = [string]$failure.type
                if ($type.Length -gt 200 -or $type -notmatch '^(Fileway|System|Microsoft)\.[A-Za-z0-9_.+`<>]+$') { continue }
                $methods = @($failure.methods | Where-Object {
                    $_ -is [string] -and $_.Length -le 250 -and $_ -match '^(Fileway|System|Microsoft)\.[A-Za-z0-9_.+`<>]+$'
                } | Select-Object -First 32)
                Record-Observation 'managed-failure' @{ type = $type; hresult = [int]$failure.HResult; methods = $methods }
            }
        }
    } catch { }
    # Windows may publish its fault event shortly after the process has exited.
    # Retry only this read, for at most 20 bounded queries / five seconds; never
    # alter WER settings, retain raw XML, or change the failed exit result.
    $faultWait = [Diagnostics.Stopwatch]::StartNew()
    for ($attempt = 0; $attempt -lt 20 -and $faultWait.ElapsedMilliseconds -lt 5000; $attempt++) {
        $events = @()
        try { $events = @(Get-WinEvent -FilterHashtable @{ LogName = 'Application'; Id = 1000; StartTime = $script:process.StartTime } -MaxEvents 16 -ErrorAction Stop) } catch { }
        $matchedFault = $false
        foreach ($event in $events) {
            try {
                $xml = [xml]$event.ToXml()
                $fields = @{}
                foreach ($datum in $xml.Event.EventData.Data) { $fields[[string]$datum.Name] = [string]$datum.'#text' }
                $processText = [string]$fields.ProcessId
                $observedId = if ($processText.StartsWith('0x', [StringComparison]::OrdinalIgnoreCase)) { [Convert]::ToInt64($processText.Substring(2), 16) } else { [long]$processText }
                if ($observedId -ne $script:process.Id -or $fields.AppName -ne [IO.Path]::GetFileName($AppPath)) { continue }
                $module = [string]$fields.ModuleName
                if ($module.Length -gt 100 -or $module -notmatch '^[A-Za-z0-9_.-]+$') { $module = 'redacted' }
                Record-Observation 'windows-application-fault' @{
                    eventId = 1000; module = $module
                    exceptionCode = [Convert]::ToUInt32(([string]$fields.ExceptionCode -replace '^0x', ''), 16)
                    faultOffset = [Convert]::ToUInt64(([string]$fields.FaultingOffset -replace '^0x', ''), 16)
                }
                $matchedFault = $true
            } catch { }
        }
        if ($matchedFault) { break }
        if ($attempt -lt 19 -and $faultWait.ElapsedMilliseconds -lt 4750) { Start-Sleep -Milliseconds 250 }
    }
    try { Record-SafeDebuggerEvidence } catch { Record-Observation 'shutdown-debugger-result' @{ code = 'capture_failed' } }
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
    if ($CaptureShutdownStack) { Assert-SdkDebugger }
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
    $script:appStartTicks = $script:process.StartTime.ToUniversalTime().Ticks
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
        # Narrow windows intentionally move speed into More; follow the real
        # adaptive UI rather than requiring an invisible toolbar button.
        if (Find-Control $hud '^播放速度(?: |$)' -Optional) {
            Invoke-Control $hud '^播放速度(?: |$)'
        } else {
            Invoke-Control $hud '^更多播放选项$'
            $menu = Wait-Condition { $window = Get-Surface 'settings'; if ($window -ne [IntPtr]::Zero) { $window } } 'more_menu_not_opened'
            Invoke-Control $menu '^播放速度 · ' 'MenuItem'
            $null = Wait-Condition { -not [Fileway.PlayerSmoke.Native]::IsWindow($menu) } 'more_menu_not_closed'
        }
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
    $null = Copy-SafeDiagnostics
    $script:stage = 'application-shutdown'
    try { Start-ShutdownDebugger }
    catch {
        $attachError = $_.Exception
        while ($attachError.InnerException) { $attachError = $attachError.InnerException }
        Record-Observation 'shutdown-debugger-attach-error' @{ phase = [int]$script:debuggerAttachPhase; hresult = [int]$attachError.HResult }
        throw
    }
    $script:process.Refresh()
    if (-not $script:process.CloseMainWindow()) { Stop-Smoke 'application_close_not_delivered' }
    if (-not $script:process.WaitForExit(15000)) { Stop-Smoke 'application_exit_timeout' }
    Record-Observation 'application-exit' @{ exitCode = $script:process.ExitCode }
    if ($script:process.ExitCode -ne 0) {
        Record-SafeExitEvidence
        Stop-Smoke 'application_exit_code_nonzero'
    }
    foreach ($ownedHost in $script:hosts) {
        if (-not $ownedHost.WaitForExit(15000)) { Stop-Smoke 'owned_host_exit_timeout' }
        Record-Observation 'owned-host-exit' @{ exitCode = $ownedHost.ExitCode }
        if ($ownedHost.ExitCode -ne 0) { Stop-Smoke 'owned_host_exit_code_nonzero' }
    }
    Complete-Step 'application-and-host-shutdown' @{ appExitCode = 0; ownedHostCount = $script:hosts.Count; hostExitCode = 0 }
    $hasDiagnostics = Copy-SafeDiagnostics
    Write-Json 'report.json' ([ordered]@{ schemaVersion = 1; scope = 'native-smoke'; status = 'passed'; code = 'smoke_ok'; checks = $script:checks; completedSteps = @($script:completed); elapsedMilliseconds = $script:clock.ElapsedMilliseconds; nativePlaybackVerified = $true; diagnosticReportPresent = $hasDiagnostics; shutdownDebuggerAttached = $script:shutdownDebuggerAttached; normalShutdownVerified = (-not $script:shutdownDebuggerAttached); latencyMeaning = 'CLI wall-clock and observed effects only; hardware performance is not inferred' })
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
        Write-Json 'report.json' ([ordered]@{ schemaVersion = 1; scope = $(if ($PreflightOnly) { 'preflight' } else { 'native-smoke' }); status = $status; code = $code; failedStage = $script:stage; checks = $script:checks; nativePlaybackVerified = $false; shutdownDebuggerAttached = $script:shutdownDebuggerAttached; normalShutdownVerified = $false; completedSteps = @($script:completed) })
    }
    Write-Host "Player smoke $status at $($script:stage): $code"
    $exitCode = if ($status -eq 'environment-blocked') { 2 } else { 1 }
}
finally {
    Stop-ShutdownDebugger
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
