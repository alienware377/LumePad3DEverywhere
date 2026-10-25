package com.lume3d.everywhere

import android.content.Context

/** Keys + store name for user settings shared between UI and service. */
private const val PREFS_FILE = "lume3d"
private fun Context.prefs() = getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

// Top-level extension properties so they can be imported and used directly on any Context.

var Context.targetPackage: String?
    get() = prefs().getString("target_pkg", null)
    set(v) { prefs().edit().putString("target_pkg", v).apply() }

var Context.targetLabel: String?
    get() = prefs().getString("target_label", null)
    set(v) { prefs().edit().putString("target_label", v).apply() }

/** 0..100 slider; mapped to a parallax baseline in the shader. */
var Context.depthStrength: Int
    get() = prefs().getInt("depth_strength", 30)
    set(v) { prefs().edit().putInt("depth_strength", v).apply() }

/** 0..100 slider; which normalized depth sits on the screen plane (zero parallax). */
var Context.convergence: Int
    get() = prefs().getInt("convergence", 50)
    set(v) { prefs().edit().putInt("convergence", v).apply() }

/** 0..200 slider; % of factory ACT (anti-crosstalk) strength. 100 = factory. */
var Context.actStrength: Int
    get() = prefs().getInt("act_strength", 100)
    set(v) { prefs().edit().putInt("act_strength", v).apply() }

/**
 * Band-limited visual filters (index order matches StereoRenderer.filters):
 * 0 brightness, 1 contrast, 2 clarity, 3 texture, 4 saturation, 5 vibrance.
 * Amount: 0..200 (100 = neutral). Band lo/hi: 0..100 (% luminance the filter affects).
 */
val FILTER_KEYS = listOf("bright", "contrast", "clarity", "texture", "sat", "vib")
val FILTER_NAMES = listOf("Brightness", "Contrast", "Clarity", "Texture", "Saturation", "Vibrance")

/** 0..200 slider; post-filter ghost-fix second pass, DARKS band (no black pedestal). */
var Context.ghost2Strength: Int
    get() = prefs().getInt("ghost2_strength", 0)
    set(v) { prefs().edit().putInt("ghost2_strength", v).apply() }

/** 0..200 slider; second-pass BRIGHTS band. Defaults to the darks value so the split
 *  starts out behaving exactly like the old single slider. */
var Context.ghost2HiStrength: Int
    get() = prefs().getInt("ghost2_hi", prefs().getInt("ghost2_strength", 0))
    set(v) { prefs().edit().putInt("ghost2_hi", v).apply() }

/** 0..100 slider; luminance % where the second pass crosses from darks to brights band. */
var Context.ghost2CrossPct: Int
    get() = prefs().getInt("ghost2_cross", 50)
    set(v) { prefs().edit().putInt("ghost2_cross", v).apply() }

/** 0..200 slider; desaturates the ghost-fix subtraction term (0 = per-channel color
 *  subtraction, 200 = luminance-only). Kills complementary-color fringes on saturated content. */
var Context.ghostDesatStrength: Int
    get() = prefs().getInt("ghost_desat", 0)
    set(v) { prefs().edit().putInt("ghost_desat", v).apply() }

/**
 * Atlas tile count. MUST be 2 while the interlacer runs in Stereo Sliding mode (interlaceMode=2,
 * the boot default): there, the 8 hardware views are a STEERING mechanism, not 8 perspectives;
 * face tracking slides a 4-views-left / 4-views-right boundary so each eye's cone (~4 hw views)
 * sees ONE consistent image. Giving each hw view a distinct perspective (tested 2026-07-27 with a
 * patched interlace.pshader tiling by hardwareViewsX) makes each eye see ~4 different perspectives
 * at once => a multi-copy SMEAR, worse than the original single ghost. True N-view needs
 * interlaceMode=0 (View Peeling), which has no JNI setter. See the skill file.
 */
var Context.leiaNumViews: Int
    get() = prefs().getInt("leia_num_views", 2).coerceIn(2, 12)
    set(v) { prefs().edit().putInt("leia_num_views", v).apply() }

/**
 * Leia Config.centerViewNumber x100 (so 224 == 2.24). This is where the interlacer places the
 * left/right view boundary relative to your face; if it's off, each eye permanently straddles
 * the seam and picks up the opposite image: a persistent double-image no amount of shader-side
 * pre-compensation truly removes. Factory value on this panel is 2.64; 2.24 measured better.
 */
var Context.centerViewX100: Int
    get() = prefs().getInt("center_view_x100", 224)
    set(v) { prefs().edit().putInt("center_view_x100", v).apply() }

/** Interlace mode: 0 = View Peeling (true N-view), 1 = View Sliding, 2 = Stereo Sliding
 *  (the SDK default). Applied via a native vtable call; -1 = leave alone. */
var Context.interlaceMode: Int
    get() = prefs().getInt("interlace_mode", -1)
    set(v) { prefs().edit().putInt("interlace_mode", v).apply() }

/** Edge pass-through margin (px). Gestures starting within this frame of the screen edge
 *  fall through to overlay apps below us (Pie Control triggers, etc.). 0 = disabled. */
var Context.edgePassMargin: Int
    get() = prefs().getInt("edge_pass_margin", 0)
    set(v) { prefs().edit().putInt("edge_pass_margin", v).apply() }

/** Disable Android's untrusted-touch block (via Shizuku) so touches released by pass-through
 *  reach other overlay apps through our opaque overlay. Device-wide security setting. */
var Context.touchBlockDisabled: Boolean
    get() = prefs().getBoolean("touch_block_disabled", true)
    set(v) { prefs().edit().putBoolean("touch_block_disabled", v).apply() }

/** Alternative to the OS mod: dim the 3D overlay below the obscuring-opacity threshold while
 *  pass-through is active, so touches pass through as trusted without changing any OS setting. */
var Context.dimForPassThrough: Boolean
    get() = prefs().getBoolean("dim_for_passthrough", false)
    set(v) { prefs().edit().putBoolean("dim_for_passthrough", v).apply() }

/** Pass-through dim opacity, 10..79 (%). Must stay below 80 to clear the untrusted-touch
 *  obscuring threshold. Used for both edge and full pass-through when dimming is active. */
var Context.passThroughDimPct: Int
    get() = prefs().getInt("passthrough_dim_pct", 40)
    set(v) { prefs().edit().putInt("passthrough_dim_pct", v).apply() }

// ── Ghost/quality effects (all default off) ──
var Context.softKneeOn: Boolean
    get() = prefs().getBoolean("softknee_on", false); set(v) { prefs().edit().putBoolean("softknee_on", v).apply() }
var Context.softKneeStrength: Int
    get() = prefs().getInt("softknee_str", 50); set(v) { prefs().edit().putInt("softknee_str", v).apply() }
var Context.softKneeThreshPct: Int
    get() = prefs().getInt("softknee_thresh", 60); set(v) { prefs().edit().putInt("softknee_thresh", v).apply() }

var Context.ghostMaskOn: Boolean
    get() = prefs().getBoolean("ghostmask_on", false); set(v) { prefs().edit().putBoolean("ghostmask_on", v).apply() }
var Context.ghostMaskStrength: Int
    get() = prefs().getInt("ghostmask_str", 50); set(v) { prefs().edit().putInt("ghostmask_str", v).apply() }

var Context.depthSharpOn: Boolean
    get() = prefs().getBoolean("depthsharp_on", false); set(v) { prefs().edit().putBoolean("depthsharp_on", v).apply() }
var Context.depthSharpStrength: Int
    get() = prefs().getInt("depthsharp_str", 50); set(v) { prefs().edit().putInt("depthsharp_str", v).apply() }

/** Master switch for the whole filter stack. */
var Context.filtersEnabled: Boolean
    get() = prefs().getBoolean("f_master", true)
    set(v) { prefs().edit().putBoolean("f_master", v).apply() }

fun Context.filterOn(i: Int): Boolean = prefs().getBoolean("f_${FILTER_KEYS[i]}_on", true)
fun Context.setFilterOn(i: Int, v: Boolean) { prefs().edit().putBoolean("f_${FILTER_KEYS[i]}_on", v).apply() }

fun Context.filterAmt(i: Int): Int = prefs().getInt("f_${FILTER_KEYS[i]}_a", 100)
fun Context.setFilterAmt(i: Int, v: Int) { prefs().edit().putInt("f_${FILTER_KEYS[i]}_a", v).apply() }
fun Context.filterLo(i: Int): Int = prefs().getInt("f_${FILTER_KEYS[i]}_lo", 0)
fun Context.setFilterLo(i: Int, v: Int) { prefs().edit().putInt("f_${FILTER_KEYS[i]}_lo", v).apply() }
fun Context.filterHi(i: Int): Int = prefs().getInt("f_${FILTER_KEYS[i]}_hi", 100)
fun Context.setFilterHi(i: Int, v: Int) { prefs().edit().putInt("f_${FILTER_KEYS[i]}_hi", v).apply() }

/** Exclude the overlay from screen capture to break the feedback loop. */
var Context.secureOverlay: Boolean
    get() = prefs().getBoolean("secure_overlay", true)
    set(v) { prefs().edit().putBoolean("secure_overlay", v).apply() }

/** Swap left/right halves if the lightfield shows reversed/pseudoscopic depth. */
var Context.swapLeftRight: Boolean
    get() = prefs().getBoolean("swap_lr", false)
    set(v) { prefs().edit().putBoolean("swap_lr", v).apply() }
