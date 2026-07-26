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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.lume3d.everywhere.Capture3DService
import com.lume3d.everywhere.convergence
import com.lume3d.everywhere.depthStrength
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
            if (touchable && onTouch != null) {
                @Suppress("ClickableViewAccessibility")
                v.setOnTouchListener { vw, ev -> onTouch.invoke(ev, vw.width, vw.height); true }
            }
            view = v
            asset = a
            addOverlay(v)
        } catch (t: Throwable) {
            Log.e(TAG, "Interlaced view setup failed: ${t.message}", t)
        }
    }

    private fun addOverlay(v: View) {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // When forwarding touch ourselves the overlay must be touchable; otherwise let
        // touches pass through to whatever is beneath it.
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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
        v.visibility = View.GONE
        wm.addView(v, lp)
        rootLp = lp
        added = true
        lowerBelowOtherOverlays(v)
        addControls()
    }

    private var rootLp: WindowManager.LayoutParams? = null
    private var fullHeightPx = 0
    private var kbMode = false

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

            val hideBtn = mkPanelBtn("👁  Hide Overlay")
            hideBtn.setOnClickListener {
                hideOverlayActive = !hideOverlayActive
                Capture3DService.active?.setOverlayVisible(!hideOverlayActive)
                hideBtn.text = if (hideOverlayActive) "👁  Show Overlay" else "👁  Hide Overlay"
                hideBtn.setBackgroundColor(
                    if (hideOverlayActive) 0xFF442200.toInt() else 0xFF2A2A2A.toInt())
            }

            val divider1 = View(context).apply { setBackgroundColor(0xFF333333.toInt()) }

            val depthLabel = android.widget.TextView(context).apply { setTextColor(0xFFD8DCE6.toInt()); textSize = 13f }
            val depthSeek = SeekBar(context).apply { max = 100 }
            val convLabel = android.widget.TextView(context).apply {
                setTextColor(0xFFD8DCE6.toInt()); textSize = 13f
                val mt = dp(8); setPadding(0, mt, 0, 0)
            }
            val convSeek = SeekBar(context).apply { max = 100 }

            fun syncLabels() {
                depthLabel.text = "Depth (pop-out): ${context.depthStrength}%"
                convLabel.text = "Convergence: ${context.convergence}%"
            }
            depthSeek.progress = context.depthStrength
            convSeek.progress = context.convergence
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

            val seekW = dp(280)
            val panelW = dp(280)
            settingsPanel.addView(hideBtn, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(divider1, LinearLayout.LayoutParams(panelW, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
            settingsPanel.addView(depthLabel)
            settingsPanel.addView(depthSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))
            settingsPanel.addView(convLabel)
            settingsPanel.addView(convSeek, LinearLayout.LayoutParams(seekW, LinearLayout.LayoutParams.WRAP_CONTENT))

            gear.setOnClickListener {
                settingsPanel.visibility = if (settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            kb.setOnClickListener { toggleKeyboard() }
            close.setOnClickListener { Capture3DService.stop(context) }

            bar.addView(grip)
            bar.addView(gear)
            bar.addView(kb)
            bar.addView(close)
            root.addView(bar)
            root.addView(settingsPanel)

            ctrlBtns = listOf(gear, kb, close)
            ctrlPanel = settingsPanel
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
        val targetLayer = -1_000_000   // far below anything WMS will ever assign
        var sc: SurfaceControl? = null
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                v.viewTreeObserver.addOnPreDrawListener {
                    val cur = sc ?: surfaceControlOf(view).also { sc = it }
                    if (cur != null && cur.isValid) {
                        try { SurfaceControl.Transaction().setLayer(cur, targetLayer).apply() }
                        catch (_: Throwable) {}
                    }
                    true
                }
                Log.i(TAG, "main overlay SF layer reapply hook installed (target=$targetLayer)")
            }
            override fun onViewDetachedFromWindow(view: View) {}
        })
    }

    private fun surfaceControlOf(view: View): SurfaceControl? = try {
        val ai = View::class.java.getDeclaredField("mAttachInfo")
            .apply { isAccessible = true }.get(view) ?: return null
        val vri = Class.forName("android.view.View\$AttachInfo")
            .getDeclaredField("mViewRootImpl").apply { isAccessible = true }.get(ai) ?: return null
        Class.forName("android.view.ViewRootImpl")
            .getDeclaredField("mSurfaceControl").apply { isAccessible = true }.get(vri) as? SurfaceControl
    } catch (_: Throwable) { null }

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
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (kotlin.math.abs(ev.rawX - downRawX) > slop ||
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

    private fun toggleKeyboard() {
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
        wm.addView(row, lp)
        inputWin = row
        inputEdit = edit
    }

    /** 2D single-view (portrait fallback) vs full lightfield 3D (landscape). */
    fun setSingleView(on: Boolean) {
        try { view?.setSingleViewMode(on); Log.i(TAG, "singleViewMode=$on") }
        catch (t: Throwable) { Log.e(TAG, "setSingleView failed: ${t.message}") }
    }

    fun show() {
        view?.let { if (it.visibility != View.VISIBLE) { it.visibility = View.VISIBLE; it.onResume(); Log.i(TAG, "overlay SHOW") } }
    }

    fun hide() {
        view?.let { if (it.visibility != View.GONE) { it.onPause(); it.visibility = View.GONE; Log.i(TAG, "overlay HIDE") } }
    }

    fun release() {
        try { view?.onPause() } catch (_: Throwable) {}
        try { exitBtn?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { tuneWin?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { inputWin?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { if (added) view?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { LeiaSDK.shutdownSDK() } catch (_: Throwable) {}
        view = null; asset = null; leiaSDK = null; added = false
    }

    // ---- LeiaSDK.Delegate ----
    override fun didInitialize(sdk: LeiaSDK) {
        Log.i(TAG, "LeiaSDK initialized; enabling 3D backlight")
        this.leiaSDK = sdk
        sdk.enableBacklight(true)
    }
    override fun onFaceTrackingStarted(sdk: LeiaSDK) { Log.i(TAG, "face tracking started") }
    override fun onFaceTrackingStopped(sdk: LeiaSDK) { Log.i(TAG, "face tracking stopped") }
    override fun onFaceTrackingFatalError(sdk: LeiaSDK) { Log.e(TAG, "face tracking fatal error") }

    companion object { private const val TAG = "OverlayController" }
}
