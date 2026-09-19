package app.mangatrans

import android.content.Context
import android.graphics.Typeface
import app.mangatrans.adapters.litertlm.LiteRtLmTranslator
import app.mangatrans.adapters.onnx.MangaOcrOnnx
import app.mangatrans.adapters.onnx.OnnxTextDetector
import app.mangatrans.adapters.assets.ModelStore
import app.mangatrans.adapters.storage.FileCache
import app.mangatrans.adapters.storage.JsonGlossaryStore
import app.mangatrans.pipeline.BubbleRenderer
import app.mangatrans.pipeline.Pipeline
import app.mangatrans.pipeline.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Goc lap rap — NOI DUY NHAT duoc noi adapter vao pipeline.
 *
 * Spine cam `pipeline` va `ui` import thang `adapters`; chung chi duoc nhan qua
 * tiem phu thuoc. Day la tang tiem do.
 *
 * Vi sao tach ra khoi `MainActivity`: `CaptureService` cua Epic 3 can y HET
 * cach nap nay. Hai ban sao se troi khoi nhau — va chung se troi o cho kho
 * thay nhat, la danh sach file mo hinh du phong.
 */
object Composition {

    const val TMP = "/data/local/tmp"

    /** Bo tu dien mau di kem APK — xem `seedGlossaryIfEmpty`. */
    const val SEED_ASSET = "glossary_seed.json"

    /**
     * ONNX Runtime ban Android KHONG co `ConvInteger` — op ma ca ba ban luong tu
     * cua encoder (`_int8` / `_quantized` / `_uint8`) deu dung o lop patch
     * embedding. Loi chi lo ra TREN MAY; tren PC chay binh thuong vi do la ban
     * day du. Nen phai dung encoder fp16 hoac fp32.
     */
    private val ENCODERS = listOf(
        "encoder_model_fp16.onnx",   // 172 MB, khong co op luong tu
        "encoder_model.onnx",        // 343 MB, du phong
    )

    /** Decoder int8 dung `MatMulInteger` — op nay ORT Android CO ho tro. */
    private val DECODERS = listOf(
        "decoder_model_int8.onnx",   // 29.6 MB
        "decoder_model.onnx",
    )

    private val REQUIRED = listOf(
        "detector-v4-s_int8.onnx",
        "vocab.txt",
        "gemma-4-E2B-it.litertlm",
    )

    class Engines(
        val pipeline: Pipeline,
        val translator: LiteRtLmTranslator,
        /**
         * Dung rieng cho chuc nang "lay chu trong vung tu chon" — doc mot vung
         * nguoi dung khoanh, khong di qua ca day chuyen dich.
         *
         * ⚠️ Phien ONNX bi dong sau moi luot dich (F37) va tu nap lai khi can,
         * nen goi truc tiep o day van an toan.
         */
        val ocr: MangaOcrOnnx,
        val typeface: Typeface,
        /** Font co du dau tieng Viet khong (FR-043). */
        val fontOk: Boolean,
    )

    /** File con thieu — `ui` dung de bao nguoi dung lenh `adb push` can chay. */
    class MissingModels(val files: List<String>) : Exception("thieu ${files.size} file mo hinh")

    private val lock = Mutex()

    @Volatile private var shared: Engines? = null

    /**
     * Engine dung chung CHO CA TIEN TRINH.
     *
     * ⚠️ Do thay tren may that: `MainActivity` va `CaptureService` song trong
     * CUNG mot tien trinh, va moi ben tu nap mot bo. Log chung minh:
     *
     * ```
     * 11:44:18.161 31347 31378 I MangaTrans: encoder: encoder_model_fp16.onnx (171 MB)
     * 11:44:18.467 31347 31380 I CaptureSvc: encoder: encoder_model_fp16.onnx (171 MB)
     * ```
     *
     * Cung pid, khac thread => 2 x 171 MB ONNX + 2 x 2.6 GB LLM tren may 8 GB.
     *
     * `AtomicBoolean` trong `MainActivity` khong cuu duoc: no chi chan Activity
     * tu nap lai chinh no. Chot phai nam o TIEN TRINH, tuc la o day.
     *
     * `Mutex` chu khong phai `@Synchronized`: nap la viec `suspend`, va hai ben
     * goi gan nhu cung luc thi ben den sau phai CHO ban dau tien xong roi dung
     * chung, chu khong duoc nap song song.
     */
    suspend fun engines(ctx: Context, say: (String) -> Unit): Engines {
        shared?.let { return it }
        return lock.withLock {
            shared ?: build(ctx.applicationContext, say).also { shared = it }
        }
    }

    /**
     * Nap detector + OCR + LLM. **Khong** ham nong — nguoi goi tu quyet dinh
     * luc nao goi `warmUp()`, vi AD-24 cho phep nha engine khi ranh.
     *
     * @param say noi tien trinh ra ngoai. KHONG ghi noi dung anh hay chu da OCR
     *   vao day — do la noi dung man hinh rieng cua nguoi dung.
     */
    private suspend fun build(ctx: Context, say: (String) -> Unit): Engines = withContext(Dispatchers.IO) {
        // Epic 4 — tim o kho cua app TRUOC, roi moi den /data/local/tmp.
        // Nho thu tu do, goi da tai ve thang goi day tay bang `adb push`, ma
        // duong day tay van con dung duoc cho moi lenh thu trong handoff.
        val store = ModelStore(ctx, appVersion(ctx))

        val enc = ENCODERS.firstNotNullOfOrNull { store.find(it) }
        val dec = DECODERS.firstNotNullOfOrNull { store.find(it) }

        val missing = REQUIRED.filterNot { store.find(it) != null }.toMutableList()
        if (enc == null) missing += ENCODERS.first()
        if (dec == null) missing += DECODERS.first()
        if (missing.isNotEmpty()) throw MissingModels(missing)

        say("Nạp detector + OCR...")
        say("  encoder: ${enc!!.name} (${enc.length() / 1_000_000} MB)")
        say("  decoder: ${dec!!.name} (${dec.length() / 1_000_000} MB)")

        val det = OnnxTextDetector(store.find("detector-v4-s_int8.onnx")!!.absolutePath)
        val vocab = store.find("vocab.txt")!!.readLines()
        val ocr = MangaOcrOnnx(enc.absolutePath, dec.absolutePath, vocab)

        // AD-25: 2 luong CPU (58-62 do C thay vi 76-84).
        // AD-2: CPU la duong DUY NHAT dung duoc tren Adreno 642L.
        val cfg = PipelineConfig()
        val translator = LiteRtLmTranslator(
            store.find("gemma-4-E2B-it.litertlm")!!.absolutePath, cfg,
            cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
        )

        val pipeline = Pipeline(
            detector = det,
            ocr = ocr,
            translator = translator,
            glossary = JsonGlossaryStore(glossaryFile(ctx)),
            cache = FileCache(File(ctx.cacheDir, "pages")),
            cfg = cfg,
            // Nha ONNX truoc khi LLM chay. Lan sau chung tu nap lai — mat vai
            // giay, doi lai la khong bi Android giet giua chung (F37).
            onVisionDone = {
                withContext(Dispatchers.IO) {
                    runCatching { det.close() }
                    runCatching { ocr.close() }
                }
            },
            // AD-24 dung nghia den: LLM chi song trong luc dich, khong song
            // trong luc nhin. `translate()` tu goi `warmUp()` khi can nen khong
            // ai phai nho bat lai.
            onVisionStart = { runCatching { translator.release() } },
        )

        val typeface = Typeface.SANS_SERIF
        val fontOk = BubbleRenderer.supportsVietnamese(typeface)
        say(if (fontOk) "Font: có đủ dấu tiếng Việt ✓" else "CẢNH BÁO: font thiếu dấu tiếng Việt")

        Engines(pipeline, translator, ocr, typeface, fontOk)
    }

    /**
     * `versionCode` cua app — AD-15 dung no de doi chieu voi khoang tuong thich
     * khai trong manifest cua goi mo hinh.
     */
    fun appVersion(ctx: Context): Int = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(1)

    /** Mot cho duy nhat quyet dinh glossary nam o dau. */
    fun glossaryFile(ctx: Context): File = File(ctx.filesDir, "glossary.json")

    /**
     * Nap glossary mau neu co va app chua co file rieng — F9 do duoc glossary
     * keo chat luong tu 46% len 60%, nen de trong la phi.
     *
     *   adb push glossary_seed.json /data/local/tmp/
     */
    fun seedGlossaryIfEmpty(ctx: Context, say: (String) -> Unit) {
        val target = glossaryFile(ctx)
        if (target.exists()) return
        target.parentFile?.mkdirs()

        // Duong cua nguoi phat trien: day file de chen bo rieng khi thu nghiem.
        val fromAdb = File(TMP, "glossary_seed.json")
        if (fromAdb.exists()) {
            fromAdb.copyTo(target, overwrite = true)
            say("Đã nạp glossary mẫu từ ${fromAdb.name}")
            return
        }

        // ⚠️ Duong cua NGUOI DUNG THAT — bo tu mau dong san trong APK.
        //
        // Truoc day chi co duong `adb` o tren, tuc la nguoi dung binh thuong
        // **luon bat dau voi tu dien rong**. Ma do tren may thi tu dien la thu
        // chua duoc nhieu loi dich nhat: rieng viec them `ちんこ` da sua 4 cho
        // dich thanh "cái chuông" tren mot trang (F82).
        runCatching {
            ctx.assets.open(SEED_ASSET).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onSuccess {
            say("Đã nạp bộ từ điển mẫu đi kèm app")
        }.onFailure {
            say("Không nạp được từ điển mẫu: ${it.javaClass.simpleName}")
        }
    }
}
