using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices
{
    /// <summary>Cancelable read with brief principal checks; a long checksum must not monopolize the session operation gate.</summary>
    private async Task<JsonElement> SessionLongReadAsync(SessionState state, string endpoint, TimeSpan timeout,
        CancellationToken cancellationToken, bool requireDownload = false)
    {
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromMinutes(30))
            throw Safe(ErrorCode.InvalidRequest, "读取时限无效。");
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        await RefreshSessionAsync(state, linked.Token).ConfigureAwait(false);
        EnsureActive(state);
        if (requireDownload) RequireDownload(state);
        try
        {
            // Only GET is admitted here. Core routing and principal ownership
            // remain immutable while other session requests can proceed.
            var response = await CallAsync("request", new { method = "GET", endpoint }, state.CoreSession,
                linked.Token, HostCallSafety.ReadOnly, timeout).ConfigureAwait(false);
            _ = HttpBody(response);
            await RefreshSessionAsync(state, linked.Token).ConfigureAwait(false);
            EnsureActive(state);
            if (requireDownload) RequireDownload(state);
            return response;
        }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.Canceled && state.LifetimeToken.IsCancellationRequested)
        {
            throw SessionCanceled(state, cancellationToken);
        }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.AuthenticationExpired)
        {
            if (await RetireAsync(state).ConfigureAwait(false)) PublishConnectionIssue(state, error.Error);
            throw;
        }
    }
}
