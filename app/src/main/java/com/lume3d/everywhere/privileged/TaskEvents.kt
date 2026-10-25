package com.lume3d.everywhere.privileged

import android.os.Binder
import android.os.Parcel
import android.util.Log

/**
 * The system's task-event callback (android.app.ITaskStackListener), written as a plain Binder so
 * we need no hidden-API stubs at compile time. Transaction codes are read at runtime from the
 * framework's own Stub class, so they always match this device's Android build. Only the
 * int-argument callbacks we need are decoded; every other event is ignored (they are all oneway).
 */
class TaskEvents(
    private val onDisplayChanged: (taskId: Int, displayId: Int) -> Unit,
    private val onRemoved: (taskId: Int) -> Unit,
    private val onMovedToFront: (taskId: Int, displayId: Int) -> Unit = { _, _ -> },
    private val onRestartAttempt: (taskId: Int, displayId: Int) -> Unit = { _, _ -> },
) : Binder() {

    private val codeDisplayChanged = code("TRANSACTION_onTaskDisplayChanged")
    private val codeRemoved = code("TRANSACTION_onTaskRemoved")
    private val codeMovedToFront = code("TRANSACTION_onTaskMovedToFront")
    // Fired when something "starts" a task that is already running and on top, e.g. tapping its
    // Recents card. On our virtual display that start changes nothing visible, so it is the only
    // sign the user wanted the app.
    private val codeRestartAttempt = code("TRANSACTION_onActivityRestartAttempt")

    /** Every event's name, so the log shows what Android actually sends us. */
    private val names: Map<Int, String> = try {
        Class.forName("$DESCRIPTOR\$Stub").declaredFields
            .filter { it.name.startsWith("TRANSACTION_") }
            .associate { f -> f.isAccessible = true; f.getInt(null) to f.name.removePrefix("TRANSACTION_") }
    } catch (_: Throwable) { emptyMap() }

    init { attachInterface(null, DESCRIPTOR) }

    private fun code(field: String): Int = try {
        Class.forName("$DESCRIPTOR\$Stub").getDeclaredField(field).apply { isAccessible = true }.getInt(null)
    } catch (t: Throwable) { Log.w(TAG, "no $field: ${t.message}"); Int.MIN_VALUE }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            when (code) {
                codeDisplayChanged -> {
                    data.enforceInterface(DESCRIPTOR)
                    val task = data.readInt(); val display = data.readInt()
                    onDisplayChanged(task, display); return true
                }
                codeRemoved -> {
                    data.enforceInterface(DESCRIPTOR)
                    onRemoved(data.readInt()); return true
                }
                codeMovedToFront, codeRestartAttempt -> {
                    // Argument is a RunningTaskInfo: a non-null marker, its deprecated `id`, then
                    // TaskInfo's fields starting userId, taskId, displayId (checked on the LP2:
                    // Recents tap on task 269 arrived as 1, 269, 0, 269, ...).
                    data.enforceInterface(DESCRIPTOR)
                    if (data.readInt() != 0) {
                        data.readInt()                       // RunningTaskInfo.id
                        data.readInt()                       // userId
                        val task = data.readInt(); val display = data.readInt()
                        Log.i(TAG, "${names[code]} task=$task display=$display")
                        if (code == codeMovedToFront) onMovedToFront(task, display)
                        else onRestartAttempt(task, display)
                    }
                    return true
                }
                else -> Log.d(TAG, "event ${names[code] ?: code}")
            }
        } catch (t: Throwable) { Log.w(TAG, "task event $code failed: ${t.message}"); return true }
        return super.onTransact(code, data, reply, flags) || true
    }

    companion object {
        private const val TAG = "TaskEvents"
        const val DESCRIPTOR = "android.app.ITaskStackListener"
    }
}
