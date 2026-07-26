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

/** Exclude the overlay from screen capture to break the feedback loop. */
var Context.secureOverlay: Boolean
    get() = prefs().getBoolean("secure_overlay", true)
    set(v) { prefs().edit().putBoolean("secure_overlay", v).apply() }

/** Swap left/right halves if the lightfield shows reversed/pseudoscopic depth. */
var Context.swapLeftRight: Boolean
    get() = prefs().getBoolean("swap_lr", false)
    set(v) { prefs().edit().putBoolean("swap_lr", v).apply() }
