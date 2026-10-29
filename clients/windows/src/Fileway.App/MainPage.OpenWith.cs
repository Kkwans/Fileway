using Fileway.App.Controls;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private OpenWithPreferences? _openWith;

    private OpenWithPreferences OpenWith => _openWith ??= OpenWithPreferencesStore.Load();

    private static OpenWithCategory? CategoryOf(FileRow row)
    {
        if (row.File.IsDirectory) return null;
        if (row.Name.EndsWith(".md", StringComparison.OrdinalIgnoreCase) || row.Name.EndsWith(".markdown", StringComparison.OrdinalIgnoreCase))
            return OpenWithCategory.Markdown;
        if (IsPdf(row)) return OpenWithCategory.Pdf;
        if (IsZipArchive(row)) return OpenWithCategory.Archive;
        if (row.IsImage) return OpenWithCategory.Image;
        if (row.IsAudio) return OpenWithCategory.Audio;
        if (row.IsMedia) return OpenWithCategory.Video;
        if (row.IsText) return OpenWithCategory.Text;
        return null;
    }

    /// <summary>Downloads first, verifies the published file, then hands it to the Windows default app.</summary>
    private async Task OpenWithSystemAsync(FileRow row)
    {
        if (_downloads is not IDownloadService service || _downloads is not IDownloadControlService control ||
            ActiveDownloadContext is not { } context || _session?.Permissions.Download != true)
        {
            SetNotice("无法使用系统应用打开", "当前连接没有下载权限，或下载服务尚未就绪。", InfoBarSeverity.Warning);
            return;
        }
        try
        {
            var preview = await service.PrepareAsync(context, row.File.Resource, _lifetime.Token);
            var item = await service.SubmitAsync(preview, _lifetime.Token);
            SetNotice("正在准备系统打开", $"{row.Name} 会先下载到默认下载目录，完成后交给 Windows 默认应用。", InfoBarSeverity.Informational);
            var task = item.Snapshot.Task;
            for (var attempt = 0; attempt < 1800 && !_disposed; attempt++)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(400), _lifetime.Token);
                var page = await service.ListAsync(context, null, 200, _lifetime.Token);
                var current = page.Items.FirstOrDefault(candidate => candidate.Snapshot.Task == task);
                if (current is null) continue;
                if (current.Status == DownloadStatus.Completed && current.Snapshot.LocalPublished)
                {
                    if (await control.VerifyLocalOpenAsync(context, task, _lifetime.Token) is not true)
                    {
                        SetNotice("本地文件校验未通过", "下载已完成，但本地文件未通过身份或大小校验，未交给系统打开。", InfoBarSeverity.Warning);
                        return;
                    }
                    var file = await Windows.Storage.StorageFile.GetFileFromPathAsync(current.DestinationDisplayPath);
                    var opened = await Windows.System.Launcher.LaunchFileAsync(file);
                    SetNotice(opened ? "已交给系统应用" : "没有可用的系统应用",
                        opened ? $"{row.Name} 已交给 Windows 默认应用打开。" : "Windows 没有注册能打开此文件类型的应用。",
                        opened ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
                    return;
                }
                if (current.Status is DownloadStatus.Failed or DownloadStatus.Canceled)
                {
                    SetNotice("下载未完成", "文件未能下载到本地，因此没有调用系统应用。", InfoBarSeverity.Warning);
                    return;
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (!_disposed) SetNotice("系统打开未完成", ErrorMessage(error), InfoBarSeverity.Warning);
        }
    }

    private async void OpenWithSettings_Click(object sender, RoutedEventArgs args)
    {
        var preferences = OpenWith;
        var panel = new StackPanel { Spacing = 12, MinWidth = 380, MaxWidth = 460 };
        panel.Children.Add(new TextBlock
        {
            Text = "内置查看器保留在应用内；选择“系统默认应用”时，会先把文件下载到默认下载目录，再交给 Windows 打开（例如 Typora 打开 Markdown）。",
            TextWrapping = TextWrapping.Wrap, FontSize = 12, Opacity = 0.75,
        });
        var choices = new List<(OpenWithCategory Category, ChoiceBox Box)>();
        foreach (var category in Enum.GetValues<OpenWithCategory>())
        {
            var box = new ChoiceBox
            {
                Header = OpenWithPreferences.Label(category),
                SelectedIndex = preferences.For(category) == OpenPreference.BuiltIn ? 0 : 1,
                HorizontalAlignment = HorizontalAlignment.Stretch,
            };
            box.Items.Add("内置查看器");
            box.Items.Add("系统默认应用");
            panel.Children.Add(box);
            choices.Add((category, box));
        }
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            RequestedTheme = ActualTheme,
            Title = "打开方式",
            Content = new ScrollViewer { Content = panel, MaxHeight = 520, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            PrimaryButtonText = "保存",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await ShowBrowserDialogAsync(dialog) != ContentDialogResult.Primary) return;
        var updated = preferences;
        foreach (var (category, box) in choices)
            updated = updated.With(category, box.SelectedIndex == 1 ? OpenPreference.SystemDefault : OpenPreference.BuiltIn);
        OpenWithPreferencesStore.Save(updated);
        _openWith = updated;
        SetNotice("打开方式已保存", "新的选择会在下次打开文件时生效。", InfoBarSeverity.Success);
    }
}
