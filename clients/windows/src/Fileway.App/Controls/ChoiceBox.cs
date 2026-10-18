using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fileway.App.Controls;

/// <summary>Standard ComboBox keyboard, pointer and IME behavior with shared styling.</summary>
public sealed class ChoiceBox : ComboBox
{
    public ChoiceBox()
    {
        DefaultStyleKey = typeof(ComboBox);
        Style = (Style)Application.Current.Resources["FilewayChoiceBoxStyle"];
        HorizontalContentAlignment = HorizontalAlignment.Center;
        VerticalContentAlignment = VerticalAlignment.Center;
        MinHeight = 36; CornerRadius = new CornerRadius(8); FontSize = 14; MaxDropDownHeight = 360;
        Loaded += (_, _) => ApplyPalette(); ActualThemeChanged += (_, _) => ApplyPalette();
    }
    private void ApplyPalette()
    {
        Resources["ComboBoxDropDownBackground"] = SurfaceStyles.PopupBrush(ActualTheme);
        SurfaceStyles.Soften(this, "ComboBoxItem");
    }
}
