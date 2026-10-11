package com.quick.sasom

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class Step(val words: List<String>, val x: Int, val y: Int, val exact: Boolean, val isSize: Boolean = false)

class BuyService : AccessibilityService() {

    companion object {
        @Volatile var inst: BuyService? = null
        @Volatile var running = false
        @Volatile var steps: List<Step> = emptyList()
        @Volatile var email = ""
        @Volatile var stopBeforeLast = false
        @Volatile var lastError = ""
        @Volatile var soldWords: List<String> = emptyList()
        @Volatile var closeWords: List<String> = emptyList()
        @Volatile var refresh = false
        @Volatile var timeoutSec = 120
        @Volatile var retryDelayMs = 300L
        @Volatile var soldSeenAt = 0L
        var status: ((String) -> Unit)? = null
    }

    // งานหนักทั้งหมดทำบนเธรดแยก ไม่แตะเธรดหลัก (กันบริการค้าง/ANR แล้วระบบตีว่า "ทำงานผิดปกติ")
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private val pending = AtomicBoolean(false)

    @Volatile private var idx = 0
    private var startedAt = 0L
    private var stepAt = 0L
    private var lastMailAt = 0L
    private var lastSlowAt = 0L
    private var lastErrShown = ""
    private var rounds = 0
    private var sizeTries = 0
    private var sizeClickAt = 0L
    private var lastSizeIdx = -1
    private var disabledSince = 0L
    private var nextAllowed = 0L

    private val tickRun = Runnable {
        pending.set(false)
        safeTick()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            safeTick()
            h?.postDelayed(this, 15)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (thread == null) {
            val t = HandlerThread("sasom-work")
            t.start()
            thread = t
            h = Handler(t.looper)
        }
        inst = this
    }

    private fun cleanup() {
        running = false
        inst = null
        h?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        h = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // ข้อความ Toast/แจ้งเตือนสั้นๆ เช่น "สินค้าหมด" ไม่อยู่ในต้นไม้หน้าจอ ต้องดักจากอีเวนต์
        if (running && e != null && e.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val t = e.text.joinToString(" ")
            if (soldWords.any { it.isNotEmpty() && t.contains(it, true) }) soldSeenAt = SystemClock.uptimeMillis()
        }
        if (running && pending.compareAndSet(false, true)) h?.post(tickRun)
    }

    fun begin() {
        val hh = h ?: return
        hh.removeCallbacksAndMessages(null)
        pending.set(false)
        hh.post {
            idx = 0
            startedAt = SystemClock.uptimeMillis()
            stepAt = startedAt
            lastMailAt = 0L
            lastSlowAt = 0L
            lastError = ""
            lastErrShown = ""
            rounds = 0
            sizeTries = 0
            sizeClickAt = 0L
            lastSizeIdx = -1
            disabledSince = 0L
            nextAllowed = 0L
            soldSeenAt = 0L
            running = true
            say("เริ่มแล้ว รอปุ่มขั้นที่ 1")
            poll.run()
        }
    }

    fun finish(msg: String) {
        running = false
        h?.removeCallbacksAndMessages(null)
        pending.set(false)
        say(msg)
    }

    private fun say(s: String) { status?.invoke(s) }

    private fun secs(ms: Long) = String.format(Locale.US, "%.1f วิ", ms / 1000.0)

    private fun safeTick() {
        try {
            // ผ่านขั้นแล้วลองขั้นต่อไปทันทีในรอบเดียวกัน ไม่ต้องรอรอบ poll/อีเวนต์ถัดไป
            var n = 0
            while (n++ < 3 && running) {
                val before = idx
                work()
                if (idx == before) break
            }
        } catch (t: Throwable) {
            val msg = t.toString() + " @ " + (t.stackTrace.firstOrNull()?.toString() ?: "")
            lastError = msg
            if (msg != lastErrShown) {
                lastErrShown = msg
                say("ผิดพลาด: $msg")
            }
        }
    }

    private fun work() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > timeoutSec * 1000L) { finish("หมดเวลา $timeoutSec วิ หยุดแล้ว (ลองไป $rounds รอบ)"); return }
        val root = rootInActiveWindow ?: return

        // กรอกอีเมลไม่ต้องสแกนทุกรอบ เช็คทุก 250ms พอ
        if (email.isNotEmpty() && now - lastMailAt > 400) {
            lastMailAt = now
            fillEmail(root)
        }
        if (idx >= steps.size) { finish("ครบทุกขั้นแล้ว รวม ${secs(now - startedAt)}"); return }
        if (now < nextAllowed) return

        val s = steps[idx]
        val node = if (s.words.isNotEmpty()) find(root, s, now) else null
        if (node != null) {
            if (stopBeforeLast && idx == steps.size - 1) {
                finish("หยุดก่อนกดขั้นสุดท้าย (เจอปุ่มแล้ว ใช้ ${secs(now - startedAt)})")
                return
            }
            if (s.isSize) {
                doSize(node, now)
                return
            }
            if (clickNode(node)) {
                disabledSince = 0L
                advance(now)
            } else {
                // เจอปุ่มแต่กดไม่ได้ (ปิดอยู่) = มักแปลว่าไซส์ยังไม่ถูกเลือก ย้อนกลับไปเลือกไซส์ใหม่ทันที
                if (disabledSince == 0L) disabledSince = now
                if (now - disabledSince > 250 && lastSizeIdx >= 0 && lastSizeIdx < idx) {
                    idx = lastSizeIdx
                    sizeTries = 0
                    disabledSince = 0L
                    stepAt = now
                    say("ปุ่มยังกดไม่ได้ กลับไปเลือกไซส์ใหม่ (รอบ ${rounds + 1})")
                }
            }
            return
        }

        // ไม่เจอปุ่มของขั้นนี้
        if (idx > 0 && soldOutVisible(root, now)) {
            restart(root, "เจอข้อความสินค้าหมด", now)
            return
        }
        if (s.x > 0 && s.y > 0 && now - stepAt > 400) {
            tap(s.x, s.y)
            advance(now)
            return
        }
        // ค้างอยู่ขั้นเดิมนานเกิน 4 วิ (เช่น หน้าเด้งกลับ/ปุ่มหาย) เริ่มรอบใหม่
        if (idx > 0 && now - stepAt > 2500) {
            restart(root, "ค้างที่ขั้น ${idx + 1} นานเกินไป", now)
        }
    }

    private fun advance(now: Long) {
        if (steps[idx].isSize) lastSizeIdx = idx
        idx++
        sizeTries = 0
        say("ขั้น $idx/${steps.size} ผ่าน (ขั้นนี้ ${secs(now - stepAt)} / รวม ${secs(now - startedAt)} / รอบ ${rounds + 1})")
        stepAt = now
        if (idx >= steps.size) finish("ครบทุกขั้นแล้ว รวม ${secs(now - startedAt)} (รอบที่ ${rounds + 1})")
    }

    /** กดไซส์ แล้วเช็คว่าถูกเลือกจริงไหม ถ้าไม่ กดซ้ำทุก 100ms (สูงสุด 5 ครั้ง) */
    private fun doSize(node: AccessibilityNodeInfo, now: Long) {
        if (chosen(node)) { advance(now); return }
        if (now - sizeClickAt < 60) return
        if (sizeTries >= 5) {
            // แอพบางตัวไม่ส่งสถานะ "เลือกแล้ว" มาให้ ลองครบแล้วก็ไปต่อ
            say("กดไซส์ครบ 5 ครั้งแต่ไม่เห็นสถานะเลือก ไปขั้นต่อไป")
            advance(now)
            return
        }
        sizeTries++
        sizeClickAt = now
        clickNode(node)
        say("กดไซส์ครั้งที่ $sizeTries (รอยืนยันว่าถูกเลือก)")
    }

    private fun chosen(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 4) {
            if (n.isSelected || n.isChecked) return true
            n = n.parent
            hops++
        }
        return false
    }

    private fun soldOutVisible(root: AccessibilityNodeInfo, now: Long): Boolean {
        if (now - soldSeenAt < 3000) return true
        for (w in soldWords) {
            if (w.isEmpty()) continue
            val hits = root.findAccessibilityNodeInfosByText(w) ?: continue
            if (hits.any { it.isVisibleToUser }) return true
        }
        return false
    }

    /** เริ่มลำดับขั้นใหม่ตั้งแต่ขั้นแรก โดยไม่เปิดลิงก์ใหม่ */
    private fun restart(root: AccessibilityNodeInfo, why: String, now: Long) {
        rounds++
        soldSeenAt = 0L
        closeDialogs(root)
        if (refresh) swipeRefresh()
        idx = 0
        sizeTries = 0
        disabledSince = 0L
        stepAt = now
        nextAllowed = now + retryDelayMs
        say("$why → เริ่มรอบที่ ${rounds + 1} (รวม ${secs(now - startedAt)})")
    }

    private fun closeDialogs(root: AccessibilityNodeInfo) {
        for (w in closeWords) {
            if (w.isEmpty()) continue
            val hits = root.findAccessibilityNodeInfosByText(w) ?: continue
            val n = hits.firstOrNull { it.isVisibleToUser } ?: continue
            if (clickNode(n)) return
        }
    }

    /** ลากนิ้วลงจากกลางจอ = ดึงรีเฟรชหน้า */
    private fun swipeRefresh() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val p = Path()
        p.moveTo(x, dm.heightPixels * 0.30f)
        p.lineTo(x, dm.heightPixels * 0.75f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 250))
            .build()
        dispatchGesture(g, null, null)
    }

    /** ค้นแบบเร็วด้วยระบบก่อน (1 IPC) ถ้าไม่เจอค่อยเดินต้นไม้เองทุก ~300ms */
    private fun find(root: AccessibilityNodeInfo, s: Step, now: Long): AccessibilityNodeInfo? {
        var loose: AccessibilityNodeInfo? = null

        fun check(n: AccessibilityNodeInfo, word: String, strict: Boolean): AccessibilityNodeInfo? {
            if (!n.isVisibleToUser) return null
            for (raw in listOfNotNull(n.text?.toString(), n.contentDescription?.toString())) {
                val t = raw.trim()
                if (t.isEmpty()) continue
                if (matchExact(t, word)) return n
                if (!strict && loose == null && t.contains(word, true) && t.length <= word.length + 12) loose = n
            }
            return null
        }

        for (w in s.words) {
            val strict = s.exact || w.startsWith("=")
            val word = w.removePrefix("=")
            if (word.isEmpty()) continue
            val hits = root.findAccessibilityNodeInfosByText(word) ?: continue
            for (n in hits) check(n, word, strict)?.let { return it }
        }
        if (loose != null) return loose

        if (now - lastSlowAt < 120) return null
        lastSlowAt = now
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.addLast(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            for (w in s.words) {
                val strict = s.exact || w.startsWith("=")
                check(n, w.removePrefix("="), strict)?.let { return it }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
        }
        return loose
    }

    private fun matchExact(t: String, w: String): Boolean =
        t.equals(w, true) || t.startsWith("$w ", true) || t.startsWith("$w\n", true)

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable) {
                if (!n.isEnabled) return false
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            n = n.parent
            hops++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.isEmpty) { tap(r.centerX(), r.centerY()); return true }
        return false
    }

    private fun fillEmail(root: AccessibilityNodeInfo) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.addLast(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.isEditable && n.isVisibleToUser) {
                val hint = n.hintText?.toString()?.lowercase() ?: ""
                val id = n.viewIdResourceName?.lowercase() ?: ""
                val isEmail = (n.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        hint.contains("mail") || hint.contains("อีเมล") || id.contains("mail")
                val cur = n.text?.toString() ?: ""
                if (isEmail && cur != email && (cur.isBlank() || cur.lowercase() == hint)) {
                    val b = Bundle()
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, email)
                    n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
        }
    }

    private fun tap(x: Int, y: Int) {
        if (x < 0 || y < 0) return
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        p.lineTo(x.toFloat() + 1f, y.toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 10))
            .build()
        dispatchGesture(g, null, null)
    }
}
