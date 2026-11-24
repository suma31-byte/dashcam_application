package com.boat.dashcamcompanion.detect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.boat.dashcamcompanion.detect.RoadBand.toFullFrame
import com.boat.dashcamcompanion.stream.AnalysisFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** How detection is running, shown on screen so problems are visible straight away. */
data class DetectionStats(
    val framesPerSecond: Float = 0f,
    val inferenceMs: Long = 0L,
    val sharpness: Float = 0f,
    val skippedFrames: Int = 0,
    val loggedPotholes: Int = 0,
    val source: String = ""
)

/**
 * Every [intervalMs]: gets the road strip of the current video frame, checks its quality, runs the
 * detector on a background thread and publishes the boxes (as fractions of the FULL frame) for
 * the overlay to draw.
 *
 * Frame source (see the two run() overloads):
 *  - [AnalysisFrameDecoder]: the camera's own frames from a background decoder (preferred).
 *  - SurfaceView: PixelCopy of what is on screen (original method, kept as a fallback).
 *
 * Then:
 *  - frames with unusable exposure, or sharpness below [minSharpness], are skipped ([FrameQualityEvaluator]);
 *  - a box is shown only once [confirmer] has seen it in several consecutive checks;
 *  - confirmed potholes are logged with GPS and de-duplicated by [eventLog].
 */
class FrameDetectionLoop(
    private val detector: PotholeDetector,
    private val intervalMs: Long = 250L,
    private val confirmer: PotholeConfirmer? = PotholeConfirmer(),
    private val eventLog: PotholeEventLog? = null,
    /** 0 = only skip badly exposed frames. Raise after checking typical values in Logcat. */
    private val minSharpness: Float = 0f
) {
    /** Pothole logging runs here so it never holds up the next check. */
    private val logScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections

    private val _stats = MutableStateFlow(DetectionStats())
    val stats: StateFlow<DetectionStats> = _stats

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Frames from the background decoder only. Runs until the calling coroutine is cancelled. */
    suspend fun run(decoder: AnalysisFrameDecoder) {
        decoder.setEnabled(true)
        try {
            runLoop({ "decoder" }, fullFrame = { decoder.captureFullFrame() }) { decoder.captureRoadStrip() }
        } finally {
            decoder.setEnabled(false)
        }
    }

    /**
     * Recommended for the live view: frames from the background decoder, and if this phone's
     * decoder delivers no frames ([DECODER_FAILURES_BEFORE_FALLBACK] captures in a row), switch to
     * PixelCopy of the on-screen video for the rest of the session - so detection works on
     * every phone, whatever its decoder supports.
     */
    suspend fun run(decoder: AnalysisFrameDecoder, fallbackView: SurfaceView) {
        decoder.setEnabled(true)
        var failures = 0
        var useScreen = false
        val screen = ScreenCapture(fallbackView)
        try {
            runLoop(
                { if (useScreen) "screen" else "decoder" },
                fullFrame = { if (useScreen) screen.lastFullCopy() else decoder.captureFullFrame() }
            ) {
                if (!useScreen) {
                    val frame = decoder.captureRoadStrip()
                    if (frame != null) {
                        failures = 0
                        return@runLoop frame
                    }
                    if (++failures >= DECODER_FAILURES_BEFORE_FALLBACK) {
                        Log.w(TAG, "background decoder gave no frames - switching to screen capture on this phone")
                        useScreen = true
                        decoder.setEnabled(false)
                    }
                    return@runLoop null
                }
                screen.capture()
            }
        } finally {
            decoder.setEnabled(false)
            screen.release()
        }
    }

    /** Any [FrameSource] (e.g. a video file decoded in the background). Runs until cancelled. */
    suspend fun run(source: FrameSource) {
        source.setEnabled(true)
        try {
            runLoop({ source.name }, fullFrame = { source.captureFullFrame() }) { source.captureRoadStrip() }
        } finally {
            source.setEnabled(false)
        }
    }

    /** PixelCopy of the on-screen video only. Runs until the calling coroutine is cancelled. */
    suspend fun run(view: SurfaceView) {
        val screen = ScreenCapture(view)
        try {
            runLoop({ "screen" }, fullFrame = { screen.lastFullCopy() }) { screen.capture() }
        } finally {
            screen.release()
        }
    }

    /** Copies the whole video surface, scaled to the view, then cuts out the road strip. */
    private inner class ScreenCapture(private val view: SurfaceView) {
        private var full: Bitmap? = null
        private var strip: Bitmap? = null

        suspend fun capture(): Bitmap? {
            if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return null
            // Copy in the video's own proportions (PixelCopy scales the frame to the bitmap): a
            // portrait phone video shown in a 16:9 view would otherwise reach the model squashed.
            val aspect = RoadBand.videoAspect
            val width = if (aspect > 0f) minOf(view.width, 1080) else view.width
            val height = if (aspect > 0f) (width * aspect).toInt().coerceAtLeast(2) else view.height
            val top = (height * RoadBand.top).toInt()
            val stripHeight = (height * RoadBand.bottom).toInt() - top
            if (stripHeight <= 0) return null
            // Copy the WHOLE surface: a PixelCopy source Rect is in the surface's own buffer
            // pixels (e.g. 1920x1080 for a file, 960x544 for the dashcam), not in view pixels.
            val frame = full?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { full?.recycle(); full = it }
            if (!copySurface(view, frame)) return null
            val target = strip?.takeIf { it.width == width && it.height == stripHeight }
                ?: Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888).also { strip?.recycle(); strip = it }
            Canvas(target).drawBitmap(frame, Rect(0, top, width, top + stripHeight), Rect(0, 0, width, stripHeight), null)
            return target
        }

        /** Copy of the whole frame from the last capture (for a pothole photo); caller owns it. */
        fun lastFullCopy(): Bitmap? = full?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)

        fun release() {
            full?.recycle(); full = null
            strip?.recycle(); strip = null
        }
    }

    private suspend fun runLoop(
        sourceName: () -> String,
        fullFrame: suspend () -> Bitmap? = { null },
        capture: suspend () -> Bitmap?
    ) {
        var skipped = 0
        var lowLightFrames = 0
        val inferenceTimes = ArrayList<Long>()
        val sessionStart = SystemClock.elapsedRealtime()
        var checks = 0
        _detections.value = emptyList()
        confirmer?.reset()
        eventLog?.start()
        try {
            while (currentCoroutineContext().isActive) {
                val cycleStart = SystemClock.elapsedRealtime()
                val strip = capture()
                // When the picture was taken - used for the pothole's GPS position, because a slow
                // detector (Gemini: seconds) answers when the vehicle has already moved on.
                val capturedAtMs = System.currentTimeMillis()
                if (strip != null) {
                    val inferenceStart = SystemClock.elapsedRealtime()
                    val (quality, raw) = withContext(Dispatchers.Default) {
                        val q = FrameQualityEvaluator.evaluate(strip)
                        when {
                            // Dark frame (night, shade): brighten it and still check it, even if the
                            // quality check calls the exposure unusable.
                            q.luminance < LowLightEnhancer.DARK_LUMINANCE -> {
                                lowLightFrames++
                                val bright = LowLightEnhancer.enhance(strip, q.luminance)
                                try { q to detector.detect(bright) } finally { bright.recycle() }
                            }
                            q.unusableExposure || q.sharpness < minSharpness -> q to null
                            else -> q to detector.detect(strip)
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    checks++
                    if (raw != null) inferenceTimes += now - inferenceStart

                    if (raw == null) {
                        skipped++
                        Log.d(TAG, "skipped frame: sharpness %.1f luminance %.0f clipped %.2f"
                            .format(quality.sharpness, quality.luminance, quality.clipped))
                    } else {
                        // At speed a pothole is visible for well under a second (only ~2-3 checks),
                        // so 2-of-3 confirmation can miss it: then a single CONFIDENT detection is
                        // enough, on top of the normal confirmation.
                        val speedKmh = com.boat.dashcamcompanion.drive.LocationTracker.state.value.speedKmh ?: 0f
                        val confirmedBoxes = confirmer?.update(raw) ?: raw
                        // A CONFIDENT detection is shown and logged at once (any speed): waiting for a
                        // second sighting made the box appear only after the pothole had passed.
                        // Weaker detections still need 2-of-3 confirmation. At speed the bar is lower.
                        val minStrong = if (speedKmh >= FAST_KMH) FAST_MIN_CONFIDENCE else STRONG_CONFIDENCE
                        val shown = if (confirmer != null) {
                            val strong = raw.filter { it.confidence >= minStrong }
                            confirmedBoxes + strong.filter { s -> confirmedBoxes.none { c -> overlap(c, s) } }
                        } else confirmedBoxes
                        _detections.value = shown.map { it.toFullFrame() }
                        if (shown.isNotEmpty() && eventLog != null) {
                            // Log in the background (saving photos waits for the next frame) so the
                            // next check starts right away; the strip is copied because it is reused.
                            val stripCopy = strip.copy(Bitmap.Config.ARGB_8888, false)
                            logScope.launch {
                                try { eventLog.onConfirmed(shown, stripCopy, quality, fullFrame, capturedAtMs) }
                                finally { stripCopy.recycle() }
                            }
                        }
                        Log.d(TAG, "[${sourceName()} ${strip.width}x${strip.height}] raw ${raw.size} " +
                            "confirmed ${shown.size} sharpness %.1f luminance %.0f"
                                .format(quality.sharpness, quality.luminance))
                    }
                    _stats.value = DetectionStats(
                        framesPerSecond = 1000f / (now - cycleStart).coerceAtLeast(1L),
                        inferenceMs = now - inferenceStart,
                        sharpness = quality.sharpness,
                        skippedFrames = skipped,
                        loggedPotholes = eventLog?.count?.value ?: 0,
                        source = sourceName()
                    )
                }
                // At speed: no extra wait between checks - check as often as the phone can.
                val fast = (com.boat.dashcamcompanion.drive.LocationTracker.state.value.speedKmh ?: 0f) >= FAST_KMH
                val wait = if (fast && intervalMs <= 250L) 10L else intervalMs - (SystemClock.elapsedRealtime() - cycleStart)
                delay(wait.coerceAtLeast(10L))
            }
        } finally {
            logSessionSummary(sourceName(), sessionStart, checks, skipped, inferenceTimes, lowLightFrames)
            _detections.value = emptyList()
            _stats.value = DetectionStats()
            confirmer?.reset()
            eventLog?.stop()
        }
    }

    /** PixelCopy reads the video surface (scaled to [into]); it fails harmlessly while the surface is not ready. */
    private suspend fun copySurface(view: SurfaceView, into: Bitmap): Boolean =
        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(view, into, { result ->
                    if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                }, mainHandler)
            } catch (e: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(false)
            }
        }

    /**
     * One summary line per detection session (when AI detection is switched off or the screen is
     * left), like pothole-reporter's drive-end summary - gives the numbers for the FP32/INT8 table.
     */
    private fun logSessionSummary(source: String, startMs: Long, checks: Int, skipped: Int, times: List<Long>, lowLight: Int = 0) {
        if (checks == 0) return
        val seconds = (SystemClock.elapsedRealtime() - startMs) / 1000f
        val sorted = times.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * p).toInt()]
        Log.i(TAG, "SESSION SUMMARY [$source] %.0f s | %d checks (%.2f/s) | skipped %d | ms per check: avg %d, median %d, p95 %d | potholes logged %d | low-light enhanced %d"
            .format(seconds, checks, checks / seconds, skipped,
                if (sorted.isEmpty()) 0 else sorted.average().toLong(), pct(0.5), pct(0.95),
                eventLog?.count?.value ?: 0, lowLight))
    }

    private companion object {
        /** From this speed (GPS) a single confident detection is accepted and checks run back to back. */
        const val FAST_KMH = 35f
        const val FAST_MIN_CONFIDENCE = 0.55f
        /** Below FAST_KMH a detection at least this confident is accepted without confirmation. */
        const val STRONG_CONFIDENCE = 0.65f
        const val TAG = "FrameDetectionLoop"
        /** Each capture waits up to 1 s, so this is ~5 s without decoder frames. */
        const val DECODER_FAILURES_BEFORE_FALLBACK = 5
    }

    private fun overlap(a: Detection, b: Detection): Boolean {
        val iw = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val ih = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = iw * ih
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return union > 0f && inter / union >= 0.3f
    }

}
