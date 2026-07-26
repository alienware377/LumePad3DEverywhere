package com.lume3d.everywhere.depth

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import com.qualcomm.qti.QnnDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * MiDaS V2 monocular depth — Qualcomm AI-Hub w8a8 (int8) tflite. This CNN runs fully on
 * the Snapdragon 888 Hexagon NPU (v68 HTP) via the QNN delegate — unlike the ViT-based
 * Depth Anything, whose transformer ops the v68 HTP rejects (validateOpConfig 0xc26).
 * Input : 256x256 RGBA bytes (from a GL downscale pass). The model wants uint8 NHWC whose
 *         dequantized value is the [0,1] image (normalization baked into the graph), so we
 *         quantize pixel/255 with (IN_SCALE, IN_ZP).
 * Output: 256x256 uint8 inverse-depth (dequant = OUT_SCALE*q; larger = nearer). normalize()
 *         rescales per-frame to [0,1] with 1.0 = nearest, as a FloatBuffer for a GL_R32F texture.
 *
 * Must be constructed and used from a single dedicated thread.
 */
class DepthEstimator(context: Context) {
    companion object {
        private const val TAG = "DepthEstimator"
        const val SIZE = 256
        // Quantization params from the model's metadata.json (midas-tflite-w8a8).
        private const val IN_SCALE = 0.00487531116232276f
        private const val IN_ZP = 24
        private const val OUT_SCALE = 6.5142998695373535f
        // Precompute pixel(0..255) -> input quant code: q = round((p/255)/IN_SCALE) + IN_ZP.
        private const val IN_MUL = 1f / (255f * IN_SCALE)   // ~0.8044
    }

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var qnnDelegate: QnnDelegate? = null
    private var loggedErr = false
    private var dbgCtx: Context? = null
    private var inferCount = 0
    private val debugDumps = false   // PNG dumps cause periodic file-IO hitches; off in normal use

    // uint8 input (256*256*3) and uint8 output (256*256).
    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val outBuf: ByteBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE).order(ByteOrder.nativeOrder())
    // Dequantized raw depth scratch (one float per pixel).
    private val rawDepth = FloatArray(SIZE * SIZE)
    private val depthOut: FloatBuffer =
        ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    // Temporal smoothing state: exponential moving average across frames so flat UI doesn't
    // shimmer with randomly-changing depth.
    private val smoothed = FloatArray(SIZE * SIZE)
    private var smoothedInit = false
    // Slow-adapting global range (kills per-frame rescale jitter).
    private var rngMn = 0f
    private var rngMx = 1f
    private var rngInit = false
    // Scratch buffers for the separable spatial blur (removes per-glyph depth shear).
    private val blurTmp = FloatArray(SIZE * SIZE)
    private val blurOut = FloatArray(SIZE * SIZE)

    init {
        dbgCtx = context.applicationContext
        val model = loadModel(context, "midas_w8a8.tflite")

        // Delegate notes (all hardware paths fail this model on this device):
        //  - GPU delegate: fast (~390ms) but emits a CONSTANT output (every pixel 0.0887,
        //    std=0) -> flat/blank depth, AND contends with the render GPU so the whole pipe
        //    goes choppy. Unusable.
        //  - NNAPI delegate: builds, but interpreter.run() native-SIGSEGVs on this device's
        //    vendor driver for this float model (uncatchable). Unusable.
        // CPU/XNNPACK is slow (~4.5s/frame at 518x518) but is the only path that yields correct,
        // structured depth and keeps the GPU free for rendering.
        gpuDelegate = null
        nnApiDelegate = null
        // Run the int8 MiDaS CNN on the Hexagon NPU (v68 HTP). The HTP natively supports
        // quantized CNN ops, so the whole graph offloads (real-time). CPU/XNNPACK is the
        // fallback if the QNN delegate can't init.
        interpreter = try {
            val opts = Interpreter.Options()
            val skelDir = context.applicationInfo.nativeLibraryDir
            // The DSP's fastrpc resolves the HTP skel via ADSP_LIBRARY_PATH; set it
            // explicitly (setSkelLibraryDir alone didn't reach cdsprpcd on this device).
            try {
                val cur = android.system.Os.getenv("ADSP_LIBRARY_PATH")
                val v = if (cur.isNullOrEmpty()) skelDir else "$skelDir;$cur"
                android.system.Os.setenv("ADSP_LIBRARY_PATH", v, true)
            } catch (e: Throwable) { Log.w(TAG, "setenv ADSP_LIBRARY_PATH: ${e.message}") }
            val qo = QnnDelegate.Options()
            qo.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            qo.setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_QUANTIZED)
            qo.setHtpPerformanceMode(
                QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
            qo.setSkelLibraryDir(skelDir)
            qo.setCacheDir(context.cacheDir.absolutePath)
            qo.setModelToken("midas_w8a8_htp")
            qnnDelegate = QnnDelegate(qo)
            opts.addDelegate(qnnDelegate)
            Interpreter(model, opts).also { Log.i(TAG, "Interpreter on QNN HTP (NPU)") }
        } catch (t: Throwable) {
            Log.w(TAG, "QNN HTP failed (${t.message}); falling back to XNNPACK CPU")
            try { qnnDelegate?.close() } catch (_: Throwable) {}
            qnnDelegate = null
            val opts = Interpreter.Options()
            opts.setNumThreads(4); opts.setUseXNNPACK(true)
            Interpreter(model, opts)
        }
        val it = interpreter.getInputTensor(0)
        val ot = interpreter.getOutputTensor(0)
        Log.i(TAG, "DepthAnythingV2 ready (nnapi=${nnApiDelegate != null}) inShape=${it.shape().joinToString()} inType=${it.dataType()} inBytes=${it.numBytes()} | outShape=${ot.shape().joinToString()} outType=${ot.dataType()} outBytes=${ot.numBytes()}")
    }

    private fun loadModel(ctx: Context, name: String): ByteBuffer {
        ctx.assets.openFd(name).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /** rgba: 256*256*4 bytes. Returns a [0,1] depth FloatBuffer (position 0), or null on failure. */
    fun infer(rgba: ByteBuffer): FloatBuffer? {
        if (debugDumps && inferCount % 5 == 0) dumpInputPng(rgba)
        inputBuf.clear()
        rgba.rewind()
        var i = 0
        val total = SIZE * SIZE
        // Quantize pixel/255 -> uint8 code: q = round(p * IN_MUL) + IN_ZP, clamped to [0,255].
        while (i < total) {
            val r = rgba.get(i * 4).toInt() and 0xFF
            val g = rgba.get(i * 4 + 1).toInt() and 0xFF
            val b = rgba.get(i * 4 + 2).toInt() and 0xFF
            inputBuf.put((Math.round(r * IN_MUL) + IN_ZP).coerceIn(0, 255).toByte())
            inputBuf.put((Math.round(g * IN_MUL) + IN_ZP).coerceIn(0, 255).toByte())
            inputBuf.put((Math.round(b * IN_MUL) + IN_ZP).coerceIn(0, 255).toByte())
            i++
        }
        inputBuf.rewind()
        return try {
            outBuf.rewind()
            val t0 = android.os.SystemClock.uptimeMillis()
            interpreter.run(inputBuf, outBuf)
            val dt = android.os.SystemClock.uptimeMillis() - t0
            if (inferCount % 5 == 0) Log.i(TAG, "infer() took ${dt}ms")
            normalize()
        } catch (t: Throwable) {
            if (!loggedErr) { loggedErr = true; Log.e(TAG, "inference failed (inLimit=${inputBuf.limit()} outLimit=${outBuf.limit()})", t) }
            null
        }
    }

    private fun normalize(): FloatBuffer {
        val total = SIZE * SIZE
        outBuf.rewind()                 // run() left position at end; view from 0
        // Dequantize uint8 output -> rawDepth (OUT_SCALE*q). Per-frame stats below.
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var sum = 0.0
        for (i in 0 until total) {
            val v = (outBuf.get(i).toInt() and 0xFF) * OUT_SCALE
            rawDepth[i] = v; if (v < mn) mn = v; if (v > mx) mx = v; sum += v
        }
        val mean = sum / total
        var varr = 0.0
        for (i in 0 until total) { val dd = rawDepth[i] - mean; varr += dd * dd }
        val std = Math.sqrt(varr / total)
        if (inferCount % 5 == 0) {
            Log.i(TAG, "RAW depth out: min=%.4f max=%.4f mean=%.4f std=%.4f range=%.4f".format(mn, mx, mean, std, mx - mn))
            if (debugDumps) dumpDepthPng(rawDepth, mn, if (mx - mn < 1e-6f) 1f else (mx - mn))
        }
        inferCount++
        // Per-frame full-contrast normalization: map THIS frame's [mn,mx] -> [0,1].
        // (The old slow-adapting global range got seeded by the cold-start black frame and
        // then compressed real frames into a tiny band -> flat/"blank" depth. Depth Anything's
        // output is clean enough that per-frame scaling doesn't shimmer once we EMA + blur it.)
        val range = if (mx - mn < 1e-6f) 1f else (mx - mn)
        val rngMnL = mn
        depthOut.clear()
        val a = 0.35f                    // EMA weight for the new frame (lower = steadier)
        if (!smoothedInit) {
            for (i in 0 until total) smoothed[i] = ((rawDepth[i] - rngMnL) / range).coerceIn(0f, 1f)
            smoothedInit = true
        } else {
            for (i in 0 until total) {
                val v = ((rawDepth[i] - rngMnL) / range).coerceIn(0f, 1f)
                smoothed[i] += a * (v - smoothed[i])
            }
        }
        // Light spatial blur: removes per-pixel depth noise (the source of text-stroke shear)
        // while preserving element-scale structure so cards/buttons keep distinct depth -> pop.
        blurInto(smoothed, blurOut, radius = 4)
        for (i in 0 until total) depthOut.put(blurOut[i])
        depthOut.rewind()
        if (inferCount % 5 == 1) {
            var omn = Float.MAX_VALUE; var omx = -Float.MAX_VALUE; var osum = 0.0
            for (i in 0 until total) { val v = blurOut[i]; if (v<omn) omn=v; if (v>omx) omx=v; osum+=v }
            val omean = osum / total; var ov = 0.0
            for (i in 0 until total) { val dd = blurOut[i]-omean; ov += dd*dd }
            Log.i(TAG, "OUT depth(GL): min=%.4f max=%.4f mean=%.4f std=%.4f".format(omn, omx, omean, Math.sqrt(ov/total)))
        }
        return depthOut
    }

    /** Separable box blur over the SIZE x SIZE depth field. src and dst may be the same array. */
    private fun blurInto(src: FloatArray, dst: FloatArray, radius: Int) {
        val n = SIZE
        val inv = 1f / (2 * radius + 1)
        // horizontal pass -> blurTmp
        for (y in 0 until n) {
            val row = y * n
            var acc = 0f
            for (k in -radius..radius) acc += src[row + k.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                blurTmp[row + x] = acc * inv
                val addI = (x + radius + 1).coerceIn(0, n - 1)
                val subI = (x - radius).coerceIn(0, n - 1)
                acc += src[row + addI] - src[row + subI]
            }
        }
        // vertical pass -> dst
        for (x in 0 until n) {
            var acc = 0f
            for (k in -radius..radius) acc += blurTmp[k.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                dst[y * n + x] = acc * inv
                val addI = (y + radius + 1).coerceIn(0, n - 1)
                val subI = (y - radius).coerceIn(0, n - 1)
                acc += blurTmp[addI * n + x] - blurTmp[subI * n + x]
            }
        }
    }

    private fun dumpInputPng(rgba: ByteBuffer) {
        val ctx = dbgCtx ?: return
        try {
            val bmp = android.graphics.Bitmap.createBitmap(SIZE, SIZE, android.graphics.Bitmap.Config.ARGB_8888)
            val px = IntArray(SIZE * SIZE)
            for (i in 0 until SIZE * SIZE) {
                val r = rgba.get(i * 4).toInt() and 0xFF
                val g = rgba.get(i * 4 + 1).toInt() and 0xFF
                val b = rgba.get(i * 4 + 2).toInt() and 0xFF
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            val f = java.io.File(ctx.getExternalFilesDir(null), "depth_input.png")
            java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "depth_input.png written")
        } catch (t: Throwable) { Log.e(TAG, "dumpInputPng failed: ${t.message}") }
    }

    private fun dumpDepthPng(fb: FloatArray, mn: Float, range: Float) {
        val ctx = dbgCtx ?: return
        try {
            val bmp = android.graphics.Bitmap.createBitmap(SIZE, SIZE, android.graphics.Bitmap.Config.ARGB_8888)
            val px = IntArray(SIZE * SIZE)
            for (i in 0 until SIZE * SIZE) {
                val g = (((fb[i] - mn) / range) * 255f).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            val f = java.io.File(ctx.getExternalFilesDir(null), "depth_dump.png")
            java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "depth_dump.png written")
        } catch (t: Throwable) { Log.e(TAG, "dumpDepthPng failed: ${t.message}") }
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
        try { nnApiDelegate?.close() } catch (_: Throwable) {}
        try { qnnDelegate?.close() } catch (_: Throwable) {}
    }
}
