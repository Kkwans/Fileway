using System.Runtime.InteropServices;

namespace Fileway.App.Windowing;

/// <summary>
/// A frameless window keeps the native sizing border, but the border is invisible and sits on top of
/// content. Passing the press straight to DefWindowProc therefore lets a single click near the edge
/// start the modal sizing loop. This gate holds the press until the pointer has actually been dragged
/// and only then asks the system to size the window.
/// </summary>
internal sealed class EdgeResizeGate
{
    private const uint WmNcLButtonDown = 0x00A1;
    private const uint WmMouseMove = 0x0200;
    private const uint WmLButtonUp = 0x0202;
    private const int VkLButton = 0x01;
    private const int SmCxDrag = 68;
    private const int HtLeft = 10;
    private const int HtBottomRight = 17;

    private readonly nint _window;
    private nint _press;
    private int _hit;
    private (int X, int Y) _origin;

    internal EdgeResizeGate(nint window) => _window = window;

    /// <summary>Returns the window-procedure result when the gate consumed the message.</summary>
    internal nint? TryHandle(uint message, nuint wParam, nint lParam)
    {
        switch (message)
        {
            case WmNcLButtonDown:
                var hit = (int)(nint)wParam;
                if (hit is < HtLeft or > HtBottomRight) return null;
                _hit = hit;
                _press = lParam;
                _origin = ToClient(PointOf(lParam));
                SetCapture(_window);
                return 0;
            case WmMouseMove when _hit != 0:
                if ((GetAsyncKeyState(VkLButton) & 0x8000) == 0) return Cancel();
                var point = PointOf(lParam);
                var slop = Math.Max(4, GetSystemMetricsForDpi(SmCxDrag, GetDpiForWindow(_window)));
                if (Math.Abs(point.X - _origin.X) < slop && Math.Abs(point.Y - _origin.Y) < slop) return 0;
                var sizing = _hit;
                Cancel();
                // Replay the press we withheld so the original window procedure starts its own sizing
                // loop; synthesising SC_SIZE ourselves lands in move mode instead.
                _ = DefSubclassProc(_window, WmNcLButtonDown, (nuint)sizing, _press);
                return 0;
            case WmLButtonUp when _hit != 0:
                return Cancel();
            default:
                return null;
        }
    }

    private nint Cancel()
    {
        _hit = 0;
        if (GetCapture() == _window) ReleaseCapture();
        return 0;
    }

    private (int X, int Y) ToClient((int X, int Y) point)
    {
        var value = new Point { X = point.X, Y = point.Y };
        ScreenToClient(_window, ref value);
        return (value.X, value.Y);
    }

    private static (int X, int Y) PointOf(nint lParam) =>
        ((int)(short)(lParam.ToInt64() & 0xffff), (int)(short)((lParam.ToInt64() >> 16) & 0xffff));

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll")] private static extern nint SetCapture(nint hWnd);
    [DllImport("user32.dll")] private static extern bool ReleaseCapture();
    [DllImport("user32.dll")] private static extern nint GetCapture();
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int vKey);
    [DllImport("user32.dll")] private static extern bool ScreenToClient(nint hWnd, ref Point lpPoint);
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(nint hWnd);
    [DllImport("user32.dll")] private static extern int GetSystemMetricsForDpi(int nIndex, uint dpi);
    [DllImport("comctl32.dll", CharSet = CharSet.Unicode)] private static extern nint DefSubclassProc(nint hWnd, uint msg, nuint wParam, nint lParam);
}
