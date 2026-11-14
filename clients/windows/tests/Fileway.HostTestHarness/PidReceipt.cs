using System.Globalization;
using System.Text;

namespace Fileway.HostTestHarness;

/// <summary>A visible receipt is complete and has no writer handle remaining open.</summary>
internal static class PidReceipt
{
    internal static Task PublishAsync(string path, int processId, CancellationToken cancellationToken)
        => PublishAsync(path, processId, WriteAsync, cancellationToken);

    // Controlled writer seam verifies publication timing using this same helper;
    // process-lifetime and Job Object behavior are still tested by the real Host.
    internal static async Task PublishAsync(string path, int processId,
        Func<string, string, CancellationToken, Task> write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(processId);
        cancellationToken.ThrowIfCancellationRequested();
        string temporary = path + "." + Guid.NewGuid().ToString("N") + ".writing";
        try
        {
            await write(temporary, processId.ToString(CultureInfo.InvariantCulture), cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            // Same-directory, no-overwrite rename publishes only after the
            // private writer has closed. File.Exists now means readable, not
            // merely that File.WriteAllTextAsync has created its open file.
            File.Move(temporary, path, overwrite: false);
        }
        finally { File.Delete(temporary); }
    }

    private static async Task WriteAsync(string path, string text, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write,
            FileShare.None, bufferSize: 4096, useAsync: true);
        await stream.WriteAsync(Encoding.UTF8.GetBytes(text), cancellationToken).ConfigureAwait(false);
    }
}
