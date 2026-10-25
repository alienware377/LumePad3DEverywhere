package com.lume3d.everywhere.privileged

import android.content.Intent
import android.os.Binder
import android.os.Parcel
import android.util.Log

/**
 * Android's activity-start watcher (android.app.IActivityController), the same hook `am monitor`
 * uses. Every activity start on the device asks it first, which lets us catch the home-screen icon
 * of the app shown in 3D BEFORE Android pulls the app off our virtual display. That move changes
 * the app's screen density, so the app rebuilds itself and loses its place (Twitch jumped back to
 * its home page). Everything else is waved through untouched.
 *
 * The system waits on these answers (some while holding its window lock), so every reply is
 * instant: [shouldBlock] must only read fields and post work elsewhere. Crash and ANR callbacks
 * answer "handle it the normal way". If our process dies, Android drops the watcher by itself on
 * the next activity start.
 */
class LaunchGuard(
    private val shouldBlock: (intent: Intent, pkg: String) -> Boolean,
) : Binder() {

    private val cStarting = code("TRANSACTION_activityStarting")
    private val cResuming = code("TRANSACTION_activityResuming")
    private val cCrashed = code("TRANSACTION_appCrashed")
    private val cEarlyAnr = code("TRANSACTION_appEarlyNotResponding")
    private val cAnr = code("TRANSACTION_appNotResponding")
    private val cSystemAnr = code("TRANSACTION_systemNotResponding")

    /** False if this Android build's watcher interface doesn't match what we expect: don't install. */
    val usable = listOf(cStarting, cResuming, cCrashed, cEarlyAnr, cAnr, cSystemAnr).none { it == Int.MIN_VALUE }

    init { attachInterface(null, DESCRIPTOR) }

    private fun code(field: String): Int = try {
        Class.forName("$DESCRIPTOR\$Stub").getDeclaredField(field).apply { isAccessible = true }.getInt(null)
    } catch (t: Throwable) { Log.w(TAG, "no $field: ${t.message}"); Int.MIN_VALUE }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val answer: Int = when (code) {
            cStarting -> {
                var allow = true
                try {
                    data.enforceInterface(DESCRIPTOR)
                    val intent = if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null
                    val pkg = data.readString()
                    if (intent != null && pkg != null && shouldBlock(intent, pkg)) allow = false
                } catch (t: Throwable) { Log.w(TAG, "activityStarting: ${t.message}") }
                if (allow) 1 else 0
            }
            cResuming -> 1      // allow
            cCrashed -> 1       // true = normal crash handling (dialog etc.)
            cEarlyAnr -> 0      // 0 = normal
            cAnr -> 0           // 0 = normal ANR dialog
            cSystemAnr -> -1    // -1 = let the watchdog do its normal thing
            else -> return super.onTransact(code, data, reply, flags)
        }
        reply?.writeNoException()
        reply?.writeInt(answer)
        return true
    }

    companion object {
        private const val TAG = "LaunchGuard"
        const val DESCRIPTOR = "android.app.IActivityController"
    }
}
