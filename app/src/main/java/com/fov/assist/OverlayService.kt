package com.fov.assist

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var menuView: View? = null
    private var fovView: FovView? = null
    private var autoTapThread: Thread? = null
    @Volatile private var autoTapOn = false
    private var tapDelayMs = 100L

    // Aim tap thread (độc lập với auto tap)
    private var aimTapThread: Thread? = null
    @Volatile private var aimTapOn = false
    private var aimTapDelayMs = 50L

    companion object {
        const val CH_ID = "fov_service"
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotif())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showFov()
        showMenu()
    }

    private fun buildNotif(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "FOV", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("FOV Assist")
            .setContentText("Đang chạy")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun showFov() {
        val v = FovView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, p)
        fovView = v
    }

    private fun showMenu() {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6101018.toInt())
            setPadding(20, 12, 20, 12)
        }

        val header = TextView(ctx).apply {
            text = "⚡ FOV Assist"
            setTextColor(0xFF6CCFFF.toInt())
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        root.addView(header)

        // --- FOV controls ---
        val sizeLabel = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 11f
            text = "Bán kính: 200px"
        }
        root.addView(sizeLabel)
        root.addView(SeekBar(ctx).apply {
            max = 700; progress = 200
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    fovView?.radius = p.toFloat()
                    fovView?.invalidate()
                    sizeLabel.text = "Bán kính: ${p}px"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val toggleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val circleToggle = ToggleButton(ctx).apply {
            textOn = "FOV"; textOff = "FOV"
            isChecked = true
            setOnCheckedChangeListener { _, c ->
                fovView?.visible = c; fovView?.invalidate()
            }
        }
        val crossToggle = ToggleButton(ctx).apply {
            textOn = "X"; textOff = "X"
            isChecked = true
            setOnCheckedChangeListener { _, c ->
                fovView?.crosshair = c; fovView?.invalidate()
            }
        }
        toggleRow.addView(circleToggle)
        toggleRow.addView(crossToggle)
        root.addView(toggleRow)

        // --- Divider ---
        root.addView(View(ctx).apply {
            setBackgroundColor(0xFF333344.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setPadding(0, 10, 0, 10)
        })

        // --- Aim Section ---
        val aimLabel = TextView(ctx).apply {
            text = "🎯 AIM"
            setTextColor(0xFFFF6688.toInt())
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        }
        root.addView(aimLabel)

        val aimInfo = TextView(ctx).apply {
            text = "Giữ nút AIM để bắn liên tục vào tâm"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 10f
            setPadding(0, 0, 0, 6)
        }
        root.addView(aimInfo)

        val aimTapBtn = Button(ctx).apply {
            text = "🎯 AIM (giữ để bắn)"
            textSize = 14f
            setBackgroundColor(0xFF7C2A4A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!ensureShizuku()) {
                            Toast.makeText(ctx, "Chưa cấp Shizuku", Toast.LENGTH_SHORT).show()
                            false
                        } else {
                            startAimTap()
                            true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopAimTap()
                        true
                    }
                    else -> false
                }
            }
        }
        root.addView(aimTapBtn)

        val aimDelayLabel = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 10f
            text = "Aim tap delay: 50ms"
        }
        root.addView(aimDelayLabel)
        root.addView(SeekBar(ctx).apply {
            max = 200; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    aimTapDelayMs = p.toLong().coerceAtLeast(20)
                    aimDelayLabel.text = "Aim tap delay: ${aimTapDelayMs}ms"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // --- Divider ---
        root.addView(View(ctx).apply {
            setBackgroundColor(0xFF333344.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setPadding(0, 10, 0, 10)
        })

        // --- App Launcher ---
        val appLabel = TextView(ctx).apply {
            text = "🚀 GAME"
            setTextColor(0xFF66CCFF.toInt())
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        }
        root.addView(appLabel)

        val currentAppTxt = TextView(ctx).apply {
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 10f
            setPadding(0, 0, 0, 6)
            val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            text = "Đích: " + (prefs.getString(MainActivity.KEY_TARGET_NAME, "chưa chọn"))
        }
        root.addView(currentAppTxt)

        val launchBtn = Button(ctx).apply {
            text = "▶ Vào game"
            textSize = 13f
            setBackgroundColor(0xFF2A7C4A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                val pkg = prefs.getString(MainActivity.KEY_TARGET_PKG, null)
                if (pkg == null) {
                    Toast.makeText(ctx, "Chưa chọn app đích", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        val i = packageManager.getLaunchIntentForPackage(pkg)
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        } else {
                            Toast.makeText(ctx, "Không tìm thấy app", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Throwable) {
                        Toast.makeText(ctx, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        root.addView(launchBtn)

        // --- Hide menu ---
        root.addView(Button(ctx).apply {
            text = "Ẩn menu"
            textSize = 11f
            setOnClickListener { menuView?.visibility = View.GONE }
        })

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 30; p.y = 200
        wm.addView(root, p)
        menuView = root

        var dx = 0; var dy = 0
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX.toInt() - p.x; dy = e.rawY.toInt() - p.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = e.rawX.toInt() - dx; p.y = e.rawY.toInt() - dy
                    wm.updateViewLayout(root, p); true
                }
                else -> false
            }
        }
    }

    private fun ensureShizuku(): Boolean {
        return try {
            if (Shizuku.isPreV11()) return false
            if (!Shizuku.pingBinder()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
    }

    private fun startAimTap() {
        if (aimTapOn) return
        aimTapOn = true
        aimTapThread = Thread {
            val dm = resources.displayMetrics
            val cx = dm.widthPixels / 2
            val cy = dm.heightPixels / 2
            while (aimTapOn) {
                shizukuExec("input", "tap", cx.toString(), cy.toString())
                try { Thread.sleep(aimTapDelayMs) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    private fun stopAimTap() {
        aimTapOn = false
        aimTapThread?.interrupt()
        aimTapThread = null
    }

    private fun shizukuExec(vararg cmd: String): Boolean {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val cmdArr: Array<String> = cmd.toList().toTypedArray()
            val proc = method.invoke(null, cmdArr, null, null) as? Process
            proc?.waitFor()
            return proc != null
        } catch (_: Throwable) {
            return try {
                val p = Runtime.getRuntime().exec(cmd.toList().toTypedArray())
                p.waitFor()
                true
            } catch (_: Throwable) { false }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        aimTapOn = false; aimTapThread?.interrupt(); aimTapThread = null
        autoTapOn = false; autoTapThread?.interrupt(); autoTapThread = null
        menuView?.let { runCatching { wm.removeView(it) } }
        fovView?.let { runCatching { wm.removeView(it) } }
    }
}

class FovView(ctx: Context) : View(ctx) {
    var radius = 200f
    var visible = true
    var crosshair = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF00FF88.toInt()
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFF4488.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, paint)
        if (crosshair) {
            canvas.drawLine(cx - 18f, cy, cx + 18f, cy, cross)
            canvas.drawLine(cx, cy - 18f, cx, cy + 18f, cross)
        }
    }
}
