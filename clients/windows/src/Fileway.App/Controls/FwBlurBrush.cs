using System.Runtime.InteropServices;
using Microsoft.Graphics.Canvas.Effects;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace Fileway.App.Controls;

/// <summary>Samples in-window content with a gentle Gaussian blur and a readable tint.</summary>
public sealed class FwBlurBrush : XamlCompositionBrushBase
{
    private CompositionBackdropBrush? _source;

    public Color Tint { get; init; }

    protected override void OnConnected()
    {
        FallbackColor = Tint;
        try
        {
            var compositor = CompositionTarget.GetCompositorForCurrentThread();
            var graph = new ArithmeticCompositeEffect
            {
                MultiplyAmount = 0,
                Source1Amount = 0.24f,
                Source2Amount = 0.76f,
                Source1 = new GaussianBlurEffect
                {
                    BlurAmount = 12f,
                    BorderMode = EffectBorderMode.Hard,
                    Optimization = EffectOptimization.Balanced,
                    Source = new CompositionEffectSourceParameter("backdrop"),
                },
                Source2 = new ColorSourceEffect { Color = Tint },
            };
            using var factory = compositor.CreateEffectFactory(graph);
            var effect = factory.CreateBrush();
            try
            {
                _source = compositor.CreateBackdropBrush();
                effect.SetSourceParameter("backdrop", _source);
                CompositionBrush = effect;
            }
            catch
            {
                effect.Dispose();
                _source?.Dispose();
                _source = null;
                throw;
            }
        }
        catch (COMException) { }
    }

    protected override void OnDisconnected()
    {
        var brush = CompositionBrush;
        CompositionBrush = null;
        brush?.Dispose();
        _source?.Dispose();
        _source = null;
    }
}
