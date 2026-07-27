package com.lume3d.everywhere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import com.lume3d.everywhere.gl.StereoRenderer
import com.lume3d.everywhere.overlay.OverlayController
import com.lume3d.everywhere.privileged.Privileged

/**
 * Foreground service implementing the Shizuku "trusted virtual display" path:
 *   - create a FLAG_TRUSTED virtual display (via Shizuku/shell) that renders into an
 *     ImageReader Surface — a clean, feedback-free, system-bar-free capture,
 *   - launch the chosen app onto that offscreen display,
 *   - run each frame through the depth -> DIBR -> CNSDK interlace pipeline and show it
 *     fullscreen on the real lightfield panel,
 *   - forward panel touches back to the virtual display so the app stays interactive.
 *
 * No MediaProjection and no root. DRM-L1 apps (Netflix etc.) still capture black; ordinary
 * apps (Chrome, Twitch) work.
 */
class Capture3DService : Service() {

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: OverlayController? = null
    private var renderer: StereoRenderer? = null

    private lateinit var readerThread: HandlerThread
    private lateinit var readerHandler: Handler

    private var captureW = 0
    private var captureH = 0
    private var panelW = 0
    private var panelH = 0
    private var captureDpi = 160
    private var displayId = -1
    @Volatile private var dumpFrames = 0
    private val DEBUG_DUMP = false
    private var targetPkg: String? = null
    private var curOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED
    private val rebuildHandler = Handler(android.os.Looper.getMainLooper())

    init { active = this }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }

        startForegroundNotif()

        if (!Privileged.isShizukuReady()) {
            Log.e(TAG, "Shizuku not ready"); stopSelf(); return START_NOT_STICKY
        }
        // User-consented touch-block mod: let touches released by our pass-through reach other
        // overlay apps (Android 12 otherwise flags them untrusted through our opaque overlay).
        if (applicationContext.touchBlockDisabled) Privileged.allowTouchThroughOverlay()
        else Privileged.restoreTouchBlock()
        targetPkg = applicationContext.targetPackage
        if (targetPkg == null) { Log.e(TAG, "no target package"); stopSelf(); return START_NOT_STICKY }

        computeCaptureSize()
        startReaderThread()
        if (!startVirtualDisplay()) { Log.e(TAG, "virtual display failed"); stopEverything(); return START_NOT_STICKY }
        startOverlay()
        // The app lives on our private display; launch it now and keep showing 3D.
        Privileged.launchOnDisplay(targetPkg!!, displayId)
        curOrientation = resources.configuration.orientation
        return START_STICKY
    }

    /**
     * The panel can rotate (its natural orientation is portrait). The whole pipeline — virtual
     * display size, capture surface, side-by-side texture, CNSDK interlaced surface — is built for
     * one orientation, so on rotation we rebuild it for the new panel dimensions. The captured app
     * stays alive: we resize the existing virtual display + swap its surface rather than relaunch.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (displayId < 0) return
        if (newConfig.orientation == curOrientation) return
        curOrientation = newConfig.orientation
        Log.i(TAG, "orientation -> ${curOrientation}; scheduling pipeline rebuild")
        rebuildHandler.removeCallbacksAndMessages(null)
        rebuildHandler.postDelayed({ rebuildForOrientation() }, 300)
    }

    private fun rebuildForOrientation() {
        try {
            Log.i(TAG, "rebuildForOrientation: tearing down overlay/renderer")
            try { overlay?.hide() } catch (_: Throwable) {}
            try { renderer?.release() } catch (_: Throwable) {}
            try { overlay?.release() } catch (_: Throwable) {}
            renderer = null; overlay = null

            val oldReader = imageReader
            computeCaptureSize()
            val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener(imageListener, readerHandler)
            imageReader = reader

            val vd = virtualDisplay
            if (vd != null) {
                vd.resize(captureW, captureH, captureDpi)
                vd.surface = reader.surface
            }
            try { oldReader?.close() } catch (_: Throwable) {}

            startOverlay()
            Log.i(TAG, "rebuilt for orientation -> capture ${captureW}x$captureH dpi=$captureDpi")
        } catch (t: Throwable) {
            Log.e(TAG, "rebuildForOrientation failed: ${t.message}", t)
        }
    }

    private fun computeCaptureSize() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        panelW = w; panelH = h
        val maxSide = 1280f
        val scale = minOf(1f, maxSide / maxOf(w, h))
        captureW = (w * scale).toInt().let { it - it % 2 }   // keep even
        captureH = (h * scale).toInt().let { it - it % 2 }
        // Scale density by the same factor so the app lays out at the panel's dp size
        // (otherwise a half-res display at full density looks like a tiny screen -> giant UI).
        val panelDpi = resources.displayMetrics.densityDpi
        captureDpi = maxOf(120, (panelDpi * scale).toInt())
        Log.i(TAG, "panel ${w}x$h dpi=$panelDpi -> capture ${captureW}x$captureH dpi=$captureDpi")
    }

    private fun startReaderThread() {
        readerThread = HandlerThread("img-reader").apply { start() }
        readerHandler = Handler(readerThread.looper)
    }

    private val imageListener = ImageReader.OnImageAvailableListener { r ->
        val image = r.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val plane = image.planes[0]
            // Debug PNG dump DISABLED: encoding a 1280x800 bitmap on this reader thread blocked
            // frame delivery for ~0.75s every 120 frames (~the 2-3s on-screen stutter).
            if (DEBUG_DUMP && ++dumpFrames % 120 == 0) saveDebugFrame(plane.buffer, image.width, image.height, plane.rowStride)
            renderer?.pushFrame(plane.buffer, image.width, image.height, plane.rowStride)
        } catch (t: Throwable) {
            Log.e(TAG, "frame error: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun startVirtualDisplay(): Boolean {
        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener(imageListener, readerHandler)

        val vd = Privileged.createTrustedVirtualDisplay(
            this, "lume3d-display", captureW, captureH, captureDpi, reader.surface
        ) ?: return false
        virtualDisplay = vd
        displayId = vd.display?.displayId ?: return false
        Log.i(TAG, "virtual display id=$displayId")
        return true
    }

    private fun startOverlay() {
        // Not secure (we capture the virtual display, not the panel) and touchable so we
        // can forward gestures to the app running offscreen.
        overlay = OverlayController(
            this, captureW * StereoRenderer.NUM_VIEWS, captureH,
            secure = false, touchable = true, onTouch = ::forwardTouch,
        ) { st ->
            val rnd = StereoRenderer(this, st, captureW, captureH)
            applyParams(rnd)
            rnd.start()
            renderer = rnd
        }.also { it.init() }
        // Show immediately: resuming the InterlacedSurfaceView is what triggers CNSDK to
        // build its GL context and fire the surface-ready callback above.
        Handler(mainLooper).post {
            overlay?.show()
            // The LP2 lightfield is calibrated for landscape only; in portrait the lenticular
            // can't present horizontal stereo, so fall back to a clean 2D single view there.
            overlay?.setSingleView(resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT)
        }
    }

    // Per-gesture downTime so the injected DOWN→MOVE→UP stream is coherent.
    private var gestureDownTime = 0L
    // Track the most recent tap position (in virtual-display coords) for text injection focus.
    @Volatile private var lastTapX = -1f
    @Volatile private var lastTapY = -1f

    /**
     * Map a panel-space touch onto the (smaller) virtual display and inject it as shell.
     *
     * We do NOT re-inject a copy of the panel's MotionEvent: that event carries the physical
     * digitizer's deviceId/source (bound to display 0), which makes cross-display injection
     * flaky. Instead we synthesize a clean single-pointer touchscreen event with deviceId=0
     * (virtual) — the same shape `input tap`/scrcpy use, which dispatches reliably to any display.
     */
    private fun forwardTouch(ev: MotionEvent, viewW: Int, viewH: Int) {
        if (displayId < 0 || viewW == 0 || viewH == 0) return
        val action = ev.actionMasked
        // Only single-pointer DOWN/MOVE/UP/CANCEL are synthesized (covers taps + scrolls).
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return

        val now = android.os.SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) gestureDownTime = now
        if (gestureDownTime == 0L) gestureDownTime = now

        // Map against the ACTUAL touched view size, not the panel bounds: the overlay window is
        // shorter than the full panel (system insets), so dividing by panelH skewed Y upward,
        // worsening toward the bottom of the screen.
        val x = ev.x * captureW.toFloat() / viewW
        val y = ev.y * captureH.toFloat() / viewH

        // Track taps for text-injection focus point.
        if (action == MotionEvent.ACTION_UP) {
            lastTapX = x
            lastTapY = y
        }

        val props = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        }
        val event = MotionEvent.obtain(
            gestureDownTime, now, action, 1,
            arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            val ok = Privileged.injectMotion(event, displayId)
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)
                Log.i(TAG, "injectMotion action=$action -> display=$displayId at ($x,$y) ok=$ok")
        } catch (t: Throwable) {
            Log.e(TAG, "forwardTouch failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    /** Re-apply slider values to the live renderer (called from the UI). */
    fun refreshParams() { renderer?.let { applyParams(it) } }

    /** Full touch pass-through: let other overlay apps receive all touches. */
    fun setPassThrough(on: Boolean) { overlay?.setPassThrough(on) }
    fun isPassThrough(): Boolean = overlay?.isPassThrough() ?: false
    /** Edge pass-through margin in px. */
    fun setEdgeMargin(px: Int) { overlay?.setEdgeMargin(px) }
    /** Toggle the OS untrusted-touch block mod (applies immediately via Shizuku). */
    fun setTouchBlockDisabled(on: Boolean) {
        applicationContext.touchBlockDisabled = on
        Thread { if (on) Privileged.allowTouchThroughOverlay() else Privileged.restoreTouchBlock() }.start()
    }
    /** Toggle the dim-to-pass-through approach (re-applies overlay opacity live). */
    fun setDimForPassThrough(on: Boolean) {
        applicationContext.dimForPassThrough = on
        overlay?.updateOverlayDim()
    }

    /** Show/hide the 3D overlay surface. The captured app keeps running on the virtual
     *  display either way; Hide just reveals whatever is on display 0 (typically the
     *  launcher / 3D Everywhere UI) so you can interact with other apps. */
    fun setOverlayVisible(visible: Boolean) {
        if (visible) overlay?.show() else overlay?.hide()
    }

    /**
     * Relay text typed into our local overlay input bar to the captured app's focused field
     * on the virtual display. The trusted-display IME path is blocked without root (shell
     * lacks ADD_TRUSTED_DISPLAY), so instead we type locally and inject the finished string
     * via shell `input -d <displayId> text` (+ ENTER to submit). Runs off the UI thread
     * because the shell calls block.
     *
     * [focusX], [focusY]: if >= 0, tap here first to focus the field on the virtual display
     * (in virtual-display coordinates, e.g. 640x400 for a 1280x800 display at 2x aspect).
     */
    fun sendTextToApp(text: String, submit: Boolean, focusX: Float = -1f, focusY: Float = -1f) {
        val d = displayId
        if (d < 0) { Log.w(TAG, "sendTextToApp: no display"); return }
        // Run on a dedicated thread: the shell calls + sleeps must NOT block the frame-reader.
        Thread {
            try {
                Log.i(TAG, "sendTextToApp d=$d text='${text}' submit=$submit focus=($focusX,$focusY)")
                if (focusX >= 0 && focusY >= 0) {
                    val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                    val coords = MotionEvent.PointerCoords().apply { x = focusX; y = focusY; pressure = 1f; size = 1f }
                    val now = android.os.SystemClock.uptimeMillis()
                    val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
                        arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    val okD = try { Privileged.injectMotion(ev, d) } finally { ev.recycle() }
                    Thread.sleep(60)
                    val ev2 = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, 1,
                        arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    val okU = try { Privileged.injectMotion(ev2, d) } finally { ev2.recycle() }
                    Log.i(TAG, "focus tap downOk=$okD upOk=$okU")
                    Thread.sleep(150)
                }
                if (text.isNotEmpty()) {
                    // Raw key/text injection isn't accepted by the captured field (it wants an
                    // InputConnection commit). Instead set our own clipboard and trigger the
                    // field's native paste handler via KEYCODE_PASTE on the virtual display.
                    val set = setClipboard(text)
                    Log.i(TAG, "setClipboard ok=$set")
                    Thread.sleep(80)
                    val okP = Privileged.injectKey(279 /* KEYCODE_PASTE */, d)
                    Log.i(TAG, "injectKey PASTE ok=$okP")
                }
                if (submit) {
                    Thread.sleep(50)
                    val okE = Privileged.injectKey(66 /* KEYCODE_ENTER */, d)
                    Log.i(TAG, "injectKey ENTER ok=$okE")
                }
            } catch (t: Throwable) { Log.e(TAG, "sendTextToApp failed: ${t.message}", t) }
        }.start()
    }

    /** Set the system clipboard (must run from the app; adb `cmd clipboard` isn't supported here). */
    private fun setClipboard(text: String): Boolean = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("lume3d", text))
        true
    } catch (t: Throwable) { Log.e(TAG, "setClipboard failed: ${t.message}"); false }

    /** Convenience: send text using the most recent tap position. */
    fun sendTextToAppAtLastTap(text: String, submit: Boolean) {
        sendTextToApp(text, submit, focusX = lastTapX, focusY = lastTapY)
    }

    /**
     * Re-tap the last-tapped field on the virtual display to (re)focus it. The captured app then
     * requests the real system IME, which renders on display 0 and is bound to the field via a
     * live InputConnection — letting the user type directly on the genuine keyboard. This is the
     * legit, no-root path; no key/text injection is involved.
     */
    fun summonKeyboard() {
        val d = displayId
        if (d < 0) { Log.w(TAG, "summonKeyboard: no display"); return }
        val fx = lastTapX; val fy = lastTapY
        if (fx < 0 || fy < 0) { Log.w(TAG, "summonKeyboard: no field tapped yet"); return }
        Thread {
            try {
                val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { x = fx; y = fy; pressure = 1f; size = 1f }
                val now = android.os.SystemClock.uptimeMillis()
                val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
                    arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                val okD = try { Privileged.injectMotion(ev, d) } finally { ev.recycle() }
                Thread.sleep(60)
                val ev2 = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, 1,
                    arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                val okU = try { Privileged.injectMotion(ev2, d) } finally { ev2.recycle() }
                Log.i(TAG, "summonKeyboard tap=($fx,$fy) downOk=$okD upOk=$okU")
            } catch (t: Throwable) { Log.e(TAG, "summonKeyboard failed: ${t.message}", t) }
        }.start()
    }

    private fun applyParams(rnd: StereoRenderer) {
        val depth = applicationContext.depthStrength / 100f
        val conv = applicationContext.convergence / 100f
        rnd.baseline = 0.004f + depth * 0.026f     // 0.004..0.03 UV; kept small to avoid ghosting/double-vision
        rnd.convergence = conv
        rnd.swapLr = if (applicationContext.swapLeftRight) 1f else 0f
        // Slider 0..200% -> crosstalk coefficient 0..0.50 (100% = 0.25; LP2 lenticular
        // leak between adjacent views can be well above 30%)
        rnd.ghost = (applicationContext.actStrength / 200f) * 0.50f
        rnd.ghost2Lo = (applicationContext.ghost2Strength / 200f) * 0.50f
        rnd.ghost2Hi = (applicationContext.ghost2HiStrength / 200f) * 0.50f
        rnd.ghost2Cross = applicationContext.ghost2CrossPct / 100f
        rnd.ghostDesat = applicationContext.ghostDesatStrength / 200f
        rnd.softKnee = if (applicationContext.softKneeOn) applicationContext.softKneeStrength / 100f else 0f
        rnd.softKneeThresh = applicationContext.softKneeThreshPct / 100f
        rnd.ghostMask = if (applicationContext.ghostMaskOn) applicationContext.ghostMaskStrength / 100f else 0f
        rnd.depthSharp = if (applicationContext.depthSharpOn) applicationContext.depthSharpStrength / 100f else 0f
        val f = FloatArray(18)
        val master = applicationContext.filtersEnabled
        for (i in 0 until 6) {
            val on = master && applicationContext.filterOn(i)
            f[i * 3] = if (on) (applicationContext.filterAmt(i) - 100) / 100f else 0f
            f[i * 3 + 1] = applicationContext.filterLo(i) / 100f
            f[i * 3 + 2] = applicationContext.filterHi(i) / 100f
        }
        rnd.filters = f
    }

    private fun saveDebugFrame(src: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int) {
        try {
            val bmp = android.graphics.Bitmap.createBitmap(rowStride / 4, h, android.graphics.Bitmap.Config.ARGB_8888)
            src.rewind()
            bmp.copyPixelsFromBuffer(src)
            val cropped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h)
            val f = java.io.File(getExternalFilesDir(null), "capture_dump.png")
            java.io.FileOutputStream(f).use { cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "debug capture saved -> ${f.absolutePath} (${w}x$h)")
        } catch (t: Throwable) {
            Log.e(TAG, "saveDebugFrame failed: ${t.message}", t)
        }
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "3D Everywhere", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, Capture3DService::class.java).setAction(ACTION_STOP)
        val stopPi = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("3D Everywhere is running")
            .setContentText("Making your chosen app 3D on the lightfield display")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopEverything() {
        // Bring the app back to the real screen so the user doesn't lose it.
        try { targetPkg?.let { Privileged.moveBackToDefault(it) } } catch (_: Throwable) {}
        try { overlay?.hide() } catch (_: Throwable) {}
        try { renderer?.release() } catch (_: Throwable) {}
        try { overlay?.release() } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { if (::readerThread.isInitialized) readerThread.quitSafely() } catch (_: Throwable) {}
        renderer = null; overlay = null; virtualDisplay = null; imageReader = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { active = null; stopEverything(); super.onDestroy() }

    companion object {
        @Volatile @JvmStatic var active: Capture3DService? = null
        private const val TAG = "Capture3DService"
        private const val CHANNEL = "lume3d"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.lume3d.everywhere.STOP"

        /** Start the virtual-display 3D service (Shizuku must already be granted). */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, Capture3DService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, Capture3DService::class.java).setAction(ACTION_STOP))
        }
    }
}
