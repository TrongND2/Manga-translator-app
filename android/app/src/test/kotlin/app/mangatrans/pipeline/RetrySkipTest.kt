package app.mangatrans.pipeline

import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
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
import org.junit.Test

/**
 * F83 — luot thu lai cua dot DAU tien la vo ich, va do duoc bang cach dem.
 *
 * Luot thu lai luon dung `continuing = false`, ma dot dau cung da la
 * `continuing = false` -> prompt y het. Mo hinh tat dinh (F80: 15/15 bong giong
 * het khi lap cung prompt), nen ket qua cung y het: ton them mot luot sinh chu
 * day du (~40-60 giay tren M52) de nhan lai dung cai cu.
 */
class RetrySkipTest {

    /** Translator gia co DEM so lan bi goi. */
    private class Counting(val out: List<BubbleTranslation>) : Translator {
        var calls = 0
        override fun translate(
            page: PageJob,
            glossary: List<GlossaryEntry>,
            continuing: Boolean,
        ): Flow<BubbleTranslation> = flow { calls++; out.forEach { emit(it) } }
        override suspend fun warmUp() {}
        override suspend fun release() {}
        override val isWarm = true
    }

    private val ja = listOf(
        "俺ァまた悦の色香でアソコが疼いて",
        "では俺は失礼してー",
        "他の女の所へシケ込むつもりなのかとよォ",
    )

    private fun job() = PageJob(
        jobId = "t", frameHash = "f", contentKey = "c",
        pageWidth = 974, pageHeight = 1400,
        bubbles = ja.mapIndexed { i, s ->
            Bubble(i, Box(0, i * 100, 100, i * 100 + 90), RegionKind.TextBubble, 0.9f, ja = s)
        },
    )

    private fun echo(s: String) = EchoGate.normalize(s, 2)

    @Test
    fun `dot dau thieu bong thi KHONG goi mo hinh lan hai`() = runTest {
        // Mo hinh chi tra ve 2 tren 3 bong — "dung som", khong lech jaEcho.
        val partial = ja.take(2).mapIndexed { i, s ->
            BubbleTranslation(i, echo(s), "ban dich tieng Viet cua bubble $i")
        }
        val fake = Counting(partial)
        TranslateFilter(fake, { emptyList() }).stream(job()).toList()

        assertEquals(
            "prompt cua luot thu lai y het luot dau nen khong duoc goi lai",
            1, fake.calls,
        )
    }

    @Test
    fun `phan da dich duoc van duoc giu lai`() = runTest {
        val partial = ja.take(2).mapIndexed { i, s ->
            BubbleTranslation(i, echo(s), "ban dich tieng Viet cua bubble $i")
        }
        val ev = TranslateFilter(Counting(partial), { emptyList() }).stream(job()).toList()
        val done = ev.filterIsInstance<app.mangatrans.domain.PageEvent.Done>()
        assertEquals("phai bao Done voi phan da dich duoc", 1, done.size)
        val vi = done.first().job.bubbles.count { it.vi != null }
        assertEquals("giu dung 2 bong da qua cong jaEcho", 2, vi)
    }
}
