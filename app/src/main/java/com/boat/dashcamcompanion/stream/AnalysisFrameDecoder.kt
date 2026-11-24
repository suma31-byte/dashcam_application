/*
 * Background frame decoding for analysis, adapted from Gaurav Sen's pothole-reporter
 * (https://github.com/coding-parrot/pothole-reporter, android-app/.../drive/NativeRtspFrameSource.kt,
 * MIT License, (c) 2026 Gaurav Sen).
 *
 * Same structure as NativeRtspFrameSource: the stream is decoded into an app-owned ImageReader
 * (YUV_420_888), the reader is (re)created when the video size is known or changes, and frames
 * are converted to bitmaps for analysis - independent of the on-screen preview.
 *
 * Difference: NativeRtspFrameSource receives the stream through Media3/ExoPlayer's RTSP client.
 * ExoPlayer's RTSP client cannot play the boAt F1 Pro (it sends PLAY to the wrong URL and the
 * camera answers 404 - see RtspH264Client), so here the decoder is fed the access units from this
 * app's own RtspH264Client instead.
 */
package com.boat.dashcamcompanion.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.os.Build
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * A second H.264 decoder next to the one drawing the live view. It is fed the same access units
 * ([RtspH264Client.accessUnitListener]) and renders into an [ImageReader]. When the detection loop
 * asks for a frame ([captureRoadStrip]) the next image from the reader is converted (road strip
 * only) to an ARGB bitmap at the stream's native resolution.
 *
 * The live view's decoder, its timing and its Surface are not touched.
 */
class AnalysisFrameDecoder(
    /** Top of the road strip as a fraction of the frame height (see RoadBand.TOP_FRACTION). */
    private val stripTopFraction: Float,
    /** Used to remember, across app restarts, that this phone can't read ImageReader frames. */
    context: Context? = null,
    /**
     * false (default) = decode into MediaCodec's own output buffers, the standard API that is
     * CPU-readable on every Android phone. true = decode into an ImageReader (the structure of
     * pothole-reporter's NativeRtspFrameSource); some MediaTek/Oplus decoders give the app
     * unreadable ImageReader memory, so this is only used where it has been verified to work.
     */
    preferImageReader: Boolean = false
) {
    private val prefs = context?.applicationContext?.getSharedPreferences("analysis_decoder", Context.MODE_PRIVATE)

    /**
     * Some phones' hardware decoders write into ImageReader buffers the CPU cannot map; reading
     * them aborts the app natively (cannot be caught). Such phones use the decoder's own output
     * buffers instead (MediaCodec.getOutputImage), which are always CPU-readable.
     * A flag set just before the first plane read and cleared after it detects a crash there,
     * so the next start switches mode automatically.
     */
    private var useImageReader: Boolean = preferImageReader && (prefs?.let {
        if (it.getBoolean(KEY_READ_IN_PROGRESS, false)) {
            Log.w(TAG, "previous ImageReader frame read crashed - using decoder buffers on this phone")
            it.edit().putBoolean(KEY_READER_UNSUPPORTED, true).putBoolean(KEY_READ_IN_PROGRESS, false).apply()
        }
        !it.getBoolean(KEY_READER_UNSUPPORTED, false)
    } ?: true)
    private var readerVerified = prefs?.getBoolean(KEY_READER_VERIFIED, false) ?: false
    private val thread = HandlerThread("AnalysisFrameDecoder").also { it.start() }
    private val handler = Handler(thread.looper)

    // All fields below are only touched on [thread].
    private var codec: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var readerWidth = 0
    private var readerHeight = 0
    private var codecConfig: ByteArray? = null
    private var waitingForKeyFrame = true
    private var pixels = IntArray(0)
    private var stripBitmap: Bitmap? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var enabled = false
    @Volatile private var request: CompletableDeferred<Bitmap?>? = null
    @Volatile private var fullRequest: CompletableDeferred<Bitmap?>? = null
    private val pendingFeeds = AtomicInteger(0)

    /** Decoded stream size, 0 until known. */
    @Volatile var frameWidth = 0
        private set
    @Volatile var frameHeight = 0
        private set

    /** Called by the RTSP client once per session with the SPS/PPS. */
    fun setCodecConfig(annexB: ByteArray) {
        handler.post {
            codecConfig = annexB
            releaseCodec()   // a new session may have a new resolution; restart cleanly
            releaseReader()
        }
    }

    /** Decode only while detection is on, to save battery. */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) handler.post {
            releaseCodec()
            request?.complete(null)
            request = null
            fullRequest?.complete(null)
            fullRequest = null
        }
    }

    /** Called on the network thread for every complete frame. Never blocks. */
    fun feed(annexB: ByteArray) {
        if (!enabled) return
        // If decoding falls far behind, drop frames and resume cleanly at the next key frame
        // (P-frames cannot be decoded without the frames before them).
        if (pendingFeeds.get() > MAX_PENDING_FEEDS) {
            handler.post { waitingForKeyFrame = true }
            return
        }
        pendingFeeds.incrementAndGet()
        handler.post {
            pendingFeeds.decrementAndGet()
            decode(annexB)
        }
    }

    /**
     * Returns the road strip of the next decoded frame, or null on timeout / when decoding is
     * not running. The returned bitmap is reused for the next capture; use it before calling again.
     */
    suspend fun captureRoadStrip(timeoutMs: Long = 1000L): Bitmap? {
        if (!enabled) return null
        val deferred = CompletableDeferred<Bitmap?>()
        request = deferred
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) request = null
        return result
    }

    /**
     * The WHOLE next decoded frame (not just the road strip), e.g. as the photo of a newly found
     * pothole. A new bitmap is returned each time; the caller owns it.
     */
    suspend fun captureFullFrame(timeoutMs: Long = 1000L): Bitmap? {
        if (!enabled) return null
        val deferred = CompletableDeferred<Bitmap?>()
        fullRequest = deferred
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) fullRequest = null
        return result
    }

    fun release() {
        enabled = false
        request?.complete(null)
        fullRequest?.complete(null)
        handler.post {
            releaseCodec()
            releaseReader()
            stripBitmap?.recycle()
            stripBitmap = null
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- decoder thread

    private fun decode(annexB: ByteArray) {
        if (!enabled) return
        if (waitingForKeyFrame) {
            if (!containsNalType(annexB, NAL_IDR)) return
            waitingForKeyFrame = false
        }
        val mc = codec ?: createCodec() ?: return
        try {
            val inIndex = mc.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inIndex >= 0) {
                val buffer = mc.getInputBuffer(inIndex)
                buffer?.clear()
                buffer?.put(annexB)
                mc.queueInputBuffer(inIndex, 0, annexB.size, SystemClock.elapsedRealtimeNanos() / 1000, 0)
            } else {
                waitingForKeyFrame = true // input full: skip to next key frame rather than corrupt
            }
            drainOutput(mc)
        } catch (e: Exception) {
            Log.w(TAG, "analysis decode failed, restarting at next key frame", e)
            releaseCodec()
        }
    }

    /**
     * Until the video size is known the decoder runs without a Surface just to learn it
     * (the reader must be created with the real size). Once known, the decoder is restarted
     * with the ImageReader's Surface as output - like NativeRtspFrameSource.onVideoSizeChanged().
     */
    private fun drainOutput(mc: MediaCodec) {
        while (true) {
            val outIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIndex >= 0 -> {
                    if (useImageReader) {
                        // With a reader attached, render=true sends the frame to the ImageReader.
                        mc.releaseOutputBuffer(outIndex, imageReader != null)
                    } else {
                        // Fallback mode: read the decoder's own (CPU-readable) output buffer.
                        if ((request != null || fullRequest != null) && bufferInfo.size > 0) {
                            val image = try { mc.getOutputImage(outIndex) } catch (e: Exception) { null }
                            image?.let { img -> try { deliver(img) } finally { img.close() } }
                        }
                        mc.releaseOutputBuffer(outIndex, false)
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = mc.outputFormat
                    // The reader gets the decoder's full (coded) buffer size, e.g. 960x544; each
                    // Image's cropRect then gives the visible picture inside it.
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val (vw, vh) = visibleSize(format)
                    Log.i(TAG, "analysis decoder output ${w}x$h (visible ${vw}x$vh)")
                    if (useImageReader && w > 0 && h > 0 && (w != readerWidth || h != readerHeight)) {
                        // Size known or changed: (re)create the reader and restart the decoder on it.
                        createReader(w, h)
                        releaseCodec()
                        return
                    }
                }
                else -> return // INFO_TRY_AGAIN_LATER etc.
            }
        }
    }

    private fun createReader(width: Int, height: Int) {
        releaseReader()
        val reader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Ask for CPU-readable buffers explicitly; without this some decoders allocate
            // GPU/video-only memory that the app cannot read.
            ImageReader.newInstance(
                width, height, ImageFormat.YUV_420_888, MAX_READER_IMAGES,
                HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
        } else {
            ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_READER_IMAGES)
        }
        imageReader = reader.apply {
            setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, handler)
        }
        readerWidth = width
        readerHeight = height
        frameWidth = width
        frameHeight = height
        Log.i(TAG, "ImageReader created ${width}x$height")
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (request == null && fullRequest == null) return
            if (!isCpuReadable(image)) {
                Log.w(TAG, "ImageReader frames are not CPU-readable on this phone - switching to decoder buffers")
                switchToDecoderBuffers()
                return
            }
            // Mark the first plane read: if it aborts natively, the next app start sees the flag.
            if (!readerVerified) prefs?.edit()?.putBoolean(KEY_READ_IN_PROGRESS, true)?.commit()
            deliver(image)
            if (!readerVerified) {
                readerVerified = true
                prefs?.edit()?.putBoolean(KEY_READ_IN_PROGRESS, false)?.putBoolean(KEY_READER_VERIFIED, true)?.apply()
            }
        } finally {
            image.close()
        }
    }

    private fun isCpuReadable(image: Image): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val hb = try { image.hardwareBuffer } catch (e: Exception) { null } ?: return true
        return try {
            val readMask = HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_READ_RARELY
            (hb.usage and readMask) != 0L
        } finally {
            hb.close()
        }
    }

    private fun switchToDecoderBuffers() {
        useImageReader = false
        prefs?.edit()?.putBoolean(KEY_READER_UNSUPPORTED, true)?.apply()
        releaseCodec()
        releaseReader()
    }

    private fun createCodec(): MediaCodec? {
        val config = codecConfig ?: return null
        return try {
            val format = MediaFormat.createVideoFormat(
                "video/avc",
                if (readerWidth > 0) readerWidth else 1280,
                if (readerHeight > 0) readerHeight else 720
            ).apply {
                if (!useImageReader) {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, 1088)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            val mc = MediaCodec.createDecoderByType("video/avc")
            // Output into the ImageReader once its size is known; before that, no Surface.
            mc.configure(format, if (useImageReader) imageReader?.surface else null, null, 0)
            mc.start()
            val inIndex = mc.dequeueInputBuffer(INPUT_TIMEOUT_US * 5)
            if (inIndex >= 0) {
                mc.getInputBuffer(inIndex)?.apply { clear(); put(config) }
                mc.queueInputBuffer(inIndex, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            Log.i(TAG, "analysis decoder started (" + when {
                !useImageReader -> "decoder buffers"
                imageReader != null -> "ImageReader ${readerWidth}x$readerHeight"
                else -> "probing size"
            } + ")")
            codec = mc
            mc
        } catch (e: Exception) {
            Log.w(TAG, "could not start analysis decoder", e)
            null
        }
    }

    private fun releaseCodec() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        waitingForKeyFrame = true
    }

    private fun releaseReader() {
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        readerWidth = 0
        readerHeight = 0
    }

    private fun visibleSize(format: MediaFormat): Pair<Int, Int> {
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        return if (format.containsKey("crop-right") && format.containsKey("crop-bottom")) {
            val left = if (format.containsKey("crop-left")) format.getInteger("crop-left") else 0
            val top = if (format.containsKey("crop-top")) format.getInteger("crop-top") else 0
            (format.getInteger("crop-right") - left + 1) to (format.getInteger("crop-bottom") - top + 1)
        } else w to h
    }

    /** Answers the pending full-frame and/or road-strip requests from one decoded image. */
    private fun deliver(image: Image) {
        fullRequest?.let { pending ->
            toBitmap(image, 0f, reuse = false)?.let { fullRequest = null; pending.complete(it) }
        }
        request?.let { pending ->
            toBitmap(image, stripTopFraction, reuse = true)?.let { request = null; pending.complete(it) }
        }
    }

    /**
     * YUV_420_888 → ARGB (BT.601, limited range) for the part of the frame below [topFraction]
     * (0 = whole frame). reuse = true recycles one bitmap for the strip; false returns a new one.
     */
    private fun toBitmap(image: Image, topFraction: Float, reuse: Boolean): Bitmap? {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null
        frameWidth = width
        frameHeight = height
        val top = (height * topFraction).toInt()
        val outH = height - top
        if (outH <= 0) return null

        val bmp = if (!reuse) Bitmap.createBitmap(width, outH, Bitmap.Config.ARGB_8888)
        else stripBitmap?.takeIf { it.width == width && it.height == outH }
            ?: Bitmap.createBitmap(width, outH, Bitmap.Config.ARGB_8888).also {
                stripBitmap?.recycle()
                stripBitmap = it
            }
        if (pixels.size != width * outH) pixels = IntArray(width * outH)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride

        var o = 0
        for (row in 0 until outH) {
            val y = crop.top + top + row
            val yBase = y * yRow
            val uvBase = (y / 2) * uvRow
            for (col in 0 until width) {
                val x = crop.left + col
                val yy = ((yBuf.get(yBase + x * yPix).toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                val uvIndex = uvBase + (x / 2) * uvPix
                val u = (uBuf.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvIndex).toInt() and 0xFF) - 128
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                pixels[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, outH)
        return bmp
    }

    private fun containsNalType(annexB: ByteArray, type: Int): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0) {
                val headerAt = when {
                    annexB[i + 2].toInt() == 1 -> i + 3
                    annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1 -> i + 4
                    else -> -1
                }
                if (headerAt in annexB.indices) {
                    if ((annexB[headerAt].toInt() and 0x1F) == type) return true
                    i = headerAt
                    continue
                }
            }
            i++
        }
        return false
    }

    private companion object {
        const val TAG = "AnalysisFrameDecoder"
        const val NAL_IDR = 5
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_PENDING_FEEDS = 30
        const val MAX_READER_IMAGES = 4   // same pool size as NativeRtspFrameSource
        const val KEY_READ_IN_PROGRESS = "reader_read_in_progress"
        const val KEY_READER_UNSUPPORTED = "reader_unsupported"
        const val KEY_READER_VERIFIED = "reader_verified"
    }
}
