package app.mangatrans.pipeline

import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.BubbleState
import app.mangatrans.domain.PageJob
import app.mangatrans.domain.RegionKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 2.3 — cong loc vung khong co thoai (AD-5).
 *
 * Kiem dung thu nguy hiem: vung `text_bubble` do detector bat nham tren manh
 * tranh (khong nam trong bubble nao) phai bi chan TRUOC khi den OCR — vi
 * manga-ocr se bia chu ra chu khong bao loi (FINDINGS F2).
 */
class GateFilterTest {

    private fun job(vararg b: Bubble) = PageJob(
        jobId = "t", frameHash = "f", contentKey = "c",
        pageWidth = 974, pageHeight = 1400, bubbles = b.toList(),
    )

    private fun shell(id: Int, box: Box) =
        Bubble(id, box, RegionKind.Bubble, 0.9f)

    private fun text(id: Int, box: Box) =
        Bubble(id, box, RegionKind.TextBubble, 0.9f)

    @Test
    fun `text_bubble nam tron trong bubble thi duoc giu`() = runTest {
        val j = job(
            shell(0, Box(100, 100, 300, 400)),
            text(1, Box(120, 120, 280, 380)),
        )
        val out = GateFilter().apply(j)
        assertEquals(BubbleState.Accepted, out.bubbles.first { it.id == 1 }.state)
        assertEquals(1, out.translatable.size + out.bubbles.count { it.id == 1 && it.ja.isBlank() } - 1 + 1)
    }

    @Test
    fun `text_bubble khong nam trong bubble nao thi bi chan`() = runTest {
        // Day chinh la truong hop F2: detector bat nham mot manh tranh.
        val j = job(
            shell(0, Box(100, 100, 300, 400)),
            text(1, Box(600, 800, 700, 900)),   // xa han vung bubble
        )
        val out = GateFilter().apply(j)
        assertEquals(
            "vung khong nam trong bubble phai bi danh Suspect, khong duoc OCR",
            BubbleState.Suspect, out.bubbles.first { it.id == 1 }.state
        )
    }

    @Test
    fun `nam mot phan duoi nguong thi bi chan`() = runTest {
        val j = job(
            shell(0, Box(0, 0, 100, 100)),
            text(1, Box(50, 50, 150, 150)),   // chi 25% nam trong
        )
        val out = GateFilter().apply(j)
        assertEquals(BubbleState.Suspect, out.bubbles.first { it.id == 1 }.state)
    }

    @Test
    fun `vo bong rong khong bao gio duoc OCR`() = runTest {
        val j = job(shell(0, Box(0, 0, 100, 100)))
        val out = GateFilter().apply(j)
        assertEquals(BubbleState.Suspect, out.bubbles.first().state)
    }

    @Test
    fun `text_free ngoai pham vi MVP`() = runTest {
        // Detector gan nhu khong bat duoc loai nay (F4: 1 box tren 6 trang).
        val j = job(Bubble(0, Box(0, 0, 50, 50), RegionKind.TextFree, 0.9f))
        val out = GateFilter().apply(j)
        assertEquals(BubbleState.Suspect, out.bubbles.first().state)
    }

    @Test
    fun `thong ke doi chieu duoc voi ty le do o Phase 0`() = runTest {
        // 12 text_bubble nam trong 12 bubble — giong trang tubaki_010 (F4).
        val bs = buildList {
            repeat(12) { i ->
                val y = i * 100
                add(shell(i * 2, Box(0, y, 200, y + 90)))
                add(text(i * 2 + 1, Box(10, y + 10, 190, y + 80)))
            }
        }
        val out = GateFilter().apply(job(*bs.toTypedArray()))
        val st = gateStats(out)
        assertEquals(12, st.kept)
        assertEquals(0, st.dropped)
    }
}
