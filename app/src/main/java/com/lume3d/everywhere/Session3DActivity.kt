package com.lume3d.everywhere

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * The 3D session's "body" on the real screen (display 0).
 *
 * The 3D picture itself lives in overlay windows, which Android's navigation ignores: before this
 * activity existed, the home launcher sat under the 3D, so the nav-bar BACK went to the launcher
 * (nothing happened), HOME left the 3D stuck on top, and Recents had nothing to show. This plain
 * black activity sits directly under the 3D while it is shown, so Android treats the session like
 * a normal app:
 *  - BACK reaches us and is forwarded to the captured app on the virtual display.
 *  - HOME, Recents and opening another app pause us, and the service hides the 3D.
 *  - Its Recents card ("Twitch 3D"), the notification and the app icon bring us back, and the 3D
 *    returns.
 * The service owns the actual visibility (a set of hide reasons); we only report our state.
 */
class Session3DActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setContentView(TextView(this).apply {
            text = "3D is running.\n\nPress Home to use other apps.\nOpen this screen from Recents to return to 3D."
            setTextColor(0xFF6A6A6A.toInt())
            gravity = Gravity.CENTER
            textSize = 15f
            setBackgroundColor(Color.BLACK)
        })
        applyTaskDescription()
    }

    /** Recents card shows the captured app's name and icon, e.g. "Twitch 3D". */
    private fun applyTaskDescription() {
        try {
            val label = applicationContext.targetLabel ?: "App"
            val icon = applicationContext.targetPackage?.let { pkg ->
                try { drawableToBitmap(packageManager.getApplicationIcon(pkg)) } catch (_: Throwable) { null }
            }
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription("$label 3D", icon))
        } catch (t: Throwable) { Log.w(TAG, "task description failed: ${t.message}") }
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable): Bitmap {
        val w = d.intrinsicWidth.coerceAtLeast(1); val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(c)
        return bmp
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Capture3DService.active?.onSessionForeground(true)
    }

    override fun onPause() {
        resumed = false
        Capture3DService.active?.onSessionForeground(false)
        super.onPause()
    }

    // Launcher3's Overview "live tile" can keep us resumed while Recents is open, so returning from
    // Recents may not call onResume. Top-resumed and window-focus changes catch that return.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) Capture3DService.active?.onSessionForeground(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Capture3DService.active?.onSessionFocused()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val svc = Capture3DService.active
        when {
            svc == null -> super.onBackPressed()
            svc.is3DShown() -> svc.forwardBack()   // back inside the captured app
            else -> moveTaskToBack(true)            // 3D hidden: leave like a normal app would
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) { current = null; resumed = false }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Session3D"
        @Volatile private var current: WeakReference<Session3DActivity>? = null
        @Volatile var resumed = false; private set

        /** Bring the session screen up on the real screen (never on the virtual display). */
        fun open(ctx: Context) {
            val i = Intent(ctx, Session3DActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            try { ctx.startActivity(i, opts.toBundle()) }
            catch (t: Throwable) { Log.e(TAG, "open failed: ${t.message}") }
        }

        /** Send the session screen behind whatever is under it (e.g. after the app backed out). */
        fun sendToBack() { current?.get()?.let { a -> a.runOnUiThread { a.moveTaskToBack(true) } } }

        /** End of the 3D session: remove the screen and its Recents card. */
        fun close() { current?.get()?.let { a -> a.runOnUiThread { a.finishAndRemoveTask() } } }

        fun pendingIntent(ctx: Context): android.app.PendingIntent = android.app.PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, Session3DActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
