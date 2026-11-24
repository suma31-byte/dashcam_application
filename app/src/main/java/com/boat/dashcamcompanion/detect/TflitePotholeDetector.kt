package com.boat.dashcamcompanion.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Real pothole detector: runs the fine-tuned YOLO11n model (.tflite, exported by Ultralytics)
 * on the road strip and returns boxes as fractions of the strip, as [PotholeDetector] expects.
 *
 * Drop-in replacement for DummyDetector:
 *     DummyDetector()  ->  TflitePotholeDetector(context)
 *
 * The model file must be in app/src/main/assets/ (default name: pothole_fp32.tflite).
 * The model is loaded on the first detect() call, on the detection thread.
 */
class TflitePotholeDetector(
    context: Context,
    private val modelAsset: String = "pothole_fp32.tflite",
    @Volatile var confThreshold: Float = 0.25f,
    @Volatile var iouThreshold: Float = 0.45f,
    /** GPU needs every detect() call on the same thread; keep false unless the loop uses one thread. */
    private val useGpu: Boolean = false,
    /** true = log the best score of every frame and save every 20th model input image. */
    private val debug: Boolean = false,
) : PotholeDetector {

    private val appContext = context.applicationContext
    private var engine: Engine? = null
    private var loadFailed = false

    /** Time taken by the last detect() call, in ms (for logging or an on-screen readout). */
    @Volatile var lastInferenceMs = 0L
        private set

    @Synchronized
    override fun detect(roadStrip: Bitmap): List<Detection> {
        if (loadFailed) return emptyList()
        val e = engine ?: try {
            Engine().also { engine = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load model '$modelAsset' from assets", t)
            loadFailed = true
            return emptyList()
        }
        val start = SystemClock.uptimeMillis()
        val result = try {
            e.run(roadStrip)
        } catch (t: Throwable) {
            Log.e(TAG, "Inference failed - returning no detections", t)
            emptyList()
        }
        lastInferenceMs = SystemClock.uptimeMillis() - start
        return result
    }

    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
    }

    /** Everything that depends on the loaded model. */
    private inner class Engine {
        private var gpuDelegate: GpuDelegate? = null
        private val interpreter: Interpreter
        private val inputSize: Int
        private val nchwInput: Boolean         // input [1, 3, H, W] (true) or [1, H, W, 3]
        private val channelsFirst: Boolean     // output [1, 5, 8400] (true) or [1, 8400, 5]
        private val numChannels: Int           // 4 box values + number of classes
        private val numAnchors: Int
        private var coordsNormalized: Boolean? = null

        private val inputBuffer: ByteBuffer
        private val outputBuffer: ByteBuffer
        private val output: FloatArray
        private val pixels: IntArray
        private val letterbox: Bitmap
        private val lbCanvas: Canvas
        private val lbPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()
        private var debugFrameCount = 0

        init {
            val model = loadModel()
            var interp: Interpreter? = null
            if (useGpu) {
                var delegate: GpuDelegate? = null
                try {
                    val compat = CompatibilityList()
                    if (compat.isDelegateSupportedOnThisDevice) {
                        delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                        interp = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                        gpuDelegate = delegate
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU not available, using CPU: ${t.message}")
                    delegate?.close()
                    interp = null
                }
            }
            interpreter = interp ?: Interpreter(model, Interpreter.Options().setNumThreads(4))

            val inTensor = interpreter.getInputTensor(0)
            require(inTensor.dataType() == DataType.FLOAT32) {
                "Model input must be FLOAT32 - use pothole_fp32.tflite or pothole_int8.tflite"
            }
            // LiteRT exports from newer Ultralytics use [1, 3, 640, 640] (channels first);
            // older TFLite exports use [1, 640, 640, 3]. Support both.
            val inShape = inTensor.shape()
            nchwInput = inShape.size == 4 && inShape[1] == 3 && inShape[3] != 3
            inputSize = if (nchwInput) inShape[2] else inShape[1]

            val outShape = interpreter.getOutputTensor(0).shape()
            channelsFirst = outShape[1] < outShape[2]
            numChannels = if (channelsFirst) outShape[1] else outShape[2]
            numAnchors = if (channelsFirst) outShape[2] else outShape[1]

            inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
                .order(ByteOrder.nativeOrder())
            output = FloatArray(numChannels * numAnchors)
            outputBuffer = ByteBuffer.allocateDirect(4 * output.size).order(ByteOrder.nativeOrder())
            pixels = IntArray(inputSize * inputSize)
            letterbox = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            lbCanvas = Canvas(letterbox)

            Log.i(TAG, "Model loaded: $modelAsset input=${inShape.contentToString()} " +
                    "layout=${if (nchwInput) "NCHW" else "NHWC"} " +
                    "output=${outShape.contentToString()} gpu=${gpuDelegate != null}")
        }

        fun run(frame: Bitmap): List<Detection> {
            val fw = frame.width.toFloat()
            val fh = frame.height.toFloat()
            if (fw < 2f || fh < 2f) return emptyList()
            val s = inputSize.toFloat()

            // 1) Letterbox to a square input, grey padding (same as Ultralytics training)
            val scale = min(s / fw, s / fh)
            val nw = fw * scale
            val nh = fh * scale
            val padX = (s - nw) / 2f
            val padY = (s - nh) / 2f
            lbCanvas.drawColor(Color.rgb(114, 114, 114))
            dst.set(padX, padY, padX + nw, padY + nh)
            lbCanvas.drawBitmap(frame, null, dst, lbPaint)

            // DEBUG: every 20th frame, save exactly what the model sees
            if (debug && debugFrameCount++ % 20 == 0) saveDebugImage(letterbox)

            // 2) RGB floats 0..1, in the layout the model expects
            letterbox.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            inputBuffer.rewind()
            val fb = inputBuffer.asFloatBuffer()
            if (nchwInput) {
                val plane = inputSize * inputSize
                for (i in pixels.indices) {
                    val p = pixels[i]
                    fb.put(i, ((p shr 16) and 0xFF) / 255f)              // R plane
                    fb.put(plane + i, ((p shr 8) and 0xFF) / 255f)       // G plane
                    fb.put(2 * plane + i, (p and 0xFF) / 255f)           // B plane
                }
            } else {
                for (i in pixels.indices) {
                    val p = pixels[i]
                    fb.put(3 * i, ((p shr 16) and 0xFF) / 255f)
                    fb.put(3 * i + 1, ((p shr 8) and 0xFF) / 255f)
                    fb.put(3 * i + 2, (p and 0xFF) / 255f)
                }
            }
            inputBuffer.rewind()

            // 3) Inference
            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            outputBuffer.asFloatBuffer().get(output)

            if (coordsNormalized == null) {
                var mx = 0f
                for (i in 0 until numAnchors) mx = max(mx, value(0, i))
                coordsNormalized = mx <= 2f
            }
            val k = if (coordsNormalized == true) s else 1f

            // 4) Decode boxes (in strip pixels)
            val boxes = ArrayList<FloatArray>()   // x1, y1, x2, y2, score
            val conf = confThreshold
            for (i in 0 until numAnchors) {
                var best = 0f
                for (c in 4 until numChannels) best = max(best, value(c, i))
                if (best < conf) continue
                val cx = value(0, i) * k
                val cy = value(1, i) * k
                val w = value(2, i) * k
                val h = value(3, i) * k
                val x1 = ((cx - w / 2f - padX) / scale).coerceIn(0f, fw)
                val y1 = ((cy - h / 2f - padY) / scale).coerceIn(0f, fh)
                val x2 = ((cx + w / 2f - padX) / scale).coerceIn(0f, fw)
                val y2 = ((cy + h / 2f - padY) / scale).coerceIn(0f, fh)
                if (x2 - x1 < 2f || y2 - y1 < 2f) continue
                boxes.add(floatArrayOf(x1, y1, x2, y2, best))
            }

            // DEBUG: best pothole score in this frame
            if (debug) {
                var bestScore = 0f
                for (i in 0 until numAnchors) for (c in 4 until numChannels) bestScore = max(bestScore, value(c, i))
                Log.d(TAG, "strip ${frame.width}x${frame.height}  best score %.3f  boxes ${boxes.size}".format(bestScore))
            }

            // 5) Non-maximum suppression, then convert to fractions of the strip
            return nms(boxes).map { b ->
                Detection(b[0] / fw, b[1] / fh, b[2] / fw, b[3] / fh, b[4])
            }
        }

        private fun saveDebugImage(bmp: Bitmap) {
            try {
                val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "pothole_debug")
                dir.mkdirs()
                val file = File(dir, "model_input_${debugFrameCount}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.d(TAG, "Saved model input image: ${file.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "Could not save debug image: ${t.message}")
            }
        }

        private fun value(c: Int, i: Int): Float =
            if (channelsFirst) output[c * numAnchors + i] else output[i * numChannels + c]

        private fun nms(boxes: List<FloatArray>): List<FloatArray> {
            val keep = ArrayList<FloatArray>()
            val iouMax = iouThreshold
            for (b in boxes.sortedByDescending { it[4] }) {
                if (keep.none { iou(it, b) > iouMax }) keep.add(b)
                if (keep.size >= 50) break
            }
            return keep
        }

        private fun iou(a: FloatArray, b: FloatArray): Float {
            val iw = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
            val ih = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
            val inter = iw * ih
            val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
            return if (union <= 0f) 0f else inter / union
        }

        fun close() {
            interpreter.close()
            gpuDelegate?.close()
        }

        private fun loadModel(): MappedByteBuffer =
            appContext.assets.openFd(modelAsset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { input ->
                    input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }

    private companion object {
        const val TAG = "TflitePotholeDetector"
    }
}