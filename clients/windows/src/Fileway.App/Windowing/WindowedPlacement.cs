using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;

namespace Fileway.App.Windowing;

[StructLayout(LayoutKind.Sequential)]
internal struct WindowedPlacement
{
    private const uint ShowNormal = 1;
    private const uint ShowMaximized = 3;

    internal uint Length, Flags, ShowCommand;
    internal int MinimumX, MinimumY, MaximumX, MaximumY;
    internal int NormalLeft, NormalTop, NormalRight, NormalBottom;

    internal static WindowedPlacement Capture(nint window)
    {
        var placement = new WindowedPlacement { Length = (uint)Marshal.SizeOf<WindowedPlacement>() };
        if (!GetWindowPlacement(window, ref placement))
            throw new Win32Exception(Marshal.GetLastPInvokeError(), "GetWindowPlacement 失败。");
        return placement;
    }

    internal readonly void Restore(nint window, OverlappedPresenter presenter)
    {
        // Preserve the workspace-coordinate restore rectangle. Maximize only
        // after returning to normal so the current work area is recalculated.
        var placement = this;
        placement.ShowCommand = ShowNormal;
        if (!SetWindowPlacement(window, ref placement))
            throw new Win32Exception(Marshal.GetLastPInvokeError(), "SetWindowPlacement 失败。");
        if (ShowCommand == ShowMaximized) presenter.Maximize();
    }

    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowPlacement(nint window, ref WindowedPlacement placement);

    [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPlacement(nint window, ref WindowedPlacement placement);
}
