package com.boat.dashcamcompanion.record

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Saves the live stream for later use as training data. A session folder contains:
 *  - video.h264  the raw H.264 the camera sent, exactly as the app decodes it (plays in ffmpeg/VLC)
 *  - frames.csv  one row per frame: arrival time on the phone, RTP timestamp, size
 *  - gps.csv     the phone's GPS fixes (see [GpsLogger])
 *
 * The folder is app-private external storage, so no storage permission is needed; copy it to a PC
 * with adb or a file manager (Android/data/<app>/files/Movies/).
 */
class StreamRecorder(private val context: Context) {

    data class Stats(
        val frames: Long = 0,
        val bytes: Long = 0,
        val elapsedMs: Long = 0,
        val gpsFixes: Long = 0,
        val gpsStatus: String = ""
    )

    private val lock = Any()
    private val gps = GpsLogger(context)
    private var video: BufferedOutputStream? = null
    private var frames: BufferedWriter? = null
    private var frameCount = 0L
    private var byteCount = 0L
    private var startedAt = 0L

    /** The folder of the current or most recent session. */
    @Volatile var directory: File? = null
        private set

    /** Set when a write failed (for example storage full) and the recording stopped by itself. */
    @Volatile var error: String? = null
        private set

    val isRecording: Boolean get() = synchronized(lock) { video != null }

    /** Starts a new session; returns its folder, or null if it could not be created. */
    fun start(): File? = synchronized(lock) {
        if (video != null) return@synchronized directory
        error = null
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return@synchronized null
        val dir = File(base, RecordFormat.sessionName(System.currentTimeMillis()))
        if (!dir.mkdirs() && !dir.isDirectory) return@synchronized null
        try {
            video = BufferedOutputStream(FileOutputStream(File(dir, "video.h264")), 64 * 1024)
            frames = File(dir, "frames.csv").bufferedWriter().also { it.appendLine(RecordFormat.FRAMES_HEADER) }
        } catch (e: IOException) {
            Log.w(TAG, "could not create recording files", e)
            closeQuietly()
            return@synchronized null
        }
        frameCount = 0
        byteCount = 0
        startedAt = SystemClock.elapsedRealtime()
        directory = dir
        gps.start(File(dir, "gps.csv"))
        Log.i(TAG, "recording started in $dir")
        dir
    }

    /** Called for every complete video frame, on the network thread. */
    fun writeAccessUnit(annexB: ByteArray, rtpTimestamp: Long) {
        synchronized(lock) {
            val out = video ?: return
            try {
                out.write(annexB)
                frames?.appendLine(RecordFormat.frameLine(frameCount, System.currentTimeMillis(), rtpTimestamp, annexB.size))
                frameCount++
                byteCount += annexB.size
            } catch (e: IOException) {
                Log.w(TAG, "write failed, stopping recording", e)
                error = e.message ?: "write failed"
                finish()
            }
        }
    }

    /** Stops the session and returns its folder. Safe to call when nothing is recording. */
    fun stop(): File? = synchronized(lock) {
        if (video == null) return@synchronized directory
        finish()
        Log.i(TAG, "recording stopped: $frameCount frames, $byteCount bytes")
        directory
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            frames = frameCount,
            bytes = byteCount,
            elapsedMs = if (video != null) SystemClock.elapsedRealtime() - startedAt else 0,
            gpsFixes = gps.fixCount,
            gpsStatus = gps.status
        )
    }

    private fun finish() {
        gps.stop()
        closeQuietly()
    }

    private fun closeQuietly() {
        try { video?.close() } catch (e: IOException) { Log.w(TAG, "closing video failed", e) }
        try { frames?.close() } catch (e: IOException) { Log.w(TAG, "closing frames.csv failed", e) }
        video = null
        frames = null
    }

    private companion object {
        const val TAG = "StreamRecorder"
    }
}
