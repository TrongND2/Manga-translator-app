package app.mangatrans.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
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
 * Do anh huong cua maxNumTokens len RAM.
 *
 * Cau hoi: bop KV cache xuong duoc bao nhieu ma van dich dung?
 *
 * RSS gom hai phan rat khac nhau:
 *   - trong so model: nap bang mmap, he dieu hanh TU thu hoi khi can
 *     (da do: 3017 -> 1998 MB trong 13 phut, khong bi kill)
 *   - KV cache: RAM CAP PHAT CUNG, khong thu hoi duoc -> day moi la phan bop duoc
 *
 * Moi muc chi dich MOT trang de may khong kip nong.
 */
class MemActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MangaMem"
        const val IN = "/data/local/tmp/story13_input.json"
        const val OUT_NAME = "mem_probe.json"
        const val MODEL = "/data/local/tmp/gemma-4-E2B-it.litertlm"
        // null = de LiteRT-LM tu chon (mac dinh hien tai)
        val LEVELS = listOf(512, 1024, 2048, null)
    }

    private lateinit var view: TextView
    private val out = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { setPadding(20, 20, 20, 20); textSize = 11f }
        setContentView(ScrollView(this).apply { addView(view) })
        lifecycleScope.launch { run() }
    }

    private fun log(s: String = "") {
        Log.i(TAG, s); runOnUiThread { view.append(s + "\n") }
    }

    private fun rssMb(): Long = try {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1
    } catch (_: Throwable) { -1 }

    private fun availMb(): Long = try {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    } catch (_: Throwable) { -1 }

    private suspend fun run() = withContext(Dispatchers.Default) {
        log("=== Do maxNumTokens vs RAM ===")
        log("May: ${Build.MODEL}")
        log("RSS truoc khi nap gi: ${rssMb()} MB")
        log()

        if (!File(MODEL).exists() || !File(IN).exists()) {
            log("LOI: thieu model hoac input"); return@withContext
        }
        val root = JSONObject(File(IN).readText())
        val sc = root.getJSONArray("scenes").getJSONObject(0)
        val bubbles = sc.getJSONArray("bubbles")
        val gl = StringBuilder()
        root.getJSONObject("glossary").let { g -> g.keys().forEach { gl.append("- $it: ${g.getString(it)}\n") } }
        val bb = StringBuilder()
        for (i in 0 until bubbles.length()) {
            val b = bubbles.getJSONObject(i); bb.append("[${b.getInt("id")}] ${b.getString("ja")}\n")
        }
        val prompt = "Dịch các bubble sau sang tiếng Việt. Trả về JSON " +
            "{\"bubbles\":[{\"id\":<số>,\"vi\":\"...\"}]}.\n\n" +
            "Bối cảnh: ${root.getString("context")}\n\nGlossary:\n$gl\nBubble:\n$bb"
        log("prompt: ${prompt.length} ky tu, ${bubbles.length()} bubble")
        log()
        log("maxTok | RSS nap MB | RSS dich MB | giay | bubble | JSON")
        log("-------+------------+-------------+------+--------+-----")

        for (lv in LEVELS) {
            try {
                val cfg = EngineConfig(modelPath = MODEL, backend = Backend.CPU(), maxNumTokens = lv)
                Engine(cfg).use { engine ->
                    engine.initialize()
                    val rssLoad = rssMb()
                    val t = System.currentTimeMillis()
                    val reply = engine.createConversation().use {
                        it.sendMessage(prompt).contents.toString()
                    }
                    val secs = (System.currentTimeMillis() - t) / 1000.0
                    val rssGen = rssMb()

                    // Kiem NOI DUNG, khong chi kiem chay duoc (AD-4).
                    val m = Regex("\\{[\\s\\S]*\\}").find(reply)
                    var n = -1; var ok = false
                    if (m != null) {
                        try {
                            n = JSONObject(m.value).getJSONArray("bubbles").length(); ok = true
                        } catch (_: Throwable) {}
                    }
                    log("%6s | %10d | %11d | %4.0f | %6s | %s".format(
                        lv?.toString() ?: "mac dinh", rssLoad, rssGen, secs,
                        if (n >= 0) "$n/${bubbles.length()}" else "?", if (ok) "OK" else "HONG"))

                    out.put(JSONObject().apply {
                        put("maxNumTokens", lv ?: JSONObject.NULL)
                        put("rssAfterLoadMb", rssLoad); put("rssAfterGenMb", rssGen)
                        put("seconds", secs); put("bubblesOut", n); put("jsonOk", ok)
                        put("availMb", availMb()); put("reply", reply.take(1500))
                    })
                }
            } catch (e: Throwable) {
                log("%6s | LOI: %s".format(lv?.toString() ?: "mac dinh", e.message?.take(60)))
                out.put(JSONObject().apply {
                    put("maxNumTokens", lv ?: JSONObject.NULL); put("error", e.message)
                })
            }
            // Cho may nguoi mot chut giua cac muc.
            Thread.sleep(5000)
        }

        File(getExternalFilesDir(null), OUT_NAME).writeText(
            JSONObject().apply { put("device", Build.MODEL); put("runs", out) }.toString(2))
        log()
        log("=== xong ===")
        log("adb pull ${File(getExternalFilesDir(null), OUT_NAME).absolutePath}")
    }
}
