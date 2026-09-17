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
         * ⚠️ Hai ly do chon dung ban **-lite**, va ca hai deu do duoc:
         *
         * **1. Han muc.** Goi mien phi cua Google tinh theo TUNG model, va
         * Google **da bo han bang so khoi tai lieu** — chi con xem duoc o
         * AI Studio cua tung tai khoan. Nguon do duoc ngoai: dong `flash`
         * thuong ~20 luot/ngay, dong `flash-lite` ~500. Nguoi dung bao "dung
         * tren app thi bao het luot" trong khi chat Gemini van thoai mai —
         * dung la vi app an vao han muc API, tach hoan toan khoi han muc cua
         * san pham chat.
         *
         * **2. May chu do rong.** Do that voi khoa cua nguoi dung, 3 cum tu:
         * ```
         *   gemini-3.6-flash        2/3 lan tra HTTP 503  |  5,2 s
         *   gemini-flash-lite-latest 3/3 lan OK           |  0,9 s
         * ```
         * 503 chinh la man hinh "high demand" nguoi dung gap.
         *
         * Chat luong cho viec TRA MOT CUM TU thi khong thua: ca hai ban lite
         * deu dich dung 「今日は杏彼氏と会うって言ってなかった?」 (giu duoc
         * phu dinh — thu ma Gemma tren may lam hong), va deu KHONG bia ten
         * "Rurimaru" vao 「溢れちゃってりゅ…」 nhu loi F65.
         *
         * Dung ban `-latest` chu khong ghim so: F63 da dinh mot lan model bi
         * khai tu ngay lan goi dau tien. Bi danh so thi som muon cung chet;
         * `-latest` tu di theo ban moi. Duong tu chua 404 ben duoi van giu lam
         * luoi do phong.
         */
        private const val DEFAULT_MODEL = "gemini-flash-lite-latest"

        /** Bat `models/<ten>` trong cau loi de lay ten thay the. */
        private val MODEL_RE = Regex("models/([A-Za-z0-9.-]+)")

        /** Cho lay khoa mien phi — hien trong man hinh Cai dat. */
        const val KEY_URL = "https://aistudio.google.com/apikey"

        /**
         * Khoa da ma hoa. O cu [KEY] chi con de **doc mot lan roi xoa**.
         */
        private const val KEY_ENC = "gemini_key_enc"

        /** Ten khoa AES nam trong Keystore cua may. */
        private const val ALIAS = "mangatrans_gemini_v1"
        private const val KEYSTORE = "AndroidKeyStore"

        /**
         * Doc khoa API.
         *
         * ⚠️ Truoc day khoa nam **nguyen van** trong `shared_prefs/cloud.xml`.
         * Ai cam duoc may (co USB debugging bat) la doc duoc bang mot lenh:
         * `run-as app.mangatrans cat shared_prefs/cloud.xml`. Gio no duoc ma
         * hoa bang mot khoa AES nam trong Keystore cua may — khoa do **khong
         * lay ra khoi may duoc**, nen chep file ra ngoai cung khong doc noi.
         *
         * Gioi han phai noi ro: chung nao app con `DEBUGGABLE`, nguoi gan
         * duoc debugger vao tien trinh van doc duoc — vi luc do chinh app giai
         * ma ho. Lop nay chan nguoi CHEP FILE, khong chan nguoi GAN DEBUGGER.
         */
        fun key(ctx: Context): String? {
            val p = prefs(ctx)
            // Doi cho khoa cu sang dang ma hoa, roi xoa ban nguyen van di.
            p.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { old ->
                setKey(ctx, old)
                return old
            }
            val blob = p.getString(KEY_ENC, null) ?: return null
            return runCatching { decrypt(blob) }.getOrElse {
                // Khoa Keystore mat (go app, khoi phuc may, doi khoa man hinh).
                // Khong cuu duoc — don di de nguoi dung nhap lai.
                p.edit().remove(KEY_ENC).apply()
                null
            }
        }

        fun setKey(ctx: Context, value: String) {
            val v = value.trim()
            val e = prefs(ctx).edit().remove(KEY)   // ban nguyen van khong bao gio quay lai
            if (v.isEmpty()) e.remove(KEY_ENC) else e.putString(KEY_ENC, encrypt(v))
            e.apply()
        }

        private fun secret(): javax.crypto.SecretKey {
            val ks = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)
                ?.let { return it.secretKey }
            val gen = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, KEYSTORE,
            )
            gen.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(
                        android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
                    )
                    .build()
            )
            return gen.generateKey()
        }

        /** Tra ve `base64(iv):base64(ban ma)`. */
        private fun encrypt(plain: String): String {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, secret())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            val b64 = android.util.Base64.NO_WRAP
            return android.util.Base64.encodeToString(c.iv, b64) + ":" +
                android.util.Base64.encodeToString(ct, b64)
        }

        private fun decrypt(blob: String): String {
            val (ivB64, ctB64) = blob.split(":", limit = 2)
            val b64 = android.util.Base64.NO_WRAP
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                javax.crypto.Cipher.DECRYPT_MODE, secret(),
                javax.crypto.spec.GCMParameterSpec(128, android.util.Base64.decode(ivB64, b64)),
            )
            return String(c.doFinal(android.util.Base64.decode(ctB64, b64)), Charsets.UTF_8)
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
