using System.IO.Compression;
using System.Security;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;

namespace Fileway.Infrastructure.Services;

public sealed partial class FilewayClientServices : IArchiveBrowseService
{
    internal const long MaximumArchiveBytes = 64 * 1024 * 1024;
    internal const long MaximumExtractTotalBytes = 1L * 1024 * 1024 * 1024;
    internal const int MaximumEntries = 5000;

    async Task<IArchiveBrowseSession> IArchiveBrowseService.OpenAsync(RemoteResourceRef archive,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(archive);
        var lease = (ResourceLease)await AcquireCachedAsync(archive, cancellationToken).ConfigureAwait(false);
        HttpResponseMessage? response = null;
        MemoryStream? content = null;
        ZipArchive? zip = null;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lease.State.LifetimeToken);
        try
        {
            response = await _images.GetAsync(lease.Address, HttpCompletionOption.ResponseHeadersRead, linked.Token).ConfigureAwait(false);
            CheckStatus((int)response.StatusCode);
            if (response.Content.Headers.ContentLength > MaximumArchiveBytes)
                throw Safe(ErrorCode.ResponseTooLarge, "压缩包超过本次浏览的 64 MB 上限，请下载后解压。");
            content = new MemoryStream();
            await using var input = await response.Content.ReadAsStreamAsync(linked.Token).ConfigureAwait(false);
            byte[] buffer = new byte[81920];
            while (true)
            {
                int count = await input.ReadAsync(buffer, linked.Token).ConfigureAwait(false);
                if (count == 0) break;
                if (content.Length + count > MaximumArchiveBytes)
                    throw Safe(ErrorCode.ResponseTooLarge, "压缩包超过本次浏览的 64 MB 上限，请下载后解压。");
                await content.WriteAsync(buffer.AsMemory(0, count), linked.Token).ConfigureAwait(false);
            }
            await RefreshSessionAsync(lease.State, linked.Token).ConfigureAwait(false);
            RequireDownload(lease.State);
            content.Position = 0;
            zip = new ZipArchive(content, ZipArchiveMode.Read, leaveOpen: false);
            var entries = new List<ArchiveEntryInfo>();
            long total = 0;
            foreach (var entry in zip.Entries)
            {
                if (entries.Count >= MaximumEntries)
                    throw Safe(ErrorCode.ResponseTooLarge, $"压缩包条目超过 {MaximumEntries} 个，请下载后解压。");
                long size = Math.Max(0, entry.Length);
                total += size;
                entries.Add(new ArchiveEntryInfo(entries.Count, entry.FullName, size, Math.Max(0, entry.CompressedLength),
                    entry.FullName.EndsWith('/'), entry.LastWriteTime));
            }
            var session = new ZipBrowseSession(archive, lease, zip, content, response, entries, total);
            response = null; content = null; zip = null;
            return session;
        }
        catch (Exception error) when (error is InvalidDataException)
        {
            await ReleaseAsync(zip, content, response, lease).ConfigureAwait(false);
            throw Safe(ErrorCode.ProtocolMismatch, "文件不是有效的 ZIP 压缩包，无法浏览。");
        }
        catch (FilewayException) { await ReleaseAsync(zip, content, response, lease).ConfigureAwait(false); throw; }
        catch (OperationCanceledException)
        {
            await ReleaseAsync(zip, content, response, lease).ConfigureAwait(false);
            var code = cancellationToken.IsCancellationRequested ? ErrorCode.Canceled :
                lease.State.LifetimeToken.IsCancellationRequested ? ErrorCode.SourceChanged : ErrorCode.Timeout;
            throw Safe(code, Message(code));
        }
        catch (Exception error) when (error is HttpRequestException or IOException or ObjectDisposedException)
        {
            await ReleaseAsync(zip, content, response, lease).ConfigureAwait(false);
            throw Safe(ErrorCode.NetworkUnavailable, "无法读取压缩包内容，请检查连接后重试。");
        }
    }

    private static async Task ReleaseAsync(ZipArchive? zip, MemoryStream? content, HttpResponseMessage? response, ResourceLease lease)
    {
        try { zip?.Dispose(); } catch (Exception) { }
        try { content?.Dispose(); } catch (Exception) { }
        try { response?.Dispose(); } catch (Exception) { }
        await lease.DisposeAsync().ConfigureAwait(false);
    }

    /// <summary>
    /// Read-only session over one fully buffered remote ZIP. Extraction enforces the zip-slip
    /// boundary, per-entry and total size budgets, and never overwrites existing targets.
    /// </summary>
    private sealed class ZipBrowseSession(RemoteResourceRef archive, ResourceLease lease,
        ZipArchive zip, MemoryStream content, HttpResponseMessage response, IReadOnlyList<ArchiveEntryInfo> entries,
        long totalUncompressed) : IArchiveBrowseSession
    {
        private readonly object _gate = new();
        private readonly SemaphoreSlim _archiveAccess = new(1, 1);
        private bool _released;
        private Task? _disposeTask;

        public RemoteResourceRef Archive { get; } = archive;
        public IReadOnlyList<ArchiveEntryInfo> Entries { get; } = entries;
        public long TotalUncompressedBytes { get; } = totalUncompressed;

        public async Task<ArchiveExtractResult> ExtractAsync(IReadOnlyList<int> indexes, string targetDirectory,
            CancellationToken cancellationToken, IProgress<ArchiveExtractItem>? progress = null)
        {
            ArgumentNullException.ThrowIfNull(indexes);
            if (indexes.Count is 0 or > 1000) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
            await _archiveAccess.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                EnsureOpen();
                return await ExtractCoreAsync(indexes, targetDirectory, cancellationToken, progress).ConfigureAwait(false);
            }
            finally { _archiveAccess.Release(); }
        }

        private async Task<ArchiveExtractResult> ExtractCoreAsync(IReadOnlyList<int> indexes, string targetDirectory,
            CancellationToken cancellationToken, IProgress<ArchiveExtractItem>? progress)
        {
            string target = Fileway.Infrastructure.Persistence.StorageSettingsValidation.DirectoryPath(targetDirectory);
            Directory.CreateDirectory(target);
            int extracted = 0, skipped = 0, failed = 0;
            long budget = MaximumExtractTotalBytes;
            var items = new List<ArchiveExtractItem>();
            void Report(ArchiveExtractItem item)
            {
                items.Add(item);
                try { progress?.Report(item); } catch (Exception) { }
            }
            foreach (int index in indexes)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (index < 0 || index >= Entries.Count) throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
                var info = Entries[index];
                if (info.IsDirectory) { Report(new(index, info.FullName, ArchiveExtractOutcome.Skipped)); skipped++; continue; }
                if (info.Size > budget)
                {
                    Report(new(index, info.FullName, ArchiveExtractOutcome.Failed, "超过本次解压剩余字节预算。"));
                    failed++;
                    continue;
                }
                try
                {
                    string? relative = SafeRelativePath(info.FullName);
                    if (relative is null)
                    {
                        Report(new(index, info.FullName, ArchiveExtractOutcome.Failed, "条目路径不安全，已拒绝解压。"));
                        failed++;
                        continue;
                    }
                    string destination = Path.Combine(target, relative.Replace('/', System.IO.Path.DirectorySeparatorChar));
                    Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                    if (File.Exists(destination))
                    {
                        Report(new(index, info.FullName, ArchiveExtractOutcome.Skipped, "目标已存在，未覆盖。"));
                        skipped++;
                        continue;
                    }
                    if (!await PublishEntryAsync(zip.Entries[index], destination, info.Size, cancellationToken).ConfigureAwait(false))
                    {
                        Report(new(index, info.FullName, ArchiveExtractOutcome.Skipped, "目标已存在，未覆盖。"));
                        skipped++;
                        continue;
                    }
                    budget -= info.Size;
                    extracted++;
                    Report(new(index, info.FullName, ArchiveExtractOutcome.Extracted));
                }
                catch (Exception error) when (error is IOException or InvalidDataException or UnauthorizedAccessException or SecurityException or NotSupportedException)
                {
                    failed++;
                    Report(new(index, info.FullName, ArchiveExtractOutcome.Failed, error.Message));
                }
            }
            return new ArchiveExtractResult(extracted, skipped, failed, items);
        }

        private static async Task<bool> PublishEntryAsync(ZipArchiveEntry entry, string destination,
            long expectedSize, CancellationToken cancellationToken)
        {
            // Stage beside the final file so publication is a same-volume rename.
            string staging = Path.Combine(Path.GetDirectoryName(destination)!, $".fileway-archive-{Guid.NewGuid():N}.partial");
            var ownsStaging = false;
            try
            {
                await using (var input = entry.Open())
                await using (var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous))
                {
                    ownsStaging = true;
                    byte[] buffer = new byte[81920];
                    long written = 0;
                    int count;
                    while ((count = await input.ReadAsync(buffer, cancellationToken).ConfigureAwait(false)) != 0)
                    {
                        written += count;
                        if (written > expectedSize) throw new InvalidDataException("条目大小与目录声明不符。");
                        await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken).ConfigureAwait(false);
                    }
                    if (written != expectedSize) throw new InvalidDataException("条目大小与目录声明不符。");
                    await output.FlushAsync(cancellationToken).ConfigureAwait(false);
                }
                cancellationToken.ThrowIfCancellationRequested();
                try { File.Move(staging, destination, overwrite: false); }
                catch (IOException) when (File.Exists(destination)) { return false; }
                ownsStaging = false;
                return true;
            }
            finally
            {
                if (ownsStaging)
                    try { File.Delete(staging); }
                    catch (Exception error) when (error is IOException or UnauthorizedAccessException or SecurityException) { }
            }
        }

        public async Task<ArchiveEntryContent> ReadEntryAsync(int index, long maximumBytes, CancellationToken cancellationToken)
        {
            if (index < 0 || index >= Entries.Count || maximumBytes <= 0)
                throw Safe(ErrorCode.InvalidRequest, Message(ErrorCode.InvalidRequest));
            var info = Entries[index];
            if (info.IsDirectory) throw Safe(ErrorCode.InvalidRequest, "文件夹不能预览。");
            if (info.Size > maximumBytes) throw Safe(ErrorCode.ResponseTooLarge, "条目超过本次预览的大小上限。");
            await _archiveAccess.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                EnsureOpen();
                return await ReadEntryCoreAsync(index, info, maximumBytes, cancellationToken).ConfigureAwait(false);
            }
            finally { _archiveAccess.Release(); }
        }

        private async Task<ArchiveEntryContent> ReadEntryCoreAsync(int index, ArchiveEntryInfo info,
            long maximumBytes, CancellationToken cancellationToken)
        {
            try
            {
                var entry = zip.Entries[index];
                await using var input = entry.Open();
                using var buffer = new MemoryStream();
                byte[] chunk = new byte[81920];
                while (true)
                {
                    int read = await input.ReadAsync(chunk, cancellationToken).ConfigureAwait(false);
                    if (read == 0) break;
                    if (buffer.Length + read > maximumBytes)
                        throw Safe(ErrorCode.ResponseTooLarge, "条目超过本次预览的大小上限。");
                    buffer.Write(chunk, 0, read);
                }
                return new ArchiveEntryContent(index, info.FullName, buffer.ToArray());
            }
            catch (InvalidDataException) { throw Safe(ErrorCode.ProtocolMismatch, "条目内容无法读取。"); }
            catch (Exception error) when (error is IOException or ObjectDisposedException or NotSupportedException)
            {
                throw Safe(ErrorCode.NetworkUnavailable, "无法读取压缩包内容，请检查连接后重试。");
            }
        }

        /// <summary>Canonical slash path inside the archive root; traversal, drives and control characters are refused.</summary>
        private static string? SafeRelativePath(string fullName)
        {
            if (fullName.Length == 0 || fullName.Length > 1024) return null;
            string normalized = fullName.Replace('\\', '/');
            if (normalized.Contains('\0') || normalized.StartsWith('/') || normalized.Contains("//", StringComparison.Ordinal)) return null;
            foreach (var segment in normalized.Split('/'))
            {
                if (segment.Length == 0 || segment is "." or ".." || segment.IndexOfAny(System.IO.Path.GetInvalidFileNameChars()) >= 0)
                    return null;
            }
            return normalized;
        }

        private void EnsureOpen()
        {
            lock (_gate)
                if (_released) throw Safe(ErrorCode.SourceChanged, Message(ErrorCode.SourceChanged));
        }

        public ValueTask DisposeAsync()
        {
            lock (_gate)
            {
                _released = true;
                return new ValueTask(_disposeTask ??= DisposeResourcesAsync());
            }
        }

        private async Task DisposeResourcesAsync()
        {
            await _archiveAccess.WaitAsync().ConfigureAwait(false);
            try
            {
                FilewayException? failure = null;
                try
                {
                    zip.Dispose();
                    content.Dispose();
                    response.Dispose();
                }
                catch (Exception) { failure = Safe(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown)); }
                if (!await lease.TryDisposeConfirmedAsync().ConfigureAwait(false))
                    failure = Safe(ErrorCode.OutcomeUnknown, Message(ErrorCode.OutcomeUnknown));
                if (failure is not null) throw failure;
            }
            finally { _archiveAccess.Release(); }
        }
    }
}
