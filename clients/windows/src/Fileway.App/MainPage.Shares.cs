using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;

namespace Fileway.App;

public sealed partial class MainPage
{
    private ContentDialog? _shareDialog;
    private Func<bool>? _shareCurrentSource;

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
        _shareDialog?.Hide(); _shareDialog = null;
    }

    private static void CopyShareText(string text)
    {
        var package = new DataPackage(); package.SetText(text);
        Clipboard.SetContent(package);
    }

    private static string ShareExpiryText(ShareLink link) => link.ExpiresAt is { } expiry
        ? "到期 " + expiry.ToLocalTime().ToString("yyyy/M/d HH:mm", System.Globalization.CultureInfo.CurrentCulture)
        : "永久有效";

    private static readonly ShareExpiryUnit[] ShareUnits = [ShareExpiryUnit.Days, ShareExpiryUnit.Hours, ShareExpiryUnit.Minutes, ShareExpiryUnit.Seconds];

    private void RenderShareLinks(StackPanel host, TextBlock empty, IReadOnlyList<ShareLink> links, Func<Task> refresh, Func<bool> current)
    {
        host.Children.Clear();
        empty.Visibility = links.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        foreach (var link in links) host.Children.Add(BuildShareRow(link, refresh, current));
    }

    private Grid BuildShareRow(ShareLink link, Func<Task> refresh, Func<bool> current)
    {
        var resources = Application.Current.Resources;
        var iconStyle = (Style)resources["FwIconButtonStyle"];
        var row = new Grid { ColumnSpacing = 8, Padding = new Thickness(0, 6, 0, 6) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var info = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        info.Children.Add(new TextBlock { Text = link.DisplayPath, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis, IsTextSelectionEnabled = true });
        var meta = new TextBlock { Text = ShareExpiryText(link) + (link.HasPassword ? " · 有密码" : ""), FontSize = 12, Style = (Style)resources["FwCaptionTextStyle"] };
        info.Children.Add(meta); row.Children.Add(info);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(actions, 1);
        var copy = new Button { Content = new Controls.LucideIcon { IconName = "link", Width = 18, Height = 18 }, Style = iconStyle, Width = 36, Height = 36, Padding = new Thickness(0) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(copy, "复制分享链接"); ToolTipService.SetToolTip(copy, "复制分享链接");
        copy.Click += (_, _) => { CopyShareText(link.ShareUrl); SetNotice("已复制分享链接", "链接已复制到剪贴板。", InfoBarSeverity.Success); };
        actions.Children.Add(copy);
        var download = new Button { Content = new Controls.LucideIcon { IconName = "download-simple", Width = 18, Height = 18 }, Style = iconStyle, Width = 36, Height = 36, Padding = new Thickness(0), IsEnabled = link.DirectDownloadUrl is not null };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(download, "复制下载链接");
        ToolTipService.SetToolTip(download, link.HasPassword ? "有密码的分享不支持直接下载链接" : "复制下载链接");
        download.Click += (_, _) => { if (link.DirectDownloadUrl is { } url) { CopyShareText(url); SetNotice("已复制下载链接", "链接已复制到剪贴板。", InfoBarSeverity.Success); } };
        actions.Children.Add(download);
        var delete = new Button { Content = new Controls.LucideIcon { IconName = "trash", Width = 18, Height = 18 }, Style = iconStyle, Width = 36, Height = 36, Padding = new Thickness(0) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(delete, "删除分享"); ToolTipService.SetToolTip(delete, "删除分享，链接立即失效");
        delete.Click += async (_, _) =>
        {
            if (!current() || _files is not IShareService service) return;
            delete.IsEnabled = false;
            try
            {
                var result = await service.DeleteAsync(link.Account, link.Hash, _lifetime.Token);
                if (!current()) return;
                if (result.Outcome == OperationOutcome.Confirmed) { SetNotice("分享已删除", "该链接已失效。", InfoBarSeverity.Success); await refresh(); }
                else SetNotice("删除结果尚未确认", result.Error?.Message ?? "请刷新分享列表核实，不要重复操作。", InfoBarSeverity.Warning);
            }
            catch (FilewayException error) { if (current()) SetNotice("分享删除失败", error.Error.Message, InfoBarSeverity.Error); }
            finally { if (current()) delete.IsEnabled = true; }
        };
        actions.Children.Add(delete); row.Children.Add(actions);
        return row;
    }

    private async void ShareResource_Click(object sender, RoutedEventArgs args)
    {
        if (!CanShareSelection || _browserDialog is not null || _shareDialog is not null
            || _files is not IShareService service || _session is not { } session || _directory is not { } directory) return;
        var source = SelectedRows()[0].File.Resource;
        var generation = _generation;
        bool Current() => !_disposed && SameBrowser(session, directory, generation)
            && _session?.Permissions.Share == true && _session?.Permissions.Download == true;
        var resources = Application.Current.Resources;
        var caption = (Style)resources["FwCaptionTextStyle"];
        var closed = false;
        var content = new StackPanel { Spacing = 12, MinWidth = 360, MaxWidth = 480 };
        content.Children.Add(new TextBlock { Text = source.DisplayName, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        content.Children.Add(new TextBlock { Text = source.DisplayPath, FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption });

        var permanent = new ToggleSwitch { Header = "永久有效", IsOn = false, OnContent = "永久", OffContent = "限期" };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(permanent, "永久有效");
        var expiry = new TextBox { Header = "期限", Text = "7", MaxLength = 6, Style = (Style)resources["FwTextBoxStyle"] };
        var unit = new Controls.ChoiceBox { Header = "单位", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var label in new[] { "天", "小时", "分钟", "秒" }) unit.Items.Add(label);
        unit.SelectedIndex = 0;
        var password = new PasswordBox { Header = "访问密码（可选）", PlaceholderText = "留空则任何人可访问", MaxLength = 256 };
        var form = new StackPanel { Spacing = 10 };
        form.Children.Add(permanent); form.Children.Add(expiry); form.Children.Add(unit); form.Children.Add(password);
        content.Children.Add(form);
        content.Children.Add(new TextBlock { Text = "分享会生成公开链接；持有链接者可访问该项目。设置密码后需凭密码访问。删除分享会使链接立即失效。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption });

        content.Children.Add(new TextBlock { Text = "此项目的分享", FontSize = 13, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        var linksHost = new StackPanel { Spacing = 2 };
        var empty = new TextBlock { Text = "尚无分享。创建后可复制链接。", FontSize = 12, Style = caption };
        content.Children.Add(linksHost); content.Children.Add(empty);
        var error = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Warning, Title = "分享操作未完成" };
        content.Children.Add(error);

        var dialog = new ContentDialog
        {
            Title = "分享链接", Content = new ScrollViewer { Content = content, MaxHeight = 460, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
            PrimaryButtonText = "创建分享", CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None,
        };
        void SyncForm()
        {
            expiry.IsEnabled = unit.IsEnabled = !permanent.IsOn;
            bool valid = permanent.IsOn || (int.TryParse(expiry.Text, out var count) && count > 0);
            dialog.IsPrimaryButtonEnabled = valid && !closed;
        }
        async Task RefreshLinks()
        {
            if (closed || !Current()) return;
            try
            {
                var links = await service.GetForResourceAsync(source, _lifetime.Token);
                if (!closed && Current()) RenderShareLinks(linksHost, empty, links, RefreshLinks, Current);
            }
            catch (FilewayException ex) { if (!closed && Current()) { error.Message = ex.Error.Message; error.IsOpen = true; } }
        }
        permanent.Toggled += (_, _) => SyncForm();
        expiry.TextChanged += (_, _) => SyncForm();
        dialog.Opened += (_, _) => { SyncForm(); _ = RefreshLinks(); };
        dialog.Closed += (_, _) => closed = true;
        dialog.PrimaryButtonClick += async (_, click) =>
        {
            click.Cancel = true;
            if (closed || !Current()) return;
            int? count = null;
            if (!permanent.IsOn)
            {
                if (!int.TryParse(expiry.Text, out var parsed) || parsed <= 0) { error.Message = "期限必须为正整数。"; error.IsOpen = true; return; }
                count = parsed;
            }
            var request = new ShareCreationRequest(count, ShareUnits[Math.Max(0, unit.SelectedIndex)], password.Password.Length == 0 ? null : password.Password);
            dialog.IsPrimaryButtonEnabled = false;
            try
            {
                var result = await service.CreateAsync(source, request, _lifetime.Token);
                if (closed || !Current()) return;
                if (result.Outcome == OperationOutcome.Confirmed)
                {
                    error.IsOpen = false; password.Password = "";
                    SetNotice("分享已创建", "可在下方复制链接。", InfoBarSeverity.Success);
                    await RefreshLinks();
                }
                else { error.Message = result.Error?.Message ?? "分享创建结果尚未确认，请刷新核实。"; error.IsOpen = true; }
            }
            catch (FilewayException ex) { if (!closed && Current()) { error.Message = ex.Error.Message; error.IsOpen = true; } }
            finally { if (!closed && Current()) SyncForm(); }
        };
        _shareDialog = dialog; _shareCurrentSource = Current;
        try { await ShowBrowserDialogAsync(dialog); }
        finally { closed = true; if (ReferenceEquals(_shareDialog, dialog)) { _shareDialog = null; _shareCurrentSource = null; } }
    }

    private async void ShareManager_Click(object sender, RoutedEventArgs args)
    {
        if (_browserDialog is not null || _shareDialog is not null || _files is not IShareService service || _session is not { } session) return;
        if (!session.Permissions.Share || !session.Permissions.Download) { SetNotice("没有分享权限", "当前账户需要分享与下载权限。", InfoBarSeverity.Warning); return; }
        var account = session.Account; var sessionId = session.Id;
        bool Current() => !_disposed && _session?.Id == sessionId && _session?.Account == account;
        var resources = Application.Current.Resources;
        var caption = (Style)resources["FwCaptionTextStyle"];
        var closed = false;
        var content = new StackPanel { Spacing = 12, MinWidth = 420, MaxWidth = 560 };
        content.Children.Add(new TextBlock { Text = (session.Permissions.Admin ? "服务器上全部分享。" : "当前账户创建的分享。") + "删除会使链接立即失效。", FontSize = 12, TextWrapping = TextWrapping.Wrap, Style = caption });
        var linksHost = new StackPanel { Spacing = 2 };
        var empty = new TextBlock { Text = "尚无分享。", FontSize = 12, Style = caption };
        content.Children.Add(linksHost); content.Children.Add(empty);
        var error = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Warning, Title = "分享列表未能加载" };
        content.Children.Add(error);
        var dialog = new ContentDialog
        {
            Title = "分享管理", Content = new ScrollViewer { Content = content, MaxHeight = 480, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
            PrimaryButtonText = "刷新", CloseButtonText = "关闭", DefaultButton = ContentDialogButton.None,
        };
        async Task RefreshLinks()
        {
            if (closed || !Current()) return;
            try
            {
                var links = await service.ListAsync(account, _lifetime.Token);
                if (!closed && Current()) { error.IsOpen = false; RenderShareLinks(linksHost, empty, links, RefreshLinks, Current); }
            }
            catch (FilewayException ex) { if (!closed && Current()) { error.Message = ex.Error.Message; error.IsOpen = true; } }
        }
        dialog.Opened += (_, _) => _ = RefreshLinks();
        dialog.Closed += (_, _) => closed = true;
        dialog.PrimaryButtonClick += async (_, click) => { click.Cancel = true; await RefreshLinks(); };
        _shareDialog = dialog; _shareCurrentSource = Current;
        try { await ShowBrowserDialogAsync(dialog); }
        finally { closed = true; if (ReferenceEquals(_shareDialog, dialog)) { _shareDialog = null; _shareCurrentSource = null; } }
    }
}