package app.mangatrans.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mangatrans.adapters.litertlm.LiteRtLmTranslator
import app.mangatrans.adapters.onnx.MangaOcrOnnx
import app.mangatrans.adapters.onnx.OnnxTextDetector
import app.mangatrans.adapters.storage.FileCache
import app.mangatrans.adapters.storage.JsonGlossaryStore
import app.mangatrans.domain.PageEvent
import app.mangatrans.pipeline.BubbleRenderer
import app.mangatrans.pipeline.Pipeline
import app.mangatrans.pipeline.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Epic 2 — man hinh toi gian: chon mot anh trang manga -> ra anh da dich.
 *
 * Chua co overlay, chua chup man hinh. Nguon anh la file, de go loi pipeline de.
 *
 * Model day len may bang adb (Epic 1 da phai lam roi):
 *   adb push gemma-4-E2B-it.litertlm      /data/local/tmp/
 *   adb push detector-v4-s_int8.onnx      /data/local/tmp/
 *   adb push encoder_model_int8.onnx      /data/local/tmp/
 *   adb push decoder_model_int8.onnx      /data/local/tmp/
 *   adb push vocab.txt                    /data/local/tmp/
 *
 * Viec tai tu dong la Epic 4, co y de sau (PRD chot dung rieng truoc).
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MangaTrans"
        const val TMP = "/data/local/tmp"
    }

    private lateinit var image: ImageView
    private lateinit var log: TextView
    private lateinit var pickBtn: Button

    private val setupOnce = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pipeline: Pipeline? = null
    private var translator: LiteRtLmTranslator? = null
    private var typeface: Typeface = Typeface.DEFAULT

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { translate(it) } }   // picker -> uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickBtn = Button(this).apply {
            text = "Chọn ảnh trang manga"
            setOnClickListener { picker.launch(arrayOf("image/*")) }
        }
        // Nut nay de chay duoc bang adb ma khong can thao tac tay:
        //   adb push trang.jpg /data/local/tmp/test_page.jpg
        //   adb shell am start -n app.mangatrans/.ui.MainActivity --ez auto true
        val sampleBtn = Button(this).apply {
            text = "Dùng ảnh mẫu (/data/local/tmp/test_page.jpg)"
            setOnClickListener { translateFile(File(TMP, "test_page.jpg")) }
        }
        // FR-033/FR-034 — sau moi trang dich, muc tu de xuat don o day cho xac nhan.
        val glossaryBtn = Button(this).apply {
            text = "Từ điển riêng"
            setOnClickListener { startActivity(GlossaryActivity.intent(this@MainActivity)) }
        }
        image = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 400
        }
        log = TextView(this).apply { setPadding(16, 8, 16, 8); textSize = 11f }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(pickBtn); addView(sampleBtn); addView(glossaryBtn)
                addView(log); addView(image)
            })
        })

        // GlossaryActivity de exported=false (dung), nen khong mo thang bang adb
        // duoc. Mo qua day de kiem tra tay:
        //   adb shell am start -n app.mangatrans/.ui.MainActivity --ez glossary true
        if (intent?.getBooleanExtra("glossary", false) == true) {
            startActivity(GlossaryActivity.intent(this))
        }

        lifecycleScope.launch {
            setup()
            // Cho phep chay tu dong: am start ... --ez auto true
            if (intent?.getBooleanExtra("auto", false) == true) {
                translateFile(File(TMP, "test_page.jpg"))
            }
        }
    }

    private fun translateFile(f: File) {
        if (!f.exists()) { say("Không thấy ${f.absolutePath}"); return }
        translate(null, f)
    }

    private fun say(s: String) {
        Log.i(TAG, s)
        runOnUiThread { log.append(s + "\n") }
    }

    private suspend fun setup() = withContext(Dispatchers.IO) {
        // Activity co the bi tao lai (doi theme, xoay man hinh...). Nap model hai
        // lan = 2 x 171 MB = OOM chac chan. Da gap that tren M52.
        if (!setupOnce.compareAndSet(false, true)) return@withContext
        // ONNX Runtime ban Android KHONG co `ConvInteger` — op ma ca ba ban
        // luong tu cua encoder (_int8/_quantized/_uint8) deu dung o lop patch
        // embedding. Loi chi lo ra TREN MAY; tren PC chay binh thuong vi do la
        // ban day du. Nen phai dung encoder fp16 hoac fp32.
        //
        // Decoder int8 dung `MatMulInteger` — op nay ORT Android co ho tro.
        val encoderCandidates = listOf(
            "encoder_model_fp16.onnx",   // 172 MB, khong co op luong tu
            "encoder_model.onnx",        // 343 MB, du phong
        )
        val decoderCandidates = listOf(
            "decoder_model_int8.onnx",   // 29.6 MB, MatMulInteger — ORT Android ho tro
            "decoder_model.onnx",
        )
        val encFile = encoderCandidates.map { File(TMP, it) }.firstOrNull { it.exists() }
        val decFile = decoderCandidates.map { File(TMP, it) }.firstOrNull { it.exists() }

        val need = listOf("detector-v4-s_int8.onnx", "vocab.txt", "gemma-4-E2B-it.litertlm")
        val missing = need.filterNot { File(TMP, it).exists() }.toMutableList()
        if (encFile == null) missing += encoderCandidates.first()
        if (decFile == null) missing += decoderCandidates.first()
        if (missing.isNotEmpty()) {
            say("Thiếu file, đẩy lên bằng adb push:")
            missing.forEach { say("  adb push $it $TMP/") }
            return@withContext
        }

        say("Nạp detector + OCR...")
        say("  encoder: ${encFile!!.name} (${encFile.length() / 1_000_000} MB)")
        say("  decoder: ${decFile!!.name} (${decFile.length() / 1_000_000} MB)")
        val det = OnnxTextDetector(File(TMP, "detector-v4-s_int8.onnx").absolutePath)
        val vocab = File(TMP, "vocab.txt").readLines()
        val ocr = MangaOcrOnnx(encFile.absolutePath, decFile.absolutePath, vocab)

        // AD-25: 2 luong CPU. AD-2: CPU la duong duy nhat tren Adreno 642L.
        val cfg = PipelineConfig()
        val tr = LiteRtLmTranslator(File(TMP, "gemma-4-E2B-it.litertlm").absolutePath, cfg)
        translator = tr

        // Nap san glossary neu co file mau — F9 da do: glossary keo 46% -> 60%.
        //   adb push glossary_seed.json /data/local/tmp/
        // Mot cho duy nhat quyet dinh glossary nam o dau — GlossaryActivity mo
        // dung file nay. Viet lai chuoi "glossary.json" o day la loi chia doi.
        val glossaryFile = GlossaryActivity.file(this@MainActivity)
        val seed = File(TMP, "glossary_seed.json")
        if (seed.exists() && !glossaryFile.exists()) {
            glossaryFile.parentFile?.mkdirs()
            seed.copyTo(glossaryFile, overwrite = true)
            say("Đã nạp glossary mẫu từ ${seed.name}")
        }

        pipeline = Pipeline(
            detector = det,
            ocr = ocr,
            translator = tr,
            glossary = JsonGlossaryStore(glossaryFile),
            cache = FileCache(File(cacheDir, "pages")),
            cfg = cfg,
        )

        // FR-043 — kiem font co du dau tieng Viet, khong tin ten font.
        typeface = Typeface.SANS_SERIF
        val ok = BubbleRenderer.supportsVietnamese(typeface)
        say(if (ok) "Font: có đủ dấu tiếng Việt ✓" else "CẢNH BÁO: font thiếu dấu tiếng Việt")

        say("Sẵn sàng. Đang hâm nóng LLM (AD-20, mất 15–30 giây)...")
        val t = System.currentTimeMillis()
        runCatching { tr.warmUp() }
            .onSuccess { say("LLM sẵn sàng sau ${(System.currentTimeMillis() - t) / 1000}s") }
            .onFailure { say("Lỗi nạp LLM: ${it.message}") }
    }

    private fun translate(uri: Uri?, file: File? = null) = lifecycleScope.launch {
        val p = pipeline ?: run { say("Chưa sẵn sàng"); return@launch }
        runOnUiThread { log.text = "" }

        val src = withContext(Dispatchers.IO) {
            when {
                file != null -> BitmapFactory.decodeFile(file.absolutePath)
                uri != null -> contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                else -> null
            }?.copy(Bitmap.Config.ARGB_8888, true)
        } ?: run { say("Không đọc được ảnh"); return@launch }

        say("Ảnh ${src.width}×${src.height}")
        // Giu ban goc de ve lai tu dau moi lan co bubble moi. Bong thoai chong
        // lan nhau, nen phai to het nen roi moi ve het chu (xem drawPage).
        val pristine = src.copy(Bitmap.Config.ARGB_8888, false)
        val canvas = Canvas(src)
        val accepted = LinkedHashMap<Int, app.mangatrans.domain.Bubble>()
        val t0 = System.currentTimeMillis()
        var drawn = 0

        fun repaint() {
            canvas.drawBitmap(pristine, 0f, 0f, null)
            drawn = BubbleRenderer.drawPage(canvas, accepted.values.toList(), typeface) {
                BubbleRenderer.sampleBackground(pristine, it)
            }
            image.setImageBitmap(src)
        }

        p.run(src).collect { ev ->
            when (ev) {
                is PageEvent.Progress -> say("  ${ev.stage}${if (ev.total > 0) " ${ev.done}/${ev.total}" else ""}")

                is PageEvent.BubbleReady -> {
                    accepted[ev.bubble.id] = ev.bubble
                    repaint()   // FR-044: hien dan tung bubble
                }

                // AD-17 — go bubble DA VE. Bat buoc ho tro, khong phai ngoai le.
                is PageEvent.Retracted -> {
                    say("  ⚠ gỡ ${ev.bubbleIds.size} bubble: ${ev.reason}")
                    ev.bubbleIds.forEach { accepted.remove(it) }
                    repaint()   // ve lai tu anh goc => chu Nhat hien lai
                }

                is PageEvent.PageRejected -> {
                    say("  ✖ từ chối cả trang: ${ev.reason}")
                    accepted.clear()
                    repaint()   // AD-9: toan bo tro ve nguyen ban
                }

                is PageEvent.Done -> {
                    val secs = (System.currentTimeMillis() - t0) / 1000.0
                    say("Xong: vẽ $drawn/${ev.job.bubbles.count { it.ja.isNotBlank() }} bubble · %.1fs".format(secs))
                    // Luu de KIEM BANG MAT. App bao "ve 12/12" khong co nghia la ve DUNG.
                    // Quy tac 2 trong CLAUDE.md: chay duoc != chay dung.
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(getExternalFilesDir(null), "rendered.png")
                            f.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            say("Ảnh đã vẽ: ${f.absolutePath}")
                        }.onFailure { say("Không lưu được ảnh: ${it.message}") }
                    }
                    ev.job.bubbles.filter { it.ja.isNotBlank() }.forEach {
                        say("  [${it.id}] ${it.ja}  →  ${it.vi ?: "(giữ nguyên)"}")
                    }
                }

                is PageEvent.Failed -> say("Lỗi: ${ev.error.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { translator?.release() }   // AD-24
    }
}
