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
        if (intent?.action == ACTION_BACK) { onNotifBack(); return START_NOT_STICKY }
        if (intent?.action == ACTION_TOGGLE) { onNotifToggle(); return START_NOT_STICKY }

        startForegroundNotif()

        // Already running (e.g. Start tapped again, or a sticky restart): just bring the session
        // screen back instead of force-stopping and relaunching the app.
        if (displayId >= 0) { Session3DActivity.open(this); return START_NOT_STICKY }

        if (!Privileged.isShizukuReady()) {
            Log.e(TAG, "Shizuku not ready"); stopSelf(); return START_NOT_STICKY
        }
        // "Let edge triggers through": touches that start in a hole must reach the other app's
        // strip through our opaque 3D, which Android 12 blocks as untrusted unless this OS switch
        // is relaxed. Put back on Stop (or when the option is turned off).
        if (applicationContext.triggerHoles) setTouchMod(true)
        targetPkg = applicationContext.targetPackage
        if (targetPkg == null) { Log.e(TAG, "no target package"); stopSelf(); return START_NOT_STICKY }

        computeCaptureSize()
        startReaderThread()
        if (!startVirtualDisplay()) { Log.e(TAG, "virtual display failed"); stopEverything(); return START_NOT_STICKY }
        startOverlay()
        // The app lives on our private display; launch it now and keep showing 3D.
        Privileged.launchOnDisplay(targetPkg!!, displayId)
        curOrientation = resources.configuration.orientation
        registerSystemHints()
        watchTasks()
        Session3DActivity.open(this)   // our body on the real screen: makes BACK/HOME/Recents work
        // Not sticky: a restart after process death would show 3D with no session screen to drive
        // its visibility, and would force-stop the app again.
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------------
    // 3D visibility. The 3D is shown only when there is NO reason to hide it. Reasons:
    //   background - the session screen is not in front (HOME, another app, a notification...)
    //   recents    - Recents was opened (Launcher3's live tile may keep us "resumed" under it)
    //   homehint   - HOME was pressed; provisional until the session screen actually pauses
    // A set (not a flag) so independent causes cannot un-hide each other, and so the rotation
    // rebuild re-applies the current state instead of blindly showing.
    // ---------------------------------------------------------------------------------------
    private val hideReasons = mutableSetOf<String>()
    private val mainH = Handler(android.os.Looper.getMainLooper())
    @Volatile private var recentsHintAt = 0L
    @Volatile private var hiddenNow = false
    @Volatile private var lastFrameAt = 0L
    // Hide 3D / Show in 2D: the app stays on screen and touchable, just flat. Not a hide reason.
    @Volatile private var flat2D = false

    fun is3DShown(): Boolean = hideReasons.isEmpty() && overlay != null

    private fun addHideReason(r: String) = mainH.post { if (hideReasons.add(r)) applyVisibility() }
    private fun clearHideReasons(vararg rs: String) = mainH.post {
        var changed = false
        for (r in rs) if (hideReasons.remove(r)) changed = true
        if (changed) applyVisibility()
    }

    private fun applyVisibility() {
        val ov = overlay ?: return
        val shown = hideReasons.isEmpty()
        hiddenNow = !shown
        // CNSDK stays resumed either way: pausing and resuming it broke its surface (EGL_BAD_ALLOC
        // crash on return from HOME/Recents). Hidden = transparent window, backlight off, and our
        // depth/DIBR work paused.
        ov.show()
        ov.set3DHidden(!shown)
        ov.setFlat2D(flat2D)
        renderer?.paused = !shown
        renderer?.flat2D = flat2D
        ov.setChromeVisible(controls = shown, catcher = shown)
        applySingleView()
        updateNotif()
        Log.i(TAG, "3D ${if (shown) "shown" else "hidden"} reasons=$hideReasons flat2D=$flat2D")
    }

    /** From Session3DActivity: it came to the front (true) or went behind something (false). */
    fun onSessionForeground(front: Boolean) {
        if (front) clearHideReasons(REASON_BACKGROUND, REASON_RECENTS, REASON_HOME_HINT)
        else { addHideReason(REASON_BACKGROUND); clearHideReasons(REASON_HOME_HINT) }
    }

    /** From Session3DActivity: its window got focus, e.g. picked again from Recents' live tile. */
    fun onSessionFocused() {
        // A focus event racing just behind the Recents hint must not undo it.
        if (android.os.SystemClock.uptimeMillis() - recentsHintAt > 500) {
            clearHideReasons(REASON_RECENTS, REASON_HOME_HINT)
        }
    }

    /**
     * Instant hints from the system ("homekey" / "recentapps"), so the 3D is gone the moment the
     * launcher or Overview starts animating instead of ~0.5 s later at onPause.
     */
    private val systemHints = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { addHideReason(REASON_SCREEN_OFF); return }
                Intent.ACTION_USER_PRESENT -> { onUnlocked(); return }
                Intent.ACTION_SCREEN_ON -> {
                    // No lock screen in the way: count it as unlocked now (else USER_PRESENT does).
                    val km = getSystemService(android.app.KeyguardManager::class.java)
                    if (km?.isKeyguardLocked == false) onUnlocked()
                    return
                }
            }
            when (val reason = i.getStringExtra("reason")) {
                "homekey" -> onHomeHint()
                "recentapps" -> { recentsHintAt = android.os.SystemClock.uptimeMillis(); addHideReason(REASON_RECENTS) }
                else -> Log.i(TAG, "system dialogs closed, reason=$reason")
            }
        }
    }
    private var hintsRegistered = false

    private fun registerSystemHints() {
        if (hintsRegistered) return
        val f = android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(systemHints, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(systemHints, f)
            hintsRegistered = true
        } catch (t: Throwable) { Log.w(TAG, "hint receiver failed: ${t.message}") }
    }

    /**
     * Screen back on and unlocked. If the 3D is showing but the app's display has delivered no
     * picture for a while (a virtual display can come back from sleep without redrawing), nudge
     * it: switching its surface off and on again forces the app to draw a fresh frame.
     */
    private fun onUnlocked() {
        clearHideReasons(REASON_SCREEN_OFF)
        val since = android.os.SystemClock.uptimeMillis()
        mainH.postDelayed({
            val vd = virtualDisplay; val reader = imageReader
            if (stopped || vd == null || reader == null || hiddenNow) return@postDelayed
            if (lastFrameAt >= since) return@postDelayed
            Log.i(TAG, "no picture from the app after unlock; refreshing its display")
            try { vd.surface = null; vd.surface = reader.surface } catch (t: Throwable) { Log.w(TAG, "refresh failed: ${t.message}") }
        }, 2500)
    }

    /**
     * HOME hint. Normally the launcher comes up and the session screen pauses within a moment.
     * HOME from an accessibility app (Pie Control) or a hardware keyboard carries no display, so
     * Android resolves it to the top-focused display, which is our virtual display after any 3D
     * tap, and nothing happens. If we are still in front after a beat, do the real HOME on the
     * real screen; if even that changes nothing, show the 3D again.
     */
    private fun onHomeHint() {
        addHideReason(REASON_HOME_HINT)
        mainH.postDelayed({
            if (Session3DActivity.resumed && hideReasons.contains(REASON_HOME_HINT)) {
                Log.i(TAG, "HOME did not reach the real screen; sending HOME to display 0")
                keyExec.execute { Privileged.injectKey(3 /* KEYCODE_HOME */, 0) }
                mainH.postDelayed({
                    if (Session3DActivity.resumed) clearHideReasons(REASON_HOME_HINT)
                }, 1200)
            }
        }, 800)
    }

    // ---------------------------------------------------------------------------------------
    // Task events. Opening the app from the home screen (or a link) while it runs in 3D makes
    // Android pull its task off our virtual display onto the real screen: 3D would go black and
    // the app would show flat. Instead, move it straight back and bring the 3D session up, so
    // "open Twitch" just returns to Twitch in 3D. If the app's task is closed for good (swiped
    // away, force-stopped), end the 3D session instead of showing a black picture.
    // ---------------------------------------------------------------------------------------
    private var taskEvents: com.lume3d.everywhere.privileged.TaskEvents? = null
    @Volatile private var vdTaskId = -1

    @Volatile private var launchGuard: com.lume3d.everywhere.privileged.LaunchGuard? = null
    /** Our own starts of the target app (BACK-out restore) must not be caught by the guard. */
    @Volatile private var allowTargetStartUntil = 0L

    private fun watchTasks() {
        val ev = com.lume3d.everywhere.privileged.TaskEvents(
            onDisplayChanged = { task, disp -> mainH.post { onTaskDisplayChanged(task, disp) } },
            onRemoved = { task -> mainH.post { onTaskRemovedEvent(task) } },
            onMovedToFront = { task, disp -> mainH.post { onTaskMovedToFront(task, disp) } },
            onRestartAttempt = { task, disp -> mainH.post { onTaskMovedToFront(task, disp) } },
        )
        taskEvents = ev
        val guard = com.lume3d.everywhere.privileged.LaunchGuard(::shouldBlockLaunch)
        Thread {
            Privileged.registerTaskEvents(ev)
            if (guard.usable && !stopped && Privileged.setActivityController(guard)) {
                launchGuard = guard
                if (stopped) Privileged.setActivityController(null)   // Stop raced us
            }
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return@Thread }  // let the launch settle
            targetPkg?.let { vdTaskId = Privileged.findTaskIdForPkg(it) }
            Log.i(TAG, "watching target task $vdTaskId")
        }.start()
    }

    /**
     * Called by Android before EVERY activity start on the device (binder thread, system waiting).
     * Catches the target app's home-screen icon while it runs in 3D: instead of letting Android
     * drag the app onto the real screen (which rebuilds it and loses its place), skip that start
     * and bring the 3D session up. Must stay instant: field reads and a post only.
     */
    private fun shouldBlockLaunch(intent: Intent, pkg: String): Boolean {
        if (stopped || displayId < 0 || pkg != targetPkg) return false
        if (intent.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return false
        if (android.os.SystemClock.uptimeMillis() < allowTargetStartUntil) return false
        mainH.post {
            Log.i(TAG, "home-screen launch of $pkg caught; showing it in 3D instead")
            if (!stopped) Session3DActivity.open(this)
        }
        return true
    }

    private fun onTaskDisplayChanged(taskId: Int, newDisplay: Int) {
        if (stopped) return
        val vd = displayId
        if (vd < 0 || newDisplay == vd) return
        val pkg = targetPkg ?: return
        keyExec.execute {
            if (stopped) return@execute
            val ours = taskId == vdTaskId || taskId == Privileged.findTaskIdForPkg(pkg)
            if (!ours) return@execute
            vdTaskId = taskId
            Log.i(TAG, "target task $taskId moved to display $newDisplay (opened normally); back into 3D")
            Privileged.moveTaskToDisplay(taskId, vd)
            Session3DActivity.open(this)
        }
    }

    @Volatile private var ignoreFrontUntil = 0L

    /**
     * The app's own Recents card was tapped (or something else brought its task forward). If it
     * came forward on our virtual display, nothing would be visible on the real screen, so bring
     * the 3D session up; if Android pulled it onto the real screen, move it back first.
     */
    private fun onTaskMovedToFront(taskId: Int, newDisplay: Int) {
        if (stopped || taskId < 0 || taskId != vdTaskId) return
        if (android.os.SystemClock.uptimeMillis() < ignoreFrontUntil) return   // our own move
        if (newDisplay == displayId) {
            if (!Session3DActivity.resumed) {
                Log.i(TAG, "target task $taskId brought to front (Recents?); showing 3D")
                Session3DActivity.open(this)
            }
        } else onTaskDisplayChanged(taskId, newDisplay)
    }

    private fun onTaskRemovedEvent(taskId: Int) {
        if (stopped || taskId < 0 || taskId != vdTaskId) return
        Log.i(TAG, "target task $taskId was closed; ending 3D")
        stopEverything(returnApp = false)
    }

    /** Swiping the "<App> 3D" card out of Recents ends the 3D session, like closing an app. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (rootIntent?.component?.className == Session3DActivity::class.java.name) {
            Log.i(TAG, "session card swiped away; ending 3D")
            mainH.post { stopEverything() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // BACK: the nav-bar BACK lands on Session3DActivity, which calls this. Injecting KEYCODE_BACK
    // tagged with the virtual display makes Android move focus to it, so the captured app gets a
    // real BACK (verified on the LP2: Twitch stepped back; with its keyboard up, the keyboard
    // closed instead). One shell call at a time; presses while one is in flight are dropped.
    // ---------------------------------------------------------------------------------------
    private val keyExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var backInFlight = false

    fun forwardBack() {
        if (backInFlight) return
        backInFlight = true
        keyExec.execute {
            try {
                sendBackToApp()
                checkAppStillThere()
            } finally { backInFlight = false }
        }
    }

    /**
     * BACK at the app's first screen closes it, which leaves our virtual display empty (a black
     * 3D picture). Do what stock Android does instead: bring the app back on our display (so
     * returning to 3D shows it again) and send the session screen behind, i.e. go home.
     */
    private fun checkAppStillThere() {
        val id = displayId; val pkg = targetPkg ?: return
        if (id < 0) return
        try { Thread.sleep(700) } catch (_: InterruptedException) { return }
        if (Privileged.hasResumedActivityOnDisplay(id)) return
        Log.i(TAG, "app backed out of display $id; restoring it there and going home")
        ignoreFrontUntil = android.os.SystemClock.uptimeMillis() + 3000   // don't reopen 3D for this
        allowTargetStartUntil = ignoreFrontUntil                           // and let this start through
        Privileged.bringToFrontOnDisplay(pkg, id)
        Session3DActivity.sendToBack()
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
        lastFrameAt = android.os.SystemClock.uptimeMillis()
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
        // One atlas tile per panel hardware view: with the patched interlace.pshader the
        // interlacer tiles by hardwareViewsX, so N views mean single-step disparity between
        // ADJACENT views instead of a hard left/right cliff, so far less crosstalk ghosting for
        // the same total depth. Cached from the SDK's Config.numViews (default 8 on the LP2).
        val n = applicationContext.leiaNumViews
        Log.i(TAG, "atlas views=$n -> sbs ${captureW * n}x$captureH")
        overlay = OverlayController(
            this, captureW * n, captureH,
            secure = false, touchable = true, onTouch = ::forwardTouch,
        ) { st ->
            // CNSDK hands us a NEW SurfaceTexture on every resume. Release the previous renderer
            // first, or each hide/show leaves a whole render thread + depth NPU session running.
            try { renderer?.release() } catch (_: Throwable) {}
            val rnd = StereoRenderer(this, st, captureW, captureH, n)
            rnd.paused = hiddenNow
            rnd.flat2D = flat2D
            applyParams(rnd)
            rnd.start()
            renderer = rnd
        }.also { it.init() }
        // Show right away unless something says hidden (e.g. a rotation rebuild while the user is
        // on the home screen): resuming the InterlacedSurfaceView is what triggers CNSDK to build
        // its GL context and fire the surface-ready callback above.
        Handler(mainLooper).post {
            applyVisibility()
        }
        // A new overlay starts without holes; give other apps a beat to lay out (after rotation).
        refreshTouchHoles(delayMs = 1200)
    }

    // ---------------------------------------------------------------------------------------
    // Touch holes over other apps' edge triggers (Pie Control). Their strips can move (settings,
    // rotation), so re-read them now and then; one `dumpsys window` every 15 s is cheap.
    // ---------------------------------------------------------------------------------------
    private val holeExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var holePollStarted = false

    fun refreshTouchHoles(delayMs: Long = 0) {
        if (stopped) return
        try {
            holeExec.schedule({ updateTouchHoles() }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!holePollStarted) {
                holePollStarted = true
                holeExec.scheduleWithFixedDelay({ updateTouchHoles() }, 15, 15, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun updateTouchHoles() {
        if (stopped || overlay == null) return
        val rects = if (!applicationContext.triggerHoles) emptyList() else {
            val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            val maxThick = (48 * resources.displayMetrics.density).toInt()
            Privileged.findEdgeTriggers(packageName, b.width(), b.height(), maxThick)
        }
        mainH.post { overlay?.setTouchHoles(rects) }
    }

    /**
     * Single view in portrait (the LP2 lightfield is calibrated for landscape only, so the
     * lenticular can't present horizontal stereo there) and in flat 2D.
     */
    private fun applySingleView() {
        val portrait = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        overlay?.setSingleView(portrait || flat2D)
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

    /** "Let edge triggers through" on/off: the holes and the OS touch switch they need, together. */
    fun setTriggerHoles(on: Boolean) {
        applicationContext.triggerHoles = on
        Thread { setTouchMod(on) }.start()
        refreshTouchHoles()
    }

    @Volatile private var touchModApplied = false
    private fun setTouchMod(on: Boolean) {
        if (on == touchModApplied) return
        touchModApplied = on
        if (on) Privileged.allowTouchThroughOverlay() else Privileged.restoreTouchBlock()
    }
    /** Where the interlacer places the L/R view seam relative to the tracked face. */
    fun setCenterView(v: Float) { overlay?.setCenterView(v) }

    /** Show/hide CNSDK's built-in debug panel. */
    fun setDebugGui(on: Boolean) { overlay?.setDebugGui(on) }
    fun isDebugGui(): Boolean = overlay?.isDebugGui() ?: false

    /** Show the captured app flat (2D, still touchable) or back in 3D. */
    fun setFlat2D(on: Boolean) = mainH.post {
        if (flat2D == on) return@post
        flat2D = on
        applyVisibility()
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
    /**
     * Send one BACK key to the captured app's virtual display. Unlike text/paste (which never land
     * in fields on the untrusted display), BACK does reach it: verified on the LP2 that it hides an
     * open IME without the app navigating. Blocking shell call, so call off the main thread.
     */
    fun sendBackToApp() {
        val id = displayId
        if (id < 0) return
        val ok = Privileged.injectKey(4 /* KEYCODE_BACK */, id)
        Log.i(TAG, "BACK -> display $id ok=$ok")
    }

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

    private fun servicePi(action: String, code: Int) = android.app.PendingIntent.getService(
        this, code, Intent(this, Capture3DService::class.java).setAction(action),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * The notification doubles as a remote: tap = back to 3D; Back (only while the 3D is on
     * screen, so it never yanks focus away from another app); Hide/Show 3D; Stop.
     */
    @Suppress("DEPRECATION")
    private fun buildNotif(): Notification {
        val shown = hideReasons.isEmpty()
        val label = applicationContext.targetLabel ?: "your app"
        val mode = if (flat2D) "2D" else "3D"
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(if (shown) "$label in $mode" else "$label $mode (in background)")
            .setContentText(if (shown) "Tap to return to $mode" else "Tap to bring it back")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(Session3DActivity.pendingIntent(this))   // tap = back to 3D
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (shown) b.addAction(android.R.drawable.ic_menu_revert, "Back", servicePi(ACTION_BACK, 3))
        b.addAction(android.R.drawable.ic_menu_view, if (flat2D) "Show 3D" else "Hide 3D", servicePi(ACTION_TOGGLE, 4))
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", servicePi(ACTION_STOP, 1))
        return b.build()
    }

    private fun updateNotif() {
        if (stopped) return
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif()) } catch (_: Throwable) {}
    }

    /** Notification Back: close the shade first, then a normal forwarded BACK. */
    private fun onNotifBack() {
        if (displayId < 0) { stopSelf(); return }   // stale notification, no session
        if (!is3DShown()) return
        keyExec.execute { Privileged.collapseShade() }
        forwardBack()
    }

    /** Notification Hide/Show 3D: flips flat 2D, and brings the session to the front if needed. */
    private fun onNotifToggle() {
        if (displayId < 0) { stopSelf(); return }
        setFlat2D(!flat2D)
        if (!Session3DActivity.resumed) {
            keyExec.execute { Privileged.collapseShade() }
            Session3DActivity.open(this)
        }
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "3D Everywhere", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @Volatile private var stopped = false

    private fun stopEverything(returnApp: Boolean = true) {
        // Runs from both the Stop action and onDestroy; only the first call does anything.
        if (stopped) return
        stopped = true
        // Stop listening first: our own moves below would otherwise look like user actions.
        taskEvents?.let { ev -> Thread { Privileged.registerTaskEvents(ev, register = false) }.start() }
        taskEvents = null
        launchGuard?.let { Thread { Privileged.setActivityController(null) }.start() }
        launchGuard = null
        if (hintsRegistered) { try { unregisterReceiver(systemHints) } catch (_: Throwable) {}; hintsRegistered = false }
        mainH.removeCallbacksAndMessages(null)
        Session3DActivity.close()
        // Undo the OS-wide touch-block change we made at start, so Android is left as we found it.
        if (touchModApplied) {
            try { Privileged.restoreTouchBlock() } catch (_: Throwable) {}
            touchModApplied = false
        }
        // Bring the app back to the real screen so the user doesn't lose it.
        if (returnApp) { try { targetPkg?.let { Privileged.moveBackToDefault(it) } } catch (_: Throwable) {} }
        try { overlay?.hide() } catch (_: Throwable) {}
        try { renderer?.release() } catch (_: Throwable) {}
        try { overlay?.release() } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { if (::readerThread.isInitialized) readerThread.quitSafely() } catch (_: Throwable) {}
        renderer = null; overlay = null; virtualDisplay = null; imageReader = null
        displayId = -1
        keyExec.shutdownNow()
        holeExec.shutdownNow()
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
        const val ACTION_BACK = "com.lume3d.everywhere.BACK"
        const val ACTION_TOGGLE = "com.lume3d.everywhere.TOGGLE"
        private const val REASON_BACKGROUND = "background"
        private const val REASON_RECENTS = "recents"
        private const val REASON_HOME_HINT = "homehint"
        private const val REASON_SCREEN_OFF = "screenoff"

        /** Start the virtual-display 3D service (Shizuku must already be granted). */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, Capture3DService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, Capture3DService::class.java).setAction(ACTION_STOP))
        }
    }
}
