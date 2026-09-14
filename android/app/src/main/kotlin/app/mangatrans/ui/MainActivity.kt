package app.mangatrans.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mangatrans.Composition
import app.mangatrans.service.CaptureService
import app.mangatrans.adapters.litertlm.LiteRtLmTranslator
import app.mangatrans.domain.PageEvent
import app.mangatrans.pipeline.BubbleRenderer
import app.mangatrans.pipeline.Pipeline
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
    private lateinit var toggleBtn: LinearLayout

    private val setupOnce = java.util.concurrent.atomic.AtomicBoolean(false)
    private var pipeline: Pipeline? = null
    private var translator: LiteRtLmTranslator? = null
    private var typeface: Typeface = Typeface.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Manga Translator"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }
        val tagline = TextView(this).apply {
            text = "Dịch manga Nhật → Việt ngay trên máy"
            textSize = 13f
            alpha = 0.6f
            setPadding(0, 0, 0, 20)
        }

        // Mot nut DUY NHAT doi giua Bat va Tat. Hai nut rieng bat nguoi dung tu
        // nho dang o trang thai nao — ma chinh cai nut la cho nen noi dieu do.
        toggleBtn = MenuButton.make(
            this, "▶", "Bật icon dịch màn hình",
            "Icon nổi lên trên app đọc truyện", MenuButton.Colors.on,
        ) { onToggleOverlay() }

        val setupBtn = MenuButton.make(
            this, "⤓", "Cài đặt / gói mô hình",
            "Tải, kiểm tra hoặc xoá gói dịch", MenuButton.Colors.setup,
        ) { startActivity(android.content.Intent(this, SetupActivity::class.java)) }

        val guideBtn = MenuButton.make(
            this, "?", "Hướng dẫn sử dụng",
            "Ba cử chỉ và ý nghĩa từng trạng thái", MenuButton.Colors.guide,
        ) { startActivity(android.content.Intent(this, GuideActivity::class.java)) }

        val glossaryBtn = MenuButton.make(
            this, "A", "Từ điển riêng",
            "Tên nhân vật, thành ngữ, xưng hô", MenuButton.Colors.glossary,
        ) { startActivity(GlossaryActivity.intent(this)) }

        image = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 0
            visibility = android.view.View.GONE
        }
        // Log ky thuat chi hien o duong go loi bang adb. Nguoi dung khong can
        // biet ten file encoder.
        log = TextView(this).apply {
            setPadding(0, 12, 0, 8); textSize = 11f; alpha = 0.7f
            visibility = android.view.View.GONE
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(32))
                addView(title); addView(tagline)
                addView(toggleBtn)
                addView(setupBtn); addView(guideBtn); addView(glossaryBtn)
                addView(log); addView(image)
            })
        })

        // GlossaryActivity va CaptureService deu de exported=false (dung), nen
        // khong goi thang bang adb duoc. Mo qua day de kiem tra tay:
        //   adb shell am start -n app.mangatrans/.ui.MainActivity --ez glossary true
        //   adb shell am start -n app.mangatrans/.ui.MainActivity --ez overlay true
        if (intent?.getBooleanExtra("glossary", false) == true) {
            startActivity(GlossaryActivity.intent(this))
        }
        if (intent?.getBooleanExtra("overlay", false) == true) {
            OverlayLauncher.start(this)
        }
        //   adb shell am start -n app.mangatrans/.ui.MainActivity --ez setup true
        if (intent?.getBooleanExtra("setup", false) == true) {
            startActivity(android.content.Intent(this, SetupActivity::class.java))
        }
        // Chi HIEN anh mau, khong dich — de co mot trang truyen tren man hinh
        // ma thu icon noi. Anh chiem het man de giong canh doc that.
        //   adb shell am start -n app.mangatrans/.ui.MainActivity --ez show true
        if (intent?.getBooleanExtra("show", false) == true) {
            val f = File(TMP, "test_page.jpg")
            if (f.exists()) {
                setContentView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                })
            }
        }

        // ⚠️ KHONG nap engine o day.
        //
        // Truoc day man hinh chinh tu nap ca 3,2 GB mo hinh ngay khi mo — ke ca
        // khi nguoi dung chi vao xem huong dan roi thoat. Vua cham mo app, vua
        // dua app len dau danh sach bi Android giet khi thieu bo nho (da xay ra
        // that: "Process app.mangatrans has died: prcp FGS" giua luc dich).
        //
        // Gio chi `CaptureService` nap, va chi khi nguoi dung bat icon.
        if (intent?.getBooleanExtra("auto", false) == true) {
            log.visibility = android.view.View.VISIBLE
            lifecycleScope.launch {
                setup()
                translateFile(File(TMP, "test_page.jpg"))
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // Nguoi dung co the tat icon tu chinh icon con ✕ roi quay lai day.
        refreshToggle()
    }

    private fun refreshToggle() {
        if (CaptureService.isRunning) {
            MenuButton.update(
                toggleBtn, "■", "Tắt icon dịch màn hình",
                "Icon đang bật", MenuButton.Colors.off,
            )
        } else {
            MenuButton.update(
                toggleBtn, "▶", "Bật icon dịch màn hình",
                "Icon nổi lên trên app đọc truyện", MenuButton.Colors.on,
            )
        }
    }

    /**
     * Tu Android 13 thong bao la quyen PHAI XIN. Chua xin thi hai thu hong cung
     * luc, va ca hai deu hong **im lang**:
     *
     *   1. thong bao thuong truc cua service khong hien — nguoi dung mat duong
     *      tat nhanh, va mat dau hieu "app dang co the chup man hinh" (FR-012);
     *   2. **moi Toast deu bi nuot**. Log he thong ghi "Suppressing toast from
     *      package app.mangatrans by user request". Nghia la moi cau bao loi ma
     *      app dinh noi — het quyen chup, app chan chup, khong thay bong thoai —
     *      deu bien mat khong dau vet.
     *
     * Do that tren M52: `POST_NOTIFICATIONS: granted=false`. Dung cai canh nguoi
     * dung ke o gop y #11: icon hien cham than do ma khong noi gi ca.
     *
     * Xin o day chu khong xin luc mo app: day la luc dau tien app that su can
     * thong bao, nen hop thoai co ngu canh. Tu choi cung khong chan gi — chi mat
     * loi bao, nen khong ep, khong hoi lai.
     */
    private val askNotif = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { OverlayLauncher.start(this) }

    private fun needsNotifPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun onToggleOverlay() {
        if (CaptureService.isRunning) {
            OverlayLauncher.stop(this)
            say("Đã tắt icon nổi.")
        } else if (needsNotifPermission()) {
            // Xin thong bao TRUOC, roi callback moi chay tiep sang quyen chup.
            // Dung hai hop thoai chong nhau — nguoi dung chi thay tung cai mot.
            askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        } else {
            OverlayLauncher.start(this)
        }
        // Service bat/tat khong tuc thi — doi mot nhip roi doc lai trang thai.
        toggleBtn.postDelayed(::refreshToggle, 400)
    }

    private fun translateFile(f: File) {
        if (!f.exists()) { say("Không thấy ${f.absolutePath}"); return }
        translate(f)
    }

    private fun say(s: String) {
        Log.i(TAG, s)
        runOnUiThread { log.append(s + "\n") }
    }

    /**
     * Nap engine qua `Composition` — cung mot duong ma `CaptureService` dung.
     * Hai ban sao cua doan nay se troi khoi nhau o cho kho thay nhat, la danh
     * sach file mo hinh du phong.
     */
    private suspend fun setup() {
        // Activity co the bi tao lai (doi theme, xoay man hinh...). Nap model hai
        // lan = 2 x 171 MB = OOM chac chan. Da gap that tren M52.
        if (!setupOnce.compareAndSet(false, true)) return

        Composition.seedGlossaryIfEmpty(this) { say(it) }

        val engines = runCatching { Composition.engines(this) { say(it) } }.getOrElse { err ->
            when (err) {
                is Composition.MissingModels -> {
                    say("Thiếu file, đẩy lên bằng adb push:")
                    err.files.forEach { say("  adb push $it ${Composition.TMP}/") }
                }
                else -> say("Lỗi nạp mô hình: ${err.message}")
            }
            return
        }

        pipeline = engines.pipeline
        translator = engines.translator
        typeface = engines.typeface

        say("Sẵn sàng. Đang hâm nóng LLM (AD-20, mất 15–30 giây)...")
        val t = System.currentTimeMillis()
        runCatching { engines.translator.warmUp() }
            .onSuccess { say("LLM sẵn sàng sau ${(System.currentTimeMillis() - t) / 1000}s") }
            .onFailure { say("Lỗi nạp LLM: ${it.message}") }
    }

    /**
     * Duong dich tu FILE — chi con dung de go loi pipeline qua adb:
     *   adb shell am start -n app.mangatrans/.ui.MainActivity --ez auto true
     *
     * Duong that cua san pham la icon noi (Epic 3). Nut chon anh o man hinh
     * chinh da bo: nguoi dung khong can, va no lam man hinh roi.
     */
    private fun translate(file: File) = lifecycleScope.launch {
        val p = pipeline ?: run { say("Chưa sẵn sàng"); return@launch }
        runOnUiThread { log.text = "" }
        image.visibility = android.view.View.VISIBLE

        val src = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.absolutePath)?.copy(Bitmap.Config.ARGB_8888, true)
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
