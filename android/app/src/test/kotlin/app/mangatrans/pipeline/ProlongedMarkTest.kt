package app.mangatrans.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F84 — OCR tra ve dau gach ASCII `-` thay cho dau keo dai kana `ー`.
 *
 * Moi chuoi "truoc" trong file nay lay NGUYEN VAN tu log chan doan cua may
 * that. Do tren 153 cau: `ー` dung chuan xuat hien 0 lan, `-` xuat hien 8 lan.
 */
class ProlongedMarkTest {

    @Test
    fun `sau katakana thi doi thanh dau keo dai`() {
        assertEquals("ザーメン", fixProlongedMark("ザ-メン"))
        assertEquals("サービス♡", fixProlongedMark("サ-ビス♡"))
        assertEquals("ノーブラ!?", fixProlongedMark("ノ-ブラ!?"))
    }

    @Test
    fun `sau hiragana cung doi`() {
        assertEquals("いいよ♡ちょーだい♡", fixProlongedMark("いいよ♡ちょ-だい♡"))
        assertEquals("不思議だよねー", fixProlongedMark("不思議だよね-"))
    }

    @Test
    fun `ca cau that tu may`() {
        assertEquals(
            "パパのザーメンアズのおマンコにちょーだい♡",
            fixProlongedMark("パパのザ-メンアズのおマンコにちょ-だい♡"),
        )
    }

    @Test
    fun `KHONG doi khi truoc no khong phai kana`() {
        assertEquals("2-3", fixProlongedMark("2-3"))
        assertEquals("A-B", fixProlongedMark("A-B"))
        assertEquals("JK-", fixProlongedMark("JK-"))
        assertEquals("-abc", fixProlongedMark("-abc"))
    }

    @Test
    fun `chuoi khong co gach thi tra ve nguyen van`() {
        val s = "俺だけ!俺だけなのか!?"
        assertEquals(s, fixProlongedMark(s))
    }

    @Test
    fun `dau keo dai nua rong va toan rong cung duoc sua`() {
        assertEquals("ザーメン", fixProlongedMark("ザｰメン"))
        assertEquals("ザーメン", fixProlongedMark("ザ－メン"))
    }

    // ---------- hai cho NOI, khong chi rieng ham ----------

    private class FakeOcr(val text: String) : app.mangatrans.ports.OcrEngine {
        override suspend fun read(
            image: app.mangatrans.ports.PageImage,
            box: app.mangatrans.domain.Box,
        ): String = text
    }

    private fun jobWith(ja: String) = app.mangatrans.domain.PageJob(
        jobId = "t", frameHash = "f", contentKey = "c", pageWidth = 100, pageHeight = 100,
        bubbles = listOf(app.mangatrans.domain.Bubble(
            0, app.mangatrans.domain.Box(0, 0, 50, 50),
            app.mangatrans.domain.RegionKind.TextBubble, 0.9f, ja = ja,
        )),
        readingOrder = listOf(0),
    )

    /** OcrFilter phai sua NGAY khi doc, de moi buoc sau deu thay chu dung. */
    @Test
    fun `OcrFilter sua ngay khi doc chu`() = kotlinx.coroutines.test.runTest {
        val img = app.mangatrans.ports.PageImage(100, 100, Any())
        val out = OcrFilter(FakeOcr("パパのザ-メン"), { img }).apply(jobWith(""))
        assertEquals("パパのザーメン", out.bubbles.first().ja)
    }

    /**
     * Cai gia that cua loi nay: muc tu dien `ザーメン` nam san trong tu dien ma
     * KHONG BAO GIO khop, vi chu OCR doc ra la `ザ-メン`.
     */
    @Test
    fun `muc tu dien co dau keo dai nay da khop duoc voi chu OCR doc ra`() =
        kotlinx.coroutines.test.runTest {
            var seen: List<app.mangatrans.ports.GlossaryEntry>? = null
            val tr = object : app.mangatrans.ports.Translator {
                override fun translate(
                    page: app.mangatrans.domain.PageJob,
                    glossary: List<app.mangatrans.ports.GlossaryEntry>,
                    continuing: Boolean,
                ): kotlinx.coroutines.flow.Flow<app.mangatrans.ports.BubbleTranslation> {
                    seen = glossary
                    return kotlinx.coroutines.flow.flowOf()
                }
                override suspend fun warmUp() {}
                override suspend fun release() {}
                override val isWarm = true
            }
            val entry = app.mangatrans.ports.GlossaryEntry(
                seriesKey = "default", surface = "ザーメン", meaning = "tinh dịch",
                kind = app.mangatrans.ports.GlossaryKind.Idiom,
                status = app.mangatrans.ports.GlossaryStatus.Confirmed,
            )
            // `ja` co dau gach ASCII, dung nhu OCR tra ve truoc khi sua.
            TranslateFilter(tr, { listOf(entry) })
                .stream(jobWith("パパのザ-メンちょ-だい")).collect { }
            assertEquals(1, seen?.size)
        }
}
