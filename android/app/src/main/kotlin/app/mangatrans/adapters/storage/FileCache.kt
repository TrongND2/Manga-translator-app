package app.mangatrans.adapters.storage

import android.graphics.Bitmap
import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.BubbleState
import app.mangatrans.domain.RegionKind
import app.mangatrans.ports.PageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * FR-060..062 + AD-18 + AD-24.
 *
 * AD-24 nang gia tri cua cache len han mot bac: no la thu DUY NHAT giam duoc
 * ca ba thu cung luc — thoi gian, RAM va nhiet. Trang da dich phai tra ra ngay
 * o trang thai Cold, KHONG danh thuc engine.
 *
 * AD-18 — va cham `contentKey` dan toi VE SAI TRANG, la loi khong the chap nhan.
 * Vi vay moi muc cache luu kem danh sach bounding box va DOI CHIEU LAI truoc khi
 * dung; lech thi coi nhu cache miss.
 */
class FileCache(
    private val dir: File,
    private val maxBytes: Long = 64L * 1024 * 1024,
) : PageCache {

    override suspend fun get(contentKey: String, boxes: List<Box>): List<Bubble>? =
        withContext(Dispatchers.IO) {
            val f = File(dir, "$contentKey.json")
            if (!f.exists()) return@withContext null
            runCatching {
                val o = JSONObject(f.readText())
                // AD-18 — doi chieu box truoc khi tin. Va cham khoa => ve sai trang.
                if (o.getString("boxSig") != boxSignature(boxes)) return@withContext null
                f.setLastModified(System.currentTimeMillis())   // cho LRU
                val arr = o.getJSONArray("bubbles")
                buildList {
                    for (i in 0 until arr.length()) {
                        val b = arr.getJSONObject(i)
                        add(Bubble(
                            id = b.getInt("id"),
                            box = Box(b.getInt("x1"), b.getInt("y1"), b.getInt("x2"), b.getInt("y2")),
                            kind = RegionKind.valueOf(b.getString("kind")),
                            detectScore = b.getDouble("score").toFloat(),
                            state = BubbleState.valueOf(b.getString("state")),
                            ja = b.optString("ja"),
                            vi = b.optString("vi").takeIf { it.isNotBlank() },
                            speaker = b.optString("speaker").takeIf { it.isNotBlank() },
                        ))
                    }
                }
            }.getOrNull()
        }

    override suspend fun put(contentKey: String, boxes: List<Box>, bubbles: List<Bubble>) =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val arr = JSONArray()
            bubbles.forEach { b ->
                arr.put(JSONObject().apply {
                    put("id", b.id); put("x1", b.box.x1); put("y1", b.box.y1)
                    put("x2", b.box.x2); put("y2", b.box.y2)
                    put("kind", b.kind.name); put("score", b.detectScore)
                    put("state", b.state.name); put("ja", b.ja)
                    b.vi?.let { put("vi", it) }
                    b.speaker?.let { put("speaker", it) }
                })
            }
            File(dir, "$contentKey.json").writeText(
                JSONObject().apply {
                    put("boxSig", boxSignature(boxes))
                    put("bubbles", arr)
                }.toString()
            )
            evictIfNeeded()
        }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** FR-062 — day thi don ban cu nhat. */
    private fun evictIfNeeded() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun boxSignature(boxes: List<Box>): String =
        sha1(boxes.joinToString(";") { "${it.x1},${it.y1},${it.x2},${it.y2}" })
}

/**
 * AD-18 — HAI hash, hai vai, yeu cau NGUOC nhau. Khong duoc dung lan.
 */
object PageHash {

    /**
     * NHAY — phat hien noi dung ben duoi da doi (AD-12).
     *
     * Tinh tren khung DA CAT status bar: tu Android 15 QPR1, he dieu hanh ve
     * chip "dang chia se man hinh" khong an duoc, va dong ho nhay phut cung
     * nam o do. Khong cat thi lop phu bi xoa oan moi phut.
     */
    fun frameHash(bmp: Bitmap, cropTopPx: Int): String {
        val step = maxOf(1, bmp.width / 64)
        val sb = StringBuilder()
        var y = cropTopPx
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                sb.append(bmp.getPixel(x, y) shr 4)   // bo 4 bit thap cho do nhieu
                x += step
            }
            y += step
        }
        return sha1(sb.toString())
    }

    /**
     * BEN — khoa cache (FR-060).
     *
     * Tinh CHI tren vung cac bubble da phat hien, tren anh da ha mau. Nho vay
     * khong bi anh huong boi dong ho, muc pin, hay khac biet nen nho.
     */
    fun contentKey(bmp: Bitmap, boxes: List<Box>): String {
        if (boxes.isEmpty()) return sha1("empty")
        val sb = StringBuilder()
        for (b in boxes.sortedWith(compareBy({ it.y1 }, { it.x1 }))) {
            val sx = maxOf(1, b.width / 8)
            val sy = maxOf(1, b.height / 8)
            var y = b.y1
            while (y < b.y2) {
                var x = b.x1
                while (x < b.x2) {
                    if (x in 0 until bmp.width && y in 0 until bmp.height) {
                        // Chi giu 3 bit cao moi kenh — ben voi nhieu nen va nen JPEG.
                        sb.append(bmp.getPixel(x, y) shr 5)
                    }
                    x += sx
                }
                y += sy
            }
            sb.append('|')
        }
        return sha1(sb.toString())
    }

    // ---------- chu ky khung hinh (Story 3.7) ----------

    /** Luoi lay mau. Co dinh, khong phu thuoc kich thuoc man hinh. */
    private const val COLS = 16
    private const val ROWS = 32

    /** Moi o lay SUB x SUB diem roi lay trung binh — ben voi nhieu hon lay mot diem. */
    private const val SUB = 3

    /**
     * Chu ky do xam cua khung hinh, **da chuan hoa nen bat bien voi do sang**.
     *
     * Vi sao khong dung `frameHash` cho viec canh trang nua: no la ma bam CHINH
     * XAC, nen doi mot bit la khac. Ma man hinh **tu giam sang** truoc khi tat
     * lam doi TOAN BO pixel — va app hieu thanh "nguoi dung sang trang" roi xoa
     * mat ban dich.
     *
     * Do duoc tren M52, ban dich dung yen 65 giay roi bien mat:
     * ```
     * 01:46:04  DeviceType: isSupportBrightnessControl   <- man tu mo di
     * 01:46:05  CaptureSvc: noi dung ben duoi doi — go lop phu
     * ```
     *
     * Giam sang xap xi mot phep bien doi tuyen tinh `v -> a*v + b` tren moi
     * pixel. Chuan hoa ve trung binh 0 va do lech chuan 1 thi **triet tieu ca a
     * lan b**, nen anh mo di cho ra gan nhu dung chu ky cu; con sang trang thi
     * hinh doi that nen chu ky doi that.
     */
    fun frameSignature(bmp: Bitmap, cropTopPx: Int): FloatArray {
        val top = cropTopPx.coerceIn(0, maxOf(0, bmp.height - 1))
        val h = bmp.height - top
        val out = FloatArray(COLS * ROWS)
        var i = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                var sum = 0f
                var n = 0
                for (sy in 0 until SUB) {
                    val y = top + ((r * SUB + sy).toLong() * h / (ROWS * SUB)).toInt()
                    for (sx in 0 until SUB) {
                        val x = ((c * SUB + sx).toLong() * bmp.width / (COLS * SUB)).toInt()
                        if (x in 0 until bmp.width && y in 0 until bmp.height) {
                            val p = bmp.getPixel(x, y)
                            // Do xam xap xi, khong can dung chuan — chi can nhat quan.
                            sum += ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 151 +
                                (p and 0xFF) * 28) shr 8
                            n++
                        }
                    }
                }
                out[i++] = if (n == 0) 0f else sum / n
            }
        }
        var mean = 0f
        for (v in out) mean += v
        mean /= out.size
        var varSum = 0f
        for (v in out) { val d = v - mean; varSum += d * d }
        val sd = kotlin.math.sqrt(varSum / out.size)
        // Man hinh mot mau tron (dang chuyen canh, hay da tat) thi sd ~ 0. Chia
        // cho no la ra vo nghia; giu nguyen 0 de hai khung nhu the coi la giong.
        val k = if (sd < 1e-3f) 0f else 1f / sd
        for (j in out.indices) out[j] = (out[j] - mean) * k
        return out
    }

    /**
     * Khoang cach giua hai chu ky — trung binh tri tuyet doi cua hieu.
     *
     * Don vi la "do lech chuan", nen nguong khong phu thuoc do sang hay bo truyen.
     */
    fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return Float.MAX_VALUE
        var s = 0f
        for (i in a.indices) s += kotlin.math.abs(a[i] - b[i])
        return s / a.size
    }
}

private fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
