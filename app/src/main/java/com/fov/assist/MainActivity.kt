package com.fov.assist

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
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
    private val REQ_MEDIA_PROJ = 300

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

        root.addView(TextView(this).apply {
            text = "⚡ FOV Assist AIM"
            textSize = 26f
            setTextColor(Color.parseColor("#6CCFFF"))
            setPadding(0, 0, 0, 10)
        })

        val targetStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FFCC66"))
            setPadding(0, 0, 0, 20)
        }
        root.addView(targetStatus)

        fun refreshTarget() {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_TARGET_NAME, null)
            targetStatus.text = if (name != null) "🎯 App: $name" else "⚠ Chưa chọn app"
        }
        refreshTarget()

        root.addView(TextView(this).apply {
            text = "1. Cấp Overlay\n" +
                   "2. Cấp Shizuku\n" +
                   "3. Chọn app đích\n" +
                   "4. Cấp quyền Screen Capture\n" +
                   "5. Bật Overlay + Vào game"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 30)
        })

        root.addView(btn("1. Overlay") { requestOverlay() })
        root.addView(btn("2. Shizuku") { requestShizuku() })
        root.addView(btn("3. Chọn app đích") { pickApp { refreshTarget() } })
        root.addView(btn("4. Cấp quyền Screen Capture") { requestMediaProjection() })
        root.addView(btn("5. Bật Overlay + Vào game") { startOverlayAndLaunch() })
        root.addView(btn("Tắt Overlay") { stopOverlay() })

        Shizuku.addRequestPermissionResultListener { req, grant ->
            if (req == REQ_SHIZUKU) {
                Toast.makeText(this,
                    if (grant == PackageManager.PERMISSION_GRANTED) "Shizuku OK" else "Từ chối",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun btn(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 8, 0, 8)
        layoutParams = lp
        gravity = Gravity.CENTER
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Đã có Overlay", Toast.LENGTH_SHORT).show()
                return
            }
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")), REQ_OVERLAY)
        }
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.isPreV11()) { Toast.makeText(this, "Shizuku cũ", Toast.LENGTH_SHORT).show(); return }
            if (!Shizuku.pingBinder()) { Toast.makeText(this, "Mở Shizuku trước", Toast.LENGTH_LONG).show(); return }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Đã có Shizuku", Toast.LENGTH_SHORT).show(); return
            }
            Shizuku.requestPermission(REQ_SHIZUKU)
        } catch (e: Throwable) {
            Toast.makeText(this, "Shizuku: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_MEDIA_PROJ && res == RESULT_OK && data != null) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            prefs.edit().putInt("proj_result", res).apply()
            // Không lưu intent trực tiếp (Parcelable) — chỉ lưu khi bật
            Toast.makeText(this, "Đã cấp quyền Screen Capture", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickApp(onPicked: () -> Unit) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (list.isEmpty()) { Toast.makeText(this, "Không có app", Toast.LENGTH_SHORT).show(); return }
        val names = list.map { "${it.loadLabel(pm)}  (${it.activityInfo.packageName})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn app đích")
            .setItems(names) { _, which ->
                val pkg = list[which].activityInfo.packageName
                val name = list[which].loadLabel(pm).toString()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_TARGET_PKG, pkg)
                    .putString(KEY_TARGET_NAME, name)
                    .apply()
                Toast.makeText(this, "Chọn: $name", Toast.LENGTH_SHORT).show()
                onPicked()
            }.setNegativeButton("Hủy", null).show()
    }

    private fun startOverlayAndLaunch() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pkg = prefs.getString(KEY_TARGET_PKG, null)
        if (pkg == null) { Toast.makeText(this, "Chưa chọn app", Toast.LENGTH_SHORT).show(); return }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Chưa cấp Overlay", Toast.LENGTH_SHORT).show(); return
        }
        // Yêu cầu MediaProjection mỗi lần bật — không cache được
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
        pendingLaunchPkg = pkg
    }

    private var pendingLaunchPkg: String? = null

    override fun onActivityResult2(req: Int, res: Int, data: Intent?) {} // dummy

    private fun launchNow(pkg: String, projData: Intent) {
        val svc = Intent(this, OverlayService::class.java).apply {
            putExtra("proj_code", projData.getIntExtra("code", 0))
            putExtra("proj_intent", projData)
        }
        startForegroundService(svc)
        try {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                moveTaskToBack(true)
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Lỗi mở: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Đã tắt", Toast.LENGTH_SHORT).show()
    }
}
