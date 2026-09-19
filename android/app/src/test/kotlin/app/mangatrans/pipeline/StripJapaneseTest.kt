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
 * F81 — chu Nhat con sot trong ban dich.
 *
 * Moi chuoi trong file nay lay tu log chan doan cua trang that tren M52.
 */
class StripJapaneseTest {

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

    private fun bubble(id: Int, ja: String) =
        Bubble(id, Box(0, 0, 100, 100), RegionKind.TextBubble, 0.9f, ja = ja)

    private suspend fun viOf(ja: String, vi: String): String? {
        val job = PageJob(
            jobId = "t", frameHash = "f", contentKey = "c",
            pageWidth = 100, pageHeight = 100,
            bubbles = listOf(bubble(1, ja)), readingOrder = listOf(1),
        )
        val t = BubbleTranslation(1, EchoGate.normalize(ja, 2), vi)
        val ev = TranslateFilter(Fake(listOf(t)), { emptyList() }).stream(job).toList()
        val done = ev.filterIsInstance<app.mangatrans.domain.PageEvent.Done>().last()
        return done.job.bubbles.first { it.id == 1 }.vi
    }

    @Test
    fun `bo duoi ngat hoi dinh sau tu tieng Viet`() = runTest {
        assertEquals("Đến rồi♡", viOf("あぁあ♡きたぁッ♡", "Đến rồiッ♡"))
    }

    @Test
    fun `bo ca chum hai ky tu`() = runTest {
        assertEquals("Niêm mạc cọ xát nhau", viOf("ナマの粘膜擦れあってるッッ", "Niêm mạc cọ xát nhauッッ"))
    }

    @Test
    fun `GIU doan dai hon hai ky tu vi co the la ten rieng`() = runTest {
        val vi = viOf("パパのザーメンアズのおマンコにちょうだい", "Cho tao cái mông của アズちゃん")
        assertEquals("Cho tao cái mông của アズちゃん", vi)
    }

    @Test
    fun `khong doi gi khi ban dich von da sach`() = runTest {
        assertEquals("Này...", viOf("おい…", "Này..."))
    }

    @Test
    fun `giu nguyen neu bo xong khong con chu nao`() = runTest {
        assertEquals("ッ", viOf("…ッ", "ッ"))
    }
}
