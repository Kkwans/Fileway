namespace Fileway.Core.Contracts;

public enum ServerSettingField
{
    Signup,
    HideLoginButton,
    CreateUserDir,
    UserHomeBasePath,
    MinimumPasswordLength,
    SessionMinutes,
    DefaultScope,
    DefaultLocale,
    DefaultView,
    DefaultSingleClick,
    DefaultRedirect,
    DefaultDateFormat,
    DefaultHideDotfiles,
    DefaultEditorTheme,
    DefaultSortBy,
    DefaultSortAscending,
    DefaultAdmin,
    DefaultDownload,
    DefaultShare,
    DefaultCreate,
    DefaultRename,
    DefaultModify,
    DefaultDelete,
    DefaultExecute,
    BrandName,
    BrandFiles,
    BrandTheme,
    BrandColor,
    BrandDisableExternal,
    BrandHideUsage,
    UploadChunk,
    UploadRetries,
    DefaultCommands,
    Shell,
    BeforeCopy,
    AfterCopy,
    BeforeDelete,
    AfterDelete,
    BeforeRename,
    AfterRename,
    BeforeSave,
    AfterSave,
    BeforeUpload,
    AfterUpload,
}

public sealed record ServerPathRule(bool Allow, bool RegularExpression, string Match)
{
    public override string ToString() => "ServerPathRule (path redacted)";
}

public sealed record ServerSettingsSnapshot(
    Guid SessionId,
    string Revision,
    string AuthMethod,
    bool? CommandExecutionEnabled,
    IReadOnlyDictionary<ServerSettingField, string> Values,
    IReadOnlyList<ServerPathRule> Rules)
{
    public override string ToString() => "ServerSettingsSnapshot (configuration redacted)";
}

public sealed record ServerSettingsEdit(
    IReadOnlyDictionary<ServerSettingField, string> Values,
    IReadOnlyList<ServerPathRule>? Rules)
{
    public override string ToString() => "ServerSettingsEdit (configuration redacted)";
}

public interface IServerSettingsService
{
    Task<ServerSettingsSnapshot> ReadServerSettingsAsync(AccountKey account, CancellationToken cancellationToken);
    Task<ServerSettingsSnapshot> SaveServerSettingsAsync(AccountKey account, ServerSettingsSnapshot snapshot, ServerSettingsEdit edit,
        CancellationToken cancellationToken);
}
