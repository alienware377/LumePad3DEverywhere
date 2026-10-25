package com.lume3d.everywhere.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.lume3d.everywhere.Capture3DService
import com.lume3d.everywhere.FILTER_NAMES
import com.lume3d.everywhere.LeiaHack
import com.lume3d.everywhere.centerViewX100
import com.lume3d.everywhere.interlaceMode
import com.lume3d.everywhere.actStrength
import com.lume3d.everywhere.convergence
import com.lume3d.everywhere.depthSharpOn
import com.lume3d.everywhere.depthSharpStrength
import com.lume3d.everywhere.depthStrength
import com.lume3d.everywhere.dimForPassThrough
import com.lume3d.everywhere.edgePassMargin
import com.lume3d.everywhere.filterAmt
import com.lume3d.everywhere.ghostMaskOn
import com.lume3d.everywhere.ghostMaskStrength
import com.lume3d.everywhere.leiaNumViews
import com.lume3d.everywhere.filterHi
import com.lume3d.everywhere.filterLo
import com.lume3d.everywhere.filterOn
import com.lume3d.everywhere.filtersEnabled
import com.lume3d.everywhere.ghost2CrossPct
import com.lume3d.everywhere.ghost2HiStrength
import com.lume3d.everywhere.ghost2Strength
import com.lume3d.everywhere.ghostDesatStrength
import com.lume3d.everywhere.passThroughDimPct
import com.lume3d.everywhere.setFilterOn
import com.lume3d.everywhere.softKneeOn
import com.lume3d.everywhere.softKneeStrength
import com.lume3d.everywhere.softKneeThreshPct
import com.lume3d.everywhere.touchBlockDisabled
import com.lume3d.everywhere.triggerHoles
import com.lume3d.everywhere.setFilterAmt
import com.lume3d.everywhere.setFilterHi
import com.lume3d.everywhere.setFilterLo
import com.leia.core.LogLevel
import com.leia.sdk.LeiaSDK
import com.leia.sdk.graphics.SurfaceTextureReadyCallback
import com.leia.sdk.views.InputViewsAsset
import com.leia.sdk.views.InterlacedSurfaceView

/**
 * Owns the Leia SDK + the full-screen interlaced overlay window.
 *
 * The overlay is FLAG_SECURE (so MediaProjection excludes it -> breaks the capture
 * feedback loop) and NOT_TOUCHABLE (so the user still drives the real app beneath it).
 * We render our synthesized side-by-side stereo into the InputViewsAsset's SurfaceTexture;
 * the InterlacedSurfaceView face-tracks and interlaces it onto the lightfield panel.
 */
class OverlayController(
    private val context: Context,
    private val sbsWidth: Int,
    private val sbsHeight: Int,
    private val secure: Boolean,
    private val touchable: Boolean = false,
    private val onTouch: ((android.view.MotionEvent, Int, Int) -> Unit)? = null,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
) : LeiaSDK.Delegate {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var leiaSDK: LeiaSDK? = null
    private var view: InterlacedSurfaceView? = null
    private var asset: InputViewsAsset? = null
    private var added = false
    private var exitBtn: View? = null
    private var tuneWin: View? = null
    private var inputWin: View? = null
    private var inputEdit: android.widget.EditText? = null
    // Control-group collapse state: the buttons that hide on minimize, the slider panel, and flag.
    private var ctrlBtns: List<View> = emptyList()
    private var ctrlPanel: View? = null
    private var minimized = false
    private var tdPanelView: View? = null
    private var hideOverlayActive = false
    private var syncHideBtn: (Boolean) -> Unit = {}

    /** The service reports the user-Hide state (it can also change from the notification). */
    private var touchCatcher: View? = null
    private var catcherWanted = true   // false while the 3D is hidden (see setChromeVisible)
    private var edgeMarginPx = 0
    private var passThroughAll = false
    private var guiMode = false
    private var guiInited = false
    private var guiShown = false
    @Volatile var reconvergence = 0f
        set(v) { field = v; asset?.setReconvergenceAmount(v) }

    fun init() {
        try {
            val args = LeiaSDK.InitArgs()
            args.delegate = this
            args.enableFaceTracking = true
            args.requiresFaceTrackingPermissionCheck = false // we request CAMERA ourselves
            args.faceTrackingServerLogLevel = LogLevel.Error
            args.platform.context = context.applicationContext
            args.platform.logLevel = LogLevel.Error
            leiaSDK = LeiaSDK.createSDK(args)
        } catch (t: Throwable) {
            Log.e(TAG, "LeiaSDK init failed: ${t.message}", t)
        }

        try {
            val v = InterlacedSurfaceView(context)
            val a = InputViewsAsset()
            a.setHorizontalViewsLayout() // declare the surface holds 2 views side-by-side
            a.CreateEmptySurfaceForVideo(sbsWidth, sbsHeight, object : SurfaceTextureReadyCallback {
                override fun onSurfaceTextureReady(st: SurfaceTexture) {
                    Log.i(TAG, "CNSDK video surface ready ${sbsWidth}x$sbsHeight")
                    onSurfaceReady(st)
                }
            })
            v.setViewAsset(a)
            // Touch is handled by a SEPARATE catcher window (see addTouchCatcher): the 3D
            // view must stay fullscreen and pixel-exact for lenticular alignment, so it can't
            // be inset for the edge pass-through, and it stays non-touchable itself.
            view = v
            asset = a
            addOverlay(v)
        } catch (t: Throwable) {
            Log.e(TAG, "Interlaced view setup failed: ${t.message}", t)
        }
    }

    private fun addOverlay(v: View) {
        // The 3D view is pixels only; a separate catcher window forwards touch. Keeping the
        // 3D window non-touchable also lets us drop the catcher entirely for full pass-through.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (secure) flags = flags or WindowManager.LayoutParams.FLAG_SECURE

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.OPAQUE
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING   // keep lenticular geometry
        v.visibility = View.GONE
        removeWin(lastMainView); lastMainView = null
        wm.addView(v, lp)
        lastMainView = v
        rootLp = lp
        added = true
        addTouchCatcher()   // below the controls in z (added first)
        addControls()
    }

    private var rootLp: WindowManager.LayoutParams? = null
    private var fullHeightPx = 0
    private var kbMode = false

    /**
     * A dedicated touch window that forwards gestures to the captured app, sitting between the
     * 3D pixels (below) and the control bar (above). Inset by [edgeMarginPx] with
     * FLAG_NOT_TOUCH_MODAL so gestures starting in the edge frame fall through to overlay apps
     * below us (Pie Control's edge triggers etc.). When [passThroughAll] is on it becomes
     * non-touchable, so the WHOLE screen passes through to other overlays.
     */
    private fun catcherLp(): WindowManager.LayoutParams {
        val bounds = wm.maximumWindowMetrics.bounds
        val m = edgeMarginPx.coerceIn(0, minOf(bounds.width(), bounds.height()) / 3)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // NOT_FOCUSABLE + ALT_FOCUSABLE_IM makes this window the IME's LAYERING target without
            // ever taking focus: Android then parents the keyboard to its own layer above every app
            // overlay, so Gboard (bound to the captured app's field on the VD) draws above the 3D
            // instead of hidden behind it (DisplayContent.computeImeParent).
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        if (passThroughAll) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            bounds.width() - 2 * m, bounds.height() - 2 * m, type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = m; lp.y = m
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        return lp
    }

    private fun addTouchCatcher() {
        if (!touchable || onTouch == null) return
        edgeMarginPx = context.edgePassMargin
        val cb = onTouch
        removeWin(touchCatcher); touchCatcher = null
        val catcher = View(context)
        if (!catcherWanted) catcher.visibility = View.GONE   // rebuilt while 3D is hidden
        @Suppress("ClickableViewAccessibility")
        catcher.setOnTouchListener { _, ev ->
            // Catcher origin is inset by the margin; shift back into full-3D-view space, then map
            // by the live 3D view size (NOT panel size — the view is shorter than the panel by the
            // system-inset strip, and mapping must use the actually-displayed height).
            val m = (catcher.layoutParams as? WindowManager.LayoutParams)?.x ?: 0
            ev.offsetLocation(m.toFloat(), m.toFloat())
            val b = wm.maximumWindowMetrics.bounds
            // In debug-GUI mode, touches drive CNSDK's built-in ImGui panel instead of being
            // injected into the captured app (the panel is how we reach Interlace Mode).
            if (guiMode) view?.onTouchEvent(ev)
            else cb.invoke(ev, view?.width ?: b.width(), view?.height ?: b.height())
            true
        }
        removeWin(lastCatcherWin); lastCatcherWin = null
        val clp = catcherLp()
        installTouchHoles(catcher, clp)
        wm.addView(catcher, clp)
        touchCatcher = catcher
        lastCatcherWin = catcher
        Log.i(TAG, "touch catcher added: margin=$edgeMarginPx passThrough=$passThroughAll")
    }

    // ---- Touch holes over other apps' edge triggers -------------------------------------------
    // The catcher keeps the whole screen except small holes exactly over other apps' thin edge
    // strips (Pie Control's triggers). A touch that starts in a hole skips the catcher and, since
    // the 3D window is NOT_TOUCHABLE, lands on that app's strip. No dimming, no lost edges. Needs
    // the OS touch-through switch, like every pass-through (our opaque 3D sits above them).
    // Android has no public per-window touch shape, so this uses the hidden insets callback that
    // PopupWindow and the floating toolbar use (TOUCHABLE_INSETS_REGION).
    @Volatile private var holes: List<android.graphics.Rect> = emptyList()

    /** Screen-pixel rects to leave untouched; an empty list means the catcher takes everything. */
    fun setTouchHoles(rects: List<android.graphics.Rect>) {
        if (rects == holes) return
        holes = rects
        Log.i(TAG, "touch holes: $rects")
        touchCatcher?.post { touchCatcher?.requestLayout() }
    }

    private fun installTouchHoles(catcher: View, lp: WindowManager.LayoutParams) {
        try {
            com.lume3d.everywhere.privileged.Privileged.ensureBypass()
            val iface = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, m, args ->
                when (m.name) {
                    "onComputeInternalInsets" -> { applyHoles(args!![0], lp); null }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.getOrNull(0)
                    "toString" -> "TouchHoles"
                    else -> null
                }
            }
            ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", iface)
                .invoke(catcher.viewTreeObserver, listener)
        } catch (t: Throwable) { Log.w(TAG, "touch holes unavailable: ${t.cause ?: t}") }
    }

    private fun applyHoles(info: Any, lp: WindowManager.LayoutParams) {
        val hs = holes
        val cls = info.javaClass
        if (hs.isEmpty()) {
            cls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 0)  // whole frame
            return
        }
        // Region is in the catcher's own coordinates; it sits at (lp.x, lp.y) on screen.
        val region = cls.getField("touchableRegion").get(info) as android.graphics.Region
        region.set(0, 0, lp.width, lp.height)
        for (h in hs) {
            region.op(h.left - lp.x, h.top - lp.y, h.right - lp.x, h.bottom - lp.y, android.graphics.Region.Op.DIFFERENCE)
        }
        cls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 3)      // REGION
    }

    /** Re-add a window at the top of the z-order (removeImmediate keeps ordering deterministic). */
    private fun bringToFront(v: View?) {
        v ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
        try { wm.addView(v, lp) } catch (t: Throwable) { Log.e(TAG, "bringToFront failed: ${t.message}") }
    }

    /**
     * Rebuild the catcher via remove+add (changing FLAG_NOT_TOUCHABLE with updateViewLayout
     * leaves a stale input region, so the toggle silently did nothing). The fresh catcher lands
     * on top, so we then lift the control bar and the input bar back above it.
     *
     * The input bar must be lifted even while it is GONE (which is nearly always): the system IME
     * is layered just above that box, so if the catcher ends up above the box it also covers the
     * keyboard and eats every key tap until the overlay is rebuilt from scratch.
     */
    private fun rebuildCatcher() {
        addTouchCatcher()
        bringToFront(tuneWin)
        bringToFront(inputWin)
    }

    /** Full pass-through: make the catcher ignore touch so every overlay app below gets it. */
    fun setPassThrough(on: Boolean) {
        passThroughAll = on
        updateOverlayDim()
        (tuneWin ?: view)?.post { rebuildCatcher() } ?: rebuildCatcher()
    }
    fun isPassThrough() = passThroughAll

    /** Edge pass-through margin in px; gestures inside this frame reach overlays below us. */
    fun setEdgeMargin(px: Int) {
        context.edgePassMargin = px
        edgeMarginPx = px
        updateOverlayDim()
        (tuneWin ?: view)?.post { rebuildCatcher() } ?: rebuildCatcher()
    }

    /**
     * "Dim to pass through" approach (alternative to the OS touch-block mod): while pass-through
     * is active, lower the 3D window's opacity below the obscuring-opacity threshold (0.8) so the
     * input system stops treating touches beneath us as untrusted. Full pass-through goes fully
     * transparent; edge pass-through only needs to dip just under the threshold. No-op unless the
     * user enabled the dim toggle.
     */
    private var hidden3D = false

    /**
     * Hide or show the 3D without pausing CNSDK. Pausing it (view.onPause) and resuming later hands
     * us a SurfaceTexture we cannot attach to again (eglCreateWindowSurface EGL_BAD_ALLOC, which
     * crashed the app when returning from HOME/Recents). So the window just goes fully transparent
     * (it is NOT_TOUCHABLE, and alpha 0 cannot obscure touches) and the 3D backlight goes off.
     */
    fun set3DHidden(hidden: Boolean) {
        hidden3D = hidden
        updateOverlayDim()
        applyBacklight()
    }

    /**
     * Flat 2D: the app stays on screen and touchable, just without depth. The 3D backlight goes
     * off (so the panel looks like a normal screen) and the renderer feeds both eyes the same view.
     */
    private var flat2D = false
    fun setFlat2D(on: Boolean) {
        if (flat2D == on) return
        flat2D = on
        applyBacklight()
        syncHideBtn(on)
    }

    private fun applyBacklight() {
        try { leiaSDK?.enableBacklight(!hidden3D && !flat2D) } catch (_: Throwable) {}
    }

    fun updateOverlayDim() {
        val lp = rootLp ?: return
        val v = view ?: return
        val dimA = (context.passThroughDimPct.coerceIn(10, 79)) / 100f
        val alpha = if (hidden3D) 0f else if (!context.dimForPassThrough) 1f else when {
            passThroughAll -> dimA        // user-set opacity (10..79%)
            edgeMarginPx > 0 -> dimA
            else -> 1f
        }
        if (lp.alpha == alpha) return
        lp.alpha = alpha
        try { wm.updateViewLayout(v, lp) } catch (t: Throwable) { Log.w(TAG, "dim failed: ${t.message}") }
    }

    /**
     * Reveal/hide the real system keyboard. The captured app's IME (Gboard) is already shown and
     * bound to the field, but it renders at the bottom of display 0 — underneath our opaque
     * fullscreen 3D overlay. Shrinking the overlay to the top portion exposes the keyboard so the
     * user can type straight into the captured app. Touch maps by view size, so the shrink keeps
     * taps aligned.
     */
    fun setKeyboardMode(on: Boolean) {
        val v = view ?: return
        val lp = rootLp ?: return
        if (fullHeightPx == 0) fullHeightPx = v.height
        lp.height = if (on && fullHeightPx > 0) (fullHeightPx * 0.5f).toInt()
                    else WindowManager.LayoutParams.MATCH_PARENT
        try { wm.updateViewLayout(v, lp) } catch (t: Throwable) { Log.e(TAG, "kbMode resize failed: ${t.message}") }
    }

    /**
     * Draggable control bar: ⠿ | ⚙ (settings + chat) | 3D (depth/conv) | ⌨ (keyboard) | ✕
     * Snaps to the nearest screen edge on release; double-tap or long-press ⠿ to minimize.
     */
    private fun addControls() {
        try {
            // Idempotence: a stale bar from a failed teardown would stack under the new one
            // (this instance's or a previous instance's — rotation rebuilds create new ones).
            removeWin(tuneWin); tuneWin = null
            removeWin(lastControlsWin); lastControlsWin = null
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val root = DragLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
            }

            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xCC000000.toInt())
            }

            fun mkBtn(label: String) = android.widget.TextView(context).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                gravity = Gravity.CENTER
                val p = dp(10); setPadding(p, p, p, p)
            }

            fun mkPanelBtn(label: String) = android.widget.TextView(context).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                val ph = dp(16); val pv = dp(10)
                setPadding(ph, pv, ph, pv)
                setBackgroundColor(0xFF2A2A2A.toInt())
            }

            val grip = mkBtn("⠿").apply { setTextColor(0xFF888888.toInt()) }
            val gear = mkBtn("⚙")
            val kb = mkBtn("⌨")
            val close = mkBtn("✕")

            // Grip: double-tap or long-press to minimize
            val gd = android.view.GestureDetector(context,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean { toggleMinimize(); return true }
                    override fun onLongPress(e: MotionEvent) { toggleMinimize() }
                })
            @Suppress("ClickableViewAccessibility")
            grip.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }

            // ── Settings panel (⚙): Hide Overlay + Depth/Convergence sliders ─────────────
            val settingsPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xE6101010.toInt())
                val p = dp(12); setPadding(p, p, p, p)
                visibility = View.GONE
            }

            val hideBtn = mkPanelBtn("👁  Show in 2D")
            syncHideBtn = { flat ->
                hideOverlayActive = flat
                hideBtn.text = if (flat) "👁  Show in 3D" else "👁  Show in 2D"
                hideBtn.setBackgroundColor(if (flat) 0xFF442200.toInt() else 0xFF2A2A2A.toInt())
            }
            hideBtn.setOnClickListener {
                Capture3DService.active?.setFlat2D(!hideOverlayActive)   // the service syncs us back
            }

            val divider1 = View(context).apply { setBackgroundColor(0xFF333333.toInt()) }

            val depthLabel = android.widget.TextView(context).apply { setTextColor(0xFFD8DCE6.toInt()); textSize = 13f }
            val depthSeek = SeekBar(context).apply { max = 100 }
            val convLabel = android.widget.TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                val mt = dp(8); setPadding(0, mt, 0, 0)
            }
            val convSeek = SeekBar(context).apply { max = 100 }
            val actLabel = android.widget.TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                val mt = dp(8); setPadding(0, mt, 0, 0)
            }
            val actSeek = SeekBar(context).apply { max = 200 }

            fun syncLabels() {
                depthLabel.text = "Depth (pop-out): ${context.depthStrength}%"
                convLabel.text = "Convergence: ${context.convergence}%"
                actLabel.text = "Ghost fix: ${context.actStrength}%"
            }
            depthSeek.progress = context.depthStrength
            convSeek.progress = context.convergence
            actSeek.progress = context.actStrength
            syncLabels()

            depthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    context.depthStrength = p; syncLabels(); Capture3DService.active?.refreshParams()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            convSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    context.convergence = p; syncLabels(); Capture3DService.active?.refreshParams()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            actSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    context.actStrength = p; syncLabels(); Capture3DService.active?.refreshParams()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            val seekW = dp(280)
            val panelW = dp(280)

            val passAccent = android.content.res.ColorStateList.valueOf(0xFF9C7BFF.toInt())

            // Two independent ways to let released touches reach other overlay apps:
            val blockCheck = android.widget.CheckBox(context).apply {
                text = "Allow touch-through (OS mod, no dimming)"
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                buttonTintList = passAccent
                isChecked = context.touchBlockDisabled
                setOnCheckedChangeListener { _, on -> Capture3DService.active?.setTouchBlockDisabled(on) }
            }
            val dimCheck = android.widget.CheckBox(context).apply {
                text = "Dim 3D to pass through (no OS change)"
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                buttonTintList = passAccent
                isChecked = context.dimForPassThrough
                setOnCheckedChangeListener { _, on -> Capture3DService.active?.setDimForPassThrough(on) }
            }
            val dimLabel = android.widget.TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                val mt = dp(4); setPadding(0, mt, 0, 0)
            }
            fun syncDim() { dimLabel.text = "Pass-through transparency: ${context.passThroughDimPct}%" }
            syncDim()
            val dimSeek = SeekBar(context).apply { max = 79; progress = context.passThroughDimPct.coerceIn(10, 79) }
            dimSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    context.passThroughDimPct = p.coerceIn(10, 79); syncDim()
                    Capture3DService.active?.setDimForPassThrough(context.dimForPassThrough)  // re-apply alpha
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            // Full touch pass-through: hand ALL touches to other overlay apps until toggled back.
            val passBtn = mkPanelBtn("👆  Pass Touches to Other Apps")
            fun syncPass() {
                val on = Capture3DService.active?.isPassThrough() ?: false
                passBtn.text = if (on) "👆  Touch: Other Apps (tap to reclaim)" else "👆  Pass Touches to Other Apps"
                passBtn.setBackgroundColor(if (on) 0xFF224422.toInt() else 0xFF2A2A2A.toInt())
            }
            passBtn.setOnClickListener {
                val on = !(Capture3DService.active?.isPassThrough() ?: false)
                Log.i(TAG, "passBtn clicked -> passThrough=$on")
                // Turning pass-through ON auto-enables dimming (so it works even without the OS
                // mod); the user can still uncheck Dim afterward while pass-through stays on.
                if (on && !context.dimForPassThrough) {
                    context.dimForPassThrough = true; dimCheck.isChecked = true
                    Capture3DService.active?.setDimForPassThrough(true)
                }
                Capture3DService.active?.setPassThrough(on); syncPass()
            }
            syncPass()

            val edgeLabel = android.widget.TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                val mt = dp(8); setPadding(0, mt, 0, 0)
            }
            fun syncEdge() { edgeLabel.text = "Edge pass-through (for Pie Control etc.): ${context.edgePassMargin}px" }
            syncEdge()
            val edgeSeek = SeekBar(context).apply { max = 150; progress = context.edgePassMargin }
            edgeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    syncEdge()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Log.i(TAG, "edgeSeek released -> ${sb?.progress}px")
                    Capture3DService.active?.setEdgeMargin(sb?.progress ?: 0)   // rebuild once on release
                }
            })

            val holesCheck = android.widget.CheckBox(context).apply {
                text = "Let edge triggers through (Pie Control etc.)"
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                buttonTintList = passAccent
                isChecked = context.triggerHoles
                setOnCheckedChangeListener { _, on ->
                    context.triggerHoles = on
                    Capture3DService.active?.refreshTouchHoles()
                }
            }

            // CNSDK debug panel: the only way to reach Interlace Mode (View Peeling experiment).
            // While it's up, touches drive the panel instead of the captured app.
            val guiBtn = mkPanelBtn("🛠  CNSDK Debug Panel")
            guiBtn.setOnClickListener {
                val on = !(Capture3DService.active?.isDebugGui() ?: false)
                Capture3DService.active?.setDebugGui(on)
                guiBtn.text = if (on) "🛠  Debug Panel ON (tap to exit)" else "🛠  CNSDK Debug Panel"
                guiBtn.setBackgroundColor(if (on) 0xFF224422.toInt() else 0xFF2A2A2A.toInt())
                if (on) ctrlPanel?.visibility = View.GONE   // get our menu out of the way
            }

            settingsPanel.addView(hideBtn, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(guiBtn, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            settingsPanel.addView(passBtn, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            settingsPanel.addView(edgeLabel)
            settingsPanel.addView(edgeSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(holesCheck, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(blockCheck, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(dimCheck, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(dimLabel)
            settingsPanel.addView(dimSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(divider1, LinearLayout.LayoutParams(panelW, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
            settingsPanel.addView(depthLabel)
            settingsPanel.addView(depthSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(convLabel)
            settingsPanel.addView(convSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(actLabel)
            settingsPanel.addView(actSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))

            // Center View: where the interlacer puts the L/R seam relative to your face. The
            // single most direct lever on the double-image (a mis-placed seam makes every eye
            // straddle it). Factory 2.64 on this panel; 2.24 measured better. 0.00..5.00.
            val cvLabel = android.widget.TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                val mt = dp(8); setPadding(0, mt, 0, 0)
            }
            fun syncCv() { cvLabel.text = "Center View (seam position): %.2f".format(context.centerViewX100 / 100f) }
            syncCv()
            val cvSeek = SeekBar(context).apply { max = 500; progress = context.centerViewX100 }
            cvSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    context.centerViewX100 = p; syncCv()
                    Capture3DService.active?.setCenterView(p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            settingsPanel.addView(cvLabel)
            settingsPanel.addView(cvSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))

            // EXPERIMENTAL: View Peeling. Switches the native interlacer from Stereo Sliding (2
            // source views + hard L/R seam) to true N-view, and feeds it one atlas tile per
            // hardware view. Needs a restart of 3D because the atlas size is fixed at creation.
            val vpBtn = mkPanelBtn("")
            fun syncVp() {
                val on = context.interlaceMode == 0
                vpBtn.text = if (on) "🧪  View Peeling: ON (restart 3D)" else "🧪  Try View Peeling (experimental)"
                vpBtn.setBackgroundColor(if (on) 0xFF224422.toInt() else 0xFF2A2A2A.toInt())
            }
            syncVp()
            vpBtn.setOnClickListener {
                val on = context.interlaceMode != 0
                context.interlaceMode = if (on) 0 else -1
                // View Peeling wants one tile per hardware view; Stereo Sliding wants exactly 2.
                context.leiaNumViews = if (on) 8 else 2
                syncVp()
                Log.i(TAG, "view peeling -> $on (interlaceMode=${context.interlaceMode}, tiles=${context.leiaNumViews})")
            }
            settingsPanel.addView(vpBtn, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

            // ── band-limited visual filters (parametric-EQ style: amount + luminance band) ──
            settingsPanel.addView(View(context).apply { setBackgroundColor(0xFF333333.toInt()) },
                LinearLayout.LayoutParams(seekW, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(6) })

            val accent = android.content.res.ColorStateList.valueOf(0xFF9C7BFF.toInt())
            val filterGroups = mutableListOf<Pair<android.widget.CheckBox, LinearLayout>>()
            fun refreshFilterStyles(master: Boolean) {
                filterGroups.forEach { (cb, body) ->
                    val on = master && cb.isChecked
                    body.alpha = if (on) 1f else 0.35f
                    fun setEnabledDeep(v: View, e: Boolean) {
                        v.isEnabled = e
                        if (v is android.view.ViewGroup) for (c in 0 until v.childCount) setEnabledDeep(v.getChildAt(c), e)
                    }
                    setEnabledDeep(body, on)
                    cb.isEnabled = master
                    cb.alpha = if (master) 1f else 0.35f
                }
            }

            val masterCheck = android.widget.CheckBox(context).apply {
                text = "Filters  (amount · brightness range)"
                setTextColor(0xFF9AA3B2.toInt()); textSize = 12f
                buttonTintList = accent
                isChecked = context.filtersEnabled
            }
            masterCheck.setOnCheckedChangeListener { _, on ->
                context.filtersEnabled = on
                refreshFilterStyles(on)
                Capture3DService.active?.refreshParams()
            }
            settingsPanel.addView(masterCheck)

            for (i in FILTER_NAMES.indices) {
                val fCheck = android.widget.CheckBox(context).apply {
                    buttonTintList = accent
                    isChecked = context.filterOn(i)
                }
                val fLabel = android.widget.TextView(context).apply {
                    setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                }
                fun syncF() {
                    val amt = context.filterAmt(i) - 100
                    val sign = if (amt > 0) "+" else ""
                    fLabel.text = "${FILTER_NAMES[i]}: $sign$amt  ·  ${context.filterLo(i)}–${context.filterHi(i)}%"
                }
                syncF()
                val amtSeek = SeekBar(context).apply { max = 200; progress = context.filterAmt(i) }
                val loSeek = SeekBar(context).apply { max = 100; progress = context.filterLo(i) }
                val hiSeek = SeekBar(context).apply { max = 100; progress = context.filterHi(i) }
                amtSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        context.setFilterAmt(i, p); syncF(); Capture3DService.active?.refreshParams()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                loSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        context.setFilterLo(i, p)
                        if (p > context.filterHi(i)) { context.setFilterHi(i, p); hiSeek.progress = p }
                        syncF(); Capture3DService.active?.refreshParams()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                hiSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        context.setFilterHi(i, p)
                        if (p < context.filterLo(i)) { context.setFilterLo(i, p); loSeek.progress = p }
                        syncF(); Capture3DService.active?.refreshParams()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })

                val headRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val mt = dp(6); setPadding(0, mt, 0, 0)
                }
                headRow.addView(fCheck)
                headRow.addView(fLabel)

                val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val range = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val half = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                range.addView(loSeek, half)
                range.addView(hiSeek, half)
                body.addView(amtSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
                body.addView(range, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))

                fCheck.setOnCheckedChangeListener { _, on ->
                    context.setFilterOn(i, on)
                    refreshFilterStyles(context.filtersEnabled)
                    Capture3DService.active?.refreshParams()
                }
                filterGroups.add(fCheck to body)
                settingsPanel.addView(headRow)
                settingsPanel.addView(body)
            }
            refreshFilterStyles(context.filtersEnabled)

            // ── post-filter ghost fix (2nd pass, no black-lift) ──
            settingsPanel.addView(View(context).apply { setBackgroundColor(0xFF333333.toInt()) },
                LinearLayout.LayoutParams(seekW, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(6) })
            fun addSlider(maxV: Int, get: () -> Int, set: (Int) -> Unit, text: (Int) -> String) {
                val lbl = android.widget.TextView(context).apply {
                    setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                    val mt = dp(6); setPadding(0, mt, 0, 0)
                    this.text = text(get())
                }
                val seek = SeekBar(context).apply { max = maxV; progress = get() }
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        set(p); lbl.text = text(p); Capture3DService.active?.refreshParams()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                settingsPanel.addView(lbl)
                settingsPanel.addView(seek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            addSlider(200, { context.ghost2Strength }, { context.ghost2Strength = it },
                { "2nd Pass Ghost Fix (darks): $it%" })
            addSlider(200, { context.ghost2HiStrength }, { context.ghost2HiStrength = it },
                { "2nd Pass Ghost Fix (brights): $it%" })
            addSlider(100, { context.ghost2CrossPct }, { context.ghost2CrossPct = it },
                { "2nd Pass Split Brightness: $it%" })

            val gdLabel = android.widget.TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                val mt = dp(8); setPadding(0, mt, 0, 0)
            }
            fun syncGd() { gdLabel.text = "Color Ghost Fix: ${context.ghostDesatStrength}%" }
            syncGd()
            val gdSeek = SeekBar(context).apply { max = 200; progress = context.ghostDesatStrength }
            gdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    context.ghostDesatStrength = p; syncGd(); Capture3DService.active?.refreshParams()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            settingsPanel.addView(gdLabel)
            settingsPanel.addView(gdSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))

            // ── extra ghost/quality effects: each an on/off + strength (+ optional 2nd slider) ──
            settingsPanel.addView(View(context).apply { setBackgroundColor(0xFF333333.toInt()) },
                LinearLayout.LayoutParams(seekW, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(6) })
            fun addEffect(title: String, onGet: () -> Boolean, onSet: (Boolean) -> Unit,
                          sliders: List<Triple<String, () -> Int, (Int) -> Unit>>, maxV: Int = 100) {
                val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                fun restyle() {
                    val on = onGet()
                    body.alpha = if (on) 1f else 0.35f
                    for (c in 0 until body.childCount) body.getChildAt(c).isEnabled = on
                }
                val cb = android.widget.CheckBox(context).apply {
                    text = title
                    setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                    buttonTintList = passAccent
                    isChecked = onGet()
                    val mt = dp(6); setPadding(paddingLeft, mt, 0, 0)
                }
                for ((lblText, g, s) in sliders) {
                    val lbl = android.widget.TextView(context).apply {
                        setTextColor(0xFFD8DCE6.toInt()); textSize = 12f; text = "$lblText: ${g()}%"
                    }
                    val seek = SeekBar(context).apply { max = maxV; progress = g() }
                    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                            s(p); lbl.text = "$lblText: $p%"; Capture3DService.active?.refreshParams()
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                    body.addView(lbl)
                    body.addView(seek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                cb.setOnCheckedChangeListener { _, on -> onSet(on); restyle(); Capture3DService.active?.refreshParams() }
                restyle()
                settingsPanel.addView(cb)
                settingsPanel.addView(body)
            }
            addEffect("Soft-knee depth (tames extreme pop-out ghosts)",
                { context.softKneeOn }, { context.softKneeOn = it },
                listOf(
                    Triple("Amount", { context.softKneeStrength }, { v: Int -> context.softKneeStrength = v }),
                    Triple("Knee start", { context.softKneeThreshPct }, { v: Int -> context.softKneeThreshPct = v })))
            addEffect("Edge ghost masking (softens ghost-prone edges)",
                { context.ghostMaskOn }, { context.ghostMaskOn = it },
                listOf(Triple("Amount", { context.ghostMaskStrength }, { v: Int -> context.ghostMaskStrength = v })))
            addEffect("Crisp depth edges (sharpens depth on colour edges)",
                { context.depthSharpOn }, { context.depthSharpOn = it },
                listOf(Triple("Amount", { context.depthSharpStrength }, { v: Int -> context.depthSharpStrength = v })))

            gear.setOnClickListener {
                ctrlPanel?.let { it.visibility = if (it.visibility == View.GONE) View.VISIBLE else View.GONE }
            }
            kb.setOnClickListener { toggleKeyboard() }
            close.setOnClickListener { Capture3DService.stop(context) }

            bar.addView(grip)
            bar.addView(gear)
            bar.addView(kb)
            bar.addView(close)
            root.addView(bar)
            root.dragHandle = bar   // drag from the button row only; the panel scrolls
            // The panel is tall now (filter EQ) — scroll it, capped well under screen height.
            val scroll = android.widget.ScrollView(context).apply {
                addView(settingsPanel)
                visibility = View.GONE
            }
            root.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(480)))
            settingsPanel.visibility = View.VISIBLE   // visibility now driven by the scroll wrapper

            ctrlBtns = listOf(gear, kb, close)
            ctrlPanel = scroll
            tdPanelView = null

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = dp(8); lp.y = dp(8)
            wm.addView(root, lp)
            tuneWin = root
            lastControlsWin = root
        } catch (t: Throwable) {
            Log.e(TAG, "controls failed: ${t.message}")
        }
    }

    /** Collapse/expand the control group to just the grip; re-tuck to its edge if snapped. */
    private fun toggleMinimize() {
        minimized = !minimized
        val vis = if (minimized) View.GONE else View.VISIBLE
        ctrlBtns.forEach { it.visibility = vis }
        if (minimized) {
            ctrlPanel?.visibility   = View.GONE
            tdPanelView?.visibility = View.GONE
        }
        (tuneWin as? DragLayout)?.onContentResized()
    }

    // ── z-order: keep main overlay below other TYPE_APPLICATION_OVERLAY windows ───────
    // Within the OVERLAY bucket, WindowManagerService reassigns SurfaceFlinger layers on
    // every traversal, so a one-shot setLayer() gets clobbered the moment any other
    // overlay is added/removed. Reapply on each onPreDraw so a deeply negative absolute
    // layer wins persistently — pre-existing overlays (Pie Control, floating apps,
    // floating keyboards, etc.) stay above us.
    // Verify with: adb shell dumpsys SurfaceFlinger | grep -i lume3d
    private fun lowerBelowOtherOverlays(v: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val targetLayer = -1_000_000   // far below anything WMS will ever assign siblings
        var sc: SurfaceControl? = null
        var running = false
        var logged = false
        // GL SurfaceView rendering never invalidates the view hierarchy, so draw-listener
        // hooks never fire — and WMS reassigns sibling layers on every traversal (Pie
        // Control's show/hide animations trigger them constantly). Re-assert per vsync:
        // last writer in a frame wins, so we stay under other overlay apps' windows.
        val choreographer = android.view.Choreographer.getInstance()
        lateinit var tick: android.view.Choreographer.FrameCallback
        tick = android.view.Choreographer.FrameCallback {
            if (!running) return@FrameCallback
            val cur = sc ?: surfaceControlOf(v).also { sc = it }
            if (cur != null && cur.isValid) {
                try {
                    SurfaceControl.Transaction().setLayer(cur, targetLayer).apply()
                    if (!logged) { logged = true; Log.i(TAG, "SF layer reassert active (target=$targetLayer)") }
                } catch (_: Throwable) { sc = null }
            }
            choreographer.postFrameCallback(tick)
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                running = true; sc = null
                choreographer.postFrameCallback(tick)
            }
            override fun onViewDetachedFromWindow(view: View) { running = false }
        })
        if (v.isAttachedToWindow) { running = true; choreographer.postFrameCallback(tick) }
    }

    private var scLookupLogged = false

    private fun surfaceControlOf(view: View): SurfaceControl? = try {
        exemptHiddenApis()
        val ai = View::class.java.getDeclaredField("mAttachInfo")
            .apply { isAccessible = true }.get(view) ?: return null
        val vri = Class.forName("android.view.View\$AttachInfo")
            .getDeclaredField("mViewRootImpl").apply { isAccessible = true }.get(ai) ?: return null
        Class.forName("android.view.ViewRootImpl")
            .getDeclaredField("mSurfaceControl").apply { isAccessible = true }.get(vri) as? SurfaceControl
    } catch (t: Throwable) {
        if (!scLookupLogged) { scLookupLogged = true; Log.e(TAG, "surfaceControlOf failed: $t") }
        null
    }

    private var hiddenApisExempted = false

    /** ViewRootImpl.mSurfaceControl is a blocked non-SDK field; the classic meta-reflection
     *  trick (calling VMRuntime.setHiddenApiExemptions via reflection-on-reflection) lifts
     *  the restriction for this process. */
    private fun exemptHiddenApis() {
        if (hiddenApisExempted) return
        hiddenApisExempted = true
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", arrayOfNulls<Class<*>>(0)) as java.lang.reflect.Method
            val setExemptions = getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as java.lang.reflect.Method
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
            Log.i(TAG, "hidden-api exemptions lifted")
        } catch (t: Throwable) { Log.w(TAG, "hidden api exemption failed: ${t.message}") }
    }

    /**
     * A LinearLayout-backed overlay window the user can drag anywhere (from any part of it — child
     * buttons still receive taps via a touch-slop check) and that snaps to the nearest screen edge
     * on release. Position is driven through the window's LayoutParams x/y (gravity TOP|START).
     */
    private inner class DragLayout(ctx: Context) : LinearLayout(ctx) {
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var snapped = false
        private var downOnHandle = false
        /** Only gestures starting on this child may drag the window — panel touches
         *  (sliders, ScrollView) pass through untouched. Null = whole window drags. */
        var dragHandle: View? = null

        /** After the group grows/shrinks (minimize toggle), re-tuck flush to its edge if snapped. */
        fun onContentResized() { if (snapped) post { snapToEdge() } }

        private fun lp() = layoutParams as WindowManager.LayoutParams
        private fun screenW() = wm.maximumWindowMetrics.bounds.width()
        private fun screenH() = wm.maximumWindowMetrics.bounds.height()

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    val l = lp(); startX = l.x; startY = l.y; dragging = false
                    downOnHandle = dragHandle?.let { h ->
                        ev.y >= h.top && ev.y <= h.bottom && ev.x >= h.left && ev.x <= h.right
                    } ?: true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (downOnHandle && !dragging &&
                        (kotlin.math.abs(ev.rawX - downRawX) > slop ||
                            kotlin.math.abs(ev.rawY - downRawY) > slop)) {
                        dragging = true; snapped = false; return true   // steal the gesture from the buttons
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    val l = lp(); startX = l.x; startY = l.y
                    return true   // ensure we keep getting MOVE on empty bar areas
                }
                MotionEvent.ACTION_MOVE -> {
                    val l = lp()
                    l.x = (startX + (ev.rawX - downRawX)).toInt().coerceIn(0, (screenW() - width).coerceAtLeast(0))
                    l.y = (startY + (ev.rawY - downRawY)).toInt().coerceIn(0, (screenH() - height).coerceAtLeast(0))
                    try { wm.updateViewLayout(this, l) } catch (_: Throwable) {}
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge()
                    dragging = false
                    return true
                }
            }
            return dragging
        }

        private fun snapToEdge() {
            val l = lp()
            val maxX = (screenW() - width).coerceAtLeast(0)
            val maxY = (screenH() - height).coerceAtLeast(0)
            val dl = l.x; val dr = maxX - l.x; val dt = l.y; val db = maxY - l.y
            when (minOf(dl, dr, dt, db)) {
                dl -> l.x = 0
                dr -> l.x = maxX
                dt -> l.y = 0
                else -> l.y = maxY
            }
            snapped = true
            try { wm.updateViewLayout(this, l) } catch (_: Throwable) {}
        }
    }

    private val kbHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * ⌨ button: close the keyboard if it is up, otherwise open it. The IME is bound to the
     * captured app's field on the virtual display, which our app can't see, so ask the system
     * (`dumpsys input_method` via Shizuku) whether it is showing. Off the main thread: it's a shell
     * round-trip.
     */
    private fun toggleKeyboard() {
        Thread {
            val shown = com.lume3d.everywhere.privileged.Privileged.isImeShown()
            Log.i(TAG, "keyboard button: imeShown=$shown")
            // Close = one BACK sent only to the virtual display. While the IME is up, the IME
            // consumes that BACK and hides itself; the captured app does not navigate (verified on
            // the LP2: mInputShown -> false, same resumed activity). Done right after the check to
            // keep the window where the IME could vanish on its own (and BACK would then go back
            // in the app) as small as possible.
            if (shown) Capture3DService.active?.sendBackToApp()
            else kbHandler.post { openKeyboard() }
        }.start()
    }

    private fun openKeyboard() {
        // The catcher is the IME's layering target, so the keyboard draws above the 3D by itself:
        // just re-tap the last text field in the app. If the keyboard still isn't up after a beat
        // (no field tapped yet, or a ROM where the layering trick fails), use the old box relay.
        Capture3DService.active?.summonKeyboard()
        Thread {
            try { Thread.sleep(900) } catch (_: InterruptedException) { return@Thread }
            if (com.lume3d.everywhere.privileged.Privileged.isImeShown()) Log.i(TAG, "keyboard up via direct tap")
            else kbHandler.post { openKeyboardViaBox() }
        }.start()
    }

    private fun openKeyboardViaBox() {
        // Pop a momentary FOCUSABLE box from OUR app so the IME draws above the 3D overlay, then
        // immediately hand focus back to the captured app's field (re-tap on the virtual display)
        // and remove our box — WITHOUT hiding the soft input. The keyboard stays up, now rebound to
        // the captured field, so the user types straight into it. No Send button needed.
        if (inputWin == null) createInputBar()
        val win = inputWin ?: return
        win.visibility = View.VISIBLE
        inputEdit?.let { e ->
            e.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        kbHandler.removeCallbacksAndMessages(null)
        kbHandler.postDelayed({
            Capture3DService.active?.summonKeyboard()  // captured field reclaims focus + the IME
            inputWin?.visibility = View.GONE           // get our box out of the way (keep IME up)
        }, 600)
    }

    private fun hideInputBar() {
        inputEdit?.let { e ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(e.windowToken, 0)
        }
        inputWin?.visibility = View.GONE
    }

    /**
     * A bottom input bar in a FOCUSABLE overlay window. The system IME targets this window and
     * therefore draws above our (non-focusable) 3D overlay. On Send we hand the finished string
     * to the service, which injects it into the captured app's focused field via shell `input`.
     */
    private fun createInputBar() {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6101010.toInt())
            val p = dp(6); setPadding(p, p, p, p)
            gravity = Gravity.CENTER_VERTICAL
        }
        val edit = android.widget.EditText(context).apply {
            hint = "Type here, then Send →"
            setHintTextColor(0xFF888888.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF202020.toInt())
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            val p = dp(8); setPadding(p, p, p, p)
        }
        val send = TextView(context).apply {
            text = "Send"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC2A6FF0.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            val p = dp(14); setPadding(p, dp(10), p, dp(10))
        }
        fun submit() {
            val t = edit.text.toString()
            val svc = Capture3DService.active
            if (svc != null) {
                // Use reflection to get the private lastTapX/Y from the service.
                // Easier: just call sendTextToApp and let it use its own tracked position.
                svc.sendTextToAppAtLastTap(t, submit = true)
            }
            edit.setText("")
            hideInputBar()
        }
        send.setOnClickListener { submit() }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }

        row.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // Focusable (no FLAG_NOT_FOCUSABLE) so it can own the IME.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        row.visibility = View.GONE
        removeWin(lastInputWin); lastInputWin = null
        wm.addView(row, lp)
        inputWin = row
        lastInputWin = row
        inputEdit = edit
    }

    // ── ACT (Leia's built-in anti-crosstalk) strength ─────────────────────────────────
    // Config.act_beta scales the interlacer's crosstalk-cancellation sharpening. We cache
    // the factory value on first read and scale it by the user's percent (100 = factory).
    private var factoryActBeta = Float.NaN

    fun setActStrength(percent: Int) {
        val sdk = leiaSDK ?: try { LeiaSDK.getInstance() } catch (t: Throwable) { null }
        if (sdk == null) { Log.w(TAG, "setActStrength($percent): no LeiaSDK instance"); return }
        try {
            val cfg = sdk.config
            if (cfg == null) { Log.w(TAG, "setActStrength($percent): null config"); return }
            if (factoryActBeta.isNaN()) {
                factoryActBeta = cfg.act_beta
                Log.i(TAG, "factory ACT: beta=${cfg.act_beta} gamma=${cfg.act_gamma} " +
                    "singleTap=${cfg.act_singleTapCoef} " +
                    "kX=${cfg.sharpeningKernelX?.joinToString()} " +
                    "kY=${cfg.sharpeningKernelY?.joinToString()}")
            }
            cfg.act_beta = factoryActBeta * (percent / 100f)
            sdk.setConfig(cfg)
            Log.i(TAG, "ACT beta -> ${cfg.act_beta} ($percent%)")
        } catch (t: Throwable) {
            Log.e(TAG, "setActStrength failed: ${t.message}")
        }
    }

    /**
     * Apply the user-selected interlace mode (View Peeling etc.) if one is set.
     *
     * Must run on the GL thread. The native SetInterlaceMode throws (and would abort the process)
     * if called from elsewhere, so we go through InterlacedSurfaceView.queueEvent. Delayed a beat
     * so the interlacer is fully constructed before we poke its vtable.
     */
    fun applyInterlaceMode() {
        val mode = context.interlaceMode
        if (mode < 0) return                      // -1 = leave the SDK default alone
        val v = view ?: return
        kbHandler.postDelayed({
            try {
                v.queueEvent {
                    val ok = LeiaHack.setInterlaceMode(v, mode)
                    Log.i(TAG, "interlaceMode=$mode applied=$ok")
                }
            } catch (t: Throwable) { Log.e(TAG, "applyInterlaceMode failed: ${t.message}") }
        }, 1500)
    }

    /**
     * Set Config.centerViewNumber: where the interlacer puts the left/right view boundary
     * relative to the tracked face. Miscalibration here makes each eye straddle the seam, which
     * is a real double-image source (2.24 measured better than the 2.64 factory value).
     * Unlike act_beta (accepted-but-ignored), this one drives the per-pixel view index directly.
     */
    fun setCenterView(v: Float) {
        val sdk = leiaSDK ?: try { LeiaSDK.getInstance() } catch (_: Throwable) { null } ?: return
        try {
            val cfg = sdk.config ?: return
            cfg.centerViewNumber = v
            sdk.setConfig(cfg)
            Log.i(TAG, "centerViewNumber -> $v (readback=${sdk.config?.centerViewNumber})")
        } catch (t: Throwable) { Log.e(TAG, "setCenterView failed: ${t.message}") }
    }

    /**
     * Show/hide CNSDK's built-in ImGui debug panel and route touches to it.
     *
     * This is the only Java-reachable way to reach `Interlace Mode` (View Peeling / View Sliding /
     * Stereo Sliding). The native `SetInterlaceMode` has no JNI export; the panel's buttons call
     * it internally on the interlacer's own thread, which also satisfies its thread check. The
     * panel additionally prints the real "Number of HW/SW Views", confirming the view geometry.
     *
     * `toggleGui()` initialises the GUI (first call only); `toggleGuiVisibility()` shows/hides it.
     * Both are queued to the GL thread by the SDK.
     */
    fun setDebugGui(on: Boolean) {
        val v = view ?: return
        try {
            // toggleGui() BOTH initialises AND shows the panel. Blindly calling
            // toggleGuiVisibility() after it cancelled the show, leaving the panel hidden while
            // guiMode said "on" (so touches still went to the captured app). Track the real
            // shown-state and only toggle when it differs from what was asked for.
            if (!guiInited) { v.toggleGui(); guiInited = true; guiShown = true }
            if (guiShown != on) { v.toggleGuiVisibility(); guiShown = !guiShown }
            guiMode = guiShown
            Log.i(TAG, "debug GUI shown=$guiShown (touch -> ${if (guiMode) "ImGui" else "captured app"})")
        } catch (t: Throwable) {
            Log.e(TAG, "setDebugGui failed: ${t.message}")
        }
    }
    fun isDebugGui() = guiMode

    /** 2D single-view (portrait fallback) vs full lightfield 3D (landscape). */
    private var singleView: Boolean? = null
    fun setSingleView(on: Boolean) {
        if (singleView == on) return
        singleView = on
        try { view?.setSingleViewMode(on); Log.i(TAG, "singleViewMode=$on") }
        catch (t: Throwable) { Log.e(TAG, "setSingleView failed: ${t.message}") }
    }

    fun show() {
        view?.let { if (it.visibility != View.VISIBLE) { it.visibility = View.VISIBLE; it.onResume(); Log.i(TAG, "overlay SHOW") } }
    }

    fun hide() {
        view?.let { if (it.visibility != View.GONE) { it.onPause(); it.visibility = View.GONE; Log.i(TAG, "overlay HIDE") } }
        // Whatever is revealed (launcher, other apps) is flat 2D: the 3D backlight would only blur it.
        try { leiaSDK?.enableBacklight(false) } catch (_: Throwable) {}
    }

    /**
     * Show or hide our other windows along with the 3D. The touch catcher must go when the 3D does,
     * or it keeps eating taps meant for the launcher/app underneath and sends them to the hidden
     * app. The control bar stays for a user Hide (its Show button) and goes otherwise, so it does
     * not float over the home screen or other apps.
     */
    fun setChromeVisible(controls: Boolean, catcher: Boolean) {
        tuneWin?.visibility = if (controls) View.VISIBLE else View.GONE
        catcherWanted = catcher
        touchCatcher?.visibility = if (catcher) View.VISIBLE else View.GONE
        if (!controls) inputWin?.visibility = View.GONE
    }

    /** Remove a window; fall back to immediate removal so rotation teardown can't leak it. */
    private fun removeWin(v: View?) {
        v ?: return
        try { wm.removeView(v) } catch (t: Throwable) {
            Log.w(TAG, "removeView failed (${t.message}); trying immediate")
            try { wm.removeViewImmediate(v) } catch (t2: Throwable) {
                Log.e(TAG, "removeViewImmediate also failed: ${t2.message}")
            }
        }
    }

    fun release() {
        try { view?.onPause() } catch (_: Throwable) {}
        removeWin(exitBtn); exitBtn = null
        removeWin(tuneWin); tuneWin = null
        removeWin(inputWin); inputWin = null
        removeWin(touchCatcher); touchCatcher = null
        if (added) removeWin(view)
        try { LeiaSDK.shutdownSDK() } catch (_: Throwable) {}
        view = null; asset = null; leiaSDK = null; added = false
    }

    // ---- LeiaSDK.Delegate ----
    override fun didInitialize(sdk: LeiaSDK) {
        Log.i(TAG, "LeiaSDK initialized; enabling 3D backlight")
        this.leiaSDK = sdk
        sdk.enableBacklight(!hidden3D && !flat2D)
        setCenterView(context.centerViewX100 / 100f)   // seam placement (see setCenterView)
        applyInterlaceMode()                           // View Peeling etc., if the user selected one
        // Log the panel config for reference. NOTE: we deliberately do NOT drive the atlas tile
        // count from numViews: in Stereo Sliding mode the hardware views steer a 2-view pair
        // rather than carrying 8 perspectives (see Prefs.leiaNumViews).
        try {
            val cfg = sdk.config
            Log.i(TAG, "Leia Config: numViews=${cfg?.numViews?.x}x${cfg?.numViews?.y}, " +
                "panel=${cfg?.panelResolution?.x}x${cfg?.panelResolution?.y}, " +
                "viewRes=${cfg?.viewResolution?.x}x${cfg?.viewResolution?.y}, " +
                "atlasTiles=${context.leiaNumViews}")
        } catch (t: Throwable) { Log.w(TAG, "config read failed: ${t.message}") }
        // Note: Config.act_beta writes are accepted but ignored by the interlacer (tested
        // 2026-07-27, even across full SDK reinit) — ghost reduction is instead done by
        // crosstalk pre-compensation in our own DIBR shader (StereoRenderer.ghost).
    }
    override fun onFaceTrackingStarted(sdk: LeiaSDK) { Log.i(TAG, "face tracking started") }
    override fun onFaceTrackingStopped(sdk: LeiaSDK) { Log.i(TAG, "face tracking stopped") }
    override fun onFaceTrackingFatalError(sdk: LeiaSDK) { Log.e(TAG, "face tracking fatal error") }

    companion object {
        private const val TAG = "OverlayController"
        // Windows of the most recent controller instance. A rotation rebuild creates a new
        // instance; if the old one's teardown failed mid-rotation, the new instance sweeps
        // these before adding its own (prevents stacked/doubled UI).
        @Volatile private var lastControlsWin: View? = null
        @Volatile private var lastInputWin: View? = null
        @Volatile private var lastMainView: View? = null
        @Volatile private var lastCatcherWin: View? = null
    }
}
