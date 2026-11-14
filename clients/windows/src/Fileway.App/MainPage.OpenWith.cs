using System.Diagnostics;
using Fileway.App.Controls;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private OpenWithPreferences? _openWith;
    private readonly Dictionary<TransferTaskRef, TaskCompletionSource<DownloadItem>> _pendingExternalDownloadOpens = [];

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

    private static bool IsAvailableApplication(string? path) =>
        path is { Length: >= 4 } && char.IsAsciiLetter(path[0]) && path[1] == ':' &&
        path[2] == '\\' && Path.GetExtension(path).Equals(".exe", StringComparison.OrdinalIgnoreCase) && File.Exists(path);

    /// <summary>Hands the verified local copy to the application selected when the file was opened.</summary>
    private async Task OpenWithExternalAsync(FileRow row, Func<bool> isCurrentFile,
        OpenPreference preference, string? applicationPath)
    {
        bool useSelectedApplication = preference == OpenPreference.SelectedApplication;
        if (useSelectedApplication && !IsAvailableApplication(applicationPath))
        {
            SetNotice("指定应用不可用", string.Empty, InfoBarSeverity.Warning);
            return;
        }
        var applicationName = useSelectedApplication ? Path.GetFileNameWithoutExtension(applicationPath) : "Windows 默认应用";
        if (_downloads is not IDownloadService service || _downloads is not IDownloadControlService control ||
            ActiveDownloadContext is not { } context || _session?.Permissions.Download != true)
        {
            SetNotice(_session?.Permissions.Download == false ? "当前连接没有下载权限" : "下载服务尚未就绪",
                string.Empty, InfoBarSeverity.Warning);
            return;
        }
        try
        {
            var preview = await service.PrepareAsync(context, row.File.Resource, _lifetime.Token);
            if (!isCurrentFile() || !IsCurrentDownloadContext(context)) return;

            var content = new StackPanel { Spacing = 8, MaxWidth = 440 };
            content.Children.Add(new TextBlock
            {
                Text = $"先下载并保留本地副本，再用 {applicationName} 打开。",
                TextWrapping = TextWrapping.Wrap,
            });
            content.Children.Add(new TextBlock
            {
                Text = preview.DestinationDisplayPath,
                TextWrapping = TextWrapping.Wrap,
                IsTextSelectionEnabled = true,
                Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
            });
            var confirm = new ContentDialog
            {
                Title = "下载并打开文件？",
                Content = content,
                PrimaryButtonText = "下载并打开",
                CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.None,
            };
            DialogOrdering.FocusCloseButton(confirm);
            if (await ShowBrowserDialogAsync(confirm) != ContentDialogResult.Primary ||
                !isCurrentFile() || !IsCurrentDownloadContext(context) || _session?.Permissions.Download != true) return;

            await DownloadAndOpenExternalAsync(service, control, preview, useSelectedApplication ? applicationPath : null);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (IsCurrentDownloadContext(context)) ShowDownloadError("外部打开未完成", error);
        }
    }

    private async Task DownloadAndOpenExternalAsync(IDownloadService service, IDownloadControlService control,
        DownloadPreview preview, string? applicationPath)
    {
        var context = preview.Context;
        var task = new TransferTaskRef(context.Account, preview.Draft.Id);
        var completion = new TaskCompletionSource<DownloadItem>(TaskCreationOptions.RunContinuationsAsynchronously);
        bool CanOpen() => IsCurrentDownloadContext(context) && _session?.Permissions.Download == true &&
            _pendingExternalDownloadOpens.TryGetValue(task, out var current) && ReferenceEquals(current, completion);

        // A fast download can publish before SubmitAsync returns. Observe it from admission,
        // but wait for the acknowledged task before launching the application.
        _pendingExternalDownloadOpens.Add(task, completion);
        try
        {
            var item = await service.SubmitAsync(preview, _lifetime.Token);
            if (!IsCurrentDownloadContext(context)) return;
            if (item.Context != context || item.Snapshot.Task != task)
                throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "下载任务与确认记录不一致。"));
            AcceptDownloadItem(item);
            if (!completion.Task.IsCompleted && !DownloadNotice.IsOpen && _downloadItems.TryGetValue(task, out var active))
            {
                var status = DownloadPresentationPolicy.EffectiveStatus(active);
                if (DownloadPresentationPolicy.IsActive(status))
                    ShowDownloadToast("正在下载本地副本", InfoBarSeverity.Informational, $"{task.Id}:external-open",
                        new DownloadNoticeLink(context, task, null, active.Snapshot.Generation, status));
            }

            var result = await completion.Task.WaitAsync(_lifetime.Token);
            if (!CanOpen() || DownloadPresentationPolicy.EffectiveStatus(result) != DownloadStatus.Completed) return;
            var verified = await control.VerifyLocalOpenAsync(context, task, _lifetime.Token);
            if (!CanOpen()) return;
            if (!verified)
            {
                SetNotice("本地文件校验未通过", string.Empty, InfoBarSeverity.Warning);
                return;
            }
            if (applicationPath is not null && !IsAvailableApplication(applicationPath))
            {
                SetNotice("指定应用不可用，本地副本已保存", string.Empty, InfoBarSeverity.Warning);
                return;
            }
            var opened = applicationPath is not null
                ? LaunchSelectedApplication(applicationPath, result.DestinationDisplayPath)
                : await LaunchDefaultApplicationAsync(result.DestinationDisplayPath, CanOpen);
            if (!CanOpen()) return;
            var title = !opened ? "应用未能打开文件" : applicationPath is not null ? "已交给指定应用" : "已交给系统应用";
            SetNotice(title, string.Empty, opened ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
        }
        finally { _pendingExternalDownloadOpens.Remove(task); }
    }

    private static bool LaunchSelectedApplication(string applicationPath, string filePath)
    {
        var start = new ProcessStartInfo(applicationPath) { UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(applicationPath)! };
        start.ArgumentList.Add(filePath);
        using var process = Process.Start(start);
        return process is not null;
    }

    private static async Task<bool> LaunchDefaultApplicationAsync(string path, Func<bool> isCurrent)
    {
        var file = await Windows.Storage.StorageFile.GetFileFromPathAsync(path);
        return isCurrent() && await Windows.System.Launcher.LaunchFileAsync(file);
    }

    private async void OpenWithSettings_Click(object sender, RoutedEventArgs args)
    {
        var preferences = OpenWith;
        var panel = new StackPanel { Spacing = 8, MinWidth = 344, MaxWidth = 440 };
        panel.Children.Add(new TextBlock
        {
            Text = "使用外部应用时，先确认下载位置，再打开本地副本。",
            TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
        });
        var choices = new List<(OpenWithCategory Category, ChoiceBox Box)>();
        var applicationPath = preferences.MarkdownApplicationPath;
        var applicationLabel = new TextBlock
        {
            Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
            TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center,
        };
        AutomationProperties.SetAutomationId(applicationLabel, "MarkdownSelectedApplication");
        var chooseApplication = new Button
        {
            Content = "选择应用…", Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"],
        };
        AutomationProperties.SetAutomationId(chooseApplication, "ChooseMarkdownApplication");
        var applicationRow = new Grid { ColumnSpacing = 10, Visibility = Visibility.Collapsed };
        applicationRow.ColumnDefinitions.Add(new ColumnDefinition());
        applicationRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(chooseApplication, 1);
        applicationRow.Children.Add(applicationLabel);
        applicationRow.Children.Add(chooseApplication);
        foreach (var category in Enum.GetValues<OpenWithCategory>())
        {
            var box = new ChoiceBox { HorizontalAlignment = HorizontalAlignment.Stretch };
            box.Items.Add("内置查看器");
            box.Items.Add("系统默认应用");
            if (category == OpenWithCategory.Markdown)
            {
                box.Items.Add("指定应用");
                AutomationProperties.SetAutomationId(box, "MarkdownOpenPreference");
            }
            box.SelectedIndex = preferences.For(category) switch
            {
                OpenPreference.SystemDefault => 1,
                OpenPreference.SelectedApplication when category == OpenWithCategory.Markdown => 2,
                _ => 0,
            };
            panel.Children.Add(DialogOrdering.FieldRow(OpenWithPreferences.Label(category), box, 104));
            if (category == OpenWithCategory.Markdown) panel.Children.Add(applicationRow);
            choices.Add((category, box));
        }
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            RequestedTheme = ActualTheme,
            Title = "打开方式",
            Content = new ScrollViewer { Content = panel, MaxHeight = DialogOrdering.ContentMaxHeight(this, 520),
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
            PrimaryButtonText = "保存",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
        };
        var markdownChoice = choices.Find(choice => choice.Category == OpenWithCategory.Markdown).Box;
        void UpdateApplicationRow()
        {
            bool visible = markdownChoice.SelectedIndex == 2;
            bool available = IsAvailableApplication(applicationPath);
            applicationRow.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
            applicationLabel.Text = available ? Path.GetFileName(applicationPath) :
                string.IsNullOrEmpty(applicationPath) ? "请选择 Markdown 应用" : "原应用路径已失效";
            ToolTipService.SetToolTip(applicationLabel, available ? applicationPath : null);
            dialog.IsPrimaryButtonEnabled = !visible || available;
        }
        markdownChoice.SelectionChanged += (_, _) => UpdateApplicationRow();
        chooseApplication.Click += async (_, _) =>
        {
            chooseApplication.IsEnabled = false;
            try
            {
                var picker = new Windows.Storage.Pickers.FileOpenPicker
                {
                    CommitButtonText = "选择应用",
                    SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.ComputerFolder,
                };
                WinRT.Interop.InitializeWithWindow.Initialize(picker, Microsoft.UI.Win32Interop.GetWindowFromWindowId(_uploadOwner));
                picker.FileTypeFilter.Add(".exe");
                var file = await picker.PickSingleFileAsync().AsTask(_lifetime.Token);
                if (file is not null)
                {
                    applicationPath = file.Path;
                    UpdateApplicationRow();
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception) { applicationLabel.Text = "应用选择器未能打开，请重试"; }
            finally { chooseApplication.IsEnabled = true; }
        };
        UpdateApplicationRow();
        Fileway.App.Controls.DialogOrdering.FocusCloseButton(dialog);
        if (await ShowBrowserDialogAsync(dialog) != ContentDialogResult.Primary) return;
        var updated = preferences;
        foreach (var (category, box) in choices)
            updated = updated.With(category, box.SelectedIndex switch
            {
                1 => OpenPreference.SystemDefault,
                2 when category == OpenWithCategory.Markdown => OpenPreference.SelectedApplication,
                _ => OpenPreference.BuiltIn,
            });
        updated = updated with { MarkdownApplicationPath = applicationPath };
        try
        {
            OpenWithPreferencesStore.Save(updated);
            _openWith = updated;
            SetNotice("打开方式已保存", "新的选择会在下次打开文件时生效。", InfoBarSeverity.Success);
        }
        catch (Exception error) { SetNotice("打开方式未保存", error, InfoBarSeverity.Warning); }
    }
}
