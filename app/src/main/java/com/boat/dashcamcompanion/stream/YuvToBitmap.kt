package com.boat.dashcamcompanion.stream

import android.graphics.Bitmap
import android.media.Image

/**
 * YUV_420_888 Image → ARGB Bitmap (BT.601, limited range), visible area only.
 *  - [topFraction] > 0 converts only the part below it (e.g. the road strip) - much less work.
 *  - Frames wider than [maxWidth] are converted at a reduced size (every 2nd/3rd… pixel); the
 *    detector scales to 640 px anyway.
 * Planes are copied once into arrays (bulk copy), which is several times faster than reading
 * the ByteBuffers pixel by pixel.
 */
object YuvToBitmap {
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

    @Synchronized
    fun convert(image: Image, maxWidth: Int = 960, topFraction: Float = 0f, bottomFraction: Float = 1f): Bitmap? {
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return null
        val step = ((crop.width() + maxWidth - 1) / maxWidth).coerceAtLeast(1)
        val firstRow = (crop.height() * topFraction).toInt()
        val width = crop.width() / step
        val lastRow = (crop.height() * bottomFraction).toInt().coerceIn(firstRow + 1, crop.height())
        val height = (lastRow - firstRow) / step
        if (width <= 0 || height <= 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        yBytes = copy(yPlane.buffer, yBytes)
        uBytes = copy(uPlane.buffer, uBytes)
        vBytes = copy(vPlane.buffer, vBytes)
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride

        val pixels = IntArray(width * height)
        var o = 0
        for (row in 0 until height) {
            val y = crop.top + firstRow + row * step
            val yBase = y * yRow
            val uvBase = (y / 2) * uvRow
            for (col in 0 until width) {
                val x = crop.left + col * step
                val yy = ((yBytes[yBase + x * yPix].toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                val uvIndex = uvBase + (x / 2) * uvPix
                val u = (uBytes[uvIndex].toInt() and 0xFF) - 128
                val v = (vBytes[uvIndex].toInt() and 0xFF) - 128
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                pixels[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun copy(buf: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
        val b = buf.duplicate()
        b.rewind()
        val out = if (reuse.size >= b.remaining()) reuse else ByteArray(b.remaining())
        b.get(out, 0, b.remaining())
        return out
    }
}
