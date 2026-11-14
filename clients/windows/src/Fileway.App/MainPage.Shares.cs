using Fileway.App.Controls;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;

namespace Fileway.App;

public sealed partial class MainPage
{
    private ContentDialog? _shareDialog;
    private Func<bool>? _shareCurrentSource;
    private enum ShareCreationState { Submitting, Uncertain }
    private readonly Dictionary<(AccountKey Account, string WirePath), ShareCreationState> _shareCreations = [];

    private bool CanShareSelection => BrowserReady && !LoadingRing.IsActive
        && _session?.Permissions.Share == true && _session?.Permissions.Download == true
        && _files is IShareService && SelectedRows() is { Length: 1 };

    private void UpdateShareContext()
    {
        if (_shareDialog is not null && (_disposed || _shareCurrentSource?.Invoke() != true)) RetireShareDialog();
    }

    private void RetireShareDialog()
    {
        _shareCurrentSource = null;
        _shareDialog?.Hide();
        _shareDialog = null;
    }

    private static void CopyShareText(string text, ShareDialogFeedback feedback, string confirmation)
    {
        try
        {
            var package = new DataPackage();
            package.SetText(text);
            if (!Clipboard.SetContentWithOptions(package,
                new ClipboardContentOptions { IsAllowedInHistory = false, IsRoamable = false }))
                throw new InvalidOperationException("clipboard_busy");
            feedback.Show(confirmation, InfoBarSeverity.Success);
        }
        catch (Exception)
        {
            feedback.Show("未能复制链接", InfoBarSeverity.Error, "请稍后重试。");
        }
    }

    private static string ShareExpiryText(ShareLink link) => link.ExpiresAt is { } expiry
        ? "到期 " + expiry.ToLocalTime().ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture)
        : "永久有效";

    private static string ShareSummary(ShareLink link) => ShareExpiryText(link) + (link.HasPassword ? " · 有密码" : "");

    private static readonly ShareExpiryUnit[] ShareUnits =
        [ShareExpiryUnit.Days, ShareExpiryUnit.Hours, ShareExpiryUnit.Minutes, ShareExpiryUnit.Seconds];

    private static Grid TwoColumns(double gap = 8)
    {
        var grid = new Grid { ColumnSpacing = gap };
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        return grid;
    }

    private static Button ShareAction(string text, string name, string? icon = null)
    {
        var button = new Button
        {
            Style = SurfaceGeometry.Style("FwSecondaryButtonStyle"),
            Height = 32, MinHeight = 32, Padding = new Thickness(10, 0, 10, 0),
            VerticalAlignment = VerticalAlignment.Center,
        };
        if (icon is null) button.Content = text;
        else
        {
            var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            content.Children.Add(new LucideIcon { IconName = icon, Width = 16, Height = 16 });
            content.Children.Add(new TextBlock { Text = text });
            button.Content = content;
        }
        AutomationProperties.SetName(button, name);
        return button;
    }

    private static Button ShareIconAction(string icon, string name)
    {
        var button = new Button
        {
            Style = SurfaceGeometry.Style("FwIconButtonStyle"),
            Content = new LucideIcon { IconName = icon, Width = 16, Height = 16 },
            Width = 32, Height = 32, MinHeight = 32, Padding = new Thickness(0),
        };
        AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        return button;
    }

    private void RenderShareLinks(StackPanel host, TextBlock empty, IReadOnlyList<ShareLink> links,
        IShareService service, Func<Task> refresh, Func<bool> current,
        ShareDialogFeedback feedback, Action<ShareLink>? retireResult = null)
    {
        host.Children.Clear();
        empty.Visibility = links.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        foreach (var link in links)
            host.Children.Add(BuildShareRow(link, service, refresh, current, feedback, retireResult));
    }

    private Grid BuildShareRow(ShareLink link, IShareService service,
        Func<Task> refresh, Func<bool> current, ShareDialogFeedback feedback, Action<ShareLink>? retireResult)
    {
        var row = TwoColumns();
        row.Padding = new Thickness(0, 6, 0, 6);
        var info = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        var path = new TextBlock
        {
            Text = link.DisplayPath, FontSize = 13, TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis, IsTextSelectionEnabled = true,
        };
        ToolTipService.SetToolTip(path, link.DisplayPath);
        info.Children.Add(path);
        info.Children.Add(new TextBlock { Text = ShareSummary(link), Style = SurfaceGeometry.Style("FwCaptionTextStyle") });
        row.Children.Add(info);

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(actions, 1);
        var copy = ShareAction("复制链接", "复制分享链接", "copy");
        copy.Click += (_, _) => { if (current()) CopyShareText(link.ShareUrl, feedback, "已复制分享链接"); };
        actions.Children.Add(copy);
        if (link.DirectDownloadUrl is { } downloadUrl)
        {
            var download = ShareIconAction("download-simple", "复制下载链接");
            download.Click += (_, _) => { if (current()) CopyShareText(downloadUrl, feedback, "已复制下载链接"); };
            actions.Children.Add(download);
        }

        var delete = ShareIconAction("trash", "删除分享");
        delete.IsEnabled = !feedback.PendingDeletes.Contains(link.Hash);
        ToolTipService.SetToolTip(delete, "删除分享后链接立即失效");
        delete.Click += async (_, _) =>
        {
            if (!current() || !delete.IsEnabled || !feedback.PendingDeletes.Add(link.Hash)) return;
            var canRetry = true;
            delete.IsEnabled = false;
            try
            {
                var result = await service.DeleteAsync(link.Account, link.Hash, _lifetime.Token);
                if (!current()) return;
                if (result.Outcome == OperationOutcome.Confirmed)
                {
                    canRetry = false;
                    retireResult?.Invoke(link);
                    feedback.ClearDetail();
                    feedback.Show("分享已删除", InfoBarSeverity.Success);
                    await refresh();
                }
                else
                {
                    canRetry = result.Outcome != OperationOutcome.OutcomeUnknown;
                    if (!canRetry) retireResult?.Invoke(link);
                    feedback.Show(canRetry ? "分享删除失败" : "删除结果尚未确认", InfoBarSeverity.Warning,
                        result.Error?.Message ?? "请刷新列表并核对这条分享。");
                }
            }
            catch (FilewayException error)
            {
                if (!current()) return;
                canRetry = error.Error.Code != ErrorCode.OutcomeUnknown;
                if (!canRetry) retireResult?.Invoke(link);
                feedback.Show(canRetry ? "分享删除失败" : "删除结果尚未确认", InfoBarSeverity.Error, error.Error.Message);
            }
            finally
            {
                feedback.PendingDeletes.Remove(link.Hash);
                if (current()) delete.IsEnabled = canRetry;
            }
        };
        actions.Children.Add(delete);
        row.Children.Add(actions);
        return row;
    }

    private static Grid ShareDialogLayout(UIElement pinned, UIElement scrollContent, ShareDialogFeedback feedback,
        double minWidth, double maxWidth, double maxHeight)
    {
        var layout = new Grid { MinWidth = minWidth, MaxWidth = maxWidth, MaxHeight = maxHeight, RowSpacing = 8 };
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.RowDefinitions.Add(new RowDefinition());
        var heading = new StackPanel { Spacing = 8 };
        heading.Children.Add(pinned);
        heading.Children.Add(feedback.Detail);
        layout.Children.Add(heading);
        var scroll = new ScrollViewer
        {
            Content = scrollContent, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };
        Grid.SetRow(scroll, 1);
        layout.Children.Add(scroll);
        Grid.SetRow(feedback.Toast, 1);
        Canvas.SetZIndex(feedback.Toast, 5);
        layout.Children.Add(feedback.Toast);
        return layout;
    }

    private async void ShareResource_Click(object sender, RoutedEventArgs args)
    {
        if (!CanShareSelection || _browserDialog is not null || _shareDialog is not null
            || _files is not IShareService service || _session is not { } session || _directory is not { } directory) return;
        var source = SelectedRows()[0].File.Resource;
        var sourceIdentity = (source.Account, source.WirePath);
        var generation = _generation;
        bool Current() => !_disposed && SameBrowser(session, directory, generation)
            && _session?.Permissions.Share == true && _session?.Permissions.Download == true;
        var closed = false;
        bool DialogCurrent() => !closed && Current();
        using var feedback = new ShareDialogFeedback();

        var sourceTitle = new TextBlock
        {
            Text = source.DisplayName, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.CharacterEllipsis, MaxLines = 2,
        };
        ToolTipService.SetToolTip(sourceTitle, source.DisplayPath);
        AutomationProperties.SetHelpText(sourceTitle, source.DisplayPath);
        var pinned = new StackPanel { Spacing = 8 };
        pinned.Children.Add(sourceTitle);
        var createdTitle = new TextBlock
        {
            Text = "分享已创建", FontSize = 14, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
        };
        AutomationProperties.SetAutomationId(createdTitle, "ShareCreatedTitle");
        var createdMeta = new TextBlock { Style = SurfaceGeometry.Style("FwCaptionTextStyle") };
        var createdText = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        createdText.Children.Add(createdTitle);
        createdText.Children.Add(createdMeta);
        var copyCreated = ShareAction("复制链接", "复制分享链接", "copy");
        AutomationProperties.SetAutomationId(copyCreated, "ShareCopyCreatedLink");
        var resultRow = TwoColumns(12);
        resultRow.Children.Add(createdText);
        Grid.SetColumn(copyCreated, 1);
        resultRow.Children.Add(copyCreated);
        var resultPanel = new Border
        {
            Style = SurfaceGeometry.Style("FwFormCardStyle"), Child = resultRow,
            Padding = new Thickness(12, 8, 12, 8), Visibility = Visibility.Collapsed,
        };
        pinned.Children.Add(resultPanel);
        ShareLink? created = null;
        copyCreated.Click += (_, _) =>
        {
            if (DialogCurrent() && created is { } link) CopyShareText(link.ShareUrl, feedback, "已复制分享链接");
        };
        void RetireResult(ShareLink link)
        {
            if (created?.Hash != link.Hash) return;
            created = null;
            resultPanel.Visibility = Visibility.Collapsed;
        }

        var permanent = new ToggleSwitch { IsOn = false, OnContent = "永久", OffContent = "限期" };
        AutomationProperties.SetName(permanent, "永久有效");
        AutomationProperties.SetAutomationId(permanent, "SharePermanentSwitch");
        var expiry = new TextBox { Text = "7", Width = 96, MaxLength = 6, Style = SurfaceGeometry.Style("FwTextBoxStyle") };
        AutomationProperties.SetName(expiry, "期限");
        AutomationProperties.SetAutomationId(expiry, "ShareExpiryAmount");
        var unit = new ChoiceBox { Width = 96 };
        AutomationProperties.SetName(unit, "单位");
        AutomationProperties.SetAutomationId(unit, "ShareExpiryUnit");
        foreach (var label in new[] { "天", "小时", "分钟", "秒" }) unit.Items.Add(label);
        unit.SelectedIndex = 0;
        var duration = TwoColumns();
        duration.HorizontalAlignment = HorizontalAlignment.Left;
        duration.Children.Add(expiry);
        Grid.SetColumn(unit, 1);
        duration.Children.Add(unit);
        var password = new PasswordBox { PlaceholderText = "选填", MaxLength = 256 };
        AutomationProperties.SetName(password, "访问密码（可选）");
        AutomationProperties.SetAutomationId(password, "SharePasswordInput");

        var content = new StackPanel { Spacing = 8 };
        content.Children.Add(DialogOrdering.FieldRow("有效期", permanent, 88));
        content.Children.Add(DialogOrdering.FieldRow("期限", new ContentControl
        {
            Content = duration, HorizontalContentAlignment = HorizontalAlignment.Stretch, IsTabStop = false,
        }, 88));
        content.Children.Add(DialogOrdering.FieldRow("访问密码", password, 88));
        content.Children.Add(new TextBlock
        {
            Text = "持有链接者可访问。设置密码后，访问者需输入密码。",
            TextWrapping = TextWrapping.Wrap, Style = SurfaceGeometry.Style("FwCaptionTextStyle"),
        });
        var historyHeading = TwoColumns();
        historyHeading.Children.Add(new TextBlock
        {
            Text = "此项目的分享", FontSize = 14, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
        });
        var refresh = ShareAction("刷新记录", "刷新分享记录");
        AutomationProperties.SetAutomationId(refresh, "ShareHistoryRefresh");
        var verifyButton = ShareAction("刷新记录", "刷新并核对分享记录");
        feedback.Detail.ActionButton = verifyButton;
        Grid.SetColumn(refresh, 1);
        historyHeading.Children.Add(refresh);
        content.Children.Add(historyHeading);
        var linksHost = new StackPanel { Spacing = 2 };
        var empty = new TextBlock { Text = "尚无分享。", Style = SurfaceGeometry.Style("FwCaptionTextStyle"), Visibility = Visibility.Collapsed };
        content.Children.Add(linksHost);
        content.Children.Add(empty);

        var dialog = new ContentDialog
        {
            Title = "分享链接", Content = ShareDialogLayout(pinned, content, feedback, 360, 480, DialogOrdering.ContentMaxHeight(this, 460)),
            PrimaryButtonText = "创建分享", CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None,
        };
        bool creating = false;
        long revision = 0;
        void SyncForm()
        {
            expiry.IsEnabled = unit.IsEnabled = !permanent.IsOn && !creating;
            permanent.IsEnabled = password.IsEnabled = !creating;
            var valid = permanent.IsOn || int.TryParse(expiry.Text, out var count) && count > 0;
            var pending = _shareCreations.ContainsKey(sourceIdentity);
            dialog.IsPrimaryButtonEnabled = valid && !creating && !pending && DialogCurrent();
            verifyButton.Visibility = pending && !creating ? Visibility.Visible : Visibility.Collapsed;
        }

        async Task RefreshLinks(bool verify = false)
        {
            if (!DialogCurrent()) return;
            var requestRevision = ++revision;
            refresh.IsEnabled = verifyButton.IsEnabled = false;
            try
            {
                var links = await service.GetForResourceAsync(source, _lifetime.Token);
                if (!DialogCurrent() || requestRevision != revision) return;
                RenderShareLinks(linksHost, empty, links, service, () => RefreshLinks(), DialogCurrent, feedback, RetireResult);
                if (verify)
                {
                    feedback.ClearDetail();
                    if (_shareCreations.TryGetValue(sourceIdentity, out var state))
                    {
                        if (state == ShareCreationState.Uncertain)
                        {
                            _shareCreations.Remove(sourceIdentity);
                            feedback.Show("记录已刷新，请核对现有链接", InfoBarSeverity.Success);
                        }
                        else feedback.Show("上一轮仍在提交", InfoBarSeverity.Informational, "请稍后刷新记录。");
                    }
                    SyncForm();
                }
            }
            catch (FilewayException ex)
            {
                if (DialogCurrent() && requestRevision == revision)
                    feedback.Show("分享记录未能加载", InfoBarSeverity.Warning, ex.Error.Message);
            }
            finally
            {
                if (DialogCurrent() && requestRevision == revision) refresh.IsEnabled = verifyButton.IsEnabled = true;
            }
        }

        refresh.Click += async (_, _) => await RefreshLinks(verify: true);
        verifyButton.Click += async (_, _) => await RefreshLinks(verify: true);
        permanent.Toggled += (_, _) => SyncForm();
        expiry.TextChanged += (_, _) => SyncForm();
        dialog.Opened += (_, _) =>
        {
            SyncForm();
            if (_shareCreations.TryGetValue(sourceIdentity, out var state))
                feedback.Show(state == ShareCreationState.Uncertain ? "创建结果尚未确认" : "上一轮仍在提交",
                    InfoBarSeverity.Warning, "请刷新记录并核对现有链接。");
            _ = RefreshLinks();
        };
        dialog.Closed += (_, _) => closed = true;
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true;
            if (!DialogCurrent() || creating || _shareCreations.ContainsKey(sourceIdentity)) return;
            int? count = null;
            if (!permanent.IsOn)
            {
                if (!int.TryParse(expiry.Text, out var parsed) || parsed <= 0) return;
                count = parsed;
            }
            creating = true;
            _shareCreations.Add(sourceIdentity, ShareCreationState.Submitting);
            created = null;
            resultPanel.Visibility = Visibility.Collapsed;
            feedback.ClearDetail();
            SyncForm();
            var request = new ShareCreationRequest(count, ShareUnits[Math.Max(0, unit.SelectedIndex)], password.Password.Length == 0 ? null : password.Password);
            try
            {
                var result = await service.CreateAsync(source, request, _lifetime.Token);
                var uncertain = result.Outcome == OperationOutcome.OutcomeUnknown;
                if (uncertain) _shareCreations[sourceIdentity] = ShareCreationState.Uncertain;
                if (!DialogCurrent()) return;
                if (result.Outcome == OperationOutcome.Confirmed)
                {
                    password.Password = "";
                    if (result.Link is { } link)
                    {
                        created = link;
                        createdMeta.Text = ShareSummary(link);
                        resultPanel.Visibility = Visibility.Visible;
                    }
                    else feedback.Show("分享已创建", InfoBarSeverity.Informational, "请在分享记录中查看链接。");
                    await RefreshLinks();
                }
                else
                {
                    feedback.Show(uncertain ? "创建结果尚未确认" : "分享未能创建", InfoBarSeverity.Warning,
                        result.Error?.Message ?? "请刷新记录并核对现有链接。");
                }
            }
            catch (FilewayException ex)
            {
                var uncertain = ex.Error.Code == ErrorCode.OutcomeUnknown;
                if (uncertain) _shareCreations[sourceIdentity] = ShareCreationState.Uncertain;
                if (!DialogCurrent()) return;
                feedback.Show(uncertain ? "创建结果尚未确认" : "分享未能创建", InfoBarSeverity.Error, ex.Error.Message);
            }
            finally
            {
                creating = false;
                if (_shareCreations.TryGetValue(sourceIdentity, out var state) && state == ShareCreationState.Submitting)
                    _shareCreations.Remove(sourceIdentity);
                if (DialogCurrent()) SyncForm();
            }
        };
        _shareDialog = dialog;
        _shareCurrentSource = Current;
        try { await ShowBrowserDialogAsync(dialog); }
        finally
        {
            closed = true;
            if (ReferenceEquals(_shareDialog, dialog)) { _shareDialog = null; _shareCurrentSource = null; }
        }
    }

    private async void ShareManager_Click(object sender, RoutedEventArgs args)
    {
        if (_browserDialog is not null || _shareDialog is not null || _files is not IShareService service || _session is not { } session) return;
        if (!session.Permissions.Share || !session.Permissions.Download)
        {
            SetNotice("没有分享权限", "当前账户需要分享与下载权限。", InfoBarSeverity.Warning);
            return;
        }
        var closed = false;
        bool Current() => !_disposed && _session is { } current && current.Id == session.Id &&
            current.Account == session.Account && current.Permissions.Share && current.Permissions.Download &&
            current.Permissions.Admin == session.Permissions.Admin;
        bool DialogCurrent() => !closed && Current();
        using var feedback = new ShareDialogFeedback();
        var summary = new TextBlock
        {
            Text = (session.Permissions.Admin ? "服务器上全部分享。" : "当前账户创建的分享。") + "删除会使链接立即失效。",
            TextWrapping = TextWrapping.Wrap, Style = SurfaceGeometry.Style("FwCaptionTextStyle"),
        };
        var content = new StackPanel { Spacing = 2 };
        var empty = new TextBlock
        {
            Text = "尚无分享。", Style = SurfaceGeometry.Style("FwCaptionTextStyle"), Visibility = Visibility.Collapsed,
        };
        var linksHost = new StackPanel { Spacing = 2 };
        content.Children.Add(linksHost);
        content.Children.Add(empty);
        var dialog = new ContentDialog
        {
            Title = "分享管理", Content = ShareDialogLayout(summary, content, feedback, 380, 560, DialogOrdering.ContentMaxHeight(this, 480)),
            PrimaryButtonText = "刷新", CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None,
        };
        long revision = 0;
        async Task RefreshLinks()
        {
            if (!DialogCurrent()) return;
            var requestRevision = ++revision;
            dialog.IsPrimaryButtonEnabled = false;
            try
            {
                var links = await service.ListAsync(session.Account, _lifetime.Token);
                if (!DialogCurrent() || requestRevision != revision) return;
                feedback.ClearDetail();
                RenderShareLinks(linksHost, empty, links, service, RefreshLinks, DialogCurrent, feedback);
            }
            catch (FilewayException ex)
            {
                if (DialogCurrent() && requestRevision == revision)
                    feedback.Show("分享列表未能加载", InfoBarSeverity.Warning, ex.Error.Message);
            }
            finally
            {
                if (DialogCurrent() && requestRevision == revision) dialog.IsPrimaryButtonEnabled = true;
            }
        }
        dialog.Opened += (_, _) => _ = RefreshLinks();
        dialog.Closed += (_, _) => closed = true;
        dialog.PrimaryButtonClick += async (_, click) => { click.Cancel = true; await RefreshLinks(); };
        _shareDialog = dialog;
        _shareCurrentSource = Current;
        try { await ShowBrowserDialogAsync(dialog); }
        finally
        {
            closed = true;
            if (ReferenceEquals(_shareDialog, dialog)) { _shareDialog = null; _shareCurrentSource = null; }
        }
    }

    private sealed class ShareDialogFeedback : IDisposable
    {
        internal HashSet<string> PendingDeletes { get; } = new(StringComparer.Ordinal);
        internal InfoBar Detail { get; } = new() { IsOpen = false, IsClosable = false, Visibility = Visibility.Collapsed };
        internal InfoBar Toast { get; } = new()
        {
            IsOpen = false, IsClosable = true, MaxWidth = 360,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(8, 8, 8, 0),
        };

        internal ShareDialogFeedback()
        {
            AutomationProperties.SetAutomationId(Detail, "ShareDialogStatus");
            FloatingNotice.Attach(Detail, automatic: false);
            FloatingNotice.Attach(Toast, automatic: false);
        }

        internal void Show(string title, InfoBarSeverity severity, string? message = null)
        {
            if (severity == InfoBarSeverity.Success && message is null)
            {
                Toast.Severity = severity;
                Toast.Title = title;
                FloatingNotice.Show(Toast, title, repeat: true, compact: true);
                return;
            }
            Toast.IsOpen = false;
            Detail.Severity = severity;
            Detail.Title = title;
            Detail.Message = message ?? "";
            Detail.Visibility = Visibility.Visible;
            Detail.IsOpen = true;
        }

        internal void ClearDetail()
        {
            Detail.IsOpen = false;
            Detail.Visibility = Visibility.Collapsed;
        }

        public void Dispose()
        {
            FloatingNotice.Detach(Detail);
            FloatingNotice.Detach(Toast);
        }
    }
}
