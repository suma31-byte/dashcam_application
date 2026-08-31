package com.boat.dashcamcompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Device-type picker shown when the user taps "Add Dash Cam", mirroring the reference
 *  app's screen with a WiFi Dash Cam section (Screen / Screenless / Mirror) and a Cloud
 *  Dash Cam section (4G). All local Wi-Fi options lead into the same install+pairing flow. */
@Composable
fun DeviceTypeScreen(onBack: () -> Unit, onSelectWifiDashCam: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp)
    ) {
        Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Add Dash Cam",
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(48.dp))
        }

        Spacer(Modifier.height(8.dp))

        SectionHeader(
            icon = Icons.Filled.Wifi,
            title = "WiFi Dash Cam",
            subtitle = "Local connection, only supports short-distance connection"
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DeviceTypeCard(
                icon = Icons.Filled.Videocam,
                label = "Screen Dash Cam",
                modifier = Modifier.weight(1f),
                onClick = onSelectWifiDashCam
            )
            DeviceTypeCard(
                icon = Icons.Filled.DirectionsCar,
                label = "Screenless Dash Cam",
                modifier = Modifier.weight(1f),
                onClick = onSelectWifiDashCam
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DeviceTypeCard(
                icon = Icons.Filled.Videocam,
                label = "Mirror Dash Cam",
                modifier = Modifier.weight(1f),
                onClick = onSelectWifiDashCam
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(28.dp))

        SectionHeader(
            icon = Icons.Filled.Cloud,
            title = "Cloud Dash Cam",
            subtitle = "Supports remote connection and remote monitoring"
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DeviceTypeCard(
                icon = Icons.Filled.Videocam,
                label = "4G Cloud Dash Cam",
                enabled = false,
                modifier = Modifier.weight(1f),
                onClick = {}
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Icon(icon, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeviceTypeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        modifier = modifier
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
