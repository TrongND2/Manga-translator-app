package app.mangatrans.adapters.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import app.mangatrans.domain.Box
import app.mangatrans.ports.OcrEngine
import app.mangatrans.ports.PageImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Buoc 3 — doc chu Nhat. FR-022, FR-024.
 *
 * Model: `onnx-community/manga-ocr-base-ONNX`, ban `_int8`. 117 MB, Apache-2.0.
 * Da do (F24): CER 0.3% so voi fp32, nhanh gap doi, nho hon 4 lan.
 * KHONG phai tu convert — ban int8 co san tren HF.
 *
 * Kien truc VisionEncoderDecoder: encoder (ViT) chay MOT lan cho moi anh,
 * decoder lap tung buoc sinh token. Khong co ham `generate()` trong ONNX
 * Runtime nen vong lap phai viet tay.
 */
class MangaOcrOnnx(
    private val encoderPath: String,
    private val decoderPath: String,
    private val vocab: List<String>,
    private val clsId: Int = 2,
    private val sepId: Int = 3,
) : OcrEngine, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    // Nap tu DUONG DAN. `readBytes()` tren file 171 MB lam OOM heap Java (256 MB).
    /**
     * Hai session nap LUOI va **nap lai duoc**.
     *
     * Truoc day la `val` tao trong ham khoi tao: dong mot lan la hong han. Ma
     * nha ra dung luc la dieu can lam — OCR xong viec TRUOC khi LLM chay, va
     * LLM la thu suyt lam app bi Android giet (F37). Encoder fp16 mot minh da
     * 172 MB.
     */
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null

    private fun encoder(): OrtSession =
        encoder ?: env.createSession(encoderPath, OnnxOptions.lean())
            .also { encoder = it }

    private fun decoder(): OrtSession =
        decoder ?: env.createSession(decoderPath, OnnxOptions.lean())
            .also { decoder = it }

    /** Ten dau vao cua decoder — doc mot lan roi nho, khoi phai mo session chi de hoi. */
    private var decoderInputsCache: Set<String>? = null
    private val decoderInputs: Set<String>
        get() = decoderInputsCache ?: decoder().inputNames.toSet()
            .also { decoderInputsCache = it }

    private companion object {
        const val IMG = 224                 // ViT input, tu preprocessor_config.json
        const val MAX_TOKENS = 300
        /** Padding quanh box truoc khi cat — chu sat vien de bi doc thieu. */
        const val PAD_PX = 2
    }

    override suspend fun read(image: PageImage, box: Box): String = withContext(Dispatchers.Default) {
        val src = image.handle as Bitmap
        val x1 = (box.x1 - PAD_PX).coerceIn(0, src.width)
        val y1 = (box.y1 - PAD_PX).coerceIn(0, src.height)
        val x2 = (box.x2 + PAD_PX).coerceIn(0, src.width)
        val y2 = (box.y2 + PAD_PX).coerceIn(0, src.height)
        if (x2 - x1 < 2 || y2 - y1 < 2) return@withContext ""

        val crop = Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
        val scaled = Bitmap.createScaledBitmap(crop, IMG, IMG, true)
        if (crop !== src) crop.recycle()

        val hidden = try {
            encode(scaled)
        } finally {
            scaled.recycle()
        }

        val text = decode(hidden)
        // Quy uoc spine: CHUAN HOA NFKC ngay tai day, truoc moi so sanh chuoi.
        // Khong lam thi khoa cache vo va cong AD-6 bao dong gia (F24).
        normalizeJa(text)
    }

    /** ViT chuan hoa mean=std=0.5 theo preprocessor_config.json cua manga-ocr. */
    private fun encode(bmp: Bitmap): Array<Array<FloatArray>> {
        val px = IntArray(IMG * IMG)
        bmp.getPixels(px, 0, IMG, 0, 0, IMG, IMG)
        val plane = IMG * IMG
        val buf = FloatBuffer.allocate(3 * plane)
        val a = buf.array()
        for (i in 0 until plane) {
            val p = px[i]
            a[i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            a[plane + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            a[2 * plane + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, IMG.toLong(), IMG.toLong())).use { t ->
            encoder().run(mapOf("pixel_values" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return r[0].value as Array<Array<FloatArray>>
            }
        }
    }

    /** Vong lap sinh token: greedy, dung khi gap SEP. */
    private fun decode(hidden: Array<Array<FloatArray>>): String {
        val ids = ArrayList<Long>(MAX_TOKENS).apply { add(clsId.toLong()) }
        val seqLen = hidden[0].size
        val hiddenDim = hidden[0][0].size

        val hBuf = FloatBuffer.allocate(seqLen * hiddenDim)
        for (row in hidden[0]) hBuf.put(row)
        hBuf.rewind()

        repeat(MAX_TOKENS) {
            OnnxTensor.createTensor(
                env, hBuf.duplicate(), longArrayOf(1, seqLen.toLong(), hiddenDim.toLong())
            ).use { hT ->
                OnnxTensor.createTensor(
                    env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong())
                ).use { iT ->
                    val feed = mutableMapOf<String, OnnxTensor>(
                        "input_ids" to iT, "encoder_hidden_states" to hT
                    )
                    var maskT: OnnxTensor? = null
                    if ("encoder_attention_mask" in decoderInputs) {
                        maskT = OnnxTensor.createTensor(
                            env, LongBuffer.wrap(LongArray(seqLen) { 1L }),
                            longArrayOf(1, seqLen.toLong())
                        )
                        feed["encoder_attention_mask"] = maskT
                    }
                    try {
                        decoder().run(feed).use { r ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = r[0].value as Array<Array<FloatArray>>
                            val last = logits[0].last()
                            var best = 0; var bestV = Float.NEGATIVE_INFINITY
                            for (i in last.indices) if (last[i] > bestV) { bestV = last[i]; best = i }
                            if (best == sepId) return buildText(ids)
                            ids.add(best.toLong())
                        }
                    } finally {
                        maskT?.close()
                    }
                }
            }
        }
        return buildText(ids)
    }

    private fun buildText(ids: List<Long>): String = ids
        .drop(1)                                      // bo CLS
        .mapNotNull { vocab.getOrNull(it.toInt()) }
        .filterNot { it.startsWith("[") && it.endsWith("]") }   // bo token dac biet
        .joinToString("") { it.removePrefix("##") }
        .replace(" ", "")

    override fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
    }
}

/**
 * Chuan hoa chu Nhat — quy uoc BAT BUOC cua spine.
 *
 * Da do (F24): cung mot vung, ban int8 tra `...` con fp32 tra `．．．`,
 * `?` vs `？`. Khong chuan hoa thi CER "tho" la 7.6%; sau chuan hoa con 0.3%.
 * Va quan trong hon: khoa cache se vo, cong AD-6 se bao dong gia.
 */
fun normalizeJa(s: String): String {
    val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
    val sb = StringBuilder(nfkc.length)
    var prev = '\u0000'
    for (ch in nfkc) {
        // Gop moi day gach ngang lien tiep thanh mot, tuong tu voi dau cham.
        val c = if (ch in "ーー-—―‐") '-' else ch
        if (c == '-' && c == prev) continue
        sb.append(c); prev = c
    }
    // ⚠️ Day dau cham PHAI gop thanh dau ba cham, KHONG phai mot dau cham.
    //
    // NFKC bien `…` (U+2026) thanh ba dau cham. Ban truoc gop moi day dau
    // cham lien tiep thanh MOT — tuc `…` bi bien thanh `.`, va cau bo lung
    // thanh cau tron ven **truoc khi mo hinh kip nhin thay**.
    //
    // Do duoc tren may, doi chieu tung bong voi trang goc:
    //   tren trang   `な、なんで わたしとその…`
    //   mo hinh thay `な、なんでわたしとその.`
    //   dich ra      "Sao lai la toi va anh?"   <- tu bia ra phan con thieu
    //
    // Bo lung la tin hieu quan trong nhat cua thoai manga: nhan vat ngap ngung,
    // noi hut, bi cat loi. Xoa no di thi mo hinh buoc phai doan (F46).
    return sb.toString().replace(Regex("\\.{2,}"), "…").trim()
}
