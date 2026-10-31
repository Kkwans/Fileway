using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.System;

namespace Fileway.App.Controls;

public sealed partial class AboutContent : UserControl
{
    public AboutContent(string version)
    {
        InitializeComponent();
        VersionText.Text = version;
    }

    private async void OpenSource_Click(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchUriAsync(new Uri("https://github.com/Kkwans/Fileway"));

    private async void OpenLicense_Click(object sender, RoutedEventArgs e) =>
        await Launcher.LaunchUriAsync(new Uri("https://github.com/Kkwans/Fileway/blob/codex/windows-native-client/clients/windows/LICENSE"));
}
