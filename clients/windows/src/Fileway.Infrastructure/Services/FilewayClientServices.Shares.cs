using System.Globalization;
using System.Text.Json;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IShareService
{
    async Task<IReadOnlyList<ShareLink>> IShareService.ListAsync(AccountKey account, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            RequireShare(state);
            var response = await RequestAsync(state, "/api/shares", linked.Token).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            var links = new List<ShareLink>();
            foreach (var row in body.RootElement.EnumerateArray()) links.Add(ParseShareLink(row, state));
            return links;
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task<IReadOnlyList<ShareLink>> IShareService.GetForResourceAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        _ = FileOperationWireIdentity(resource.WirePath, allowRoot: true);
        var state = GetResourceState(resource);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            RequireShare(state);
            var response = await RequestAsync(state, "/api/share" + resource.WirePath, linked.Token).ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            var links = new List<ShareLink>();
            foreach (var row in body.RootElement.EnumerateArray()) links.Add(ParseShareLink(row, state));
            return links;
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task<ShareWriteResult> IShareService.CreateAsync(RemoteResourceRef resource, ShareCreationRequest request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(resource);
        ArgumentNullException.ThrowIfNull(request);
        _ = FileOperationWireIdentity(resource.WirePath, allowRoot: true);
        if (request.Expires is { } count && count <= 0) throw Safe(ErrorCode.InvalidRequest, "分享期限必须为正整数。");
        if (request.Password is { } password && password.Length > 256) throw Safe(ErrorCode.InvalidRequest, "分享密码过长。");
        var state = GetResourceState(resource);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        string expires = request.Expires?.ToString(CultureInfo.InvariantCulture) ?? "";
        string unit = ShareUnit(request.Unit);
        try
        {
            RequireShare(state);
            var response = await SessionRequestAsync(state, new
            {
                method = "POST",
                endpoint = "/api/share" + resource.WirePath,
                body = new { password = request.Password ?? "", expires, unit },
            }, requireDownload: false, HostCallSafety.Mutation, linked.Token, operation: "request").ConfigureAwait(false);
            RequireExactStatus(response, 200);
            using var body = ParseBody(response);
            EnsureActive(state);
            return new ShareWriteResult(OperationOutcome.Confirmed, ParseShareLink(body.RootElement, state));
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.OutcomeUnknown)
        {
            return new ShareWriteResult(OperationOutcome.OutcomeUnknown, Error: new ErrorInfo(ErrorCode.OutcomeUnknown,
                "分享创建结果尚未确认，请刷新分享列表核实；不要直接重复提交。", error.Error.HttpStatus));
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        { throw InvalidResponse(); }
    }

    async Task<ShareWriteResult> IShareService.DeleteAsync(AccountKey account, string hash, CancellationToken cancellationToken)
    {
        var state = GetState(account);
        ValidateShareHash(hash);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, state.LifetimeToken);
        try
        {
            RequireShare(state);
            var response = await SessionRequestAsync(state, new
            {
                method = "DELETE",
                endpoint = "/api/share/" + hash,
            }, requireDownload: false, HostCallSafety.Mutation, linked.Token, operation: "request").ConfigureAwait(false);
            RequireExactStatus(response, 200);
            EnsureActive(state);
            return new ShareWriteResult(OperationOutcome.Confirmed);
        }
        catch (OperationCanceledException) { throw SessionCanceled(state, cancellationToken); }
        catch (FilewayException error) when (error.Error.Code == ErrorCode.OutcomeUnknown)
        {
            return new ShareWriteResult(OperationOutcome.OutcomeUnknown, Error: new ErrorInfo(ErrorCode.OutcomeUnknown,
                "分享删除结果尚未确认，请刷新分享列表核实；不要直接重复提交。", error.Error.HttpStatus));
        }
    }

    private void RequireShare(SessionState state)
    {
        EnsureActive(state);
        if (!state.Session.Permissions.Share || !state.Session.Permissions.Download)
            throw Safe(ErrorCode.PermissionDenied, "当前账户没有分享权限（需要分享与下载权限）。");
    }

    private static string ShareUnit(ShareExpiryUnit unit) => unit switch
    {
        ShareExpiryUnit.Seconds => "seconds",
        ShareExpiryUnit.Minutes => "minutes",
        ShareExpiryUnit.Days => "days",
        _ => "hours",
    };

    private static void ValidateShareHash(string hash)
    {
        if (string.IsNullOrEmpty(hash) || hash.Length > 128 ||
            hash.Any(character => character is not (>= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or '-' or '_' or '=')))
            throw Safe(ErrorCode.InvalidRequest, "分享标识无效。");
    }

    private static ShareLink ParseShareLink(JsonElement row, SessionState state)
    {
        string hash = RequiredText(row.GetProperty("hash"));
        ValidateShareHash(hash);
        string displayPath = OptionalText(row, "path") ?? "";
        long expire = row.GetProperty("expire").GetInt64();
        if (expire < 0) throw InvalidResponse();
        bool hasPassword = row.TryGetProperty("password_hash", out var passwordHash) &&
            passwordHash.ValueKind == JsonValueKind.String && !string.IsNullOrEmpty(passwordHash.GetString());
        string baseUrl = state.Saved.Profile.BaseAddress.AbsoluteUri.TrimEnd('/');
        string shareUrl = baseUrl + "/share/" + hash;
        string? downloadUrl = hasPassword ? null : baseUrl + "/api/public/dl/" + hash + "?inline=true";
        return new ShareLink(state.Session.Account, hash, displayPath,
            expire == 0 ? null : DateTimeOffset.FromUnixTimeSeconds(expire), hasPassword, shareUrl, downloadUrl);
    }
}