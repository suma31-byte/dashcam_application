package com.boat.dashcamcompanion.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boat.dashcamcompanion.detect.Detection
import com.boat.dashcamcompanion.detect.RoadBand

/**
 * Draws pothole boxes over the live video. Boxes arrive as fractions of the full frame, and the
 * video fills this area exactly (the player stretches the 16:9 stream to the 16:9 view), so a
 * fraction maps straight to a pixel position. A thin line marks the top of the zone the AI analyses.
 */
@Composable
fun DetectionOverlay(detections: List<Detection>, modifier: Modifier = Modifier) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    Canvas(modifier = modifier) {
        val zoneTop = size.height * RoadBand.top
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = Offset(0f, zoneTop),
            end = Offset(size.width, zoneTop),
            strokeWidth = 1.dp.toPx()
        )
        labelPaint.textSize = 12.sp.toPx()
        detections.forEach { d ->
            val topLeft = Offset(d.left * size.width, d.top * size.height)
            val boxSize = Size((d.right - d.left) * size.width, (d.bottom - d.top) * size.height)
            drawRect(Color(0xFFFF3B30), topLeft, boxSize, style = Stroke(width = 2.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(
                "%.2f".format(d.confidence),
                topLeft.x + 3.dp.toPx(),
                (topLeft.y - 3.dp.toPx()).coerceAtLeast(labelPaint.textSize),
                labelPaint
            )
        }
    }
}
