using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class UploadApplicationService
{
    public Task<ConnectionSession> ConnectAsync(ConnectionProfile profile, LoginInput login, CancellationToken cancellationToken) =>
        ConnectCoreAsync(token => _connection.ConnectAsync(profile, login, token), cancellationToken);

    public Task<ConnectionSession> RestoreAsync(Guid profileId, CancellationToken cancellationToken) =>
        ConnectCoreAsync(token => _connection.RestoreAsync(profileId, token), cancellationToken);

    private async Task<ConnectionSession> ConnectCoreAsync(Func<CancellationToken, Task<ConnectionSession>> connect,
        CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        ConnectionSession? opened = null;
        UploadActivationToken? activation = null;
        var opening = new Opening();
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            Binding? previous;
            lock (_gate) { _opening = opening; previous = FenceCurrentLocked(); }
            try { await AwaitDrainsAsync().ConfigureAwait(false); }
            catch
            {
                // Drain failures are reported only after local release. Retire the old owner but do not open a new one.
                if (previous is not null) await _connection.DisconnectAsync(previous.Context.Account, CancellationToken.None).ConfigureAwait(false);
                throw;
            }
            if (previous is not null)
                await _connection.DisconnectAsync(previous.Context.Account, CancellationToken.None).ConfigureAwait(false);
            opened = await connect(cancellationToken).ConfigureAwait(false);
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                if (opening.Invalidated.Contains((opened.Account, opened.Id))) throw Safe(ErrorCode.SourceChanged);
                if (opening.Updates.TryGetValue((opened.Account, opened.Id), out var updated)) opened = updated;
            }
            activation = await _coordinator.ActivateAsync(opened, cancellationToken).ConfigureAwait(false);
            if (activation.Value.Account != opened.Account || activation.Value.SessionId != opened.Id || activation.Value.Id == Guid.Empty)
                throw Safe(ErrorCode.ProtocolMismatch);
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                if (opening.Invalidated.Contains((opened.Account, opened.Id))) throw Safe(ErrorCode.SourceChanged);
                if (opening.Updates.TryGetValue((opened.Account, opened.Id), out var updated)) opened = updated;
                _current = new(opened, activation.Value);
                _opening = null;
            }
            return opened;
        }
        catch (Exception error)
        {
            if (activation is { } started)
            {
                try { await _coordinator.StopAndDrainAsync(started, CancellationToken.None).ConfigureAwait(false); }
                catch (Exception) { /* Never give this failed activation to callers; coordinator disposal remains owned. */ }
            }
            if (opened is not null)
            {
                try { await _connection.DisconnectAsync(opened.Account, CancellationToken.None).ConfigureAwait(false); }
                catch (Exception) { /* Preserve the original safe failure; the application still owns service disposal. */ }
            }
            throw Normalize(error);
        }
        finally
        {
            lock (_gate) if (ReferenceEquals(_opening, opening)) _opening = null;
            _commands.Release();
        }
    }

    public Task DisconnectAsync(AccountKey account, CancellationToken cancellationToken) =>
        RetireCommandAsync(account, token => _connection.DisconnectAsync(account, token), cancellationToken);

    public Task LogoutAsync(AccountKey account, CancellationToken cancellationToken) =>
        RetireCommandAsync(account, token => _connection.LogoutAsync(account, token), cancellationToken);

    public async Task ForgetAsync(Guid profileId, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate) if (_current?.Context.Account.ProfileId == profileId) _ = FenceCurrentLocked();
            FilewayException? failure = null;
            try { await AwaitDrainsAsync().ConfigureAwait(false); }
            catch (Exception error) { failure = Normalize(error); }
            // The decorated catalog retains its protected-transfer/journal guard. Never bypass it or remove task records here.
            try { await _catalog.ForgetAsync(profileId, CancellationToken.None).ConfigureAwait(false); }
            catch (Exception error) { failure = Normalize(error); }
            if (failure is not null) throw failure;
        }
        finally { _commands.Release(); }
    }

    private async Task RetireCommandAsync(AccountKey account, Func<CancellationToken, Task> retire, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate) if (_current?.Context.Account == account) _ = FenceCurrentLocked();
            FilewayException? failure = null;
            try { await AwaitDrainsAsync().ConfigureAwait(false); }
            catch (Exception error) { failure = Normalize(error); }
            // A failed Pause write must not leave a requested logout using the old session after handles drain.
            // Cancellation was honored before the command began. Once draining starts,
            // it cannot skip the already-requested retirement after handles release.
            try { await retire(CancellationToken.None).ConfigureAwait(false); }
            catch (Exception error) { failure = Normalize(error); }
            if (failure is not null) throw failure;
        }
        finally { _commands.Release(); }
    }

    public async Task<IReadOnlyList<SavedConnection>> ListAsync(CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try { return await _catalog.ListAsync(cancellationToken).ConfigureAwait(false); }
        finally { _commands.Release(); }
    }

    public async Task<SavedConnection?> GetAsync(Guid profileId, CancellationToken cancellationToken)
    {
        await EnterAsync(cancellationToken).ConfigureAwait(false);
        try { return await _catalog.GetAsync(profileId, cancellationToken).ConfigureAwait(false); }
        finally { _commands.Release(); }
    }

    // Caller holds _gate. This notification only fences admission; drain runs outside the retiring runner's stack.
    private Binding? FenceCurrentLocked()
    {
        var binding = _current;
        _current = null;
        _updates.Clear();
        if (binding is not null)
            _drains.Add(Task.Run(() => _coordinator.StopAndDrainAsync(binding.Activation, CancellationToken.None)));
        return binding;
    }

    private async Task AwaitDrainsAsync()
    {
        Task[] pending;
        lock (_gate) { pending = [.. _drains]; _drains.Clear(); }
        if (pending.Length != 0) await Task.WhenAll(pending).ConfigureAwait(false);
    }

    private void ConnectionSessionUpdated(object? sender, ConnectionSession session)
    {
        lock (_gate)
        {
            if (_disposed) return;
            if (_opening is { } opening) opening.Updates[(session.Account, session.Id)] = session;
            if (_current is { } current && current.Context.Account == session.Account && current.Context.SessionId == session.Id)
                current.Session = session;
        }
        PublishEvent(this, SessionUpdated, session);
    }

    private void ConnectionIssueReceived(object? sender, ConnectionIssue issue)
    {
        lock (_gate)
        {
            if (_disposed) return;
            if (issue.SessionInvalidated && _opening is { } opening) opening.Invalidated.Add((issue.Account, issue.SessionId));
            if (issue.SessionInvalidated && _current is { } current && current.Context.Account == issue.Account &&
                current.Context.SessionId == issue.SessionId) _ = FenceCurrentLocked();
        }
        PublishEvent(this, ConnectionIssue, issue with { Error = Safe(issue.Error.Code, issue.Error.HttpStatus, issue.Error.Retryable).Error });
    }

    public ValueTask DisposeAsync()
    {
        TaskCompletionSource completion;
        lock (_gate)
        {
            if (_disposeCompletion is not null) return new(_disposeCompletion.Task);
            _disposeCompletion = completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            _disposed = true;
            _ = FenceCurrentLocked();
        }
        _connection.SessionUpdated -= ConnectionSessionUpdated;
        _connection.ConnectionIssue -= ConnectionIssueReceived;
        _coordinator.TaskChanged -= QueueTaskChanged;
        _ = DisposeCoreAsync(completion);
        return new(completion.Task);
    }

    private async Task DisposeCoreAsync(TaskCompletionSource completion)
    {
        FilewayException? failure = null;
        await _commands.WaitAsync().ConfigureAwait(false);
        try
        {
            try { await AwaitDrainsAsync().ConfigureAwait(false); }
            catch (Exception error) { failure = Normalize(error); }
            try { await _coordinator.DisposeAsync().ConfigureAwait(false); }
            catch (Exception error) { failure ??= Normalize(error); }
        }
        finally { _commands.Release(); }
        if (failure is not null) completion.TrySetException(failure);
        else completion.TrySetResult();
    }
}
