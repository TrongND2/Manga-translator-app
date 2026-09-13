package app.mangatrans.adapters.litertlm

import app.mangatrans.ports.BubbleTranslation
import org.json.JSONObject

/**
 * Doc tung object bubble NGAY KHI no hoan chinh trong dong JSON dang chay ve.
 *
 * Vi sao can: AD-17 doi phat tung bubble ngay khi duoc chap nhan, va PRD 8.3
 * chot "thoi gian toi bubble DAU TIEN" moi la chi so nguoi dung cam nhan
 * (NFR-005, <= 8s). Neu doi ca trang sinh xong moi parse thi bubble dau ve sau
 * 169 giay — da do that tren M52.
 *
 * Cach lam: dem ngoac trong chuoi tich luy. Moi lan mot `{...}` can bang o muc
 * ben trong mang `bubbles`, thu doc no ngay.
 *
 * Bo qua ngoac nam trong chuoi va ky tu escape — ban dich tieng Viet co the
 * chua `{`, `}` hoac `\"`.
 */
class StreamingJsonParser {

    private val buf = StringBuilder()
    private var scanFrom = 0

    /**
     * @param chunk phan van ban moi nhan duoc
     * @return cac bubble vua hoan chinh trong lan goi nay
     */
    fun feed(chunk: String): List<BubbleTranslation> {
        buf.append(chunk)
        val out = mutableListOf<BubbleTranslation>()

        var i = scanFrom
        while (i < buf.length) {
            if (buf[i] != '{') { i++; continue }

            val end = matchBrace(i)
            if (end == null) {
                // Ngoac nay chua dong. KHONG dung lai: ngoac ngoai cung
                // (`{"bubbles":[...`) chi dong o cuoi, con cac object bubble
                // ben trong da du tu lau. Di tiep de tim chung.
                i++
                continue
            }
            val parsed = parseOne(buf.substring(i, end + 1))
            if (parsed != null) {
                out.add(parsed)
                i = end + 1
                scanFrom = i          // chi tien qua thu DA phat
            } else {
                i++                   // object bao ngoai — nhin vao ben trong
            }
        }
        return out
    }

    /** Tra ve vi tri `}` dong cua `{` o `start`, hoac null neu chua du du lieu. */
    private fun matchBrace(start: Int): Int? {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until buf.length) {
            val c = buf[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** Chi nhan object co du `id` va `vi` — bo qua object bao ngoai. */
    private fun parseOne(text: String): BubbleTranslation? = runCatching {
        val o = JSONObject(text)
        if (!o.has("id") || !o.has("vi")) return null
        BubbleTranslation(
            id = o.getInt("id"),
            jaEcho = o.optString("jaEcho", ""),
            vi = o.optString("vi", "").trim(),
            speaker = o.optString("speaker", "").takeIf { it.isNotBlank() && it != "?" },
        )
    }.getOrNull()

    /** Doc not phan con lai khi dong da ket thuc — phong khi thieu dau dong. */
    fun drain(): List<BubbleTranslation> {
        val rest = buf.substring(scanFrom)
        scanFrom = buf.length
        if (rest.isBlank()) return emptyList()
        return Regex("\\{[^{}]*\\}").findAll(rest).mapNotNull { parseOne(it.value) }.toList()
    }

    val raw: String get() = buf.toString()
}
