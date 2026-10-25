package com.lume3d.everywhere.privileged

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Privileged operations performed at the shell uid via Shizuku, with no root:
 *   - create a FLAG_TRUSTED virtual display whose output is our capture Surface,
 *   - launch an app onto that display,
 *   - inject touch events to that display.
 *
 * Implemented with reflection + ShizukuBinderWrapper so we need no compile-time
 * hidden-API stubs. The central trick: replace DisplayManagerGlobal's cached
 * IDisplayManager binder with a shell-wrapped one, so the ordinary
 * DisplayManager.createVirtualDisplay() transacts as shell and is allowed to set
 * FLAG_TRUSTED (which the app uid itself may not).
 */
object Privileged {
    private const val TAG = "Privileged"

    // android.hardware.display.DisplayManager virtual-display flags (some @hide).
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_TRUSTED = 1 shl 10

    private var bypassReady = false
    private var displayRouted = false
    private var shellInput: Any? = null   // IInputManager routed through shell
    private var shellWm: Any? = null      // IWindowManager routed through shell

    fun isShizukuReady(): Boolean =
        try { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }
        catch (t: Throwable) { false }

    private fun ensureBypass() {
        if (bypassReady) return
        // Allow reflective access to non-SDK (hidden) members for our process.
        HiddenApiBypass.addHiddenApiExemptions("")
        bypassReady = true
    }

    private fun getService(name: String): IBinder {
        val sm = Class.forName("android.os.ServiceManager")
        return sm.getMethod("getService", String::class.java).invoke(null, name) as IBinder
    }

    /** Route DisplayManagerGlobal's IDisplayManager through the shell uid (once). */
    private fun ensureDisplayRoutedThroughShell() {
        if (displayRouted) return
        ensureBypass()
        val real = getService("display")
        val wrapped = ShizukuBinderWrapper(real)
        val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        val iDm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)

        val dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = dmgCls.getMethod("getInstance").invoke(null)
        val mDm = dmgCls.getDeclaredField("mDm").apply { isAccessible = true }
        mDm.set(dmg, iDm)
        displayRouted = true
        Log.i(TAG, "DisplayManagerGlobal routed through shell")
    }

    /**
     * Create a trusted virtual display that renders into [surface]. Returns the
     * VirtualDisplay (display id via .display.displayId) or null on failure.
     */
    fun createTrustedVirtualDisplay(
        context: Context, name: String, w: Int, h: Int, dpi: Int, surface: Surface,
    ): VirtualDisplay? {
        return try {
            ensureDisplayRoutedThroughShell()
            // FLAG_TRUSTED is what gives the display a real input viewport (otherwise dumpsys
            // shows "touch NONE" and injected touches have nowhere to route -> taps do nothing).
            // The shell uid (2000) can normally set it. If a given build rejects it, fall back.
            // The transaction runs as shell uid (2000), so the packageName carried in the
            // request must be a shell-owned package or DisplayManagerService rejects it.
            val shellCtx = object : android.content.ContextWrapper(context.applicationContext) {
                override fun getPackageName(): String = "com.android.shell"
                override fun getOpPackageName(): String = "com.android.shell"
            }
            val dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgCls.getMethod("getInstance").invoke(null)
            val createM = dmgCls.declaredMethods.first {
                it.name == "createVirtualDisplay" && it.parameterTypes.size == 5
            }.apply { isAccessible = true }
            val cfgBuilderCls = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")

            fun attempt(flags: Int): VirtualDisplay? {
                val builder = cfgBuilderCls
                    .getConstructor(String::class.java, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .newInstance(name, w, h, dpi)
                cfgBuilderCls.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
                cfgBuilderCls.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
                val config = cfgBuilderCls.getMethod("build").invoke(builder)
                return createM.invoke(dmg, shellCtx, null, config, null, null) as VirtualDisplay?
            }

            val trustedFlags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED
            val vd = try {
                attempt(trustedFlags).also {
                    Log.i(TAG, "virtual display created (TRUSTED) id=${it?.display?.displayId} ${w}x$h")
                }
            } catch (t: Throwable) {
                val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
                Log.w(TAG, "FLAG_TRUSTED rejected (${c.javaClass.name}: ${c.message}); falling back to untrusted", c)
                attempt(FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY).also {
                    Log.i(TAG, "virtual display created (untrusted) id=${it?.display?.displayId} ${w}x$h")
                }
            }
            vd
        } catch (t: Throwable) {
            Log.e(TAG, "createTrustedVirtualDisplay failed: ${t.message}", t); null
        }
    }

    /**
     * Launch [pkg]'s launcher activity onto [displayId] via `am start` as shell.
     *
     * FLAG_OWN_CONTENT_ONLY untrusted virtual displays only render tasks that were
     * launched directly onto them; migrated tasks (via `am stack move-task`) attach
     * but produce a black surface. So on initial service start we always force-stop
     * + fresh-launch to guarantee a visible capture. The Hide/Show overlay toggle
     * uses [migrateToDisplay] instead, which preserves state for that scope.
     */
    fun launchOnDisplay(pkg: String, displayId: Int): Boolean {
        return try {
            val cmp = resolveLauncher(pkg) ?: pkg
            runShell(arrayOf("am", "force-stop", pkg))
            val cmd = arrayOf(
                "am", "start", "--display", displayId.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                "-n", cmp,
                "-f", "0x10200000" // NEW_TASK | RESET_TASK_IF_NEEDED
            )
            val rc = runShell(cmd)
            Log.i(TAG, "launchOnDisplay $cmp -> display $displayId rc=$rc")
            rc == 0
        } catch (t: Throwable) {
            Log.e(TAG, "launchOnDisplay failed: ${t.message}", t); false
        }
    }

    /**
     * Scan `am stack list` (or its newer `am task list`) output for a task owned by [pkg].
     * Returns the first matching task id, or -1 if none found.
     */
    fun findTaskIdForPkg(pkg: String): Int = try {
        val out = runShellCapture(arrayOf("am", "stack", "list")).ifBlank {
            runShellCapture(arrayOf("am", "task", "list"))
        }
        val re = Regex("""taskId=(\d+):[^\n]*${Regex.escape(pkg)}""")
        re.find(out)?.groupValues?.get(1)?.toInt() ?: -1
    } catch (_: Throwable) { -1 }

    /** Bring a package back to the default display (display 0). Migrates the existing
     *  task without killing the process; falls back to a fresh launch if no task exists. */
    fun moveBackToDefault(pkg: String): Boolean = migrateToDisplay(pkg, 0)

    /** Move (or, if absent, fresh-launch) [pkg]'s task onto [displayId] without killing
     *  the process. Used by the Hide/Show toggle to swap between 2D on display 0 and
     *  3D on the virtual display while preserving the activity's in-memory state. */
    fun migrateToDisplay(pkg: String, displayId: Int): Boolean {
        return try {
            val taskId = findTaskIdForPkg(pkg)
            // `am stack move-task` takes a ROOT TASK id and a root-task POSITION, not a display, so
            // it never did what this needed. `am display move-stack <rootTaskId> <displayId>` is the
            // real cross-display move (for a single-task app the root task id == the task id).
            if (taskId >= 0 && moveTaskToDisplay(taskId, displayId)) return true
            val cmp = resolveLauncher(pkg) ?: pkg
            runShell(arrayOf("am", "start", "--display", displayId.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                "-n", cmp, "-f", "0x10220000")) == 0
        } catch (t: Throwable) {
            Log.e(TAG, "migrateToDisplay failed: ${t.message}", t); false
        }
    }

    private fun resolveLauncher(pkg: String): String? = try {
        // `cmd package resolve-activity --brief -c <LAUNCHER> <pkg>` prints the component on the last line.
        val out = runShellCapture(arrayOf("cmd", "package", "resolve-activity", "--brief",
            "-c", "android.intent.category.LAUNCHER", pkg))
        out.trim().lineSequence().lastOrNull { it.contains("/") }?.trim()
    } catch (t: Throwable) { null }

    // ---- IME (soft keyboard) routing ----

    private fun ensureWm() {
        if (shellWm != null) return
        ensureBypass()
        val wrapped = ShizukuBinderWrapper(getService("window"))
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        shellWm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
    }

    /**
     * Make the soft keyboard show ON [displayId] (the virtual display the app lives on)
     * instead of falling back to the default display, where our opaque 3D overlay hides it.
     * DISPLAY_IME_POLICY_LOCAL = 0. Callable as shell (no signature perm needed), same as
     * scrcpy's secondary-display IME handling.
     */
    fun setImeLocal(displayId: Int): Boolean = try {
        ensureWm()
        val wm = shellWm!!
        wm.javaClass.getMethod(
            "setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(wm, displayId, 0 /* DISPLAY_IME_POLICY_LOCAL */)
        Log.i(TAG, "setDisplayImePolicy(LOCAL) on display $displayId")
        true
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
        Log.e(TAG, "setImeLocal failed: ${cause.javaClass.name}: ${cause.message}", cause); false
    }

    // ---- input injection ----

    private fun ensureInput() {
        if (shellInput != null) return
        ensureBypass()
        val wrapped = ShizukuBinderWrapper(getService("input"))
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        shellInput = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
    }

    /** Inject a MotionEvent onto [displayId]. mode 0 = ASYNC. */
    fun injectMotion(event: MotionEvent, displayId: Int): Boolean {
        return try {
            ensureInput()
            // event.setDisplayId(displayId) is hidden.
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
            val im = shellInput!!
            val m = im.javaClass.getMethod("injectInputEvent",
                Class.forName("android.view.InputEvent"), Int::class.javaPrimitiveType)
            m.invoke(im, event, 0) as Boolean
        } catch (t: Throwable) {
            Log.e(TAG, "injectMotion failed: ${t.message}"); false
        }
    }

    /**
     * Type [text] into the focused field on [displayId].
     *
     * `input text` mangles spaces (they get dropped/merged on many builds), so we split the
     * string on spaces, type each word with `input text`, and inject KEYCODE_SPACE (62)
     * between words.
     */
    fun injectText(text: CharSequence, displayId: Int): Boolean = try {
        val s = text.toString()
        var ok = true
        val parts = s.split(" ")
        for ((i, part) in parts.withIndex()) {
            if (i > 0) ok = injectKey(62 /* KEYCODE_SPACE */, displayId) && ok
            if (part.isNotEmpty()) {
                ok = (runShell(arrayOf("input", "-d", displayId.toString(), "text", part)) == 0) && ok
            }
        }
        ok
    } catch (t: Throwable) { Log.e(TAG, "injectText failed: ${t.message}"); false }

    /** Inject a key (e.g. KEYCODE_ENTER=66, KEYCODE_DEL=67) onto [displayId]. */
    fun injectKey(keyCode: Int, displayId: Int): Boolean = try {
        runShell(arrayOf("input", "-d", displayId.toString(), "keyevent", keyCode.toString())) == 0
    } catch (t: Throwable) { Log.e(TAG, "injectKey failed: ${t.message}"); false }

    /**
     * Disable Android 12's untrusted-touch blocking (user-consented). Our fullscreen 3D overlay
     * is opaque, so touches released to other overlay apps (Pie Control etc.) below us are
     * otherwise flagged obscured and silently dropped by those apps / the input dispatcher.
     *  - maximum_obscuring_opacity_for_touch = 1.0: an opaque overlay's opacity (1.0) is not
     *    strictly greater than the threshold, so it no longer marks touches untrusted.
     *  - block_untrusted_touches_mode = 0 (DeviceConfig): disables the block feature outright.
     * Both are shell-scoped and applied live via the InputManager settings/DeviceConfig observers.
     */
    /**
     * Is the system keyboard showing right now? The IME is bound to the captured app on the
     * virtual display, so our app gets no callbacks for it; `dumpsys input_method` reports
     * `mInputShown=true` while it is up (verified on the LP2).
     */
    /** Close the notification shade (after a notification button that acts on the 3D app). */
    fun collapseShade(): Boolean = try { runShell(arrayOf("cmd", "statusbar", "collapse")) == 0 }
        catch (t: Throwable) { Log.w(TAG, "collapseShade failed: ${t.message}"); false }

    /** Move a (root) task to another display, keeping the app's process and state. */
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean = try {
        val rc = runShell(arrayOf("am", "display", "move-stack", taskId.toString(), displayId.toString()))
        Log.i(TAG, "move-stack $taskId -> display $displayId rc=$rc")
        rc == 0
    } catch (t: Throwable) { Log.w(TAG, "moveTaskToDisplay failed: ${t.message}"); false }

    /**
     * Register [listener] (a [TaskEvents] binder) for the system's task events, as shell. The
     * framework calls our binder directly, the same way our virtual-display callback already works.
     * Needs MANAGE_ACTIVITY_TASKS, which shell holds.
     */
    fun registerTaskEvents(listener: TaskEvents, register: Boolean = true): Boolean = try {
        ensureBypass()
        val atmStub = Class.forName("android.app.IActivityTaskManager\$Stub")
        val atm = atmStub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(getService("activity_task")))
        val iface = Class.forName("android.app.ITaskStackListener")
        val name = if (register) "registerTaskStackListener" else "unregisterTaskStackListener"
        atm!!.javaClass.getMethod(name, iface).invoke(atm, binderAs(iface, listener, "TaskEvents"))
        Log.i(TAG, "$name ok")
        true
    } catch (t: Throwable) { Log.w(TAG, "task listener (register=$register) failed: ${t.cause ?: t}"); false }

    /** Install (or with null, remove) the system-wide activity-start watcher. See [LaunchGuard]. */
    fun setActivityController(guard: LaunchGuard?): Boolean = try {
        ensureBypass()
        val amStub = Class.forName("android.app.IActivityManager\$Stub")
        val am = amStub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(getService("activity")))
        val iface = Class.forName("android.app.IActivityController")
        am!!.javaClass.getMethod("setActivityController", iface, Boolean::class.javaPrimitiveType)
            .invoke(am, guard?.let { binderAs(iface, it, "LaunchGuard") }, false)
        Log.i(TAG, "activity controller ${if (guard != null) "set" else "cleared"}")
        true
    } catch (t: Throwable) { Log.w(TAG, "setActivityController failed: ${t.cause ?: t}"); false }

    /**
     * A dynamic proxy standing in for a hidden AIDL interface; the only thing the framework's
     * proxy asks it for is asBinder(), which hands over our real Binder.
     */
    private fun binderAs(iface: Class<*>, binder: IBinder, label: String): Any =
        java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, m, args ->
            when (m.name) {
                "asBinder" -> binder
                "hashCode" -> System.identityHashCode(binder)
                "equals" -> self === args?.getOrNull(0)
                "toString" -> label
                else -> null
            }
        }

    /** Does [displayId] currently have a resumed activity? (Used to notice the app backing out.) */
    fun hasResumedActivityOnDisplay(displayId: Int): Boolean = try {
        val out = runShellCapture(arrayOf("dumpsys", "activity", "activities"))
        val start = out.indexOf("Display #$displayId (")
        if (start < 0) false else {
            val next = out.indexOf("Display #", start + 1).let { if (it < 0) out.length else it }
            out.substring(start, next).contains("mResumedActivity:")
        }
    } catch (t: Throwable) { Log.w(TAG, "hasResumedActivityOnDisplay failed: ${t.message}"); true }

    /** Bring [pkg]'s existing task to the front of [displayId] (no force-stop: state is kept). */
    fun bringToFrontOnDisplay(pkg: String, displayId: Int): Boolean = try {
        val cmp = resolveLauncher(pkg) ?: pkg
        runShell(arrayOf("am", "start", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-n", cmp, "-f", "0x10200000")) == 0   // NEW_TASK | RESET_TASK_IF_NEEDED
    } catch (t: Throwable) { Log.w(TAG, "bringToFrontOnDisplay failed: ${t.message}"); false }

    fun isImeShown(): Boolean = try {
        runShellCapture(arrayOf("dumpsys", "input_method")).contains("mInputShown=true")
    } catch (t: Throwable) { Log.w(TAG, "isImeShown failed: ${t.message}"); false }

    fun allowTouchThroughOverlay(): Boolean = try {
        val a = runShell(arrayOf("settings", "put", "global",
            "maximum_obscuring_opacity_for_touch", "1.0"))
        val b = runShell(arrayOf("device_config", "put", "input_native_boot",
            "block_untrusted_touches_mode", "0"))
        Log.i(TAG, "allowTouchThroughOverlay rc=($a,$b)")
        a == 0
    } catch (t: Throwable) { Log.e(TAG, "allowTouchThroughOverlay failed: ${t.message}"); false }

    /** Restore Android's default untrusted-touch blocking (undo [allowTouchThroughOverlay]). */
    fun restoreTouchBlock(): Boolean = try {
        val a = runShell(arrayOf("settings", "delete", "global",
            "maximum_obscuring_opacity_for_touch"))
        val b = runShell(arrayOf("device_config", "delete", "input_native_boot",
            "block_untrusted_touches_mode"))
        Log.i(TAG, "restoreTouchBlock rc=($a,$b)")
        a == 0
    } catch (t: Throwable) { Log.e(TAG, "restoreTouchBlock failed: ${t.message}"); false }

    // ---- shell process helpers (via Shizuku) ----

    private fun runShell(cmd: Array<String>): Int {
        val p = newProcess(cmd)
        p.javaClass.getMethod("waitFor").invoke(p)
        return p.javaClass.getMethod("exitValue").invoke(p) as Int
    }

    private fun runShellCapture(cmd: Array<String>): String {
        val p = newProcess(cmd)
        val ins = p.javaClass.getMethod("getInputStream").invoke(p) as java.io.InputStream
        val text = ins.bufferedReader().readText()
        p.javaClass.getMethod("waitFor").invoke(p)
        return text
    }

    private fun newProcess(cmd: Array<String>): Any {
        // rikka.shizuku.Shizuku.newProcess(String[], String[], String) is @hide; call reflectively.
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        return m.invoke(null, cmd, null, null)
            ?: error("Shizuku.newProcess returned null")
    }
}
