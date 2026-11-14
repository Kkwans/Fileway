using Fileway.App.Controls;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.System;
using Windows.UI.Core;

namespace Fileway.App.Views;

public sealed partial class TextWindow
{
    private readonly Button _findButton;
    private Popup _findPopup = null!;
    private TextBox _findInput = null!;
    private TextBlock _findStatus = null!;
    private Button _findPrevious = null!;
    private Button _findNext = null!;
    private CancellationTokenSource? _findWork;
    private object? _findSurface;
    private TextBlock? _findMarkedBlock;
    private TextHighlighter? _findHighlight;
    private NativeFindMatch? _findMatch;
    private int _findSelectionLength;
    private int _findRequest;
    private bool IsFindOpen => _findPopup.IsOpen;

    private void BuildFindPopup()
    {
        _findInput = new TextBox
        {
            Style = SurfaceGeometry.Style("FwTextBoxStyle"), MinHeight = 32, Height = 32,
            Width = 196, PlaceholderText = "在文档中查找", MaxLength = 256,
        };
        AutomationProperties.SetAutomationId(_findInput, "DocumentFindQuery");
        AutomationProperties.SetName(_findInput, "文内查找");
        _findInput.TextChanged += (_, _) => RequestFind(0, debounce: true);
        _findStatus = new TextBlock
        {
            Style = SurfaceGeometry.Style("FwCaptionTextStyle"), VerticalAlignment = VerticalAlignment.Center,
            MinWidth = 58, TextAlignment = TextAlignment.Center,
        };
        AutomationProperties.SetAutomationId(_findStatus, "DocumentFindStatus");
        AutomationProperties.SetLiveSetting(_findStatus, AutomationLiveSetting.Polite);
        _findPrevious = FindAction("arrow-up", "上一个结果 (Shift+Enter)", () => RequestFind(-1));
        _findNext = FindAction("arrow-up", "下一个结果 (Enter)", () => RequestFind(1));
        if (_findNext.Content is FrameworkElement arrow)
        {
            arrow.RenderTransformOrigin = new Point(0.5, 0.5);
            arrow.RenderTransform = new RotateTransform { Angle = 180 };
        }
        var close = FindAction("x", "关闭查找 (Esc)", CloseFind);
        AutomationProperties.SetAutomationId(_findPrevious, "DocumentFindPrevious");
        AutomationProperties.SetAutomationId(_findNext, "DocumentFindNext");
        AutomationProperties.SetAutomationId(close, "DocumentFindClose");
        var row = new Grid { ColumnSpacing = 4 };
        row.ColumnDefinitions.Add(new ColumnDefinition());
        var controls = new FrameworkElement[] { _findStatus, _findPrevious, _findNext, close };
        row.Children.Add(_findInput);
        for (int index = 0; index < controls.Length; index++)
        {
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn(controls[index], index + 1);
            row.Children.Add(controls[index]);
        }
        var surface = new Border
        {
            CornerRadius = new CornerRadius(14), Padding = new Thickness(8),
            BorderThickness = new Thickness(1), Child = row,
        };
        AutomationProperties.SetAutomationId(surface, "DocumentFindPopup");
        surface.PreviewKeyDown += (_, args) => HandleFindKeys(args);
        _findPopup = new Popup
        {
            Child = surface, PlacementTarget = _toolbar,
            DesiredPlacement = PopupPlacementMode.BottomEdgeAlignedRight,
            HorizontalOffset = -16, VerticalOffset = 8, IsLightDismissEnabled = false,
        };
        SurfaceStyles.AttachPopup(_findPopup, surface, _toolbar);
        _findPopup.Opened += (_, _) => FocusFindInput();
        _findPopup.Closed += (_, _) => FinishFind();
        _root.Children.Add(_findPopup);
    }

    private static Button FindAction(string icon, string label, Action click)
    {
        var button = ViewerAction(icon, label, click, "FwNavigationButtonStyle");
        button.Width = 30;
        button.HorizontalContentAlignment = HorizontalAlignment.Center;
        return button;
    }

    private void ToggleFindPopup()
    {
        if (IsFindOpen) CloseFind();
        else OpenFind();
    }

    private void OpenFind()
    {
        if (_closed || !_loaded || _saving || _confirmingClose || _switchingMarkdown) return;
        if (_tableScroll.Visibility == Visibility.Visible)
        {
            _showTable = false;
            ApplyViewMode();
            UpdateCommands();
        }
        if (IsFindOpen) FocusFindInput();
        else _findPopup.IsOpen = true;
        UpdateFindSurface();
    }

    private void FocusFindInput()
    {
        if (!IsFindOpen) return;
        if (_findInput.Focus(FocusState.Programmatic)) _findInput.SelectAll();
    }

    private bool HandleFindKeys(KeyRoutedEventArgs args)
    {
        if (_saving || _confirmingClose || _switchingMarkdown || _codeMenuOpen) return false;
        if (args.Key == VirtualKey.F && KeyIsDown(VirtualKey.Control))
        {
            OpenFind();
            args.Handled = true;
            return true;
        }
        if (!IsFindOpen) return false;
        if (args.Key == VirtualKey.Escape)
        {
            CloseFind();
            args.Handled = true;
            return true;
        }
        if (_findInput.FocusState == FocusState.Unfocused) return false;
        if (args.Key == VirtualKey.Enter)
        {
            if (_findNext.IsEnabled) RequestFind(KeyIsDown(VirtualKey.Shift) ? -1 : 1);
            args.Handled = true;
        }
        return true;
    }

    private static bool KeyIsDown(VirtualKey key) =>
        InputKeyboardSource.GetKeyStateForCurrentThread(key).HasFlag(CoreVirtualKeyStates.Down);

    private void HandleDocumentEscape()
    {
        if (_closed) return;
        if (IsFindOpen) CloseFind();
        else Close();
    }

    private void CloseFind()
    {
        if (IsFindOpen) _findPopup.IsOpen = false;
    }

    private void FinishFind()
    {
        if (IsFindOpen) return;
        CancelFind();
        if (!_closed) ClearFindMark();
        _findSurface = null;
        if (_closed) return;
        if (_editing) RestoreEditingInput();
        else FocusContent();
    }

    private void UpdateFindSurface()
    {
        if (!IsFindOpen) return;
        object target = _markdownEditor is { IsReady: true, View.Visibility: Visibility.Visible } markdown ? markdown :
            _codePreview is { IsReady: true, View.Visibility: Visibility.Visible } code ? code :
            _renderScroll.Visibility == Visibility.Visible ? _renderHost : _text;
        if (ReferenceEquals(target, _findSurface)) return;
        ClearFindMark();
        _findSurface = target;
        RequestFind(0);
    }

    private void RequestFind(int step, bool debounce = false)
    {
        if (!IsFindOpen || _closed) return;
        _ = FindAsync(step, debounce);
    }

    private async Task FindAsync(int step, bool debounce)
    {
        CancelFind();
        var request = _findRequest;
        using var work = new CancellationTokenSource();
        _findWork = work;
        var query = _findInput.Text;
        try
        {
            if (step == 0)
            {
                ClearFindMark();
                SetFindStatus(query.Length == 0 ? "" : "…", false);
            }
            if (query.Length == 0) return;
            if (debounce) await Task.Delay(100, work.Token);
            work.Token.ThrowIfCancellationRequested();
            if (_findSurface is MarkdownVisualEditor markdown)
            {
                await markdown.FindAsync(query, step, request, work.Token);
                return;
            }
            if (_findSurface is CodePreview code)
            {
                code.Find(query, step, request);
                return;
            }

            var blocks = new List<TextBlock>();
            var nativeText = ReferenceEquals(_findSurface, _text);
            if (!nativeText) CollectFindBlocks(_renderHost, blocks);
            string[] texts = nativeText ? [_text.Text] : blocks.Select(block => block.Text).ToArray();
            var previousOrdinal = _findMatch?.Ordinal ?? 1;
            var result = await Task.Run(() => FindNativeMatch(texts, query, step, previousOrdinal, work.Token), work.Token);
            if (_closed || !IsFindOpen || request != _findRequest) return;
            ClearNativeFindMark();
            _findMatch = result.Match;
            if (result.Match is { } match)
            {
                if (!nativeText) MarkRenderedMatch(blocks[match.Block], match.Start, query.Length);
                else if (step != 0 || !_editing || _text.FocusState == FocusState.Unfocused)
                    MarkTextMatch(match.Start, query.Length);
            }
            ShowFindResult(request, result.Match?.Ordinal ?? 0, result.Total);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Document find: {error.GetType().Name}");
            if (!_closed && IsFindOpen && request == _findRequest) SetFindStatus("查找不可用", false);
        }
        finally { if (ReferenceEquals(_findWork, work)) _findWork = null; }
    }

    private void CancelFind()
    {
        ++_findRequest;
        _findWork?.Cancel();
        _findWork = null;
    }

    private void ShowFindResult(int request, int ordinal, int total)
    {
        if (_closed || !IsFindOpen || request != _findRequest) return;
        SetFindStatus(total > 0 ? $"{Math.Max(1, ordinal)} / {total}" : "无结果", total > 0);
    }

    private void SetFindStatus(string text, bool canNavigate)
    {
        _findPrevious.IsEnabled = _findNext.IsEnabled = canNavigate;
        if (_findStatus.Text == text) return;
        _findStatus.Text = text;
        if (text.Length > 0 && text != "…")
            FrameworkElementAutomationPeer.FromElement(_findStatus)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
    }

    private void RefreshFindColors()
    {
        if (_findHighlight is { } highlight)
        {
            highlight.Background = ThemeBrush("FwChoiceSelectionBrush");
            highlight.Foreground = ThemeBrush("FwTextPrimaryBrush");
        }
        if (_findSelectionLength != 0)
            _text.SelectionHighlightColorWhenNotFocused = ThemeBrush("FwChoiceSelectionBrush");
    }

    private void ClearFindMark()
    {
        if (_findSurface is MarkdownVisualEditor markdown) markdown.ClearFind();
        if (_findSurface is CodePreview code) code.ClearFind();
        ClearNativeFindMark();
        _findMatch = null;
    }

    private void ClearNativeFindMark()
    {
        if (_findMarkedBlock is { } block && _findHighlight is { } highlight) block.TextHighlighters.Remove(highlight);
        if (_findSelectionLength != 0)
        {
            if (_findMatch is { } match && _text.SelectionStart == match.Start && _text.SelectionLength == _findSelectionLength)
                _text.Select(match.Start, 0);
            _text.ClearValue(TextBox.SelectionHighlightColorWhenNotFocusedProperty);
            _findSelectionLength = 0;
        }
        _findMarkedBlock = null;
        _findHighlight = null;
    }

    private void MarkTextMatch(int start, int length)
    {
        if (start + length > _text.Text.Length) return;
        _text.SelectionHighlightColorWhenNotFocused = ThemeBrush("FwChoiceSelectionBrush");
        _text.Select(start, length);
        _findSelectionLength = length;
        if (PlainTextScrollHost() is not { } scroll) return;
        var rect = _text.GetRectFromCharacterIndex(start, trailingEdge: false);
        if (!double.IsFinite(rect.Top) || !double.IsFinite(rect.Left)) return;
        var point = _text.TransformToVisual(scroll).TransformPoint(new Point(rect.Left, rect.Top));
        var horizontal = point.X < 0 || point.X > scroll.ViewportWidth - 32
            ? Math.Clamp(scroll.HorizontalOffset + point.X - 24, 0, scroll.ScrollableWidth) : (double?)null;
        var vertical = point.Y < 0 || point.Y + rect.Height > scroll.ViewportHeight
            ? Math.Clamp(scroll.VerticalOffset + point.Y - scroll.ViewportHeight * 0.35, 0, scroll.ScrollableHeight) : (double?)null;
        if (horizontal is not null || vertical is not null) scroll.ChangeView(horizontal, vertical, null, true);
    }

    private void MarkRenderedMatch(TextBlock block, int start, int length)
    {
        var highlight = new TextHighlighter
        {
            Background = ThemeBrush("FwChoiceSelectionBrush"), Foreground = ThemeBrush("FwTextPrimaryBrush"),
        };
        highlight.Ranges.Add(new TextRange { StartIndex = start, Length = length });
        block.TextHighlighters.Add(highlight);
        _findMarkedBlock = block;
        _findHighlight = highlight;
        block.StartBringIntoView(new BringIntoViewOptions { VerticalAlignmentRatio = 0.35, AnimationDesired = false });
    }

    private static void CollectFindBlocks(DependencyObject parent, List<TextBlock> blocks)
    {
        if (parent is TextBlock block) { if (block.Text.Length != 0) blocks.Add(block); return; }
        for (int index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
            CollectFindBlocks(VisualTreeHelper.GetChild(parent, index), blocks);
    }

    private readonly record struct NativeFindMatch(int Block, int Start, int Ordinal);

    private static (NativeFindMatch? Match, int Total) FindNativeMatch(string[] texts, string query, int step,
        int previousOrdinal, CancellationToken token)
    {
        NativeFindMatch? first = null, last = null, selected = null;
        var target = step == 0 ? 1 : previousOrdinal + step;
        int total = 0;
        for (int block = 0; block < texts.Length; block++)
        {
            token.ThrowIfCancellationRequested();
            var text = texts[block];
            int offset = 0;
            while (offset <= text.Length - query.Length)
            {
                int start = text.IndexOf(query, offset, StringComparison.OrdinalIgnoreCase);
                if (start < 0) break;
                offset = start + query.Length;
                ++total;
                if ((total & 1023) == 0) token.ThrowIfCancellationRequested();
                var match = new NativeFindMatch(block, start, total);
                first ??= match;
                if (step < 0) last = match;
                if (total == target) selected = match;
            }
        }
        return (selected ?? (step < 0 ? last : first), total);
    }
}
