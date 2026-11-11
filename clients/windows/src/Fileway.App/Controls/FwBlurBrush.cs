using Microsoft.Graphics.Canvas.Effects;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.Controls;

/// <summary>Real content blur for in-window surfaces: samples the XAML backdrop behind the
/// brush and gaussian-blurs it, then lays a readable tint over the result.</summary>
public sealed class FwBlurBrush : XamlCompositionBrushBase
{
    public static readonly DependencyProperty TintProperty = DependencyProperty.Register(
        nameof(Tint), typeof(Windows.UI.Color), typeof(FwBlurBrush),
        new PropertyMetadata(Microsoft.UI.Colors.Transparent, (_, _) => { }));

    private CompositionEffectBrush? _effectBrush;

    public Windows.UI.Color Tint
    {
        get => (Windows.UI.Color)GetValue(TintProperty);
        set => SetValue(TintProperty, value);
    }

    protected override void OnConnected()
    {
        if (_effectBrush is not null) return;
        var compositor = CompositionTarget.GetCompositorForCurrentThread();
        var graph = new ArithmeticCompositeEffect
        {
            MultiplyAmount = 0,
            Source1Amount = 0.72f,
            Source2Amount = 0.28f,
            Source1 = new GaussianBlurEffect
            {
                Name = "Blur",
                BlurAmount = 18f,
                BorderMode = EffectBorderMode.Hard,
                Optimization = EffectOptimization.Balanced,
                Source = new CompositionEffectSourceParameter("backdrop"),
            },
            Source2 = new ColorSourceEffect { Color = Tint },
        };
        var factory = compositor.CreateEffectFactory(graph);
        _effectBrush = factory.CreateBrush();
        _effectBrush.SetSourceParameter("backdrop", compositor.CreateBackdropBrush());
        CompositionBrush = _effectBrush;
    }

    protected override void OnDisconnected()
    {
        _effectBrush?.Dispose();
        _effectBrush = null;
        CompositionBrush = null;
    }
}
