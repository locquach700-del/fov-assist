package com.fov.assist

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var menuView: View? = null
    private var fovView: FovView? = null

    @Volatile private var aimOn = false
    private var sensitivity = 1.0f
    private var aimTapDelayMs = 60L

    // MediaProjection
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var imgReader: ImageReader? = null
    private var screenW = 1080
    private var screenH = 1920
    private var screenDpi = 320

    // CV state
    private val frameLock = Object()
    private var latestBitmap: Bitmap? = null
    private var latestTarget: PointF? = null
    @Volatile private var detections = 0

    // Aim loop
    private val aimThreadRunning = AtomicBoolean(false)
    private var aimThread: Thread? = null

    companion object {
        const val CH_ID = "fov_service"
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotif())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        screenDpi = dm.densityDpi
        showFov()
        showMenu()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val data = if (Build.VERSION.SDK_INT >= 33)
                it.getParcelableExtra("proj_intent", Intent::class.java)
            else @Suppress("DEPRECATION") it.getParcelableExtra("proj_intent")
            val code = it.getIntExtra("proj_code", 0)
            if (data != null) startProjection(code, data)
        }
        return START_STICKY
    }

    private fun buildNotif(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "FOV", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("FOV Assist AIM")
            .setContentText("Đang chạy")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun startProjection(code: Int, data: Intent) {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            imgReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            imgReader?.setOnImageAvailableListener({ reader ->
                val img: Image? = try { reader.acquireLatestImage() } catch (_: Throwable) { null }
                img?.use {
                    processImage(it)
                }
            }, Handler(Looper.getMainLooper()))
            vDisplay = projection?.createVirtualDisplay(
                "fov_cap", screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imgReader?.surface, null, null
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "Capture lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun processImage(img: Image) {
        try {
            val plane = img.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenW
            val bmp = Bitmap.createBitmap(
                screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buf)

            // Detect
            val target = detectTarget(bmp)
            synchronized(frameLock) {
                latestBitmap?.recycle()
                latestBitmap = bmp
                latestTarget = target
            }
        } catch (_: Throwable) {}
    }

    // ===== CV DETECT =====
    // Quét vùng FOV quanh crosshair, tìm pixel da/đỏ
    private fun detectTarget(bmp: Bitmap): PointF? {
        val fovR = fovView?.radius ?: 200f
        val ccx = fovView?.cx ?: (screenW / 2f)
        val ccy = fovView?.cy ?: (screenH / 2f)
        val step = 8
        val left = (ccx - fovR).toInt().coerceAtLeast(0)
        val top = (ccy - fovR).toInt().coerceAtLeast(0)
        val right = (ccx + fovR).toInt().coerceAtMost(screenW - 1)
        val bottom = (ccy + fovR).toInt().coerceAtMost(screenH - 1)
        if (right <= left || bottom <= top) return null

        var sx = 0.0; var sy = 0.0; var n = 0
        detections = 0

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Filter 1: skin tone (đa dạng da người)
                val isSkin = r > 90 && r > g && g > b && r - g > 12 && r - b > 20
                // Filter 2: red nametag
                val isRed = r > 170 && g < 80 && b < 80
                if (isSkin || isRed) {
                    sx += x; sy += y; n++
                }
                x += step
            }
            y += step
        }

        detections = n
        if (n < 12) return null
        return PointF((sx / n).toFloat(), (sy / n).toFloat())
    }

    // ===== AIM LOOP =====
    private fun startAimLoop() {
        if (!ensureShizuku()) { Toast.makeText(this, "Chưa cấp Shizuku", Toast.LENGTH_SHORT).show(); return }
        aimOn = true
        aimThreadRunning.set(true)
        aimThread = Thread {
            while (aimThreadRunning.get()) {
                try {
                    var target: PointF? = null
                    synchronized(frameLock) { target = latestTarget }
                    if (target != null) {
                        val ccx = fovView?.cx ?: (screenW / 2f)
                        val ccy = fovView?.cy ?: (screenH / 2f)
                        val dx = target!!.x - ccx
                        val dy = target!!.y - ccy
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (dist > 15f) {
                            // Swipe camera từ giữa màn hình (vùng aim FF)
                            val startX = (screenW * 0.85f).toInt()
                            val startY = (screenH * 0.5f).toInt()
                            val endX = (startX + dx * sensitivity).toInt()
                            val endY = (startY + dy * sensitivity).toInt()
                            shizukuExec("input", "swipe",
                                startX.toString(), startY.toString(),
                                endX.toString(), endY.toString(),
                                "40")
                        }
                    }
                    Thread.sleep(aimTapDelayMs)
                } catch (_: InterruptedException) { break }
                catch (_: Throwable) {}
            }
        }.also { it.start() }
    }

    private fun stopAimLoop() {
        aimOn = false
        aimThreadRunning.set(false)
        aimThread?.interrupt()
        aimThread = null
    }

    private fun ensureShizuku(): Boolean = try {
        if (Shizuku.isPreV11()) false
        else if (!Shizuku.pingBinder()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun shizukuExec(vararg cmd: String): Boolean {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java)
            method.isAccessible = true
            val cmdArr: Array<String> = cmd.toList().toTypedArray()
            val proc = method.invoke(null, cmdArr, null, null) as? Process
            proc?.waitFor()
            return proc != null
        } catch (_: Throwable) {
            return try {
                val p = Runtime.getRuntime().exec(cmd.toList().toTypedArray())
                p.waitFor(); true
            } catch (_: Throwable) { false }
        }
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showFov() {
        val v = FovView(this)
        v.cx = screenW / 2f
        v.cy = screenH / 2f
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
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
            text = "⚡ AIM"
            setTextColor(0xFF6CCFFF.toInt())
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        root.addView(header)

        // FOV radius
        val sizeLbl = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 11f
            text = "Bán kính FOV: 200px"
        }
        root.addView(sizeLbl)
        root.addView(SeekBar(ctx).apply {
            max = 500; progress = 200
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    fovView?.radius = p.toFloat(); fovView?.invalidate()
                    sizeLbl.text = "Bán kính FOV: ${p}px"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // Crosshair X
        val cxLbl = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 11f
            text = "Crosshair X: ${screenW/2}"
        }
        root.addView(cxLbl)
        root.addView(SeekBar(ctx).apply {
            max = screenW; progress = screenW/2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    fovView?.cx = p.toFloat(); fovView?.invalidate()
                    cxLbl.text = "Crosshair X: $p"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // Crosshair Y
        val cyLbl = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 11f
            text = "Crosshair Y: ${screenH/2}"
        }
        root.addView(cyLbl)
        root.addView(SeekBar(ctx).apply {
            max = screenH; progress = screenH/2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    fovView?.cy = p.toFloat(); fovView?.invalidate()
                    cyLbl.text = "Crosshair Y: $p"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // Sensitivity
        val sensLbl = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 11f
            text = "Sensitivity: 1.0x"
        }
        root.addView(sensLbl)
        root.addView(SeekBar(ctx).apply {
            max = 30; progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    sensitivity = p / 10f
                    sensLbl.text = "Sensitivity: %.1fx".format(sensitivity)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // Aim delay
        val delayLbl = TextView(ctx).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 11f
            text = "Aim delay: 60ms"
        }
        root.addView(delayLbl)
        root.addView(SeekBar(ctx).apply {
            max = 200; progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                    aimTapDelayMs = p.toLong().coerceAtLeast(30)
                    delayLbl.text = "Aim delay: ${aimTapDelayMs}ms"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        val aimBtn = Button(ctx).apply {
            text = "🎯 BẬT AIM"
            textSize = 14f
            setBackgroundColor(0xFF7C2A4A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (aimOn) {
                    stopAimLoop()
                    text = "🎯 BẬT AIM"
                    setBackgroundColor(0xFF7C2A4A.toInt())
                } else {
                    startAimLoop()
                    if (aimOn) {
                        text = "⏹ TẮT AIM (ON)"
                        setBackgroundColor(0xFF2A7C4A.toInt())
                    }
                }
            }
        }
        root.addView(aimBtn)

        val detLbl = TextView(ctx).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 10f
            text = "Detect: 0 px"
        }
        root.addView(detLbl)
        // Update detection count every 500ms
        val h = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                detLbl.text = "Detect: $detections px"
                h.postDelayed(this, 500)
            }
        }
        h.post(updater)

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
            PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 20; p.y = 200
        wm.addView(root, p)
        menuView = root

        var dx = 0; var dy = 0
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX.toInt() - p.x; dy = e.rawY.toInt() - p.y; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = e.rawX.toInt() - dx; p.y = e.rawY.toInt() - dy
                    wm.updateViewLayout(root, p); true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAimLoop()
        try { vDisplay?.release() } catch (_: Throwable) {}
        try { imgReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        synchronized(frameLock) { latestBitmap?.recycle(); latestBitmap = null }
        menuView?.let { runCatching { wm.removeView(it) } }
        fovView?.let { runCatching { wm.removeView(it) } }
    }
}

class FovView(ctx: Context) : View(ctx) {
    var radius = 200f
    var cx = 0f
    var cy = 0f
    var visible = true
    var crosshair = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF00FF88.toInt()
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFFF4488.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return
        if (cx == 0f) { cx = width / 2f; cy = height / 2f }
        canvas.drawCircle(cx, cy, radius, paint)
        if (crosshair) {
            canvas.drawLine(cx - 18f, cy, cx + 18f, cy, cross)
            canvas.drawLine(cx, cy - 18f, cx, cy + 18f, cross)
        }
    }
}
