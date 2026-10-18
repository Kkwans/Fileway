using Fileway.Core.Contracts;

namespace Fileway.App;

/// <summary>
/// UI-thread-owned state for one transfer direction. Rows retain their first-observed order;
/// neither the loaded count nor a page boundary describes the complete transfer history.
/// </summary>
internal sealed class TransferCenterPageState<T>
{
    private readonly Func<T, TransferTaskRef> _key;
    private readonly Func<T, T, T> _prefer;
    private readonly Func<T, bool>? _acceptItem;
    private readonly List<T> _items = [];
    private readonly Dictionary<TransferTaskRef, int> _indexes = [];
    private readonly HashSet<string> _seenCursors = new(StringComparer.Ordinal);

    internal TransferCenterPageState(Func<T, TransferTaskRef> key, Func<T, T, T> prefer,
        Func<T, bool>? acceptItem = null)
    {
        ArgumentNullException.ThrowIfNull(key);
        ArgumentNullException.ThrowIfNull(prefer);
        _key = key;
        _prefer = prefer;
        _acceptItem = acceptItem;
        Items = _items.AsReadOnly();
    }

    internal IReadOnlyList<T> Items { get; }
    internal long Generation { get; private set; }
    internal bool IsLoading { get; private set; }
    internal bool HasLoaded { get; private set; }
    internal string? NextCursor { get; private set; }
    internal string? Error { get; private set; }
    internal bool CanLoadMore => !IsLoading && (!HasLoaded || NextCursor is not null);

    /// <summary>Retires old requests and rows when their account or activation is no longer current.</summary>
    internal void Reset()
    {
        BeginRefresh();
        _items.Clear();
        _indexes.Clear();
    }

    /// <summary>Restarts paging without discarding newer live rows or their evidence.</summary>
    internal void BeginRefresh()
    {
        ++Generation;
        IsLoading = false;
        HasLoaded = false;
        NextCursor = null;
        Error = null;
        _seenCursors.Clear();
    }

    /// <summary>The generation fences this individual request, including retries after a failed page.</summary>
    internal bool TryBeginLoad(out long generation, out string? cursor)
    {
        generation = Generation;
        cursor = NextCursor;
        if (!CanLoadMore) return false;
        generation = ++Generation;
        IsLoading = true;
        Error = null;
        return true;
    }

    /// <summary>
    /// Filters through the caller's exact-context predicate and merges by task identity. A repeated
    /// opaque cursor is a protocol error before any rows or cursor are committed; FailLoad can then
    /// keep the same request cursor available for an explicit retry.
    /// </summary>
    internal bool CompleteLoad(long generation, IEnumerable<T> items, string? nextCursor)
    {
        if (!IsCurrentLoad(generation)) return false;
        ArgumentNullException.ThrowIfNull(items);
        if (nextCursor is not null && _seenCursors.Contains(nextCursor))
            throw new FilewayException(new(ErrorCode.ProtocolMismatch, "传输记录分页游标重复，请刷新后重试。"));

        // Stage the whole page so an invalid page or projection cannot partially alter the rows.
        var merged = new List<T>(_items);
        var indexes = new Dictionary<TransferTaskRef, int>(_indexes);
        foreach (var item in items) Merge(merged, indexes, item);

        _items.Clear();
        _items.AddRange(merged);
        _indexes.Clear();
        foreach (var entry in indexes) _indexes.Add(entry.Key, entry.Value);
        if (nextCursor is not null) _seenCursors.Add(nextCursor);
        NextCursor = nextCursor;
        IsLoading = false;
        HasLoaded = true;
        Error = null;
        return true;
    }

    internal bool FailLoad(long generation, string error)
    {
        if (!IsCurrentLoad(generation)) return false;
        ArgumentNullException.ThrowIfNull(error);
        IsLoading = false;
        Error = error;
        return true;
    }

    /// <summary>Accepts a current-context event or command result without changing paging state.</summary>
    internal bool Upsert(T item) => Merge(_items, _indexes, item);
    internal void Remove(TransferTaskRef task)
    {
        _items.RemoveAll(item => _key(item) == task); _indexes.Clear();
        for (var i = 0; i < _items.Count; i++) _indexes[_key(_items[i])] = i;
    }

    private bool IsCurrentLoad(long generation) => IsLoading && generation == Generation;

    private bool Merge(List<T> items, Dictionary<TransferTaskRef, int> indexes, T item)
    {
        if (_acceptItem is not null && !_acceptItem(item)) return false;
        var key = _key(item);
        if (indexes.TryGetValue(key, out var index)) items[index] = _prefer(items[index], item);
        else
        {
            indexes.Add(key, items.Count);
            items.Add(item);
        }
        return true;
    }
}
