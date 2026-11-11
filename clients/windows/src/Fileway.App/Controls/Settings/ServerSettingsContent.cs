using Fileway.Core.Contracts;
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
    private readonly ChoiceBox _section = new() { HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly ContentControl _body = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly Button _reload;
    private readonly Button _review;
    private readonly Button _save;
    private readonly Button _edit;
    private readonly Grid _layout;
    private readonly double _editorHeight;
    private readonly List<FrameworkElement> _sectionContents = [];
    private readonly Dictionary<ServerSettingField, Func<string>> _fields = [];
    private ServerSettingsSnapshot? _snapshot;
    private ServerRulesEditor? _rules;
    private ServerSettingsEdit? _pending;
    private bool _busy;
    private bool _mustRefresh;

    internal bool IsSaving { get; private set; }
    private bool Current => !_token.IsCancellationRequested && _isCurrent();

    internal ServerSettingsContent(IServerSettingsService service, AccountKey account, Func<bool> isCurrent, double height, CancellationToken lifetime)
    {
        _service = service;
        _account = account;
        _isCurrent = isCurrent;
        _lifetime = CancellationTokenSource.CreateLinkedTokenSource(lifetime);
        _token = _lifetime.Token;
        _editorHeight = height;
        var resources = Application.Current.Resources;
        _capabilities.Style = (Style)resources["FwCaptionTextStyle"];
        AutomationProperties.SetAutomationId(_status, "ServerSettingsStatus");
        AutomationProperties.SetLiveSetting(_status, AutomationLiveSetting.Polite);
        AutomationProperties.SetAutomationId(_section, "ServerSettingsSection");
        AutomationProperties.SetName(_section, "服务器设置分组");
        foreach (var item in Sections) _section.Items.Add(new ComboBoxItem { Content = item.Title });
        _section.SelectionChanged += (_, _) => RenderSection();
        _reload = CreateButton("重新读取", "ReloadServerSettings");
        _review = CreateButton("检查更改…", "ReviewServerSettings", primary: true);
        _save = CreateButton("保存服务器设置", "SaveServerSettings", primary: true);
        _edit = CreateButton("继续编辑", "EditServerSettings");
        _reload.Click += async (_, _) => await ReloadAsync();
        _review.Click += (_, _) => Review();
        _edit.Click += (_, _) => { _pending = null; _status.Text = ""; RenderSection(); UpdateActions(); };
        _save.Click += async (_, _) => await SaveAsync();
        var header = new StackPanel { Spacing = 8 };
        header.Children.Add(_capabilities);
        header.Children.Add(_section);
        header.Children.Add(_status);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Right };
        actions.Children.Add(_reload);
        actions.Children.Add(_edit);
        actions.Children.Add(_review);
        actions.Children.Add(_save);
        var scroll = new ScrollViewer
        {
            Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };
        _layout = new Grid { RowSpacing = 12, Height = height, MinWidth = 344, MaxWidth = 480 };
        _layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _layout.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        _layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _layout.Children.Add(header);
        Grid.SetRow(scroll, 1);
        _layout.Children.Add(scroll);
        Grid.SetRow(actions, 2);
        _layout.Children.Add(actions);
        Content = _layout;
        UpdateActions();
        Loaded += OnLoaded;
    }

    private static Button CreateButton(string label, string id, bool primary = false)
    {
        var button = new Button
        {
            Content = label,
            Style = (Style)Application.Current.Resources[primary ? "FwPrimaryButtonStyle" : "FwSecondaryButtonStyle"],
        };
        AutomationProperties.SetAutomationId(button, id);
        return button;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        await ReloadAsync();
    }

    private async Task ReloadAsync()
    {
        if (_busy || !Current) return;
        _busy = true;
        _pending = null;
        _status.Text = "正在读取服务器设置…";
        UpdateActions();
        try
        {
            var snapshot = await _service.ReadServerSettingsAsync(_account, _token);
            if (!Current) return;
            Apply(snapshot);
            _status.Text = "";
        }
        catch (Exception error)
        {
            if (Current)
                _status.Text = error is FilewayException known ? known.Error.Message : "服务器设置未能读取，请重试。";
        }
        finally
        {
            _busy = false;
            if (Current) { RenderSection(); UpdateActions(); }
        }
    }

    private void Apply(ServerSettingsSnapshot snapshot)
    {
        _snapshot = snapshot;
        _mustRefresh = false;
        _pending = null;
        _body.Content = null;
        _fields.Clear();
        _sectionContents.Clear();
        _rules = new ServerRulesEditor(snapshot.Rules, "GlobalRules");
        string authentication = snapshot.AuthMethod switch { "json" => "账号密码", "noauth" => "无身份验证", "" => "未知", _ => snapshot.AuthMethod };
        _capabilities.Text = $"身份验证：{authentication} · 命令执行：" + (snapshot.CommandExecutionEnabled switch
        {
            true => "已启用", false => "服务器未启用", null => "未知",
        });
        foreach (var section in Sections)
        {
            var content = new StackPanel { Spacing = 10, Padding = new Thickness(0, 0, 8, 0) };
            content.Children.Add(new TextBlock { Text = section.Caption, TextWrapping = TextWrapping.Wrap,
                Style = (Style)Application.Current.Resources["FwCaptionTextStyle"] });
            if (section.Fields.Length == 0) content.Children.Add(_rules);
            foreach (var field in section.Fields)
            {
                var value = snapshot.Values[field.Key];
                if (field.Toggle)
                {
                    var toggle = new CheckBox { Content = field.Label, IsChecked = bool.TryParse(value, out var on) && on };
                    AutomationProperties.SetAutomationId(toggle, "ServerSetting" + field.Key);
                    _fields.Add(field.Key, () => (toggle.IsChecked == true).ToString());
                    content.Children.Add(toggle);
                    continue;
                }
                content.Children.Add(new TextBlock { Text = field.Label, Style = (Style)Application.Current.Resources["FwCaptionTextStyle"] });
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
                content.Children.Add(box);
            }
            _sectionContents.Add(content);
        }
        if (_section.SelectedIndex < 0) _section.SelectedIndex = 0;
        RenderSection();
    }

    private void RenderSection()
    {
        if (_pending is not null || _section.SelectedIndex < 0 || _section.SelectedIndex >= _sectionContents.Count) return;
        _body.Content = _sectionContents[_section.SelectedIndex];
    }

    private void Review()
    {
        if (_snapshot is not { } snapshot || _rules is null || _busy || _mustRefresh || !Current) return;
        var values = new Dictionary<ServerSettingField, string>();
        foreach (var (field, read) in _fields)
        {
            string value = read();
            if (value != snapshot.Values[field]) values.Add(field, value);
        }
        var rules = _rules.ReadDraft();
        bool changedRules = !rules.SequenceEqual(snapshot.Rules);
        if (values.Count == 0 && !changedRules) { _status.Text = "没有需要保存的更改。"; return; }
        if (changedRules && rules.Any(rule => rule.Match.Length == 0))
        {
            _status.Text = "请填写所有路径规则，或移除空白规则。";
            _section.SelectedIndex = Sections.Length - 1;
            return;
        }
        _pending = new(values, changedRules ? rules : null);
        var review = new StackPanel { Spacing = 8 };
        review.Children.Add(new TextBlock { Text = "确认要应用到当前服务器的更改", FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        foreach (var section in Sections)
        {
            var changed = section.Fields.Where(field => values.ContainsKey(field.Key)).Select(field => field.Label).ToArray();
            if (changed.Length > 0)
                review.Children.Add(new TextBlock { Text = section.Title + "： " + string.Join("、", changed), TextWrapping = TextWrapping.Wrap });
        }
        if (changedRules) review.Children.Add(new TextBlock { Text = "路径规则：保存列表中的规则及其顺序。", TextWrapping = TextWrapping.Wrap });
        _status.Text = "保存会修改当前服务器的配置。";
        _body.Content = review;
        UpdateActions();
    }

    private async Task SaveAsync()
    {
        if (_busy || _pending is not { } pending || _snapshot is not { } snapshot || !Current) return;
        _busy = IsSaving = true;
        _status.Text = "正在保存服务器设置…";
        UpdateActions();
        try
        {
            var result = await _service.SaveServerSettingsAsync(_account, snapshot, pending, _token);
            if (!Current) return;
            Apply(result);
            _status.Text = "服务器设置已保存。";
        }
        catch (Exception error)
        {
            if (!Current) return;
            _pending = null;
            _mustRefresh = error is FilewayException { Error.Code: ErrorCode.OutcomeUnknown or ErrorCode.Conflict };
            _status.Text = error is FilewayException known ? known.Error.Message : "服务器设置未能保存。请重新读取后核对。";
            RenderSection();
        }
        finally
        {
            _busy = IsSaving = false;
            if (Current) UpdateActions();
        }
    }

    private void UpdateActions()
    {
        bool ready = _snapshot is not null && !_busy;
        bool reviewing = _pending is not null;
        _layout.Height = reviewing ? Math.Min(_editorHeight, 304) : _editorHeight;
        _section.Visibility = reviewing ? Visibility.Collapsed : Visibility.Visible;
        _section.IsEnabled = ready && !reviewing;
        _body.IsEnabled = !_busy && !_mustRefresh;
        _reload.IsEnabled = !_busy;
        _reload.Content = _snapshot is null || _mustRefresh ? "重新读取" : "放弃并重载";
        _reload.Visibility = reviewing ? Visibility.Collapsed : Visibility.Visible;
        _review.Visibility = reviewing ? Visibility.Collapsed : Visibility.Visible;
        _review.IsEnabled = ready && !_mustRefresh;
        _save.Visibility = _edit.Visibility = reviewing ? Visibility.Visible : Visibility.Collapsed;
        _save.IsEnabled = _edit.IsEnabled = ready;
    }

    public void Dispose()
    {
        _lifetime.Cancel();
        _lifetime.Dispose();
    }
}
