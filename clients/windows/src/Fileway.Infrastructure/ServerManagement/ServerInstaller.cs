using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using Fileway.Core.Contracts;

namespace Fileway.Infrastructure.ServerManagement;

/// <summary>
/// Bootstraps a fresh database with the server's own command line interface.
/// Only 'users add' hashes a supplied password, so the quick-setup path is never used for a chosen secret.
/// </summary>
internal static class ServerInstaller
{
    internal const int MinimumPasswordLength = 12;

    private static readonly char[] Alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".ToCharArray();

    internal static string GeneratePassword(int length = 20)
    {
        var builder = new StringBuilder(length);
        foreach (byte value in System.Security.Cryptography.RandomNumberGenerator.GetBytes(length))
            builder.Append(Alphabet[value % Alphabet.Length]);
        return builder.ToString();
    }

    internal static string GenerateUsername() => "fileway-" + Guid.NewGuid().ToString("N")[..6].ToLowerInvariant();

    internal static void Validate(LocalServerDeploymentRequest request)
    {
        if (request.Port is < 1024 or > 65535)
            throw Invalid("端口需要在 1024-65535 之间，避开需要管理员权限的低位端口。");
        if (string.IsNullOrWhiteSpace(request.AdminUsername) || request.AdminUsername.Length > 64 ||
            request.AdminUsername.Contains(' ', StringComparison.Ordinal) || request.AdminUsername.Any(char.IsControl))
            throw Invalid("管理员用户名需要是 1-64 个字符，不能包含空格或控制字符。");
        if (request.AdminPassword.Length < MinimumPasswordLength || request.AdminPassword.Length > 128 ||
            request.AdminPassword.Any(char.IsControl))
            throw Invalid($"管理员密码需要至少 {MinimumPasswordLength} 位，且不超过 128 位。");
        if (string.IsNullOrWhiteSpace(request.FilesRoot) || !Path.IsPathFullyQualified(request.FilesRoot))
            throw Invalid("请选择一个完整的共享文件夹路径。");
    }

    /// <summary>Creates the database and its single administrator; never adopts a foreign database.</summary>
    internal static async Task PrepareAsync(LocalServerLayout layout, LocalServerDocument document, string password, CancellationToken cancellationToken)
    {
        layout.EnsureDirectories();
        Directory.CreateDirectory(document.FilesRoot);
        if (File.Exists(layout.DatabasePath))
        {
            // The deployment document is what makes a database ours; a stray file must not be adopted.
            throw new FilewayException(new ErrorInfo(ErrorCode.Conflict,
                "这个数据目录下已经有一个不是本客户端创建的服务端数据库。请更换端口或选择新的数据目录后重新部署。"));
        }

        await RunAsync(layout,
        [
            "config", "init",
            "--database", layout.DatabasePath,
            "--address", document.Address,
            "--port", document.Port.ToString(CultureInfo.InvariantCulture),
            "--root", document.FilesRoot,
            "--log", layout.RuntimeLogPath,
            "--disableExec=true",
        ], cancellationToken).ConfigureAwait(false);
        if (!File.Exists(layout.DatabasePath))
            throw Failure("服务端没有创建数据库，请检查数据目录是否可写。", string.Empty);

        try
        {
            // The scope is a path inside the server root, so it stays relative rather than a native path.
            await RunAsync(layout,
            [
                "users", "add", document.AdminUsername, password,
                "--perm.admin=true",
                "--scope", ".",
                "--locale", "zh",
                "--viewMode", "mosaic",
                "--dateFormat=true",
                "--redirectAfterCopyMove=true",
                "--database", layout.DatabasePath,
            ], cancellationToken).ConfigureAwait(false);
        }
        catch (FilewayException)
        {
            // A half-provisioned database would block every retry, so remove only what this call created.
            TryDelete(layout.DatabasePath);
            throw;
        }
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { /* The next attempt reports the conflict. */ }
    }

    /// <summary>Runs a short-lived CLI step with captured output. The password travels only in this argv.</summary>
    private static async Task RunAsync(LocalServerLayout layout, IReadOnlyList<string> arguments, CancellationToken cancellationToken)
    {
        var start = new ProcessStartInfo
        {
            FileName = layout.ExecutablePath,
            WorkingDirectory = layout.ApplicationDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (string part in arguments) start.ArgumentList.Add(part);

        using var process = new Process { StartInfo = start };
        var output = new StringBuilder();
        var error = new StringBuilder();
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) _ = output.Append(e.Data).Append('\n'); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) _ = error.Append(e.Data).Append('\n'); };
        try
        {
            if (!process.Start()) throw Failure("无法启动服务端安装步骤。", string.Empty);
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(90));
            try
            {
                await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                SafeKill(process);
                throw Failure("服务端安装步骤超时。", string.Empty);
            }
        }
        catch (Exception exception) when (exception is Win32Exception or InvalidOperationException)
        {
            throw Failure("无法运行服务端安装步骤。", exception.Message);
        }

        process.WaitForExit();
        cancellationToken.ThrowIfCancellationRequested();
        if (process.ExitCode != 0)
            throw Failure("服务端初始化失败（退出码 " + process.ExitCode.ToString(CultureInfo.InvariantCulture) + "）。", Tail(error.Length > 0 ? error.ToString() : output.ToString()));
    }

    private static void SafeKill(Process process)
    {
        try
        {
            if (!process.HasExited) process.Kill(entireProcessTree: false);
        }
        catch (Exception error) when (error is InvalidOperationException or Win32Exception)
        {
            // The step already finished; the exit code check reports the real outcome.
        }
    }

    private static string Tail(string text)
    {
        string trimmed = text.Trim();
        if (trimmed.Length == 0) return string.Empty;
        string[] lines = trimmed.Split('\n');
        return string.Join(' ', lines.Skip(Math.Max(0, lines.Length - 4)).Select(line => line.Trim())).Trim();
    }

    private static FilewayException Invalid(string message) => new(new ErrorInfo(ErrorCode.InvalidRequest, message));

    private static FilewayException Failure(string message, string detail) => new(new ErrorInfo(ErrorCode.LocalStorageUnavailable,
        string.IsNullOrEmpty(detail) ? message : message + " " + detail));
}
