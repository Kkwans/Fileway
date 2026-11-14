using System.Runtime.InteropServices;

namespace Fileway.App.Windowing;

/// <summary>
/// A frameless window keeps native sizing/caption hit tests, but a single press must not enter the
/// modal move/sizing loop. The press is withheld until the pointer actually drags past the system
/// drag threshold; only then is the original press replayed so Snap and native resize semantics survive.
/// </summary>
internal sealed class NonClientInputGate
{
    private const uint WmNcLButtonDown = 0x00A1;
    private const uint WmNcLButtonDblClk = 0x00A3;
    private const uint WmNcLButtonUp = 0x00A2;
    private const uint WmMouseMove = 0x0200;
    private const uint WmNcMouseMove = 0x00A0;
    private const uint WmLButtonUp = 0x0202;
    private const uint WmTimer = 0x0113;
    private const uint WmCaptureChanged = 0x0215;
    private const uint WmCancelMode = 0x001F;
    private const int VkLButton = 0x01;
    private const int SmCxDrag = 68;
    private const int SmCyDrag = 69;
    private const int HtCaption = 2;
    private const int HtLeft = 10;
    private const int HtBottomRight = 17;
    private const nuint TimerId = 1;

    private readonly nint _window;
    private nint _press;
    private int _hit;
    private NativePoint _origin;

    internal NonClientInputGate(nint window) => _window = window;

    /// <summary>Returns the window-procedure result when the gate consumed the message.</summary>
    internal nint? TryHandle(uint message, nuint wParam, nint lParam)
    {
        if (message is 0x0082 or 0x0010) // NCDESTROY / CLOSE: never leave a pending timer or capture behind.
        {
            if (_hit != 0) Cancel();
            return null;
        }
        switch (message)
        {
            case WmNcLButtonDown:
                var hit = (int)(nint)wParam;
                if (hit is not HtCaption and (< HtLeft or > HtBottomRight)) return null;
                _hit = hit;
                _press = lParam;
                _origin = PointOf(lParam);
                SetCapture(_window);
                SetTimer(_window, TimerId, 16, 0);
                return 0;
            case WmNcLButtonDblClk when _hit != 0 || (int)(nint)wParam == HtCaption:
                Cancel();
                // Title double-click keeps the native maximize contract; sizing borders do not.
                return (int)(nint)wParam == HtCaption
                    ? DefSubclassProc(_window, message, wParam, lParam)
                    : 0;
            case WmTimer when wParam == TimerId && _hit != 0:
                return PollDrag();
            case WmMouseMove or WmNcMouseMove when _hit != 0:
                return PollDrag();
            case WmLButtonUp or WmNcLButtonUp when _hit != 0:
                return Cancel();
            case WmCaptureChanged or WmCancelMode when _hit != 0:
                return Cancel();
            default:
                return null;
        }
    }

    private nint PollDrag()
    {
        if ((GetAsyncKeyState(VkLButton) & 0x8000) == 0) return Cancel();
        if (!GetCursorPos(out var point)) return 0;
        var dpi = GetDpiForWindow(_window);
        var slopX = Math.Max(4, GetSystemMetricsForDpi(SmCxDrag, dpi));
        var slopY = Math.Max(4, GetSystemMetricsForDpi(SmCyDrag, dpi));
        if (Math.Abs(point.X - _origin.X) < slopX && Math.Abs(point.Y - _origin.Y) < slopY) return 0;
        var sizing = _hit;
        var press = _press;
        Cancel();
        // Replaying the withheld press starts the native loop; synthesising SC_SIZE lands in move mode.
        _ = DefSubclassProc(_window, WmNcLButtonDown, (nuint)sizing, press);
        return 0;
    }

    private nint Cancel()
    {
        _hit = 0;
        KillTimer(_window, TimerId);
        if (GetCapture() == _window) ReleaseCapture();
        return 0;
    }

    private static NativePoint PointOf(nint lParam) =>
        new() { X = unchecked((short)(lParam.ToInt64() & 0xffff)), Y = unchecked((short)((lParam.ToInt64() >> 16) & 0xffff)) };

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint
    {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll")] private static extern nint SetCapture(nint hWnd);
    [DllImport("user32.dll")] private static extern bool ReleaseCapture();
    [DllImport("user32.dll")] private static extern nint GetCapture();
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int vKey);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out NativePoint point);
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(nint hWnd);
    [DllImport("user32.dll")] private static extern int GetSystemMetricsForDpi(int nIndex, uint dpi);
    [DllImport("user32.dll")] private static extern nuint SetTimer(nint hWnd, nuint id, uint elapse, nint callback);
    [DllImport("user32.dll")] private static extern bool KillTimer(nint hWnd, nuint id);
    [DllImport("comctl32.dll", CharSet = CharSet.Unicode)] private static extern nint DefSubclassProc(nint hWnd, uint msg, nuint wParam, nint lParam);
}