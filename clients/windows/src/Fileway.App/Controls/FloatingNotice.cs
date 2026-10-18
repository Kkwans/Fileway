using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

/// <summary>Preserves InfoBar actions while presenting a dismissible, timed overlay.</summary>
internal static class FloatingNotice
{
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<InfoBar, State> States = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Grid, StackPanel> Hosts = new();
    internal static void Attach(InfoBar bar)
    {
        if (!States.TryGetValue(bar, out _)) States.Add(bar, new State(bar));
    }
    internal static void Show(InfoBar bar)
    {
        Attach(bar);
        if (States.TryGetValue(bar, out var state)) state.Restart();
    }
    private sealed class State
    {
        private readonly InfoBar _bar;
        private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _timer;
        private string? _lastKey;
        private bool _moving;
        internal State(InfoBar bar)
        {
            _bar = bar; bar.IsClosable = true; bar.CornerRadius = new CornerRadius(10);
            _timer = bar.DispatcherQueue.CreateTimer(); _timer.IsRepeating = false;
            _timer.Tick += (_, _) => bar.IsOpen = false;
            bar.Closed += (_, _) => _timer.Stop();
            bar.Unloaded += (_, _) => { if (!_moving) _timer.Stop(); };
            bar.Loaded += (_, _) => Place(); bar.ActualThemeChanged += (_, _) => Material();
            bar.RegisterPropertyChangedCallback(InfoBar.IsOpenProperty, (_, _) => OpenChanged());
            bar.RegisterPropertyChangedCallback(InfoBar.MessageProperty, (_, _) => { if (bar.IsOpen) OpenChanged(); });
            bar.RegisterPropertyChangedCallback(InfoBar.TitleProperty, (_, _) => { if (bar.IsOpen) OpenChanged(); });
        }
        internal void Restart() { _lastKey = null; _bar.IsOpen = true; OpenChanged(); }
        private void Place()
        {
            if (_moving) return;
            Grid? root = null;
            var dialog = false;
            for (DependencyObject? item = VisualTreeHelper.GetParent(_bar); item is not null; item = VisualTreeHelper.GetParent(item))
            {
                if (item is Grid grid) root = grid;
                if (item is ContentDialog) { dialog = true; break; }
                if (item is UserControl or Page) break;
            }
            if (root is null) return;
            var host = _bar.Parent is StackPanel { Name: "ToastHost" } existing ? existing : Hosts.GetValue(root, grid =>
            {
                var stack = new StackPanel { Spacing = 8, Margin = new Thickness(16), MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = dialog ? VerticalAlignment.Top : VerticalAlignment.Bottom };
                Grid.SetRowSpan(stack, Math.Max(1, grid.RowDefinitions.Count)); Grid.SetColumnSpan(stack, Math.Max(1, grid.ColumnDefinitions.Count));
                Canvas.SetZIndex(stack, 100); grid.Children.Add(stack); return stack;
            });
            if (_bar.Parent is Panel parent && parent != host)
            { _moving = true; parent.Children.Remove(_bar); host.Children.Add(_bar); _moving = false; }
            _bar.HorizontalAlignment = HorizontalAlignment.Stretch; _bar.VerticalAlignment = VerticalAlignment.Top;
            _bar.Margin = new Thickness(0); _bar.Width = double.NaN; _bar.MaxWidth = 420;
            Material(); if (_bar.IsOpen) OpenChanged();
        }
        private void Material()
        {
            foreach (var severity in new[] { "Error", "Warning", "Success", "Informational" })
                _bar.Resources["InfoBar" + severity + "SeverityBackgroundBrush"] = SurfaceStyles.PopupBrush(_bar.ActualTheme);
        }
        private void OpenChanged()
        {
            if (!_bar.IsOpen) { _timer.Stop(); return; }
            var key = _bar.Title + "\n" + _bar.Message;
            if (_lastKey == key)
            {
                if (!_timer.IsRunning) _bar.IsOpen = false;
                return;
            }
            _lastKey = key; _timer.Stop(); _timer.Interval = TimeSpan.FromSeconds(PresentationPreferences.ToastSeconds); _timer.Start();
        }
    }
}
