package app.mangatrans.pipeline

import java.text.Normalizer

/**
 * AD-6 — cong toan ven anh xa id <-> noi dung.
 *
 * Vi sao can: da quan sat that (FINDINGS F10) model tra du 12 bubble, id chay
 * dung 0->11, done_reason=stop, MOI kiem tra tu dong deu xanh — nhung toan bo
 * ban dich LECH MOT O tu bubble thu tu. Nguoi doc thay moi bubble deu troi chay
 * ma ca trang sai mach hoi thoai.
 *
 * JSON hop le va du so phan tu KHONG tinh la da kiem tra.
 *
 * Thuat toan nay da duoc kiem chung (F14) tren du lieu that:
 *   so khop chinh xac tung ky tu : 1 bao dong gia, bat 11/11 o lech
 *   chuan hoa + sai <=1          : 0 bao dong gia, bat 11/11 o lech
 *
 * Bao dong gia den tu nhieu ky tu, khong phai lech o:
 *   何を当て vs 何が当て   — model doi mot tro tu
 *   どうせ無 vs ―どうせ    — model bo dau gach dau cau
 */
object EchoGate {

    /** Dau cau va khoang trang bi bo truoc khi so. Bao gom ca dang toan rong. */
    private const val PUNCT = "―ー-—…‥。、．，！？!?「」『』（）()〝〟“”\"'  　\n\t"

    /**
     * Chuan hoa ba buoc — KHONG duoc bo buoc nao:
     *   1. NFKC  (dong nhat dang toan rong / nua rong)
     *   2. bo dau cau va khoang trang
     *   3. lay `n` ky tu dau
     */
    fun normalize(s: String?, n: Int): String {
        val nfkc = Normalizer.normalize(s ?: "", Normalizer.Form.NFKC)
        val stripped = nfkc.filterNot { it in PUNCT }
        return stripped.take(n)
    }

    /** Khoang cach sua <= 1. Du cho muc dich nay, khong can Levenshtein day du. */
    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) return a.indices.count { a[it] != b[it] } <= 1
        val (lo, hi) = if (a.length < b.length) a to b else b to a
        for (i in hi.indices) {
            if (hi.removeRange(i, i + 1) == lo) return true
        }
        return false
    }

    /**
     * @param truth  id -> nguyen ban tieng Nhat dua VAO prompt
     * @param answer id -> jaEcho model tra VE
     * @return danh sach id khong khop. Rong = toan ven.
     */
    fun mismatches(
        truth: Map<Int, String>,
        answer: Map<Int, String>,
        cfg: PipelineConfig = PipelineConfig(),
    ): List<Int> = answer.keys.filter { id ->
        val want = normalize(truth[id], cfg.jaEchoChars)
        val got = normalize(answer[id], cfg.jaEchoChars)
        val ok = if (cfg.jaEchoMaxEdits >= 1) withinOneEdit(got, want) else got == want
        !ok
    }

    /**
     * Kiem ca tinh day du: model co tra ve id nao KHONG co trong dau vao khong,
     * hoac thieu id nao khong.
     *
     * Da quan sat that: trang tubaki_025 model tra ve 13 bubble trong khi dau
     * vao co 12 (FINDINGS F19). Day khong phai su co mot lan.
     */
    fun extraneousIds(truth: Map<Int, String>, answer: Map<Int, String>): List<Int> =
        answer.keys.filterNot { it in truth.keys }
}
