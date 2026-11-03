using System.ComponentModel;
using Fograin.Windows.Models;

namespace Fograin.Windows.ViewModels;

/// <summary>Static data for the M1 home screen.</summary>
public sealed class HomeViewModel
{
    public Instance Current => SampleData.Current;

    public IReadOnlyList<Instance> Recent => SampleData.Recent;

    public IReadOnlyList<DownloadTask> Downloads => SampleData.Downloads;

    public IReadOnlyList<RuntimeRow> Runtime => SampleData.Runtime;

    public IReadOnlyList<NewsItem> News => SampleData.News;

    public AccountInfo Account => SampleData.Account;

    public string PageCaption => $"{SampleData.InstanceCount} 个实例 · 上次游玩 2 小时前";
}

/// <summary>Static data for the M1 library screen; selection drives the preview pane.</summary>
public sealed class LibraryViewModel : INotifyPropertyChanged
{
    private Instance _selectedInstance = SampleData.Current;

    public IReadOnlyList<InstanceGroup> Groups { get; } = SampleData.Groups;

    public string PageCaption => $"{SampleData.InstanceCount} 个实例 · 占用 14.2 GB";

    public Instance SelectedInstance
    {
        get => _selectedInstance;
        set
        {
            if (ReferenceEquals(_selectedInstance, value))
            {
                return;
            }

            _selectedInstance = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SelectedInstance)));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
}
