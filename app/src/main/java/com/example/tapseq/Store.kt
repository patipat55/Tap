package com.example.tapseq

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** หนึ่งขั้นการกด: ตำแหน่งเป็นสัดส่วนของหน้าจอ (0..1) ใช้ได้ทุกขนาดจอ */
data class Step(
    var fx: Float,
    var fy: Float,
    var delay: Long = 30,      // หน่วงหลังกดก่อนไปขั้นถัดไป (ms) 0 = เร็วที่สุด
    var wait: String = ""      // รอให้ข้อความนี้ขึ้นบนจอก่อนกด (ว่าง = ไม่รอ)
)

data class Config(
    var rounds: Int = 1,           // จำนวนรอบ (จำกัด 1..20)
    var timeoutMs: Long = 3000,    // รอข้อความนานสุดกี่ ms ก่อนหยุด
    val steps: MutableList<Step> = mutableListOf(),
    var url: String = "",            // ลิงก์หน้าสินค้า
    var launchDelayMs: Long = 1500   // หน่วงหลังแอปเปิดขึ้นมา ก่อนเริ่มแตะ (ms)
)

object Store {
    private const val PREF = "tapseq"

    fun load(c: Context): Config {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // อ่านแบบกันพัง: ถ้าข้อมูลเก่าเก็บเป็นชนิดอื่น (Int/Long/String) จะไม่ทำให้แอพเด้ง
        val all = sp.all
        fun num(k: String, d: Long): Long = (all[k] as? Number)?.toLong() ?: d
        val cfg = Config(num("rounds", 1).toInt(), num("timeout", 3000))
        cfg.url = (all["url"] as? String) ?: ""
        cfg.launchDelayMs = num("launchDelay", 1500)
        runCatching {
            val a = JSONArray(sp.getString("steps", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                cfg.steps.add(
                    Step(
                        o.getDouble("x").toFloat(),
                        o.getDouble("y").toFloat(),
                        o.optLong("d", 30),
                        o.optString("w", "")
                    )
                )
            }
        }
        return cfg
    }

    fun save(c: Context, cfg: Config) {
        val a = JSONArray()
        cfg.steps.forEach {
            a.put(
                JSONObject()
                    .put("x", it.fx.toDouble())
                    .put("y", it.fy.toDouble())
                    .put("d", it.delay)
                    .put("w", it.wait)
            )
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("rounds", cfg.rounds.coerceIn(1, 20))
            .putLong("timeout", cfg.timeoutMs.coerceIn(500, 30000))
            .putString("steps", a.toString())
            .putString("url", cfg.url)
            .putLong("launchDelay", cfg.launchDelayMs.coerceIn(0, 30000))
            .apply()
    }

    /** โหลดล่าสุด → แก้ → บันทึก (กันทับกันระหว่างแอพกับตัวลอยบนจอ) */
    fun edit(c: Context, f: (Config) -> Unit) {
        val cfg = load(c)
        f(cfg)
        save(c, cfg)
    }
}
