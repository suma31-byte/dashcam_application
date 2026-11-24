package com.boat.dashcamcompanion.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Switch
import androidx.compose.runtime.collectAsState
import com.boat.dashcamcompanion.detect.DetectionStats
import com.boat.dashcamcompanion.detect.TflitePotholeDetector
import com.boat.dashcamcompanion.detect.FrameDetectionLoop
import com.boat.dashcamcompanion.detect.PotholeDetector
import com.boat.dashcamcompanion.detect.PotholeConfirmer
import com.boat.dashcamcompanion.detect.PotholeEventLog
import com.boat.dashcamcompanion.detect.RoadBand
import com.boat.dashcamcompanion.stream.AnalysisFrameDecoder
import com.boat.dashcamcompanion.stream.BackgroundSurfaceSink
import com.boat.dashcamcompanion.drive.DriveService
import com.boat.dashcamcompanion.drive.VoiceAlerts
import com.boat.dashcamcompanion.drive.LocationTracker
import com.boat.dashcamcompanion.drive.TripRecorder
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalView
import com.boat.dashcamcompanion.record.RecordFormat
import com.boat.dashcamcompanion.record.StreamRecorder
import com.boat.dashcamcompanion.stream.RtspH264Client
import com.boat.dashcamcompanion.stream.StreamCandidates
import com.boat.dashcamcompanion.stream.YiZhiHandshake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiveViewScreen(
    gatewayIp: String,
    onBack: () -> Unit,
    /**
     * false = bench test with an RTSP stream from a PC (no dashcam): skip the camera auto-detect
     * and open the manual-URL panel instead.
     */
    autoConnect: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var manualUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not connected") }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var autoDetectJob by remember { mutableStateOf<Job?>(null) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var showAdvanced by remember { mutableStateOf(!autoConnect) }

    val recorder = remember { StreamRecorder(context.applicationContext) }
    // Second decoder that gives the detector the camera's own frames (does nothing while AI is off).
    // Takes the live video while the screen is off, so the stream keeps running in the background.
    val backgroundSink = remember { BackgroundSurfaceSink() }
    val analysisDecoder = remember { AnalysisFrameDecoder(RoadBand.TOP_FRACTION, context.applicationContext) }
    val client = remember {
        RtspH264Client { message -> status = message }.also { c ->
            c.accessUnitListener = { frame, rtpTimestamp ->
                recorder.writeAccessUnit(frame, rtpTimestamp) // does nothing unless recording
                analysisDecoder.feed(frame)                   // does nothing unless AI detection is on
            }
            c.codecConfigListener = { config -> analysisDecoder.setCodecConfig(config) }
        }
    }
    val isStreaming = status.startsWith("Streaming")

    // Recording the live stream (video + GPS) to collect training data.
    var recording by remember { mutableStateOf(false) }
    var recordStats by remember { mutableStateOf(StreamRecorder.Stats()) }
    var lastSavedFolder by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recording) {
        while (recording) {
            recordStats = recorder.stats()
            if (!recorder.isRecording) { // stopped by itself, e.g. the phone's storage is full
                recording = false
                lastSavedFolder = recorder.directory?.absolutePath
                val reason = recorder.error?.let { ": $it" } ?: ""
                Toast.makeText(context, "Recording stopped$reason", Toast.LENGTH_LONG).show()
            }
            delay(1000)
        }
    }

    // AI pothole detection with the fine-tuned YOLO11n model (assets/pothole_fp32.tflite):
    // background-decoded frames -> quality check -> model -> 2-of-3 frame confirmation -> GPS log.
    var aiEnabled by remember { mutableStateOf(false) }
    // Full-screen panel over the live view: "list", "map" or "report:<reportId>" (null = none).
    var panel by remember { mutableStateOf<String?>(null) }
    // Dashcam road band (a phone video in the video test may have changed it)
    remember { RoadBand.reset(); true }
    val potholeDetector: PotholeDetector = remember {
        TflitePotholeDetector(context.applicationContext, modelAsset = "pothole_int8.tflite")
    }
    DisposableEffect(potholeDetector) { onDispose { potholeDetector.close() } }
    val potholeLog = remember {
        PotholeEventLog(context.applicationContext).apply {
            captureSource = if (autoConnect) "Drive Mode (live dashcam)" else "Drive Mode (PC RTSP stream)"
            modelName = "YOLO11n INT8 (pothole_int8.tflite)"
        }
    }
    val detectionLoop = remember(potholeDetector) {
        FrameDetectionLoop(
            detector = potholeDetector,
            confirmer = PotholeConfirmer(),   // 2-of-3 confirmation (relaxed at speed)
            eventLog = potholeLog
        )
    }
    val detections by detectionLoop.detections.collectAsState()
    val detectionStats by detectionLoop.stats.collectAsState()
    val activeSurfaceView = surfaceView
    // A "drive" runs while AI detection is on and the stream plays: trip log + GPS, and a
    // foreground service that keeps everything running with the screen off.
    val driveActive = aiEnabled && isStreaming
    DisposableEffect(driveActive) {
        if (driveActive) {
            TripRecorder.start(context.applicationContext)
            DriveService.start(context.applicationContext)
        }
        onDispose {
            if (driveActive) {
                TripRecorder.stop()
                DriveService.stop(context.applicationContext)
            }
        }
    }
    val gps by LocationTracker.state.collectAsState()
    val trip by TripRecorder.stats.collectAsState()

    LaunchedEffect(aiEnabled, isStreaming, activeSurfaceView) {
        if (aiEnabled && isStreaming && activeSurfaceView != null) {
            // Decoder frames, with automatic fallback to screen capture on phones whose
            // decoder can't deliver them - so detection works on every phone.
            if (USE_DECODER_FRAMES) detectionLoop.run(analysisDecoder, activeSurfaceView)
            else detectionLoop.run(activeSurfaceView)
        }
    }

    // Keep the screen on while the live view is open: a sleeping phone would drop the recording.
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        hostView.keepScreenOn = true
        onDispose {
            client.stop()
            recorder.stop()
            analysisDecoder.release()
            backgroundSink.release()
            hostView.keepScreenOn = false
        }
    }

    fun toggleRecording() {
        if (recording) {
            val folder = recorder.stop()
            recordStats = recorder.stats()
            recording = false
            lastSavedFolder = folder?.absolutePath
            Toast.makeText(context, "Recording saved", Toast.LENGTH_LONG).show()
        } else {
            val folder = recorder.start()
            if (folder == null) {
                Toast.makeText(context, "Couldn't create the recording folder", Toast.LENGTH_LONG).show()
            } else {
                recording = true
                lastSavedFolder = null
            }
        }
    }

    // keepAutoDetect: auto-detect calls this for every address it tries, so it must not cancel
    // itself (before this, it stopped after its first address).
    fun play(url: String, keepAutoDetect: Boolean = false) {
        val currentSurface = surface
        if (currentSurface == null) {
            status = "Surface not ready yet, try again"
            return
        }
        if (!keepAutoDetect) autoDetectJob?.cancel()
        status = "Connecting to $url…"
        client.start(url, currentSurface)
    }

    fun autoDetect() {
        autoDetectJob?.cancel()
        log = emptyList()
        autoDetectJob = scope.launch {
            // Some cameras (e.g. boAt Hive F1 Pro) keep RTSP closed until they are asked over HTTP
            // to enter recorder mode, so wake the camera first and try the address it reports.
            status = "Waking camera…"
            val handshake = withContext(Dispatchers.IO) { YiZhiHandshake.enter(gatewayIp) }
            if (handshake != null) {
                log = log + "Camera ${handshake.model ?: "?"} is awake — stream ${handshake.rtspUrl}"
            }
            val candidates = (listOfNotNull(handshake?.rtspUrl) + StreamCandidates.rtspCandidates(gatewayIp)).distinct()
            for (candidate in candidates) {
                if (!isActive) return@launch
                play(candidate, keepAutoDetect = true)
                val streaming = withTimeoutOrNull(4000) {
                    while (!status.startsWith("Streaming")) delay(100)
                    true
                }
                if (streaming == true) {
                    log = log + "$candidate — OK"
                    return@launch
                } else {
                    log = log + "$candidate — $status"
                    client.stop()
                }
            }
            status = "No RTSP stream found automatically — try a manual URL below"
        }
    }

    fun takeSnapshot() {
        val view = surfaceView ?: run {
            Toast.makeText(context, "Nothing to capture yet", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(view, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val saved = saveSnapshot(context, bitmap)
                val message = if (saved) "Snapshot saved" else "Couldn't save snapshot"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Couldn't capture frame", Toast.LENGTH_SHORT).show()
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun comingSoon(feature: String) {
        Toast.makeText(context, "$feature coming soon", Toast.LENGTH_SHORT).show()
    }

    var hasAutoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(surface) {
        if (surface != null && !hasAutoStarted && autoConnect) {
            hasAutoStarted = true
            autoDetect()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LiveViewTopBar(onBack = onBack, onSettingsClick = { showAdvanced = !showAdvanced })

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                // Screen back on: move the running stream back onto the view.
                                if (status.startsWith("Streaming")) client.setOutputSurface(holder.surface)
                                surface = holder.surface
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                // Screen off / app in background: keep the stream running off-screen.
                                if (status.startsWith("Streaming")) client.setOutputSurface(backgroundSink.surface)
                                surface = null
                            }
                        })
                        surfaceView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (aiEnabled && isStreaming) {
                DetectionOverlay(detections, modifier = Modifier.fillMaxSize())
            }

            if (isStreaming) {
                RecBadge(modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                Text(
                    "boAt HIVE",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                )
            } else {
                BoatHiveLogoPlaceholder(
                    status = status,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // These sit in the whitespace below the video, not on top of it — matching
        // the reference app, where the feed itself stays clean and unobstructed.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AiToggle(
                    enabled = aiEnabled,
                    isStreaming = isStreaming,
                    stats = detectionStats,
                    onToggle = { aiEnabled = it }
                )
                var voiceOn by remember { mutableStateOf(VoiceAlerts.isEnabled(context)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Switch(checked = voiceOn, onCheckedChange = { voiceOn = it; VoiceAlerts.setEnabled(context, it) })
                    Text("Voice alerts (\"Pothole ahead\")", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 10.dp))
                }
                if (driveActive) {
                    val speed = gps.speedKmh?.let { "%.0f km/h".format(it) } ?: "-- km/h"
                    val position = gps.location?.let { "%.6f, %.6f".format(java.util.Locale.US, it.latitude, it.longitude) } ?: "no position yet"
                    Text(
                        "GPS: ${gps.status} · $speed · $position\nTrip: %d min · %.2f km · %d potholes".format(
                            trip.durationS / 60, trip.distanceM / 1000, trip.potholes
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                    )
                }
                // Pothole reports and map during the drive. They open as full-screen panels ON TOP of
                // the live view (not a new screen), so the stream, detection and trip keep running.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { panel = "list" }) {
                        Text("Potholes (${detectionStats.loggedPotholes})")
                    }
                    OutlinedButton(onClick = { panel = "map" }) { Text("Map") }
                }

                // Mini map during the drive: live position, route, known potholes.
                var showMap by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Switch(checked = showMap, onCheckedChange = { showMap = it })
                    Text("Show map", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
                }
                if (showMap) {
                    val liveRoute by TripRecorder.routePoints.collectAsState()
                    var mapPotholes by remember { mutableStateOf<List<com.boat.dashcamcompanion.detect.PotholeJson.Saved>>(emptyList()) }
                    val logged = detectionStats.loggedPotholes
                    LaunchedEffect(logged) {
                        mapPotholes = withContext(Dispatchers.IO) {
                            com.boat.dashcamcompanion.detect.PotholeJson.loadAll(context)
                        }
                    }
                    PotholeMap(
                        potholes = mapPotholes,
                        route = liveRoute,
                        me = gps.location,
                        follow = true,
                        onOpenReport = { id -> panel = "report:$id" },
                        modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                RecordControl(
                    isStreaming = isStreaming,
                    recording = recording,
                    stats = recordStats,
                    savedFolder = lastSavedFolder,
                    onToggle = { toggleRecording() }
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FloatingCircleButton(icon = Icons.Filled.Map, contentDescription = "Location") {
                    comingSoon("Location")
                }
                Spacer(modifier = Modifier.height(8.dp))
                FloatingCircleButton(icon = Icons.Filled.HelpOutline, contentDescription = "Help") {
                    comingSoon("Help")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (showAdvanced) {
            Text(
                "$gatewayIp · $status",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else if (status.startsWith("Error") || status.startsWith("No RTSP")) {
            // Still surface real failures even in the clean view — silently hiding
            // a broken connection would be worse than a stray debug line.
            Text(
                status,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        BottomToolbar(
            isStreaming = isStreaming,
            onPlaybackClick = { comingSoon("Playback") },
            onRecordToggle = {
                if (isStreaming) {
                    client.stop()
                    status = "Stopped"
                } else {
                    autoDetect()
                }
            },
            onSnapshotClick = { takeSnapshot() },
            onFoldClick = onBack,
            onDownloadedFilesClick = { comingSoon("Downloaded files") },
            onAudioRecordingClick = { comingSoon("Audio recording") }
        )

        if (showAdvanced) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = manualUrl,
                    onValueChange = { manualUrl = it },
                    label = {
                        Text(
                            if (autoConnect) "Manual RTSP URL (rtsp://$gatewayIp/...)"
                            else "PC stream URL, e.g. rtsp://192.168.1.20:8554/dashcam"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { if (manualUrl.isNotBlank()) play(manualUrl) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("Connect to manual URL")
                }

                if (log.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .padding(top = 12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    // Panels over the live view (Dialog keeps the live view - and the drive - running underneath).
    panel?.let { current ->
        Dialog(
            onDismissRequest = { panel = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                when {
                    current == "list" -> PotholeListScreen(
                        onBack = { panel = null },
                        onOpen = { id -> panel = "report:$id" }
                    )
                    current == "map" -> MapScreen(
                        onBack = { panel = null },
                        onOpenReport = { id -> panel = "report:$id" }
                    )
                    current.startsWith("report:") -> PotholeDetailScreen(
                        reportId = current.removePrefix("report:"),
                        onClose = { panel = "list" }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveViewTopBar(onBack: () -> Unit, onSettingsClick: () -> Unit) {
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
            "Live Video Preview",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

/**
 * true  = detector gets the camera's own frames from [AnalysisFrameDecoder] (works whatever is on screen).
 * false = detector reads the on-screen video with PixelCopy (the original method).
 */
private const val USE_DECODER_FRAMES = true

/** On/off switch for pothole detection, with a live read-out of how fast it is running. */
@Composable
private fun AiToggle(
    enabled: Boolean,
    isStreaming: Boolean,
    stats: DetectionStats,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val detail = when {
        !enabled -> "Off"
        !isStreaming -> "Waiting for video…"
        stats.framesPerSecond <= 0f -> "Starting…"
        else -> "%.1f checks/s · %d ms each · %d potholes logged".format(
            stats.framesPerSecond, stats.inferenceMs, stats.loggedPotholes
        )
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = onToggle)
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text("AI pothole detection", style = MaterialTheme.typography.labelLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Starts and stops saving the live video and GPS on the phone, for building a training set. */
@Composable
private fun RecordControl(
    isStreaming: Boolean,
    recording: Boolean,
    stats: StreamRecorder.Stats,
    savedFolder: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detail = when {
        recording -> "${RecordFormat.duration(stats.elapsedMs)} · ${RecordFormat.megabytes(stats.bytes)} · " +
            "GPS: ${stats.gpsStatus} (${stats.gpsFixes} fixes)"
        savedFolder != null -> "Saved: $savedFolder"
        !isStreaming -> "Open the live video first"
        else -> "Saves video and GPS on this phone"
    }
    Column(modifier = modifier) {
        Button(
            onClick = onToggle,
            enabled = isStreaming || recording,
            colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFF424242) else Color(0xFFD32F2F)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(if (recording) "Stop recording" else "Record for training", color = Color.White)
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun RecBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC1A1A1A))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            "REC",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** Idle placeholder shown in the video area before a stream is connected, echoing the
 *  boAt Hive Cam app's own branded loading screen. */
@Composable
private fun BoatHiveLogoPlaceholder(status: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HexagonMark(color = Color(0xFF2ECC71), size = 48.dp)
        Text(
            "boAt",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "HIVE",
            color = Color(0xFF2ECC71),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            status,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
internal fun HexagonMark(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val path = Path()
        for (i in 0 until 6) {
            val angle = Math.PI / 180.0 * (60 * i - 90)
            val point = Offset(
                x = center.x + (radius * cos(angle)).toFloat(),
                y = center.y + (radius * sin(angle)).toFloat()
            )
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        drawPath(path, color = color)
    }
}

@Composable
private fun FloatingCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, Color.LightGray, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun BottomToolbar(
    isStreaming: Boolean,
    onPlaybackClick: () -> Unit,
    onRecordToggle: () -> Unit,
    onSnapshotClick: () -> Unit,
    onFoldClick: () -> Unit,
    onDownloadedFilesClick: () -> Unit,
    onAudioRecordingClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FramedToolbarAction(icon = Icons.Filled.Image, label = "Playback", onClick = onPlaybackClick)
            ToolbarAction(
                icon = if (isStreaming) Icons.Filled.FiberManualRecord else Icons.Outlined.FiberManualRecord,
                label = if (isStreaming) "Stop" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRecordToggle
            )
            FramedToolbarAction(icon = Icons.Filled.PhotoCamera, label = "Snapshot", onClick = onSnapshotClick)
            FramedToolbarAction(icon = Icons.Filled.ExpandMore, label = "Fold", onClick = onFoldClick)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FramedToolbarAction(icon = Icons.Filled.FileDownload, label = "Downloaded Files", onClick = onDownloadedFilesClick)
            ToolbarAction(icon = Icons.Filled.Mic, label = "Audio Recording", onClick = onAudioRecordingClick)
            FramedToolbarAction(
                icon = Icons.Filled.Cameraswitch,
                label = "Switch Camera",
                enabled = false,
                onClick = {}
            )
        }
    }
}

@Composable
private fun ToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FramedToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, tint.copy(alpha = if (enabled) 0.4f else 0.2f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center
        )
    }
}

private fun saveSnapshot(context: Context, bitmap: Bitmap): Boolean {
    return try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return false
        val file = File(dir, "dashcam_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        true
    } catch (e: Exception) {
        false
    }
}
