package com.fograin.launcher.ui.community

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay

/** Immutable playback snapshot shared by the Compose controls. */
data class CommunityVideoState(
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val buffering: Boolean = false,
    /** True once the platform player can decode media. */
    val prepared: Boolean = false,
    /** True once a video frame has been rendered; the placeholder hides then. */
    val rendered: Boolean = false,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * Platform-neutral playback controls.
 *
 * Every platform is backed by Compose Media Player's native engines
 * (AVPlayer, Media Foundation, GStreamer, Media3); this interface only exposes
 * the small surface the community chrome needs.
 */
interface CommunityVideoController {
    val state: CommunityVideoState

    fun togglePlayPause()

    /** Resumes or pauses playback without flipping the current state. */
    fun setPlaying(playing: Boolean)

    fun seekTo(positionMillis: Long)
    fun setVolume(volume: Float)
    fun setSpeed(speed: Float)

    /** Kept for the UI poll loop; the native state is already observable. */
    fun sync()

    fun release()
}

/**
 * True on touch platforms: a single tap enters/leaves fullscreen and a double
 * tap toggles playback. Desktop keeps a single click for play/pause and
 * reserves fullscreen for the button.
 */
expect val communityVideoTouchNavigation: Boolean

/** True on desktop: the floating chrome hides five seconds after the pointer leaves. */
expect val communityVideoAutoHideChrome: Boolean

/** Hides platform system bars while the fullscreen player owns the window. */
@Composable
expect fun CommunityVideoImmersiveMode(enabled: Boolean)

/**
 * Wraps the native player in the controller contract.
 *
 * [state] is derived from the library's observable properties, so the Compose
 * chrome recomposes without any polling. The blocking duration getter is
 * cached once per media in [rememberCommunityVideoController].
 */
private class NativeVideoController(
    val playerState: VideoPlayerState,
) : CommunityVideoController {
    private var durationMillis by mutableStateOf(0L)

    override val state: CommunityVideoState
        get() {
            val duration = durationMillis
            val position = if (duration > 0) {
                (playerState.sliderPos / 1000f * duration).toLong().coerceIn(0L, duration)
            } else {
                0L
            }
            return CommunityVideoState(
                isPlaying = playerState.isPlaying,
                positionMillis = position,
                durationMillis = duration,
                buffering = playerState.isLoading,
                prepared = playerState.hasMedia,
                rendered = playerState.hasMedia && !playerState.isLoading,
                volume = playerState.volume,
                speed = playerState.playbackSpeed,
                error = playerState.error?.let(::errorMessage),
            )
        }

    fun open(url: String) {
        playerState.openUri(url, InitialPlayerState.PLAY)
    }

    /** Reads the platform duration once; returns true when it is known. */
    fun refreshDuration(): Boolean {
        if (!playerState.hasMedia) return false
        val duration = runCatching { playerState.duration.toLong() }.getOrDefault(0L)
        if (duration > 0 && duration != durationMillis) durationMillis = duration
        return durationMillis > 0
    }

    override fun togglePlayPause() = setPlaying(!playerState.isPlaying)

    override fun setPlaying(playing: Boolean) {
        if (!playing) {
            playerState.pause()
            return
        }
        // Native players keep the last frame after end-of-media; restart from
        // zero so a second play always has a picture to show.
        if (playerState.sliderPos >= 995f && durationMillis > 0) {
            playerState.restart()
        } else {
            playerState.play()
        }
    }

    override fun seekTo(positionMillis: Long) {
        if (durationMillis <= 0) return
        val fraction = (positionMillis.toFloat() / durationMillis * 1000f).coerceIn(0f, 1000f)
        playerState.seekTo(fraction)
    }

    override fun setVolume(volume: Float) {
        playerState.volume = volume.coerceIn(0f, 1f)
    }

    override fun setSpeed(speed: Float) {
        playerState.playbackSpeed = speed.coerceIn(0.5f, 2f)
    }

    override fun sync() = Unit

    override fun release() = Unit

    private fun errorMessage(error: VideoPlayerError): String = when (error) {
        is VideoPlayerError.CodecError -> error.message
        is VideoPlayerError.NetworkError -> error.message
        is VideoPlayerError.SourceError -> error.message
        is VideoPlayerError.UnknownError -> error.message
    }
}

/**
 * Creates the native player for [url].
 *
 * [rememberVideoPlayerState] disposes the native engine when the player leaves
 * the composition, so the controller's [CommunityVideoController.release] is
 * intentionally a no-op.
 */
@Composable
fun rememberCommunityVideoController(url: String): CommunityVideoController {
    val playerState = rememberVideoPlayerState()
    val controller = remember(playerState) { NativeVideoController(playerState) }
    LaunchedEffect(controller, url) {
        controller.open(url)
    }
    LaunchedEffect(controller, playerState.hasMedia) {
        // Duration is only available after the media opens; poll briefly
        // instead of blocking the composition on the native getter.
        if (!playerState.hasMedia) return@LaunchedEffect
        repeat(20) {
            if (controller.refreshDuration()) return@LaunchedEffect
            delay(200)
        }
    }
    return controller
}

/** Draws the native video surface; the Compose chrome is composed above it. */
@Composable
fun CommunityVideoPlayer(
    controller: CommunityVideoController,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val native = controller as NativeVideoController
    VideoPlayerSurface(
        playerState = native.playerState,
        modifier = modifier,
        contentScale = contentScale,
    )
}
