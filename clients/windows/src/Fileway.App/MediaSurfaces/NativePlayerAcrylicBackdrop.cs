using Microsoft.UI.Composition;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.MediaSurfaces;

/// <summary>SDK acrylic for bounded playback chrome, with a stable material while visible.</summary>
public sealed class NativePlayerAcrylicBackdrop : SystemBackdrop
{
    private sealed record TargetState(DesktopAcrylicController Controller, XamlRoot Root, bool SystemQueueBefore, bool SystemQueueAfter);
    private readonly Dictionary<ICompositionSupportsSystemBackdrop, TargetState> _targets = [];
    private readonly HashSet<ICompositionSupportsSystemBackdrop> _baseTargets = [];
    private readonly PlayerBackdropState? _activity;

    public NativePlayerAcrylicBackdrop() { }
    internal NativePlayerAcrylicBackdrop(PlayerBackdropState activity) => _activity = activity;
    internal static bool IsSupported => DesktopAcrylicController.IsSupported();

    protected override void OnTargetConnected(ICompositionSupportsSystemBackdrop connectedTarget, XamlRoot xamlRoot)
    {
        var target = connectedTarget;
        var root = xamlRoot;
        base.OnTargetConnected(target, root);
        _baseTargets.Add(target);
        var systemQueueBefore = Windows.System.DispatcherQueue.GetForCurrentThread() is not null;
        Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread().EnsureSystemDispatcherQueue();
        var systemQueueAfter = Windows.System.DispatcherQueue.GetForCurrentThread() is not null;
        var controller = new DesktopAcrylicController
        {
            Kind = DesktopAcrylicKind.Thin,
            TintColor = Windows.UI.Color.FromArgb(255, 16, 16, 18),
            TintOpacity = 0.72f,
            LuminosityOpacity = 0f,
        };
        var added = false;
        try
        {
            added = controller.AddSystemBackdropTarget(target);
            if (!added) throw new InvalidOperationException("The native acrylic target is unavailable.");
            if (_targets.Count == 0 && _activity is not null) _activity.Changed += Activity_Changed;
            _targets.Add(target, new TargetState(controller, root, systemQueueBefore, systemQueueAfter));
            Configure(target);
        }
        catch
        {
            _targets.Remove(target);
            if (_targets.Count == 0 && _activity is not null) _activity.Changed -= Activity_Changed;
            try { if (added) controller.RemoveSystemBackdropTarget(target); }
            finally { controller.Dispose(); }
            throw;
        }
    }

    protected override void OnDefaultSystemBackdropConfigurationChanged(ICompositionSupportsSystemBackdrop target, XamlRoot xamlRoot)
    {
        base.OnDefaultSystemBackdropConfigurationChanged(target, xamlRoot);
        if (_targets.ContainsKey(target)) Configure(target);
    }

    private void Configure(ICompositionSupportsSystemBackdrop target)
    {
        var state = _targets[target];
        var configuration = GetDefaultSystemBackdropConfiguration(target, state.Root);
        if (_activity is not null)
        {
            // Playback chrome follows in-app material behavior: switching focus
            // must not replace glass with a solid plate. The SDK still observes
            // transparency, high-contrast and system rendering capability policy.
            configuration.IsInputActive = true;
            configuration.Theme = _activity.Theme switch
            {
                ElementTheme.Dark => SystemBackdropTheme.Dark,
                ElementTheme.Light => SystemBackdropTheme.Light,
                _ => configuration.Theme,
            };
        }
        state.Controller.SetSystemBackdropConfiguration(configuration);
        try
        {
            var dataRoot = Environment.GetEnvironmentVariable("FILEWAY_WINDOWS_DATA_ROOT");
            if (!string.IsNullOrEmpty(dataRoot))
            {
                var directory = Path.Combine(dataRoot, "diagnostics");
                Directory.CreateDirectory(directory);
                File.WriteAllText(Path.Combine(directory, "player-native-acrylic.json"), System.Text.Json.JsonSerializer.Serialize(new
                {
                    timestampUtc = DateTimeOffset.UtcNow,
                    controller = "SDK.DesktopAcrylic.Thin", attached = true,
                    materialActive = configuration.IsInputActive, ownerForeground = _activity?.IsInputActive,
                    theme = configuration.Theme.ToString(),
                    systemQueueBefore = state.SystemQueueBefore, systemQueueAfter = state.SystemQueueAfter,
                    tintOpacity = state.Controller.TintOpacity, luminosityOpacity = state.Controller.LuminosityOpacity,
                    tintColor = state.Controller.TintColor.ToString(System.Globalization.CultureInfo.InvariantCulture),
                }));
            }
        }
        catch (Exception) { }
    }

    private void Activity_Changed(object? sender, EventArgs args)
    {
        foreach (var target in _targets.Keys) Configure(target);
    }

    protected override void OnTargetDisconnected(ICompositionSupportsSystemBackdrop disconnectedTarget)
    {
        var target = disconnectedTarget;
        if (!_baseTargets.Remove(target)) return;
        try
        {
            if (_targets.Remove(target, out var state))
            {
                try { state.Controller.RemoveSystemBackdropTarget(target); }
                finally { state.Controller.Dispose(); }
            }
        }
        finally
        {
            if (_targets.Count == 0 && _activity is not null) _activity.Changed -= Activity_Changed;
            base.OnTargetDisconnected(target);
        }
    }
}

internal sealed class PlayerBackdropState
{
    internal bool IsInputActive { get; private set; }
    internal ElementTheme Theme { get; private set; }
    internal int? HostBackdropHresult { get; private set; }
    internal event EventHandler? Changed;
    internal void SetHostBackdropResult(int result) => HostBackdropHresult = result;
    internal void Update(bool isInputActive, ElementTheme theme)
    {
        if (IsInputActive == isInputActive && Theme == theme) return;
        IsInputActive = isInputActive;
        Theme = theme;
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
