package app.mangatrans.bench

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
 * Story 1.3 — chay 48 bubble that qua ban int4 TREN MAY THAT.
 *
 * Cau hoi duy nhat: luong tu hoa xuong int4 lam mat bao nhieu diem so voi
 * tran 77% do duoc o Phase 0 tren ban ollama 7.2GB do chinh xac cao?
 *
 * Dung DUNG dieu kien cua Phase 0 de so sanh hop le:
 *   - ca trang trong MOT lan goi (AD-3)
 *   - cung glossary 8 muc, cung boi canh
 *   - cung prompt v1
 *
 * Dau vao : /data/local/tmp/story13_input.json   (adb push)
 * Dau ra  : /data/local/tmp/story13_output.json  (adb pull, roi cham tay)
 */
class QualityActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MangaQual"
        const val IN = "/data/local/tmp/story13_input.json"
        // KHONG ghi vao /data/local/tmp: thu muc do thuoc quyen `shell`,
        // app KHONG co quyen ghi -> writeText() nem exception va mat het ket qua.
        // Ghi vao getExternalFilesDir() -> /sdcard/Android/data/<pkg>/files/, adb pull duoc.
        const val OUT_NAME = "story13_output.json"
        const val MODEL_CPU = "/data/local/tmp/gemma-4-E2B-it.litertlm"
        const val MODEL_GPU = "/data/local/tmp/gemma-4-E2B-it-gpu.litertlm"
    }

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 11f }
        setContentView(ScrollView(this).apply { addView(view) })
        lifecycleScope.launch { run() }
    }

    private fun log(s: String = "") {
        Log.i(TAG, s)
        runOnUiThread { view.append(s + "\n") }
    }

    /** Prompt v1 — chep nguyen tu spike/r2_translate/prompt.py de so sanh hop le. */
    private fun buildPrompt(context: String, glossary: JSONObject, bubbles: JSONArray): String {
        val gl = StringBuilder()
        glossary.keys().forEach { k -> gl.append("- $k: ${glossary.getString(k)}\n") }
        val bb = StringBuilder()
        for (i in 0 until bubbles.length()) {
            val b = bubbles.getJSONObject(i)
            bb.append("[${b.getInt("id")}] ${b.getString("ja")}\n")
        }
        return """## Bối cảnh trang
$context

## Nhân vật (glossary)
$gl
## Bong bóng thoại
Đã sắp theo thứ tự đọc manga (phải→trái, trên→dưới). Bubble liền nhau thường là một mạch hội thoại.

$bb
## Yêu cầu
Dịch từng bubble sang tiếng Việt. Trả về JSON đúng dạng:
{"bubbles": [{"id": <số>, "vi": "<bản dịch>", "speaker": "<tên người nói hoặc ?>"}]}
Phải đủ ${bubbles.length()} phần tử, đúng thứ tự id."""
    }

    private val system = """Bạn là người dịch truyện tranh Nhật sang tiếng Việt, làm việc cho một nhóm dịch.

Nguyên tắc:
- Dịch thoại manga, không phải văn bản trang trọng. Giữ giọng nói tự nhiên như người Việt nói chuyện.
- Tiếng Nhật lược chủ ngữ liên tục. Bạn PHẢI suy ra người nói và người nghe từ ngữ cảnh cả trang, rồi chọn xưng hô tiếng Việt cho đúng. Xưng hô sai là lỗi nặng nhất.
- Giữ nguyên xưng hô đã chốt trong glossary. Không đổi giữa chừng.
- Hậu tố -san/-kun/-chan/-sama: bỏ, chuyển thành sắc thái qua cách xưng hô tiếng Việt.
- Độ dài bản dịch phải vừa bong bóng: ngắn gọn.
- KHÔNG giải thích, KHÔNG chú thích, KHÔNG thêm bubble mới.

Trả về DUY NHẤT một khối JSON, không có chữ nào khác ngoài nó."""

    private suspend fun run() = withContext(Dispatchers.Default) {
        log("=== Story 1.3 — chat luong ban int4 ===")
        log("May: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL})")
        log()

        val inFile = File(IN)
        if (!inFile.exists()) {
            log("LOI: chua co $IN")
            log("  adb push spike/out/story13_input.json $IN")
            return@withContext
        }

        // Chon backend theo file model dang co tren may.
        val gpu = File(MODEL_GPU).exists()
        val modelPath = if (gpu) MODEL_GPU else MODEL_CPU
        val backend = if (gpu) Backend.GPU() else Backend.CPU()
        if (!File(modelPath).exists()) { log("LOI: chua co model nao"); return@withContext }
        log("model  : ${File(modelPath).name}")
        log("backend: ${if (gpu) "GPU" else "CPU"}")
        log()

        val root = JSONObject(inFile.readText())
        val glossary = root.getJSONObject("glossary")
        val context = root.getString("context")
        val scenes = root.getJSONArray("scenes")
        val result = JSONArray()

        try {
            Engine(EngineConfig(modelPath = modelPath, backend = backend)).use { engine ->
                val t0 = System.currentTimeMillis()
                engine.initialize()
                log("initialize: ${(System.currentTimeMillis() - t0) / 1000.0} s")
                log()

                for (i in 0 until scenes.length()) {
                    val sc = scenes.getJSONObject(i)
                    val page = sc.getString("page")
                    val bubbles = sc.getJSONArray("bubbles")
                    log("--- $page (${bubbles.length()} bubble) ---")

                    val t = System.currentTimeMillis()
                    // AD-3: CA TRANG trong MOT lan goi.
                    val reply = engine.createConversation().use { conv ->
                        conv.sendMessage(system + "\n\n" + buildPrompt(context, glossary, bubbles))
                            .contents.toString()
                    }
                    val secs = (System.currentTimeMillis() - t) / 1000.0
                    log("  %.1f s".format(secs))

                    result.put(JSONObject().apply {
                        put("page", page)
                        put("seconds", secs)
                        put("raw", reply)
                        put("ja", bubbles)
                    })
                }
            }
        } catch (e: Throwable) {
            log("LOI: ${e::class.java.simpleName}: ${e.message}")
            Log.e(TAG, "loi", e)
        }

        val payload = JSONObject().apply {
            put("device", "${Build.MODEL} / ${Build.SOC_MODEL}")
            put("backend", if (gpu) "GPU" else "CPU")
            put("model", File(modelPath).name)
            put("pages", result)
        }.toString(2)

        val outFile = File(getExternalFilesDir(null), OUT_NAME)
        try {
            outFile.writeText(payload)
            log()
            log("=== xong ===")
            log("Ghi: ${outFile.absolutePath} (${outFile.length()} byte)")
            log("Lay ve may tinh:")
            log("  adb pull ${outFile.absolutePath}")
        } catch (e: Throwable) {
            // Du sao cung khong duoc mat ket qua sau khi da cho vai phut.
            log("KHONG ghi duoc file: ${e.message}")
            log("Do nguyen ket qua ra logcat thay the:")
            payload.chunked(3000).forEachIndexed { i, c -> log("[RAW $i] $c") }
        }
    }
}
