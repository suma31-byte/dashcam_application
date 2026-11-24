/*
 * Multi-frame confirmation, following the burst idea in Gaurav Sen's pothole-reporter
 * (https://github.com/coding-parrot/pothole-reporter, MIT License, (c) 2026 Gaurav Sen):
 * one frame is not trusted on its own; a pothole must be seen in several consecutive checks.
 */
package com.boat.dashcamcompanion.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A box is shown only if a matching box was also found in at least [minHits] of the last
 * [windowSize] checked frames (including the current one). This removes most one-frame
 * false positives (shadows, glare) at the cost of ~1 check of delay.
 *
 * Boxes are fractions (0..1) of the road strip. Because the car moves, a pothole shifts
 * between checks, so "matching" accepts a small overlap OR nearby centres.
 */
class PotholeConfirmer(
    private val windowSize: Int = 3,
    private val minHits: Int = 2,
    private val minIou: Float = 0.10f,
    private val maxCenterShift: Float = 0.12f
) {
    private val history = ArrayDeque<List<Detection>>()

    @Synchronized
    fun update(current: List<Detection>): List<Detection> {
        val confirmed = current.filter { det ->
            val hits = 1 + history.count { past -> past.any { matches(det, it) } }
            hits >= minHits
        }
        history.addLast(current)
        while (history.size > windowSize - 1) history.removeFirst()
        return confirmed
    }

    @Synchronized
    fun reset() = history.clear()

    private fun matches(a: Detection, b: Detection): Boolean {
        val (al, at, ar, ab) = a
        val (bl, bt, br, bb) = b
        val iw = max(0f, min(ar, br) - max(al, bl))
        val ih = max(0f, min(ab, bb) - max(at, bt))
        val inter = iw * ih
        val union = (ar - al) * (ab - at) + (br - bl) * (bb - bt) - inter
        val iou = if (union > 0f) inter / union else 0f
        if (iou >= minIou) return true
        val dx = abs((al + ar) / 2f - (bl + br) / 2f)
        val dy = abs((at + ab) / 2f - (bt + bb) / 2f)
        return dx <= maxCenterShift && dy <= maxCenterShift * 2f
    }
}
