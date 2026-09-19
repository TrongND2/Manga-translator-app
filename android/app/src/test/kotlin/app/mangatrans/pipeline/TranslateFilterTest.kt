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
 * Story 2.5 / 2.6 / 2.7 — AD-3, AD-6, AD-17.
 *
 * Du lieu lay tu trang tubaki_010 that.
 */
class TranslateFilterTest {

    private val ja = listOf(
        "俺ァまた悦の色香でアソコが疼いて",
        "では俺は失礼してー",
        "他の女の所へシケ込むつもりなのかとよォ",
        "瑠璃丸ったら～～",
    )

    private fun job() = PageJob(
        jobId = "t", frameHash = "f", contentKey = "c",
        pageWidth = 974, pageHeight = 1400,
        bubbles = ja.mapIndexed { i, s ->
            Bubble(i, Box(0, i * 100, 100, i * 100 + 90), RegionKind.TextBubble, 0.9f, ja = s)
        },
    )

    /** Translator gia — tra ve dung nhung gi test muon. */
    private class Fake(val out: List<BubbleTranslation>) : Translator {
        override fun translate(
            page: PageJob,
            glossary: List<GlossaryEntry>,
            continuing: Boolean,
        ): Flow<BubbleTranslation> = flow { out.forEach { emit(it) } }
        override suspend fun warmUp() {}
        override suspend fun release() {}
        override val isWarm = true
    }

    /**
     * ⚠️ Ban dich gia phai DAI ngang ban dich that.
     *
     * Truoc day cho nay la `"dich $i"` (6 ky tu) cho nhung cau Nhat dai 16-19
     * ky tu. Con so do khong giong bat cu ban dich that nao: do tren 92 cap
     * JA/VI that lay tu may, ty le do dai VI/JA thap nhat (voi JA >= 8 ky tu)
     * la **1,33**, con ban dich bi cut that su nam o **0,24**. Cong chan ban
     * dich cut (`looksTruncated`) vi the loai dung nhung chuoi gia nay.
     */
    private fun run(out: List<BubbleTranslation>) = TranslateFilter(Fake(out), { emptyList() })

    private fun echo(s: String) = EchoGate.normalize(s, 2)

    @Test
    fun `dich dung thi phat du bubble va Done`() = runTest {
        val good = ja.mapIndexed { i, s -> BubbleTranslation(i, echo(s), "ban dich tieng Viet cua bubble $i") }
        val ev = run(good).stream(job()).toList()

        assertEquals(4, ev.filterIsInstance<PageEvent.BubbleReady>().size)
        assertEquals(1, ev.filterIsInstance<PageEvent.Done>().size)
        assertEquals(0, ev.filterIsInstance<PageEvent.Retracted>().size)
    }

    /**
     * ⚠️ Test nay TRUOC DAY doi "lech giua chung -> go sach, tu choi ca trang".
     * Hop dong do da doi CO CHU DICH o F57, va ly do ghi ngay trong
     * `TranslateFilter`:
     *
     *   Do tren may, mot trang 18 bong: mo hinh dich dung 10 bong roi sinh muc
     *   thu 11 lech jaEcho — va ca 10 bong dung bi vut, nguoi dung nhan mot
     *   trang trang tron. Vut 10 ban dich dung vi mot muc hong la danh doi sai
     *   phia.
     *
     * Cong jaEcho kiem TUNG BONG MOT, nen mot muc hong o cuoi khong lam nhung
     * muc da doi chieu tro nen dang ngo. Gio giu lai phan da nhan.
     */
    @Test
    fun `lech giua chung thi giu phan da nhan, khong vut ca trang`() = runTest {
        // Tai hien DUNG loi F10 da quan sat that tren tubaki_025:
        // bubble 0..1 dung, tu bubble 2 tro di lech mot o.
        val shifted = ja.indices.map { i ->
            val src = if (i < 2) ja[i] else ja.getOrElse(i + 1) { ja[i] }
            BubbleTranslation(i, echo(src), "ban dich tieng Viet cua bubble $i")
        }
        val ev = run(shifted).stream(job()).toList()

        assertEquals("phai bao Done voi phan dich duoc",
            1, ev.filterIsInstance<PageEvent.Done>().size)
        assertEquals("khong duoc go bong da ve nua",
            0, ev.filterIsInstance<PageEvent.Retracted>().size)
        assertEquals("khong tu choi ca trang nua",
            0, ev.filterIsInstance<PageEvent.PageRejected>().size)

        // Day moi la phan dang gia: 0 va 1 phai GIU duoc ban dich, 2 va 3 phai
        // tro ve nguyen ban tieng Nhat (AD-9) chu khong phai o trong.
        val out = ev.filterIsInstance<PageEvent.Done>().single().job.bubbles.associateBy { it.id }
        assertEquals("ban dich tieng Viet cua bubble 0", out.getValue(0).vi)
        assertEquals("ban dich tieng Viet cua bubble 1", out.getValue(1).vi)
        assertEquals("bong lech khong duoc nhan ban dich", null, out.getValue(2).vi)
        assertEquals("bong chua toi luot cung khong duoc nhan", null, out.getValue(3).vi)
        assertEquals("nguyen ban phai con nguyen", ja[2], out.getValue(2).ja)
    }

    @Test
    fun `lech ngay bubble dau thi khong co gi de go`() = runTest {
        // Chua ve gi thi khong phat Retracted — dung, khong phai thieu sot.
        val shifted = ja.indices.map { i ->
            BubbleTranslation(i, echo(ja.getOrElse(i + 1) { ja[i] }), "ban dich tieng Viet cua bubble $i")
        }
        val ev = run(shifted).stream(job()).toList()
        assertEquals("khong ve gi thi khong go gi",
            0, ev.filterIsInstance<PageEvent.Retracted>().size)
        assertEquals(1, ev.filterIsInstance<PageEvent.PageRejected>().size)
    }

    /**
     * Id la (bubble ma) khong duoc keo theo nhung bong da nhan dung.
     *
     * Truoc F57 cho nay tu choi ca trang. Nhung id 99 den SAU khi ca 4 bong
     * that da qua cong jaEcho — vut chung di la vut co so.
     */
    @Test
    fun `id thua khong lam mat nhung bong da nhan dung`() = runTest {
        // Da gap that o tubaki_025: model tra 13 bubble trong khi vao 12 (F19).
        val extra = ja.mapIndexed { i, s -> BubbleTranslation(i, echo(s), "ban dich tieng Viet cua bubble $i") } +
            BubbleTranslation(99, "XX", "bubble ma")
        val ev = run(extra).stream(job()).toList()

        assertEquals(1, ev.filterIsInstance<PageEvent.Done>().size)
        val out = ev.filterIsInstance<PageEvent.Done>().single().job.bubbles
        assertEquals("ca 4 bong that phai giu ban dich",
            4, out.count { it.vi != null })
        assertEquals("bong ma khong duoc lot vao trang",
            emptyList<Int>(), out.map { it.id }.filter { it == 99 })
    }

    /**
     * Mo hinh dung som: nhan 2/4 bong. Hop dong moi la GIU 2 bong do lai, phan
     * con lai tro ve nguyen ban (AD-9) — khong phai vut ca trang.
     *
     * `Done` cua trang thieu phai PHAN BIET DUOC voi `Done` cua trang hoan
     * hao — neu khong thi mot dot hong di qua hoan toan im lang. `untranslated`
     * la cho de phan biet.
     */
    @Test
    fun `thieu bong thi giu phan dich duoc va bao duoc la con thieu`() = runTest {
        val short = ja.dropLast(2).mapIndexed { i, s -> BubbleTranslation(i, echo(s), "ban dich tieng Viet cua bubble $i") }
        val ev = run(short).stream(job()).toList()

        assertEquals(1, ev.filterIsInstance<PageEvent.Done>().size)
        assertEquals(0, ev.filterIsInstance<PageEvent.PageRejected>().size)

        val done = ev.filterIsInstance<PageEvent.Done>().single().job
        val out = done.bubbles.associateBy { it.id }
        assertEquals("ban dich tieng Viet cua bubble 0", out.getValue(0).vi)
        assertEquals("ban dich tieng Viet cua bubble 1", out.getValue(1).vi)
        assertEquals("bong khong nhan duoc phai de null", null, out.getValue(2).vi)
        assertEquals("bong khong nhan duoc phai de null", null, out.getValue(3).vi)

        assertEquals("phai bao dung 2 bong con thieu",
            listOf(2, 3), done.untranslated.map { it.id })
    }

    /** Trang dich du thi `untranslated` phai RONG — khong bao dong gia. */
    @Test
    fun `trang dich du thi khong bao thieu`() = runTest {
        val good = ja.mapIndexed { i, s -> BubbleTranslation(i, echo(s), "ban dich tieng Viet cua bubble $i") }
        val done = run(good).stream(job()).toList()
            .filterIsInstance<PageEvent.Done>().single().job
        assertEquals(emptyList<Int>(), done.untranslated.map { it.id })
    }

    @Test
    fun `nhieu ky tu trong echo van duoc chap nhan`() = runTest {
        // Bao dong gia THAT da gap (F14): model doi tro tu, bo dau gach dau cau.
        val noisy = listOf(
            // ⚠️ Ban dich phai du dai — xem ghi chu o `run`. Phep kiem nay noi
            // ve cong jaEcho, khong phai ve do dai, nen dung chuoi that.
            BubbleTranslation(0, echo("俺ア"), "ban dich thu nhat cua trang"),  // ァ -> ア, sai 1 ky tu
            BubbleTranslation(1, echo("では"), "ban dich thu hai cua trang"),
            BubbleTranslation(2, echo("他の"), "ban dich thu ba cua trang"),
            BubbleTranslation(3, echo("瑠璃"), "ban dich thu tu cua trang"),
        )
        val ev = run(noisy).stream(job()).toList()
        assertEquals("sai mot ky tu khong duoc coi la lech",
            1, ev.filterIsInstance<PageEvent.Done>().size)
    }

    @Test
    fun `bubble ready phat ra truoc khi Done`() = runTest {
        // AD-13: phai phat dan, khong doi den cuoi moi phat mot cuc.
        val good = ja.mapIndexed { i, s -> BubbleTranslation(i, echo(s), "ban dich tieng Viet cua bubble $i") }
        val ev = run(good).stream(job()).toList()
        val firstReady = ev.indexOfFirst { it is PageEvent.BubbleReady }
        val done = ev.indexOfFirst { it is PageEvent.Done }
        assertTrue("BubbleReady phai den truoc Done", firstReady in 0 until done)
    }
}
