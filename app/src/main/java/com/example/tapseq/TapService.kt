package com.example.tapseq

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class TapService : AccessibilityService() {

    companion object {
        @JvmStatic
        var instance: TapService? = null
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())

    private var bar: View? = null
    private var runBtn: TextView? = null
    private val markers = mutableListOf<TextView>()

    private var cfg = Config()
    private var running = false
    private var idx = 0
    private var round = 0
    private var targetPkg: String? = null

    private val orange = 0xCCFF5722.toInt()
    private val green = 0xFF2ECC40.toInt()

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopRun("ถูกขัดจังหวะ")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopRun(null)
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screen(): DisplayMetrics {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return m
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun overlayParams(w: Int, hgt: Int, flags: Int) = WindowManager.LayoutParams(
        w, hgt,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    // ---------- overlay: แถบควบคุม + จุดลากได้ ----------

    fun showOverlay() {
        if (bar != null) {
            refresh(); return
        }
        buildBar()
        refresh()
    }

    fun hideOverlay() {
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        runBtn = null
    }

    /** สร้างจุดใหม่ทั้งหมดจากค่าที่บันทึกไว้ */
    fun refresh() {
        if (bar == null || running) return
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        cfg = Store.load(this)
        cfg.steps.forEachIndexed { i, s -> addMarker(i, s) }
    }

    private fun buildBar() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xE6222222.toInt())
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply { x = dp(8); y = dp(120) }

        fun btn(t: String, action: () -> Unit) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { action() }
        }

        // ที่จับลากแถบ
        val handle = TextView(this).apply {
            text = "⠿"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 20f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            var dx = 0f
            var dy = 0f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (e.rawX - dx).toInt()
                        lp.y = (e.rawY - dy).toInt()
                        wm.updateViewLayout(row, lp)
                    }
                }
                true
            }
        }

        row.addView(handle)
        row.addView(btn("＋") { addStep() })
        row.addView(btn("－") { removeLastStep() })
        val run = btn("▶") { if (running) stopRun("หยุดแล้ว") else startRun() }
        runBtn = run
        row.addView(run)
        row.addView(btn("✕") { stopRun(null); hideOverlay() })

        wm.addView(row, lp)
        bar = row
    }

    private fun addStep() {
        if (running) return
        Store.edit(this) { it.steps.add(Step(0.5f, 0.5f, 30, "")) }
        refresh()
    }

    private fun removeLastStep() {
        if (running) return
        Store.edit(this) { if (it.steps.isNotEmpty()) it.steps.removeAt(it.steps.size - 1) }
        refresh()
    }

    private fun addMarker(i: Int, s: Step) {
        val size = dp(48)
        val m = screen()
        val tv = TextView(this).apply {
            text = "${i + 1}"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = circle(orange)
        }
        val lp = overlayParams(size, size, baseFlags).apply {
            x = (s.fx * m.widthPixels - size / 2f).toInt()
            y = (s.fy * m.heightPixels - size / 2f).toInt()
        }
        var dx = 0f
        var dy = 0f
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - dx).toInt()
                    lp.y = (e.rawY - dy).toInt()
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP -> {
                    val index = markers.indexOf(v as TextView)
                    val sm = screen()
                    val fx = ((lp.x + size / 2f) / sm.widthPixels).coerceIn(0f, 1f)
                    val fy = ((lp.y + size / 2f) / sm.heightPixels).coerceIn(0f, 1f)
                    Store.edit(this) { c -> c.steps.getOrNull(index)?.let { it.fx = fx; it.fy = fy } }
                }
            }
            true
        }
        wm.addView(tv, lp)
        markers.add(tv)
    }

    private fun setMarkersTouchable(touchable: Boolean) {
        markers.forEach {
            val lp = it.layoutParams as WindowManager.LayoutParams
            lp.flags = if (touchable) baseFlags
            else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(it, lp) }
        }
    }

    /** วงกระเพื่อมที่จุดกำลังกด */
    private fun flash(i: Int) {
        val v = markers.getOrNull(i) ?: return
        v.background = circle(green)
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f
        v.animate().scaleX(1.6f).scaleY(1.6f).setDuration(60).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).withEndAction {
                v.background = circle(orange)
            }.start()
        }.start()
    }

    // ---------- runner ----------

    // กดปุ่ม ▶ เอง: ใช้แอปที่อยู่หน้าจอตอนนี้เป็นเป้าหมาย
    private fun startRun() {
        if (running) return
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg == null || pkg == packageName) {
            toast("เปิดหน้าแอพเป้าหมายก่อนกด ▶"); return
        }
        beginRun(pkg)
    }

    // เรียกจาก MainActivity หลังเปิดลิงก์: เช็คทุก 50ms ว่าแอปเป้าหมายขึ้นหรือยัง → เริ่มแตะทันที
    fun launchRun(expectedPkg: String? = null) {
        if (running) return
        waitTarget(expectedPkg, SystemClock.uptimeMillis())
    }

    private fun waitTarget(expectedPkg: String?, t0: Long) {
        if (running) return
        val pkg = rootInActiveWindow?.packageName?.toString()
        val ready = pkg != null && pkg != packageName &&
            (expectedPkg == null || pkg == expectedPkg)
        if (ready) {
            val delay = Store.load(this).launchDelayMs
            if (delay <= 0) beginRun(pkg!!)
            else h.postDelayed({ if (!running) beginRun(pkg!!) }, delay)
            return
        }
        if (SystemClock.uptimeMillis() - t0 > 10000) {
            toast("แอพเป้าหมายไม่ขึ้นภายใน 10 วินาที"); return
        }
        h.postDelayed({ waitTarget(expectedPkg, t0) }, 50)
    }

    private fun beginRun(pkg: String) {
        if (running) return
        cfg = Store.load(this)
        if (cfg.steps.isEmpty()) { toast("ยังไม่มีขั้นตอน กด ＋ ก่อน"); return }
        targetPkg = pkg
        running = true
        idx = 0
        round = 0
        runBtn?.text = "■"
        setMarkersTouchable(false)
        h.postDelayed({ step() }, 250)
    }

    private fun stopRun(msg: String?) {
        val wasRunning = running
        running = false
        h.removeCallbacksAndMessages(null)
        runBtn?.text = "▶"
        setMarkersTouchable(true)
        if (wasRunning && msg != null) toast(msg)
    }

    private fun onTarget(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return true // กำลังเปลี่ยนหน้า
        return pkg == targetPkg
    }

    private fun hasText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(t).any { it.isVisibleToUser }
    }

    private fun step() {
        if (!running) return
        val s = cfg.steps[idx]
        if (!onTarget()) { stopRun("ออกจากแอพเป้าหมาย หยุดแล้ว"); return }
        if (s.wait.isBlank()) tap(s) else waitThenTap(s, SystemClock.uptimeMillis())
    }

    /** ตรวจทุก ~8ms ว่าข้อความขึ้นหรือยัง พอขึ้นกดทันที */
    private fun waitThenTap(s: Step, t0: Long) {
        if (!running) return
        if (hasText(s.wait)) { tap(s); return }
        if (SystemClock.uptimeMillis() - t0 > cfg.timeoutMs) {
            stopRun("หมดเวลารอ \"${s.wait}\" ขั้นที่ ${idx + 1}")
            return
        }
        h.postDelayed({ waitThenTap(s, t0) }, 8)
    }

    private fun tap(s: Step) {
        val m = screen()
        val x = s.fx * m.widthPixels
        val y = s.fy * m.heightPixels
        flash(idx)
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = advance(s.delay)
            override fun onCancelled(d: GestureDescription?) = stopRun("การกดถูกยกเลิก")
        }, null)
        if (!ok) stopRun("ส่งการกดไม่สำเร็จ")
    }

    private fun advance(delay: Long) {
        if (!running) return
        idx++
        if (idx >= cfg.steps.size) {
            idx = 0
            round++
            if (round >= cfg.rounds.coerceIn(1, 20)) {
                stopRun("เสร็จแล้ว"); return
            }
        }
        if (delay <= 0) h.post { step() } else h.postDelayed({ step() }, delay)
    }
}
