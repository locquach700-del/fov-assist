package com.fov.assist

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val REQ_OVERLAY = 100
    private val REQ_SHIZUKU = 200

    companion object {
        const val PREFS = "fov_prefs"
        const val KEY_TARGET_PKG = "target_pkg"
        const val KEY_TARGET_NAME = "target_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101018"))
            setPadding(60, 100, 60, 60)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "⚡ FOV Assist"
            textSize = 26f
            setTextColor(Color.parseColor("#6CCFFF"))
            setPadding(0, 0, 0, 10)
        }
        root.addView(title)

        val targetStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FFCC66"))
            setPadding(0, 0, 0, 20)
            id = 999
        }
        root.addView(targetStatus)

        fun refreshTarget() {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_TARGET_NAME, null)
            targetStatus.text = if (name != null) "🎯 App đích: $name" else "⚠ Chưa chọn app đích"
        }
        refreshTarget()

        val status = TextView(this).apply {
            text = "Hướng dẫn:\n" +
                   "1. Cấp quyền Overlay\n" +
                   "2. Cấp quyền Shizuku\n" +
                   "3. Chọn app đích (Free Fire)\n" +
                   "4. Bật Overlay → vào game"
            textSize = 14f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(0, 0, 0, 40)
        }
        root.addView(status)

        root.addView(makeButton("1. Cấp quyền Overlay") { requestOverlay() })
        root.addView(makeButton("2. Cấp quyền Shizuku") { requestShizuku() })
        root.addView(makeButton("3. Chọn app đích") { pickApp { refreshTarget() } })
        root.addView(makeButton("4. Bật Overlay + Vào game") { startOverlayAndLaunch() })
        root.addView(makeButton("Chỉ bật Overlay (không launch game)") { startOverlay() })
        root.addView(makeButton("Tắt Overlay") { stopOverlay() })

        Shizuku.addRequestPermissionResultListener { req, grant ->
            if (req == REQ_SHIZUKU) {
                val msg = if (grant == PackageManager.PERMISSION_GRANTED) "Shizuku OK" else "Shizuku từ chối"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 8, 0, 8)
            layoutParams = lp
            gravity = Gravity.CENTER
        }
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Đã có quyền Overlay", Toast.LENGTH_SHORT).show()
                return
            }
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        }
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku phiên bản cũ", Toast.LENGTH_SHORT).show()
                return
            }
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku chưa chạy — mở app Shizuku trước", Toast.LENGTH_LONG).show()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Đã có quyền Shizuku", Toast.LENGTH_SHORT).show()
                return
            }
            Shizuku.requestPermission(REQ_SHIZUKU)
        } catch (e: Throwable) {
            Toast.makeText(this, "Shizuku lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickApp(onPicked: () -> Unit) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        if (list.isEmpty()) {
            Toast.makeText(this, "Không có app nào", Toast.LENGTH_SHORT).show()
            return
        }

        val names = list.map { "${it.loadLabel(pm)}  (${it.activityInfo.packageName})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Chọn app đích")
            .setItems(names) { _, which ->
                val info = list[which]
                val pkg = info.activityInfo.packageName
                val name = info.loadLabel(pm).toString()
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TARGET_PKG, pkg)
                    .putString(KEY_TARGET_NAME, name)
                    .apply()
                Toast.makeText(this, "Đã chọn: $name", Toast.LENGTH_SHORT).show()
                onPicked()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Chưa có quyền Overlay — bấm bước 1", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Đã bật Overlay", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Lỗi bật: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlayAndLaunch() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_TARGET_PKG, null)
        if (pkg == null) {
            Toast.makeText(this, "Chưa chọn app đích — bấm bước 3", Toast.LENGTH_SHORT).show()
            return
        }
        startOverlay()
        launchTarget(pkg)
    }

    private fun launchTarget(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                Toast.makeText(this, "Không tìm thấy app $pkg", Toast.LENGTH_SHORT).show()
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            moveTaskToBack(true)
        } catch (e: Throwable) {
            Toast.makeText(this, "Lỗi mở app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Đã tắt", Toast.LENGTH_SHORT).show()
    }
}
