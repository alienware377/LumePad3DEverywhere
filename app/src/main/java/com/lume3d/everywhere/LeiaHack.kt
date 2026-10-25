package com.lume3d.everywhere

import android.util.Log

/**
 * Reaches CNSDK's native `ThreadedInterlacer::SetInterlaceMode`, which selects how the panel maps
 * its hardware views:
 *
 *   0 = View Peeling   : N genuinely different perspectives, face-driven per-pixel view selection
 *                        (PIXELCORRECTION=1). softwareViews becomes numViews.x (8 here).
 *   1 = View Sliding   : no head tracking (PIXELCORRECTION=0).
 *   2 = Stereo Sliding : the ctor default: only TWO source views, whose L/R boundary slides with
 *                        your face (PIXELCORRECTION=2). GetNumberOfSourceViews() returns a literal
 *                        2 in this mode, which is what pins softwareViews and creates the hard
 *                        L/R seam that shows up as the double-image ghost.
 *
 * There is no JNI export for it, so we call the C++ vtable slot directly:
 *   - `Interlacer.cxxPtr` (private long) points at a holder whose first qword is the
 *     ThreadedInterlacer* (every SDK JNI stub does `ldr x0,[x0]` on it).
 *   - SetInterlaceMode is vtable slot 7 (+0x38) in this libleiaSDK.so build (0.6.167).
 *
 * MUST be called on the interlacer's own thread (the GL thread), otherwise the native side
 * throws leia::Exception("Changing interlace mode from another thread...") which would abort the
 * process. Callers use InterlacedSurfaceView.queueEvent {}.
 *
 * The vtable index is specific to this .so build; [available] is false if the lib didn't load.
 */
object LeiaHack {
    private const val TAG = "LeiaHack"
    private const val VTABLE_SLOT_SET_INTERLACE_MODE = 7

    val available: Boolean = try {
        System.loadLibrary("leiahack"); true
    } catch (t: Throwable) { Log.w(TAG, "native lib unavailable: ${t.message}"); false }

    @JvmStatic private external fun nSetInterlaceMode(cxxPtr: Long, slot: Int, mode: Int): Int

    /** Pull the native interlacer pointer out of the Java object graph. */
    private fun cxxPtrOf(interlacedSurfaceView: Any): Long = try {
        val rf = interlacedSurfaceView.javaClass.getDeclaredField("mRenderer").apply { isAccessible = true }
        val renderer = rf.get(interlacedSurfaceView) ?: return 0L
        val inf = renderer.javaClass.getDeclaredField("mInterlacer").apply { isAccessible = true }
        val interlacer = inf.get(renderer) ?: return 0L
        interlacer.javaClass.getDeclaredField("cxxPtr").apply { isAccessible = true }.getLong(interlacer)
    } catch (t: Throwable) { Log.e(TAG, "cxxPtr lookup failed: ${t.message}"); 0L }

    /**
     * Apply [mode] to the interlacer owned by [interlacedSurfaceView]. Returns true if the call
     * was dispatched. Safe to call with an unavailable lib or a null pointer (no-ops).
     */
    fun setInterlaceMode(interlacedSurfaceView: Any, mode: Int): Boolean {
        if (!available) { Log.w(TAG, "skip: native lib not loaded"); return false }
        val ptr = cxxPtrOf(interlacedSurfaceView)
        if (ptr == 0L) { Log.w(TAG, "skip: cxxPtr == 0"); return false }
        val rc = try {
            nSetInterlaceMode(ptr, VTABLE_SLOT_SET_INTERLACE_MODE, mode)
        } catch (t: Throwable) { Log.e(TAG, "native call threw: ${t.message}"); -1 }
        Log.i(TAG, "setInterlaceMode(mode=$mode) ptr=0x${java.lang.Long.toHexString(ptr)} rc=$rc")
        return rc == 0
    }
}
