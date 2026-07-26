package com.lume3d.everywhere

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lume3d.everywhere.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant == PackageManager.PERMISSION_GRANTED) startServiceNow()
        else status("Shizuku permission denied. Grant it to enable 3D.")
    }

    private val cameraPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnOverlayPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.btnUsagePerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        b.btnCameraPerm.setOnClickListener { cameraPerm.launch(android.Manifest.permission.CAMERA) }

        b.btnPickApp.setOnClickListener { pickApp() }

        b.depthSeek.progress = depthStrength
        b.convSeek.progress = convergence
        b.depthLabel.text = "Depth (pop-out): ${depthStrength}%"
        b.convLabel.text = "Convergence (screen plane): ${convergence}%"
        b.depthSeek.setOnSeekBarChangeListener(seek { p ->
            depthStrength = p; b.depthLabel.text = "Depth (pop-out): $p%"; Capture3DService.active?.refreshParams()
        })
        b.convSeek.setOnSeekBarChangeListener(seek { p ->
            convergence = p; b.convLabel.text = "Convergence (screen plane): $p%"; Capture3DService.active?.refreshParams()
        })

        b.swapSwitch.isChecked = swapLeftRight
        b.swapSwitch.setOnCheckedChangeListener { _, checked ->
            swapLeftRight = checked; Capture3DService.active?.refreshParams()
        }

        b.btnStart.setOnClickListener { onStart3D() }
        b.btnStop.setOnClickListener { Capture3DService.stop(this); setRunning(false); status("Stopped.") }

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun onStart3D() {
        val problems = buildList {
            if (!Settings.canDrawOverlays(this@MainActivity)) add("overlay permission")
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                add("camera permission")
            if (targetPackage == null) add("a target app")
        }
        if (problems.isNotEmpty()) {
            status("Still need: ${problems.joinToString(", ")}.")
            return
        }
        ensureShizukuThenStart()
    }

    /** Confirm Shizuku is up and granted, requesting the permission if needed. */
    private fun ensureShizukuThenStart() {
        val up = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!up) {
            status("Shizuku service isn't running. Open the Shizuku app and start it, then tap Start 3D.")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startServiceNow()
        } else {
            status("Requesting Shizuku permission…")
            Shizuku.requestPermission(SHIZUKU_REQ)
        }
    }

    private fun startServiceNow() {
        Capture3DService.start(this)
        setRunning(true)
        status("Running. Showing “${targetLabel ?: "your app"}” in 3D on the panel.")
    }

    private fun pickApp() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
        val labels = apps.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Make which app 3D?")
            .setItems(labels) { _, i ->
                targetPackage = apps[i].first
                targetLabel = apps[i].second
                b.btnPickApp.text = "Target: ${apps[i].second}"
                refreshStatus()
            }
            .show()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun setRunning(running: Boolean) {
        b.btnStart.isEnabled = !running
        b.btnStop.isEnabled = running
    }

    private fun refreshStatus() {
        targetLabel?.let { b.btnPickApp.text = "Target: $it" }
        setRunning(Capture3DService.active != null)
        val ok = Settings.canDrawOverlays(this) && hasUsageAccess() &&
            checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        status(
            "overlay:${tick(Settings.canDrawOverlays(this))}  " +
                "usage:${tick(hasUsageAccess())}  " +
                "camera:${tick(checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)}  " +
                "app:${if (targetPackage != null) "✓" else "—"}\n" +
                if (ok && targetPackage != null) "Ready. Tap Start 3D." else "Grant the items above."
        )
    }

    private fun tick(v: Boolean) = if (v) "✓" else "✗"
    private fun status(s: String) { b.statusText.text = s }

    private inline fun seek(crossinline onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    companion object { private const val SHIZUKU_REQ = 1001 }
}
