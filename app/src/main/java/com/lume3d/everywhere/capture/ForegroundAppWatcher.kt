package com.lume3d.everywhere.capture

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread

/**
 * Polls UsageStatsManager to learn the current foreground package, so the 3D overlay
 * is only shown while the chosen target app is in front (per-app v1 mode).
 *
 * Requires the user to grant "Usage access" in Settings (PACKAGE_USAGE_STATS).
 */
class ForegroundAppWatcher(
    context: Context,
    private val onChange: (pkg: String?) -> Unit,
) {
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val thread = HandlerThread("fg-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private var lastPkg: String? = null
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            // queryEvents only reports RESUMED transitions; if no app switch happened in
            // the lookback window it returns null. Treat null as "still on lastPkg" so the
            // overlay doesn't drop out while the user sits in the target app.
            val pkg = currentForeground()
            if (pkg != null && pkg != lastPkg) {
                lastPkg = pkg
                onChange(pkg)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun currentForeground(): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - LOOKBACK_MS, now)
        val e = android.app.usage.UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = e.packageName
            }
        }
        return pkg
    }

    companion object {
        private const val POLL_MS = 400L
        private const val LOOKBACK_MS = 10_000L
    }
}
