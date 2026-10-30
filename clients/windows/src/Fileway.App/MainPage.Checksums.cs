using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private ContentDialog? _checksumDialog;
    private FileChecksumController? _checksumController;
    private Func<bool>? _checksumCurrentSource;
    private bool CanChecksumSelection => BrowserReady && !LoadingRing.IsActive && _session?.Permissions.Download == true
        && _files is IFileChecksumService && SelectedRows() is { Length: 1 } selected && !selected[0].File.IsDirectory;

    private void UpdateChecksumContext()
    {
        if (_checksumDialog is not null && (_disposed || _checksumCurrentSource?.Invoke() != true)) RetireChecksumDialog();
    }
    private void RetireChecksumDialog()
    {
        _checksumController?.Dispose(); _checksumController = null; _checksumCurrentSource = null;
        _checksumDialog?.Hide(); _checksumDialog = null;
    }

    private async void FileChecksum_Click(object sender, RoutedEventArgs args)
    {
        if (!CanChecksumSelection || _browserDialog is not null || _storageDialog is not null || _checksumDialog is not null
            || _files is not IFileChecksumService service || _session is not { } session || _directory is not { } directory) return;
        var source = SelectedRows()[0].File.Resource;
        var generation = _generation;
        bool CurrentSource()
        {
            if (!SameBrowser(session, directory, generation) || _session?.Permissions.Download != true) return false;
            var selected = SelectedRows();
            return selected.Length == 1 && !selected[0].File.IsDirectory && ReferenceEquals(selected[0].File.Resource, source);
        }
        var controller = new FileChecksumController(); controller.SetContext(service, source, session);
        var closed = false;
        var content = new StackPanel { Spacing = 12, MinWidth = 320, MaxWidth = 440 };
        content.Children.Add(new TextBlock { Text = source.DisplayName, FontSize = 18,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        content.Children.Add(new TextBlock { Text = source.DisplayPath, FontSize = 12, TextWrapping = TextWrapping.Wrap });
        var algorithm = new Fileway.App.Controls.ChoiceBox { Header = "算法", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var label in new[] { "MD5", "SHA-1", "SHA-256", "SHA-512" }) algorithm.Items.Add(label);
        algorithm.SelectedIndex = 2;
        var expected = new TextBox { Header = "预期校验和（可选）", MaxLength = 1024, PlaceholderText = "十六进制字符；留空只计算" };
        var validation = new TextBlock { Text = "预期校验和必须与算法长度一致，且只包含十六进制字符。", FontSize = 12,
            TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        content.Children.Add(algorithm); content.Children.Add(expected); content.Children.Add(validation);
        content.Children.Add(new TextBlock { Text = "校验和用于文件一致性核对，不保证来源可信。计算需要读取远端文件；不会下载或修改文件。",
            FontSize = 12, TextWrapping = TextWrapping.Wrap });
        var activity = new Grid { ColumnSpacing = 8, Visibility = Visibility.Collapsed };
        activity.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        activity.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var progress = new ProgressRing { Width = 20, Height = 20 };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(progress, "正在计算文件校验和");
        var activityText = new TextBlock { Text = "正在计算，大文件可能需要等待…", TextWrapping = TextWrapping.Wrap };
        Grid.SetColumn(activityText, 1); activity.Children.Add(progress); activity.Children.Add(activityText);
        var stop = new Button { Content = "停止计算", HorizontalAlignment = HorizontalAlignment.Left, Visibility = Visibility.Collapsed };
        var resultPanel = new StackPanel { Spacing = 8, Visibility = Visibility.Collapsed };
        var digest = new TextBlock { IsTextSelectionEnabled = true, TextWrapping = TextWrapping.Wrap,
            FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Consolas") };
        var comparison = new TextBlock { TextWrapping = TextWrapping.Wrap, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold };
        var metadata = new TextBlock { FontSize = 12, TextWrapping = TextWrapping.Wrap };
        resultPanel.Children.Add(comparison); resultPanel.Children.Add(digest); resultPanel.Children.Add(metadata);
        var error = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Warning, Title = "校验未能完成" };
        content.Children.Add(activity); content.Children.Add(stop); content.Children.Add(resultPanel); content.Children.Add(error);
        var dialog = new ContentDialog
        {
            Title = "文件校验和", Content = new ScrollViewer { Content = content, MaxHeight = 400,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
            PrimaryButtonText = "计算校验和", CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None,
        };
        ChecksumAlgorithm SelectedAlgorithm() => (ChecksumAlgorithm)algorithm.SelectedIndex;
        void Render()
        {
            if (closed || !CurrentSource()) return;
            var busy = controller.IsCalculating;
            var valid = FileChecksumController.TryExpectedDigest(SelectedAlgorithm(), expected.Text, out _);
            algorithm.IsEnabled = expected.IsEnabled = !busy;
            validation.Visibility = valid ? Visibility.Collapsed : Visibility.Visible;
            dialog.IsPrimaryButtonEnabled = !busy && valid;
            progress.IsActive = busy; activity.Visibility = stop.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
            error.Message = controller.Error; error.IsOpen = controller.Error is not null;
            resultPanel.Visibility = controller.Result is null ? Visibility.Collapsed : Visibility.Visible;
            if (controller.Result is { } result)
            {
                digest.Text = result.Digest;
                comparison.Text = result.Comparison switch
                {
                    ChecksumComparison.Matches => "与预期校验和一致", ChecksumComparison.Differs => "与预期校验和不同",
                    _ => "计算完成，未提供预期校验和",
                };
                metadata.Text = $"{algorithm.SelectedItem} · {DownloadPresentationPolicy.FormatBytes(result.Size)}";
            }
        }
        algorithm.SelectionChanged += (_, _) => { controller.Cancel(); Render(); };
        expected.TextChanged += (_, _) => { controller.Cancel(); Render(); };
        stop.Click += (_, _) => { controller.Cancel(); Render(); };
        dialog.Opened += (_, _) => algorithm.Focus(FocusState.Keyboard);
        dialog.Closed += (_, _) => { closed = true; controller.Dispose(); };
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true;
            if (closed || !CurrentSource() || controller.IsCalculating) return;
            var pending = controller.CalculateAsync(SelectedAlgorithm(), expected.Text);
            Render();
            await pending;
            if (!closed && CurrentSource()) Render();
        };
        _checksumDialog = dialog; _checksumController = controller; _checksumCurrentSource = CurrentSource;
        Render();
        try { await ShowBrowserDialogAsync(dialog); }
        finally
        {
            closed = true; controller.Dispose();
            if (ReferenceEquals(_checksumDialog, dialog))
            { _checksumDialog = null; _checksumController = null; _checksumCurrentSource = null; }
        }
    }
}
