package app.mangatrans.pipeline

import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.PageEvent
import app.mangatrans.domain.PageJob
import app.mangatrans.domain.RegionKind
import app.mangatrans.ports.BubbleTranslation
import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.Translator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duong CHIA DOT cua `TranslateFilter` — `MAX_PER_CALL = 10`.
 *
 * Vi sao viet bay gio: duong **3 dot tro len** chua chay lan nao. Quet ca 201
 * trang that trong bo test bang chinh detector cua app (nguong 0,30, dem
 * `kind=TextBubble`):
 *
 *   <= 10 vung (mot lan goi) : 130 trang
 *   11-20 vung (2 dot)       :  70 trang
 *   >= 21 vung (3 dot)       :   1 trang   <- tubaki_121, 22 vung -> [8, 8, 6]
 *
 * Dung **1/201**. Hiem la ly do no chua lo loi; chua tung chay la ly do khong
 * ai biet no co dung khong. Va san mot trang trong 201 de kiem thi vua may rui
 * vua khong lap lai duoc — nen kiem o day, tat dinh.
 */
class ChunkedTranslateTest {

    /**
     * Chuoi Nhat phai DAI >= 8 ky tu: duoi nguong do `looksTruncated` khong
     * xet, nen test se khong con phu duoc cong chan ban dich cut.
     */
    private fun ja(i: Int) = "これはテスト用の日本語$i"

    private fun job(n: Int) = PageJob(
        jobId = "t", frameHash = "f", contentKey = "c",
        pageWidth = 974, pageHeight = 1400,
        bubbles = (0 until n).map { i ->
            Bubble(i, Box(0, i * 50, 100, i * 50 + 40), RegionKind.TextBubble, 0.9f, ja = ja(i))
        },
    )

    /**
     * Translator gia GHI LAI tung luot goi: dot nay gom nhung id nao, va co
     * phai la luot noi tiep phien truoc khong.
     *
     * Tra ve ban dich cho DUNG nhung bong trong dot — tuc mo phong mot mo hinh
     * chay dung, de test doc duoc CO CHE chia/gop chu khong phai doc duoc cach
     * xu ly loi.
     */
    private class Recorder : Translator {
        val calls = mutableListOf<Pair<List<Int>, Boolean>>()
        var endPageCalls = 0

        override fun translate(
            page: PageJob,
            glossary: List<GlossaryEntry>,
            continuing: Boolean,
        ): Flow<BubbleTranslation> {
            val batch = page.translatable
            calls += batch.map { it.id } to continuing
            return flow {
                batch.forEach { b ->
                    emit(BubbleTranslation(
                        b.id,
                        EchoGate.normalize(b.ja, 2),
                        "ban dich tieng Viet day du cho bong ${b.id}",
                    ))
                }
            }
        }

        override suspend fun endPage() { endPageCalls++ }
        override suspend fun warmUp() {}
        override suspend fun release() {}
        override val isWarm = true
    }

    /** Trang 22 bong — dung con so cua `tubaki_121`, trang duy nhat cham duong 3 dot. */
    @Test
    fun `trang 22 bong chia lam ba dot va dich du ca 22`() = runTest {
        val rec = Recorder()
        val ev = TranslateFilter(rec, { emptyList() }).stream(job(22)).toList()

        assertEquals("phai chia lam 3 dot", 3, rec.calls.size)
        assertEquals(listOf(8, 8, 6), rec.calls.map { it.first.size })
        assertEquals("du 22 bong", 22, ev.filterIsInstance<PageEvent.BubbleReady>().size)
        assertEquals(1, ev.filterIsInstance<PageEvent.Done>().size)
        assertEquals(0, ev.filterIsInstance<PageEvent.Retracted>().size)
    }

    /**
     * KHONG dot nao duoc vuot `MAX_PER_CALL`, va KHONG duoc de ra dot le mot
     * bong. Ghi chu trong `TranslateFilter` noi ro vi sao: `chunked(10)` thang
     * cho trang 11 bong ra `[10, 1]`, va **dot mot bong luon hong** -- do tren
     * may, hai lan thu lien tiep deu nhan 0 bong.
     */
    @Test
    fun `khong dot nao vuot 10, va khong co dot le mot bong`() = runTest {
        for (n in 11..40) {
            val rec = Recorder()
            TranslateFilter(rec, { emptyList() }).stream(job(n)).toList()
            val sizes = rec.calls.map { it.first.size }
            assertEquals("tong phai du $n bong", n, sizes.sum())
            assertTrue("$n -> $sizes: co dot vuot 10", sizes.all { it <= 10 })
            assertTrue("$n -> $sizes: co dot le mot bong", sizes.none { it == 1 })
        }
    }

    /**
     * Dot DAU mo phien moi, cac dot SAU noi tiep phien do.
     *
     * Do tren may: mo phien moi phai doc lai 2171 ky tu co dinh (~16 giay),
     * noi tiep chi ~2 giay. Neu co nao do lam moi dot deu mo phien moi thi
     * trang 3 dot cham them ~28 giay ma khong ai thay o dau.
     */
    @Test
    fun `chi dot dau mo phien moi`() = runTest {
        val rec = Recorder()
        TranslateFilter(rec, { emptyList() }).stream(job(22)).toList()
        assertEquals(listOf(false, true, true), rec.calls.map { it.second })
    }

    /** Chia xong phai bao het trang DUNG MOT lan, du co bao nhieu dot. */
    @Test
    fun `endPage goi dung mot lan cho ca trang nhieu dot`() = runTest {
        val rec = Recorder()
        TranslateFilter(rec, { emptyList() }).stream(job(22)).toList()
        assertEquals(1, rec.endPageCalls)
    }

    /**
     * Thu tu doc phai giu nguyen qua cac dot: bong 0..21 theo dung thu tu, va
     * moi bong nhan dung ban dich cua chinh no.
     *
     * Day la cho de vo am tham nhat khi gop ket qua nhieu dot -- lech mot o thi
     * ca trang van "co ban dich", chi la ban dich cua bong ben canh.
     */
    @Test
    fun `gop nhieu dot khong lam lech id hay thu tu`() = runTest {
        val rec = Recorder()
        val ev = TranslateFilter(rec, { emptyList() }).stream(job(22)).toList()

        assertEquals((0..21).toList(), rec.calls.flatMap { it.first })

        val done = ev.filterIsInstance<PageEvent.Done>().single().job
        done.bubbles.forEach { b ->
            assertEquals("bong ${b.id} nhan nham ban dich", "ban dich tieng Viet day du cho bong ${b.id}", b.vi)
        }
    }

    /** Vua dung 10 bong thi KHONG chia -- chia la them mot lan prefill vo co. */
    @Test
    fun `dung 10 bong thi goi mot lan`() = runTest {
        val rec = Recorder()
        TranslateFilter(rec, { emptyList() }).stream(job(10)).toList()
        assertEquals(1, rec.calls.size)
        assertEquals(false, rec.calls.single().second)
    }
}
