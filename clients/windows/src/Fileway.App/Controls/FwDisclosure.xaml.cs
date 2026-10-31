using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls;

public sealed partial class FwDisclosure : UserControl
{
    public static readonly DependencyProperty HeaderProperty = DependencyProperty.Register(
        nameof(Header), typeof(string), typeof(FwDisclosure), new PropertyMetadata(""));
    public static readonly DependencyProperty SectionContentProperty = DependencyProperty.Register(
        nameof(SectionContent), typeof(object), typeof(FwDisclosure), new PropertyMetadata(null));

    public FwDisclosure() => InitializeComponent();

    public string Header { get => (string)GetValue(HeaderProperty); set => SetValue(HeaderProperty, value); }
    public object? SectionContent { get => GetValue(SectionContentProperty); set => SetValue(SectionContentProperty, value); }

    private void HeaderToggle_Changed(object sender, RoutedEventArgs args)
    {
        var expanded = HeaderToggle.IsChecked == true;
        Body.Visibility = expanded ? Visibility.Visible : Visibility.Collapsed;
        ChevronRotation.Angle = expanded ? 0 : -90;
    }
}
