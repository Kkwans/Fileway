namespace Fileway.Core.Contracts;

/// <summary>How a file category is opened from the browser.</summary>
public enum OpenPreference
{
    BuiltIn,
    SystemDefault,
}

public enum OpenWithCategory
{
    Markdown,
    Text,
    Image,
    Video,
    Audio,
    Pdf,
    Archive,
}

/// <summary>Per-category open preference; defaults to the built-in viewers.</summary>
public sealed record OpenWithPreferences(
    OpenPreference Markdown = OpenPreference.BuiltIn,
    OpenPreference Text = OpenPreference.BuiltIn,
    OpenPreference Image = OpenPreference.BuiltIn,
    OpenPreference Video = OpenPreference.BuiltIn,
    OpenPreference Audio = OpenPreference.BuiltIn,
    OpenPreference Pdf = OpenPreference.BuiltIn,
    OpenPreference Archive = OpenPreference.BuiltIn)
{
    public static OpenWithPreferences Default { get; } = new();

    public OpenPreference For(OpenWithCategory category) => category switch
    {
        OpenWithCategory.Markdown => Markdown,
        OpenWithCategory.Text => Text,
        OpenWithCategory.Image => Image,
        OpenWithCategory.Video => Video,
        OpenWithCategory.Audio => Audio,
        OpenWithCategory.Pdf => Pdf,
        OpenWithCategory.Archive => Archive,
        _ => OpenPreference.BuiltIn,
    };

    public OpenWithPreferences With(OpenWithCategory category, OpenPreference preference) => category switch
    {
        OpenWithCategory.Markdown => this with { Markdown = preference },
        OpenWithCategory.Text => this with { Text = preference },
        OpenWithCategory.Image => this with { Image = preference },
        OpenWithCategory.Video => this with { Video = preference },
        OpenWithCategory.Audio => this with { Audio = preference },
        OpenWithCategory.Pdf => this with { Pdf = preference },
        OpenWithCategory.Archive => this with { Archive = preference },
        _ => this,
    };

    public static string Label(OpenWithCategory category) => category switch
    {
        OpenWithCategory.Markdown => "Markdown",
        OpenWithCategory.Text => "文本 / 代码",
        OpenWithCategory.Image => "图片",
        OpenWithCategory.Video => "视频",
        OpenWithCategory.Audio => "音频",
        OpenWithCategory.Pdf => "PDF",
        OpenWithCategory.Archive => "压缩包",
        _ => category.ToString(),
    };
}
