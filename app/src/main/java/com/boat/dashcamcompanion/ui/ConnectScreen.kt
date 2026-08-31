package com.boat.dashcamcompanion.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boat.dashcamcompanion.wifi.DashcamWifiManager

@Composable
fun ConnectScreen(
    wifiManager: DashcamWifiManager,
    onBack: () -> Unit = {},
    onContinueToLiveView: (gatewayIp: String) -> Unit
) {
    val context = LocalContext.current
    val linkInfo by wifiManager.linkInfo.collectAsState()
    val nearbyNetworks by wifiManager.nearbyDashcamNetworks.collectAsState()
    var binding by remember { mutableStateOf(false) }
    var bindError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        wifiManager.pollCurrentWifi()
    }

    val looksLikeCamera = linkInfo.ssid?.let { ssid ->
        val lower = ssid.lowercase()
        lower.contains("dashcam") || lower.contains("hive") || lower.contains("boat") || lower.contains("cam")
    } ?: false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        Text(
            text = "Connect to the Dash Cam's Wi-Fi",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "1. Please select the Dashcam's Wi-Fi network in the Settings — WLAN option on your mobile phone and enter the password.\n" +
                "2. Enter the default password: 12345678 (or as mentioned in your manual).\n" +
                "3. Once connected, return to the app to continue setup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        WlanCard(
            linkInfo = linkInfo,
            nearbyNetworks = nearbyNetworks,
            onNetworkInfoClick = { network ->
                Toast.makeText(context, "${network.ssid} · signal ${network.signalLevel} dBm", Toast.LENGTH_SHORT).show()
            }
        )
        bindError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = if (looksLikeCamera) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (looksLikeCamera) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Text(
                "Wi-Fi Connected",
                modifier = Modifier.padding(start = 8.dp),
                color = if (looksLikeCamera) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = { wifiManager.openSystemWifiSettings() },
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Open Wi-Fi Settings", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                binding = true
                bindError = null
                wifiManager.bindToCurrentWifi { success ->
                    binding = false
                    if (success) {
                        val ip = wifiManager.linkInfo.value.gatewayIp ?: "192.168.1.254"
                        onContinueToLiveView(ip)
                    } else {
                        bindError = "Couldn't bind to the Wi-Fi network. Make sure you're connected to the dashcam first."
                    }
                }
            },
            enabled = linkInfo.ssid != null && !binding,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(bottom = 24.dp)
        ) {
            if (binding) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Text("Go to Live View", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun WlanCard(
    linkInfo: DashcamWifiManager.WifiLinkInfo,
    nearbyNetworks: List<DashcamWifiManager.NearbyNetwork>,
    onNetworkInfoClick: (DashcamWifiManager.NearbyNetwork) -> Unit
) {
    // Flat, borderless panel — the reference app's WLAN block sits on a plain
    // light surface, not an elevated/outlined Material Card.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("WLAN", style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = linkInfo.ssid != null,
                onCheckedChange = null,
                enabled = false,
                colors = SwitchDefaults.colors(
                    disabledCheckedThumbColor = Color.White,
                    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary,
                    disabledCheckedBorderColor = MaterialTheme.colorScheme.primary,
                    disabledUncheckedThumbColor = Color.White,
                    disabledUncheckedTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )
        }

        if (linkInfo.ssid != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        linkInfo.ssid,
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp)
                    )
                }
            }
            if (linkInfo.gatewayIp != null) {
                Text(
                    "Gateway: ${linkInfo.gatewayIp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                )
            }
        } else {
            Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WifiOff, contentDescription = null)
                Text("Not connected to any Wi-Fi", modifier = Modifier.padding(start = 8.dp))
            }
        }

        val otherNetworks = nearbyNetworks.filter { it.ssid != linkInfo.ssid }
        if (otherNetworks.isNotEmpty()) {
            Text(
                "MY NETWORKS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            otherNetworks.forEach { network ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(network.ssid, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Icon(
                            Icons.Filled.Wifi,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(16.dp)
                        )
                        IconButton(onClick = { onNetworkInfoClick(network) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Info, contentDescription = "Network info", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
