using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using Fileway.Core.Contracts;
using Fileway.Core.Integration;
using Fileway.Infrastructure.Persistence;
using Fileway.Infrastructure.Transfers;
using Microsoft.Win32.SafeHandles;

namespace Fileway.Infrastructure.Tests.Transfers;

public sealed partial class WindowsDownloadFileAccessTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task KnownFinalFileFailsBeforeAllocatingAPartialEvenWhileItsDataIsExclusivelyHeld(bool empty)
    {
        using var fixture = new NativeFixture();
        byte[] bytes = empty ? [] : StrangerPayload;
        await File.WriteAllBytesAsync(fixture.FinalPath, bytes, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, fixture.FinalPath);
        int names = 0;
        var access = WithPartialName(() => { Interlocked.Increment(ref names); return ForcedName; });
        using (var unrelated = new FileStream(fixture.FinalPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            // Attribute-only access can coexist with an exclusive data handle. The probe must not request content access.
            var conflict = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(fixture.FinalPath, Token));
            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            AssertSafeFailure(conflict, fixture.Root);
            Assert.Equal(0, names);
            Assert.Equal(fixture.FinalPath, Assert.Single(Directory.GetFileSystemEntries(fixture.DestinationDirectory)));
            Assert.Equal(bytes.Length, unrelated.Length);
        }
        Assert.Equal(bytes, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, fixture.FinalPath));
        AssertPreflightParentsReleased(fixture);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task KnownFinalDirectoryOrJunctionPreservesTheEntryAndTargetBeforeAnyPartialAllocation(bool junction)
    {
        using var fixture = new NativeFixture();
        string target = junction ? Path.Combine(fixture.DestinationDirectory, "target") : fixture.FinalPath;
        Directory.CreateDirectory(target);
        string marker = Path.Combine(target, "marker.bin");
        await File.WriteAllBytesAsync(marker, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, marker);
        var targetIdentity = NativePreflightEntryIdentity(target);
        if (junction) await RunFixtureCommandAsync(fixture.DestinationDirectory, "mklink /j download.bin target");
        try
        {
            var entryIdentity = NativePreflightEntryIdentity(fixture.FinalPath);
            FileAttributes attributes = File.GetAttributes(fixture.FinalPath);
            string[] entries = PreflightEntries(fixture);
            int names = 0;
            var access = WithPartialName(() => { Interlocked.Increment(ref names); return ForcedName; });

            var conflict = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(fixture.FinalPath, Token));

            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            AssertSafeFailure(conflict, fixture.Root);
            Assert.Equal(0, names);
            Assert.Equal(entries, PreflightEntries(fixture));
            Assert.Equal(entryIdentity, NativePreflightEntryIdentity(fixture.FinalPath));
            Assert.Equal(targetIdentity, NativePreflightEntryIdentity(target));
            Assert.Equal(attributes, File.GetAttributes(fixture.FinalPath));
            Assert.Equal(junction, (attributes & FileAttributes.ReparsePoint) != 0);
            Assert.Equal(marker, Assert.Single(Directory.GetFileSystemEntries(target)));
            Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(marker, Token));
            Assert.Equal(stranger, await SnapshotAsync(fixture, marker));
            AssertPreflightParentsReleased(fixture);
        }
        finally
        {
            if (junction) Directory.Delete(fixture.FinalPath); // Remove only the fixture junction, never its target.
        }
    }

    [Fact]
    public async Task KnownDanglingFinalJunctionIsAConflictWithoutFollowingOrRecreatingItsTarget()
    {
        using var fixture = new NativeFixture();
        string target = Path.Combine(fixture.DestinationDirectory, "target");
        Directory.CreateDirectory(target);
        await RunFixtureCommandAsync(fixture.DestinationDirectory, "mklink /j download.bin target");
        Directory.Delete(target); // Only this empty fixture target is removed; the final reparse entry remains.
        try
        {
            var entryIdentity = NativePreflightEntryIdentity(fixture.FinalPath);
            string[] entries = PreflightEntries(fixture);
            int names = 0;
            var access = WithPartialName(() => { Interlocked.Increment(ref names); return ForcedName; });

            var conflict = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(fixture.FinalPath, Token));

            Assert.Equal(ErrorCode.Conflict, conflict.Error.Code);
            AssertSafeFailure(conflict, fixture.Root);
            Assert.Equal(0, names);
            Assert.Equal(entries, PreflightEntries(fixture));
            Assert.Equal(entryIdentity, NativePreflightEntryIdentity(fixture.FinalPath));
            Assert.True((File.GetAttributes(fixture.FinalPath) & FileAttributes.ReparsePoint) != 0);
            Assert.False(Directory.Exists(target));
            Assert.False(File.Exists(target));
            AssertPreflightParentsReleased(fixture);
        }
        finally { Directory.Delete(fixture.FinalPath); }
    }

    [Fact]
    public async Task DeniedFinalMetadataAccessFailsClosedBeforeAnyPartialAllocationAndReleasesParents()
    {
        using var fixture = new NativeFixture();
        await File.WriteAllBytesAsync(fixture.FinalPath, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, fixture.FinalPath);
        var file = new FileInfo(fixture.FinalPath);
        FileSecurity originalAccess = file.GetAccessControl(AccessControlSections.Access);
        byte[] originalDescriptor = originalAccess.GetSecurityDescriptorBinaryForm();
        using var identity = WindowsIdentity.GetCurrent();
        SecurityIdentifier sid = identity.User ?? throw new InvalidOperationException("Synthetic fixture requires an authenticated Windows user.");
        // Deny SYNCHRONIZE too: Windows can grant leaf attributes through the parent's list-directory permission.
        // FileSystemAccessRule removes SYNCHRONIZE from ordinary Deny rules, so retain this exact mask in a raw ACE.
        var deniedDescriptor = new RawSecurityDescriptor(originalDescriptor, 0);
        RawAcl deniedAcl = deniedDescriptor.DiscretionaryAcl ?? throw new InvalidOperationException("Synthetic fixture requires a discretionary ACL.");
        deniedAcl.InsertAce(0, new CommonAce(AceFlags.None, AceQualifier.AccessDenied,
            (int)(FileSystemRights.ReadAttributes | FileSystemRights.Synchronize), sid, isCallback: false, opaque: null));
        byte[] deniedBinary = new byte[deniedDescriptor.BinaryLength];
        deniedDescriptor.GetBinaryForm(deniedBinary, 0);
        var deniedAccess = new FileSecurity();
        deniedAccess.SetSecurityDescriptorBinaryForm(deniedBinary, AccessControlSections.Access);
        int names = 0;
        var access = WithPartialName(() => { Interlocked.Increment(ref names); return ForcedName; });
        try
        {
            file.SetAccessControl(deniedAccess);

            var denied = await Assert.ThrowsAsync<FilewayException>(() => access.CreateFreshAsync(fixture.FinalPath, Token));

            Assert.Equal(ErrorCode.PermissionDenied, denied.Error.Code);
            AssertSafeFailure(denied, fixture.Root);
            Assert.Equal(0, names);
            Assert.Equal(fixture.FinalPath, Assert.Single(PreflightEntries(fixture)));
            AssertPreflightParentsReleased(fixture);
        }
        finally
        {
            originalAccess.SetSecurityDescriptorBinaryForm(originalDescriptor, AccessControlSections.Access);
            file.SetAccessControl(originalAccess);
        }
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, fixture.FinalPath));
    }

    [Fact]
    public async Task RealNativeRunnerKnownFinalConflictsBeforeLeaseHttpBodyOrChecksumAndPersistsNoOwnedFiles()
    {
        using var fixture = new NativeFixture();
        await File.WriteAllBytesAsync(fixture.FinalPath, StrangerPayload, Token);
        TransferSourceSnapshot stranger = await SnapshotAsync(fixture, fixture.FinalPath);
        var store = new InMemoryProfileStore();
        var saved = new SavedConnection(new(Guid.NewGuid(), Guid.NewGuid(), "native preflight fixture", new("https://fixture.invalid/"),
            NetworkMode.SystemNetwork, new(ProxyMode.Direct)), AuthenticationMode.Password, "fixture", "7", false, DateTimeOffset.UtcNow);
        await store.SaveAsync(saved, Token);
        var plan = new TransferPlan(new(saved.Account, Guid.NewGuid()), TransferDirection.Download,
            new(saved.Account, "/fixture.bin", "fixture.bin", "/fixture.bin"), fixture.FinalPath);
        await store.CreateTransferAsync(plan, Token);
        var sources = new NativePreflightSources();
        var http = new NativePreflightHttp();
        int names = 0;
        var access = WithPartialName(() => { Interlocked.Increment(ref names); return ForcedName; });
        await using var runner = new FileDownloadRunner(store, sources, access,
            new WindowsUploadFileAccess(fixture.ExecutionDirectory), http, new() { BufferSizeBytes = 4 });

        var result = await runner.RunAsync(plan.Reference, Token);

        Assert.Equal(TransferPhase.Conflict, result.Checkpoint.Phase);
        Assert.Equal(ErrorCode.Conflict, result.Checkpoint.Error!.Code);
        AssertSafeDisplay(result.Checkpoint.Error.Message, fixture.Root);
        Assert.Null(result.Run);
        Assert.Null(result.Checkpoint.Files);
        Assert.Null(result.Checkpoint.LocalSha256);
        Assert.Null(result.Checkpoint.ServerSha256);
        Assert.Null(result.Checkpoint.ConfirmedOffset);
        Assert.Equal(TransferUncertainty.None, result.Checkpoint.Uncertainty);
        Assert.Equal(result, await store.GetTransferAsync(plan.Reference, Token));
        Assert.False(await store.HasProtectedTransfersAsync(saved.Account, Token));
        Assert.Equal(1, sources.PrepareCalls);
        Assert.Equal(0, sources.AcquireCalls);
        Assert.Equal(0, sources.ChecksumCalls);
        Assert.Equal(0, http.Requests);
        Assert.Equal(0, http.BodyReads);
        Assert.Equal(0, names);
        Assert.Equal(fixture.FinalPath, Assert.Single(PreflightEntries(fixture)));
        Assert.Equal(StrangerPayload, await File.ReadAllBytesAsync(fixture.FinalPath, Token));
        Assert.Equal(stranger, await SnapshotAsync(fixture, fixture.FinalPath));
        AssertPreflightParentsReleased(fixture);
    }

    private static string[] PreflightEntries(NativeFixture fixture) =>
        Directory.GetFileSystemEntries(fixture.DestinationDirectory).Order(StringComparer.Ordinal).ToArray();

    private static void AssertPreflightParentsReleased(NativeFixture fixture)
    {
        string movedOutput = Path.Combine(fixture.Root, "released-output");
        string movedRoot = fixture.Root + "-released";
        try
        {
            Directory.Move(fixture.DestinationDirectory, movedOutput);
            Directory.Move(movedOutput, fixture.DestinationDirectory);
            Directory.Move(fixture.Root, movedRoot);
            Directory.Move(movedRoot, fixture.Root);
        }
        finally
        {
            if (Directory.Exists(movedRoot) && !Directory.Exists(fixture.Root)) Directory.Move(movedRoot, fixture.Root);
            if (Directory.Exists(movedOutput) && !Directory.Exists(fixture.DestinationDirectory))
                Directory.Move(movedOutput, fixture.DestinationDirectory);
        }
    }

    private static (ulong Volume, ulong Low, ulong High) NativePreflightEntryIdentity(string path)
    {
        // Reuse the existing native declarations through reflection, as the test-only constructor seam does.
        // Open the entry itself with attributes-only access, including a dangling reparse entry; never its target.
        Type native = typeof(WindowsUploadFileAccess).Assembly.GetType("Fileway.Infrastructure.Transfers.UploadFileNative", throwOnError: true)!;
        const BindingFlags flags = BindingFlags.Static | BindingFlags.NonPublic;
        MethodInfo open = Assert.IsAssignableFrom<MethodInfo>(native.GetMethod("CreateFileW", flags));
        using var handle = Assert.IsType<SafeFileHandle>(open.Invoke(null,
            [@"\\?\" + path, (uint)0x80, (uint)7, (nint)0, (uint)3, (uint)0x02300000, (nint)0]));
        Assert.False(handle.IsInvalid);
        MethodInfo observe = Assert.IsAssignableFrom<MethodInfo>(native.GetMethod("GetIdentityInformation", flags));
        Type informationType = observe.GetParameters()[2].ParameterType.GetElementType()!;
        object?[] arguments = [handle, 18, null, checked((uint)Marshal.SizeOf(informationType))];
        Assert.True(Assert.IsType<bool>(observe.Invoke(null, arguments)));
        object information = Assert.IsAssignableFrom<object>(arguments[2]);
        Assert.Equal(informationType, information.GetType());
        return (Read("VolumeSerialNumber"), Read("FileIdLow"), Read("FileIdHigh"));

        ulong Read(string field) => Assert.IsType<ulong>(informationType.GetField(field, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(information));
    }

    // This valid source/HTTP fixture would let the original provider receive, verify and then collide at publication.
    // Its zero-work assertions therefore exercise the early-preflight boundary, not invalid fixture metadata.
    private sealed class NativePreflightSources : IDownloadSourceProvider
    {
        private static readonly DateTimeOffset Modified = new(2026, 10, 12, 0, 0, 0, TimeSpan.Zero);
        internal int PrepareCalls;
        internal int AcquireCalls;
        internal int ChecksumCalls;
        public Task<RemoteFile> PrepareDownloadAsync(RemoteResourceRef resource, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref PrepareCalls);
            return Task.FromResult(new RemoteFile(resource, false, Payload.LongLength, Modified));
        }
        public Task<IDownloadLease> AcquireDownloadAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref AcquireCalls);
            return Task.FromResult<IDownloadLease>(new NativePreflightLease(request));
        }
        public Task<FileChecksumResult> CalculateDownloadChecksumAsync(DownloadLeaseRequest request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Interlocked.Increment(ref ChecksumCalls);
            return Task.FromResult(new FileChecksumResult(request.Source, ChecksumAlgorithm.Sha256, Sha256(Payload),
                ChecksumComparison.NotRequested, Payload.LongLength, Modified));
        }
    }

    private sealed class NativePreflightLease(DownloadLeaseRequest request) : IDownloadLease
    {
        public RemoteResourceRef Resource => request.Source;
        public Uri Address { get; } = new("http://127.0.0.1:32450/stream/private-download-capability");
        public long Length => request.Length;
        public string? SourceVersion => null;
        public bool SupportsResume => false;
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private sealed class NativePreflightHttp : HttpMessageHandler
    {
        internal int Requests;
        internal int BodyReads;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.Equal(HttpMethod.Get, request.Method);
            Interlocked.Increment(ref Requests);
            var content = new StreamContent(new NativePreflightBody(() => Interlocked.Increment(ref BodyReads)));
            content.Headers.ContentLength = Payload.LongLength;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }

    private sealed class NativePreflightBody(Action onRead) : MemoryStream(Payload, writable: false)
    {
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            onRead();
            return base.ReadAsync(buffer, cancellationToken);
        }
    }
}
