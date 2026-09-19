package app.mangatrans.pipeline

import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.GlossaryKind
import app.mangatrans.ports.GlossaryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `relevantGlossary` — loc muc tu dien co mat trong doan chu Nhat dang dich.
 *
 * Ham nay gio co HAI duong goi: dich ca trang (`TranslateFilter`) va dich mot
 * cum (`translateOnePhrase`, tuc nut "📱 AI tren may" + luong `⌖`). Truoc day
 * duong thu hai truyen `emptyList()` nen khong ap tu dien -- cung mot cum chu,
 * hai duong ra hai ket qua.
 *
 * Phan de vo nhat khi tach dung chung la chuan hoa dau `ー` (F84): chep tay
 * sang ben kia rat de lam rot, va khi rot thi muc tu dien nam do vo dung ma
 * khong ai biet. Nen no co test rieng o duoi.
 */
class RelevantGlossaryTest {

    private fun e(surface: String, meaning: String = "nghia") = GlossaryEntry(
        seriesKey = "default",
        surface = surface,
        meaning = meaning,
        kind = GlossaryKind.Idiom,
        status = GlossaryStatus.Confirmed,
    )

    @Test
    fun `chi giu muc co mat tren trang`() {
        val all = listOf(e("幼馴染"), e("瀑布"))   // 幼馴染 / 瀑布
        val got = relevantGlossary(all, listOf("幼馴染だった"))
        assertEquals(listOf("幼馴染"), got.map { it.surface })
    }

    @Test
    fun `khong co muc nao khop thi tra ve rong`() {
        val got = relevantGlossary(listOf(e("瀑布")), listOf("こんにちは"))
        assertEquals(emptyList<String>(), got.map { it.surface })
    }

    @Test
    fun `tu dien rong thi tra ve rong, khong nem`() {
        assertEquals(emptyList<GlossaryEntry>(), relevantGlossary(emptyList(), listOf("abc")))
    }

    @Test
    fun `mat chu de trong thi bo qua`() {
        assertEquals(emptyList<String>(), relevantGlossary(listOf(e("   ")), listOf("abc")).map { it.surface })
    }

    /**
     * F84 — OCR tung tra ve `ザ-メン` (gach noi ASCII) thay vi `ザーメン` (dau keo
     * dai that). Hai chuoi trong giong het nhau ma khong bao gio khop.
     *
     * Phai khop duoc CA HAI CHIEU, vi loi co the nam o hai phia khac nhau:
     * chu doc ra sai dau, hoac muc tu dien nguoi dung go nham dau.
     */
    @Test
    fun `dau keo dai bi doc nham thanh gach noi van phai khop`() {
        val real = "ザーメン"     // ザーメン, dau keo dai that
        val ascii = "ザ-メン"         // ザ-メン, gach noi ASCII

        // Chu tren trang bi doc sai dau, muc tu dien go dung.
        assertEquals(
            listOf(real),
            relevantGlossary(listOf(e(real)), listOf(ascii)).map { it.surface },
        )
        // Nguoc lai: chu tren trang dung, muc tu dien go nham dau.
        assertEquals(
            listOf(ascii),
            relevantGlossary(listOf(e(ascii)), listOf(real)).map { it.surface },
        )
    }

    /** Nhieu bong thi gop lai roi moi so — muc nam o bong bat ky deu phai khop. */
    @Test
    fun `muc nam o bong thu hai van duoc giu`() {
        val got = relevantGlossary(
            listOf(e("幼馴染")),
            listOf("こんにちは", "幼馴染だ"),
        )
        assertEquals(1, got.size)
    }
}
