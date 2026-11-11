using Fileway.App.Controls.Settings;
using Fileway.Core.Contracts;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.StorageTools;

public sealed partial class StorageAnalysisControl
{
    private async Task EditUserPolicyAsync()
    {
        if (_adminUsers is null || !_isAdmin || _busy || _token.IsCancellationRequested || SelectedAdminUser() is not { } user) return;
        var old = user.Permissions;
        var style = (Style)Application.Current.Resources["FwTextBoxStyle"];
        var caption = (Style)Application.Current.Resources["FwCaptionTextStyle"];
        var scope = new TextBox { Text = user.Scope, MaxLength = 4096, Style = style };
        AutomationProperties.SetAutomationId(scope, "ServerUserScope");
        AutomationProperties.SetName(scope, "服务器目录范围");
        var permissions = new Grid { RowSpacing = 6, ColumnSpacing = 8 };
        permissions.ColumnDefinitions.Add(new ColumnDefinition());
        permissions.ColumnDefinitions.Add(new ColumnDefinition());
        var flags = new[]
        {
            ("下载", "Download", old.Download), ("分享", "Share", old.Share),
            ("创建", "Create", old.Create), ("重命名", "Rename", old.Rename),
            ("修改", "Modify", old.Modify), ("删除", "Delete", old.Delete),
            ("执行命令", "Execute", old.Execute),
        };
        var boxes = new List<CheckBox>();
        for (int index = 0; index < flags.Length; index++)
        {
            var (label, id, on) = flags[index];
            var box = new CheckBox { Content = label, IsChecked = on };
            AutomationProperties.SetAutomationId(box, "ServerUserPermission" + id);
            if (index % 2 == 0) permissions.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            Grid.SetColumn(box, index % 2);
            Grid.SetRow(box, index / 2);
            permissions.Children.Add(box);
            boxes.Add(box);
        }
        boxes[0].Unchecked += (_, _) => { boxes[1].IsChecked = false; boxes[1].IsEnabled = false; };
        boxes[0].Checked += (_, _) => boxes[1].IsEnabled = true;
        boxes[1].IsEnabled = old.Download;
        var beforeCommands = string.Join('\n', user.Commands ?? []);
        var commands = new TextBox
        {
            Text = beforeCommands, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 68, MaxHeight = 144,
            MaxLength = 32768, Style = style,
        };
        AutomationProperties.SetAutomationId(commands, "ServerUserAllowedCommands");
        AutomationProperties.SetName(commands, "允许命令，每行一条");
        var rules = new ServerRulesEditor(user.Rules ?? [], "UserRules");
        var password = new PasswordBox { MaxLength = 1024, HorizontalAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(password, "ConfirmServerUserPolicyPassword");
        AutomationProperties.SetName(password, "当前管理员密码");
        var content = new StackPanel { Spacing = 8, MinWidth = 344, MaxWidth = 480 };
        content.Children.Add(new TextBlock { Text = "服务器目录范围", Style = caption });
        content.Children.Add(scope);
        content.Children.Add(new TextBlock { Text = "文件权限", FontWeight = FontWeights.SemiBold });
        content.Children.Add(permissions);
        content.Children.Add(new TextBlock { Text = "允许命令", FontWeight = FontWeights.SemiBold });
        content.Children.Add(new TextBlock
        {
            Text = _enableExec == true
                ? "每行一条。账户同时需要执行权限。"
                : "每行一条。服务器未启用或未报告执行能力，保存白名单不会开启执行。",
            Style = caption, TextWrapping = TextWrapping.Wrap,
        });
        content.Children.Add(commands);
        content.Children.Add(new TextBlock { Text = "账户路径规则", FontWeight = FontWeights.SemiBold });
        content.Children.Add(rules);
        if (_adminRequiresPassword)
        {
            content.Children.Add(new TextBlock { Text = "当前管理员密码", Style = caption });
            content.Children.Add(password);
        }
        var validation = new TextBlock { TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetLiveSetting(validation, AutomationLiveSetting.Polite);
        content.Children.Add(validation);
        var dialog = new ContentDialog
        {
            Title = $"编辑 {user.Username} 的权限与规则",
            Content = new ScrollViewer
            {
                Content = content, MaxHeight = DialogOrdering.ContentMaxHeight(this, 480),
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            },
            PrimaryButtonText = "保存策略", CloseButtonText = "取消", DefaultButton = ContentDialogButton.None,
        };
        dialog.PrimaryButtonClick += (_, click) =>
        {
            if (_adminRequiresPassword && password.Password.Length == 0)
            {
                validation.Text = "请输入当前管理员密码。";
                password.Focus(FocusState.Programmatic);
                click.Cancel = true;
            }
            else if (rules.ReadDraft().Any(rule => rule.Match.Length == 0))
            {
                validation.Text = "请填写所有路径规则，或移除空白规则。";
                click.Cancel = true;
            }
        };
        if (await ShowDialogAsync(dialog) != ContentDialogResult.Primary || !_isAdmin || _token.IsCancellationRequested) return;
        var requested = new PermissionSet(boxes[0].IsChecked == true, boxes[2].IsChecked == true, boxes[3].IsChecked == true,
            boxes[4].IsChecked == true, boxes[5].IsChecked == true, boxes[6].IsChecked == true, old.Admin, boxes[1].IsChecked == true);
        string updatedCommands = commands.Text.ReplaceLineEndings("\n");
        var newRules = rules.ReadDraft();
        var update = new AdminUserUpdate(user.Id, null, null, null, requested == old ? null : requested,
            SharePermission: requested.Share == old.Share ? null : requested.Share,
            Scope: scope.Text == user.Scope ? null : scope.Text,
            Commands: updatedCommands == beforeCommands ? null : updatedCommands.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries),
            Rules: newRules.SequenceEqual(user.Rules ?? []) ? null : newRules, ExpectedPolicy: user);
        string adminPassword = password.Password;
        password.Password = "";
        if (update.Permissions is null && update.Scope is null && update.Commands is null && update.Rules is null)
        {
            AddAdminStatus("没有需要保存的更改。", InfoBarSeverity.Informational);
            return;
        }
        SetBusy(true);
        try
        {
            await _adminUsers.UpdateAsync(_directory?.Account ?? default, update, adminPassword, _token);
            AddAdminStatus("用户权限与规则已保存。", InfoBarSeverity.Success);
            await RefreshAdminAsync();
        }
        catch (OperationCanceledException) { }
        catch (FilewayException error) when (error.Error.Code is ErrorCode.OutcomeUnknown or ErrorCode.Conflict || error.Error.HttpStatus is >= 500)
        {
            await RefreshAdminAsync();
            AddAdminStatus("保存结果需要核对。请查看服务器上的当前策略；应用没有重复提交。", InfoBarSeverity.Warning);
        }
        catch (Exception error)
        {
            AddAdminStatus(error is FilewayException known ? known.Error.Message : "用户策略未能保存。", InfoBarSeverity.Error);
        }
        finally { SetBusy(false); }
    }
}
