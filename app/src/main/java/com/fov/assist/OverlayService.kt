package com.fov.assist

import android.app.*
import android.content.Context
import android.content.Intent
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
            setPadding(24, 16, 24, 16)
        }

        val header = TextView(ctx).apply {
            text = "⚡ FOV Assist"
            setTextColor(0xFF6CCFFF.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        root.addView(header)

        val sizeLabel = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 12f
            text = "Bán kính: 200px"
        }
        root.addView(sizeLabel)

        val seek = SeekBar(ctx).apply {
            max = 700
            progress = 200
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    fovView?.radius = prog.toFloat()
                    fovView?.invalidate()
                    sizeLabel.text = "Bán kính: ${prog}px"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(seek)

        val circleToggle = ToggleButton(ctx).apply {
            textOn = "FOV: ON"; textOff = "FOV: OFF"
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                fovView?.visible = checked
                fovView?.invalidate()
            }
        }
        root.addView(circleToggle)

        val crosshairToggle = ToggleButton(ctx).apply {
            textOn = "Cross: ON"; textOff = "Cross: OFF"
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                fovView?.crosshair = checked
                fovView?.invalidate()
            }
        }
        root.addView(crosshairToggle)

        val autoTapToggle = ToggleButton(ctx).apply {
            textOn = "Auto Tap: ON"; textOff = "Auto Tap: OFF"
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    val ok = ensureShizuku()
                    if (!ok) {
                        isChecked = false
                        Toast.makeText(ctx, "Chưa cấp quyền Shizuku", Toast.LENGTH_SHORT).show()
                    } else {
                        startAutoTap()
                    }
                } else {
                    stopAutoTap()
                }
            }
        }
        root.addView(autoTapToggle)

        val delayLabel = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 11f
            text = "Tap delay: 100ms"
        }
        root.addView(delayLabel)
        root.addView(SeekBar(ctx).apply {
            max = 500
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    tapDelayMs = p.toLong().coerceAtLeast(20)
                    delayLabel.text = "Tap delay: ${tapDelayMs}ms"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val closeBtn = Button(ctx).apply {
            text = "Ẩn menu"
            setOnClickListener { menuView?.visibility = View.GONE }
        }
        root.addView(closeBtn)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 40; p.y = 250
        wm.addView(root, p)
        menuView = root

        var dx = 0; var dy = 0
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX.toInt() - p.x
                    dy = e.rawY.toInt() - p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = e.rawX.toInt() - dx
                    p.y = e.rawY.toInt() - dy
                    wm.updateViewLayout(root, p)
                    true
                }
                else -> false
            }
        }
    }

    private fun ensureShizuku(): Boolean {
        return try {
            if (Shizuku.isPreV11()) return false
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun startAutoTap() {
        if (autoTapOn) return
        autoTapOn = true
        autoTapThread = Thread {
            val dm = resources.displayMetrics
            val cx = dm.widthPixels / 2
            val cy = dm.heightPixels / 2
            while (autoTapOn) {
                shizukuExec("input", "tap", cx.toString(), cy.toString())
                try { Thread.sleep(tapDelayMs) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    private fun stopAutoTap() {
        autoTapOn = false
        autoTapThread?.interrupt()
        autoTapThread = null
    }

    private fun shizukuExec(vararg cmd: String): Boolean {
        // Reflection vào Shizuku.newProcess (private)
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
            // Fallback Runtime.exec
            return try {
                val p = Runtime.getRuntime().exec(cmd.toList().toTypedArray())
                p.waitFor()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoTap()
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
