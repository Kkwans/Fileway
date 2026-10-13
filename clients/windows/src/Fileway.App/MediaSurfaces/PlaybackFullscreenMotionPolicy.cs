namespace Fileway.App.MediaSurfaces;

/// <summary>Native screen pixels. Right/bottom must remain representable by a Win32 RECT.</summary>
internal readonly record struct FullscreenPixelRect(int X, int Y, int Width, int Height);

/// <summary>Continuous physical-pixel geometry or its velocity in pixels per second.</summary>
internal readonly record struct FullscreenMotionVector(double X, double Y, double Width, double Height);

internal readonly record struct FullscreenMotionFrame(FullscreenPixelRect Bounds,
    FullscreenMotionVector Geometry, FullscreenMotionVector Velocity, FullscreenPixelRect TargetBounds,
    long Generation, bool DesiredFullscreen, bool CommittedFullscreen, bool IsAnimating, bool IsClosed);

/// <summary>
/// Caller-serialized, platform-independent trajectory for one live native window. Every new target
/// inherits position and velocity; only integer output is constrained to the native coordinate range.
/// This policy never creates a visual, copies a video frame, or owns a window/rendering clock.
/// </summary>
internal sealed class PlaybackFullscreenMotionPolicy
{
    private static readonly TimeSpan DefaultResponse = TimeSpan.FromMilliseconds(400);
    private static readonly TimeSpan MinimumResponse = TimeSpan.FromMilliseconds(100);
    private static readonly TimeSpan MaximumResponse = TimeSpan.FromSeconds(2);
    private const double PositionTolerance = 0.25;
    private const double VelocityTolerance = 5;
    private FullscreenMotionVector _position;
    private FullscreenMotionVector _velocity;
    private FullscreenMotionVector _segmentPosition;
    private FullscreenMotionVector _segmentVelocity;
    private FullscreenMotionVector _targetPosition;
    private FullscreenPixelRect _target;
    private TimeSpan _response;
    private long _timeTicks;
    private long _segmentStartTicks;
    private long _generation;
    private bool _desired;
    private bool _committed;
    private bool _animating;
    private bool _animationsEnabled = true;
    private bool _closed;
    private FullscreenMotionFrame _current;

    internal PlaybackFullscreenMotionPolicy(FullscreenPixelRect initial, bool committedFullscreen = false,
        TimeSpan? responseDuration = null)
    {
        Validate(initial);
        _response = ValidateResponse(responseDuration ?? DefaultResponse);
        _target = initial; _position = _segmentPosition = _targetPosition = ToState(initial);
        _desired = _committed = committedFullscreen;
        _current = BuildFrame();
    }

    internal FullscreenMotionFrame Current => _current;
    internal TimeSpan ResponseDuration => _response;

    internal FullscreenMotionFrame Retarget(FullscreenPixelRect target, bool desiredFullscreen,
        TimeSpan monotonicTime, TimeSpan? responseDuration = null)
    {
        if (_closed) return _current;
        Validate(target);
        var response = ValidateResponse(responseDuration ?? _response);
        AdvanceTo(monotonicTime);
        // Repeated input does not extend the trajectory, reset its velocity, or invalidate its clock.
        if (_target == target && _desired == desiredFullscreen) return _current;
        _generation = checked(_generation + 1);
        _target = target; _targetPosition = ToState(target); _desired = desiredFullscreen; _response = response;
        _segmentPosition = _position; _segmentVelocity = _velocity; _segmentStartTicks = _timeTicks;
        _animating = true;
        if (!_animationsEnabled || IsSettled()) SnapToTarget();
        _current = BuildFrame();
        return _current;
    }

    /// <summary>A retired rendering subscription cannot advance a replacement or canceled trajectory.</summary>
    internal bool TryAdvance(long generation, TimeSpan monotonicTime, out FullscreenMotionFrame frame)
    {
        if (_closed || generation != _generation) { frame = _current; return false; }
        AdvanceTo(monotonicTime);
        frame = _current;
        return true;
    }

    /// <summary>Reduced motion commits the latest target immediately and retires already queued frames.</summary>
    internal FullscreenMotionFrame SetAnimationsEnabled(bool enabled, TimeSpan monotonicTime)
    {
        if (_closed) return _current;
        AdvanceTo(monotonicTime);
        if (_animationsEnabled == enabled) return _current;
        _animationsEnabled = enabled; _generation = checked(_generation + 1);
        if (!enabled) SnapToTarget();
        _current = BuildFrame();
        return _current;
    }

    /// <summary>Stops geometric motion by committing the latest requested endpoint, without replaying old intent.</summary>
    internal FullscreenMotionFrame Cancel(TimeSpan monotonicTime)
    {
        if (_closed) return _current;
        AdvanceTo(monotonicTime);
        _generation = checked(_generation + 1); SnapToTarget(); _current = BuildFrame();
        return _current;
    }

    /// <summary>Teardown freezes the last sampled frame. It must not move a closing HWND to an endpoint.</summary>
    internal FullscreenMotionFrame Close()
    {
        if (_closed) return _current;
        _closed = true; _animating = false; _velocity = default;
        _generation = checked(_generation + 1);
        _current = _current with { Generation = _generation, Velocity = default, IsAnimating = false, IsClosed = true };
        return _current;
    }

    private void AdvanceTo(TimeSpan time)
    {
        long ticks = Math.Max(_timeTicks, time.Ticks);
        if (ticks == _timeTicks) return;
        _timeTicks = ticks;
        if (!_animating) return;
        double elapsed = (ticks - _segmentStartTicks) / (double)TimeSpan.TicksPerSecond;
        double omega = 12 / _response.TotalSeconds;
        // Skip underflow and products involving a very long scheduling gap. This is the
        // analytic settled limit, rather than integrating thousands of missed frames.
        if (omega * elapsed >= 50) SnapToTarget();
        else
        {
            var x = Spring(_segmentPosition.X, _segmentVelocity.X, _targetPosition.X, elapsed, omega);
            var y = Spring(_segmentPosition.Y, _segmentVelocity.Y, _targetPosition.Y, elapsed, omega);
            var width = Spring(_segmentPosition.Width, _segmentVelocity.Width, _targetPosition.Width, elapsed, omega);
            var height = Spring(_segmentPosition.Height, _segmentVelocity.Height, _targetPosition.Height, elapsed, omega);
            _position = new(x.Position, y.Position, width.Position, height.Position);
            _velocity = new(x.Velocity, y.Velocity, width.Velocity, height.Velocity);
            if (IsSettled()) SnapToTarget();
        }
        _current = BuildFrame();
    }

    private static (double Position, double Velocity) Spring(double position, double velocity,
        double target, double seconds, double omega)
    {
        double error = position - target;
        double coefficient = velocity + omega * error;
        double decay = Math.Exp(-omega * seconds);
        // Keep even adversarial repeated redirection finite. This bound is many orders of
        // magnitude outside native geometry and cannot affect an ordinary monitor trajectory.
        const double stateLimit = 1e100;
        return (Math.Clamp(target + (error + coefficient * seconds) * decay, -stateLimit, stateLimit),
            Math.Clamp((velocity - omega * coefficient * seconds) * decay, -stateLimit, stateLimit));
    }

    private bool IsSettled()
    {
        var (geometry, velocity) = FromState(_position, _velocity);
        return Math.Abs(geometry.X - _target.X) <= PositionTolerance &&
            Math.Abs(geometry.Y - _target.Y) <= PositionTolerance &&
            Math.Abs(geometry.Width - _target.Width) <= PositionTolerance &&
            Math.Abs(geometry.Height - _target.Height) <= PositionTolerance &&
            Math.Abs(velocity.X) <= VelocityTolerance && Math.Abs(velocity.Y) <= VelocityTolerance &&
            Math.Abs(velocity.Width) <= VelocityTolerance && Math.Abs(velocity.Height) <= VelocityTolerance;
    }

    private void SnapToTarget()
    {
        _position = _segmentPosition = _targetPosition; _velocity = _segmentVelocity = default;
        _animating = false; _committed = _desired;
    }

    private FullscreenMotionFrame BuildFrame()
    {
        var (geometry, velocity) = _animating ? FromState(_position, _velocity) :
            (new FullscreenMotionVector(_target.X, _target.Y, _target.Width, _target.Height), default(FullscreenMotionVector));
        return new(_animating ? ToPixels(geometry) : _target, geometry, velocity, _target, _generation,
            _desired, _committed, _animating, _closed);
    }

    // Center coordinates avoid anchoring growth to a corner; log extents keep the continuous
    // rectangle positive even when a slower replacement inherits a large shrinking velocity.
    private static FullscreenMotionVector ToState(FullscreenPixelRect rect) =>
        new(rect.X + rect.Width / 2d, rect.Y + rect.Height / 2d, Math.Log(rect.Width), Math.Log(rect.Height));

    private static (FullscreenMotionVector Geometry, FullscreenMotionVector Velocity) FromState(
        FullscreenMotionVector position, FullscreenMotionVector velocity)
    {
        // These defensive bounds are far outside native geometry, but keep pathological
        // repeated retargeting finite before native integer output is safely constrained.
        double width = Math.Exp(Math.Clamp(position.Width, -700, 700));
        double height = Math.Exp(Math.Clamp(position.Height, -700, 700));
        double widthVelocity = FiniteProduct(width, velocity.Width);
        double heightVelocity = FiniteProduct(height, velocity.Height);
        return (new(position.X - width / 2, position.Y - height / 2, width, height),
            new(velocity.X - widthVelocity / 2, velocity.Y - heightVelocity / 2, widthVelocity, heightVelocity));
    }

    private static double FiniteProduct(double positive, double value) =>
        value != 0 && positive > double.MaxValue / Math.Abs(value) ? Math.CopySign(double.MaxValue, value) : positive * value;

    private static FullscreenPixelRect ToPixels(FullscreenMotionVector value)
    {
        int width = (int)Math.Clamp(Math.Round(value.Width, MidpointRounding.AwayFromZero), 1, int.MaxValue);
        int height = (int)Math.Clamp(Math.Round(value.Height, MidpointRounding.AwayFromZero), 1, int.MaxValue);
        int x = (int)Math.Clamp(Math.Round(value.X, MidpointRounding.AwayFromZero), int.MinValue, (long)int.MaxValue - width);
        int y = (int)Math.Clamp(Math.Round(value.Y, MidpointRounding.AwayFromZero), int.MinValue, (long)int.MaxValue - height);
        return new(x, y, width, height);
    }

    private static void Validate(FullscreenPixelRect rect)
    {
        if (rect.Width < 1 || rect.Height < 1 || (long)rect.X + rect.Width > int.MaxValue ||
            (long)rect.Y + rect.Height > int.MaxValue)
            throw new ArgumentOutOfRangeException(nameof(rect), "窗口矩形必须为可表示的正整数屏幕像素范围。");
    }

    private static TimeSpan ValidateResponse(TimeSpan response)
    {
        if (response < MinimumResponse || response > MaximumResponse)
            throw new ArgumentOutOfRangeException(nameof(response), "动效响应时间必须介于 100 毫秒与 2 秒之间。");
        return response;
    }
}
