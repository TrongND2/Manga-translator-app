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
        override fun translate(page: PageJob, glossary: List<GlossaryEntry>): Flow<BubbleTranslation> =
            flow { out.forEach { emit(it) } }
        override suspend fun warmUp() {}
        override suspend fun release() {}
        override val isWarm = true
    }

    private fun run(out: List<BubbleTranslation>) = TranslateFilter(Fake(out), { emptyList() })

    private fun echo(s: String) = EchoGate.normalize(s, 2)

    @Test
    fun `dich dung thi phat du bubble va Done`() = runTest {
        val good = ja.mapIndexed { i, s -> BubbleTranslation(i, echo(s), "dich $i") }
        val ev = run(good).stream(job()).toList()

        assertEquals(4, ev.filterIsInstance<PageEvent.BubbleReady>().size)
        assertEquals(1, ev.filterIsInstance<PageEvent.Done>().size)
        assertEquals(0, ev.filterIsInstance<PageEvent.Retracted>().size)
    }

    @Test
    fun `lech giua chung thi go bubble da ve va tu choi ca trang`() = runTest {
        // Tai hien DUNG loi F10 da quan sat that tren tubaki_025:
        // bubble 0..1 dung, tu bubble 2 tro di lech mot o.
        val shifted = ja.indices.map { i ->
            val src = if (i < 2) ja[i] else ja.getOrElse(i + 1) { ja[i] }
            BubbleTranslation(i, echo(src), "dich $i")
        }
        val ev = run(shifted).stream(job()).toList()

        val retracted = ev.filterIsInstance<PageEvent.Retracted>()
        assertTrue("phai go nhung bubble DA VE truoc khi phat hien lech",
            retracted.isNotEmpty())
        assertEquals("phai go dung 2 bubble da ve", listOf(0, 1), retracted.first().bubbleIds)
        assertEquals("ca trang phai bi tu choi",
            1, ev.filterIsInstance<PageEvent.PageRejected>().size)
        assertEquals("khong duoc bao Done", 0, ev.filterIsInstance<PageEvent.Done>().size)
    }

    @Test
    fun `lech ngay bubble dau thi khong co gi de go`() = runTest {
        // Chua ve gi thi khong phat Retracted — dung, khong phai thieu sot.
        val shifted = ja.indices.map { i ->
            BubbleTranslation(i, echo(ja.getOrElse(i + 1) { ja[i] }), "dich $i")
        }
        val ev = run(shifted).stream(job()).toList()
        assertEquals("khong ve gi thi khong go gi",
            0, ev.filterIsInstance<PageEvent.Retracted>().size)
        assertEquals(1, ev.filterIsInstance<PageEvent.PageRejected>().size)
    }

    @Test
    fun `id thua thi tu choi`() = runTest {
        // Da gap that o tubaki_025: model tra 13 bubble trong khi vao 12 (F19).
        val extra = ja.mapIndexed { i, s -> BubbleTranslation(i, echo(s), "dich $i") } +
            BubbleTranslation(99, "XX", "bubble ma")
        val ev = run(extra).stream(job()).toList()
        assertEquals(1, ev.filterIsInstance<PageEvent.PageRejected>().size)
    }

    @Test
    fun `thieu bubble thi khong bao Done`() = runTest {
        val short = ja.dropLast(2).mapIndexed { i, s -> BubbleTranslation(i, echo(s), "dich $i") }
        val ev = run(short).stream(job()).toList()
        assertEquals(0, ev.filterIsInstance<PageEvent.Done>().size)
        assertEquals(1, ev.filterIsInstance<PageEvent.PageRejected>().size)
    }

    @Test
    fun `nhieu ky tu trong echo van duoc chap nhan`() = runTest {
        // Bao dong gia THAT da gap (F14): model doi tro tu, bo dau gach dau cau.
        val noisy = listOf(
            BubbleTranslation(0, echo("俺ア"), "a"),       // ァ -> ア, sai 1 ky tu
            BubbleTranslation(1, echo("では"), "b"),
            BubbleTranslation(2, echo("他の"), "c"),
            BubbleTranslation(3, echo("瑠璃"), "d"),
        )
        val ev = run(noisy).stream(job()).toList()
        assertEquals("sai mot ky tu khong duoc coi la lech",
            1, ev.filterIsInstance<PageEvent.Done>().size)
    }

    @Test
    fun `bubble ready phat ra truoc khi Done`() = runTest {
        // AD-13: phai phat dan, khong doi den cuoi moi phat mot cuc.
        val good = ja.mapIndexed { i, s -> BubbleTranslation(i, echo(s), "dich $i") }
        val ev = run(good).stream(job()).toList()
        val firstReady = ev.indexOfFirst { it is PageEvent.BubbleReady }
        val done = ev.indexOfFirst { it is PageEvent.Done }
        assertTrue("BubbleReady phai den truoc Done", firstReady in 0 until done)
    }
}
