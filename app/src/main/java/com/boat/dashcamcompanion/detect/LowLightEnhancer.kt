/*
 * Low-light enhancement before detection, following DetectionImageEnhancement in Gaurav Sen's
 * pothole-reporter (FrameQualityEvaluator.kt, MIT License, (c) 2026 Gaurav Sen): dark frames
 * (night, shade, under flyovers) are brightened so road damage has more visible contrast.
 */
package com.boat.dashcamcompanion.detect

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.pow

object LowLightEnhancer {
    /** Frames darker than this (mean luminance 0..255) are enhanced. */
    const val DARK_LUMINANCE = 80f
    private const val TARGET_LUMINANCE = 115f

    /**
     * Returns a brightened copy of [src] using a gamma curve chosen so the mean luminance moves
     * towards ~115 (shadows lifted much more than highlights, so headlights don't blow out).
     */
    fun enhance(src: Bitmap, meanLuminance: Float): Bitmap {
        val mean = meanLuminance.coerceIn(4f, 250f) / 255f
        val gamma = (ln(TARGET_LUMINANCE / 255.0) / ln(mean.toDouble())).coerceIn(0.35, 1.0)
        val lut = IntArray(256) { v -> ((v / 255.0).pow(gamma) * 255.0).toInt().coerceIn(0, 255) }
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            px[i] = (p and 0xFF000000.toInt()) or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
