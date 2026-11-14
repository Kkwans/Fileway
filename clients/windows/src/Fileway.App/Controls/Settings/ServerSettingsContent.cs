using System.Globalization;
using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Settings;

internal sealed class ServerSettingsContent : UserControl, IDisposable
{
    private sealed record Field(ServerSettingField Key, string Label, bool Toggle = false, bool Lines = false);
    private sealed record Section(string Title, string Caption, Field[] Fields);
    private enum RequiredRead { None, Conflict, Unknown }

    private static readonly Section[] Sections =
    [
        new("安全与登录", "全局登录和账号创建策略。",
        [
            new(ServerSettingField.Signup, "开放注册", Toggle: true),
            new(ServerSettingField.HideLoginButton, "隐藏 Web 登录按钮", Toggle: true),
            new(ServerSettingField.CreateUserDir, "为新用户创建主目录", Toggle: true),
            new(ServerSettingField.UserHomeBasePath, "用户主目录基路径"),
            new(ServerSettingField.MinimumPasswordLength, "最短密码长度"),
            new(ServerSettingField.SessionMinutes, "会话期限（分钟）"),
        ]),
        new("用户默认值", "新用户的文件浏览初始偏好。",
        [
            new(ServerSettingField.DefaultScope, "默认目录范围"),
            new(ServerSettingField.DefaultLocale, "语言代码"),
            new(ServerSettingField.DefaultView, "浏览视图"),
            new(ServerSettingField.DefaultSingleClick, "单击打开", Toggle: true),
            new(ServerSettingField.DefaultRedirect, "移动或复制后跳转", Toggle: true),
            new(ServerSettingField.DefaultDateFormat, "使用完整日期", Toggle: true),
            new(ServerSettingField.DefaultHideDotfiles, "隐藏点文件", Toggle: true),
            new(ServerSettingField.DefaultEditorTheme, "Web 编辑器主题"),
            new(ServerSettingField.DefaultSortBy, "默认排序字段"),
            new(ServerSettingField.DefaultSortAscending, "升序排列", Toggle: true),
        ]),
        new("默认权限", "只影响新账户的默认授权。分享需要下载权限。",
        [
            new(ServerSettingField.DefaultAdmin, "管理员", Toggle: true),
            new(ServerSettingField.DefaultDownload, "下载", Toggle: true),
            new(ServerSettingField.DefaultShare, "分享", Toggle: true),
            new(ServerSettingField.DefaultCreate, "创建", Toggle: true),
            new(ServerSettingField.DefaultRename, "重命名", Toggle: true),
            new(ServerSettingField.DefaultModify, "修改", Toggle: true),
            new(ServerSettingField.DefaultDelete, "删除", Toggle: true),
            new(ServerSettingField.DefaultExecute, "执行命令", Toggle: true),
        ]),
        new("Web 外观", "调整 Web 页面的品牌和显示选项。",
        [
            new(ServerSettingField.BrandName, "站点名称"),
            new(ServerSettingField.BrandFiles, "品牌资源目录"),
            new(ServerSettingField.BrandTheme, "主题"),
            new(ServerSettingField.BrandColor, "主题颜色"),
            new(ServerSettingField.BrandDisableExternal, "隐藏外部链接", Toggle: true),
            new(ServerSettingField.BrandHideUsage, "隐藏磁盘使用比例", Toggle: true),
        ]),
        new("上传", "与服务端 TUS 上传一致。",
        [
            new(ServerSettingField.UploadChunk, "分块大小（字节）"),
            new(ServerSettingField.UploadRetries, "失败重试次数"),
        ]),
        new("允许命令", "每行一条命令或参数。保存这里的配置不会直接运行命令。",
        [
            new(ServerSettingField.DefaultCommands, "新用户命令白名单", Lines: true),
            new(ServerSettingField.Shell, "命令解释器及参数", Lines: true),
            new(ServerSettingField.BeforeCopy, "复制前的服务器钩子", Lines: true),
            new(ServerSettingField.AfterCopy, "复制后的服务器钩子", Lines: true),
            new(ServerSettingField.BeforeDelete, "删除前的服务器钩子", Lines: true),
            new(ServerSettingField.AfterDelete, "删除后的服务器钩子", Lines: true),
            new(ServerSettingField.BeforeRename, "重命名前的服务器钩子", Lines: true),
            new(ServerSettingField.AfterRename, "重命名后的服务器钩子", Lines: true),
            new(ServerSettingField.BeforeSave, "保存前的服务器钩子", Lines: true),
            new(ServerSettingField.AfterSave, "保存后的服务器钩子", Lines: true),
            new(ServerSettingField.BeforeUpload, "上传前的服务器钩子", Lines: true),
            new(ServerSettingField.AfterUpload, "上传后的服务器钩子", Lines: true),
        ]),
        new("路径规则", "全局规则先于账户规则应用。", []),
    ];

    private readonly IServerSettingsService _service;
    private readonly AccountKey _account;
    private readonly Func<bool> _isCurrent;
    private readonly CancellationTokenSource _lifetime;
    private readonly CancellationToken _token;
    private readonly TextBlock _capabilities = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly StackPanel _header = new() { Spacing = 8 };
    private readonly StackPanel _footer = new() { Spacing = 8 };
    private readonly ChoiceBox _section = new() { HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly ContentControl _body = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly Button _reload;
    private readonly Button _review;
    private readonly Button _save;
    private readonly Button _edit;
    private readonly Button _close;
    private readonly ScrollViewer _scroll;
    private readonly List<FrameworkElement> _sectionContents = [];
    private readonly Dictionary<ServerSettingField, Func<string>> _fields = [];
    private XamlRoot? _hostRoot;
    private ServerSettingsSnapshot? _snapshot;
    private ServerSettingsSnapshot? _comparisonSnapshot;
    private ServerRulesEditor? _rules;
    private ServerSettingsEdit? _pending;
    private double _editorOffset;
    private double _contentHeightLimit;
    private bool _busy;
    private RequiredRead _requiredRead;

    internal bool IsSaving { get; private set; }
    internal event EventHandler? CloseRequested;
    private bool Current => !_token.IsCancellationRequested && _isCurrent();

    internal ServerSettingsContent(IServerSettingsService service, AccountKey account, Func<bool> isCurrent, double height, CancellationToken lifetime)
    {
        _service = service;
        _account = account;
        _isCurrent = isCurrent;
        _lifetime = CancellationTokenSource.CreateLinkedTokenSource(lifetime);
        _token = _lifetime.Token;
        _contentHeightLimit = height;
        var resources = Application.Current.Resources;
        _capabilities.Style = (Style)resources["FwCaptionTextStyle"];
        _status.Style = (Style)resources["FwCaptionTextStyle"];
        AutomationProperties.SetAutomationId(_status, "ServerSettingsStatus");
        AutomationProperties.SetLiveSetting(_status, AutomationLiveSetting.Polite);
        _status.Visibility = Visibility.Collapsed;
        AutomationProperties.SetAutomationId(_section, "ServerSettingsSection");
        AutomationProperties.SetName(_section, "服务器设置分组");
        foreach (var item in Sections) _section.Items.Add(new ComboBoxItem { Content = item.Title });
        _section.SelectionChanged += (_, _) => RenderSection();
        _reload = CreateButton("重新读取", "ReloadServerSettings");
        _review = CreateButton("检查更改…", "ReviewServerSettings", primary: true);
        _save = CreateButton("确认保存", "SaveServerSettings", primary: true);
        _edit = CreateButton("继续编辑", "EditServerSettings");
        _close = CreateButton("关闭", "CloseServerSettings");
        _reload.Click += async (_, _) => await ReloadAsync();
        _review.Click += (_, _) => Review();
        _edit.Click += (_, _) => ContinueEditing();
        _save.Click += async (_, _) => await SaveAsync();
        _close.Click += (_, _) => CloseRequested?.Invoke(this, EventArgs.Empty);
        _header.Children.Add(_capabilities);
        _header.Children.Add(_section);
        var actions = new Grid { ColumnSpacing = 8 };
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        actions.Children.Add(_close);
        actions.Children.Add(_reload);
        actions.Children.Add(_edit);
        Grid.SetColumn(_review, 3);
        actions.Children.Add(_review);
        Grid.SetColumn(_save, 3);
        actions.Children.Add(_save);
        _footer.Children.Add(new Border { Style = (Style)resources["FwDividerStyle"], Height = 1 });
        _footer.Children.Add(_status);
        _footer.Children.Add(actions);
        _scroll = new ScrollViewer
        {
            Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Visible,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };
        AutomationProperties.SetAutomationId(_scroll, "ServerSettingsScroll");
        var layout = new Grid { MinWidth = 320, MaxWidth = 480 };
        for (int row = 0; row < 3; row++) layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _header.Margin = new Thickness(0, 0, 0, 12);
        layout.Children.Add(_header);
        Grid.SetRow(_scroll, 1);
        layout.Children.Add(_scroll);
        _footer.Margin = new Thickness(0, 8, 0, 0);
        Grid.SetRow(_footer, 2);
        layout.Children.Add(_footer);
        _header.SizeChanged += (_, _) => UpdateScrollHeight();
        _footer.SizeChanged += (_, _) => UpdateScrollHeight();
        Content = layout;
        UpdateActions();
        Loaded += OnLoaded;
    }

    private static Button CreateButton(string label, string id, bool primary = false)
    {
        var button = new Button
        {
            Content = label,
            Style = ActionStyle(primary),
        };
        AutomationProperties.SetAutomationId(button, id);
        return button;
    }

    private static Style ActionStyle(bool primary) =>
        (Style)Application.Current.Resources[primary ? "FwPrimaryButtonStyle" : "FwSecondaryButtonStyle"];

    private void SetStatus(string message, bool attention = false)
    {
        _status.Style = (Style)Application.Current.Resources[attention ? "FwDangerTextStyle" : "FwCaptionTextStyle"];
        _status.Text = message;
        _status.Visibility = message.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        if (message.Length > 0)
            FrameworkElementAutomationPeer.FromElement(_status)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        _hostRoot = XamlRoot;
        if (_hostRoot is not null)
        {
            _hostRoot.Changed += OnHostSizeChanged;
            UpdateContentHeightLimit(_hostRoot.Size.Height);
        }
        await ReloadAsync();
    }

    private void OnHostSizeChanged(XamlRoot sender, XamlRootChangedEventArgs args) =>
        UpdateContentHeightLimit(sender.Size.Height);

    private void UpdateContentHeightLimit(double hostHeight)
    {
        if (hostHeight > 0) _contentHeightLimit = Math.Clamp(hostHeight - 180, 220, 472);
        UpdateScrollHeight();
    }

    private void UpdateScrollHeight()
    {
        static double OuterHeight(FrameworkElement element) => element.Visibility == Visibility.Collapsed
            ? 0 : element.ActualHeight + element.Margin.Top + element.Margin.Bottom;

        _scroll.MaxHeight = Math.Max(64, _contentHeightLimit - OuterHeight(_header) - OuterHeight(_footer));
    }

    private async Task ReloadAsync()
    {
        if (_busy || !Current) return;
        var previous = _snapshot;
        var draft = _pending ?? ReadDraft();
        var recovery = _requiredRead;
        if (_pending is null) _editorOffset = _scroll.VerticalOffset;
        _busy = true;
        SetStatus("正在读取服务器设置…");
        UpdateActions();
        try
        {
            var snapshot = await _service.ReadServerSettingsAsync(_account, _token);
            if (!Current) return;
            var remaining = draft is null ? null : UnmatchedDraft(snapshot, draft);
            if (draft is not null && recovery == RequiredRead.None && previous?.Revision == snapshot.Revision)
            {
                SetStatus("服务器设置未变更，草稿已保留。");
            }
            else if (remaining is null)
            {
                Apply(snapshot, offset: _editorOffset);
                SetStatus(draft is not null ? "已核对：当前服务器设置与草稿一致。"
                    : previous is not null ? "已重新读取服务器设置。" : "");
            }
            else
            {
                _pending = remaining;
                _comparisonSnapshot = snapshot;
                _requiredRead = RequiredRead.None;
                ShowReview(snapshot, remaining, comparison: true);
                SetStatus("已读取当前设置。请核对差异后继续编辑。");
            }
        }
        catch (Exception error)
        {
            if (Current)
                SetStatus(recovery != RequiredRead.None
                    ? "暂时无法核对服务器设置。草稿已保留，请重新读取。"
                    : error is FilewayException known ? known.Error.Message : "服务器设置未能读取，请重试。",
                    attention: true);
        }
        finally
        {
            _busy = false;
            if (Current)
            {
                UpdateActions();
                if (_comparisonSnapshot is not null) _edit.Focus(FocusState.Programmatic);
                else if (_requiredRead != RequiredRead.None || _snapshot is null) _reload.Focus(FocusState.Programmatic);
                else if (previous is null) _section.Focus(FocusState.Programmatic);
                else if (recovery != RequiredRead.None) _review.Focus(FocusState.Programmatic);
            }
        }
    }

    private void Apply(ServerSettingsSnapshot snapshot, ServerSettingsEdit? draft = null, double offset = 0)
    {
        _snapshot = snapshot;
        _requiredRead = RequiredRead.None;
        _pending = null;
        _comparisonSnapshot = null;
        _body.Content = null;
        _fields.Clear();
        _sectionContents.Clear();
        _rules = new ServerRulesEditor(draft?.Rules ?? snapshot.Rules, "GlobalRules");
        string authentication = snapshot.AuthMethod switch { "json" => "账号密码", "noauth" => "无身份验证", "" => "未知", _ => snapshot.AuthMethod };
        _capabilities.Text = $"身份验证：{authentication} · 命令执行：" + (snapshot.CommandExecutionEnabled switch
        {
            true => "已启用", false => "服务器未启用", null => "未知",
        });
        foreach (var section in Sections)
        {
            var content = new StackPanel { Spacing = 8, Padding = new Thickness(0, 0, 10, 12) };
            content.Children.Add(new TextBlock { Text = section.Caption, TextWrapping = TextWrapping.Wrap,
                Style = (Style)Application.Current.Resources["FwCaptionTextStyle"] });
            if (section.Fields.Length == 0) content.Children.Add(_rules);
            foreach (var field in section.Fields)
            {
                var value = draft?.Values.GetValueOrDefault(field.Key) ?? snapshot.Values[field.Key];
                if (field.Toggle)
                {
                    var toggle = new CheckBox { Content = field.Label, IsChecked = bool.TryParse(value, out var on) && on };
                    AutomationProperties.SetAutomationId(toggle, "ServerSetting" + field.Key);
                    _fields.Add(field.Key, () => (toggle.IsChecked == true).ToString());
                    content.Children.Add(toggle);
                    continue;
                }
                var box = new TextBox
                {
                    Text = value, HorizontalAlignment = HorizontalAlignment.Stretch, AcceptsReturn = field.Lines,
                    TextWrapping = field.Lines ? TextWrapping.Wrap : TextWrapping.NoWrap,
                    MinHeight = field.Lines ? 68 : 36, MaxHeight = field.Lines ? 160 : double.PositiveInfinity,
                    MaxLength = field.Lines ? 32768 : 4096, Style = (Style)Application.Current.Resources["FwTextBoxStyle"],
                };
                AutomationProperties.SetAutomationId(box, "ServerSetting" + field.Key);
                AutomationProperties.SetName(box, field.Label);
                _fields.Add(field.Key, () => field.Lines ? box.Text.ReplaceLineEndings("\n") : box.Text);
                var labeled = new StackPanel { Spacing = 4 };
                labeled.Children.Add(Caption(field.Label));
                labeled.Children.Add(box);
                content.Children.Add(labeled);
            }
            _sectionContents.Add(content);
        }
        if (_section.SelectedIndex < 0) _section.SelectedIndex = 0;
        RenderSection(offset);
    }

    private void RenderSection(double offset = 0)
    {
        if (_pending is not null || _section.SelectedIndex < 0 || _section.SelectedIndex >= _sectionContents.Count) return;
        PresentBody(_sectionContents[_section.SelectedIndex], offset);
    }

    private void PresentBody(FrameworkElement content, double offset = 0)
    {
        _body.Content = content;
        _scroll.ChangeView(null, offset, null, disableAnimation: true);
        DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () =>
        {
            if (Current && ReferenceEquals(_body.Content, content))
                _scroll.ChangeView(null, offset, null, disableAnimation: true);
        });
    }

    private ServerSettingsEdit? ReadDraft()
    {
        if (_snapshot is not { } snapshot || _rules is null) return null;
        var values = new Dictionary<ServerSettingField, string>();
        foreach (var field in Sections.SelectMany(section => section.Fields))
        {
            string value = _fields[field.Key]();
            string previous = snapshot.Values[field.Key];
            if (field.Lines) previous = previous.ReplaceLineEndings("\n");
            if (value != previous) values.Add(field.Key, value);
        }
        var rules = _rules.ReadDraft();
        var changedRules = !rules.SequenceEqual(snapshot.Rules);
        return values.Count == 0 && !changedRules ? null : new(values, changedRules ? rules : null);
    }

    private static ServerSettingsEdit? UnmatchedDraft(ServerSettingsSnapshot snapshot, ServerSettingsEdit draft)
    {
        var values = new Dictionary<ServerSettingField, string>();
        foreach (var field in Sections.SelectMany(section => section.Fields))
        {
            if (!draft.Values.TryGetValue(field.Key, out string? value)) continue;
            string actual = snapshot.Values[field.Key];
            if (field.Lines) actual = actual.ReplaceLineEndings("\n");
            if (actual != AppliedValue(field, value)) values.Add(field.Key, value);
        }
        var rules = draft.Rules is { } proposed && !proposed.SequenceEqual(snapshot.Rules) ? proposed : null;
        return values.Count == 0 && rules is null ? null : new(values, rules);
    }

    // Mirror the existing server-settings conversion for display and readback only.
    // The service still owns validation and serialization; retain the text the user entered.
    private static string AppliedValue(Field field, string value)
    {
        if (field.Lines)
            return string.Join('\n', value.ReplaceLineEndings("\n").Split('\n',
                StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries));
        if (field.Key == ServerSettingField.SessionMinutes &&
            decimal.TryParse(value, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var minutes) &&
            minutes is >= 10 and <= 43200)
            return minutes.ToString("0.########", CultureInfo.InvariantCulture);
        if ((field.Key is ServerSettingField.MinimumPasswordLength or ServerSettingField.UploadChunk or ServerSettingField.UploadRetries) &&
            ulong.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out var number))
            return number.ToString(CultureInfo.InvariantCulture);
        return value;
    }

    private void Review()
    {
        if (_snapshot is not { } snapshot || _pending is not null || _busy || _requiredRead != RequiredRead.None || !Current) return;
        var draft = ReadDraft() is { } changes ? UnmatchedDraft(snapshot, changes) : null;
        if (draft is null) { SetStatus("没有需要保存的更改。"); return; }
        if (draft.Rules?.Any(rule => rule.Match.Length == 0) == true)
        {
            SetStatus("请填写所有路径规则，或移除空白规则。", attention: true);
            _section.SelectedIndex = Sections.Length - 1;
            return;
        }
        _editorOffset = _scroll.VerticalOffset;
        _pending = draft;
        SetStatus("");
        ShowReview(snapshot, draft, comparison: false);
        UpdateActions();
        _edit.Focus(FocusState.Programmatic);
    }

    private static TextBlock Caption(string text) => new()
    {
        Text = text, Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
    };

    private static TextBlock Heading(string text, string style = "FwBodyTextStyle") => new()
    {
        Text = text, Style = (Style)Application.Current.Resources[style],
        FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap,
    };

    private static string DisplayValue(Field field, string value) =>
        field.Toggle && bool.TryParse(value, out bool enabled) ? enabled ? "启用" : "关闭" :
        value.Length == 0 ? "（空）" : value;

    private static void AddValue(StackPanel panel, string label, string text, string id)
    {
        var value = new TextBlock
        {
            Text = text, TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["FwBodyTextStyle"],
        };
        AutomationProperties.SetAutomationId(value, id);
        AutomationProperties.SetName(value, label + "：" + text);
        var block = new StackPanel { Spacing = 2 };
        block.Children.Add(Caption(label));
        block.Children.Add(value);
        panel.Children.Add(block);
    }

    private void ShowReview(ServerSettingsSnapshot snapshot, ServerSettingsEdit draft, bool comparison)
    {
        string id = comparison ? "ServerSettingsComparison" : "ServerSettingsReview";
        var review = new StackPanel { Spacing = 16, Padding = new Thickness(0, 0, 10, 12) };
        var introduction = new StackPanel { Spacing = 4 };
        var title = Heading(comparison ? "核对服务器与草稿" : "复核全局更改", "FwSubtitleTextStyle");
        AutomationProperties.SetAutomationId(title, id);
        introduction.Children.Add(title);
        introduction.Children.Add(Caption(comparison
            ? "以下草稿与服务器当前值不同。"
            : "确认保存后，下列更改将应用到当前服务器。"));
        review.Children.Add(introduction);
        foreach (var section in Sections)
        {
            var changed = section.Fields.Where(field => draft.Values.ContainsKey(field.Key)).ToArray();
            if (changed.Length == 0) continue;
            var group = new StackPanel { Spacing = 8 };
            group.Children.Add(Caption(section.Title));
            foreach (var field in changed)
            {
                var row = new StackPanel { Spacing = 4 };
                row.Children.Add(Heading(field.Label));
                AddValue(row, "当前服务器", DisplayValue(field, snapshot.Values[field.Key]), id + field.Key + "Current");
                AddValue(row, comparison ? "本地草稿" : "拟保存", DisplayValue(field, AppliedValue(field, draft.Values[field.Key])),
                    id + field.Key + "Draft");
                group.Children.Add(row);
            }
            review.Children.Add(group);
        }
        if (draft.Rules is { } rules)
        {
            var group = new StackPanel { Spacing = 8 };
            group.Children.Add(Heading("路径规则"));
            group.Children.Add(Caption("按列表顺序检查，最后一条匹配生效。"));
            AddValue(group, "当前服务器", FormatRules(snapshot.Rules), id + "RulesCurrent");
            AddValue(group, comparison ? "本地草稿" : "拟保存", FormatRules(rules), id + "RulesDraft");
            review.Children.Add(group);
        }
        PresentBody(review);
    }

    private static string FormatRules(IReadOnlyList<ServerPathRule> rules) => rules.Count == 0 ? "（无规则）"
        : string.Join('\n', rules.Select((rule, index) =>
            $"{index + 1}. {(rule.Allow ? "允许" : "拒绝")} · {(rule.RegularExpression ? "正则" : "路径")} · {rule.Match}"));

    private void ContinueEditing()
    {
        if (_busy || _pending is not { } draft || _requiredRead != RequiredRead.None || !Current) return;
        if (_comparisonSnapshot is { } snapshot)
        {
            Apply(snapshot, draft, _editorOffset);
            SetStatus("已保留草稿。请复核后再保存。");
        }
        else
        {
            _pending = null;
            SetStatus("");
            RenderSection(_editorOffset);
        }
        UpdateActions();
        _section.Focus(FocusState.Programmatic);
    }

    private async Task SaveAsync()
    {
        if (_busy || _requiredRead != RequiredRead.None || _comparisonSnapshot is not null ||
            _pending is not { } pending || _snapshot is not { } snapshot || !Current) return;
        _busy = IsSaving = true;
        SetStatus("正在保存服务器设置…");
        UpdateActions();
        try
        {
            var result = await _service.SaveServerSettingsAsync(_account, snapshot, pending, _token);
            if (!Current) return;
            Apply(result, offset: _editorOffset);
            SetStatus("服务器设置已保存。");
        }
        catch (Exception error)
        {
            if (!Current) return;
            var failure = (error as FilewayException)?.Error;
            switch (failure)
            {
                case { Code: ErrorCode.Conflict or ErrorCode.SourceChanged } or { HttpStatus: 409 }:
                    _requiredRead = RequiredRead.Conflict;
                    SetStatus("服务器设置已变化。请读取当前值并核对草稿。", attention: true);
                    break;
                case { Code: ErrorCode.OutcomeUnknown } or { HttpStatus: 408 }:
                    MarkOutcomeUnknown();
                    break;
                case { Code: ErrorCode.InvalidRequest or ErrorCode.AuthenticationExpired or ErrorCode.PermissionDenied }
                    or { HttpStatus: >= 400 and < 500 }:
                    _pending = null;
                    SetStatus(failure.Code is ErrorCode.InvalidRequest or ErrorCode.AuthenticationExpired or ErrorCode.PermissionDenied
                        ? failure.Message
                        : "服务器拒绝了设置更改。草稿已保留，请检查后再提交。", attention: true);
                    RenderSection(_editorOffset);
                    break;
                default:
                    MarkOutcomeUnknown();
                    break;
            }
        }
        finally
        {
            _busy = IsSaving = false;
            if (Current)
            {
                UpdateActions();
                (_requiredRead == RequiredRead.None ? _review : _reload).Focus(FocusState.Programmatic);
            }
        }
    }

    private void MarkOutcomeUnknown()
    {
        _requiredRead = RequiredRead.Unknown;
        SetStatus("保存结果尚未确认。请读取服务器设置并核对草稿。", attention: true);
    }

    private void UpdateActions()
    {
        bool hasSnapshot = _snapshot is not null;
        bool reviewing = _pending is not null;
        bool comparing = _comparisonSnapshot is not null;
        bool readRequired = _requiredRead != RequiredRead.None;
        bool readIsPrimary = !hasSnapshot || readRequired;
        _header.Visibility = hasSnapshot && !reviewing ? Visibility.Visible : Visibility.Collapsed;
        _section.IsEnabled = !_busy;
        _body.IsEnabled = !_busy || reviewing;
        _reload.IsEnabled = !_busy;
        _reload.Content = readRequired ? "读取并核对" : "重新读取";
        _reload.Visibility = !reviewing || readRequired ? Visibility.Visible : Visibility.Collapsed;
        _reload.Style = ActionStyle(readIsPrimary);
        Grid.SetColumn(_reload, readIsPrimary ? 3 : 1);
        _review.Visibility = hasSnapshot && !reviewing ? Visibility.Visible : Visibility.Collapsed;
        _review.IsEnabled = !_busy;
        _save.Visibility = reviewing && !comparing && !readRequired ? Visibility.Visible : Visibility.Collapsed;
        _edit.Visibility = reviewing && !readRequired ? Visibility.Visible : Visibility.Collapsed;
        _edit.Style = ActionStyle(comparing);
        Grid.SetColumn(_edit, comparing ? 3 : 1);
        _save.IsEnabled = _edit.IsEnabled = !_busy;
        _close.IsEnabled = !IsSaving;
        UpdateScrollHeight();
    }

    public void Dispose()
    {
        if (_hostRoot is not null) _hostRoot.Changed -= OnHostSizeChanged;
        _lifetime.Cancel();
        _lifetime.Dispose();
    }
}
