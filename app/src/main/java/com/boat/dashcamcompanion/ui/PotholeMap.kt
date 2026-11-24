package com.boat.dashcamcompanion.ui

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.boat.dashcamcompanion.detect.PotholeJson
import com.boat.dashcamcompanion.drive.LocationTracker
import com.boat.dashcamcompanion.drive.TripRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Map with: the vehicle's live position (blue dot), the route of the current trip (or the last
 * saved trip), and every saved pothole (red = detected, green = confirmed by the vision model,
 * grey = vision model said "not road damage"). Tapping a pothole opens its report.
 * Map tiles: OpenStreetMap (needs internet; tiles already seen are cached on the phone).
 */
@Composable
fun PotholeMap(
    potholes: List<PotholeJson.Saved>,
    route: List<Pair<Double, Double>>,
    me: Location?,
    follow: Boolean,
    onOpenReport: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var centred by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName           // required by the OpenStreetMap tile servers
                load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            if (route.size >= 2) {
                map.overlays += Polyline(map).apply {
                    setPoints(route.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = AColor.rgb(33, 102, 220)
                    outlinePaint.strokeWidth = 10f
                }
            }
            for (s in potholes) {
                val e = s.event
                if (e.lat == null || e.lon == null) continue
                val colour = when {
                    e.assessment == "damaged" -> AColor.rgb(46, 125, 50)
                    e.assessment?.startsWith("undamaged") == true -> AColor.GRAY
                    else -> AColor.rgb(211, 47, 47)
                }
                map.overlays += Marker(map).apply {
                    position = GeoPoint(e.lat, e.lon)
                    icon = dot(context, colour, 18)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = e.place ?: "Pothole ${e.reportId}"
                    setOnMarkerClickListener { _, _ -> onOpenReport(e.reportId); true }
                }
            }
            if (me != null) {
                val p = GeoPoint(me.latitude, me.longitude)
                map.overlays += Marker(map).apply {
                    position = p
                    icon = dot(context, AColor.rgb(25, 118, 210), 26)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You"
                }
                if (follow || !centred) {
                    map.controller.animateTo(p)
                    centred = true
                }
            } else if (!centred) {
                val first = potholes.firstOrNull { it.event.lat != null }?.event
                val start = first?.let { GeoPoint(it.lat!!, it.lon!!) }
                    ?: route.lastOrNull()?.let { GeoPoint(it.first, it.second) }
                if (start != null) {
                    map.controller.setCenter(start)
                    centred = true
                }
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() }
    )
}

private fun dot(context: Context, colour: Int, sizeDp: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(colour)
    val px = (sizeDp * context.resources.displayMetrics.density).toInt()
    setSize(px, px)
    setStroke((2 * context.resources.displayMetrics.density).toInt(), AColor.WHITE)
}

/** Full-screen map from the home screen: live position, current/last trip, all potholes. */
@Composable
fun MapScreen(onBack: () -> Unit, onOpenReport: (String) -> Unit) {
    val context = LocalContext.current
    var potholes by remember { mutableStateOf<List<PotholeJson.Saved>>(emptyList()) }
    var lastRoute by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        potholes = withContext(Dispatchers.IO) { PotholeJson.loadAll(context) }
        lastRoute = withContext(Dispatchers.IO) { TripRecorder.loadLatestRoute(context) }
    }
    // GPS while the map is open
    DisposableEffect(Unit) {
        LocationTracker.start(context.applicationContext)
        onDispose { LocationTracker.stop() }
    }
    val gps by LocationTracker.state.collectAsState()
    val liveRoute by TripRecorder.routePoints.collectAsState()
    val trip by TripRecorder.stats.collectAsState()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Pothole map", color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("Follow", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Switch(checked = follow, onCheckedChange = { follow = it }, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Box(Modifier.weight(1f)) {
            PotholeMap(
                potholes = potholes,
                route = if (trip.active) liveRoute else lastRoute,
                me = gps.location,
                follow = follow,
                onOpenReport = onOpenReport,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            "${potholes.count { it.event.lat != null }} potholes with GPS · GPS: ${gps.status}" +
                (if (trip.active) " · trip %.2f km".format(trip.distanceM / 1000) else "") +
                "\nRed = detected · Green = confirmed · Grey = not damage · Map © OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp)
        )
    }
}
