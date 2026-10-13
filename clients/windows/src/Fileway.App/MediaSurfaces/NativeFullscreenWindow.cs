using System.Runtime.InteropServices;
using Fileway.Core.Contracts;

namespace Fileway.App.MediaSurfaces;

/// <summary>UI-thread-owned native geometry and Shell fullscreen marking; never owns the renderer.</summary>
internal sealed class NativeFullscreenWindow : IDisposable
{
    private ITaskbarList2? _taskbar;

    internal void Mark(nint window, bool fullscreen)
    {
        if (_taskbar is null)
        {
            var classId = new Guid("56FDF344-FD6D-11D0-958A-006097C9A090");
            var interfaceId = new Guid("602D4995-B13A-429B-A66E-1935E44F4317");
            if (CoCreateInstance(ref classId, 0, 1, ref interfaceId, out var taskbar) < 0) throw Failure();
            _taskbar = taskbar;
            if (_taskbar.HrInit() < 0)
            {
                Dispose();
                throw Failure();
            }
        }
        if (_taskbar.MarkFullscreenWindow(window, fullscreen) < 0) throw Failure();
    }

    internal static FullscreenPixelRect Bounds(nint window)
    {
        if (!NativePlayerOverlay.GetWindowRect(window, out var rect)) throw Failure();
        return Pixels(rect);
    }

    internal static FullscreenPixelRect MonitorBounds(nint window)
    {
        var info = new NativeVideoWindow.MonitorInfo { Size = Marshal.SizeOf<NativeVideoWindow.MonitorInfo>() };
        if (!NativeVideoWindow.GetMonitorInfoW(NativeVideoWindow.MonitorFromWindow(window, 2), ref info)) throw Failure();
        return Pixels(info.Monitor);
    }

    internal static NativePlacement Placement(nint window)
    {
        var placement = new NativePlacement { Length = (uint)Marshal.SizeOf<NativePlacement>() };
        if (!GetWindowPlacement(window, ref placement)) throw Failure();
        return placement;
    }

    internal static void Restore(nint window, NativePlacement placement)
    {
        // WINDOWPLACEMENT has workspace coordinates; pass it back to its matching API intact.
        int disabled = 1;
        bool restoreTransitions = DwmGetWindowAttribute(window, 3, out int previous, sizeof(int)) >= 0;
        if (restoreTransitions) _ = NativeVideoWindow.DwmSetWindowAttribute(window, 3, ref disabled, sizeof(int));
        try
        {
            if (!SetWindowPlacement(window, ref placement)) throw Failure();
        }
        finally
        {
            if (restoreTransitions) _ = NativeVideoWindow.DwmSetWindowAttribute(window, 3, ref previous, sizeof(int));
        }
    }

    internal static void Move(nint window, FullscreenPixelRect bounds)
    {
        // Preserve activation, owner and z-order; all animation writes execute on the UI thread.
        if (!NativeVideoWindow.SetWindowPos(window, 0, bounds.X, bounds.Y, bounds.Width, bounds.Height, 0x0214)) throw Failure();
    }

    internal static void StageMinimizedRestore(nint window, NativePlacement original, bool fullscreen,
        FullscreenPixelRect fullscreenBounds)
    {
        // A minimized HWND must stay minimized. Change its restore placement, never Show/Move it.
        var placement = original;
        placement.ShowCommand = 7; // SW_SHOWMINNOACTIVE.
        // WPF_RESTORETOMAXIMIZED is defined only with SW_SHOWMINIMIZED, not this
        // non-activating command. The live owner defers maximize until an actual active restore.
        placement.Flags &= ~2u;
        if (fullscreen)
        {
            var screen = new NativePlayerOverlay.Rect
            {
                Left = fullscreenBounds.X, Top = fullscreenBounds.Y,
                Right = checked(fullscreenBounds.X + fullscreenBounds.Width),
                Bottom = checked(fullscreenBounds.Y + fullscreenBounds.Height),
            };
            var info = new NativeVideoWindow.MonitorInfo { Size = Marshal.SizeOf<NativeVideoWindow.MonitorInfo>() };
            if (!NativeVideoWindow.GetMonitorInfoW(MonitorFromRect(ref screen, 2), ref info)) throw Failure();
            int offsetX = checked(info.Work.Left - info.Monitor.Left), offsetY = checked(info.Work.Top - info.Monitor.Top);
            placement.NormalPosition = new NativePlayerOverlay.Rect
            {
                Left = checked(screen.Left - offsetX), Top = checked(screen.Top - offsetY),
                Right = checked(screen.Right - offsetX), Bottom = checked(screen.Bottom - offsetY),
            };
        }
        if (!SetWindowPlacement(window, ref placement)) throw Failure();
    }

    internal static FilewayException Failure() => new(new(ErrorCode.Unknown, "窗口显示状态未能更新，请重试全屏切换。"));

    private static FullscreenPixelRect Pixels(NativePlayerOverlay.Rect rect)
    {
        long width = (long)rect.Right - rect.Left, height = (long)rect.Bottom - rect.Top;
        if (width is < 1 or > int.MaxValue || height is < 1 or > int.MaxValue) throw Failure();
        return new(rect.Left, rect.Top, (int)width, (int)height);
    }

    public void Dispose()
    {
        if (_taskbar is null) return;
        _ = Marshal.ReleaseComObject(_taskbar);
        _taskbar = null;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct NativePlacement
    {
        internal uint Length, Flags, ShowCommand;
        internal NativeVideoWindow.NativePoint MinPosition, MaxPosition;
        internal NativePlayerOverlay.Rect NormalPosition;
    }

    [ComImport, Guid("602D4995-B13A-429B-A66E-1935E44F4317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ITaskbarList2
    {
        [PreserveSig] int HrInit();
        [PreserveSig] int AddTab(nint window);
        [PreserveSig] int DeleteTab(nint window);
        [PreserveSig] int ActivateTab(nint window);
        [PreserveSig] int SetActiveAlt(nint window);
        [PreserveSig] int MarkFullscreenWindow(nint window, [MarshalAs(UnmanagedType.Bool)] bool fullscreen);
    }

    [DllImport("ole32.dll", ExactSpelling = true)]
    private static extern int CoCreateInstance(ref Guid classId, nint outer, uint context, ref Guid interfaceId,
        [MarshalAs(UnmanagedType.Interface)] out ITaskbarList2 taskbar);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowPlacement(nint window, ref NativePlacement placement);
    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPlacement(nint window, ref NativePlacement placement);
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern nint MonitorFromRect(ref NativePlayerOverlay.Rect rectangle, uint flags);
    [DllImport("dwmapi.dll", ExactSpelling = true)]
    private static extern int DwmGetWindowAttribute(nint window, uint attribute, out int value, int size);
}
