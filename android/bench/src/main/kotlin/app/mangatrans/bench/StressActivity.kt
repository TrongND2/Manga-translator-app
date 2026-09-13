package app.mangatrans.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
 * Story 1.4 — do RAM va nhiet trong 30 phut dich lien tuc.
 *
 * Tra loi NFR-007. Ba cau hoi:
 *   1. Co bi OOM-kill khong?
 *   2. RAM that su duoc cap cho process la bao nhieu? (7.19 GiB danh nghia
 *      nhung MemAvailable luc do chi 2.52 GiB)
 *   3. tok/s o phut 1 va phut 30 co khac nhau khong? (throttle do nhiet)
 *
 * Neu app bi OOM-kill giua chung thi khong ghi duoc file ket qua — day chinh
 * la tin hieu can tim. Vi vay MOI VONG deu ghi de file ngay, khong doi den cuoi.
 */
class StressActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MangaStress"
        const val IN = "/data/local/tmp/story13_input.json"
        const val OUT_NAME = "story14_stress.json"
        const val MODEL_CPU = "/data/local/tmp/gemma-4-E2B-it.litertlm"
        const val MINUTES = 30
    }

    private lateinit var view: TextView
    private val samples = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Giu man hinh sang — man hinh tat co the lam he dieu hanh ha uu tien process.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = TextView(this).apply { setPadding(20, 20, 20, 20); textSize = 11f }
        setContentView(ScrollView(this).apply { addView(view) })
        lifecycleScope.launch { run() }
    }

    private fun log(s: String = "") {
        Log.i(TAG, s)
        runOnUiThread { view.append(s + "\n") }
    }

    /** RSS that su cua process, tinh bang MB. Doc tu /proc/self/status. */
    private fun rssMb(): Long = try {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1
    } catch (_: Throwable) { -1 }

    /** RAM con trong cua ca may, MB. */
    private fun availMb(): Long = try {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    } catch (_: Throwable) { -1 }

    /** He dieu hanh da coi process la sap bi kill chua. */
    private fun lowMemory(): Boolean = try {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.lowMemory
    } catch (_: Throwable) { false }

    /** Nhiet do cao nhat trong cac thermal zone, do C. Nhieu may khong cho doc. */
    private fun tempC(): Double = try {
        File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.mapNotNull { z ->
                runCatching { File(z, "temp").readText().trim().toDouble() }.getOrNull()
            }
            ?.map { if (it > 1000) it / 1000.0 else it }
            ?.filter { it in 1.0..150.0 }
            ?.maxOrNull() ?: -1.0
    } catch (_: Throwable) { -1.0 }

    private fun writeOut(note: String) {
        try {
            File(getExternalFilesDir(null), OUT_NAME).writeText(
                JSONObject().apply {
                    put("device", "${Build.MODEL} / ${Build.SOC_MODEL}")
                    put("note", note)
                    put("samples", samples)
                }.toString(2)
            )
        } catch (e: Throwable) { Log.e(TAG, "khong ghi duoc", e) }
    }

    private suspend fun run() = withContext(Dispatchers.Default) {
        log("=== Story 1.4 — RAM & nhiet, $MINUTES phut ===")
        log("May: ${Build.MODEL} (${Build.SOC_MODEL})")
        log("RAM may con trong luc bat dau: ${availMb()} MB")
        log()

        if (!File(MODEL_CPU).exists()) { log("LOI: chua co $MODEL_CPU"); return@withContext }
        if (!File(IN).exists()) { log("LOI: chua co $IN"); return@withContext }

        val scenes = JSONObject(File(IN).readText()).getJSONArray("scenes")
        val glossary = JSONObject(File(IN).readText()).getJSONObject("glossary")
        val context = JSONObject(File(IN).readText()).getString("context")

        val rssStart = rssMb()
        log("RSS truoc khi nap model: $rssStart MB")

        try {
            Engine(EngineConfig(modelPath = MODEL_CPU, backend = Backend.CPU())).use { engine ->
                val t0 = System.currentTimeMillis()
                engine.initialize()
                log("initialize: ${(System.currentTimeMillis() - t0) / 1000} s")
                log("RSS sau khi nap model: ${rssMb()} MB")
                log()
                log("phut | trang | giay | RSS MB | may trong MB | nhiet C | lowMem")
                log("-----+-------+------+--------+--------------+---------+-------")

                val deadline = System.currentTimeMillis() + MINUTES * 60_000L
                var n = 0
                while (System.currentTimeMillis() < deadline) {
                    val sc = scenes.getJSONObject(n % scenes.length())
                    val bubbles = sc.getJSONArray("bubbles")
                    val gl = StringBuilder()
                    glossary.keys().forEach { k -> gl.append("- $k: ${glossary.getString(k)}\n") }
                    val bb = StringBuilder()
                    for (i in 0 until bubbles.length()) {
                        val b = bubbles.getJSONObject(i)
                        bb.append("[${b.getInt("id")}] ${b.getString("ja")}\n")
                    }
                    val prompt = "Dịch các bubble sau sang tiếng Việt, trả về JSON " +
                        "{\"bubbles\":[{\"id\":<số>,\"vi\":\"...\"}]}.\n\n" +
                        "Bối cảnh: $context\n\nGlossary:\n$gl\nBubble:\n$bb"

                    val t = System.currentTimeMillis()
                    val len = try {
                        engine.createConversation().use { it.sendMessage(prompt).contents.toString().length }
                    } catch (e: Throwable) {
                        log("LOI vong $n: ${e::class.java.simpleName}: ${e.message}")
                        writeOut("loi giua chung o vong $n")
                        -1
                    }
                    val secs = (System.currentTimeMillis() - t) / 1000.0
                    val minute = (System.currentTimeMillis() - t0) / 60_000.0
                    val rss = rssMb(); val avail = availMb(); val temp = tempC(); val low = lowMemory()

                    samples.put(JSONObject().apply {
                        put("minute", "%.1f".format(minute)); put("page", sc.getString("page"))
                        put("seconds", secs); put("rssMb", rss); put("availMb", avail)
                        put("tempC", temp); put("lowMemory", low); put("replyChars", len)
                    })
                    // Ghi de NGAY moi vong: neu bi OOM-kill thi van con du lieu toi thoi diem do.
                    writeOut("dang chay")

                    log("%4.1f | %5d | %4.0f | %6d | %12d | %7.1f | %s"
                        .format(minute, n, secs, rss, avail, temp, if (low) "CO" else "-"))
                    n++
                }
                log()
                log("=== chay het $MINUTES phut, KHONG bi OOM-kill ===")
                writeOut("hoan thanh $MINUTES phut")
            }
        } catch (e: Throwable) {
            log("LOI: ${e::class.java.simpleName}: ${e.message}")
            Log.e(TAG, "loi", e)
            writeOut("loi: ${e.message}")
        }

        log("Lay ve: adb pull ${File(getExternalFilesDir(null), OUT_NAME).absolutePath}")
    }
}
