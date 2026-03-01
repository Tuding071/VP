package com.example.videoplayer

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainActivity : ComponentActivity() {
    private lateinit var videoFile: File
    private val mainScope = MainScope()

    // Native methods
    private external fun initDecoder(path: String): Boolean
    private external fun getFrameAt(us: Long): Bitmap?
    private external fun releaseDecoder()

    init {
        System.loadLibrary("ffmpeg_wrapper")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoFile = File(filesDir, "sample.mp4")
        if (!videoFile.exists()) {
            downloadSampleVideo()
        } else {
            initPlayerAndUI()
        }
    }

    private fun downloadSampleVideo() {
        mainScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://github.com/mediaelement/mediaelement-files/raw/master/big_buck_bunny.mp4")
                url.openStream().use { input ->
                    FileOutputStream(videoFile).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    initPlayerAndUI()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun initPlayerAndUI() {
        // Initialize native decoder
        if (!initDecoder(videoFile.absolutePath)) {
            Toast.makeText(this, "FFmpeg init failed", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            VideoPlayerScreen(
                videoUri = Uri.fromFile(videoFile),
                onGetFrame = { us -> getFrameAt(us) },
                onRelease = { releaseDecoder() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseDecoder()
        mainScope.cancel()
    }
}

@Composable
fun VideoPlayerScreen(videoUri: Uri, onGetFrame: (Long) -> Bitmap?, onRelease: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    // Clean up player when composable leaves
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            onRelease()
        }
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Update slider max when duration known
    val duration by remember { derivedStateOf { player.duration } }
    val currentPosition by remember { derivedStateOf { player.currentPosition } }

    Box(modifier = Modifier.fillMaxSize()) {
        // Video player view
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = this@apply.player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Thumbnail overlay while scrubbing
        if (isScrubbing && thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.Center)
            )
        }

        // Slider at bottom
        Slider(
            value = if (isScrubbing) sliderPosition else currentPosition.toFloat(),
            onValueChange = { newValue ->
                sliderPosition = newValue
                if (isScrubbing) {
                    // Request thumbnail at this position (convert ms to µs)
                    coroutineScope.launch(Dispatchers.IO) {
                        val bitmap = onGetFrame((newValue * 1000).toLong())
                        withContext(Dispatchers.Main) {
                            thumbnailBitmap = bitmap
                        }
                    }
                }
            },
            onValueChangeFinished = {
                isScrubbing = false
                player.seekTo(sliderPosition.toLong())
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.BottomCenter)
        )
    }
}
