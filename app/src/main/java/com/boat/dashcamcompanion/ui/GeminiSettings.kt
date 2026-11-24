package com.boat.dashcamcompanion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boat.dashcamcompanion.vision.VisionVerifier

/**
 * API key + model for pothole DETECTION with Google Gemini, shown right under the
 * "Detect with Google Gemini" switch (video test and live view).
 */
@Composable
fun GeminiDetectionSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(VisionVerifier.geminiKey(context)) }
    var model by remember { mutableStateOf(VisionVerifier.geminiModel(context)) }
    var saved by remember { mutableStateOf(key.isNotBlank()) }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        OutlinedTextField(
            key, { key = it; saved = false },
            label = { Text("Gemini API key (from aistudio.google.com)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            model, { model = it; saved = false },
            label = { Text("Gemini model, e.g. gemini-3.7-flash") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                VisionVerifier.saveGeminiSettings(context, key, model, VisionVerifier.autoVerify(context))
                saved = true
            }) { Text("Save") }
            Text(
                when {
                    key.isBlank() -> "Enter your API key - detection needs it"
                    saved -> "Saved · needs internet (mobile data works on the dashcam's Wi-Fi)"
                    else -> "Not saved yet"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (key.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
