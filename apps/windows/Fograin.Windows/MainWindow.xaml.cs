using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml;

namespace Fograin.Windows;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();
    }
}
