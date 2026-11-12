using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace Fileway.App;

public sealed partial class MainPage
{
    private enum ConnectionAction { Connect, Restore, RetryRestore, SignIn, OpenWorkspace }
    private readonly Dictionary<Guid, ConnectionAction> _savedConnectionRecovery = [];
    private bool _applyingConnectionForm;
    private bool _restoringConnection;
    private Guid? _workspaceRetrySession;
    private bool _composingConnectionField;
    private long _connectionCompositionRevision;

    private bool PasswordAuthentication => AuthenticationBox.SelectedIndex != 1;
    private bool ConnectionNetworkReady => SelectedConnectionNetwork != NetworkMode.EmbeddedTailnet ||
        _tailnetStatus?.State == EmbeddedTailnetState.Running;

    private void InitializeConnectionForm()
    {
        foreach (var box in new[] { ServerBox, ConnectionNameBox, UsernameBox })
        {
            box.TextCompositionStarted += (_, _) =>
            {
                ++_connectionCompositionRevision;
                _composingConnectionField = true;
            };
            box.TextCompositionEnded += (_, _) =>
            {
                var revision = ++_connectionCompositionRevision;
                DispatcherQueue.TryEnqueue(() =>
                {
                    if (revision == _connectionCompositionRevision) _composingConnectionField = false;
                });
            };
        }
        LoginNotice.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, (sender, _) =>
        {
            var notice = (InfoBar)sender;
            notice.Visibility = notice.IsOpen ? Visibility.Visible : Visibility.Collapsed;
            UpdateConnectionSecondaryActions();
        });
        UpdateConnectionPrimaryAction();
    }

    private ConnectionAction PrimaryConnectionAction()
    {
        if (_workspaceRetrySession is { } session && _session?.Id == session)
            return ConnectionAction.OpenWorkspace;
        if (SavedConnectionBox.SelectedItem is not ConnectionChoice { Saved.KeepSignedIn: true } choice ||
            HasSavedConnectionEdits(choice))
            return ConnectionAction.Connect;
        if (_savedConnectionRecovery.TryGetValue(choice.Saved.Profile.Id, out var recovery) && recovery == ConnectionAction.SignIn)
            return recovery;
        if (PasswordBox.Password.Length > 0) return ConnectionAction.Connect;
        return recovery == ConnectionAction.RetryRestore ? recovery : ConnectionAction.Restore;
    }

    private bool HasSavedConnectionEdits(ConnectionChoice choice)
    {
        var saved = choice.Saved;
        return !TryReadConnectionAddress(out var address) ||
            address.AbsoluteUri != saved.Profile.BaseAddress.AbsoluteUri ||
            !string.Equals(ConnectionNameBox.Text.Trim(), choice.DisplayName, StringComparison.Ordinal) ||
            SelectedConnectionNetwork != saved.Profile.Network ||
            PasswordAuthentication != (saved.AuthenticationMode == AuthenticationMode.Password) ||
            PasswordAuthentication && !string.Equals(UsernameBox.Text, saved.Username, StringComparison.Ordinal) ||
            (KeepSignedInBox.IsChecked == true) != saved.KeepSignedIn;
    }

    private bool TryReadConnectionAddress([System.Diagnostics.CodeAnalysis.NotNullWhen(true)] out Uri? address) =>
        Uri.TryCreate(ServerBox.Text.Trim(), UriKind.Absolute, out address) &&
        (address.Scheme is "http" or "https") && address.UserInfo.Length == 0 &&
        address.Query.Length == 0 && address.Fragment.Length == 0;

    private void UpdateConnectionPrimaryAction()
    {
        if (ConnectButton is null || _disposed) return;
        var action = PrimaryConnectionAction();
        ConnectButton.Content = _connecting ? (_restoringConnection ? "正在恢复…" :
            action == ConnectionAction.OpenWorkspace ? "正在打开…" : "正在连接…")
            : action switch
            {
                ConnectionAction.Restore => "恢复登录",
                ConnectionAction.RetryRestore => "重试恢复登录",
                ConnectionAction.SignIn => PasswordAuthentication ? "重新登录" : "重新连接",
                ConnectionAction.OpenWorkspace => "重试打开文件空间",
                _ => "连接并浏览",
            };
        bool networkReady = ConnectionNetworkReady;
        ConnectButton.IsEnabled = !_connecting && !_tailnetBusy && _connection is not null && networkReady;
        var style = Controls.SurfaceGeometry.Style(networkReady ? "FwPrimaryButtonStyle" : "FwSecondaryButtonStyle");
        if (!ReferenceEquals(ConnectButton.Style, style)) ConnectButton.Style = style;
        UpdateConnectionSecondaryActions();
    }

    private void UpdateConnectionSecondaryActions()
    {
        if (LoginErrorDetailsButton is null || LocalServerFromConnectionButton is null || _disposed) return;
        bool hasDetails = !_connecting && LoginNotice.IsOpen && LoginErrorDetailsButton.Tag is ErrorInfo;
        LoginErrorDetailsButton.Visibility = hasDetails ? Visibility.Visible : Visibility.Collapsed;
        LocalServerFromConnectionButton.Visibility = !_connecting && !hasDetails && _session is null
            ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ConnectionDraftChanged(object sender, RoutedEventArgs args)
    {
        if (_applyingConnectionForm || _connecting || _disposed || ConnectButton is null) return;
        // WinUI can deliver PasswordChanged after the operation has cleared a failed password.
        if (ReferenceEquals(sender, PasswordBox) && PasswordBox.Password.Length == 0 ||
            SavedConnectionBox.SelectedItem is ConnectionChoice choice && !HasSavedConnectionEdits(choice) && PasswordBox.Password.Length == 0)
        {
            UpdateConnectionPrimaryAction();
            return;
        }
        _workspaceRetrySession = null;
        LoginNotice.IsOpen = false;
        UpdateConnectionPrimaryAction();
    }

    private void RememberConnectionRecovery(Guid profileId, ErrorInfo error)
    {
        if (error.Code is ErrorCode.AuthenticationExpired or ErrorCode.CredentialsUnavailable)
            _savedConnectionRecovery[profileId] = ConnectionAction.SignIn;
        else if (error.Code is ErrorCode.NetworkUnavailable or ErrorCode.Timeout or ErrorCode.HostExited)
            _savedConnectionRecovery[profileId] = ConnectionAction.RetryRestore;
    }

    private bool SelectSavedConnection(Guid profileId)
    {
        if (SavedConnectionBox.ItemsSource is not IEnumerable<ConnectionChoice> choices ||
            choices.FirstOrDefault(choice => choice.Saved.Profile.Id == profileId) is not { } selected)
            return false;
        _refreshingSavedConnections = true;
        try { SavedConnectionBox.SelectedItem = selected; }
        finally { _refreshingSavedConnections = false; }
        FillSavedSelection();
        return true;
    }

    private Task ExecutePrimaryConnectionActionAsync()
    {
        if (_disposed || _connecting || _composingConnectionField || !ConnectButton.IsEnabled ||
            ConnectionPanel.Visibility != Visibility.Visible) return Task.CompletedTask;
        return PrimaryConnectionAction() switch
        {
            ConnectionAction.Restore or ConnectionAction.RetryRestore => RestoreSelectedAsync(),
            ConnectionAction.OpenWorkspace when _session is { } session => RunConnectionAsync(_ => Task.FromResult(session),
                session.Account.ProfileId, openingOnly: true),
            _ => ConnectFromFormAsync(),
        };
    }

    private async void ConnectionField_KeyDown(object sender, KeyRoutedEventArgs args)
    {
        if (args.Handled || args.Key != VirtualKey.Enter || args.KeyStatus.WasKeyDown || _composingConnectionField) return;
        foreach (var modifier in new[] { VirtualKey.Control, VirtualKey.Menu, VirtualKey.Shift })
            if (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(modifier)
                .HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down)) return;
        args.Handled = true;
        await ExecutePrimaryConnectionActionAsync();
    }

    private void FocusConnectionField(Control target)
    {
        if (_disposed || _connecting || ConnectionPanel.Visibility != Visibility.Visible) return;
        if (!target.Focus(FocusState.Programmatic))
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!_disposed && !_connecting && ConnectionPanel.Visibility == Visibility.Visible)
                    target.Focus(FocusState.Programmatic);
            });
    }

    private void FocusConnectionForm()
    {
        if (!ConnectionNetworkReady)
        {
            FocusConnectionField(TailnetAuthorizeButton is { IsEnabled: true, Visibility: Visibility.Visible }
                ? TailnetAuthorizeButton : TailnetStartButton is { IsEnabled: true, Visibility: Visibility.Visible }
                    ? TailnetStartButton : TailnetReadButton is { IsEnabled: true, Visibility: Visibility.Visible }
                        ? TailnetReadButton : ConnectionNetworkBox);
            return;
        }
        var action = PrimaryConnectionAction();
        FocusConnectionField(action switch
        {
            ConnectionAction.SignIn => PasswordAuthentication ? PasswordBox : AuthenticationBox,
            ConnectionAction.Restore or ConnectionAction.RetryRestore or ConnectionAction.OpenWorkspace => ConnectButton,
            _ => ServerBox,
        });
    }
}
