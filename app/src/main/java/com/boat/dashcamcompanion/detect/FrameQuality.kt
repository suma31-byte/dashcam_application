/*
 * Frame quality scoring adapted from Gaurav Sen's pothole-reporter
 * https://github.com/coding-parrot/pothole-reporter
 * (android-app/.../drive/FrameQualityEvaluator.kt)
 *
 * Original work: MIT License, Copyright (c) 2026 Gaurav Sen.
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software, subject to the condition that the above copyright notice and this permission
 * notice are included in all copies or substantial portions of the Software.
 */
package com.boat.dashcamcompanion.detect

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Sharpness / brightness of one frame. [score] <= -100 means the exposure is unusable. */
data class FrameQuality(
    val sharpness: Float,
    val luminance: Float,
    val clipped: Float,
    val score: Float
) {
    val unusableExposure: Boolean get() = score <= UNUSABLE_SCORE

    companion object {
        const val UNUSABLE_SCORE = -100f
    }
}

object FrameQualityEvaluator {
    /** Frames are downscaled to this longest side before scoring: fast (a few ms) and stable. */
    private const val SAMPLE_MAX_DIMENSION = 160

    /** Scores the whole bitmap (the road strip in this app). The caller keeps ownership of [bitmap]. */
    fun evaluate(bitmap: Bitmap): FrameQuality {
        val longest = max(bitmap.width, bitmap.height)
        val scale = if (longest > SAMPLE_MAX_DIMENSION) SAMPLE_MAX_DIMENSION.toFloat() / longest else 1f
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val scaled = if (width == bitmap.width && height == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            return score(pixels, width, height)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    /** Same formula as the original FrameQualityEvaluator.scoreRoadPixels(). */
    fun score(pixels: IntArray, width: Int, height: Int): FrameQuality {
        val gray = FloatArray(width * height)
        var total = 0f
        var dark = 0
        var bright = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            val y = 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)
            gray[i] = y
            total += y
            if (y < 12f) dark++
            if (y > 245f) bright++
        }

        // Sharpness = mean absolute Laplacian (edge strength). Blurry frames score low.
        var edgeTotal = 0f
        var samples = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                edgeTotal += abs(4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width])
                samples++
            }
        }

        val n = (width * height).toFloat()
        val luminance = if (n > 0) total / n else 0f
        val clipped = if (n > 0) (dark + bright) / n else 0f
        val sharpness = if (samples > 0) edgeTotal / samples else 0f
        val unusableExposure = clipped > 0.70f || luminance < 18f || luminance > 238f
        val score = if (unusableExposure) FrameQuality.UNUSABLE_SCORE
        else sharpness - abs(luminance - 115f) * 0.055f - clipped * 45f
        return FrameQuality(sharpness, luminance, clipped, score)
    }
}
