package com.lume3d.everywhere.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.view.Surface
import com.lume3d.everywhere.depth.DepthEstimator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Owns a GL context that renders into the CNSDK input SurfaceTexture.
 *
 * Per captured frame:
 *   1. upload color to a TEXTURE_2D,
 *   2. (throttled) downscale to 256x256 and run MiDaS on a worker thread,
 *   3. DIBR: synthesize left/right eye views by depth-based horizontal parallax,
 *      packed side-by-side, and swap into the CNSDK surface.
 */
class StereoRenderer(
    context: Context,
    private val outputTexture: SurfaceTexture,
    private val captureW: Int,
    private val captureH: Int,
) {
    private val appContext = context.applicationContext
    private val sbsW = captureW * 2
    private val sbsH = captureH

    // ---- tunables (set from UI) ----
    @Volatile var baseline = 0.04f      // max parallax in UV units
    @Volatile var convergence = 0.5f    // normalized depth on the screen plane
    @Volatile var flipY = 1.0f          // 1 = flip output vertically (verify on device)
    @Volatile var swapLr = 0.0f         // 1 = swap eyes (fix pseudoscopic depth)

    private lateinit var egl: EglCore
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uFrame = 0
    private var uDepth = 0
    private var uBaseline = 0
    private var uConv = 0
    private var uFlip = 0
    private var uSwap = 0
    private var vbo = 0

    // simple full-frame blit used for the depth downscale pass
    private var blitProgram = 0
    private var blitAPos = 0
    private var blitAUv = 0
    private var blitUFrame = 0

    private var frameTex = 0
    private var depthTex = 0
    private var frameTexW = 0
    private var frameTexH = 0
    private var downscale: GlUtil.Fbo? = null
    private val depthReadBuf: ByteBuffer =
        ByteBuffer.allocateDirect(DepthEstimator.SIZE * DepthEstimator.SIZE * 4).order(ByteOrder.nativeOrder())

    // Latest captured frame (tightly packed RGBA), guarded by [frameLock].
    private val frameLock = Any()
    private var pendingFrame: ByteBuffer? = null
    private var pendingW = 0
    private var pendingH = 0

    private var frameCounter = 0
    @Volatile private var depthBusy = false

    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler
    private lateinit var depthThread: HandlerThread
    private lateinit var depthHandler: Handler
    private var depth: DepthEstimator? = null

    companion object {
        private const val MSG_INIT = 1
        private const val MSG_FRAME = 2
        private const val MSG_DEPTH_RESULT = 3
        private const val MSG_RELEASE = 4
        private const val MSG_RUN_DEPTH = 10
        private const val DEPTH_EVERY = 2   // run depth every Nth frame
    }

    fun start() {
        outputTexture.setDefaultBufferSize(sbsW, sbsH)

        depthThread = HandlerThread("lume-depth").apply { start() }
        depthHandler = Handler(depthThread.looper) { msg -> onDepthMsg(msg) }

        renderThread = HandlerThread("lume-render").apply { start() }
        renderHandler = Handler(renderThread.looper) { msg -> onRenderMsg(msg) }
        renderHandler.sendEmptyMessage(MSG_INIT)
    }

    /** Called from the ImageReader thread. Copies pixels (stride-aware) and posts a render. */
    fun pushFrame(src: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int) {
        val packed = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val rowBytes = width * 4
        if (rowStrideBytes == rowBytes) {
            src.rewind(); packed.put(src)
        } else {
            val row = ByteArray(rowBytes)
            for (y in 0 until height) {
                src.position(y * rowStrideBytes)
                src.get(row, 0, rowBytes)
                packed.put(row)
            }
        }
        packed.rewind()
        synchronized(frameLock) {
            pendingFrame = packed; pendingW = width; pendingH = height
        }
        renderHandler.removeMessages(MSG_FRAME)
        renderHandler.sendEmptyMessage(MSG_FRAME)
    }

    fun release() {
        if (::renderHandler.isInitialized) renderHandler.sendEmptyMessage(MSG_RELEASE)
    }

    // ---------------- render thread ----------------

    private fun onRenderMsg(msg: Message): Boolean {
        when (msg.what) {
            MSG_INIT -> initGl()
            MSG_FRAME -> drawFrame()
            MSG_DEPTH_RESULT -> {
                @Suppress("UNCHECKED_CAST")
                uploadDepth(msg.obj as FloatBuffer)
                depthBusy = false
            }
            MSG_RELEASE -> releaseGl()
        }
        return true
    }

    private fun initGl() {
        egl = EglCore(Surface(outputTexture))
        egl.makeCurrent()

        program = GlUtil.program(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uFrame = GLES20.glGetUniformLocation(program, "uFrame")
        uDepth = GLES20.glGetUniformLocation(program, "uDepth")
        uBaseline = GLES20.glGetUniformLocation(program, "uBaseline")
        uConv = GLES20.glGetUniformLocation(program, "uConv")
        uFlip = GLES20.glGetUniformLocation(program, "uFlipY")
        uSwap = GLES20.glGetUniformLocation(program, "uSwap")

        blitProgram = GlUtil.program(BLIT_VS, BLIT_FS)
        blitAPos = GLES20.glGetAttribLocation(blitProgram, "aPos")
        blitAUv = GLES20.glGetAttribLocation(blitProgram, "aUv")
        blitUFrame = GLES20.glGetUniformLocation(blitProgram, "uFrame")

        val vb = IntArray(1); GLES20.glGenBuffers(1, vb, 0); vbo = vb[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val fb = ByteBuffer.allocateDirect(GlUtil.FULLSCREEN_VERTS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(GlUtil.FULLSCREEN_VERTS); fb.rewind()
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, GlUtil.FULLSCREEN_VERTS.size * 4, fb, GLES20.GL_STATIC_DRAW)

        frameTex = GlUtil.genTexture()
        depthTex = GlUtil.genTexture()
        bindTex2d(depthTex)
        // allocate a default flat depth (0.5) so we render even before MiDaS warms up
        val flat = ByteBuffer.allocateDirect(DepthEstimator.SIZE * DepthEstimator.SIZE * 4)
            .order(ByteOrder.nativeOrder())
        val ffb = flat.asFloatBuffer(); for (i in 0 until DepthEstimator.SIZE * DepthEstimator.SIZE) ffb.put(0.5f); ffb.rewind()
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            DepthEstimator.SIZE, DepthEstimator.SIZE, 0, GLES30.GL_RED, GLES20.GL_FLOAT, flat)

        downscale = GlUtil.Fbo(DepthEstimator.SIZE, DepthEstimator.SIZE)

        depth = try {
            DepthEstimator(appContext).also { android.util.Log.i("StereoRenderer", "DepthEstimator ready") }
        } catch (t: Throwable) {
            android.util.Log.e("StereoRenderer", "DepthEstimator init failed: ${t.message}", t); null
        }
        android.util.Log.i("StereoRenderer", "GL init complete, sbs=${sbsW}x$sbsH")
    }

    private fun bindTex2d(tex: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun drawFrame() {
        val buf: ByteBuffer; val w: Int; val h: Int
        synchronized(frameLock) {
            buf = pendingFrame ?: return
            w = pendingW; h = pendingH
            pendingFrame = null
        }

        // upload color
        bindTex2d(frameTex)
        if (w != frameTexW || h != frameTexH) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            frameTexW = w; frameTexH = h
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        }

        // throttled depth: downscale into FBO, read back, dispatch to MiDaS
        if (depth != null && !depthBusy && (frameCounter % DEPTH_EVERY == 0)) {
            runDownscaleAndDispatch()
        }
        frameCounter++

        // DIBR pass into the CNSDK surface
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, sbsW, sbsH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); bindTex2d(frameTex); GLES20.glUniform1i(uFrame, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex); GLES20.glUniform1i(uDepth, 1)
        GLES20.glUniform1f(uBaseline, baseline)
        GLES20.glUniform1f(uConv, convergence)
        GLES20.glUniform1f(uFlip, flipY)
        GLES20.glUniform1f(uSwap, swapLr)

        bindQuad()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        egl.setPresentationTimeNs(System.nanoTime())
        egl.swapBuffers()

        // lightweight fps log every 60 rendered frames
        if (++renderedFrames % 60 == 0) {
            val now = System.nanoTime()
            val dt = (now - fpsMarkNs) / 1e9
            val fps = if (dt > 0) 60.0 / dt else 0.0
            fpsMarkNs = now
            android.util.Log.i("StereoRenderer", "render fps=%.1f  frame=%dx%d  depthBusy=%b".format(fps, w, h, depthBusy))
        }
    }

    private var renderedFrames = 0
    private var fpsMarkNs = System.nanoTime()

    private fun bindQuad() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, 8)
    }

    private fun runDownscaleAndDispatch() {
        val fbo = downscale ?: return
        fbo.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); bindTex2d(frameTex); GLES20.glUniform1i(blitUFrame, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(blitAPos)
        GLES20.glVertexAttribPointer(blitAPos, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(blitAUv)
        GLES20.glVertexAttribPointer(blitAUv, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        depthReadBuf.rewind()
        GLES20.glReadPixels(0, 0, DepthEstimator.SIZE, DepthEstimator.SIZE,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, depthReadBuf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // hand a copy to the depth thread
        val copy = ByteBuffer.allocateDirect(DepthEstimator.SIZE * DepthEstimator.SIZE * 4).order(ByteOrder.nativeOrder())
        depthReadBuf.rewind(); copy.put(depthReadBuf); copy.rewind()
        depthBusy = true
        depthHandler.removeMessages(MSG_RUN_DEPTH)
        depthHandler.sendMessage(depthHandler.obtainMessage(MSG_RUN_DEPTH, copy))
    }

    private fun uploadDepth(depthBuf: FloatBuffer) {
        bindTex2d(depthTex)
        depthBuf.rewind()
        GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
            DepthEstimator.SIZE, DepthEstimator.SIZE, GLES30.GL_RED, GLES20.GL_FLOAT, depthBuf)
    }

    private fun releaseGl() {
        // Stop the depth thread and WAIT for any in-flight infer() to finish before closing the
        // interpreter; closing TFLite while it's mid-run native-SIGSEGVs (crashes on background).
        try { depthThread.quitSafely(); depthThread.join(2000) } catch (_: Throwable) {}
        try { depth?.close() } catch (_: Throwable) {}
        try { egl.release() } catch (_: Throwable) {}
        renderThread.quitSafely()
    }

    // ---------------- depth thread ----------------

    private fun onDepthMsg(msg: Message): Boolean {
        if (msg.what == MSG_RUN_DEPTH) {
            val rgba = msg.obj as ByteBuffer
            val d = depth?.infer(rgba)
            if (d != null) {
                renderHandler.sendMessage(renderHandler.obtainMessage(MSG_DEPTH_RESULT, d))
            } else {
                depthBusy = false
            }
        }
        return true
    }
}

private const val VS = """#version 300 es
in vec2 aPos;
in vec2 aUv;
out vec2 vUv;
uniform float uFlipY;
void main() {
    vUv = aUv;
    float y = mix(aPos.y, -aPos.y, uFlipY);
    gl_Position = vec4(aPos.x, y, 0.0, 1.0);
}
"""

private const val FS = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uFrame;
uniform sampler2D uDepth;
uniform float uBaseline;
uniform float uConv;
uniform float uSwap;
void main() {
    float h = step(0.5, vUv.x);                    // 0 = left half, 1 = right half
    float eyeIsRight = mix(h, 1.0 - h, uSwap);
    float viewSign = mix(1.0, -1.0, eyeIsRight);   // left eye +1, right eye -1
    float u = (vUv.x - 0.5 * h) * 2.0;             // 0..1 within this eye's half
    vec2 src = vec2(u, vUv.y);
    float d = texture(uDepth, src).r;              // 1 = near, 0 = far
    float disp = uBaseline * (d - uConv);
    src.x += viewSign * disp;
    frag = texture(uFrame, src);
}
"""

// Full-frame blit (depth downscale pass): no eye split, no flip, no parallax.
private const val BLIT_VS = """#version 300 es
in vec2 aPos;
in vec2 aUv;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

private const val BLIT_FS = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uFrame;
void main() { frag = texture(uFrame, vUv); }
"""
