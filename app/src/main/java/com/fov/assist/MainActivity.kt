package com.fov.assist

import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101018"))
            setPadding(60, 120, 60, 60)
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "⚡ FOV Assist"
            textSize = 24f
            setTextColor(Color.parseColor("#6CCFFF"))
            setPadding(0, 0, 0, 30)
        }
        root.addView(title)

        val status = TextView(this).apply {
            text = "Hướng dẫn:\n\n" +
                   "1. Cấp quyền Overlay (vẽ đè màn hình)\n" +
                   "2. Cấp quyền Shizuku (tap tự động)\n" +
                   "3. Bật Overlay → chuyển sang Free Fire\n\n" +
                   "Yêu cầu: đã cài Shizuku và pair qua Wireless Debugging."
            textSize = 14f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(0, 0, 0, 50)
        }
        root.addView(status)

        root.addView(makeButton("1. Cấp quyền Overlay") { requestOverlay() })
        root.addView(makeButton("2. Cấp quyền Shizuku") { requestShizuku() })
        root.addView(makeButton("3. Bật Overlay") { startOverlay() })
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
            lp.setMargins(0, 10, 0, 10)
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
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
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

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Chưa có quyền Overlay — bấm bước 1", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Đã bật — chuyển sang Free Fire", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } catch (e: Throwable) {
            Toast.makeText(this, "Lỗi bật: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Đã tắt", Toast.LENGTH_SHORT).show()
    }
}
