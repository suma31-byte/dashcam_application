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
import com.boat.dashcamcompanion.stream.RtspH264Client
import com.boat.dashcamcompanion.stream.StreamCandidates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiveViewScreen(gatewayIp: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var manualUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not connected") }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var autoDetectJob by remember { mutableStateOf<Job?>(null) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var showAdvanced by remember { mutableStateOf(false) }

    val client = remember { RtspH264Client { message -> status = message } }
    val isStreaming = status.startsWith("Streaming")

    DisposableEffect(Unit) {
        onDispose { client.stop() }
    }

    fun play(url: String) {
        val currentSurface = surface
        if (currentSurface == null) {
            status = "Surface not ready yet, try again"
            return
        }
        autoDetectJob?.cancel()
        status = "Connecting to $url…"
        client.start(url, currentSurface)
    }

    fun autoDetect() {
        autoDetectJob?.cancel()
        log = emptyList()
        autoDetectJob = scope.launch {
            for (candidate in StreamCandidates.rtspCandidates(gatewayIp)) {
                if (!isActive) return@launch
                play(candidate)
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
        if (surface != null && !hasAutoStarted) {
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
                                surface = holder.surface
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surface = null
                            }
                        })
                        surfaceView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

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
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
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
                    label = { Text("Manual RTSP URL (rtsp://$gatewayIp/...)") },
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
