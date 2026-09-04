package app.facecard.data.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

interface EmbeddingModel : Closeable {
    val dim: Int
    val name: String

    /** 112×112 RGB face crop → L2-normalised embedding. Call off the main thread. */
    fun embed(face112: Bitmap): FloatArray
}

/**
 * On-device MobileFaceNet (float, 112×112 input, 192-d output).
 *
 * Model: `assets/mobilefacenet.tflite`, sourced from
 * MCarlomagno/FaceRecognitionAuth (BSD-3-Clause), MobileFaceNet
 * architecture (deepinsight). Validated: float32 [1,112,112,3] in,
 * float32 [1,192] out, unit-norm embeddings → cosine sim = dot product.
 * Preprocessing mirrors the training setup: pixels scaled to [-1, 1].
 *
 * Documented in README (Phase 6) with the similarity threshold (τ=0.55).
 */
class TfliteMobileFaceNet(appContext: Context) : EmbeddingModel {

    override val name: String =
        "MobileFaceNet 192-d float (assets/mobilefacenet.tflite)"

    private val interpreter: Interpreter
    private val inputW: Int
    private val inputH: Int
    override val dim: Int
    private val lock = Any()

    init {
        val options = Interpreter.Options().setNumThreads(4)
        interpreter = Interpreter(loadMapped(appContext, MODEL_ASSET), options)
        val inShape = interpreter.getInputTensor(0).shape()
        require(inShape.size == 4 && inShape[1] == inShape[2] && inShape[3] == 3) {
            "Unexpected embedding model input shape: ${inShape.toList()}"
        }
        inputH = inShape[1]
        inputW = inShape[2]
        dim = interpreter.getOutputTensor(0).shape()[1]
        require(dim > 0) { "Unexpected embedding model output shape" }
    }

    override fun embed(face112: Bitmap): FloatArray {
        val px = IntArray(inputW * inputH)
        val scaled = if (face112.width != inputW || face112.height != inputH) {
            Bitmap.createScaledBitmap(face112, inputW, inputH, true)
        } else {
            face112
        }
        scaled.getPixels(px, 0, inputW, 0, 0, inputW, inputH)
        if (scaled !== face112) scaled.recycle()
        val buf = ByteBuffer.allocateDirect(inputW * inputH * 3 * 4)
            .order(ByteOrder.nativeOrder())
        for (c in px) {
            buf.putFloat((((c shr 16) and 0xFF) / 127.5f) - 1f)
            buf.putFloat((((c shr 8) and 0xFF) / 127.5f) - 1f)
            buf.putFloat((((c and 0xFF) / 127.5f) - 1f))
        }
        buf.rewind()
        val out = Array(1) { FloatArray(dim) }
        // Interpreter is not thread-safe; inference here is strictly
        // sequential but may hop Dispatchers.Default workers — guard it.
        synchronized(lock) {
            interpreter.run(buf, out)
        }
        return l2norm(out[0])
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        const val MODEL_ASSET = "mobilefacenet.tflite"

        private fun loadMapped(context: Context, asset: String): ByteBuffer {
            // Requires `aaptOptions { noCompress "tflite" }` so the asset
            // stays stored (mmap-able) inside the APK.
            val fd = context.assets.openFd(asset)
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                    .order(ByteOrder.nativeOrder())
            }
        }

        private fun l2norm(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            n = sqrt(n)
            if (n > 1e-9f) {
                for (i in v.indices) v[i] /= n
            }
            return v
        }
    }
}

/**
 * Generous square crop around a detected face for the embedding model:
 * box expanded by [expandFrac], squared on the longer side, clamped into
 * the frame, resized to [size]. No tight face-box crops (PRD F-9).
 * Caller must recycle the returned bitmap.
 */
fun squareFaceCrop(
    src: Bitmap,
    f: DetectedFace,
    expandFrac: Float = 0.2f,
    size: Int = 112,
): Bitmap {
    val cx = (f.left + f.right) / 2f
    val cy = (f.top + f.bottom) / 2f
    val side = max(f.width, f.height) * (1f + expandFrac)
    var l = (cx - side / 2).toInt().coerceIn(0, src.width - 1)
    var t = (cy - side / 2).toInt().coerceIn(0, src.height - 1)
    var r = (l + side).toInt().coerceIn(l + 1, src.width)
    var b = (t + side).toInt().coerceIn(t + 1, src.height)
    // Re-clamp top-left if the box hit the bottom-right edge.
    l = (r - side).toInt().coerceAtLeast(0)
    t = (b - side).toInt().coerceAtLeast(0)
    val cropped = Bitmap.createBitmap(src, l, t, r - l, b - t)
    return Bitmap.createScaledBitmap(cropped, size, size, true).also {
        cropped.recycle()
    }
}
