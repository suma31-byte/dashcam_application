package com.boat.dashcamcompanion.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI

/**
 * Minimal RTSP client for cheap LIVE555-based IP/dash cams.
 *
 * Written for this project because ExoPlayer's RTSP extension does not follow the
 * server's Content-Base header when the SDP declares session-level aggregate control
 * ("a=control:*"): it keeps sending PLAY to the original request URL instead of the
 * per-session URL the server just handed out in DESCRIBE, so LIVE555 always answers
 * "404 Stream Not Found". This client resolves DESCRIBE/SETUP/PLAY targets the way
 * LIVE555 actually expects, uses RTP-over-RTSP (interleaved TCP) to avoid any UDP/NAT
 * concerns on the camera's own Wi-Fi AP, and depacketizes H264 (single-NAL/STAP-A/FU-A)
 * straight into a MediaCodec decoder pointed at the given Surface.
 */
class RtspH264Client(private val onStatus: (String) -> Unit) {

    @Volatile private var running = false
    private var socket: Socket? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Decoupling network reads from the codec's input-buffer availability: the old
    // synchronous path called dequeueInputBuffer with a short timeout and, if no
    // buffer was free right that instant, silently dropped the frame — no retry.
    // That's the "slight break" — an occasional discarded frame whenever the decoder
    // was momentarily busy. The async callback + queue below guarantees every access
    // unit eventually gets fed once a buffer frees up, instead of being lost.
    private data class AccessUnit(val data: ByteArray, val ptsUs: Long)

    private val pendingAccessUnits = java.util.concurrent.ConcurrentLinkedQueue<AccessUnit>()
    private val freeInputBuffers = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val feedLock = Any()
    private val maxPendingAccessUnits = 6 // cap so a slow decoder can't build unbounded latency

    private fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    fun start(url: String, surface: Surface) {
        stop()
        running = true
        thread = Thread {
            // Real-time-ish scheduling priority so the OS doesn't deprioritize this
            // thread under load — any scheduling delay here shows up directly as
            // extra glass-to-glass latency on a live feed.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                runSession(url, surface)
            } catch (e: Exception) {
                if (running) postStatus("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
            }
        }.also { it.name = "RtspH264Client"; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        pendingAccessUnits.clear()
        freeInputBuffers.clear()
    }

    private fun runSession(urlString: String, surface: Surface) {
        val uri = URI(urlString)
        val host = uri.host
        val port = if (uri.port > 0) uri.port else 554
        postStatus("Connecting to $host:$port…")

        val sock = Socket(host, port)
        sock.soTimeout = 8000
        sock.tcpNoDelay = true // disable Nagle's algorithm — batches/delays small TCP
                                // writes (RTSP commands here) by tens of ms otherwise
        // A bigger receive window gives the kernel more room to absorb Wi-Fi jitter
        // without the single interleaved TCP stream (RTSP + RTP share one connection)
        // stalling — a stall here blocks every later frame behind it, not just one.
        try { sock.receiveBufferSize = 256 * 1024 } catch (_: Exception) {}
        socket = sock
        val input = BufferedInputStream(sock.getInputStream(), 32 * 1024)
        val output = sock.getOutputStream()
        var cseq = 0

        sendRequest(output, "DESCRIBE", urlString, ++cseq, mapOf("Accept" to "application/sdp"))
        val describeResp = readResponse(input)
        if (describeResp.statusCode != 200) {
            postStatus("DESCRIBE failed: RTSP ${describeResp.statusCode}")
            return
        }
        val contentBase = describeResp.headers["content-base"]?.trim()?.trimEnd() ?: urlString
        val sdp = SdpParser.parseSdp(describeResp.body)
        val sprop = sdp.spropParameterSets
        if (sprop == null) {
            postStatus("No H264 video track in SDP")
            return
        }

        val aggregateUrl = SdpParser.resolveControlUrl(contentBase, sdp.aggregateControl)
        val trackUrl = SdpParser.resolveControlUrl(aggregateUrl, sdp.trackControl)

        sendRequest(
            output, "SETUP", trackUrl, ++cseq,
            mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
        )
        val setupResp = readResponse(input)
        if (setupResp.statusCode != 200) {
            postStatus("SETUP failed: RTSP ${setupResp.statusCode}")
            return
        }
        val session = setupResp.headers["session"]?.split(";")?.first()?.trim()
        if (session == null) {
            postStatus("SETUP response had no session id")
            return
        }

        // PLAY targets the aggregate (session) URL, not the original request URL —
        // this is the exact step ExoPlayer's RtspClient gets wrong against LIVE555.
        sendRequest(
            output, "PLAY", aggregateUrl, ++cseq,
            mapOf("Session" to session, "Range" to "npt=0.000-")
        )
        val playResp = readResponse(input)
        if (playResp.statusCode != 200) {
            postStatus("PLAY failed: RTSP ${playResp.statusCode}")
            return
        }

        postStatus("Streaming")

        val paramSets = sprop.split(",").mapNotNull {
            try { Base64.decode(it, Base64.DEFAULT) } catch (e: Exception) { null }
        }
        val mediaCodec = MediaCodec.createDecoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
        // Request the decoder's low-latency path: on Android 11+ (API 30) with a
        // supporting codec, this skips the frame-reordering buffer hardware decoders
        // normally hold to smooth out playback, which is exactly what causes the
        // "slight delay" on a live feed where every frame should hit the screen
        // as soon as it's decoded rather than being queued up first.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // Real-time priority hints the codec to favor immediate output over
        // throughput/battery efficiency — default is already 0 (realtime), set
        // explicitly so a future default change doesn't silently reintroduce lag.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        // Cheap dashcam encoders often skip proper rate control, so I-frames can run
        // well past MediaCodec's default input-buffer guess for this resolution. When
        // that happened, buffer.put(data) below threw, the generic catch swallowed it,
        // and that whole frame silently vanished — a stutter with no visible error.
        // Sizing this generously up front avoids ever hitting that path.
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
        mediaCodec.configure(format, surface, null, 0)
        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                freeInputBuffers.add(index)
                pumpPendingAccessUnits(codec)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // Render at the scheduled time (derived from the camera's own RTP
                // clock below), not "immediately" — immediate rendering is what threw
                // away all timing information and let network jitter reach the screen
                // directly as juddery playback.
                try { codec.releaseOutputBuffer(index, info.presentationTimeUs * 1000) } catch (e: Exception) {
                    Log.w(TAG, "releaseOutputBuffer failed", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "MediaCodec error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        })
        mediaCodec.start()
        codec = mediaCodec

        if (paramSets.isNotEmpty()) {
            // Config-only NALs carry no picture, so their timestamp doesn't matter —
            // feed them at pts 0, well before the first real frame's scheduled time.
            feedAccessUnit(mediaCodec, AccessUnit(buildAnnexB(paramSets), 0L))
        }

        readInterleavedRtpLoop(input, mediaCodec)
    }

    private fun readInterleavedRtpLoop(input: InputStream, mediaCodec: MediaCodec) {
        val nalAccumulator = mutableListOf<ByteArray>()
        var fuBuffer: ByteArrayOutputStream? = null
        var fuNalHeader = 0

        // Maps the camera's own RTP capture clock onto our local playback clock, so
        // frames get displayed at the same relative spacing they were captured with —
        // instead of whatever spacing the network happened to deliver them in.
        val timestampScheduler = RtpTimestampScheduler()
        fun scheduledPtsUs(rawTimestamp: Long): Long = timestampScheduler.scheduledPtsUs(rawTimestamp)

        while (running) {
            val marker = input.read()
            if (marker == -1) break
            if (marker != 0x24) continue // '$' marks an interleaved binary frame

            val channel = input.read()
            val lenHi = input.read()
            val lenLo = input.read()
            if (channel == -1 || lenHi == -1 || lenLo == -1) break
            val length = (lenHi shl 8) or lenLo
            val packet = ByteArray(length)
            var readCount = 0
            while (readCount < length) {
                val r = input.read(packet, readCount, length - readCount)
                if (r == -1) break
                readCount += r
            }
            if (readCount < length || channel != 0) continue // channel 0 = RTP, 1 = RTCP

            if (packet.size < 12) continue
            val vpxcc = packet[0].toInt() and 0xFF
            val csrcCount = vpxcc and 0x0F
            val hasExtension = (vpxcc and 0x10) != 0
            val markerBit = (packet[1].toInt() and 0x80) != 0
            // RTP header bytes 4-7: 32-bit capture timestamp, 90kHz clock for H264.
            val rawTimestamp = (
                ((packet[4].toInt() and 0xFF).toLong() shl 24) or
                ((packet[5].toInt() and 0xFF).toLong() shl 16) or
                ((packet[6].toInt() and 0xFF).toLong() shl 8) or
                (packet[7].toInt() and 0xFF).toLong()
            )
            var offset = 12 + csrcCount * 4
            if (hasExtension && offset + 4 <= packet.size) {
                val extLen = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
                offset += 4 + extLen * 4
            }
            if (offset >= packet.size) continue
            val payload = packet.copyOfRange(offset, packet.size)
            if (payload.isEmpty()) continue

            val nalHeaderByte = payload[0].toInt() and 0xFF
            val nalType = nalHeaderByte and 0x1F

            when {
                nalType in 1..23 -> nalAccumulator.add(payload)
                nalType == 24 -> { // STAP-A: aggregated NAL units
                    var p = 1
                    while (p + 2 <= payload.size) {
                        val size = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
                        p += 2
                        if (p + size > payload.size) break
                        nalAccumulator.add(payload.copyOfRange(p, p + size))
                        p += size
                    }
                }
                nalType == 28 -> { // FU-A: fragmented NAL unit
                    if (payload.size >= 2) {
                        val fuHeader = payload[1].toInt() and 0xFF
                        val start = (fuHeader and 0x80) != 0
                        val end = (fuHeader and 0x40) != 0
                        val originalType = fuHeader and 0x1F
                        if (start) {
                            fuBuffer = ByteArrayOutputStream()
                            fuNalHeader = (nalHeaderByte and 0xE0) or originalType
                        }
                        fuBuffer?.write(payload, 2, payload.size - 2)
                        val completedBuffer = fuBuffer
                        if (end && completedBuffer != null) {
                            val body = completedBuffer.toByteArray()
                            val nal = ByteArray(1 + body.size)
                            nal[0] = fuNalHeader.toByte()
                            System.arraycopy(body, 0, nal, 1, body.size)
                            nalAccumulator.add(nal)
                            fuBuffer = null
                        }
                    }
                }
            }

            if (markerBit && nalAccumulator.isNotEmpty()) {
                feedAccessUnit(mediaCodec, AccessUnit(buildAnnexB(nalAccumulator), scheduledPtsUs(rawTimestamp)))
                nalAccumulator.clear()
            }
        }
    }

    private fun buildAnnexB(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun feedAccessUnit(mediaCodec: MediaCodec, unit: AccessUnit) {
        // Drop the oldest queued frame, not the newest, if the decoder falls behind —
        // keeps us feeding the freshest available frame instead of letting a backlog
        // of stale ones grow into a widening lag.
        while (pendingAccessUnits.size >= maxPendingAccessUnits) {
            pendingAccessUnits.poll()
        }
        pendingAccessUnits.add(unit)
        pumpPendingAccessUnits(mediaCodec)
    }

    private fun pumpPendingAccessUnits(mediaCodec: MediaCodec) {
        synchronized(feedLock) {
            while (true) {
                val bufferIndex = freeInputBuffers.poll() ?: break
                val unit = pendingAccessUnits.poll()
                if (unit == null) {
                    freeInputBuffers.add(bufferIndex) // no data yet — give the buffer back
                    break
                }
                try {
                    val buffer = mediaCodec.getInputBuffer(bufferIndex)
                    buffer?.clear()
                    buffer?.put(unit.data)
                    mediaCodec.queueInputBuffer(bufferIndex, 0, unit.data.size, unit.ptsUs, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "queueInputBuffer failed", e)
                }
            }
        }
    }

    private fun sendRequest(
        output: OutputStream,
        method: String,
        url: String,
        cseq: Int,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("User-Agent: DashcamCompanion\r\n")
        for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private data class RtspResponse(val statusCode: Int, val headers: Map<String, String>, val body: String)

    private fun readResponse(input: InputStream): RtspResponse {
        val statusLine = readLine(input)
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = input.read(bodyBytes, read, contentLength - read)
            if (r == -1) break
            read += r
        }
        return RtspResponse(statusCode, headers, String(bodyBytes, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "RtspH264Client"
    }
}
