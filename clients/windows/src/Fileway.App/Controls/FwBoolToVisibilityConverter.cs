using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;

namespace Fileway.App.Controls;

public sealed class FwBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) =>
        value is true ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}
