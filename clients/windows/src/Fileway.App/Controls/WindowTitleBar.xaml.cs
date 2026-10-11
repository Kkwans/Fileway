using Fileway.App.Branding;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

/// <summary>Fluent caption content; native window movement and Snap belong to its owner.</summary>
public sealed partial class WindowTitleBar : UserControl
{
    public static readonly DependencyProperty CaptionProperty = DependencyProperty.Register(
        nameof(Caption), typeof(string), typeof(WindowTitleBar),
        new PropertyMetadata(string.Empty, Caption_Changed));

    public WindowTitleBar()
    {
        InitializeComponent();
        Caption = AppBranding.DisplayName;
        SetButtonLabel(MinimizeButton, AppBranding.IsChinese ? "最小化" : "Minimize");
        SetButtonLabel(CloseButton, AppBranding.IsChinese ? "关闭" : "Close");
        SetMaximized(false);
    }

    public string Caption
    {
        get => (string)GetValue(CaptionProperty);
        set => SetValue(CaptionProperty, value ?? string.Empty);
    }

    public FrameworkElement DragRegion => DragSurface;
    public FrameworkElement MaximizeButtonElement => MaximizeButton;

    public event EventHandler? MinimizeRequested;
    public event EventHandler? MaximizeRequested;
    public event EventHandler? CloseRequested;

    public void SetMaximized(bool maximized)
    {
        MaximizeIcon.IconName = maximized ? "SquareMultiple" : "Square";
        SetButtonLabel(MaximizeButton, maximized
            ? AppBranding.IsChinese ? "还原" : "Restore"
            : AppBranding.IsChinese ? "最大化" : "Maximize");
    }

    private static void Caption_Changed(DependencyObject sender, DependencyPropertyChangedEventArgs args)
    {
        var titleBar = (WindowTitleBar)sender;
        if (titleBar.CaptionText is null) return;
        var caption = args.NewValue as string ?? string.Empty;
        titleBar.CaptionText.Text = caption;
        ToolTipService.SetToolTip(titleBar.DragSurface, caption);
        AutomationProperties.SetName(titleBar.CaptionText, caption);
    }

    private static void SetButtonLabel(Button button, string label)
    {
        AutomationProperties.SetName(button, label);
        ToolTipService.SetToolTip(button, label);
    }

    private void CaptionButton_Loaded(object sender, RoutedEventArgs args)
    {
        var button = (Button)sender;
        if (button.Content is not Control icon) return;
        button.ApplyTemplate();
        if (FindCaptionPresenter(button, icon) is not { } presenter) return;
        // SDK visual states change this template presenter's Foreground, not
        // Button.Foreground. The XAML button binding remains a safe fallback.
        icon.SetBinding(Control.ForegroundProperty, new Binding
        {
            Source = presenter,
            Path = new PropertyPath(nameof(ContentPresenter.Foreground)),
            Mode = BindingMode.OneWay,
        });
    }

    private static ContentPresenter? FindCaptionPresenter(DependencyObject parent, Control icon)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is ContentPresenter presenter && ReferenceEquals(presenter.Content, icon)) return presenter;
            if (FindCaptionPresenter(child, icon) is { } nested) return nested;
        }
        return null;
    }

    private void Minimize_Click(object sender, RoutedEventArgs args) => MinimizeRequested?.Invoke(this, EventArgs.Empty);
    private void Maximize_Click(object sender, RoutedEventArgs args) => MaximizeRequested?.Invoke(this, EventArgs.Empty);
    private void Close_Click(object sender, RoutedEventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);
}
