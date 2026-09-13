package app.mangatrans.bench

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Do anh huong cua so luong CPU thread len NHIET va TOC DO.
 *
 * Gia thuyet: decode bi chan boi BANG THONG BO NHO chu khong phai suc tinh,
 * nen giam so luong co the gan nhu khong mat toc do ma nhiet giam ro.
 * Neu dung, day la cach giam nhiet re nhat.
 *
 * SM7325 co 8 nhan: 4 manh (Cortex-A78) + 4 tiet kiem (Cortex-A55).
 *
 * Moi cau hinh chi dich 2 trang de may khong kip nong — va giua cac cau hinh
 * co nghi de nhiet ve gan mac ban dau, neu khong thi cau hinh sau bi thiet.
 */
class ThermalActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MangaTherm"
        const val IN = "/data/local/tmp/story13_input.json"
        const val OUT_NAME = "thermal_probe.json"
        const val MODEL = "/data/local/tmp/gemma-4-E2B-it.litertlm"
        val THREADS = listOf(2, 4, null)   // null = de LiteRT-LM tu chon
        const val PAGES_PER_CONFIG = 2
        const val COOLDOWN_SEC = 45
    }

    private lateinit var view: TextView
    private val out = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { setPadding(20, 20, 20, 20); textSize = 11f }
        setContentView(ScrollView(this).apply { addView(view) })
        lifecycleScope.launch { run() }
    }

    private fun log(s: String = "") { Log.i(TAG, s); runOnUiThread { view.append(s + "\n") } }

    private fun tempC(): Double = try {
        File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.mapNotNull { runCatching { File(it, "temp").readText().trim().toDouble() }.getOrNull() }
            ?.map { if (it > 1000) it / 1000.0 else it }
            ?.filter { it in 1.0..150.0 }?.maxOrNull() ?: -1.0
    } catch (_: Throwable) { -1.0 }

    /** Trang thai nhiet do HE DIEU HANH bao — API 29+. Day moi la thu app nen dua vao. */
    private fun thermalStatus(): String = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "?"
        }
    } catch (_: Throwable) { "?" }

    private suspend fun run() = withContext(Dispatchers.Default) {
        log("=== Do so luong CPU thread vs nhiet ===")
        log("May: ${Build.MODEL} (${Build.SOC_MODEL})")
        log("So nhan: ${Runtime.getRuntime().availableProcessors()}")
        log("Nhiet ban dau: %.1f C (trang thai: %s)".format(tempC(), thermalStatus()))
        log()

        if (!File(MODEL).exists()) {
            log("LOI: chua co $MODEL")
            log("  adb push spike/models/gemma-4-E2B-it.litertlm $MODEL"); return@withContext
        }
        if (!File(IN).exists()) { log("LOI: chua co $IN"); return@withContext }

        val root = JSONObject(File(IN).readText())
        val scenes = root.getJSONArray("scenes")
        val gl = StringBuilder()
        root.getJSONObject("glossary").let { g -> g.keys().forEach { gl.append("- $it: ${g.getString(it)}\n") } }

        log("luong | trang | giay | nhiet C | trang thai | bubble")
        log("------+-------+------+---------+------------+-------")

        for (th in THREADS) {
            val tempBefore = tempC()
            try {
                val backend = if (th == null) Backend.CPU() else Backend.CPU(th, null)
                Engine(EngineConfig(modelPath = MODEL, backend = backend)).use { engine ->
                    engine.initialize()
                    for (k in 0 until PAGES_PER_CONFIG) {
                        val sc = scenes.getJSONObject(k % scenes.length())
                        val bubbles = sc.getJSONArray("bubbles")
                        val bb = StringBuilder()
                        for (i in 0 until bubbles.length()) {
                            val b = bubbles.getJSONObject(i)
                            bb.append("[${b.getInt("id")}] ${b.getString("ja")}\n")
                        }
                        val prompt = "Dịch các bubble sau sang tiếng Việt. Trả về JSON " +
                            "{\"bubbles\":[{\"id\":<số>,\"vi\":\"...\"}]}.\n\n" +
                            "Bối cảnh: ${root.getString("context")}\n\nGlossary:\n$gl\nBubble:\n$bb"

                        val t = System.currentTimeMillis()
                        val reply = engine.createConversation().use { it.sendMessage(prompt).contents.toString() }
                        val secs = (System.currentTimeMillis() - t) / 1000.0
                        val temp = tempC(); val st = thermalStatus()

                        // AD-4: kiem NOI DUNG, khong chi kiem chay duoc.
                        val m = Regex("\\{[\\s\\S]*\\}").find(reply)
                        val n = m?.let { runCatching { JSONObject(it.value).getJSONArray("bubbles").length() }.getOrNull() } ?: -1

                        log("%5s | %5d | %4.0f | %7.1f | %10s | %s".format(
                            th?.toString() ?: "mac dinh", k, secs, temp, st,
                            if (n >= 0) "$n/${bubbles.length()}" else "HONG"))

                        out.put(JSONObject().apply {
                            put("threads", th ?: JSONObject.NULL); put("page", k)
                            put("seconds", secs); put("tempC", temp); put("thermalStatus", st)
                            put("bubblesOut", n); put("tempBeforeConfig", tempBefore)
                        })
                    }
                }
            } catch (e: Throwable) {
                log("%5s | LOI: %s".format(th?.toString() ?: "mac dinh", e.message?.take(50)))
                out.put(JSONObject().apply { put("threads", th ?: JSONObject.NULL); put("error", e.message) })
            }
            // Nghi cho nhiet ha, neu khong cau hinh sau bi thiet vi thua nhiet cua cau hinh truoc.
            if (th != THREADS.last()) {
                log("   ... nghi ${COOLDOWN_SEC}s cho nguoi (nhiet %.1f C)".format(tempC()))
                Thread.sleep(COOLDOWN_SEC * 1000L)
            }
        }

        File(getExternalFilesDir(null), OUT_NAME).writeText(
            JSONObject().apply {
                put("device", "${Build.MODEL} / ${Build.SOC_MODEL}")
                put("cores", Runtime.getRuntime().availableProcessors())
                put("runs", out)
            }.toString(2))
        log()
        log("=== xong, nhiet cuoi %.1f C ===".format(tempC()))
        log("adb pull ${File(getExternalFilesDir(null), OUT_NAME).absolutePath}")
    }
}
