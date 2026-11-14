using Fileway.App.Controls;
using Fileway.Core.Contracts;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Fileway.App.MediaSurfaces;

internal sealed partial class PlayerWindow
{
    private enum MediaPresentation { Unknown, Video, Audio }

    private readonly StackPanel _audioBody = new()
    {
        Spacing = 12, Margin = new Thickness(16, 52, 16, 68),
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        IsHitTestVisible = false, Visibility = Visibility.Collapsed,
    };
    private readonly TextBlock _audioCaption = new()
    {
        Style = SurfaceGeometry.Style("FwCaptionTextStyle"), TextAlignment = TextAlignment.Center,
    };
    private readonly Border _audioIconTile = new()
    {
        Width = 64, Height = 64, CornerRadius = new CornerRadius(16),
        HorizontalAlignment = HorizontalAlignment.Center,
        Style = SurfaceGeometry.Style("FwIconTileStyle"),
        Child = new LucideIcon
        {
            IconName = "headphones", Width = 30, Height = 30,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            Foreground = ThemePalette.Brush("FwTextPrimaryBrush", ElementTheme.Dark),
        },
    };
    private MediaPresentation _mediaPresentation;
    private bool _audioCoverSelected;
    private bool _audioSizeConsidered;
    private bool _audioAutoSized;

    private bool IsAudioPresentation => _mediaPresentation == MediaPresentation.Audio;
    private string MediaName => _mediaPresentation switch
    {
        MediaPresentation.Audio => "音频",
        MediaPresentation.Video => "视频",
        _ => "媒体",
    };

    private void BuildAudioBody()
    {
        _audioBody.Children.Add(_audioIconTile);
        AutomationProperties.SetAutomationId(_audioCaption, "AudioPlaybackBody");
        _audioBody.Children.Add(_audioCaption);
        _root.Children.Add(_audioBody);
    }

    private MediaPresentation ApplyMediaPresentation(PlaybackSnapshot snapshot)
    {
        // mpv exposes cover images as video tracks, with a separate album-art flag.
        var observed = snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Video && !track.IsAlbumArt)
            ? MediaPresentation.Video
            : snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Audio) ? MediaPresentation.Audio : MediaPresentation.Unknown;
        if (observed == MediaPresentation.Unknown) return observed;

        if (_mediaPresentation != observed)
        {
            _mediaPresentation = observed;
            _audioSizeConsidered = false;
            CancelVideoGestures(resetPolicy: true);
            _overlay.SetAudioPresentation(IsAudioPresentation);
            _stateIcon.IconName = IsAudioPresentation ? "headphones" : "film-strip";
            _root.Background = IsAudioPresentation ? ThemePalette.Brush("FwSurfaceBrush", ElementTheme.Dark)
                : new SolidColorBrush(ThemePalette.Color("FwVideoMatteColor", ElementTheme.Dark));
            if (IsAudioPresentation && _systemMedia is { } media)
            {
                media.NextEnabled = false;
                media.PreviousEnabled = false;
            }
        }

        _audioCoverSelected = IsAudioPresentation && snapshot.Tracks.Any(track => track.Kind == MediaTrackKind.Video && track.IsAlbumArt && track.Selected);
        if (IsAudioPresentation)
        {
            var codec = snapshot.Tracks.FirstOrDefault(track => track.Kind == MediaTrackKind.Audio && track.Selected)?.Codec;
            if (codec?.StartsWith("pcm_", StringComparison.OrdinalIgnoreCase) == true) codec = "PCM";
            _audioCaption.Text = codec is { Length: > 0 and <= 12 } && codec.All(char.IsAsciiLetterOrDigit)
                ? $"音频 · {codec.ToUpperInvariant()}" : "音频";
            if (!_audioSizeConsidered && snapshot.Status is PlaybackStatus.Playing or PlaybackStatus.Paused)
            {
                _audioSizeConsidered = true;
                if (CanApplyMediaSize())
                {
                    FitWindowToWorkArea(560, 300);
                    _audioAutoSized = true;
                }
            }
        }
        else RestoreAudioSizedVideoWindow();
        UpdateVideoVisibility();
        return observed;
    }

    private bool CanApplyMediaSize() => !_userAdjustedWindow && !_miniMode && !FullscreenGeometry &&
        !NativePlayerOverlay.IsZoomed(_window) && !NativePlayerOverlay.IsIconic(_window);

    private void RestoreAudioSizedVideoWindow()
    {
        if (_mediaPresentation != MediaPresentation.Video || !_audioAutoSized) return;
        if (_userAdjustedWindow) { _audioAutoSized = false; return; }
        if (_fullscreenApplyingFrame || !CanApplyMediaSize()) return;
        _audioAutoSized = false;
        ApplyInitialWindowSize();
        // A transition snapshot can still carry the previous cover's aspect.
        _initialAspectApplied = true;
    }
}
