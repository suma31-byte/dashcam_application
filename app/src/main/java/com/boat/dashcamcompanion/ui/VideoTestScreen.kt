package com.boat.dashcamcompanion.ui

import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.boat.dashcamcompanion.detect.FrameDetectionLoop
import com.boat.dashcamcompanion.detect.PotholeDetector
import com.boat.dashcamcompanion.detect.PotholeConfirmer
import com.boat.dashcamcompanion.detect.PotholeEventLog
import com.boat.dashcamcompanion.detect.TflitePotholeDetector
import com.boat.dashcamcompanion.drive.DriveService
import com.boat.dashcamcompanion.drive.VoiceAlerts
import com.boat.dashcamcompanion.drive.LocationTracker
import com.boat.dashcamcompanion.detect.RoadBand
import com.boat.dashcamcompanion.stream.VideoFileFrameSource
import com.boat.dashcamcompanion.drive.TripRecorder

/**
 * Bench test without the dashcam: plays a video file from the phone (e.g. test_clip.mp4) and runs
 * the same detection chain as the live view on it — road strip → quality check → TFLite model →
 * 2-of-3 confirmation → pothole log — with the boxes drawn over the video.
 *
 * Frames come from VideoFileFrameSource, which decodes the file in real time on its own thread,
 * so detection (and the trip / notifications) keep running with the screen off, like a live drive.
 * The on-screen player only shows the picture and follows the source's position.
 */
@Composable
fun VideoTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }   // video loaded (playing or paused)
    var playing by remember { mutableStateOf(false) }
    // The on-screen player's prepared file (it follows fileSource's position).
    var preparedUri by remember { mutableStateOf<Uri?>(null) }
    var aiEnabled by remember { mutableStateOf(true) }
    var useInt8 by remember { mutableStateOf(false) }
    // On: log potholes at the phone's GPS position and record a trip - use with a mock-location
    // app (e.g. Lockito) to test GPS features at a desk. Off: potholes merged by time only.
    var useGps by remember { mutableStateOf(false) }
    var voiceOn by remember { mutableStateOf(VoiceAlerts.isEnabled(context)) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) videoUri = uri
    }

    val player = remember { MediaPlayer() }
    // Decodes the file for detection, independent of the screen (keeps going with the screen off).
    val fileSource = remember { VideoFileFrameSource(context.applicationContext, RoadBand.TOP_FRACTION) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    // Play/pause wish of the user. Only ONE decoder runs at a time: the on-screen player while the
    // screen is visible (detection reads its picture), the background decoder while the screen is
    // off. Two decoders at once overloaded the phone and made playback slow.
    var userPlaying by remember { mutableStateOf(true) }
    val playerPositionMs: () -> Long = { try { player.currentPosition.toLong() } catch (e: Exception) { fileSource.positionMs } }

    // New detector / loop whenever the test settings change, so each run starts clean.
    val detector: PotholeDetector = remember(useInt8) {
        TflitePotholeDetector(
            context.applicationContext,
            modelAsset = if (useInt8) "pothole_int8.tflite" else "pothole_fp32.tflite",
            debug = false
        )
    }
    // The phone is not moving during a video test, so merge sightings by time, not GPS.
    val potholeLog = remember(useGps) {
        PotholeEventLog(context.applicationContext, useGps = useGps).apply {
            captureSource = "Imported video"
            videoOffsetMs = { if (surfaceReady) playerPositionMs() else fileSource.positionMs }
        }
    }
    potholeLog.modelName = if (useInt8) "YOLO11n INT8 (pothole_int8.tflite)" else "YOLO11n FP32 (pothole_fp32.tflite)"
    val detectionLoop = remember(detector, potholeLog) {
        FrameDetectionLoop(
            detector = detector,
            confirmer = PotholeConfirmer(),   // 2-of-3 frame confirmation (relaxed at speed)
            eventLog = potholeLog
        )
    }
    DisposableEffect(detector) { onDispose { detector.close() } }

    // The trip continues while the screen is off (the file keeps being decoded in the background).
    val tripActive = useGps && aiEnabled && sourceUri != null
    DisposableEffect(tripActive) {
        if (tripActive) TripRecorder.start(context.applicationContext)
        onDispose { if (tripActive) TripRecorder.stop() }
    }
    val gps by LocationTracker.state.collectAsState()
    val trip by TripRecorder.stats.collectAsState()

    val detections by detectionLoop.detections.collectAsState()
    val stats by detectionLoop.stats.collectAsState()

    // Start background decoding as soon as a file is chosen.
    LaunchedEffect(videoUri) {
        val uri = videoUri ?: return@LaunchedEffect
        if (sourceUri != uri) {
            fileSource.open(uri)
            if (surfaceReady) fileSource.pause()   // the on-screen player is used while visible
            sourceUri = uri
        }
    }
    // While detection runs: foreground service (notification + wake lock) so it continues with the
    // screen off.
    val backgroundActive = aiEnabled && sourceUri != null
    DisposableEffect(backgroundActive) {
        if (backgroundActive) DriveService.start(context.applicationContext)
        onDispose { if (backgroundActive) DriveService.stop(context.applicationContext) }
    }

    // Start the on-screen player once both the file and the surface are ready.
    LaunchedEffect(videoUri, surfaceReady) {
        val uri = videoUri ?: return@LaunchedEffect
        val view = surfaceView ?: return@LaunchedEffect
        if (!surfaceReady) return@LaunchedEffect
        if (preparedUri == uri) return@LaunchedEffect   // returning from background: surfaceCreated resumes it
        try {
            player.reset()
            player.setDataSource(context, uri)
            player.setSurface(view.holder.surface)
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                preparedUri = uri
                it.seekTo(fileSource.positionMs.toInt())
                if (userPlaying) it.start()
                fileSource.pause()
                started = true
                playing = userPlaying
            }
            player.setOnErrorListener { _, what, extra ->
                // Errors while the app is in the background (no surface) are expected - ignore them.
                if (surfaceReady) Toast.makeText(context, "Video error ($what/$extra)", Toast.LENGTH_LONG).show()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't play this video: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Detection: screen visible → reads the player's picture (PixelCopy, light); screen off →
    // background decoder. The pothole log and trip continue across the switch. While paused it
    // keeps checking the paused frame, so a single frame can be tested on its own.
    val activeView = surfaceView
    LaunchedEffect(aiEnabled, sourceUri, surfaceReady, preparedUri, activeView, detectionLoop) {
        if (!aiEnabled || sourceUri == null) return@LaunchedEffect
        if (surfaceReady && preparedUri != null && activeView != null) detectionLoop.run(activeView)
        else if (!surfaceReady) detectionLoop.run(fileSource)
    }

    val hostView = LocalView.current
    DisposableEffect(Unit) {
        hostView.keepScreenOn = true
        onDispose {
            try { player.stop() } catch (_: Exception) {}
            player.release()
            fileSource.close()
            RoadBand.reset()
            hostView.keepScreenOn = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "Test with video file",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.padding(24.dp))
        }

        // Shown in the video's own shape: 16:9 for dashcam videos, tall for portrait phone videos
        // (so the picture isn't squashed and the boxes line up).
        val videoAspect = if (preparedUri != null) RoadBand.videoAspect else 0f
        Box(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            modifier = if (videoAspect > 1f)
                Modifier.height(380.dp).aspectRatio(1f / videoAspect)
            else
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceReady = true
                                if (preparedUri != null) {
                                    // Back from the background: show the video where detection is now.
                                    try {
                                        val pos = fileSource.positionMs
                                        fileSource.pause()                // hand over to the player
                                        player.setSurface(holder.surface)
                                        player.seekTo(pos.toInt())
                                        if (userPlaying) player.start()
                                        started = true
                                        playing = userPlaying
                                    } catch (e: Exception) {
                                        preparedUri = null   // could not resume: the next effect prepares it again
                                    }
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                // App closed / screen off: pause and detach instead of letting the
                                // player write into a surface that no longer exists ("Video error").
                                try {
                                    val pos = player.currentPosition.toLong()
                                    if (player.isPlaying) player.pause()
                                    player.setSurface(null)
                                    // hand over to the background decoder at the same position
                                    fileSource.seekTo(pos)
                                    if (userPlaying) fileSource.play() else fileSource.pause()
                                } catch (_: Exception) {}
                                surfaceReady = false
                            }
                        })
                        surfaceView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (aiEnabled && started) {
                DetectionOverlay(detections, modifier = Modifier.fillMaxSize())
            }
            if (videoUri == null) {
                Text(
                    "Choose a video to start",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        }

        // Scrollable, so every switch is reachable on smaller screens.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "Model: " + (if (useInt8) "INT8 (pothole_int8.tflite)" else "FP32 (pothole_fp32.tflite)") +
                    (if (RoadBand.isPortraitBand) " · phone video: road band 25-88 %" else ""),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text("Choose video") }
                OutlinedButton(
                    enabled = videoUri != null && surfaceReady,
                    onClick = {
                        userPlaying = !userPlaying
                        try { if (userPlaying) player.start() else player.pause() } catch (_: Exception) {}
                        playing = userPlaying
                    }
                ) { Text(if (playing) "Pause" else "Play") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(enabled = started, onClick = {
                    val t = (playerPositionMs() - 10_000).coerceAtLeast(0)
                    fileSource.seekTo(t); player.seekTo(t.toInt())
                }) { Text("-10 s") }
                OutlinedButton(enabled = started, onClick = {
                    val t = (playerPositionMs() + 10_000).coerceAtMost(player.duration.toLong())
                    fileSource.seekTo(t); player.seekTo(t.toInt())
                }) { Text("+10 s") }
                OutlinedButton(enabled = started, onClick = {
                    val t = 120_000L.coerceAtMost(player.duration.toLong()) // 2:00 = frame 22m00s of test_clip.mp4
                    fileSource.seekTo(t); player.seekTo(t.toInt())
                }) { Text("Go to 2:00") }
            }

            Spacer(Modifier.height(16.dp))
            SettingSwitch("AI pothole detection", aiEnabled) { aiEnabled = it }
            SettingSwitch("Use INT8 model (off = FP32)", useInt8) { useInt8 = it }
            SettingSwitch("Use phone GPS + trip log (for mock-location tests)", useGps) { useGps = it }
            SettingSwitch("Voice alerts (\"Pothole ahead\")", voiceOn) { voiceOn = it; VoiceAlerts.setEnabled(context, it) }

            Spacer(Modifier.height(12.dp))
            val detail = when {
                !aiEnabled -> "Detection off"
                !started -> "Choose a video"
                stats.framesPerSecond <= 0f -> "Starting…"
                else -> "%.1f checks/s · %d ms each · %d potholes logged · %d frames skipped".format(
                    stats.framesPerSecond, stats.inferenceMs, stats.loggedPotholes, stats.skippedFrames
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tripActive) {
                val speed = gps.speedKmh?.let { "%.0f km/h".format(it) } ?: "-- km/h"
                val position = gps.location?.let { "%.6f, %.6f".format(java.util.Locale.US, it.latitude, it.longitude) } ?: "no position yet"
                Text(
                    "GPS: ${gps.status} · $speed · $position · Trip %d min · %.2f km · %d potholes".format(
                        trip.durationS / 60, trip.distanceM / 1000, trip.potholes
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "Potholes are saved in Android/data/com.boat.dashcamcompanion/files/Documents/potholes/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
    }
}
