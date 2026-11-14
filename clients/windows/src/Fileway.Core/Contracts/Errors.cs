namespace Fileway.Core.Contracts;

public enum ErrorCode
{
    Unknown,
    AuthenticationExpired,
    PermissionDenied,
    NetworkUnavailable,
    Timeout,
    Canceled,
    Conflict,
    SourceChanged,
    OutcomeUnknown,
    ResponseTooLarge,
    CapabilityMissing,
    HostExited,
    ProtocolMismatch,
    InvalidRequest,
    Busy,
    CredentialsUnavailable,
    LocalStorageUnavailable,
}

/// <summary>Safe to present or export. It must not contain credentials, leases or private URLs/paths.</summary>
public sealed record ErrorInfo(
    ErrorCode Code,
    string Message,
    int? HttpStatus = null,
    bool Retryable = false);

public sealed class FilewayException : Exception
{
    public FilewayException(ErrorInfo error) : base(error.Message)
    {
        Error = error;
    }

    public FilewayException(ErrorInfo error, Exception innerException) : base(error.Message, innerException)
    {
        Error = error;
    }

    public ErrorInfo Error { get; }
}
