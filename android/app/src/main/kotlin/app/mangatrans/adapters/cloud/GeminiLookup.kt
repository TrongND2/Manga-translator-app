package app.mangatrans.adapters.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tra nghia MOT cum chu Nhat bang Gemini — **cong cu tra tu, khong phai bo dich**.
 *
 * ⚠️ Rang buoc D1 cua du an la "offline hoan toan", va no VAN DUNG: dich trang
 * chay tren may, khong goi ra ngoai. Cho nay la ngoai le duy nhat, va nguoi dung
 * da dong y voi dung pham vi nay:
 *
 *   - chi chay khi nguoi dung **tu bam** "Hoi Gemini";
 *   - chi gui **dung cum chu ho vua khoanh** — vai chu, khong phai ca trang,
 *     khong bao gio la anh man hinh;
 *   - khong co khoa thi khong goi gi ca, va app van chay day du.
 *
 * Goi nguoi dung nen biet: goi mien phi cua Google thuong cho phep dung du lieu
 * de huan luyen. Gui mot cum tu le thi muc phoi bay nho hon han gui ca trang —
 * do chinh la ly do gioi han pham vi o day.
 *
 * Dung `HttpURLConnection` co san, khong them thu vien: mot lan goi HTTP khong
 * dang de keo them phu thuoc vao mot app dang phai dem tung MB.
 */
class GeminiLookup(private val apiKey: String) {

    companion object {
        private const val PREFS = "cloud"
        private const val KEY = "gemini_key"

        /**
         * Ten model. Google doi ten model kha thuong xuyen, va khi sai ten thi
         * may chu tra ve loi ro rang — `meaningOf` chuyen nguyen van loi do ra
         * man hinh de nguoi dung (hoac lan sua sau) biet duong sua.
         */
        private const val MODEL = "gemini-2.0-flash"

        fun key(ctx: Context): String? =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

        fun setKey(ctx: Context, value: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, value.trim()).apply()
        }
    }

    suspend fun meaningOf(ja: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$MODEL:generateContent?key=$apiKey"
            )
            val prompt =
                "Cụm tiếng Nhật trong truyện tranh: 「$ja」\n" +
                    "Cho nghĩa tiếng Việt NGẮN GỌN để đưa vào từ điển thuật ngữ. " +
                    "Chỉ trả về nghĩa, không giải thích, không xuống dòng."
            val body = JSONObject().apply {
                put("contents", org.json.JSONArray().put(JSONObject().apply {
                    put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))
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

            val out = JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text").trim()
            if (out.isBlank()) error("Gemini trả về rỗng (có thể do bộ lọc nội dung)")
            out
        }
    }
}
