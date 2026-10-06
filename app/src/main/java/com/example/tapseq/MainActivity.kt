package com.example.tapseq

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var roundsEt: EditText
    private lateinit var timeoutEt: EditText
    private lateinit var urlEt: EditText
    private lateinit var launchDelayEt: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply {
            text = "TapSeq – กดตามลำดับ"
            textSize = 24f
        })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)

        col.addView(button("1) เปิดการเข้าถึง (Accessibility) → TapSeq") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(label("รอบ (1–20)  /  รอข้อความสูงสุด (ms)"))
        val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        roundsEt = field("", 70, InputType.TYPE_CLASS_NUMBER) { s ->
            Store.edit(this) { it.rounds = (s.toIntOrNull() ?: 1).coerceIn(1, 20) }
        }
        timeoutEt = field("", 100, InputType.TYPE_CLASS_NUMBER) { s ->
            Store.edit(this) { it.timeoutMs = (s.toLongOrNull() ?: 3000L).coerceIn(500, 30000) }
        }
        settingsRow.addView(roundsEt)
        settingsRow.addView(timeoutEt)
        col.addView(settingsRow)

        // ---------- ลิงก์หน้าสินค้า ----------
        col.addView(label("ลิงก์หน้าสินค้า (Shopee / Lazada ฯลฯ)"))
        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlEt = field("", 0, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { s ->
            Store.edit(this) { it.url = s.trim() }
        }
        urlEt.hint = "วางลิงก์สินค้าที่นี่"
        urlEt.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        urlRow.addView(urlEt)
        urlRow.addView(Button(this).apply {
            text = "วาง"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                if (!t.isNullOrBlank()) urlEt.setText(t.trim())
            }
        })
        col.addView(urlRow)

        col.addView(label("หน่วงหลังแอปเปิดขึ้นมา ก่อนเริ่มแตะ (ms)"))
        launchDelayEt = field("", 100, InputType.TYPE_CLASS_NUMBER) { s ->
            Store.edit(this) { it.launchDelayMs = (s.toLongOrNull() ?: 1500L).coerceIn(0, 30000) }
        }
        col.addView(launchDelayEt)

        col.addView(button("🚀 เริ่มเลย (เปิดลิงก์เข้าแอป แล้วแตะตามที่ตั้งไว้)") { launchLink() })

        col.addView(label("ขั้นตอน:  ลำดับ | หน่วงหลังกด (ms) | รอข้อความนี้ก่อนกด"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        col.addView(button("＋ เพิ่มขั้น") {
            Store.edit(this) { it.steps.add(Step(0.5f, 0.5f, 30, "")) }
            rebuild()
            TapService.instance?.refresh()
        })
        col.addView(button("ใส่ค่าเริ่มต้นตามหน้าจอที่ส่งมา (4 ขั้น)") { preset() })
        col.addView(button("2) แสดงจุด/แถบควบคุมบนจอ") {
            val s = TapService.instance
            if (s == null) {
                Toast.makeText(this, "เปิด Accessibility ก่อน", Toast.LENGTH_LONG).show()
            } else {
                s.showOverlay()
                moveTaskToBack(true)
            }
        })
        col.addView(label(
            "วิธีใช้: เปิดแอพเป้าหมายที่หน้าสินค้า → ลากเลข ①②③ ไปวางบนปุ่ม → กด ▶\n" +
                    "หยุดได้ทุกเมื่อด้วยปุ่ม ■"
        ))
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TapService.instance != null) "สถานะ: พร้อมใช้งาน ✅" else "สถานะ: ยังไม่ได้เปิด Accessibility ❌"
        val cfg = Store.load(this)
        roundsEt.setText(cfg.rounds.toString())
        timeoutEt.setText(cfg.timeoutMs.toString())
        urlEt.setText(cfg.url)
        launchDelayEt.setText(cfg.launchDelayMs.toString())
        rebuild()
    }

    /** เปิดลิงก์สินค้าตรงเข้าแอป (ถ้ามี) แล้วสั่ง service เริ่มแตะเมื่อแอปขึ้นมา */
    private fun launchLink() {
        val svc = TapService.instance
        if (svc == null) {
            Toast.makeText(this, "เปิด Accessibility ก่อน", Toast.LENGTH_LONG).show()
            return
        }
        val raw = urlEt.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "ใส่ลิงก์สินค้าก่อน", Toast.LENGTH_SHORT).show()
            return
        }
        Store.edit(this) { it.url = raw }
        val uri = Uri.parse(if (raw.startsWith("http", true)) raw else "https://$raw")
        val host = uri.host.orEmpty().lowercase()
        val pkg = when {
            "shopee" in host || "shp.ee" in host -> "com.shopee.th"
            "lazada" in host || "lzd.co" in host -> "com.lazada.android"
            else -> null
        }

        svc.showOverlay()

        fun view(withPkg: Boolean) = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (withPkg && pkg != null) setPackage(pkg)
        }
        try {
            startActivity(view(true))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(view(false))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "เปิดลิงก์ไม่ได้", Toast.LENGTH_LONG).show()
                return
            }
        }
        svc.launchRun(pkg)
    }

    private fun preset() {
        Store.edit(this) {
            it.steps.clear()
            it.steps.add(Step(0.77f, 0.94f, 40, ""))                       // ① ซื้อ
            it.steps.add(Step(0.17f, 0.47f, 40, "ไซซ์"))                   // ② เลือกไซซ์ (ลากไปไซซ์ที่ต้องการ)
            it.steps.add(Step(0.50f, 0.94f, 40, "ซื้อเลย"))                // ③ ซื้อเลย
            it.steps.add(Step(0.50f, 0.91f, 0, "ดำเนินการชำระเงิน"))       // ④ ชำระเงิน (ลบออกได้ถ้าจะกดเอง)
        }
        rebuild()
        TapService.instance?.refresh()
    }

    private fun rebuild() {
        list.removeAllViews()
        val cfg = Store.load(this)
        cfg.steps.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 18f
                setPadding(0, 0, dp(8), 0)
            })
            row.addView(field(s.delay.toString(), 70, InputType.TYPE_CLASS_NUMBER) { t ->
                Store.edit(this) { c -> c.steps.getOrNull(i)?.delay = (t.toLongOrNull() ?: 0L).coerceAtLeast(0) }
            })
            val wait = field(s.wait, 0, InputType.TYPE_CLASS_TEXT) { t ->
                Store.edit(this) { c -> c.steps.getOrNull(i)?.wait = t }
            }
            wait.hint = "รอข้อความ (ว่าง = ไม่รอ)"
            wait.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(wait)
            row.addView(Button(this).apply {
                text = "✕"
                setOnClickListener {
                    Store.edit(this@MainActivity) { c -> if (i < c.steps.size) c.steps.removeAt(i) }
                    rebuild()
                    TapService.instance?.refresh()
                }
            })
            list.addView(row)
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { f() }
    }

    /** ช่องกรอกที่บันทึกทันทีเมื่อพิมพ์ (widthDp = 0 → ไม่กำหนด) */
    private fun field(initial: String, widthDp: Int, type: Int, onChange: (String) -> Unit): EditText {
        return EditText(this).apply {
            inputType = type
            setText(initial)
            if (widthDp > 0) layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
            })
        }
    }
}
