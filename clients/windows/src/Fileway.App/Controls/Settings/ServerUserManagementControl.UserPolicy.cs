using Fileway.Core.Contracts;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Settings;

public sealed partial class ServerUserManagementControl
{
    private sealed record PolicyDraft(string Scope, PermissionSet Permissions, IReadOnlyList<string> Commands, IReadOnlyList<ServerPathRule> Rules);
    private sealed record PendingPolicy(AdminUser Baseline, PolicyDraft Draft, bool Conflict);
    private readonly Dictionary<ulong, PendingPolicy> _pendingPolicies = [];

    private static readonly (string Label, string Id, Func<PermissionSet, bool> Enabled)[] PolicyFlags =
    [
        ("下载", "Download", value => value.Download), ("分享", "Share", value => value.Share),
        ("创建", "Create", value => value.Create), ("重命名", "Rename", value => value.Rename),
        ("修改", "Modify", value => value.Modify), ("删除", "Delete", value => value.Delete),
        ("执行命令", "Execute", value => value.Execute),
    ];

    private async Task EditUserPolicyAsync()
    {
        if (!_isAdmin || _busy || _token.IsCancellationRequested || SelectedAdminUser() is not { } initial) return;
        var pending = _pendingPolicies.GetValueOrDefault(initial.Id);
        var baseline = pending?.Baseline ?? initial;
        var startingDraft = pending?.Draft ?? new(baseline.Scope, baseline.Permissions, baseline.Commands ?? [], baseline.Rules ?? []);
        var caption = SurfaceGeometry.Style("FwCaptionTextStyle");
        var scope = new TextBox { Text = startingDraft.Scope, MaxLength = 4096, Style = SurfaceGeometry.Style("FwTextBoxStyle") };
        AutomationProperties.SetAutomationId(scope, "ServerUserScope");
        AutomationProperties.SetName(scope, "服务器目录范围");
        var permissions = new Grid { RowSpacing = 4, ColumnSpacing = 12 };
        permissions.ColumnDefinitions.Add(new ColumnDefinition());
        permissions.ColumnDefinitions.Add(new ColumnDefinition());
        var boxes = new CheckBox[PolicyFlags.Length];
        for (int index = 0; index < boxes.Length; index++)
        {
            var flag = PolicyFlags[index];
            var box = new CheckBox { Content = flag.Label, IsChecked = flag.Enabled(startingDraft.Permissions) };
            AutomationProperties.SetAutomationId(box, "ServerUserPermission" + flag.Id);
            if (index % 2 == 0) permissions.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            Grid.SetColumn(box, index % 2);
            Grid.SetRow(box, index / 2);
            permissions.Children.Add(box);
            boxes[index] = box;
        }
        boxes[0].Unchecked += (_, _) => { boxes[1].IsChecked = false; boxes[1].IsEnabled = false; };
        boxes[0].Checked += (_, _) => boxes[1].IsEnabled = true;
        boxes[1].Unchecked += (_, _) => boxes[1].IsEnabled = boxes[0].IsChecked == true;
        boxes[1].IsEnabled = startingDraft.Permissions.Download || startingDraft.Permissions.Share;
        var commands = new TextBox
        {
            Text = string.Join('\n', startingDraft.Commands), AcceptsReturn = true, TextWrapping = TextWrapping.Wrap,
            MinHeight = 64, MaxHeight = 120, MaxLength = 32768, Style = SurfaceGeometry.Style("FwTextBoxStyle"),
        };
        AutomationProperties.SetAutomationId(commands, "ServerUserAllowedCommands");
        AutomationProperties.SetName(commands, "允许命令，每行一条");
        var rules = new ServerRulesEditor(startingDraft.Rules, "UserRules");
        var rulesSlot = new ContentControl { Content = rules, HorizontalContentAlignment = HorizontalAlignment.Stretch, IsTabStop = false };
        var editor = new StackPanel { Spacing = 8 };
        editor.Children.Add(PolicyText("服务器目录范围", caption));
        editor.Children.Add(scope);
        editor.Children.Add(PolicyHeading("文件权限"));
        editor.Children.Add(permissions);
        editor.Children.Add(PolicyHeading("允许命令"));
        editor.Children.Add(PolicyText(_enableExec == true
            ? "每行一条，账户同时需要执行权限。"
            : "每行一条。当前服务器未启用或未报告执行能力。", caption));
        editor.Children.Add(commands);
        editor.Children.Add(PolicyHeading("账户路径规则"));
        editor.Children.Add(rulesSlot);

        var scroll = new ScrollViewer
        {
            Content = editor, MaxHeight = DialogOrdering.ContentMaxHeight(this, 470, reserved: 260, minimum: 180),
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollBarVisibility = ScrollBarVisibility.Visible,
            Padding = new Thickness(0, 0, 6, 8),
        };
        var password = new PasswordBox { MaxLength = 1024, HorizontalAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(password, "ServerUserPolicyPassword");
        AutomationProperties.SetName(password, "当前管理员密码");
        var passwordArea = new StackPanel { Spacing = 4, Visibility = Visibility.Collapsed };
        passwordArea.Children.Add(PolicyText("当前管理员密码", caption));
        passwordArea.Children.Add(password);

        var feedback = PolicyText("", caption);
        feedback.Visibility = Visibility.Collapsed;
        AutomationProperties.SetAutomationId(feedback, "ServerUserPolicyFeedback");
        AutomationProperties.SetLiveSetting(feedback, AutomationLiveSetting.Polite);
        var read = PolicyButton("核对服务器策略", "ServerUserPolicyReconcile", primary: true);
        var rebase = PolicyButton("以服务器值为基线继续编辑", "ServerUserPolicyRebase", primary: true);
        read.Visibility = rebase.Visibility = Visibility.Collapsed;

        var secondary = PolicyButton("取消", "ServerUserPolicyBack", primary: false);
        var primary = PolicyButton("检查更改…", "ServerUserPolicyReview", primary: true);
        var footer = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 4, 0, 0) };
        footer.ColumnDefinitions.Add(new ColumnDefinition());
        footer.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        secondary.HorizontalAlignment = HorizontalAlignment.Left;
        footer.Children.Add(secondary);
        var primarySlot = new Grid();
        primarySlot.Children.Add(primary);
        primarySlot.Children.Add(read);
        primarySlot.Children.Add(rebase);
        Grid.SetColumn(primarySlot, 1);
        footer.Children.Add(primarySlot);
        var content = new StackPanel { Spacing = 10, MinWidth = 344, MaxWidth = 500 };
        content.Children.Add(scroll);
        content.Children.Add(passwordArea);
        content.Children.Add(feedback);
        content.Children.Add(new Border { Style = SurfaceGeometry.Style("FwDividerStyle"), Height = 1 });
        content.Children.Add(footer);
        var dialog = new ContentDialog { Title = $"编辑 {initial.Username} 的权限与规则", Content = content };

        bool reviewing = false;
        bool working = false;
        bool observed = false;
        double editorOffset = 0;
        Control? lastEditorFocus = null;
        PolicyDraft? reviewed = null;
        AdminUser? serverCurrent = null;
        bool needsRead = false;
        var hostRoot = XamlRoot;
        editor.GotFocus += (_, args) => { if (args.OriginalSource is Control input) lastEditorFocus = input; };
        password.PasswordChanged += (_, _) => RenderCommands();
        secondary.Click += (_, _) =>
        {
            if (working) return;
            if (reviewing && !needsRead && !observed) BackToEditing();
            else dialog.Hide();
        };
        primary.Click += async (_, _) =>
        {
            if (!_isAdmin || working || _token.IsCancellationRequested) return;
            if (!reviewing) Review();
            else if (observed) dialog.Hide();
            else await SaveAsync();
        };
        read.Click += async (_, _) => await ReadServerAsync();
        rebase.Click += (_, _) => Rebase();
        dialog.Closing += (_, args) => { if (working && _isAdmin && !_token.IsCancellationRequested) args.Cancel = true; };
        if (hostRoot is not null) hostRoot.Changed += HostChanged;
        if (pending is not null)
        {
            reviewed = pending.Draft;
            reviewing = needsRead = true;
            scroll.Content = PolicyReview(baseline, pending.Draft);
            SetFeedback(ReconciliationPrompt(pending.Conflict), error: true);
            dialog.Opened += (_, _) => read.Focus(FocusState.Programmatic);
        }
        try { await ShowDialogAsync(dialog); }
        finally
        {
            if (hostRoot is not null) hostRoot.Changed -= HostChanged;
            password.Password = "";
        }

        void HostChanged(XamlRoot sender, XamlRootChangedEventArgs args) => ResizeBody();

        void ResizeBody()
        {
            double height = hostRoot?.Size.Height ?? ActualHeight;
            if (height <= 0) return;
            double reserved = 260 + (passwordArea.Visibility == Visibility.Visible ? 72 : 0) +
                (feedback.Visibility == Visibility.Visible ? 40 : 0);
            scroll.MaxHeight = Math.Clamp(height - reserved, 140, reviewing ? 322 : 470);
        }

        void RenderCommands()
        {
            secondary.Content = !reviewing ? "取消" : !needsRead && !observed ? "继续编辑" : "关闭";
            secondary.IsEnabled = !working;
            primary.Content = !reviewing ? "检查更改…" : observed ? "完成" : "保存策略";
            AutomationProperties.SetAutomationId(primary, reviewing ? "ServerUserPolicyCommit" : "ServerUserPolicyReview");
            bool canSave = !needsRead && (!_adminRequiresPassword || password.Password.Length > 0);
            primary.IsEnabled = !working && (!reviewing || observed || canSave);
            primary.Visibility = needsRead ? Visibility.Collapsed : Visibility.Visible;
            scroll.VerticalScrollBarVisibility = reviewing ? ScrollBarVisibility.Auto : ScrollBarVisibility.Visible;
            passwordArea.Visibility = reviewing && _adminRequiresPassword && !needsRead && !observed
                ? Visibility.Visible : Visibility.Collapsed;
            password.IsEnabled = !working;
            read.Visibility = needsRead && serverCurrent is null ? Visibility.Visible : Visibility.Collapsed;
            rebase.Visibility = serverCurrent is not null && !observed ? Visibility.Visible : Visibility.Collapsed;
            read.IsEnabled = rebase.IsEnabled = !working;
            ResizeBody();
        }

        void SetFeedback(string message, bool error = false)
        {
            feedback.Text = message;
            feedback.Style = SurfaceGeometry.Style(error ? "FwDangerTextStyle" : "FwCaptionTextStyle");
            feedback.Visibility = message.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            RenderCommands();
            if (message.Length > 0)
                FrameworkElementAutomationPeer.FromElement(feedback)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
        }

        PolicyDraft ReadDraft()
        {
            var originalCommands = baseline.Commands ?? [];
            string commandText = commands.Text.ReplaceLineEndings("\n");
            var commandDraft = commandText == string.Join('\n', originalCommands).ReplaceLineEndings("\n")
                ? originalCommands : commandText.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
            return new(scope.Text,
                new PermissionSet(boxes[0].IsChecked == true, boxes[2].IsChecked == true, boxes[3].IsChecked == true,
                    boxes[4].IsChecked == true, boxes[5].IsChecked == true, boxes[6].IsChecked == true,
                    baseline.Permissions.Admin, boxes[1].IsChecked == true), commandDraft, rules.ReadDraft());
        }

        void Review()
        {
            var draft = ReadDraft();
            if (draft.Rules.Any(rule => string.IsNullOrWhiteSpace(rule.Match)))
            {
                SetFeedback("请填写所有路径规则，或移除空白规则。", error: true);
                return;
            }
            if (draft.Permissions.Share && !draft.Permissions.Download)
            {
                SetFeedback("启用分享权限前，请先启用下载权限。", error: true);
                return;
            }
            if (PolicyUpdate(baseline, draft) is null)
            {
                SetFeedback("没有需要保存的更改。");
                return;
            }
            editorOffset = scroll.VerticalOffset;
            reviewed = draft;
            reviewing = true;
            scroll.Content = PolicyReview(baseline, draft);
            scroll.ChangeView(null, 0, null, true);
            SetFeedback("");
            if (_adminRequiresPassword) FocusPassword();
            else primary.Focus(FocusState.Programmatic);
        }

        void BackToEditing()
        {
            reviewing = false;
            password.Password = "";
            scroll.Content = editor;
            SetFeedback("");
            primary.Focus(FocusState.Programmatic);
            DispatcherQueue.TryEnqueue(() =>
            {
                if (reviewing || !_isAdmin) return;
                lastEditorFocus?.Focus(FocusState.Programmatic);
                scroll.ChangeView(null, editorOffset, null, true);
            });
        }

        void FocusPassword()
        {
            if (!password.Focus(FocusState.Programmatic))
                DispatcherQueue.TryEnqueue(() =>
                {
                    if (reviewing && !needsRead && _isAdmin) password.Focus(FocusState.Programmatic);
                });
        }

        async Task SaveAsync()
        {
            if (needsRead || reviewed is not { } draft || PolicyUpdate(baseline, draft) is not { } update ||
                _adminRequiresPassword && password.Password.Length == 0) return;
            string secret = password.Password;
            password.Password = "";
            working = true;
            SetBusy(true);
            SetFeedback("正在保存策略…");
            bool saved = false;
            bool focusPassword = false;
            try
            {
                await _adminUsers.UpdateAsync(_account, update, secret, _token);
                saved = true;
            }
            catch (OperationCanceledException) when (_token.IsCancellationRequested) { }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.InvalidRequest && error.Error.HttpStatus == 400)
            {
                SetFeedback(error.Error.Message, error: true);
                focusPassword = true;
            }
            catch (FilewayException error) when (error.Error.Code == ErrorCode.Conflict || error.Error.HttpStatus == 409)
            {
                RequireReconciliation(draft, conflict: true);
            }
            catch (FilewayException error) when (error.Error.Code is ErrorCode.InvalidRequest or ErrorCode.PermissionDenied or ErrorCode.AuthenticationExpired ||
                error.Error.HttpStatus is 400 or 401 or 403 or 404 or 412 or 422)
            {
                SetFeedback(error.Error.Code is ErrorCode.InvalidRequest or ErrorCode.PermissionDenied or ErrorCode.AuthenticationExpired
                    ? error.Error.Message : "服务器拒绝了策略更改。请检查输入或路径规则后重试。", error: true);
            }
            catch (Exception)
            {
                RequireReconciliation(draft, conflict: false);
            }
            finally
            {
                secret = "";
                working = false;
                SetBusy(false);
                RenderCommands();
            }
            if (!_isAdmin || _token.IsCancellationRequested) return;
            if (saved)
            {
                dialog.Hide();
                AddAdminStatus("用户权限与规则已保存。", InfoBarSeverity.Success);
                await RefreshAdminAsync();
            }
            else if (focusPassword) FocusPassword();
            else if (needsRead) read.Focus(FocusState.Programmatic);
        }

        void RequireReconciliation(PolicyDraft draft, bool conflict)
        {
            needsRead = true;
            _pendingPolicies[baseline.Id] = new(baseline, draft, conflict);
            SetFeedback(ReconciliationPrompt(conflict), error: true);
        }

        async Task ReadServerAsync()
        {
            if (!_isAdmin || working || !needsRead || reviewed is not { } draft) return;
            working = true;
            serverCurrent = null;
            SetBusy(true);
            SetFeedback("正在读取服务器当前策略…");
            try
            {
                var users = await _adminUsers.ListAsync(_account, _token);
                if (!_isAdmin || _token.IsCancellationRequested) return;
                var current = users.FirstOrDefault(user => user.Id == initial.Id);
                if (current is null || !string.Equals(current.Username, baseline.Username, StringComparison.Ordinal))
                {
                    SetFeedback("当前服务器上的账户已更名或不可用，请返回用户列表刷新。", error: true);
                    return;
                }
                observed = PolicyUpdate(current, MergePolicyDraft(baseline, current, draft)) is null;
                if (observed)
                {
                    needsRead = false;
                    _pendingPolicies.Remove(initial.Id);
                    SetFeedback("服务器当前策略已包含这些更改。");
                    AddAdminStatus("已核实用户策略。", InfoBarSeverity.Success);
                    await RefreshAdminAsync();
                }
                else
                {
                    serverCurrent = current;
                    scroll.Content = PolicyReview(baseline, draft, current);
                    SetFeedback("服务器当前值与本次草稿不同。核对后可以继续编辑。");
                }
            }
            catch (OperationCanceledException) when (_token.IsCancellationRequested) { }
            catch (Exception)
            {
                if (_isAdmin && !_token.IsCancellationRequested) SetFeedback("暂时无法读取服务器策略，请稍后重新核对。", error: true);
            }
            finally
            {
                working = false;
                SetBusy(false);
                RenderCommands();
                if (_isAdmin && !_token.IsCancellationRequested)
                    (observed ? primary : serverCurrent is null ? read : rebase).Focus(FocusState.Programmatic);
            }
        }

        void Rebase()
        {
            if (!_isAdmin || working || serverCurrent is not { } current || reviewed is not { } draft) return;
            var merged = MergePolicyDraft(baseline, current, draft);
            baseline = current;
            scope.Text = merged.Scope;
            for (int index = 0; index < boxes.Length; index++)
                boxes[index].IsChecked = PolicyFlags[index].Enabled(merged.Permissions);
            boxes[1].IsEnabled = merged.Permissions.Download || merged.Permissions.Share;
            commands.Text = string.Join('\n', merged.Commands);
            if (!merged.Rules.SequenceEqual(draft.Rules))
            {
                rules = new ServerRulesEditor(merged.Rules, "UserRules");
                rulesSlot.Content = rules;
            }
            needsRead = false;
            _pendingPolicies.Remove(initial.Id);
            serverCurrent = null;
            BackToEditing();
            if (merged.Permissions.Share && !merged.Permissions.Download)
                SetFeedback("下载权限已关闭。请选择关闭分享或启用下载，再复核更改。", error: true);
            else SetFeedback("已读取新的服务器基线。请复核需要保存的更改。");
        }
    }

    private static AdminUserUpdate? PolicyUpdate(AdminUser baseline, PolicyDraft draft)
    {
        bool permissionChanged = baseline.Permissions != draft.Permissions;
        string? scope = baseline.Scope == draft.Scope ? null : draft.Scope;
        var commands = draft.Commands.SequenceEqual(baseline.Commands ?? []) ? null : draft.Commands;
        var rules = draft.Rules.SequenceEqual(baseline.Rules ?? []) ? null : draft.Rules;
        return !permissionChanged && scope is null && commands is null && rules is null ? null
            : new AdminUserUpdate(baseline.Id, null, null, null, permissionChanged ? draft.Permissions : null,
                SharePermission: baseline.Permissions.Share == draft.Permissions.Share ? null : draft.Permissions.Share,
                Scope: scope, Commands: commands, Rules: rules, ExpectedPolicy: baseline);
    }

    private static PolicyDraft MergePolicyDraft(AdminUser baseline, AdminUser current, PolicyDraft draft)
    {
        bool Keep(Func<PermissionSet, bool> flag) => flag(baseline.Permissions) != flag(draft.Permissions)
            ? flag(draft.Permissions) : flag(current.Permissions);
        return new(baseline.Scope == draft.Scope ? current.Scope : draft.Scope,
            new PermissionSet(Keep(PolicyFlags[0].Enabled), Keep(PolicyFlags[2].Enabled), Keep(PolicyFlags[3].Enabled),
                Keep(PolicyFlags[4].Enabled), Keep(PolicyFlags[5].Enabled), Keep(PolicyFlags[6].Enabled),
                current.Permissions.Admin, Keep(PolicyFlags[1].Enabled)),
            draft.Commands.SequenceEqual(baseline.Commands ?? []) ? current.Commands ?? [] : draft.Commands,
            draft.Rules.SequenceEqual(baseline.Rules ?? []) ? current.Rules ?? [] : draft.Rules);
    }

    private static StackPanel PolicyReview(AdminUser baseline, PolicyDraft draft, AdminUser? serverCurrent = null)
    {
        var body = new StackPanel { Spacing = 8 };
        var title = PolicyHeading("复核用户策略");
        AutomationProperties.SetAutomationId(title, "ServerUserPolicyReviewSummary");
        body.Children.Add(title);
        if (baseline.Scope != draft.Scope)
            AddReviewValue("服务器目录范围", ScopeLabel(baseline.Scope), ScopeLabel(draft.Scope), serverCurrent is null ? null : ScopeLabel(serverCurrent.Scope));
        foreach (var flag in PolicyFlags)
        {
            bool old = flag.Enabled(baseline.Permissions);
            bool requested = flag.Enabled(draft.Permissions);
            if (old != requested)
                AddReviewValue(flag.Label, PermissionLabel(old), PermissionLabel(requested),
                    serverCurrent is null ? null : PermissionLabel(flag.Enabled(serverCurrent.Permissions)));
        }
        if (!draft.Commands.SequenceEqual(baseline.Commands ?? []))
            AddReviewValue("允许命令", ListLabel(baseline.Commands ?? []), ListLabel(draft.Commands),
                serverCurrent is null ? null : ListLabel(serverCurrent.Commands ?? []));
        if (!draft.Rules.SequenceEqual(baseline.Rules ?? []))
            AddReviewValue("账户路径规则", RuleLabel(baseline.Rules ?? []), RuleLabel(draft.Rules),
                serverCurrent is null ? null : RuleLabel(serverCurrent.Rules ?? []));
        return body;

        void AddReviewValue(string label, string previous, string next, string? current)
        {
            var field = new StackPanel { Spacing = 4 };
            field.Children.Add(PolicyHeading(label));
            field.Children.Add(PolicyText($"原值 · {previous}", SurfaceGeometry.Style("FwCaptionTextStyle")));
            field.Children.Add(PolicyText($"草稿 · {next}"));
            if (current is not null)
                field.Children.Add(PolicyText($"服务器当前值 · {current}", SurfaceGeometry.Style("FwCaptionTextStyle")));
            body.Children.Add(field);
        }
    }

    private static string PermissionLabel(bool allowed) => allowed ? "启用" : "关闭";
    private static string ReconciliationPrompt(bool conflict) => conflict
        ? "服务器策略已变化。请读取当前值并核对草稿。"
        : "保存结果需要核对。请先读取服务器当前策略。";
    private static string ScopeLabel(string scope) => scope.Length == 0 ? "服务器默认范围" : scope;
    private static string ListLabel(IReadOnlyList<string> items) => items.Count == 0 ? "无" : string.Join('\n', items);
    private static string RuleLabel(IReadOnlyList<ServerPathRule> rules) => ListLabel(rules.Select((rule, index) =>
        $"{index + 1}. {(rule.Allow ? "允许" : "拒绝")} · {(rule.RegularExpression ? "正则" : "路径")} · {rule.Match}").ToArray());

    private static TextBlock PolicyHeading(string text) => new() { Text = text, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };
    private static TextBlock PolicyText(string text, Style? style = null) => new() { Text = text, Style = style, TextWrapping = TextWrapping.Wrap };
    private static Button PolicyButton(string label, string id, bool primary)
    {
        var button = new Button { Content = label, Style = SurfaceGeometry.Style(primary ? "FwPrimaryButtonStyle" : "FwSecondaryButtonStyle") };
        AutomationProperties.SetAutomationId(button, id);
        return button;
    }
}
