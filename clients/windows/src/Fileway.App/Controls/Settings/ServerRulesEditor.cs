using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls.Settings;

/// <summary>Ordered path rule draft shared by global and per-user settings.</summary>
internal sealed class ServerRulesEditor : UserControl
{
    private readonly List<ServerPathRule> _rules;
    private readonly ListView _list = new() { MaxHeight = 150, SelectionMode = ListViewSelectionMode.Single };
    private readonly CheckBox _allow = new() { Content = "允许访问" };
    private readonly CheckBox _regex = new() { Content = "Go 正则" };
    private readonly TextBox _match = new() { MaxLength = 4096, HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly StackPanel _editPanel = new() { Spacing = 8 };
    private readonly Button _up;
    private readonly Button _down;
    private readonly Button _remove;
    private bool _rendering;
    private int _selected = -1;

    internal ServerRulesEditor(IEnumerable<ServerPathRule> rules, string automationPrefix)
    {
        _rules = rules.ToList();
        AutomationProperties.SetName(_list, "路径规则");
        AutomationProperties.SetAutomationId(_list, automationPrefix + "List");
        AutomationProperties.SetAutomationId(_allow, automationPrefix + "Allow");
        AutomationProperties.SetAutomationId(_regex, automationPrefix + "Regex");
        AutomationProperties.SetAutomationId(_match, automationPrefix + "Match");
        _match.Style = (Style)Application.Current.Resources["FwTextBoxStyle"];
        _list.SelectionChanged += (_, _) =>
        {
            if (_rendering) return;
            int selected = _list.SelectedIndex;
            CommitDraft();
            Render(selected);
        };

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        actions.Children.Add(ActionButton("新增规则", automationPrefix + "Add", () =>
        {
            CommitDraft();
            _rules.Add(new(false, false, ""));
            Render(_rules.Count - 1);
            _match.Focus(FocusState.Programmatic);
        }));
        _up = ActionButton("上移", automationPrefix + "MoveUp", () => Move(-1));
        _down = ActionButton("下移", automationPrefix + "MoveDown", () => Move(1));
        _remove = ActionButton("移除", automationPrefix + "Remove", () =>
        {
            if (_selected < 0) return;
            int index = _selected;
            _rules.RemoveAt(index);
            Render(Math.Min(index, _rules.Count - 1));
        });
        actions.Children.Add(_up);
        actions.Children.Add(_down);
        actions.Children.Add(_remove);
        var flags = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        flags.Children.Add(_allow);
        flags.Children.Add(_regex);
        _editPanel.Children.Add(flags);
        _editPanel.Children.Add(new TextBlock
        {
            Text = "路径或正则表达式",
            Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
        });
        _editPanel.Children.Add(_match);
        AutomationProperties.SetName(_match, "路径或正则表达式");
        var root = new StackPanel { Spacing = 8 };
        root.Children.Add(new TextBlock
        {
            Text = "按列表顺序检查，最后一条匹配生效。普通路径以 / 开头。",
            Style = (Style)Application.Current.Resources["FwCaptionTextStyle"],
            TextWrapping = TextWrapping.Wrap,
        });
        root.Children.Add(_list);
        root.Children.Add(actions);
        root.Children.Add(_editPanel);
        Content = root;
        Render(-1);
    }

    internal ServerPathRule[] ReadDraft()
    {
        CommitDraft();
        return _rules.ToArray();
    }

    private static Button ActionButton(string label, string id, Action action)
    {
        var button = new Button { Content = label, Style = (Style)Application.Current.Resources["FwSecondaryButtonStyle"] };
        AutomationProperties.SetAutomationId(button, id);
        button.Click += (_, _) => action();
        return button;
    }

    private void CommitDraft()
    {
        if (_selected >= 0)
            _rules[_selected] = new(_allow.IsChecked == true, _regex.IsChecked == true, _match.Text);
    }

    private void Move(int step)
    {
        int target = _selected + step;
        if (_selected < 0 || target < 0 || target >= _rules.Count) return;
        CommitDraft();
        (_rules[_selected], _rules[target]) = (_rules[target], _rules[_selected]);
        Render(target);
    }

    private void Render(int index)
    {
        _rendering = true;
        _selected = index;
        _list.ItemsSource = _rules.Select((rule, i) => i == index
            ? $"{i + 1}. 正在编辑"
            : $"{i + 1}. {(rule.Allow ? "允许" : "拒绝")} · {(rule.RegularExpression ? "正则" : "路径")} · {(rule.Match.Length == 0 ? "待输入" : rule.Match)}").ToArray();
        _list.SelectedIndex = index;
        _list.Visibility = _rules.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        _editPanel.Visibility = index < 0 ? Visibility.Collapsed : Visibility.Visible;
        if (index >= 0)
        {
            var rule = _rules[index];
            _allow.IsChecked = rule.Allow;
            _regex.IsChecked = rule.RegularExpression;
            _match.Text = rule.Match;
        }
        _up.IsEnabled = index > 0;
        _down.IsEnabled = index >= 0 && index + 1 < _rules.Count;
        _remove.IsEnabled = index >= 0;
        _rendering = false;
    }
}
