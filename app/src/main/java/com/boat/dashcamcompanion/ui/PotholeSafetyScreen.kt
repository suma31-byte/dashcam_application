package com.boat.dashcamcompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boat.dashcamcompanion.drive.AlertSettings
import com.boat.dashcamcompanion.drive.VoiceAlerts
import com.boat.dashcamcompanion.ui.theme.DashCard
import com.boat.dashcamcompanion.ui.theme.DashCardHeader
import com.boat.dashcamcompanion.ui.theme.DashCheckRow
import com.boat.dashcamcompanion.ui.theme.DashColors
import com.boat.dashcamcompanion.ui.theme.DashPrimaryButton
import com.boat.dashcamcompanion.ui.theme.DashTopBar
import com.boat.dashcamcompanion.ui.theme.DashType

/**
 * Pothole alert settings, laid out like the design's "Safety" screen (Figma "Safety 1.2"):
 * a card with a master toggle and checkbox rows, and a green "Save Changes" button.
 */
@Composable
fun PotholeSafetyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf(AlertSettings.alertsOn(context)) }
    var voice by remember { mutableStateOf(VoiceAlerts.isEnabled(context)) }
    var advance by remember { mutableStateOf(AlertSettings.advanceSetting(context)) }
    var notify by remember { mutableStateOf(AlertSettings.notificationsSetting(context)) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(DashColors.Background)) {
        DashTopBar("Pothole Safety", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Pothole alerts while driving, warnings for known potholes and notifications.", style = DashType.Body)
            DashCard {
                DashCardHeader("Pothole Alerts", "Choose exactly which alerts to receive", all) { all = it; saved = false }
                Spacer(Modifier.height(8.dp))
                DashCheckRow("Voice alert \"Pothole ahead\"", voice, enabled = all) { voice = it; saved = false }
                DashCheckRow("Advance warning (100 m)", advance, enabled = all) { advance = it; saved = false }
                DashCheckRow("Pothole notifications", notify, enabled = all, divider = false) { notify = it; saved = false }
            }
            DashCard {
                DashCardHeader("How it works", "Detection runs on the phone, no internet needed")
                Spacer(Modifier.height(12.dp))
                Text("• \"Pothole ahead\" when the camera sees a new pothole\n" +
                    "• \"Pothole in 100 metres\" before potholes saved on earlier drives\n" +
                    "• A notification for each new pothole, with its photo and location",
                    style = DashType.Caption)
            }
            if (saved) Text("Changes saved", style = DashType.Caption.copy(color = DashColors.Accent))
        }
        DashPrimaryButton(
            "Save Changes",
            onClick = {
                AlertSettings.save(context, all, advance, notify)
                VoiceAlerts.setEnabled(context, voice)
                saved = true
            },
            modifier = Modifier.padding(16.dp)
        )
    }
}
