package com.acro5piano.futsalplayer

import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToLong

private val SPEEDS = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val MAX_ZOOM = 10f
private const val DOUBLE_TAP_SEEK_MS = 5_000L
private const val DEFAULT_FPS = 30f

@Composable
fun PlayerScreen(player: ExoPlayer, videoUri: Uri?, onOpen: (Uri) -> Unit) {
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let(onOpen) }
    val pickVideo = { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (videoUri == null) {
            EmptyState(onPick = pickVideo)
        } else {
            VideoPlayer(player = player, onPick = pickVideo)
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = onPick) { Text("Pick a video") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Google Photos videos appear in the picker, or use Share / Open with from Google Photos.",
            color = Color.Gray,
            fontSize = 13.sp,
        )
    }
}

/** Snapshot of the player state that the UI renders. */
private class PlayerUiState {
    var showPlayButton by mutableStateOf(true)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
    var fps by mutableFloatStateOf(DEFAULT_FPS)
    var speed by mutableFloatStateOf(1f)
    var error by mutableStateOf<String?>(null)
}

/**
 * Steps exactly one frame at a time by seeking to frame boundaries.
 *
 * The current frame comes from the timestamp of the frame actually handed to the display:
 * after pausing playback, `currentPosition` lags the visible frame by a few frames, which would
 * make the first step go the wrong way.
 *
 * Seeks land a quarter frame before the target frame's timestamp so small timestamp jitter
 * never skips a frame; the last stepped frame is remembered so rapid taps chain correctly
 * before the seek has rendered.
 */
@AndroidXOptIn(UnstableApi::class)
private class FrameStepper(private val player: ExoPlayer) : VideoFrameMetadataListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    var renderedPtsUs by mutableLongStateOf(C.TIME_UNSET)
        private set
    private var lastFrame = -1L
    private var lastPositionMs = -1L

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        // Called on the playback thread.
        mainHandler.post { renderedPtsUs = presentationTimeUs }
    }

    fun frameAt(positionMs: Long, fps: Float): Long {
        if (positionMs == lastPositionMs) return lastFrame
        val timeUs = if (renderedPtsUs != C.TIME_UNSET) renderedPtsUs else positionMs * 1000
        return (timeUs * fps / 1_000_000.0).roundToLong()
    }

    fun step(delta: Int, fps: Float) {
        player.pause()
        val frameMs = 1000.0 / fps
        val maxFrame = if (player.duration == C.TIME_UNSET) Long.MAX_VALUE else (player.duration / frameMs).toLong()
        val target = (frameAt(player.currentPosition, fps) + delta).coerceIn(0, maxFrame)
        val positionMs = max(0L, ((target - 0.25) * frameMs).toLong())
        lastFrame = target
        lastPositionMs = positionMs
        player.seekTo(positionMs)
    }
}

@AndroidXOptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(player: ExoPlayer, onPick: () -> Unit) {
    val ui = remember { PlayerUiState() }
    val stepper = remember(player) { FrameStepper(player) }
    DisposableEffect(stepper) {
        player.setVideoFrameMetadataListener(stepper)
        onDispose { player.clearVideoFrameMetadataListener(stepper) }
    }
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(player) {
        fun sync() {
            ui.showPlayButton = Util.shouldShowPlayButton(player)
            ui.videoSize = player.videoSize
            ui.fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: DEFAULT_FPS
            ui.speed = player.playbackParameters.speed
            ui.durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()

            override fun onPlayerError(error: PlaybackException) {
                ui.error = "${error.errorCodeName}: ${error.message}"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) ui.error = null
            }
        }
        sync()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Poll the position: every frame while playing, slowly while paused.
    LaunchedEffect(player) {
        while (isActive) {
            ui.positionMs = player.currentPosition
            delay(if (player.isPlaying) 16 else 50)
        }
    }

    val view = LocalView.current
    val keepScreenOn = !ui.showPlayButton
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val seekBy = { deltaMs: Long ->
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            ZoomableVideo(
                player = player,
                videoSize = ui.videoSize,
                frameLabel = "f${stepper.frameAt(ui.positionMs, ui.fps)} · ${"%.2f".format(ui.fps)}fps",
                error = ui.error,
                onPick = onPick,
                onDoubleTap = { forward -> seekBy(if (forward) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            Controls(
                wide = wide,
                showPlayButton = ui.showPlayButton,
                positionMs = scrubPositionMs ?: ui.positionMs,
                durationMs = ui.durationMs,
                speed = ui.speed,
                onPlayPause = { Util.handlePlayPauseButtonAction(player) },
                onFrameStep = { delta -> stepper.step(delta, ui.fps) },
                onScrub = { positionMs ->
                    if (scrubPositionMs == null) player.setScrubbingModeEnabled(true)
                    scrubPositionMs = positionMs
                    player.seekTo(positionMs)
                },
                onScrubFinished = {
                    player.setScrubbingModeEnabled(false)
                    scrubPositionMs?.let { player.seekTo(it) }
                    scrubPositionMs = null
                },
                onSpeed = { player.setPlaybackSpeed(it) },
            )
        }
    }
}

@Composable
private fun ZoomableVideo(
    player: ExoPlayer,
    videoSize: VideoSize,
    frameLabel: String,
    error: String?,
    onPick: () -> Unit,
    onDoubleTap: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    // Double-tap feedback: which side, how many seconds accumulated, and a key to restart the fade timer.
    var seekHintForward by remember { mutableStateOf<Boolean?>(null) }
    var seekHintSeconds by remember { mutableLongStateOf(0L) }
    var seekHintKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekHintKey) {
        delay(700)
        seekHintForward = null
        seekHintSeconds = 0
    }

    fun clampOffset(raw: Offset, s: Float): Offset {
        val maxX = max(0f, (contentSize.width * s - containerSize.width) / 2f)
        val maxY = max(0f, (contentSize.height * s - containerSize.height) / 2f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        val forward = position.x > size.width / 2f
                        if (seekHintForward != forward) seekHintSeconds = 0
                        seekHintForward = forward
                        seekHintSeconds += DOUBLE_TAP_SEEK_MS / 1000
                        seekHintKey++
                        currentOnDoubleTap(forward)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    val z = newScale / scale
                    // Keep the point under the fingers fixed while zooming (offset is relative to center).
                    val fromCenter = centroid - Offset(containerSize.width / 2f, containerSize.height / 2f)
                    offset = clampOffset(fromCenter + pan - (fromCenter - offset) * z, newScale)
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val aspect = if (videoSize.width > 0 && videoSize.height > 0) {
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        } else {
            16f / 9f
        }
        AndroidView(
            factory = { context -> TextureView(context).also(player::setVideoTextureView) },
            onRelease = player::clearVideoTextureView,
            modifier = Modifier
                .aspectRatio(aspect)
                .onSizeChanged { contentSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        seekHintForward?.let { forward ->
            Text(
                if (forward) "+${seekHintSeconds}s ››" else "‹‹ -${seekHintSeconds}s",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        error?.let {
            Text(
                it,
                color = Color(0xFFFF8A80),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }

        // Top overlay: open button on the left, frame info and zoom reset on the right.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallIconButton(R.drawable.ic_folder_open, "Open video", onClick = onPick)
            Spacer(Modifier.weight(1f))
            if (scale > 1.01f) {
                TextButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("${"%.1f".format(scale)}× reset", color = Color.White, fontSize = 12.sp)
                }
            }
            OverlayText(frameLabel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    wide: Boolean,
    showPlayButton: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    onPlayPause: () -> Unit,
    onFrameStep: (delta: Int) -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val buttons = @Composable {
        SmallIconButton(
            if (showPlayButton) R.drawable.ic_play else R.drawable.ic_pause,
            if (showPlayButton) "Play" else "Pause",
            onClick = onPlayPause,
        )
        RepeatingIconButton(R.drawable.ic_frame_back, "Previous frame") { onFrameStep(-1) }
        RepeatingIconButton(R.drawable.ic_frame_forward, "Next frame") { onFrameStep(1) }
    }
    val timeline = @Composable { modifier: Modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            TimeText(formatTime(positionMs, withFraction = true))
            Slider(
                value = positionMs.toFloat().coerceIn(0f, max(durationMs, 1L).toFloat()),
                onValueChange = { onScrub(it.toLong()) },
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..max(durationMs, 1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
                thumb = {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(Color.White, CircleShape),
                    )
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        modifier = Modifier.height(4.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                        ),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .padding(horizontal = 8.dp),
            )
            TimeText(formatTime(durationMs, withFraction = false))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 4.dp),
    ) {
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                buttons()
                Spacer(Modifier.width(8.dp))
                timeline(Modifier.weight(1f))
                SpeedButton(speed, onSpeed)
            }
        } else {
            timeline(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                buttons()
                Spacer(Modifier.width(16.dp))
                SpeedButton(speed, onSpeed)
            }
        }
    }
}

@Composable
private fun SpeedButton(speed: Float, onSpeed: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.height(36.dp)) {
            Text(formatSpeed(speed), color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(s), fontFamily = FontFamily.Monospace) },
                    onClick = {
                        onSpeed(s)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SmallIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Fires once on press, then repeatedly while held, so a scene can be walked through frame by frame. */
@Composable
private fun RepeatingIconButton(@DrawableRes icon: Int, description: String, onStep: () -> Unit) {
    val currentOnStep by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (pressed) Color.White.copy(alpha = 0.2f) else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    currentOnStep()
                    val repeater = scope.launch {
                        delay(400)
                        while (isActive) {
                            currentOnStep()
                            delay(120)
                        }
                    }
                    waitForUpOrCancellation()
                    repeater.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TimeText(text: String) {
    Text(text, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun OverlayText(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatTime(ms: Long, withFraction: Boolean): String {
    val totalSeconds = ms / 1000
    val base = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    return if (withFraction) "%s.%02d".format(base, ms % 1000 / 10) else base
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
