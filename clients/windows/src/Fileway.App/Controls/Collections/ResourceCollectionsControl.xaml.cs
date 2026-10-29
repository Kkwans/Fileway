using System.Collections.ObjectModel;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Fileway.App.Controls.Collections;

public sealed partial class ResourceCollectionsControl : UserControl, IDisposable
{
    private static readonly System.Text.UTF8Encoding StrictWireUtf8 = new(false, true);
    private static readonly (string Name, string Value)[] Colors =
    [
        ("红", "#E5484D"), ("玫瑰", "#D95876"), ("珊瑚", "#F06A5B"), ("橙", "#F28C28"),
        ("金", "#DDAA1D"), ("黄", "#D6BE21"), ("草绿", "#86B83E"), ("绿", "#35A867"),
        ("青绿", "#2AA889"), ("青", "#28AFC0"), ("天蓝", "#3A9BD9"), ("蓝", "#3F72D8"),
        ("靛蓝", "#5B62D9"), ("紫", "#7656C9"), ("兰紫", "#9B4DB5"), ("洋红", "#C34F90"), ("灰", "#758195"),
    ];
    private readonly ObservableCollection<CollectionRow> _records = [];
    private readonly ObservableCollection<CollectionRow> _resources = [];
    private CancellationTokenSource? _contextCancellation;
    private IResourceCollectionsService? _service;
    private IFileRepository? _files;
    private ConnectionSession? _session;
    private ResourceCollectionsSnapshot? _snapshot;
    private RemoteResourceRef? _currentResource;
    private ContentDialog? _dialog;
    private long _generation;
    private long _openGeneration;
    private long _resourceGeneration;
    private bool _busy;
    private bool _rendering;
    private bool _requiresRefresh;
    private bool _unknownWrite;
    private readonly Dictionary<AccountKey, CollectionAccountState> _accountWriteState = [];
    private readonly InfoBar _receiptWarningBar = new() { Title = "本机操作记录提示", Severity = InfoBarSeverity.Warning, IsClosable = false };
    private readonly Button _reconcileButton = new() { Content = "核实上次结果", Visibility = Visibility.Collapsed };
    private bool _disposed;
    private string? _browseTagId;
    public event EventHandler? CloseRequested;
    public event EventHandler<ResourceCollectionsSnapshot>? SnapshotChanged;
    public event EventHandler<CollectionStatus>? StatusChanged;
    public event EventHandler? CommandsChanged;
    public event EventHandler<CollectionReceiptWarning>? ReceiptWarningChanged;
    public bool HasOpenDialog => _dialog is not null;
    public bool CanManageCurrentResource => CanWrite && _currentResource is not null;
    public bool CanNavigate => !_busy && Capture() is not null;
    public bool IsCurrentResourceFavorite => _currentResource is { } resource && _snapshot?.Favorites.Any(item => SameResource(item.Reference.Resource, resource)) == true;
    public Func<RemoteFile, Task>? OpenResourceAsync { get; set; }

    public ResourceCollectionsControl()
    {
        InitializeComponent();
        if (Content is Grid host) host.Children.Add(_receiptWarningBar);
        Fileway.App.Controls.FloatingNotice.Attach(Status);
        Fileway.App.Controls.FloatingNotice.Attach(_receiptWarningBar);
        Status.ActionButton = _reconcileButton;
        _reconcileButton.Click += async (_, _) => await ReconcileUnknownAsync();
        Records.ItemsSource = _records; TagResources.ItemsSource = _resources;
        UpdateCommands();
    }

    public void SetContext(IResourceCollectionsService? service, IFileRepository? files, ConnectionSession? session, RemoteResourceRef? currentResource)
    {
        if (_disposed) return;
        var changed = !ReferenceEquals(_service, service) || _session?.Id != session?.Id || _session?.Account != session?.Account;
        _service = service; _files = files;
        if (changed)
        {
            var wasRendering = _rendering;
            _rendering = true;
            try
            {
                ++_generation; ++_openGeneration; _contextCancellation?.Cancel(); _contextCancellation?.Dispose();
                _contextCancellation = session is null ? null : new CancellationTokenSource();
                _dialog?.Hide(); _session = session; _snapshot = null; _busy = false;
                _records.Clear(); _resources.Clear();
                _browseTagId = null;
                GroupBox.ItemsSource = null; Status.IsOpen = false;
            }
            finally { _rendering = wasRendering; }
        }
        else _session = session;
        RestoreAccountWriteState();
        var nextResource = currentResource?.Account == session?.Account ? currentResource : null;
        if (_currentResource != nextResource) { ++_resourceGeneration; ++_openGeneration; }
        _currentResource = nextResource;
        if (_browseTagId is null) CurrentResourceText.Text = _currentResource is { } resource ? $"选中资源：{resource.DisplayPath}" : "从文件列表选择一个项目，可在这里收藏或设置标签。";
        EmptyText.Text = session is null ? "请先连接服务器。" : "暂无记录。";
        UpdateCommands();
    }

    private Scope? Capture() => !_disposed && _service is not null && _session is { } session && _contextCancellation is { } cancellation
        ? new Scope(session.Id, session.Account, _generation, cancellation.Token) : null;
    private bool Current(Scope scope) => !_disposed && !scope.Token.IsCancellationRequested && scope.Generation == _generation && _session?.Id == scope.SessionId && _session.Account == scope.Account;
    private bool CanWrite => !_busy && !_requiresRefresh && !_unknownWrite && _snapshot is not null && Capture() is not null;
    private bool TagMode => ModeBox.SelectedIndex == 1;
    private FavoriteGroup? SelectedGroup => (GroupBox.SelectedItem as GroupChoice)?.Group;
    private CollectionRow? Selected => Records.SelectedItem as CollectionRow;

    public async Task RefreshAsync()
    {
        if (_busy || Capture() is not { } scope) return;
        _busy = true; UpdateCommands();
        try
        {
            await ReadSnapshotAsync(scope);
            if (!Current(scope)) return;
            AccountState(scope.Account).RequiresRefresh = false; RestoreAccountWriteState();
            ShowStatus(_unknownWrite ? "已刷新服务器记录，上次写入仍未确认。普通刷新不会解除写入保护，请核实上次结果，勿重复提交。" : "已刷新收藏与标签。", _unknownWrite ? InfoBarSeverity.Warning : InfoBarSeverity.Success);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception) { if (Current(scope)) ShowStatus($"刷新失败：{MainPage.ErrorMessage(exception)}", InfoBarSeverity.Error); }
        finally { if (Current(scope)) { _busy = false; UpdateCommands(); } }
    }

    private async Task ReadSnapshotAsync(Scope scope)
    {
        var snapshot = await _service!.GetCollectionsAsync(scope.Account, scope.Token);
        if (!Current(scope)) return;
        if (snapshot.Account != scope.Account || snapshot.Favorites.Any(item => item.Account != scope.Account || item.Reference.Resource is { } resource && resource.Account != scope.Account) ||
            snapshot.FavoriteGroups.Any(item => item.Account != scope.Account) || snapshot.Tags.Any(item => item.Account != scope.Account || item.Resources.Any(reference => reference.Resource is { } resource && resource.Account != scope.Account)))
            throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "收藏或标签的账号身份不匹配。"));
        _snapshot = snapshot;
        Render();
        SnapshotChanged?.Invoke(this, snapshot);
    }

    private void Render()
    {
        if (_rendering || _disposed) return;
        var selectedId = Selected?.Id;
        var groupId = (GroupBox.SelectedItem as GroupChoice)?.Id;
        _rendering = true;
        try
        {
            var choices = new List<GroupChoice> { new("*", "全部收藏"), new("", "未分组"), new("?", "分组不可用") };
            choices.AddRange((_snapshot?.FavoriteGroups ?? []).OrderBy(item => item.Order).Select(item => new GroupChoice(item.Id, item.Name, item)));
            GroupBox.ItemsSource = choices; GroupBox.SelectedItem = choices.FirstOrDefault(item => item.Id == groupId) ?? choices[0];
            _records.Clear();
            if (_snapshot is { } snapshot)
            {
                if (TagMode)
                    foreach (var tag in snapshot.Tags) _records.Add(new CollectionRow(tag.Id, tag.Name, $"{tag.Resources.Count} 个关联", "", Tag: tag));
                else
                {
                    var filter = (GroupBox.SelectedItem as GroupChoice)?.Id ?? "*";
                    foreach (var favorite in snapshot.Favorites.OrderBy(item => item.Order))
                    {
                        var group = snapshot.FavoriteGroups.FirstOrDefault(item => item.Id == favorite.GroupId);
                        var missing = !string.IsNullOrEmpty(favorite.GroupId) && group is null;
                        if (filter != "*" && !(filter == "?" ? missing : filter == "" ? string.IsNullOrEmpty(favorite.GroupId) : favorite.GroupId == filter)) continue;
                        _records.Add(new CollectionRow(favorite.Id, favorite.Name, favorite.Reference.DisplayPath,
                            $"{(missing ? "分组不可用" : group?.Name ?? "未分组")} · {IdentityState(favorite.Reference)}", favorite.Reference, favorite));
                    }
                }
            }
            Records.SelectedItem = _records.FirstOrDefault(item => item.Id == (_browseTagId ?? selectedId));
            GroupBox.Visibility = TagMode ? Visibility.Collapsed : Visibility.Visible;
            TagResourcesArea.Visibility = TagMode ? Visibility.Visible : Visibility.Collapsed;
            TagResourcesRow.Height = new GridLength(TagMode ? 1 : 0, TagMode ? GridUnitType.Star : GridUnitType.Pixel);
            EmptyHost.Visibility = _records.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            EmptyText.Text = TagMode ? "暂无标签。新建标签后，可绑定选中资源。" : "此分组暂无收藏。可从文件列表选择资源后添加。";
            RenderTagResources();
            ApplyPurpose();
        }
        finally { _rendering = false; UpdateCommands(); }
    }
    public void EnterManagementView()
    {
        _browseTagId = null; CollectionHeading.Text = "收藏与标签";
        CurrentResourceText.Text = _currentResource is { } resource ? $"选中资源：{resource.DisplayPath}" : "从文件列表选择一个项目，可在这里收藏或设置标签。";
        Render();
    }
    public async Task ShowTagResourcesAsync(string tagId)
    {
        if (Capture() is not { } scope) return;
        _browseTagId = tagId; ModeBox.SelectedIndex = 1; Render();
        await RefreshAsync();
        if (Current(scope)) ApplyPurpose();
    }
    private void ApplyPurpose()
    {
        var browse = _browseTagId is not null;
        OpenTagResourceButton.Visibility = UnlinkButton.Visibility = TagMode ? Visibility.Visible : Visibility.Collapsed;
        ManagementSelection.Visibility = Records.Visibility = browse ? Visibility.Collapsed : Visibility.Visible;
        RecordsRow.Height = new GridLength(browse ? 0 : 1, browse ? GridUnitType.Pixel : GridUnitType.Star);
        AddFavoriteButton.Visibility = ResourceTagsButton.Visibility = CreateGroupButton.Visibility = CreateTagButton.Visibility = browse ? Visibility.Collapsed : Visibility.Visible;
        if (!browse) return;
        EmptyHost.Visibility = Visibility.Collapsed; TagResourcesArea.Visibility = Visibility.Visible; TagResourcesRow.Height = new GridLength(1, GridUnitType.Star);
        var tag = Selected?.Tag;
        CollectionHeading.Text = tag is null ? "标签不可用" : tag.Name;
        CurrentResourceText.Text = tag is null ? "此标签已删除或当前账号不可见，请刷新或返回文件。" : $"{tag.Resources.Count} 个项目 · 双击或按 Enter 打开";
    }

    private void RenderTagResources()
    {
        _resources.Clear();
        TagEmptyHost.Visibility = Visibility.Visible;
        TagEmptyText.Text = "选择标签以查看关联资源。";
        if (Selected?.Tag is not { } tag) return;
        var index = 0;
        foreach (var reference in tag.Resources)
            _resources.Add(new CollectionRow($"{tag.Id}:{index++}", reference.DisplayPath.TrimEnd('/').Split('/').LastOrDefault() is { Length: > 0 } name ? name : "/", reference.DisplayPath, IdentityState(reference), reference, Tag: tag));
        TagEmptyText.Text = "此标签暂无关联资源。可用“设置资源标签”绑定当前选中资源。";
        TagEmptyHost.Visibility = _resources.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }
    private static string IdentityState(CollectionResource reference) => reference.Resource is null ? "历史记录，资源身份暂不可用" : "";

    private void UpdateCommands()
    {
        if (Records is null) return;
        var connected = Capture() is not null;
        RefreshButton.IsEnabled = connected && !_busy;
        CreateGroupButton.IsEnabled = CreateTagButton.IsEnabled = CanWrite;
        AddFavoriteButton.IsEnabled = ResourceTagsButton.IsEnabled = CanWrite && _currentResource is not null;
        EditButton.IsEnabled = RemoveButton.IsEnabled = CanWrite && Selected is not null;
        ContextEdit.IsEnabled = ContextRemove.IsEnabled = EditButton.IsEnabled;
        EditGroupButton.IsEnabled = RemoveGroupButton.IsEnabled = CanWrite && !TagMode && SelectedGroup is not null;
        OpenButton.IsEnabled = !_busy && Selected?.Reference?.Resource is not null && connected;
        ContextOpen.IsEnabled = OpenButton.IsEnabled;
        OpenTagResourceButton.IsEnabled = !_busy && (TagResources.SelectedItem as CollectionRow)?.Reference?.Resource is not null && connected;
        UnlinkButton.IsEnabled = CanWrite && (TagResources.SelectedItem as CollectionRow)?.Reference?.Resource is not null;
        ModeBox.IsEnabled = GroupBox.IsEnabled = !_busy;
        _reconcileButton.Visibility = _unknownWrite ? Visibility.Visible : Visibility.Collapsed;
        _reconcileButton.IsEnabled = !_busy && _session is { } owner && AccountState(owner.Account).UnknownOperations.Count > 0 && (_files is IOperationHistoryService || _service is IOperationHistoryService);
        CommandsChanged?.Invoke(this, EventArgs.Empty);
    }

    private async Task WriteAsync(Scope scope, Func<CancellationToken, Task<CollectionWriteResult>> write, string confirmed)
    {
        if (!Current(scope) || !CanWrite) return;
        _busy = true; UpdateCommands();
        try
        {
            var result = await write(scope.Token);
            var state = AccountState(scope.Account);
            ConsumeReceipt(scope.Account, result.Receipt);
            if (result.Outcome == OperationOutcome.OutcomeUnknown) TrackUnknown(scope.Account, result.Receipt);
            if (result.Outcome != OperationOutcome.Confirmed) state.RequiresRefresh = true;
            RestoreAccountWriteState();
            if (!Current(scope)) return;
            if (result.Outcome != OperationOutcome.Confirmed)
            {
                ShowStatus((result.Outcome == OperationOutcome.Partial ? "部分操作已保存，请刷新查看逐项状态。" : "操作结果未知。请使用‘核实上次结果’，普通刷新不会解除写入保护。") + "勿直接重复提交。" + (result.Error is { } error ? $" {error.Message}" : ""), InfoBarSeverity.Warning);
                return;
            }
            ShowStatus(confirmed, InfoBarSeverity.Success);
            try
            {
                await ReadSnapshotAsync(scope);
                if (Current(scope)) { state.RequiresRefresh = false; RestoreAccountWriteState(); }
            }
            catch (OperationCanceledException) { }
            catch (Exception exception)
            {
                state.RequiresRefresh = true;
                if (Current(scope)) { RestoreAccountWriteState(); ShowStatus($"{confirmed} 列表刷新失败，请刷新查看；不要重复提交。{MainPage.ErrorMessage(exception)}", InfoBarSeverity.Warning); }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            if (exception is FilewayException { Error.Code: ErrorCode.OutcomeUnknown }) TrackUnknown(scope.Account, null);
            if (Current(scope))
            {
                RestoreAccountWriteState();
                ShowStatus(_unknownWrite ? "操作结果未知，写入已暂停。请核实服务器记录，勿重复提交；本机记录不可用时需要人工核对。" : MainPage.ErrorMessage(exception), InfoBarSeverity.Error);
            }
        }
        finally { if (Current(scope)) { _busy = false; UpdateCommands(); } }
    }

    private CollectionAccountState AccountState(AccountKey account)
    {
        if (!_accountWriteState.TryGetValue(account, out var state)) _accountWriteState[account] = state = new CollectionAccountState();
        return state;
    }
    private void RestoreAccountWriteState()
    {
        var state = _session is { } session ? AccountState(session.Account) : null;
        _requiresRefresh = state?.RequiresRefresh == true;
        _unknownWrite = state is not null && (state.UntrackedUnknown || state.UnknownOperations.Count > 0);
        _receiptWarningBar.Message = state is null ? "" : "此提示不改变服务器已返回的操作结果，请勿因此重复提交。\n" + string.Join("\n", state.Warnings);
        _receiptWarningBar.IsOpen = state?.Warnings.Count > 0;
    }
    private void TrackUnknown(AccountKey account, OperationReceipt? receipt)
    {
        var state = AccountState(account);
        if (receipt is null) state.UntrackedUnknown = true;
        else state.UnknownOperations.Add(new OperationRef(account, receipt.OperationId));
        RestoreAccountWriteState(); UpdateCommands();
    }
    private void ConsumeReceipt(AccountKey account, OperationReceipt? receipt)
    {
        var message = receipt?.PersistenceWarning?.Message ?? (receipt is { AckPersisted: false } ? "本机操作记录未能完整保存。" : null);
        if (message is null) return;
        AccountState(account).Warnings.Add(message);
        ReceiptWarningChanged?.Invoke(this, new CollectionReceiptWarning(account, message));
        RestoreAccountWriteState();
    }
    private async Task ReconcileUnknownAsync()
    {
        if (_busy || Capture() is not { } scope || (_files as IOperationHistoryService ?? _service as IOperationHistoryService) is not { } operations) return;
        var state = AccountState(scope.Account);
        if (state.UnknownOperations.Count == 0) return;
        _busy = true; UpdateCommands();
        try
        {
            foreach (var reference in state.UnknownOperations.ToArray())
            {
                var result = await operations.ReconcileAsync(reference, scope.Token);
                if (result.Error is null && result.Resolution is (OperationResolution.Confirmed or OperationResolution.NotSent or OperationResolution.ObservedDesiredState or OperationResolution.ObservedTaskState)) state.UnknownOperations.Remove(reference);
                if (!Current(scope)) return;
            }
            await ReadSnapshotAsync(scope);
            if (!Current(scope)) return;
            state.RequiresRefresh = false; RestoreAccountWriteState();
            ShowStatus(_unknownWrite ? "仍无法确认上次操作，写入保护保留。请人工核对服务器，不要重复提交。" : "上次操作结果已核实，可以继续管理收藏与标签。", _unknownWrite ? InfoBarSeverity.Warning : InfoBarSeverity.Informational);
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (Current(scope)) ShowStatus("核实未完成，写入保护保留。" + MainPage.ErrorMessage(error), InfoBarSeverity.Warning); }
        finally { if (Current(scope)) { RestoreAccountWriteState(); _busy = false; UpdateCommands(); } }
    }

    private async Task<ContentDialogResult> ShowDialogAsync(Scope scope, ContentDialog dialog)
    {
        if (!Current(scope) || _dialog is not null) return ContentDialogResult.None;
        dialog.XamlRoot = XamlRoot; dialog.RequestedTheme = RequestedTheme;
        _dialog = dialog;
        try { return await dialog.ShowAsync(); }
        catch (OperationCanceledException) { return ContentDialogResult.None; }
        catch (Exception) { if (Current(scope)) ShowStatus("请关闭其他对话框后重试。", InfoBarSeverity.Warning); return ContentDialogResult.None; }
        finally { if (ReferenceEquals(_dialog, dialog)) _dialog = null; }
    }

    private async Task<(string Name, string? GroupId, string? Color)?> EditDialogAsync(Scope scope, string title, string initialName, bool chooseGroup = false, string? groupId = null, bool chooseColor = false, string? color = null)
    {
        var name = new TextBox { Header = "名称", Text = initialName };
        var panel = new StackPanel { Spacing = 16, MinWidth = 260 }; panel.Children.Add(name);
        ComboBox? groups = null; ComboBox? colors = null;
        if (chooseGroup)
        {
            var choices = new List<GroupChoice> { new("", "未分组") };
            choices.AddRange((_snapshot?.FavoriteGroups ?? []).Select(item => new GroupChoice(item.Id, item.Name, item)));
            if (!string.IsNullOrEmpty(groupId) && choices.All(item => item.Id != groupId)) panel.Children.Add(new TextBlock { Text = "原分组已不可用，保存时请选择现有分组或未分组。", TextWrapping = TextWrapping.Wrap });
            groups = new Fileway.App.Controls.ChoiceBox { Header = "收藏分组", ItemsSource = choices, DisplayMemberPath = "Label", HorizontalAlignment = HorizontalAlignment.Stretch };
            groups.SelectedItem = choices.FirstOrDefault(item => item.Id == groupId) ?? choices[0]; panel.Children.Add(groups);
        }
        if (chooseColor)
        {
            var choices = Colors.Select(item => new ColorChoice(item.Name, item.Value)).ToList();
            if (!string.IsNullOrEmpty(color) && choices.All(item => !StringComparer.OrdinalIgnoreCase.Equals(item.Value, color))) choices.Insert(0, new("保留原颜色", color));
            colors = new Fileway.App.Controls.ChoiceBox { Header = "标签颜色", ItemsSource = choices, HorizontalAlignment = HorizontalAlignment.Stretch };
            colors.SelectedItem = choices.FirstOrDefault(item => StringComparer.OrdinalIgnoreCase.Equals(item.Value, color)) ?? choices[0];
            colors.ItemTemplate = (DataTemplate)Resources["TagColorTemplate"]; panel.Children.Add(colors);
        }
        var dialog = new ContentDialog { Title = title, Content = panel, PrimaryButtonText = "保存", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary, IsPrimaryButtonEnabled = !string.IsNullOrWhiteSpace(initialName) };
        name.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = !string.IsNullOrWhiteSpace(name.Text);
        if (await ShowDialogAsync(scope, dialog) != ContentDialogResult.Primary || !Current(scope)) return null;
        return (name.Text.Trim(), groups is null ? groupId : (groups.SelectedItem as GroupChoice)?.Id, colors is null ? color : (colors.SelectedItem as ColorChoice)?.Value);
    }
    private async Task<bool> ConfirmAsync(Scope scope, string title, string message) => await ShowDialogAsync(scope, new ContentDialog { Title = title, Content = message, PrimaryButtonText = "确认", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close }) == ContentDialogResult.Primary && Current(scope);

    private async void AddFavorite_Click(object sender, RoutedEventArgs args) => await AddCurrentFavoriteAsync();
    public async Task ToggleCurrentFavoriteAsync()
    {
        if (!CanWrite || Capture() is not { } scope || _currentResource is not { } resource) return;
        var favorite = _snapshot?.Favorites.FirstOrDefault(item => SameResource(item.Reference.Resource, resource));
        if (favorite is null) await AddCurrentFavoriteAsync();
        else await WriteAsync(scope, token => _service!.RemoveFavoriteAsync(scope.Account, favorite.Id, token), "收藏已移除。");
    }
    public async Task AddCurrentFavoriteAsync()
    {
        if (!CanWrite || Capture() is not { } scope || _currentResource is not { } resource) return;
        var resourceGeneration = _resourceGeneration;
        var input = await EditDialogAsync(scope, "收藏资源", resource.DisplayName, chooseGroup: true);
        if (input is { } value && resourceGeneration == _resourceGeneration) await WriteAsync(scope, token => _service!.AddFavoriteAsync(resource, value.Name, value.GroupId, token), "收藏已保存。");
    }
    private async void CreateGroup_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite || Capture() is not { } scope) return;
        if (await EditDialogAsync(scope, "新建收藏分组", "") is { } value) await WriteAsync(scope, token => _service!.CreateFavoriteGroupAsync(scope.Account, value.Name, null, token), "分组已创建。");
    }
    private async void CreateTag_Click(object sender, RoutedEventArgs args) => await CreateTagAsync();
    private async Task CreateTagAsync()
    {
        if (!CanWrite || Capture() is not { } scope) return;
        if (await EditDialogAsync(scope, "新建标签", "", chooseColor: true) is { } value) await WriteAsync(scope, token => _service!.CreateTagAsync(scope.Account, value.Name, value.Color!, token), "标签已创建。");
    }
    private async void Edit_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite || Capture() is not { } scope || Selected is not { } selected) return;
        if (selected.Favorite is { } favorite && await EditDialogAsync(scope, "编辑收藏", favorite.Name, true, favorite.GroupId) is { } f)
            await WriteAsync(scope, token => _service!.UpdateFavoriteAsync(scope.Account, favorite.Id, f.Name, f.GroupId, token), "收藏已保存。");
        else if (selected.Tag is { } tag && await EditDialogAsync(scope, "编辑标签", tag.Name, chooseColor: true, color: tag.Color) is { } t)
            await WriteAsync(scope, token => _service!.UpdateTagAsync(scope.Account, tag.Id, t.Name, t.Color!, token), "标签已保存。");
    }
    private async void Remove_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite || Capture() is not { } scope || Selected is not { } selected) return;
        if (!await ConfirmAsync(scope, selected.Tag is null ? "移除收藏？" : "删除标签？", selected.Tag is null ? "只移除此收藏，服务器上的文件不变。" : "将删除此标签及其全部关联，服务器上的文件不变。")) return;
        await WriteAsync(scope, token => selected.Tag is null ? _service!.RemoveFavoriteAsync(scope.Account, selected.Id, token) : _service!.RemoveTagAsync(scope.Account, selected.Id, token), selected.Tag is null ? "收藏已移除。" : "标签已删除。");
    }
    private async void EditGroup_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite || Capture() is not { } scope || SelectedGroup is not { } group) return;
        if (await EditDialogAsync(scope, "编辑分组", group.Name, color: group.Color) is { } value) await WriteAsync(scope, token => _service!.UpdateFavoriteGroupAsync(scope.Account, group.Id, value.Name, group.Color, token), "分组已保存。");
    }
    private async void RemoveGroup_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite || Capture() is not { } scope || SelectedGroup is not { } group || !await ConfirmAsync(scope, "删除分组？", "分组内的收藏将移到未分组，收藏与文件都会保留。")) return;
        await WriteAsync(scope, token => _service!.RemoveFavoriteGroupAsync(scope.Account, group.Id, token), "分组已删除，收藏保留在未分组。");
    }
    private async void ResourceTags_Click(object sender, RoutedEventArgs args) => await ManageCurrentResourceTagsAsync();
    public async Task ManageCurrentResourceTagsAsync()
    {
        if (!CanWrite || Capture() is not { } scope || _currentResource is not { } resource || _snapshot is not { } snapshot) return;
        var resourceGeneration = _resourceGeneration;
        var rows = new StackPanel { Spacing = 8, MinWidth = 260 };
        var localStatus = new InfoBar { IsOpen = false, IsClosable = true };
        Fileway.App.Controls.FloatingNotice.Attach(localStatus);
        var panel = new StackPanel { Spacing = 12 }; panel.Children.Add(new TextBlock { Text = resource.DisplayName, TextWrapping = TextWrapping.Wrap }); panel.Children.Add(localStatus);
        panel.Children.Add(new ScrollViewer { Content = rows, MaxHeight = 360, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        var create = new Button { Content = "新建标签", HorizontalAlignment = HorizontalAlignment.Left }; panel.Children.Add(create);
        var dialog = new ContentDialog { Title = "标记资源", Content = panel, CloseButtonText = "关闭" };
        var createRequested = false;
        create.Click += (_, _) => { createRequested = true; dialog.Hide(); };
        void Populate(ResourceCollectionsSnapshot state)
        {
            rows.Children.Clear(); create.IsEnabled = CanWrite;
            if (state.Tags.Count == 0) rows.Children.Add(new TextBlock { Text = "暂无标签。可以新建标签后标记此资源。", TextWrapping = TextWrapping.Wrap });
            foreach (var tag in state.Tags)
            {
                var linked = tag.Resources.Any(item => SameResource(item.Resource, resource));
                var content = new Grid { ColumnSpacing = 10 };
                content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); content.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var dot = new Microsoft.UI.Xaml.Shapes.Ellipse { Width = 12, Height = 12, Fill = new ColorChoice("", tag.Color).Brush, StrokeThickness = 1 };
                dot.SetBinding(Microsoft.UI.Xaml.Shapes.Shape.StrokeProperty, new Microsoft.UI.Xaml.Data.Binding { Source = this, Path = new PropertyPath(nameof(Foreground)) }); content.Children.Add(dot);
                var name = new TextBlock { Text = tag.Name, TextTrimming = TextTrimming.CharacterEllipsis }; Grid.SetColumn(name, 1); content.Children.Add(name);
                var assignment = new TextBlock { Text = linked ? "已标记" : "未标记" }; Grid.SetColumn(assignment, 2); content.Children.Add(assignment);
                var button = new Button { Content = content, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch, IsEnabled = CanWrite };
                Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, $"{tag.Name}，{(linked ? "已标记，单击取消关联" : "未标记，单击绑定")}");
                button.Click += async (_, _) =>
                {
                    if (!Current(scope) || resourceGeneration != _resourceGeneration || !CanWrite || !ReferenceEquals(_dialog, dialog)) return;
                    foreach (var child in rows.Children.OfType<Button>()) child.IsEnabled = false;
                    create.IsEnabled = false;
                    await WriteAsync(scope, token => linked ? _service!.RemoveTagResourceAsync(tag.Id, resource, token) : _service!.AddTagResourceAsync(tag.Id, resource, token), linked ? "标签关联已取消。" : "标签已绑定。");
                    if (!Current(scope) || !ReferenceEquals(_dialog, dialog)) return;
                    localStatus.Message = Status.Message; localStatus.Severity = Status.Severity; localStatus.IsOpen = true;
                    if (_snapshot is { } refreshed) Populate(refreshed);
                };
                rows.Children.Add(button);
            }
        }
        Populate(snapshot);
        await ShowDialogAsync(scope, dialog);
        if (!createRequested || !Current(scope) || resourceGeneration != _resourceGeneration || !CanWrite) return;
        await CreateTagAsync();
        if (Current(scope) && resourceGeneration == _resourceGeneration && CanWrite) await ManageCurrentResourceTagsAsync();
    }
    private static bool SameResource(RemoteResourceRef? left, RemoteResourceRef right) => left is not null && left.Account == right.Account &&
        TryWireBytes(left.WirePath, out var leftBytes) && TryWireBytes(right.WirePath, out var rightBytes) && leftBytes.AsSpan().SequenceEqual(rightBytes);

    // Compare the server's raw path bytes; percent spelling and hex case do not establish identity.
    // Decode each segment exactly once, keeping literal '+' and opaque non-UTF8 bytes intact.
    private static bool TryWireBytes(string wirePath, out byte[] bytes)
    {
        bytes = [];
        if (string.IsNullOrEmpty(wirePath) || wirePath[0] != '/' || wirePath.Any(character => character is '?' or '#' or '\\' || char.IsControl(character))) return false;
        if (wirePath == "/") { bytes = [(byte)'/']; return true; }
        var decoded = new List<byte>();
        foreach (var segment in wirePath[1..].Split('/'))
        {
            if (segment.Length == 0) return false;
            byte[] encoded;
            try { encoded = StrictWireUtf8.GetBytes(segment); }
            catch (System.Text.EncoderFallbackException) { return false; }
            decoded.Add((byte)'/');
            var start = decoded.Count;
            for (var index = 0; index < encoded.Length; index++)
            {
                var value = encoded[index];
                if (value == (byte)'%')
                {
                    if (index + 2 >= encoded.Length) return false;
                    var high = HexValue((char)encoded[index + 1]); var low = HexValue((char)encoded[index + 2]);
                    if (high < 0 || low < 0) return false;
                    value = (byte)((high << 4) | low); index += 2;
                }
                if (value is 0 or (byte)'/') return false;
                decoded.Add(value);
            }
            var length = decoded.Count - start;
            if (length == 1 && decoded[start] == (byte)'.' || length == 2 && decoded[start] == (byte)'.' && decoded[start + 1] == (byte)'.') return false;
        }
        bytes = decoded.ToArray(); return true;
    }
    private static int HexValue(char value) => value switch
    {
        >= '0' and <= '9' => value - '0',
        >= 'a' and <= 'f' => value - 'a' + 10,
        >= 'A' and <= 'F' => value - 'A' + 10,
        _ => -1,
    };
    private async void Unlink_Click(object sender, RoutedEventArgs args)
    {
        if (!CanWrite || Capture() is not { } scope || TagResources.SelectedItem is not CollectionRow { Reference.Resource: { } resource, Tag: { } tag }) return;
        await WriteAsync(scope, token => _service!.RemoveTagResourceAsync(tag.Id, resource, token), "标签关联已取消。");
    }

    public Task OpenReferenceAsync(CollectionResource reference) => OpenAsync(new CollectionRow("", reference.DisplayPath, "", IdentityState(reference), reference), allowHidden: true);
    private async Task OpenAsync(CollectionRow? row, bool allowHidden = false)
    {
        if (_busy || Capture() is not { } scope || row?.Reference?.Resource is not { } resource || _files is null) return;
        var openGeneration = ++_openGeneration;
        _busy = true; UpdateCommands();
        try
        {
            var file = await _files.GetAsync(resource, scope.Token);
            if (!Current(scope) || openGeneration != _openGeneration || !allowHidden && Visibility != Visibility.Visible) return;
            if (file.Resource.Account != scope.Account || !SameResource(file.Resource, resource)) throw new FilewayException(new ErrorInfo(ErrorCode.ProtocolMismatch, "服务器返回的资源身份不匹配。"));
            if (OpenResourceAsync is { } open) await open(file);
        }
        catch (OperationCanceledException) { }
        catch (Exception exception)
        {
            if (Current(scope) && openGeneration == _openGeneration && (allowHidden || Visibility == Visibility.Visible))
            {
                var message = exception is FilewayException { Error.HttpStatus: 404 } ? "文件已移走或删除。仍可移除收藏或取消可信标签关联。" : exception is FilewayException { Error.HttpStatus: 403 } or FilewayException { Error.Code: ErrorCode.PermissionDenied } ? "当前账号无权访问。仍可管理自己的收藏和标签关联。" : $"暂时无法读取资源。{MainPage.ErrorMessage(exception)}";
                var owner = row.Tag is null ? _records : _resources;
                var index = owner.IndexOf(row);
                if (index >= 0)
                {
                    var view = row.Tag is null ? Records : TagResources;
                    var selected = ReferenceEquals(view.SelectedItem, row);
                    var updated = row with { State = message }; owner[index] = updated;
                    if (selected) view.SelectedItem = updated;
                }
                ShowStatus(message, InfoBarSeverity.Warning);
            }
        }
        finally { if (Current(scope)) { _busy = false; UpdateCommands(); } }
    }
    private void ShowStatus(string message, InfoBarSeverity severity) { Status.Message = message; Status.Severity = severity; Fileway.App.Controls.FloatingNotice.Show(Status); StatusChanged?.Invoke(this, new CollectionStatus(message, severity)); }
    // Closing the view must not discard an acknowledged/unknown write on this session.
    // Only resource navigation is retired; account/session replacement cancels all work.
    public void SuspendView() { ++_openGeneration; _dialog?.Hide(); }
    private void Close_Click(object sender, RoutedEventArgs args) => CloseRequested?.Invoke(this, EventArgs.Empty);
    private async void Refresh_Click(object sender, RoutedEventArgs args) => await RefreshAsync();
    private void Mode_Changed(object sender, SelectionChangedEventArgs args) { if (Records is not null && !_rendering) Render(); }
    private void Group_Changed(object sender, SelectionChangedEventArgs args) { if (!_rendering) Render(); }
    private void Record_Changed(object sender, SelectionChangedEventArgs args) { if (!_rendering) { RenderTagResources(); UpdateCommands(); } }
    private void TagResource_Changed(object sender, SelectionChangedEventArgs args) => UpdateCommands();
    private async void Open_Click(object sender, RoutedEventArgs args) => await OpenAsync(Selected);
    private async void OpenTagResource_Click(object sender, RoutedEventArgs args) => await OpenAsync(TagResources.SelectedItem as CollectionRow);
    private async void Records_DoubleTapped(object sender, DoubleTappedRoutedEventArgs args) { if (SelectTapped(Records, args)) await OpenAsync(Selected); }
    private async void TagResources_DoubleTapped(object sender, DoubleTappedRoutedEventArgs args) { if (SelectTapped(TagResources, args)) await OpenAsync(TagResources.SelectedItem as CollectionRow); }
    private static bool SelectTapped(ListView view, DoubleTappedRoutedEventArgs args)
    {
        var source = args.OriginalSource as DependencyObject;
        while (source is not null && !ReferenceEquals(source, view) && source is not SelectorItem) source = VisualTreeHelper.GetParent(source);
        if (source is not SelectorItem item) return false;
        view.SelectedItem = item.Content; return true;
    }
    private async void Records_KeyDown(object sender, KeyRoutedEventArgs args) { if (!args.Handled && args.Key == VirtualKey.Enter) { args.Handled = true; await OpenAsync(Selected); } }
    private async void TagResources_KeyDown(object sender, KeyRoutedEventArgs args) { if (!args.Handled && args.Key == VirtualKey.Enter) { args.Handled = true; await OpenAsync(TagResources.SelectedItem as CollectionRow); } }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true; ++_generation; _dialog?.Hide(); _contextCancellation?.Cancel(); _contextCancellation?.Dispose();
        _session = null; _snapshot = null; _currentResource = null; OpenResourceAsync = null;
    }
    private sealed record Scope(Guid SessionId, AccountKey Account, long Generation, CancellationToken Token);
    private sealed class CollectionAccountState
    {
        public bool RequiresRefresh;
        public bool UntrackedUnknown;
        public HashSet<OperationRef> UnknownOperations { get; } = [];
        public HashSet<string> Warnings { get; } = [];
    }
}

[Microsoft.UI.Xaml.Data.Bindable]
public sealed class GroupChoice(string id, string label, FavoriteGroup? group = null)
{
    public string Id { get; } = id;
    public string Label { get; } = label;
    public FavoriteGroup? Group { get; } = group;
    public override string ToString() => Label;
}

[Microsoft.UI.Xaml.Data.Bindable]
public sealed class ColorChoice(string name, string value)
{
    public string Name { get; } = name;
    public string Value { get; } = value;
    public string Label => Name;
    public override string ToString() => Label;
    public SolidColorBrush Brush
    {
        get
        {
            if (Value.Length == 7 && uint.TryParse(Value.AsSpan(1), System.Globalization.NumberStyles.HexNumber, null, out var rgb)) return new SolidColorBrush(Windows.UI.Color.FromArgb(255, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb));
            return new SolidColorBrush(Microsoft.UI.Colors.Gray);
        }
    }
}

public sealed record CollectionRow(string Id, string Name, string Detail, string State, CollectionResource? Reference = null, ResourceFavorite? Favorite = null, ResourceTag? Tag = null)
{
    public override string ToString() => Name;
    public Visibility IconVisibility => Tag is not null && Reference is null ? Visibility.Collapsed : Visibility.Visible;
    public Visibility TagColorVisibility => Tag is not null && Reference is null ? Visibility.Visible : Visibility.Collapsed;
    public SolidColorBrush TagColorBrush => new ColorChoice("", Tag?.Color ?? "").Brush;
}

public sealed record CollectionStatus(string Message, InfoBarSeverity Severity);
public sealed record CollectionReceiptWarning(AccountKey Account, string Message);
