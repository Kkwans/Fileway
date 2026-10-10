using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Markup;

namespace Fileway.App.Controls.Playback;

public sealed partial class ScreenboxVectorIcon : UserControl
{
    public static readonly DependencyProperty IconNameProperty = DependencyProperty.Register(
        nameof(IconName), typeof(string), typeof(ScreenboxVectorIcon), new PropertyMetadata(null, IconName_Changed));

    private bool _initialized;
    private bool _resolveQueued;

    public ScreenboxVectorIcon()
    {
        InitializeComponent();
        _initialized = true;
        Loaded += (_, _) => ResolveIcon();
    }

    public string? LoadDiagnostic { get; private set; }

    public string? IconName
    {
        get => (string?)GetValue(IconNameProperty);
        set => SetValue(IconNameProperty, value);
    }

    private static void IconName_Changed(DependencyObject sender, DependencyPropertyChangedEventArgs args)
    {
        var icon = (ScreenboxVectorIcon)sender;
        // The XAML loader can set this DP before resources/artwork are ready.
        // Only record the value here; lookup runs after load on the UI queue.
        if (!icon._initialized || !icon.IsLoaded || icon._resolveQueued) return;
        icon._resolveQueued = true;
        icon.DispatcherQueue.TryEnqueue(() =>
        {
            icon._resolveQueued = false;
            if (icon.IsLoaded) icon.ResolveIcon();
        });
    }

    private void ResolveIcon()
    {
        if (!_initialized || IconName is not { } name) return;
        var key = name + "Geometry";
        object? resource = null;
        try
        {
            if (!TryFindResource(Resources, key, out resource))
                throw new InvalidOperationException("ResourceKeyMissing");
            if (resource is not string pathString)
                throw new InvalidOperationException("ExpectedPathString");
            // Resource values are immutable strings. The framework converter
            // creates a fresh native Geometry owned only by this rendered Path.
            Artwork.Data = (Geometry)XamlBindingHelper.ConvertValue(typeof(Geometry), pathString);
            ResourceError.Visibility = Visibility.Collapsed;
            LoadDiagnostic = null;
            AutomationProperties.SetHelpText(this, string.Empty);
        }
        catch (Exception exception)
        {
            // Only public resource names and exception types, never SVG data or
            // private media. Keep a visible error rather than a silent blank icon.
            var reason = exception.Message is "ResourceKeyMissing" or "ExpectedPathString" ? exception.Message : "NativeResourceLookupFailed";
            LoadDiagnostic = $"图标资源加载失败：{key}; resource={resource?.GetType().FullName ?? "missing"}; exception={exception.GetType().FullName}; HRESULT=0x{exception.HResult:X8}; inner={exception.InnerException?.GetType().FullName ?? "none"}; reason={reason}";
            ResourceError.Visibility = Visibility.Visible;
            const string userMessage = "图标暂不可用，请重新打开播放窗口";
            AutomationProperties.SetHelpText(this, userMessage);
            System.Diagnostics.Trace.TraceError(LoadDiagnostic);
            DependencyObject? ancestor = VisualTreeHelper.GetParent(this);
            while (ancestor is not null)
            {
                if (ancestor is ScreenboxPlayerControls controls) { controls.ReportError(userMessage); break; }
                ancestor = VisualTreeHelper.GetParent(ancestor);
            }
        }
    }

    internal static bool TryFindResource(ResourceDictionary dictionary, string key, out object? value)
    {
        if (dictionary.TryGetValue(key, out value)) return true;
        foreach (var merged in dictionary.MergedDictionaries)
            if (TryFindResource(merged, key, out value)) return true;
        value = null;
        return false;
    }
}
