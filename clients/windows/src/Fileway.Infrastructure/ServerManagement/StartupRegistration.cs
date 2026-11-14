using Microsoft.Win32;

namespace Fileway.Infrastructure.ServerManagement;

/// <summary>
/// Starts the managed server when this Windows user signs in.
/// Task Scheduler was measured to require elevation on ordinary accounts, so the per-user Run key is used.
/// </summary>
internal static class StartupRegistration
{
    internal const string ValueName = "FilewayLocalServer";

    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

    internal static string Command(string executable, IReadOnlyList<string> arguments)
    {
        var builder = new System.Text.StringBuilder("\"" + executable + "\"");
        foreach (string argument in arguments)
            builder.Append(' ').Append(argument.Contains(' ', StringComparison.Ordinal) ? "\"" + argument + "\"" : argument);
        return builder.ToString();
    }

    internal static bool IsRegistered(string command) => Read() == command;

    internal static void Apply(string command)
    {
        using RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true)
            ?? throw new IOException("无法打开当前用户的启动项注册表位置。");
        key.SetValue(ValueName, command, RegistryValueKind.String);
    }

    internal static void Remove()
    {
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
        if (key?.GetValue(ValueName) is null) return;
        key.DeleteValue(ValueName, throwOnMissingValue: false);
    }

    private static string? Read()
    {
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
        return key?.GetValue(ValueName) as string;
    }
}
