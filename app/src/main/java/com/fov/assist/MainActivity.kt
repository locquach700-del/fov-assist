package com.fov.assist

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private val REQ_OVERLAY = 100
    private val REQ_SHIZUKU = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 120, 60, 60)
        }

        val status = TextView(this).apply {
            text = "FOV Assist\n\nBước 1: cấp quyền Overlay\nBước 2: cấp quyền Shizuku\nBước 3: bật Overlay Service"
            textSize = 14f
            setPadding(0, 0, 0, 60)
        }
        layout.addView(status)

        layout.addView(Button(this).apply {
            text = "1. Cấp quyền Overlay"
            setOnClickListener { requestOverlay() }
        })
        layout.addView(Button(this).apply {
            text = "2. Cấp quyền Shizuku"
            setOnClickListener { requestShizuku() }
        })
        layout.addView(Button(this).apply {
            text = "3. Bật Overlay"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Chưa có quyền Overlay", Toast.LENGTH_SHORT).show()
                } else {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                    Toast.makeText(this@MainActivity, "Đã bật — chuyển sang Free Fire", Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Tắt Overlay"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Đã tắt", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(layout)

        Shizuku.addRequestPermissionResultListener { req, grant ->
            if (req == REQ_SHIZUKU) {
                val msg = if (grant == PackageManager.PERMISSION_GRANTED) "Shizuku OK" else "Shizuku từ chối"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        }
    }

    private fun requestShizuku() {
        if (Shizuku.isPreV11()) {
            Toast.makeText(this, "Shizuku phiên bản cũ", Toast.LENGTH_SHORT).show()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Đã có quyền Shizuku", Toast.LENGTH_SHORT).show()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(this, "Mở Shizuku app, cấp quyền cho FOV Assist", Toast.LENGTH_LONG).show()
        }
        Shizuku.requestPermission(REQ_SHIZUKU)
    }
}
