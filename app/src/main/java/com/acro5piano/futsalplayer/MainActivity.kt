package com.acro5piano.futsalplayer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var videoUri by mutableStateOf<Uri?>(null)

    // Set when a video is opened; the orientation is decided once its size is known.
    private var autoFullscreenPending = false

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        hideSystemBars()

        player = ExoPlayer.Builder(this).build().apply {
            // Every seek must land on the exact requested frame, not the nearest keyframe.
            setSeekParameters(SeekParameters.EXACT)
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!autoFullscreenPending || videoSize.width == 0 || videoSize.height == 0) return
                    autoFullscreenPending = false
                    // Wide videos (e.g. GoPro footage) open straight into fullscreen, i.e. landscape.
                    if (videoSize.width * videoSize.pixelWidthHeightRatio > videoSize.height) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
            })
        }

        val restoredUri = savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_URI, Uri::class.java) }
        if (restoredUri != null) {
            open(restoredUri, startPositionMs = savedInstanceState.getLong(KEY_POSITION), autoPlay = false)
        } else {
            handleIntent(intent)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(
                    player = player,
                    videoUri = videoUri,
                    onOpen = { uri -> open(uri) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        videoUri?.let {
            outState.putParcelable(KEY_URI, it)
            outState.putLong(KEY_POSITION, player.currentPosition)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) open(uri)
    }

    private fun open(uri: Uri, startPositionMs: Long = 0, autoPlay: Boolean = true) {
        // Photo picker URIs can be persisted so the video survives process death; others may not support it.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        videoUri = uri
        // Restoring after process death keeps whatever orientation the user had chosen.
        autoFullscreenPending = autoPlay
        player.setMediaItem(MediaItem.fromUri(uri), startPositionMs)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val KEY_URI = "uri"
        const val KEY_POSITION = "position"
    }
}
