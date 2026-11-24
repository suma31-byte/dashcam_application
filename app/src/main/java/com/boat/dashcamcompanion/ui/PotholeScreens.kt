package com.boat.dashcamcompanion.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.boat.dashcamcompanion.detect.PotholeJson
import com.boat.dashcamcompanion.vision.VisionVerifier
import com.boat.dashcamcompanion.net.InternetConnection
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US)

// ======================================================================== list

/** Every pothole saved on this phone, newest first. Tap one for the full report. */
@Composable
fun PotholeListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<PotholeJson.Saved>?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(reloadKey) { items = withContext(Dispatchers.IO) { PotholeJson.loadAll(context) } }

    // Local vision model (Ollama) settings and batch verification
    val scope = rememberCoroutineScope()
    var showVision by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(VisionVerifier.serverUrl(context)) }
    var model by remember { mutableStateOf(VisionVerifier.model(context)) }
    var provider by remember { mutableStateOf(VisionVerifier.provider(context)) }
    var geminiKey by remember { mutableStateOf(VisionVerifier.geminiKey(context)) }
    var geminiModel by remember { mutableStateOf(VisionVerifier.geminiModel(context)) }
    var autoCheck by remember { mutableStateOf(VisionVerifier.autoVerify(context)) }
    val saveVisionSettings = {
        VisionVerifier.setProvider(context, provider)
        VisionVerifier.saveSettings(context, server, model)
        VisionVerifier.saveGeminiSettings(context, geminiKey, geminiModel, autoCheck)
    }
    var progress by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all potholes?") },
            text = { Text("All saved potholes, photos and their history on this phone will be deleted. Trips are kept. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { PotholeJson.deleteAll(context) }
                        com.boat.dashcamcompanion.drive.KnownPotholes.clear()
                        progress = "Deleted $n drive(s) of pothole records"
                        reloadKey++
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Potholes found", onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val unchecked = items?.count { it.event.visionProvider == null && it.photo != null } ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !running && unchecked > 0,
                    onClick = {
                        saveVisionSettings()
                        running = true
                        scope.launch {
                            val todo = items.orEmpty().filter { it.event.visionProvider == null && it.photo != null }
                            var ok = 0
                            var failed = 0
                            for ((i, saved) in todo.withIndex()) {
                                progress = "Checking ${i + 1}/${todo.size} with ${VisionVerifier.activeModel(context)}…"
                                try {
                                    VisionVerifier.verify(context, saved); ok++
                                } catch (e: Exception) {
                                    failed++
                                    progress = "Error: ${e.message}"
                                    if (i == 0) break   // server not reachable: stop at once
                                }
                            }
                            if (failed == 0 || ok > 0) progress = "Checked $ok" + if (failed > 0) ", $failed failed" else ""
                            running = false
                            reloadKey++
                        }
                    }
                ) { Text(if (running) "Checking…" else "Verify with vision model ($unchecked)") }
                TextButton(onClick = { showVision = !showVision }) { Text(if (showVision) "Hide settings" else "Settings") }
            }
            TextButton(onClick = { confirmDelete = true }, enabled = !running && !items.isNullOrEmpty()) {
                Text("Delete all potholes (clear test history)", color = MaterialTheme.colorScheme.error)
            }
            if (showVision) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vision model:", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { provider = "gemini"; saveVisionSettings() }) {
                        Text(if (provider == "gemini") "● Google Gemini (cloud)" else "○ Google Gemini (cloud)")
                    }
                    TextButton(onClick = { provider = "ollama"; saveVisionSettings() }) {
                        Text(if (provider == "ollama") "● Ollama (own PC)" else "○ Ollama (own PC)")
                    }
                }
                if (provider == "gemini") {
                    OutlinedTextField(geminiKey, { geminiKey = it }, label = { Text("Gemini API key (aistudio.google.com)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    OutlinedTextField(geminiModel, { geminiModel = it }, label = { Text("Model, e.g. gemini-3.7-flash") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = autoCheck, onCheckedChange = { autoCheck = it; saveVisionSettings() })
                        Text("Check each new pothole automatically (when there is internet)",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("Photos are sent to Google for checking. Needs internet (mobile data works while on the dashcam's Wi-Fi).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(server, { server = it }, label = { Text("Ollama server, e.g. http://192.168.20.186:11434") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it }, label = { Text("Vision model, e.g. qwen2.5vl:7b") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("The phone must be on the same network as the server (not the dashcam's Wi-Fi).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { saveVisionSettings(); progress = "Settings saved" }) { Text("Save settings") }
            }
            progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
        Divider()
        val list = items
        when {
            list == null -> Text("Loading…", Modifier.padding(16.dp))
            list.isEmpty() -> Text(
                "No potholes saved yet. Turn on AI pothole detection in the live view or a video test.",
                Modifier.padding(16.dp)
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.folder.name + "/" + it.event.id }) { saved ->
                    val e = saved.event
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(e.reportId) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val thumb = rememberImage(saved.strip ?: saved.photo, maxSide = 320)
                        Box(Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(e.place ?: coords(e.lat, e.lon) ?: "No location", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(
                                "${TIME.format(Date(e.firstSeenMs))} · ${e.sizeClass} · %.0f%%".format(e.maxConfidence * 100),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                e.captureSource + if (e.drivesSeen > 1) " · seen on ${e.drivesSeen} drives" else "",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                when {
                                    e.visionProvider == null -> "Not checked by vision model"
                                    e.assessment == "damaged" -> "✓ Confirmed: ${e.damageType}"
                                    else -> "✗ Vision model: not road damage"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    e.visionProvider == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    e.assessment == "damaged" -> Color(0xFF2E7D32)
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

// ======================================================================== detail

/** The full report for one pothole (fields follow pothole-reporter's report view). */
@Composable
fun PotholeDetailScreen(reportId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf<PotholeJson.Saved?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf<String?>(null) }
    var addressTried by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(reportId, reloadKey) {
        saved = withContext(Dispatchers.IO) { PotholeJson.loadAll(context).firstOrNull { it.event.reportId == reportId } }
        loaded = true
        val e = saved?.event
        // Offline place name first; otherwise ask OpenStreetMap if it answers within 4 s.
        addressTried = false
        address = e?.place ?: if (e?.lat != null && e.lon != null) fetchAddress(context, e.lat, e.lon) else null
        addressTried = true
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar("Pothole report", onClose)
        val s = saved
        if (s == null) {
            Text(if (loaded) "This pothole was not found." else "Loading…", Modifier.padding(16.dp))
        } else {
            // No early "return" inside Column: Compose cannot handle returns out of inline
            // layout lambdas (it crashed with IndexOutOfBoundsException in Stack.pop).
            ReportBody(s, address ?: if (addressTried) null else "looking up…", onClose, onVerified = { reloadKey++ })
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReportBody(
    s: PotholeJson.Saved,
    address: String?,
    onClose: () -> Unit,
    onVerified: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf<String?>(null) }
    val e = s.event
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // ---- photo
            val photo = rememberImage(s.photo, maxSide = 1600)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                photo?.let { Image(it, "Pothole photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }

            // ---- location
            Section("Location")
            Text(
                coords(e.lat, e.lon) ?: "No GPS position",
                fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Field("Address", address ?: if (e.lat != null) "not available (no internet connection)" else "—")
            Field("GPS accuracy", e.accuracyM?.let { "± %.0f m".format(it) } ?: "—")
            Field("Location source", e.locationSource)
            Field("Location age", e.fixAgeMs?.let { if (it >= 0) "%.1f s".format(it / 1000.0) else "fix %.1f s after the frame".format(-it / 1000.0) } ?: "—")

            // ---- pothole details
            Section("Pothole details")
            Field("Damage type", e.damageType ?: "pending (vision model)")
            Field("Size", "${e.sizeClass} (approx., from box size)")
            Field("Assessment", e.assessment ?: "pending (vision model)")
            Field("Image quality", e.imageQuality)
            Field("Detector confidence", "%.0f%% · seen %d times".format(e.maxConfidence * 100, e.seenCount))
            Field("Description", e.description ?: "pending (vision model)")

            // ---- time and movement
            Section("Time and movement")
            Field("Detected at", TIME.format(Date(e.firstSeenMs)))
            Field("Seen on", if (e.drivesSeen > 1) "${e.drivesSeen} drives · last ${TIME.format(Date(e.lastDriveSeenMs ?: e.lastSeenMs))}" else "1 drive (not seen again yet)")
            Field("Captured at", if (e.videoOffsetMs != null) "from imported video" else TIME.format(Date(e.firstSeenMs)))
            e.videoOffsetMs?.let { Field("Video offset", "%d:%04.1f".format(it / 60000, (it % 60000) / 1000.0)) }
            Field("Speed", e.speedMps?.let { "%.0f km/h".format(it * 3.6f) } ?: "—")
            Field("Heading", e.bearingDeg?.let { "%.0f° (%s)".format(it, compass(it)) } ?: "—")

            // ---- source and identifiers
            Section("Source and identifiers")
            Field("Capture source", e.captureSource)
            Field("Drive ID", e.driveId ?: "—")
            Field("Detection model", e.model)
            Field("Vision provider", e.visionProvider ?: "not checked yet")
            e.promptVersion?.let { Field("Prompt version", it) }
            e.visionConfidence?.let { Field("Vision confidence", "%.0f%%".format(it * 100)) }
            e.visionCheckedAtMs?.let { Field("Checked at", TIME.format(Date(it))) }
            e.visionRequestId?.let { Field("Vision request ID", it) }
            TextButton(
                enabled = checking == null && s.photo != null,
                onClick = {
                    checking = "Checking with ${VisionVerifier.activeModel(context)}…"
                    scope.launch {
                        checking = try {
                            VisionVerifier.verify(context, s); onVerified(); null
                        } catch (ex: Exception) {
                            "Error: ${ex.message}"
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text(if (e.visionProvider == null) "Check with vision model" else "Check again") }
            checking?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            Field("Report ID", e.reportId)
            Text(
                "Map data © OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        // ---- buttons
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = e.lat != null, onClick = { openInMaps(context, e.lat!!, e.lon!!) }, modifier = Modifier.weight(1f)) {
                Text("Open in Maps")
            }
            Button(onClick = { share(context, s, address) }, modifier = Modifier.weight(1f)) { Text("Share") }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
        }
}

// ======================================================================== helpers

@Composable
private fun TopBar(title: String, onBack: () -> Unit) =
    com.boat.dashcamcompanion.ui.theme.DashTopBar(title, onBack)   // design: round back button + 24 sp title

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun rememberImage(file: File?, maxSide: Int): ImageBitmap? {
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        image = file?.let { f ->
            withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                } catch (e: Exception) { null }
            }
        }
    }
    return image
}

private fun coords(lat: Double?, lon: Double?): String? =
    if (lat == null || lon == null) null else "%.6f, %.6f".format(Locale.US, lat, lon)

private fun compass(deg: Float): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]

/** Street address from OpenStreetMap (Nominatim), only if it answers within 4 seconds. */
private suspend fun fetchAddress(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
    try {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&lat=$lat&lon=$lon&accept-language=en"
        // over a network with internet - not the dashcam's Wi-Fi the app is bound to
        val conn = InternetConnection.open(context, url).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "DashcamCompanion/1.0 (pothole detection; ${context.packageName})")
        }
        conn.inputStream.bufferedReader().use { JSONObject(it.readText()).optString("display_name").ifEmpty { null } }
    } catch (e: Exception) {
        android.util.Log.w("PotholeReport", "address lookup failed: ${e.message}")
        null
    }
}

private fun mapLink(lat: Double, lon: Double) = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, lat, lon)

private fun openInMaps(context: Context, lat: Double, lon: Double) {
    val label = URLEncoder.encode("Pothole", "UTF-8")
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:%.6f,%.6f?q=%.6f,%.6f($label)".format(Locale.US, lat, lon, lat, lon)))
    try {
        context.startActivity(geo)
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapLink(lat, lon))))
        } catch (e2: Exception) {
            Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Shares the whole report as text (with a map link) and the photo, to WhatsApp, SMS, email… */
private fun share(context: Context, s: PotholeJson.Saved, address: String?) {
    val e = s.event
    val text = buildString {
        appendLine("Pothole report ${e.reportId}")
        e.lat?.let { appendLine("Location: ${coords(e.lat, e.lon)} (± ${e.accuracyM?.let { a -> "%.0f".format(a) } ?: "?"} m)") }
        address?.let { appendLine("Address: $it") }
        if (e.lat != null && e.lon != null) appendLine("Map: ${mapLink(e.lat, e.lon)}")
        appendLine("Detected: ${TIME.format(Date(e.firstSeenMs))}")
        appendLine("Size: ${e.sizeClass} · confidence %.0f%% · seen %d times".format(e.maxConfidence * 100, e.seenCount))
        e.damageType?.let { appendLine("Damage type: $it") }
        e.description?.let { appendLine("Description: $it") }
        e.speedMps?.let { appendLine("Speed: %.0f km/h".format(it * 3.6f)) }
        e.bearingDeg?.let { appendLine("Heading: %.0f° (%s)".format(it, compass(it))) }
        appendLine("Source: ${e.captureSource} · model ${e.model}")
        e.driveId?.let { appendLine("Drive: $it") }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        val photo = s.photo
        if (photo != null) {
            try {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", photo)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ex: Exception) {
                type = "text/plain"
            }
        } else {
            type = "text/plain"
        }
    }
    context.startActivity(Intent.createChooser(intent, "Share pothole report"))
}
