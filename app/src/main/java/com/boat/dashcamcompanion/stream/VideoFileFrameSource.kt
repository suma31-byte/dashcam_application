package com.boat.dashcamcompanion.stream

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.boat.dashcamcompanion.detect.FrameSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decodes a video file in real time on its own thread, independent of the screen - so the
 * "Test with a video file" detection keeps running with the screen off, like a live drive.
 * The on-screen player only shows the picture and follows this source's position.
 *
 * Uses MediaExtractor + MediaCodec output buffers (readable on every phone). Loops at the end.
 * While paused it keeps serving the last frame, so a single frame can be tested.
 */
class VideoFileFrameSource(
    private val context: Context,
    private val stripTopFraction: Float
) : FrameSource {

    override val name = "file"

    /** Current position in the video, ms. */
    @Volatile var positionMs = 0L
        private set
    @Volatile var isPlaying = false
        private set

    @Volatile private var running = false
    @Volatile private var pendingSeekMs = -1L
    @Volatile private var stripRequest: CompletableDeferred<Bitmap?>? = null
    @Volatile private var fullRequest: CompletableDeferred<Bitmap?>? = null
    private var thread: Thread? = null
    private var lastFull: Bitmap? = null   // decode thread only
    private var lastStrip: Bitmap? = null  // decode thread only (served again while paused)

    fun open(uri: Uri, startMs: Long = 0L) {
        close()
        positionMs = startMs
        pendingSeekMs = if (startMs > 0) startMs else -1L
        running = true
        isPlaying = true
        thread = Thread({ decodeLoop(uri) }, "VideoFileFrameSource").also { it.start() }
    }

    fun play() { isPlaying = true }
    fun pause() { isPlaying = false }

    fun seekTo(ms: Long) {
        positionMs = ms.coerceAtLeast(0)
        pendingSeekMs = positionMs
    }

    fun close() {
        running = false
        thread?.join(1500)
        thread = null
        stripRequest?.complete(null); stripRequest = null
        fullRequest?.complete(null); fullRequest = null
    }

    override fun setEnabled(on: Boolean) {
        if (!on) {
            stripRequest?.complete(null); stripRequest = null
            fullRequest?.complete(null); fullRequest = null
        }
    }

    override suspend fun captureRoadStrip(): Bitmap? = await(true)
    override suspend fun captureFullFrame(): Bitmap? = await(false)

    private suspend fun await(strip: Boolean): Bitmap? {
        if (!running) return null
        val d = CompletableDeferred<Bitmap?>()
        if (strip) stripRequest = d else fullRequest = d
        val r = withTimeoutOrNull(1500L) { d.await() }
        if (r == null) { if (strip) stripRequest = null else fullRequest = null }
        return r
    }

    // ---------------------------------------------------------------- decode thread

    /** While paused: answer requests from the last converted frame/strip. */
    private fun servePaused() {
        val strip = lastStrip
        stripRequest?.let { p -> if (strip != null) { stripRequest = null; p.complete(strip.copy(Bitmap.Config.ARGB_8888, false)) } }
        val full = lastFull ?: strip
        fullRequest?.let { p -> if (full != null) { fullRequest = null; p.complete(full.copy(Bitmap.Config.ARGB_8888, true)) } }
    }

    /**
     * While playing: convert only what is asked for - the road strip for each check (small and
     * fast, same 960 px width as the dashcam stream) and the whole frame only for a new
     * pothole's photo.
     */
    private fun serve(image: android.media.Image) {
        if (stripRequest != null) {
            // road band of this video (RoadBand: dashcam strip, or 25-88 % for portrait phone videos)
            YuvToBitmap.convert(image, maxWidth = 960, topFraction = com.boat.dashcamcompanion.detect.RoadBand.top,
                bottomFraction = com.boat.dashcamcompanion.detect.RoadBand.bottom)?.let { strip ->
                lastStrip?.recycle()
                lastStrip = strip
                stripRequest?.let { p -> stripRequest = null; p.complete(strip.copy(Bitmap.Config.ARGB_8888, false)) }
            }
        }
        if (fullRequest != null) {
            YuvToBitmap.convert(image, maxWidth = 1280)?.let { full ->
                lastFull?.recycle()
                lastFull = full
                fullRequest?.let { p -> fullRequest = null; p.complete(full.copy(Bitmap.Config.ARGB_8888, true)) }
            }
        }
    }

    private fun decodeLoop(uri: Uri) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val ex = MediaExtractor().also { extractor = it }
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("no video track")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // portrait phone video → road band higher up (rotation swaps width/height)
            val rot = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
            val vw = format.getInteger(MediaFormat.KEY_WIDTH)
            val vh = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (rot == 90 || rot == 270) com.boat.dashcamcompanion.detect.RoadBand.setForVideo(vh, vw)
            else com.boat.dashcamcompanion.detect.RoadBand.setForVideo(vw, vh)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val mc = MediaCodec.createDecoderByType(mime).also { codec = it }
            mc.configure(format, null, null, 0)
            mc.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var clockNs = System.nanoTime()
            var clockUs = positionMs * 1000

            while (running) {
                val seek = pendingSeekMs
                if (seek >= 0) {
                    pendingSeekMs = -1
                    ex.seekTo(seek * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    mc.flush()
                    inputDone = false
                    clockUs = seek * 1000
                    clockNs = System.nanoTime()
                }
                if (!isPlaying) {
                    if (stripRequest != null || fullRequest != null) servePaused()
                    Thread.sleep(30)
                    clockNs = System.nanoTime()
                    clockUs = positionMs * 1000
                    continue
                }
                if (!inputDone) {
                    val inIdx = mc.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val buf = mc.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            mc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            mc.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = mc.dequeueOutputBuffer(info, 5_000)
                if (outIdx < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    mc.releaseOutputBuffer(outIdx, false)
                    // loop the video
                    ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    mc.flush()
                    inputDone = false
                    positionMs = 0
                    clockUs = 0
                    clockNs = System.nanoTime()
                    continue
                }
                val pts = info.presentationTimeUs
                if (pts >= clockUs) {
                    // real-time pacing
                    val waitNs = clockNs + (pts - clockUs) * 1000 - System.nanoTime()
                    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                    positionMs = pts / 1000
                    if ((stripRequest != null || fullRequest != null) && info.size > 0) {
                        val image = try { mc.getOutputImage(outIdx) } catch (e: Exception) { null }
                        image?.let { img -> try { serve(img) } finally { img.close() } }
                    }
                }
                mc.releaseOutputBuffer(outIdx, false)
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Exception) {
            Log.w(TAG, "video file decoding stopped", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            lastFull?.recycle()
            lastFull = null
            lastStrip?.recycle()
            lastStrip = null
        }
    }

    private companion object {
        const val TAG = "VideoFileFrameSource"
    }
}
