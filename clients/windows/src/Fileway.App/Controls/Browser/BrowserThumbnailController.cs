using Fileway.Core.Contracts;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Foundation;
using Windows.Graphics.Imaging;

namespace Fileway.App.Controls.Browser;

/// <summary>Owns previews only for realized containers intersecting the active browser viewport.</summary>
internal sealed class BrowserThumbnailController : IDisposable
{
    private const long EncodedByteBudget = 8L * 1024 * 1024;
    private const uint MaximumEdge = 512;
    private const double PixelBudget = 512 * 512;
    // Sixteen decoded previews cap the retained pixel payload at 16 MiB.
    private const int RetainedPreviewLimit = 16;
    private readonly ListViewBase[] _views;
    private readonly Dictionary<SelectorItem, ContainerState> _containers = [];
    private readonly Dictionary<ListViewBase, ScrollViewer> _scrolls = [];
    private readonly SemaphoreSlim _parallel = new(4, 4);
    private readonly LinkedList<(RemoteFile File, SoftwareBitmapSource Source)> _recentPreviews = [];
    private readonly CancellationToken _lifetime;
    private IThumbnailContentService? _service;
    private ConnectionSession? _session;
    private long _generation;
    private bool _refreshQueued;
    private bool _waitingForTemplate;
    private bool _disposed;
    private int _outstanding;
    private bool _parallelDisposed;

    public BrowserThumbnailController(CancellationToken lifetime, params ListViewBase[] views)
    {
        _lifetime = lifetime; _views = views;
        foreach (var view in views)
        {
            view.ContainerContentChanging += ContainerContentChanging;
            view.Loaded += ViewLoaded;
            view.Unloaded += ViewUnloaded;
            view.SizeChanged += ViewSizeChanged;
            view.LayoutUpdated += ViewLayoutUpdated;
        }
    }

    public void SetContext(IThumbnailContentService? service, ConnectionSession? session)
    {
        if (_disposed || ReferenceEquals(_service, service) && _session?.Id == session?.Id && _session?.Account == session?.Account) return;
        _service = service; _session = session;
        Invalidate(dropPreviews: true);
    }

    // Call before a directory replacement or filter rebuild, including same-account refreshes.
    public void Invalidate(bool dropPreviews = false)
    {
        if (_disposed) return;
        ++_generation;
        if (dropPreviews) _recentPreviews.Clear();
        foreach (var state in _containers.Values) Stop(state);
        QueueRefresh();
    }

    public void RefreshVisibility()
    {
        if (_disposed) return;
        // Cancel the hidden view immediately, then remeasure the newly shown view after layout.
        foreach (var state in _containers.Values)
            if (!IsVisible(state)) Stop(state);
        QueueRefresh();
    }

    private void ContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (_disposed) return;
        var container = args.ItemContainer;
        if (_containers.TryGetValue(container, out var previous))
        {
            if (!args.InRecycleQueue && ReferenceEquals(previous.Row, args.Item)) { QueueRefresh(); return; }
            ReleaseContainer(previous); _containers.Remove(container);
        }
        if (!args.InRecycleQueue && args.Item is FileRow row && (row.IsImage || row.IsVideo))
        {
            _containers.Add(container, new ContainerState(sender, container, row));
            QueueRefresh();
        }
    }

    private void ViewLoaded(object sender, RoutedEventArgs args)
    {
        if (sender is ListViewBase view) AttachScrollViewer(view);
        QueueRefresh();
    }

    private void AttachScrollViewer(ListViewBase view)
    {
        if (_scrolls.ContainsKey(view) || Find<ScrollViewer>(view) is not { } scroll) return;
        _scrolls.Add(view, scroll);
        scroll.ViewChanged += ScrollViewChanged;
    }

    private void ViewUnloaded(object sender, RoutedEventArgs args)
    {
        if (sender is not ListViewBase view) return;
        if (_scrolls.Remove(view, out var scroll)) scroll.ViewChanged -= ScrollViewChanged;
        foreach (var state in _containers.Values.Where(state => ReferenceEquals(state.View, view))) ReleaseContainer(state);
    }

    private void ViewSizeChanged(object sender, SizeChangedEventArgs args) => QueueRefresh();
    private void ViewLayoutUpdated(object? sender, object args)
    {
        // LayoutUpdated does not always supply the list as sender.
        foreach (var view in _views)
            if (view.IsLoaded && view.Visibility == Visibility.Visible) AttachScrollViewer(view);
        if (_waitingForTemplate) QueueRefresh();
    }
    private void ScrollViewChanged(object? sender, ScrollViewerViewChangedEventArgs args) => RefreshVisibility();

    private void QueueRefresh()
    {
        if (_disposed || _refreshQueued || _containers.Count == 0) return;
        _refreshQueued = true;
        if (!_views[0].DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Low, () =>
        {
            _refreshQueued = false;
            if (!_disposed) Refresh();
        })) _refreshQueued = false;
    }

    private void Refresh()
    {
        _waitingForTemplate = false;
        foreach (var state in _containers.Values)
        {
            if (_service is null || _session is null || state.Resource.Account != _session.Account)
            { Stop(state); continue; }
            if (!IsVisible(state))
            {
                Stop(state);
                // ContentChanging can arrive before the container has completed its first layout.
                if (state.View.IsLoaded && state.View.Visibility == Visibility.Visible &&
                    (!state.Container.IsLoaded || state.Container.ActualWidth <= 0 || state.Container.ActualHeight <= 0))
                    _waitingForTemplate = true;
                continue;
            }
            if (state.Attempted) continue;
            // ContentChanging can precede template creation. LayoutUpdated retries only visual lookup.
            state.Image ??= Find<Image>(state.Container, "ThumbnailImage");
            state.Icon ??= Find<FrameworkElement>(state.Container, "ThumbnailIcon");
            if (state.Image is null || state.Icon is null)
            {
                _waitingForTemplate = true;
                continue;
            }
            state.Attempted = true;
            if (TryShowRecentPreview(state)) continue;
            var request = new Request(_generation, CancellationTokenSource.CreateLinkedTokenSource(_lifetime));
            state.Request = request;
            ++_outstanding;
            _ = LoadAsync(state, request, _service);
        }
    }

    private bool TryShowRecentPreview(ContainerState state)
    {
        for (var node = _recentPreviews.First; node is not null; node = node.Next)
        {
            if (node.Value.File != state.Row.File) continue;
            _recentPreviews.Remove(node);
            _recentPreviews.AddFirst(node);
            ShowPreview(state, node.Value.Source);
            return true;
        }
        return false;
    }

    private static void ShowPreview(ContainerState state, SoftwareBitmapSource source)
    {
        state.Image!.Source = source;
        state.Image.Visibility = Visibility.Visible;
        state.Icon!.Visibility = Visibility.Collapsed;
    }

    private static bool IsVisible(ContainerState state)
    {
        var view = state.View;
        if (!view.IsLoaded || view.Visibility != Visibility.Visible || !state.Container.IsLoaded ||
            state.Container.ActualWidth <= 0 || state.Container.ActualHeight <= 0 ||
            !ReferenceEquals(state.Container.Content, state.Row)) return false;
        try
        {
            var bounds = state.Container.TransformToVisual(view).TransformBounds(
                new Rect(0, 0, state.Container.ActualWidth, state.Container.ActualHeight));
            return bounds.Right > 0 && bounds.Bottom > 0 && bounds.Left < view.ActualWidth && bounds.Top < view.ActualHeight;
        }
        catch (ArgumentException) { return false; } // A container can be detached during recycling.
        catch (InvalidOperationException) { return false; }
        catch (System.Runtime.InteropServices.COMException) { return false; }
    }

    private bool Current(ContainerState state, Request request) => !_disposed && !request.Cancellation.IsCancellationRequested &&
        request.Generation == _generation && _session?.Account == state.Resource.Account &&
        _containers.TryGetValue(state.Container, out var current) && ReferenceEquals(current, state) &&
        ReferenceEquals(state.Request, request) && state.Row.File.Resource == state.Resource && IsVisible(state);

    private async Task LoadAsync(ContainerState state, Request request, IThumbnailContentService service)
    {
        var token = request.Cancellation.Token;
        var acquired = false;
        try
        {
            await _parallel.WaitAsync(token); acquired = true;
            if (!Current(state, request)) return;
            using var bitmap = await Task.Run(() => DecodeAsync(service, state.Resource, token), token);
            if (!Current(state, request)) return;
            // WinUI source creation/upload is the only image work performed on the UI thread.
            var source = new SoftwareBitmapSource();
            await source.SetBitmapAsync(bitmap);
            if (!Current(state, request)) return;
            if (_recentPreviews.Count >= RetainedPreviewLimit) _recentPreviews.RemoveLast();
            _recentPreviews.AddFirst((state.Row.File, source));
            ShowPreview(state, source);
        }
        catch (OperationCanceledException) { }
        catch (Exception) { /* Preview is best effort: keep the type icon and never request raw content. */ }
        finally
        {
            if (ReferenceEquals(state.Request, request)) state.Request = null;
            request.Cancellation.Dispose();
            if (acquired) _parallel.Release();
            --_outstanding;
            DisposeDrainedSemaphore();
        }
    }

    private static async Task<SoftwareBitmap> DecodeAsync(IThumbnailContentService service, RemoteResourceRef resource, CancellationToken token)
    {
        using var encoded = new MemoryStream();
        await using (var content = await service.OpenThumbnailAsync(resource, token).ConfigureAwait(false))
        {
            token.ThrowIfCancellationRequested();
            var mediaType = content.ContentType?.Split(';')[0].Trim();
            if (content.Resource != resource || content.Length > EncodedByteBudget ||
                !StringComparer.OrdinalIgnoreCase.Equals(mediaType, "image/jpeg") && !StringComparer.OrdinalIgnoreCase.Equals(mediaType, "image/png"))
                throw new InvalidDataException("Invalid bounded thumbnail content.");
            var buffer = new byte[64 * 1024];
            int count;
            while ((count = await content.Content.ReadAsync(buffer.AsMemory(), token).ConfigureAwait(false)) != 0)
            {
                if (encoded.Length + count > EncodedByteBudget) throw new InvalidDataException("Thumbnail byte budget exceeded.");
                await encoded.WriteAsync(buffer.AsMemory(0, count), token).ConfigureAwait(false);
            }
        }
        token.ThrowIfCancellationRequested();
        encoded.Position = 0;
        using var random = encoded.AsRandomAccessStream();
        var decoder = await BitmapDecoder.CreateAsync(random).AsTask(token).ConfigureAwait(false);
        if (decoder.PixelWidth == 0 || decoder.PixelHeight == 0 ||
            decoder.DecoderInformation.CodecId != BitmapDecoder.JpegDecoderId && decoder.DecoderInformation.CodecId != BitmapDecoder.PngDecoderId)
            throw new InvalidDataException("Invalid thumbnail image format.");
        var scale = Math.Min(1, Math.Min(Math.Sqrt(PixelBudget / ((double)decoder.PixelWidth * decoder.PixelHeight)),
            (double)MaximumEdge / Math.Max(decoder.PixelWidth, decoder.PixelHeight)));
        var transform = new BitmapTransform
        {
            ScaledWidth = Math.Max(1, (uint)(decoder.PixelWidth * scale)),
            ScaledHeight = Math.Max(1, (uint)(decoder.PixelHeight * scale)),
            InterpolationMode = BitmapInterpolationMode.Fant,
        };
        return await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb).AsTask(token).ConfigureAwait(false);
    }

    private static void Stop(ContainerState state)
    {
        if (!state.Attempted) return;
        state.Request?.Cancellation.Cancel(); state.Request = null; state.Attempted = false;
        if (state.Image is not null) { state.Image.Source = null; state.Image.Visibility = Visibility.Collapsed; }
        if (state.Icon is not null) state.Icon.Visibility = Visibility.Visible;
    }

    private static void ReleaseContainer(ContainerState state)
    {
        Stop(state); state.Image = null; state.Icon = null;
    }

    private static T? Find<T>(DependencyObject root, string? name = null) where T : FrameworkElement
    {
        if (root is T match && (name is null || match.Name == name)) return match;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            if (Find<T>(VisualTreeHelper.GetChild(root, index), name) is { } child) return child;
        return null;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true; ++_generation;
        foreach (var state in _containers.Values) ReleaseContainer(state);
        _containers.Clear();
        _recentPreviews.Clear();
        foreach (var scroll in _scrolls.Values) scroll.ViewChanged -= ScrollViewChanged;
        _scrolls.Clear();
        foreach (var view in _views)
        {
            view.ContainerContentChanging -= ContainerContentChanging;
            view.Loaded -= ViewLoaded; view.Unloaded -= ViewUnloaded;
            view.SizeChanged -= ViewSizeChanged; view.LayoutUpdated -= ViewLayoutUpdated;
        }
        _service = null; _session = null;
        DisposeDrainedSemaphore();
    }

    private void DisposeDrainedSemaphore()
    {
        // Cancellation need not complete synchronously. The last outstanding operation owns final disposal.
        if (!_disposed || _outstanding != 0 || _parallelDisposed) return;
        _parallelDisposed = true; _parallel.Dispose();
    }

    private sealed class ContainerState(ListViewBase view, SelectorItem container, FileRow row)
    {
        public ListViewBase View { get; } = view;
        public SelectorItem Container { get; } = container;
        public FileRow Row { get; } = row;
        public RemoteResourceRef Resource { get; } = row.File.Resource;
        public Image? Image { get; set; }
        public FrameworkElement? Icon { get; set; }
        public bool Attempted { get; set; }
        public Request? Request { get; set; }
    }

    private sealed record Request(long Generation, CancellationTokenSource Cancellation);
}
