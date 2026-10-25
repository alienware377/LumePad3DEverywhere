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
import com.lume3d.everywhere.*
import com.lume3d.everywhere.LeiaHack
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
     * 3D pixels (below) and the control bar (above). FLAG_NOT_TOUCH_MODAL so touches in its
     * holes (see [setTouchHoles]) fall through to other apps' edge triggers below us.
     */
    private fun catcherLp(): WindowManager.LayoutParams {
        val bounds = wm.maximumWindowMetrics.bounds
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // NOT_FOCUSABLE + ALT_FOCUSABLE_IM makes this window the IME's LAYERING target without
            // ever taking focus: Android then parents the keyboard to its own layer above every app
            // overlay, so Gboard (bound to the captured app's field on the VD) draws above the 3D
            // instead of hidden behind it (DisplayContent.computeImeParent).
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            bounds.width(), bounds.height(), type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        return lp
    }

    private fun addTouchCatcher() {
        if (!touchable || onTouch == null) return
        val cb = onTouch
        removeWin(touchCatcher); touchCatcher = null
        val catcher = View(context)
        if (!catcherWanted) catcher.visibility = View.GONE   // rebuilt while 3D is hidden
        @Suppress("ClickableViewAccessibility")
        catcher.setOnTouchListener { _, ev ->
            // Map by the live 3D view size (NOT panel size: the view is shorter than the panel by
            // the system-inset strip, and mapping must use the actually-displayed height).
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
        Log.i(TAG, "touch catcher added (holes=${holes.size})")
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
        val alpha = if (hidden3D) 0f else 1f
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

            // ── Settings panel (⚙) ──────────────────────────────────────────────────────────
            // Show in 2D/3D on top, then collapsible sections that remember whether they're open.
            // Each top section has a Reset link: it forgets the stored values (back to defaults)
            // and re-syncs its controls through the `resyncs` each control registers.
            val settingsPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xE6101010.toInt())
                val p = dp(12); setPadding(p, p, p, p)
                visibility = View.GONE
            }
            val seekW = dp(280)
            val panelW = dp(280)
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            val accentColor = 0xFF9C7BFF.toInt()
            val accent = android.content.res.ColorStateList.valueOf(accentColor)
            fun resized() { (tuneWin as? DragLayout)?.onContentResized() }
            fun refresh() { Capture3DService.active?.refreshParams() }

            val hideBtn = mkPanelBtn("👁  Show in 2D")
            syncHideBtn = { flat ->
                hideOverlayActive = flat
                hideBtn.text = if (flat) "👁  Show in 3D" else "👁  Show in 2D"
                hideBtn.setBackgroundColor(if (flat) 0xFF442200.toInt() else 0xFF2A2A2A.toInt())
            }
            hideBtn.setOnClickListener {
                Capture3DService.active?.setFlat2D(!hideOverlayActive)   // the service syncs us back
            }
            settingsPanel.addView(hideBtn, LinearLayout.LayoutParams(panelW, wrap))

            fun mkLabel(size: Float = 13f, topPad: Int = 6) = TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = size
                setPadding(0, dp(topPad), 0, 0)
            }
            fun mkCheck(title: String, checked: Boolean) = android.widget.CheckBox(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                buttonTintList = accent
                isChecked = checked
            }

            /** A labelled slider. Only the user's own drags write the setting. Returns its re-sync. */
            fun addSlider(parent: LinearLayout, maxV: Int, get: () -> Int, set: (Int) -> Unit,
                          label: (Int) -> String, onChange: () -> Unit = ::refresh): () -> Unit {
                val lbl = mkLabel()
                val seek = SeekBar(context).apply { max = maxV }
                val sync = { seek.progress = get(); lbl.text = label(get()) }
                sync()
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        set(p); lbl.text = label(p); onChange()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                parent.addView(lbl)
                parent.addView(seek, LinearLayout.LayoutParams(seekW, wrap))
                return sync
            }

            /**
             * A collapsible section: tap its title to fold it. Top-level sections get a divider and,
             * with [resetKeys], a Reset link. Nested ones (a [parent] given) share the parent's
             * re-syncs so the parent's Reset covers them.
             */
            class Section(val body: LinearLayout, val resyncs: MutableList<() -> Unit>)
            fun addSection(key: String, title: String, openByDefault: Boolean,
                           resetKeys: List<String>? = null, parent: Section? = null,
                           headerExtra: View? = null): Section {
                val nested = parent != null
                val host = parent?.body ?: settingsPanel
                val body = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(if (nested) 12 else 4), 0, 0, dp(4))
                }
                val sec = Section(body, parent?.resyncs ?: mutableListOf())
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(if (nested) 6 else 8), 0, dp(2))
                }
                val titleView = TextView(context).apply {
                    setTextColor(if (nested) 0xFFD8DCE6.toInt() else 0xFFFFFFFF.toInt())
                    textSize = if (nested) 13f else 15f
                    if (!nested) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(6), 0, dp(6))
                }
                fun applyOpen(open: Boolean) {
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    titleView.text = (if (open) "▾  " else "▸  ") + title
                }
                titleView.setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    context.setSectionOpen(key, open); applyOpen(open); resized()
                }
                header.addView(titleView, LinearLayout.LayoutParams(0, wrap, 1f))
                headerExtra?.let { header.addView(it) }
                if (resetKeys != null) {
                    header.addView(TextView(context).apply {
                        text = "Reset"
                        setTextColor(accentColor); textSize = 12f
                        val p = dp(8); setPadding(p, p, 0, p)
                        setOnClickListener {
                            context.resetPrefs(resetKeys)
                            sec.resyncs.forEach { it() }
                            refresh()
                            Log.i(TAG, "reset section $key")
                        }
                    })
                }
                if (!nested) host.addView(View(context).apply { setBackgroundColor(0xFF333333.toInt()) },
                    LinearLayout.LayoutParams(panelW, dp(1)).apply { topMargin = dp(8) })
                host.addView(header, LinearLayout.LayoutParams(panelW, wrap))
                host.addView(body)
                applyOpen(context.sectionOpen(key, openByDefault))
                return sec
            }

            /** An on/off effect whose sliders only show while it's on. */
            fun addEffect(sec: Section, title: String, onGet: () -> Boolean, onSet: (Boolean) -> Unit,
                          sliders: List<Triple<String, () -> Int, (Int) -> Unit>>, maxV: Int = 100) {
                val body = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(32), 0, 0, 0)
                }
                val cb = mkCheck(title, onGet())
                for ((t, g, s) in sliders) sec.resyncs += addSlider(body, maxV, g, s, { "$t: $it%" })
                fun show() { body.visibility = if (onGet()) View.VISIBLE else View.GONE; resized() }
                cb.setOnCheckedChangeListener { _, on -> onSet(on); show(); refresh() }
                sec.resyncs += { cb.isChecked = onGet(); show() }
                sec.body.addView(cb)
                sec.body.addView(body)
                show()
            }

            // ── 3D ──
            val s3d = addSection("3d", "3D", openByDefault = true, resetKeys = KEYS_3D)
            s3d.resyncs += addSlider(s3d.body, 100, { context.depthStrength }, { context.depthStrength = it },
                { "Depth (pop-out): $it%" })
            s3d.resyncs += addSlider(s3d.body, 100, { context.convergence }, { context.convergence = it },
                { "Convergence: $it%" })
            s3d.resyncs += addSlider(s3d.body, 200, { context.actStrength }, { context.actStrength = it },
                { "Ghost fix: $it%" })
            // Center View: where the interlacer puts the L/R seam relative to your face. The
            // single most direct lever on the double-image (a mis-placed seam makes every eye
            // straddle it). Factory 2.64 on this panel; 2.24 measured better. 0.00..5.00.
            val cvSync = addSlider(s3d.body, 500, { context.centerViewX100 }, { context.centerViewX100 = it },
                { "Center View (seam position): %.2f".format(it / 100f) },
                onChange = { Capture3DService.active?.setCenterView(context.centerViewX100 / 100f) })
            s3d.resyncs += { cvSync(); Capture3DService.active?.setCenterView(context.centerViewX100 / 100f) }

            // ── Filters (parametric-EQ style: amount + the brightness range it touches) ──
            val masterSwitch = android.widget.Switch(context).apply {
                isChecked = context.filtersEnabled
                thumbTintList = accent
            }
            val sf = addSection("filters", "Filters", openByDefault = false, resetKeys = KEYS_FILTERS,
                headerExtra = masterSwitch)
            val filterList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val offNote = mkLabel(12f).apply { text = "Filters are off (switch above)." }
            fun showFilters() {
                val on = context.filtersEnabled
                filterList.visibility = if (on) View.VISIBLE else View.GONE
                offNote.visibility = if (on) View.GONE else View.VISIBLE
                resized()
            }
            masterSwitch.setOnCheckedChangeListener { _, on ->
                context.filtersEnabled = on; showFilters(); refresh()
            }
            sf.resyncs += { masterSwitch.isChecked = context.filtersEnabled; showFilters() }
            sf.body.addView(offNote)
            sf.body.addView(filterList)
            for (i in FILTER_NAMES.indices) {
                val head = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val fCheck = android.widget.CheckBox(context).apply { buttonTintList = accent; isChecked = context.filterOn(i) }
                val fLabel = TextView(context).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 13f }
                val rangeToggle = TextView(context).apply {
                    setTextColor(accentColor); textSize = 12f
                    val p = dp(8); setPadding(p, p, 0, p)
                }
                head.addView(fCheck)
                head.addView(fLabel, LinearLayout.LayoutParams(0, wrap, 1f))
                head.addView(rangeToggle)

                val body = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(32), 0, 0, 0)
                }
                fun syncF() {
                    val amt = context.filterAmt(i) - 100
                    fLabel.text = "${FILTER_NAMES[i]}: ${if (amt > 0) "+" else ""}$amt"
                }
                syncF()
                val amtSeek = SeekBar(context).apply { max = 200; progress = context.filterAmt(i) }
                amtSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        context.setFilterAmt(i, p); syncF(); refresh()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                body.addView(amtSeek, LinearLayout.LayoutParams(seekW, wrap))

                // Brightness range (which part of the picture the filter touches), folded away.
                val range = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val rangeLbl = mkLabel(12f, 2)
                fun syncRange() { rangeLbl.text = "Brightness range: ${context.filterLo(i)} to ${context.filterHi(i)}%" }
                syncRange()
                val loSeek = SeekBar(context).apply { max = 100; progress = context.filterLo(i) }
                val hiSeek = SeekBar(context).apply { max = 100; progress = context.filterHi(i) }
                loSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        context.setFilterLo(i, p)
                        if (p > context.filterHi(i)) { context.setFilterHi(i, p); hiSeek.progress = p }
                        syncRange(); refresh()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                hiSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        context.setFilterHi(i, p)
                        if (p < context.filterLo(i)) { context.setFilterLo(i, p); loSeek.progress = p }
                        syncRange(); refresh()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                val pair = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val half = LinearLayout.LayoutParams(0, wrap, 1f)
                pair.addView(loSeek, half)
                pair.addView(hiSeek, LinearLayout.LayoutParams(0, wrap, 1f))
                range.addView(rangeLbl)
                range.addView(pair, LinearLayout.LayoutParams(seekW, wrap))
                body.addView(range)

                val rangeKey = "frange_$i"
                fun showF() {
                    val on = context.filterOn(i)
                    body.visibility = if (on) View.VISIBLE else View.GONE
                    rangeToggle.visibility = if (on) View.VISIBLE else View.GONE
                    val open = context.sectionOpen(rangeKey, false)
                    range.visibility = if (open) View.VISIBLE else View.GONE
                    rangeToggle.text = if (open) "range ▾" else "range ▸"
                    resized()
                }
                rangeToggle.setOnClickListener {
                    context.setSectionOpen(rangeKey, !context.sectionOpen(rangeKey, false)); showF()
                }
                fCheck.setOnCheckedChangeListener { _, on -> context.setFilterOn(i, on); showF(); refresh() }
                sf.resyncs += {
                    fCheck.isChecked = context.filterOn(i)
                    amtSeek.progress = context.filterAmt(i); loSeek.progress = context.filterLo(i)
                    hiSeek.progress = context.filterHi(i)
                    syncF(); syncRange(); showF()
                }
                filterList.addView(head)
                filterList.addView(body)
                showF()
            }
            showFilters()

            // ── Ghost cleanup ──
            val sg = addSection("ghost", "Ghost cleanup", openByDefault = false, resetKeys = KEYS_GHOST)
            val s2 = addSection("ghost2", "Second pass", openByDefault = false, parent = sg)
            sg.resyncs += addSlider(s2.body, 200, { context.ghost2Strength }, { context.ghost2Strength = it },
                { "Darks: $it%" })
            sg.resyncs += addSlider(s2.body, 200, { context.ghost2HiStrength }, { context.ghost2HiStrength = it },
                { "Brights: $it%" })
            sg.resyncs += addSlider(s2.body, 100, { context.ghost2CrossPct }, { context.ghost2CrossPct = it },
                { "Split point (brightness): $it%" })
            sg.resyncs += addSlider(sg.body, 200, { context.ghostDesatStrength }, { context.ghostDesatStrength = it },
                { "Color ghost fix: $it%" })
            addEffect(sg, "Soft-knee depth (tames extreme pop-out ghosts)",
                { context.softKneeOn }, { context.softKneeOn = it },
                listOf(
                    Triple("Amount", { context.softKneeStrength }, { v: Int -> context.softKneeStrength = v }),
                    Triple("Knee start", { context.softKneeThreshPct }, { v: Int -> context.softKneeThreshPct = v })))
            addEffect(sg, "Edge ghost masking (softens ghost-prone edges)",
                { context.ghostMaskOn }, { context.ghostMaskOn = it },
                listOf(Triple("Amount", { context.ghostMaskStrength }, { v: Int -> context.ghostMaskStrength = v })))
            addEffect(sg, "Crisp depth edges (sharpens depth on colour edges)",
                { context.depthSharpOn }, { context.depthSharpOn = it },
                listOf(Triple("Amount", { context.depthSharpStrength }, { v: Int -> context.depthSharpStrength = v })))

            // ── Touch ──
            val st = addSection("touch", "Touch", openByDefault = false, resetKeys = KEYS_TOUCH)
            val holesCheck = mkCheck("Let edge triggers through (Pie Control etc.)", context.triggerHoles)
            holesCheck.setOnCheckedChangeListener { _, on -> Capture3DService.active?.setTriggerHoles(on) }
            st.resyncs += { holesCheck.isChecked = context.triggerHoles }
            st.body.addView(holesCheck)
            st.body.addView(mkLabel(11f, 0).apply {
                text = "Leaves tiny touch holes over other apps' edge strips. While 3D runs, it also " +
                    "relaxes Android's block on touches through overlays."
                setTextColor(0xFF9AA3B2.toInt())
                setPadding(dp(32), 0, 0, dp(4))
            }, LinearLayout.LayoutParams(panelW, wrap))

            // ── Experimental ──
            val sx = addSection("experimental", "Experimental", openByDefault = false, resetKeys = KEYS_EXPERIMENTAL)
            // View Peeling switches the native interlacer from Stereo Sliding (2 source views + hard
            // L/R seam) to true N-view and feeds it one atlas tile per hardware view. Needs a 3D
            // restart because the atlas size is fixed at creation.
            val vpBtn = mkPanelBtn("")
            fun syncVp() {
                val on = context.interlaceMode == 0
                vpBtn.text = if (on) "🧪  View Peeling: ON (restart 3D)" else "🧪  Try View Peeling"
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
            sx.resyncs += { syncVp() }
            sx.body.addView(vpBtn, LinearLayout.LayoutParams(panelW, wrap).apply { topMargin = dp(8) })

            // CNSDK debug panel: the only way to reach Interlace Mode by hand. While it's up,
            // touches drive the panel instead of the captured app.
            val guiBtn = mkPanelBtn("🛠  CNSDK Debug Panel")
            guiBtn.setOnClickListener {
                val on = !(Capture3DService.active?.isDebugGui() ?: false)
                Capture3DService.active?.setDebugGui(on)
                guiBtn.text = if (on) "🛠  Debug Panel ON (tap to exit)" else "🛠  CNSDK Debug Panel"
                guiBtn.setBackgroundColor(if (on) 0xFF224422.toInt() else 0xFF2A2A2A.toInt())
                if (on) ctrlPanel?.visibility = View.GONE   // get our menu out of the way
            }
            sx.body.addView(guiBtn, LinearLayout.LayoutParams(panelW, wrap).apply { topMargin = dp(6) })

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
            // Scrolls once the open sections get tall; otherwise only as tall as its content, so
            // no empty strip of the window sits over the app eating taps.
            val maxPanelH = dp(480)
            val scroll = object : android.widget.ScrollView(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxPanelH, View.MeasureSpec.AT_MOST))
                }
            }.apply {
                addView(settingsPanel)
                visibility = View.GONE
            }
            root.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
