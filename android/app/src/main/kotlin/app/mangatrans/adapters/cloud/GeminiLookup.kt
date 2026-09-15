package app.mangatrans.adapters.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tra nghia MOT cum chu Nhat bang Gemini — **cong cu tra tu, khong phai bo dich**.
 *
 * ⚠️ Rang buoc D1 ("offline hoan toan") VAN DUNG cho viec dich trang: no chay
 * tren may, khong goi ra ngoai. Cho nay la ngoai le duy nhat, va nguoi dung da
 * chot dung pham vi: *"offline van la mac dinh, cloud chi la cong cu tra tu"*.
 *
 *   - chi chay khi nguoi dung **tu bam** "Hoi Gemini";
 *   - chi gui **dung cum chu ho vua khoanh** — vai chu, khong bao gio ca trang,
 *     khong bao gio anh man hinh;
 *   - khong co khoa thi khong goi gi ca, va app van chay day du.
 *
 * Vi sao gioi han o mot cum tu: goi mien phi cua Google thuong cho phep dung du
 * lieu de huan luyen. Gui mot cum tu le thi muc phoi bay nho hon han ca trang —
 * va cung it bi bo loc noi dung chan hon.
 *
 * Dung `HttpURLConnection` co san, khong them thu vien: mot lan goi HTTP khong
 * dang de keo them phu thuoc vao app dang phai dem tung MB.
 */
class GeminiLookup(private val ctx: Context, private val apiKey: String) {

    companion object {
        private const val PREFS = "cloud"
        private const val KEY = "gemini_key"
        private const val MODEL_PREF = "gemini_model"

        /**
         * Ten model MAC DINH.
         *
         * ⚠️ Google khai tu ten model kha thuong xuyen — gap ngay o lan goi thu
         * dau tien trong doi:
         *
         * ```
         * 404  This model models/gemini-2.0-flash is no longer available.
         *      Please update your code to use models/gemini-3.6-flash
         * ```
         *
         * May chu **noi thang ten thay the** ngay trong cau loi. Nen thay vi bat
         * nguoi dung doi app moi lan Google doi ten, `meaningOf` doc ten do ra,
         * goi lai ngay va nho lai cho nhung lan sau.
         */
        private const val DEFAULT_MODEL = "gemini-3.6-flash"

        /** Bat `models/<ten>` trong cau loi de lay ten thay the. */
        private val MODEL_RE = Regex("models/([A-Za-z0-9.-]+)")

        /** Cho lay khoa mien phi — hien trong man hinh Cai dat. */
        const val KEY_URL = "https://aistudio.google.com/apikey"

        fun key(ctx: Context): String? =
            prefs(ctx).getString(KEY, null)

        fun setKey(ctx: Context, value: String) {
            prefs(ctx).edit().putString(KEY, value.trim()).apply()
        }

        private fun prefs(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private var model: String
        get() = prefs(ctx).getString(MODEL_PREF, null) ?: DEFAULT_MODEL
        set(v) { prefs(ctx).edit().putString(MODEL_PREF, v).apply() }

    suspend fun meaningOf(ja: String): Result<String> = withContext(Dispatchers.IO) {
        val first = model
        runCatching { call(ja, first) }.recoverCatching { e ->
            val alt = MODEL_RE.findAll(e.message.orEmpty())
                .map { it.groupValues[1] }
                .firstOrNull { it != first }
                ?: throw e
            call(ja, alt).also { model = alt }
        }
    }

    private fun call(ja: String, modelName: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "$modelName:generateContent?key=$apiKey"
        )
        // ⚠️ Hoi troc "cum nay nghia la gi" cho ket qua SAI. Do that voi
        // 「硬くしてる」:
        //   khong boi canh -> "Lam nham, dong dai"        (sai han)
        //   noi ro truyen tranh + nhan vat dang noi -> "Dang gong cung"  (dung)
        val prompt =
            "Trong truyện tranh Nhật, nhân vật nói: 「$ja」. " +
                "Cụm này nghĩa tiếng Việt là gì? " +
                "Trả lời NGẮN GỌN chỉ phần nghĩa, không giải thích, không xuống dòng."
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            // Chuyen nguyen van loi cua may chu: sai khoa, het han muc va sai
            // ten model la ba chuyen khac han nhau, nguoi dung can biet cai nao.
            val msg = runCatching {
                JSONObject(text).getJSONObject("error").getString("message")
            }.getOrDefault(text.take(200))
            error("máy chủ trả lỗi $code — $msg")
        }

        val out = runCatching {
            JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text").trim()
        }.getOrDefault("")
        if (out.isBlank()) error("Gemini trả về rỗng (có thể do bộ lọc nội dung)")
        return out
    }
}
