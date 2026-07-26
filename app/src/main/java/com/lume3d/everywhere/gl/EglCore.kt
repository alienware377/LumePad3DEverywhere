package com.lume3d.everywhere.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/** Minimal EGL14 context owning a single window surface (GLES 3). */
class EglCore(surface: Any) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val config: EGLConfig

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }

        fun chooseConfig(withAlpha: Boolean): EGLConfig? {
            val attrib = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, if (withAlpha) 8 else 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x0040, // ES3 bit
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            return if (EGL14.eglChooseConfig(display, attrib, 0, cfgs, 0, 1, n, 0) && n[0] > 0) cfgs[0] else null
        }
        config = chooseConfig(true) ?: chooseConfig(false)
            ?: error("eglChooseConfig failed (err=0x${Integer.toHexString(EGL14.eglGetError())})")

        val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val win: Any = when (surface) {
            is Surface -> surface
            is SurfaceTexture -> Surface(surface)
            else -> error("unsupported surface type: $surface")
        }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, win, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            val err = EGL14.eglGetError()
            Log.e("EglCore", "eglCreatewindowSurface failed err=0x${Integer.toHexString(err)} surface=$surface")
            error("eglCreateWindowSurface failed (err=0x${Integer.toHexString(err)})")
        }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    fun setPresentationTimeNs(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
