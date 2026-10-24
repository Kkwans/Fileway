using System.Buffers.Binary;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Ipc;

internal static class HostWire
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    // Domain records intentionally hide payload from serializers. This DTO is
    // private to the transport and is never logged or used as an exception value.
    private sealed class RequestEnvelope
    {
        [JsonPropertyName("protocolMajor")] public int Major { get; init; } = HostProtocol.Major;
        [JsonPropertyName("protocolMinor")] public int Minor { get; init; } = HostProtocol.Minor;
        [JsonPropertyName("requestId")] public required string RequestId { get; init; }
        [JsonPropertyName("generation")] public long Generation { get; init; }
        [JsonPropertyName("op")] public required string Operation { get; init; }
        [JsonPropertyName("session"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Session { get; init; }
        [JsonPropertyName("parameters")] public required JsonElement Parameters { get; init; }
    }

    internal static byte[] Encode(string id, HostCall call)
    {
        if (string.IsNullOrEmpty(call.Operation) || call.Generation < 0 || call.Session is { Length: 0 })
            throw SafeError(ErrorCode.InvalidRequest, "Invalid Host request identity.");
        JsonElement parameters = call.Parameters?.Clone() ?? JsonSerializer.SerializeToElement(new { });
        if (parameters.ValueKind != JsonValueKind.Object)
            throw SafeError(ErrorCode.InvalidRequest, "Host parameters must be an object.");
        ValidateUnique(parameters);
        foreach (var property in parameters.EnumerateObject())
        {
            if (property.Name.Equals("op", StringComparison.OrdinalIgnoreCase) ||
                property.Name.Equals("session", StringComparison.OrdinalIgnoreCase) ||
                property.Name.Equals("requestId", StringComparison.OrdinalIgnoreCase) ||
                property.Name.Equals("requestId", StringComparison.OrdinalIgnoreCase))
                throw SafeError(ErrorCode.InvalidRequest, "Reserved Host parameters are not allowed.");
        }
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new RequestEnvelope
        {
            RequestId = id, Generation = call.Generation, Operation = call.Operation, Session = call.Session, Parameters = parameters,
        });
        if (payload.Length > HostProtocol.MaxFrameBytes)
            throw SafeError(ErrorCode.ResponseTooLarge, "Host request exceeds the frame limit.");
        // The core receives these same fields after envelope removal. Use its
        // actual encoded size, including supplied identity, rather than body size.
        using var coreBuffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(coreBuffer))
        {
            writer.WriteStartObject();
            writer.WriteString("op", call.Operation);
            writer.WriteString("requestId", "windows-host-18446744073709551615");
            if (call.Session is not null) writer.WriteString("session", call.Session);
            foreach (var property in parameters.EnumerateObject()) property.WriteTo(writer);
            writer.WriteEndObject();
        }
        if (coreBuffer.Length > HostProtocol.MaxCoreCommandBytes)
            throw SafeError(ErrorCode.InvalidRequest, "Host command exceeds the core limit.");
        byte[] frame = new byte[payload.Length + 4];
        BinaryPrimitives.WriteUInt32LittleEndian(frame, (uint)payload.Length);
        payload.CopyTo(frame, 4);
        return frame;
    }

    internal static async Task<JsonDocument> ReadAsync(Stream stream, CancellationToken cancellationToken)
    {
        byte[] header = new byte[4];
        await stream.ReadExactlyAsync(header, cancellationToken).ConfigureAwait(false);
        uint length = BinaryPrimitives.ReadUInt32LittleEndian(header);
        if (length is 0 or > HostProtocol.MaxFrameBytes)
            throw SafeError(ErrorCode.ProtocolMismatch, "Invalid Host frame length.");
        byte[] bytes = new byte[(int)length];
        await stream.ReadExactlyAsync(bytes, cancellationToken).ConfigureAwait(false);
        _ = StrictUtf8.GetCharCount(bytes);
        var document = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 128 });
        try { ValidateUnique(document.RootElement); }
        catch { document.Dispose(); throw; }
        return document;
    }

    internal static HostReply Decode(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object) throw InvalidResponse();
        foreach (var property in root.EnumerateObject())
            if (property.Name is not ("protocolMajor" or "protocolMinor" or "requestId" or "generation" or "ok" or "result" or "error" or "errorInfo")) throw InvalidResponse();
        if (Integer(root, "protocolMajor") != HostProtocol.Major || Integer(root, "protocolMinor") < 0) throw InvalidResponse();
        string id = Text(root, "requestId");
        if (id.Length is 0 or > 128 || id.Any(character => character is < '!' or > '~')) throw InvalidResponse();
        long generation = root.GetProperty("generation").GetInt64();
        if (generation < 0) throw InvalidResponse();
        bool ok = root.GetProperty("ok").GetBoolean();
        if (ok)
        {
            if (!root.TryGetProperty("result", out var result) || root.TryGetProperty("error", out _) || root.TryGetProperty("errorInfo", out _)) throw InvalidResponse();
            return new HostReply(id, generation, true, result.Clone(), null);
        }
        if (root.TryGetProperty("result", out _) || Text(root, "error").Length == 0) throw InvalidResponse();
        var error = root.GetProperty("errorInfo");
        if (error.ValueKind != JsonValueKind.Object) throw InvalidResponse();
        foreach (var property in error.EnumerateObject())
            if (property.Name is not ("code" or "message" or "retryable" or "httpStatus")) throw InvalidResponse();
        string code = Text(error, "code");
        if (code is not ("ProtocolMismatch" or "HandshakeRequired" or "InvalidRequest" or "DuplicateRequest" or "Busy" or
            "Canceled" or "ResponseTooLarge" or "HostClosing" or "HostFailure" or "CoreRejected")) throw InvalidResponse();
        _ = Text(error, "message"); // Untrusted text never enters an exportable error.
        bool retryable = error.GetProperty("retryable").GetBoolean();
        int? status = error.TryGetProperty("httpStatus", out var value) ? value.GetInt32() : null;
        if (status is < 100 or > 599 || code.Length == 0) throw InvalidResponse();
        string safeMessage = code switch
        {
            "Busy" => "Host request capacity is full.",
            "Canceled" => "The request was canceled.",
            "ProtocolMismatch" => "Host protocol is incompatible.",
            "ResponseTooLarge" => "Host response exceeds the frame limit.",
            "CoreRejected" => "The shared core rejected the request.",
            "HostClosing" => "The Host is closing.",
            _ => "The Host rejected the request.",
        };
        return new HostReply(id, generation, false, null, new HostError(code, safeMessage,
            code == "Busy" && retryable, status));
    }

    internal static void ValidateUnique(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name)) throw InvalidResponse();
                ValidateUnique(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var value in element.EnumerateArray()) ValidateUnique(value);
    }

    internal static string Text(JsonElement parent, string name) => parent.GetProperty(name).GetString() ?? throw InvalidResponse();
    internal static int Integer(JsonElement parent, string name) => parent.GetProperty(name).GetInt32();
    internal static FilewayException InvalidResponse() => SafeError(ErrorCode.ProtocolMismatch, "Invalid Host response.");
    internal static FilewayException SafeError(ErrorCode code, string message) => new(new ErrorInfo(code, message));
}
