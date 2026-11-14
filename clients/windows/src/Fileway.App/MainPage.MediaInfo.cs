using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App;

public sealed partial class MainPage
{
    private ContentDialog? _mediaInfoDialog;
    private Func<bool>? _mediaInfoCurrentSource;

    private bool CanMediaInfoSelection => BrowserReady && !LoadingRing.IsActive && _session?.Permissions.Download == true
        && _files is IMediaInfoService && SelectedRows() is { Length: 1 } selected
        && selected[0].File.MediaType is "image" or "video" or "audio";

    private void UpdateMediaInfoContext()
    {
        if (_mediaInfoDialog is not null && (_disposed || _mediaInfoCurrentSource?.Invoke() != true)) RetireMediaInfoDialog();
    }

    private void RetireMediaInfoDialog()
    {
        _mediaInfoCurrentSource = null;
        _mediaInfoDialog?.Hide(); _mediaInfoDialog = null;
    }

    private static string MediaTypeLabel(string type) => type switch { "image" => "图片", "video" => "视频", "audio" => "音频", _ => "媒体" };

    private static string MediaDurationLabel(double seconds)
    {
        var time = TimeSpan.FromSeconds(seconds);
        return time.TotalHours >= 1 ? time.ToString(@"h\:mm\:ss", System.Globalization.CultureInfo.InvariantCulture) : time.ToString(@"m\:ss", System.Globalization.CultureInfo.InvariantCulture);
    }

    private static string MediaBitRateLabel(long bitRate) => bitRate >= 1_000_000
        ? (bitRate / 1_000_000.0).ToString("0.00", System.Globalization.CultureInfo.CurrentCulture) + " Mbps"
        : Math.Round(bitRate / 1000.0).ToString(System.Globalization.CultureInfo.CurrentCulture) + " kbps";

    private static string MediaTrackLabel(MediaTrackInfo track)
    {
        string codec = track.Codec == "hdmv_pgs_subtitle" ? "PGS 图形字幕" : track.Codec.ToUpperInvariant();
        var parts = new List<string> { string.IsNullOrEmpty(track.Title) ? (track.Language ?? "未命名") : track.Title!, codec };
        if (track.Default) parts.Add("默认");
        if (track.Forced) parts.Add("强制");
        return string.Join(" · ", parts);
    }

    private static void AddMediaField(StackPanel host, string label, string value)
    {
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 3, 0, 3) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var key = new TextBlock { Text = label, FontSize = 13, Style = (Style)Application.Current.Resources["FwCaptionTextStyle"] };
        var val = new TextBlock { Text = value, FontSize = 13, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
        Grid.SetColumn(val, 1); row.Children.Add(key); row.Children.Add(val); host.Children.Add(row);
    }

    private static void AddMediaSection(StackPanel host, string title) =>
        host.Children.Add(new TextBlock { Text = title, FontSize = 13, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Margin = new Thickness(0, 10, 0, 2) });

    private async void MediaInfo_Click(object sender, RoutedEventArgs args)
    {
        if (!CanMediaInfoSelection || _browserDialog is not null || _mediaInfoDialog is not null
            || _files is not IMediaInfoService service || _session is not { } session || _directory is not { } directory) return;
        var source = SelectedRows()[0].File.Resource;
        var generation = _generation;
        bool Current() => !_disposed && SameBrowser(session, directory, generation) && _session?.Permissions.Download == true;
        var closed = false; var requestId = 0; var locationRequested = false; string? locationError = null; MediaInfo? lastInfo = null;
        var caption = (Style)Application.Current.Resources["FwCaptionTextStyle"];
        var content = new StackPanel { Spacing = 8, MinWidth = 360, MaxWidth = 480 };
        content.Children.Add(new TextBlock { Text = source.DisplayName, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        content.Children.Add(new TextBlock { Text = source.DisplayPath, FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption });
        var loading = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Margin = new Thickness(0, 8, 0, 8) };
        var ring = new ProgressRing { Width = 20, Height = 20, IsActive = true };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(ring, "正在读取媒体信息");
        loading.Children.Add(ring); loading.Children.Add(new TextBlock { Text = "正在读取媒体信息…", VerticalAlignment = VerticalAlignment.Center });
        content.Children.Add(loading);
        var results = new StackPanel { Spacing = 2, Visibility = Visibility.Collapsed };
        content.Children.Add(results);
        var error = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Error, Title = "媒体信息读取失败" };
        content.Children.Add(error);
        var dialog = new ContentDialog
        {
            Title = "媒体信息", Content = new ScrollViewer { Content = content, MaxHeight = 500, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
            CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None,
        };
        void RenderInfo(MediaInfo info)
        {
            results.Children.Clear();
            AddMediaField(results, "类型", MediaTypeLabel(info.Type));
            AddMediaField(results, "大小", DownloadPresentationPolicy.FormatBytes(info.Size));
            if (info.Modified is { } modified) AddMediaField(results, "修改时间", modified.ToLocalTime().ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture));
            if (info.Width is { } w && info.Height is { } h) AddMediaField(results, "分辨率", $"{w} × {h}");
            if (info.DurationSeconds is { } duration && duration > 0) AddMediaField(results, "时长", MediaDurationLabel(duration));
            if (!string.IsNullOrEmpty(info.Format)) AddMediaField(results, "封装", info.Format!);
            if (!string.IsNullOrEmpty(info.VideoCodec)) AddMediaField(results, "视频编码", info.VideoCodec!.ToUpperInvariant());
            if (info.Hdr) AddMediaField(results, "动态范围", "HDR10 · 兼容播放映射为 SDR");
            if (!string.IsNullOrEmpty(info.AudioCodec)) AddMediaField(results, "音频编码", info.AudioCodec!.ToUpperInvariant());
            if (info.AudioTracks.Count > 0) AddMediaField(results, "内挂音轨", info.AudioTracks.Count + " 条");
            if (info.SubtitleTracks.Count > 0) AddMediaField(results, "内挂字幕", info.SubtitleTracks.Count + " 条");
            if (info.BitRate is { } bitRate && bitRate > 0) AddMediaField(results, "码率", MediaBitRateLabel(bitRate));
            if (info.SampleRate is { } sampleRate && sampleRate > 0) AddMediaField(results, "采样率", (sampleRate / 1000.0).ToString("0.0", System.Globalization.CultureInfo.CurrentCulture) + " kHz");
            if (info.AudioTracks.Count > 0)
            {
                AddMediaSection(results, "内挂音轨");
                foreach (var track in info.AudioTracks) AddMediaField(results, "轨道 " + track.Index, MediaTrackLabel(track));
            }
            if (info.SubtitleTracks.Count > 0)
            {
                AddMediaSection(results, "内挂字幕");
                foreach (var track in info.SubtitleTracks) AddMediaField(results, "轨道 " + track.Index, MediaTrackLabel(track));
                if (info.SubtitleTracks.Any(track => track.Codec == "hdmv_pgs_subtitle"))
                    results.Children.Add(new TextBlock { Text = "PGS 图形字幕可在播放器字幕菜单中选择，需使用兼容转码。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption });
            }
            var metadata = new (string Label, string? Value)[] { ("标题", info.Title), ("艺术家", info.Artist), ("专辑", info.Album), ("日期", info.Date) };
            if (metadata.Any(item => !string.IsNullOrEmpty(item.Value)))
            {
                AddMediaSection(results, "作品信息");
                foreach (var item in metadata) if (!string.IsNullOrEmpty(item.Value)) AddMediaField(results, item.Label, item.Value!);
            }
            AddMediaSection(results, "位置信息");
            if (!locationRequested)
            {
                results.Children.Add(new TextBlock { Text = "为保护隐私，位置信息默认隐藏，仅在你主动请求后读取。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption });
                var button = new Button { Content = "显示位置信息", HorizontalAlignment = HorizontalAlignment.Left, Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"] };
                Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, "显示位置信息");
                button.Click += async (_, _) => { locationRequested = true; locationError = null; button.IsEnabled = false; await LoadAsync(true); };
                results.Children.Add(button);
            }
            else if (!string.IsNullOrEmpty(locationError))
                results.Children.Add(new TextBlock { Text = locationError, FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["FwDangerTextStyle"] });
            else if (info.Location is { } location && !string.IsNullOrEmpty(location))
                results.Children.Add(new TextBlock { Text = location, FontSize = 13, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true });
            else
                results.Children.Add(new TextBlock { Text = "该文件未提供可识别的位置信息。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption });
            if (!string.IsNullOrEmpty(info.TechnicalError))
                results.Children.Add(new TextBlock { Text = "部分技术信息无法读取：" + info.TechnicalError, FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption, Margin = new Thickness(0, 8, 0, 0) });
            results.Visibility = Visibility.Visible;
        }
        async Task LoadAsync(bool includeLocation)
        {
            var current = ++requestId;
            if (!includeLocation) { loading.Visibility = Visibility.Visible; results.Visibility = Visibility.Collapsed; }
            error.IsOpen = false;
            try
            {
                var info = await service.GetMediaInfoAsync(source, includeLocation, _lifetime.Token);
                if (closed || current != requestId || !Current()) return;
                lastInfo = info; if (includeLocation) locationError = null;
                RenderInfo(info);
            }
            catch (FilewayException ex)
            {
                if (closed || current != requestId || !Current()) return;
                if (includeLocation) { locationError = ex.Error.Message; if (lastInfo is { } previous) RenderInfo(previous); }
                else { error.Message = ex.Error.Message; error.IsOpen = true; }
            }
            finally
            {
                if (!closed && current == requestId) loading.Visibility = Visibility.Collapsed;
            }
        }
        dialog.Opened += (_, _) => _ = LoadAsync(false);
        dialog.Closed += (_, _) => closed = true;
        _mediaInfoDialog = dialog; _mediaInfoCurrentSource = Current;
        try { await ShowBrowserDialogAsync(dialog); }
        finally { closed = true; if (ReferenceEquals(_mediaInfoDialog, dialog)) { _mediaInfoDialog = null; _mediaInfoCurrentSource = null; } }
    }
}