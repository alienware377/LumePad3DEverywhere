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
    /** Atlas tile count: one per panel hardware view (see Prefs.leiaNumViews). */
    private val numViews: Int = 2,
) {
    private val appContext = context.applicationContext
    private val sbsW = captureW * numViews
    private val sbsH = captureH

    // ---- tunables (set from UI) ----
    @Volatile var baseline = 0.04f      // max parallax in UV units
    @Volatile var convergence = 0.5f    // normalized depth on the screen plane
    @Volatile var flipY = 1.0f          // 1 = flip output vertically (verify on device)
    @Volatile var swapLr = 0.0f         // 1 = swap eyes (fix pseudoscopic depth)
    @Volatile var ghost = 0.0f          // crosstalk pre-compensation coefficient (0..~0.5)
    /** While the 3D is hidden: drop frames, no depth inference, no drawing (saves the NPU/GPU). */
    /** Show the app flat (no depth, no ghost passes). Depth inference is skipped too. */
    @Volatile var flat2D = false
    @Volatile var paused = false
    private var glReady = false
    // Post-filter 2nd pass (pure subtraction, no pedestal), split into two brightness bands
    // with a soft crossover at ghost2Cross (0..1 luminance).
    @Volatile var ghost2Lo = 0.0f       // strength below the crossover
    @Volatile var ghost2Hi = 0.0f       // strength above the crossover
    @Volatile var ghost2Cross = 0.5f    // crossover luminance
    @Volatile var ghostDesat = 0.0f     // 0..1: desaturate the subtraction term (kills complementary color fringes)
    @Volatile var softKnee = 0.0f       // 0..1: soft-knee compression of extreme disparity tails (0 = off)
    @Volatile var softKneeThresh = 0.6f // 0..1: fraction of max disparity where the knee starts
    @Volatile var ghostMask = 0.0f      // 0..1: edge-aware ghost masking (soften high-disparity high-contrast edges)
    @Volatile var depthSharp = 0.0f     // 0..1: color-guided bilateral depth-edge sharpening (0 = off)
    // 6 band-limited filters x (amount -1..1, band lo 0..1, band hi 0..1):
    // brightness, contrast, clarity, texture, saturation, vibrance. All 0-amount = passthrough.
    @Volatile var filters = FloatArray(18)

    private lateinit var egl: EglCore
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uFrame = 0
    private var uDepth = 0
    private var uBaseline = 0
    private var uConv = 0
    private var uGhost = 0
    private var uGhost2Lo = 0
    private var uGhost2Hi = 0
    private var uGhost2X = 0
    private var uGhDesat = 0
    private var uSoftKnee = 0
    private var uSoftKneeT = 0
    private var uGhMask = 0
    private var uDepthSharp = 0
    private var uFilt = 0
    private var uBlur = 0
    private var uPx = 0
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
        // Historical note: with the STOCK CNSDK interlace shader this had to be 2: the native
        // GetNumberOfSourceViews() returns a hard-coded 2 while interlaceMode == 2 (the boot
        // default, no JNI setter), pinning the softwareViews uniform, so a 4-tile atlas rendered
        // squished/doubled. We now ship a patched assets/shaders/interlace.pshader that tiles by
        // hardwareViewsX instead, so the count is the panel's real view count (Prefs.leiaNumViews).
        const val LEGACY_NUM_VIEWS = 2
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
        // Attaching to CNSDK's SurfaceTexture can fail transiently (EGL_BAD_ALLOC = still attached
        // elsewhere). Retry briefly, and never let a failure here kill the whole app.
        var core: EglCore? = null
        for (attempt in 1..6) {
            core = try { EglCore(Surface(outputTexture)) } catch (t: Throwable) {
                android.util.Log.w("StereoRenderer", "EGL surface attempt $attempt failed: ${t.message}"); null
            }
            if (core != null) break
            try { Thread.sleep(150) } catch (_: InterruptedException) { return }
        }
        if (core == null) { android.util.Log.e("StereoRenderer", "giving up on EGL surface; renderer idle"); return }
        egl = core
        egl.makeCurrent()

        program = GlUtil.program(VS, fsSource(numViews))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uFrame = GLES20.glGetUniformLocation(program, "uFrame")
        uDepth = GLES20.glGetUniformLocation(program, "uDepth")
        uBaseline = GLES20.glGetUniformLocation(program, "uBaseline")
        uConv = GLES20.glGetUniformLocation(program, "uConv")
        uFlip = GLES20.glGetUniformLocation(program, "uFlipY")
        uSwap = GLES20.glGetUniformLocation(program, "uSwap")
        uGhost = GLES20.glGetUniformLocation(program, "uGhost")
        uGhost2Lo = GLES20.glGetUniformLocation(program, "uGhost2Lo")
        uGhost2Hi = GLES20.glGetUniformLocation(program, "uGhost2Hi")
        uGhost2X = GLES20.glGetUniformLocation(program, "uGhost2X")
        uGhDesat = GLES20.glGetUniformLocation(program, "uGhDesat")
        uSoftKnee = GLES20.glGetUniformLocation(program, "uSoftKnee")
        uSoftKneeT = GLES20.glGetUniformLocation(program, "uSoftKneeT")
        uGhMask = GLES20.glGetUniformLocation(program, "uGhMask")
        uDepthSharp = GLES20.glGetUniformLocation(program, "uDepthSharp")
        uFilt = GLES20.glGetUniformLocation(program, "uFilt")
        uBlur = GLES20.glGetUniformLocation(program, "uBlur")
        uPx = GLES20.glGetUniformLocation(program, "uPx")

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
        glReady = true
    }

    private fun bindTex2d(tex: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun drawFrame() {
        if (!glReady) return
        if (paused) { synchronized(frameLock) { pendingFrame = null }; return }
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

        // Downscale into the FBO every frame: it doubles as the clarity filter's blur
        // source. Depth readback/dispatch off it stays throttled.
        blitToDownscale()
        if (!flat2D && depth != null && !depthBusy && (frameCounter % DEPTH_EVERY == 0)) {
            runReadbackAndDispatch()
        }
        frameCounter++

        // DIBR pass into the CNSDK surface
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, sbsW, sbsH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); bindTex2d(frameTex); GLES20.glUniform1i(uFrame, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex); GLES20.glUniform1i(uDepth, 1)
        downscale?.let {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it.texture)
            GLES20.glUniform1i(uBlur, 2)
        }
        // Flat 2D: both eyes get the same picture, so every depth and ghost pass goes to zero.
        // The colour filters stay, so 2D looks the same as the 3D minus the depth.
        val flat = flat2D
        GLES20.glUniform1f(uBaseline, if (flat) 0f else baseline)
        GLES20.glUniform1f(uConv, convergence)
        GLES20.glUniform1f(uFlip, flipY)
        GLES20.glUniform1f(uSwap, swapLr)
        GLES20.glUniform1f(uGhost, if (flat) 0f else ghost)
        GLES20.glUniform1f(uGhost2Lo, if (flat) 0f else ghost2Lo)
        GLES20.glUniform1f(uGhost2Hi, if (flat) 0f else ghost2Hi)
        GLES20.glUniform1f(uGhost2X, ghost2Cross)
        GLES20.glUniform1f(uGhDesat, ghostDesat)
        GLES20.glUniform1f(uSoftKnee, if (flat) 0f else softKnee)
        GLES20.glUniform1f(uSoftKneeT, softKneeThresh)
        GLES20.glUniform1f(uGhMask, if (flat) 0f else ghostMask)
        GLES20.glUniform1f(uDepthSharp, if (flat) 0f else depthSharp)
        GLES20.glUniform3fv(uFilt, 6, filters, 0)
        GLES20.glUniform2f(uPx, 1f / w, 1f / h)

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
            android.util.Log.i("StereoRenderer", "render fps=%.1f  frame=%dx%d  depthBusy=%b  ghost=%.2f".format(fps, w, h, depthBusy, ghost))
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

    /** Downscale the current frame into the small FBO (blur source + depth-net input). */
    private fun blitToDownscale() {
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
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun runReadbackAndDispatch() {
        val fbo = downscale ?: return
        fbo.bind()
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

// N-view horizontal atlas DIBR. Total disparity spread (outermost pair) is identical to the
// old 2-view SBS — the intermediate views just halve/third the step between ADJACENT panel
// views, which is what lenticular crosstalk ghosting is proportional to. N=2 reproduces the
// old behavior exactly (tiles 0,1 -> coef +1,-1).
private fun fsSource(numViews: Int) = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uFrame;
uniform sampler2D uDepth;
uniform float uBaseline;
uniform float uConv;
uniform float uSwap;
const float N = ${numViews}.0;
uniform float uGhost;
uniform float uGhost2Lo; // post-filter pass strength below the brightness crossover
uniform float uGhost2Hi; // ... and above it (no pedestal in either — blacks stay black)
uniform float uGhost2X;  // crossover luminance, soft +-0.1 blend
uniform float uGhDesat;  // 0..1: how much the subtraction term is desaturated. Per-channel
                         // subtraction of a saturated leak leaves its COMPLEMENT behind
                         // (yellow ghost -> blue fringe); subtracting luminance instead
                         // cancels the brightness edge without inventing opposite hues.
uniform vec3 uFilt[6];   // per filter: x = amount -1..1, y = band lo, z = band hi
uniform sampler2D uBlur; // downscaled frame (clarity's local-average source)
uniform vec2 uPx;        // 1 / frame size (texel step for the texture filter)
uniform float uSoftKnee;   // soft-knee disparity tail compression (0 = off)
uniform float uSoftKneeT;  // knee start, fraction of max disparity
uniform float uGhMask;     // edge-aware ghost masking strength (0 = off)
uniform float uDepthSharp; // color-guided bilateral depth-edge sharpening (0 = off)
const vec3 LW = vec3(0.299, 0.587, 0.114);

// Parametric-EQ-style band weight over source luminance: full inside [lo, hi],
// soft 0.15-wide shoulders outside. lo=0 / hi=1 keep the edges fully open.
float bandw(vec3 f, float L) {
    return smoothstep(f.y - 0.15, f.y, L) * (1.0 - smoothstep(f.z, f.z + 0.15, L));
}

void main() {
    float tile = floor(min(vUv.x * N, N - 0.5));   // 0..N-1, this fragment's view
    float u = vUv.x * N - tile;                    // 0..1 within this view's tile
    float t = tile / (N - 1.0);                    // 0 = leftmost … 1 = rightmost
    float coef = mix(1.0, -1.0, mix(t, 1.0 - t, uSwap)); // leftmost +1 … rightmost -1
    vec2 duv = vec2(u, vUv.y);
    float d = texture(uDepth, duv).r;              // 1 = near, 0 = far
    if (uDepthSharp > 0.001) {
        // Depth unsharp mask: d + k*(d - blur(d)). The depth map is pre-blurred (256px, box
        // r=4), which smears silhouettes into gradual ramps -> halo. Adding back the
        // high-frequency difference steepens those ramps so near objects "cut out" from the
        // background more sharply -> crisper pop at edges. Colour-gated so we only steepen
        // where there's a real object edge, not inside flat regions (avoids ringing on noise).
        float r = 3.0 / 256.0;                      // blur radius in depth texels
        float dblur = d; vec3 gc = texture(uFrame, duv).rgb; float cedge = 0.0;
        for (int k = 0; k < 8; k++) {
            float ang = float(k) * 0.7853981;
            vec2 nuv = duv + r * vec2(cos(ang), sin(ang));
            dblur += texture(uDepth, nuv).r;
            cedge += length(texture(uFrame, nuv).rgb - gc);
        }
        dblur /= 9.0;
        float gate = clamp(cedge * 1.5, 0.0, 1.0);  // 0 in flat areas, 1 across colour edges
        d = clamp(d + uDepthSharp * 4.0 * (d - dblur) * gate, 0.0, 1.0);
    }
    float disp = uBaseline * (d - uConv);
    if (uSoftKnee > 0.001) {
        // Soft-knee: leave depth up to the knee untouched, gently compress only the tails
        // (the big-disparity outliers that ghost worst). Mid-range 3D volume preserved.
        float knee = uSoftKneeT * uBaseline;
        float ad = abs(disp);
        if (ad > knee) {
            float over = ad - knee;
            float comp = over / (1.0 + uSoftKnee * 8.0 * over / max(uBaseline, 1e-4));
            disp = sign(disp) * (knee + comp);
        }
    }
    vec2 sv = vec2(u + coef * disp, vUv.y);
    vec3 own = texture(uFrame, sv).rgb;

    // The crosstalk that ghosts is leakage from the ADJACENT panel view(s). At N=2 the only
    // neighbour is the opposite eye (one step of 2.0 in coef space), identical to the old
    // behaviour. At N>2 the neighbours are one 2/(N-1) step away on each side, so average the
    // valid ones; edge views have a neighbour on one side only.
    float stepC = 2.0 / max(N - 1.0, 1.0);
    float cA = coef + stepC;
    float cB = coef - stepC;
    float wA = (cA <= 1.0001) ? 1.0 : 0.0;
    float wB = (cB >= -1.0001) ? 1.0 : 0.0;
    vec3 nbr = (wA * texture(uFrame, vec2(u + cA * disp, vUv.y)).rgb
              + wB * texture(uFrame, vec2(u + cB * disp, vUv.y)).rgb) / max(wA + wB, 1.0);
    if (uGhMask > 0.001) {
        // Edge-aware ghost masking: only where BOTH local contrast and disparity are high
        // (exactly where crosstalk shows), blend toward a wider local average to soften the
        // edge the ghost copies. Flat areas and the screen plane are untouched. The blur
        // radius scales up with strength so the effect is actually visible.
        vec2 e = (2.0 + uGhMask * 4.0) * uPx;   // 2..6 capture px
        vec3 nA = 0.125 * (
              texture(uFrame, sv + vec2( e.x, 0.0)).rgb + texture(uFrame, sv + vec2(-e.x, 0.0)).rgb
            + texture(uFrame, sv + vec2(0.0,  e.y)).rgb + texture(uFrame, sv + vec2(0.0, -e.y)).rgb
            + texture(uFrame, sv + vec2( e.x,  e.y)).rgb + texture(uFrame, sv + vec2(-e.x, -e.y)).rgb
            + texture(uFrame, sv + vec2( e.x, -e.y)).rgb + texture(uFrame, sv + vec2(-e.x,  e.y)).rgb);
        float contrast = length(own - nA);
        float m = clamp(uGhMask * (abs(disp) / max(uBaseline, 1e-4)) * contrast * 12.0, 0.0, uGhMask);
        own = mix(own, nA, m);
    }
    if (uGhost > 0.001) {
        // Crosstalk pre-compensation with a black pedestal. The panel optically adds a
        // fraction c of the OTHER view onto this one; subtracting it here cancels the
        // leak at the eye — but a bright ghost over black has nothing to subtract from.
        // Lifting blacks by p >= c (linear light) gives every pixel enough headroom to
        // cancel fully. Cost: blacks turn slightly grey as the slider rises.
        vec3 ol = pow(own, vec3(2.2));
        vec3 tl = pow(nbr, vec3(2.2));
        tl = mix(tl, vec3(dot(tl, LW)), uGhDesat);
        float p = min(uGhost, 0.25);               // pedestal: just enough headroom
        ol = p + ol * (1.0 - p);
        tl = p + tl * (1.0 - p);
        vec3 lin = (ol - uGhost * tl) / (1.0 - uGhost);
        own = pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));
    }

    // ── band-limited visual filters (all no-ops at amount 0) ──
    float L0 = dot(own, LW);                       // masks keyed off pre-filter luminance
    float a = uFilt[0].x;                          // 0 brightness (exposure-style)
    if (abs(a) > 0.003) own = clamp(own * pow(2.0, a * 1.5 * bandw(uFilt[0], L0)), 0.0, 1.0);
    a = uFilt[1].x;                                // 1 contrast (S-curve about mid-grey)
    if (abs(a) > 0.003) own = clamp(mix(own, (own - 0.5) * (1.0 + a * 1.5) + 0.5, bandw(uFilt[1], L0)), 0.0, 1.0);
    a = uFilt[2].x;                                // 2 clarity (local contrast vs blur)
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        float Lb = dot(texture(uBlur, sv).rgb, LW);
        own = clamp(own * (1.0 + a * 2.5 * (L - Lb) * bandw(uFilt[2], L0) / max(L, 0.05)), 0.0, 1.0);
    }
    a = uFilt[3].x;                                // 3 texture (1px unsharp)
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        float ln = dot(texture(uFrame, sv + vec2(uPx.x, 0.0)).rgb, LW)
                 + dot(texture(uFrame, sv - vec2(uPx.x, 0.0)).rgb, LW)
                 + dot(texture(uFrame, sv + vec2(0.0, uPx.y)).rgb, LW)
                 + dot(texture(uFrame, sv - vec2(0.0, uPx.y)).rgb, LW);
        float lap = dot(texture(uFrame, sv).rgb, LW) - ln * 0.25;
        own = clamp(own * (1.0 + a * 3.0 * lap * bandw(uFilt[3], L0) / max(L, 0.05)), 0.0, 1.0);
    }
    a = uFilt[4].x;                                // 4 saturation
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        own = clamp(mix(vec3(L), own, 1.0 + a * bandw(uFilt[4], L0)), 0.0, 1.0);
    }
    a = uFilt[5].x;                                // 5 vibrance (boosts muted colors most)
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        float cs = max(own.r, max(own.g, own.b)) - min(own.r, min(own.g, own.b));
        own = clamp(mix(vec3(L), own, 1.0 + a * (1.0 - cs) * bandw(uFilt[5], L0)), 0.0, 1.0);
    }

    // ── post-filter ghost fix, second pass: pure subtraction, NO pedestal. Blacks are
    // never lifted; the clamp just leaves dark ghosts uncancelled instead. Runs after
    // the filters so it also cancels leak of filter-boosted content. ──
    if (uGhost2Lo > 0.001 || uGhost2Hi > 0.001) {
        float Lown = dot(own, LW);
        float c2 = mix(uGhost2Lo, uGhost2Hi, smoothstep(uGhost2X - 0.1, uGhost2X + 0.1, Lown));
        if (c2 > 0.001) {
            vec3 t2 = pow(nbr, vec3(2.2));
            t2 = mix(t2, vec3(dot(t2, LW)), uGhDesat);
            vec3 lin2 = (pow(own, vec3(2.2)) - c2 * t2) / (1.0 - c2);
            own = pow(clamp(lin2, 0.0, 1.0), vec3(1.0 / 2.2));
        }
    }
    frag = vec4(own, 1.0);
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
