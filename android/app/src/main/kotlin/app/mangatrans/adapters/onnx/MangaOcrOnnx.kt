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

        val colour = Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
        // manga_ocr/ocr.py: `img.convert("L").convert("RGB")` — mo hinh duoc
        // huan luyen tren anh XAM. Trang mau khong sao, nhung khong co ly do gi
        // de dua mau vao mot mo hinh chua bao gio thay mau.
        val crop = toGrayscale(colour)
        if (colour !== src) colour.recycle()

        // ⚠️ Doc HAI LAN roi lay ban mo hinh TU TIN hon. Xem `readOnce`.
        val a = readOnce(scaleForOcr(crop, IMG, IMG))
        val b = readOnce(scaleForOcr(padToSquare(crop), IMG, IMG))
        if (crop !== src) crop.recycle()

        // Quy uoc spine: CHUAN HOA NFKC ngay tai day, truoc moi so sanh chuoi.
        // Khong lam thi khoa cache vo va cong AD-6 bao dong gia (F24).
        normalizeJa(if (b.second > a.second) b.first else a.first)
    }

    /** @return chu doc duoc va do tu tin trung binh moi token (log-prob). */
    private fun readOnce(scaled: Bitmap): Pair<String, Double> {
        val hidden = try { encode(scaled) } finally { scaled.recycle() }
        return decode(hidden)
    }

    /**
     * Bo khung anh cho VUONG bang mau nen cua chinh no, giu nguyen ty le.
     *
     * Vi sao can: `ViTImageProcessor` cua manga-ocr resize thang ve 224x224,
     * tuc **keo gian**. Hop chu bong thoai thuong cao gap 4 lan chieu ngang, nen
     * bi keo gian rat manh. Phan lon truong hop van doc dung — day dung la thu
     * mo hinh duoc huan luyen tren — nhung chu to, dam, cach dieu thi hong.
     *
     * Do duoc tren dung bong nguoi dung chi:
     * ```
     *   keo gian    -> ただの本名はじゃない…    SAI  (本名 = "ten that")
     *   chen vuong  -> ただの変態じゃない…      DUNG (変態 = "bien thai")
     * ```
     * Ban dich vi the ra "Khong phai ten that dau" — sai han nghia, ma nhin ban
     * dich thi khong co cach nao doan duoc no bat nguon tu day (F53).
     *
     * ⚠️ Nhung chen vuong KHONG phai lúc nao cung tot hon: bong ngan it chu thi
     * chen vuong lam chu nho lai va mo hinh doc thua ky tu — `キャッ!` thanh
     * `ハキャッし`. Nen khong the chon cung mot cach cho moi bong.
     */
    private fun padToSquare(src: Bitmap): Bitmap {
        val side = maxOf(src.width, src.height)
        if (side == src.width && side == src.height) return src
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(borderColour(src))
            drawBitmap(src, ((side - src.width) / 2).toFloat(), ((side - src.height) / 2).toFloat(), null)
        }
        return out
    }

    /** Mau hay gap nhat tren vien anh — coi nhu mau nen. */
    private fun borderColour(b: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        val stepX = maxOf(1, b.width / 16)
        val stepY = maxOf(1, b.height / 16)
        var x = 0
        while (x < b.width) {
            counts.merge(b.getPixel(x, 0), 1, Int::plus)
            counts.merge(b.getPixel(x, b.height - 1), 1, Int::plus)
            x += stepX
        }
        var y = 0
        while (y < b.height) {
            counts.merge(b.getPixel(0, y), 1, Int::plus)
            counts.merge(b.getPixel(b.width - 1, y), 1, Int::plus)
            y += stepY
        }
        return counts.maxByOrNull { it.value }?.key ?: android.graphics.Color.WHITE
    }

    /** Doi sang xam, giu 3 kenh — dung nhu `convert("L").convert("RGB")`. */
    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setSaturation(0f) }
            )
        }
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Thu nho anh ve 224x224 **co chong rang cua**.
     *
     * ⚠️ `Bitmap.createScaledBitmap(..., filter = true)` nghe nhu da loc roi,
     * nhung bilinear cua Android chi lay 2x2 diem lan can. Thu nho 2-3 lan mot
     * luc thi phan lon diem anh **khong duoc nhin den**, va net chu manh bien
     * mat. Hop chu bong thoai cao 400-550 px ha xuong 224 la thu nho 2-2,5 lan,
     * dung vung hong nhat.
     *
     * Do duoc, chay chinh encoder/decoder nay tren PC voi dung tam anh may da
     * chup, mot bong thoai that:
     * ```
     *   bilinear tho (nhu cu)      -> 予末らかいものに…     <- SAI
     *   co chong rang cua          -> 柔らかいものに…       <- DUNG
     * ```
     * `予末` va `柔` la hai chu khac han nhau; nguoi dung thay ban dich noi
     * "thu gi do dang so" thay vi "thu mem mai" ma khong hieu tu dau ra (F50).
     *
     * Cach chua khong can thu vien ngoai: **ha dan tung nua**. Ha mot nua bang
     * bilinear tuong duong lay trung binh 2x2, tuc moi diem anh deu duoc tinh
     * den. Lap den khi con trong pham vi 2x cua dich roi ha not.
     *
     * Chi ha nua theo TRUC NAO DANG DAI — hop chu bong thoai thuong hep va cao,
     * ha ca hai truc se lam nhoe truc von da phai phong to.
     */
    private fun scaleForOcr(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width > 2 * w || cur.height > 2 * h) {
            val nw = maxOf(w, (cur.width + 1) / 2)
            val nh = maxOf(h, (cur.height + 1) / 2)
            if (nw == cur.width && nh == cur.height) break
            val next = Bitmap.createScaledBitmap(cur, nw, nh, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
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
    /**
     * Giai ma tham lam, dong thoi do **do tu tin** = log-prob trung binh moi
     * token. Day la thu duy nhat manga-ocr cho biet ve chat luong mot lan doc —
     * no khong tra diem tin cay nao khac — va no du de chon giua hai cach cat
     * anh (xem `padToSquare`).
     */
    private fun decode(hidden: Array<Array<FloatArray>>): Pair<String, Double> {
        val ids = ArrayList<Long>(MAX_TOKENS).apply { add(clsId.toLong()) }
        var logProb = 0.0
        var steps = 0
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
                            // log-softmax cua token vua chon, tinh on dinh so hoc.
                            var sum = 0.0
                            for (v in last) sum += kotlin.math.exp((v - bestV).toDouble())
                            logProb += -kotlin.math.ln(sum)
                            steps++
                            if (best == sepId) return buildText(ids) to (logProb / steps)
                            ids.add(best.toLong())
                        }
                    } finally {
                        maskT?.close()
                    }
                }
            }
        }
        return buildText(ids) to (if (steps == 0) -99.0 else logProb / steps)
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
