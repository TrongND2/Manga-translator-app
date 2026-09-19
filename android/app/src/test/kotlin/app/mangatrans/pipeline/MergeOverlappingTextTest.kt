package app.mangatrans.pipeline

import app.mangatrans.domain.Box
import app.mangatrans.domain.RegionKind
import app.mangatrans.ports.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F78 — mot bong thoai bi detector cat thanh HAI hop chong nhau.
 *
 * Moi con so trong file nay lay tu log `Geom` cua mot trang that tren M52,
 * khong phai so bia ra. Doi chung la mat luon y nghia cua phep kiem.
 */
class MergeOverlappingTextTest {

    private fun text(x: Int, y: Int, w: Int, h: Int, score: Float = 0.9f) =
        Detection(Box(x, y, x + w, y + h), RegionKind.TextBubble, score)

    private fun shell(x: Int, y: Int, w: Int, h: Int) =
        Detection(Box(x, y, x + w, y + h), RegionKind.Bubble, 0.9f)

    /**
     * Truong hop that: `#20` va `#24` la hai manh cua CUNG mot bong thoai,
     * chong nhau 30x262 px = 32,4% hop nho hon. OCR doc trung cot
     * 「俺だけなのか!?」 va mo hinh nhan mot manh cau cut.
     */
    @Test
    fun `hai manh cua cung mot bong thi gop lam mot`() {
        val out = mergeOverlappingText(listOf(text(935, 1457, 73, 332), text(823, 1527, 142, 384)))
        assertEquals("phai con dung mot hop", 1, out.size)
        // Hop gop phai trum het CA HAI, neu khong la mat chu o mep.
        assertEquals(Box(823, 1457, 1008, 1911), out[0].box)
    }

    /**
     * Hai bong RIENG chi cham via nhau (`#13` x `#17` = 8,5%) thi tuyet doi
     * khong duoc gop — gop la hai cau thoai khac nhau dinh vao lam mot.
     */
    @Test
    fun `hai bong rieng chi cham via thi giu nguyen`() {
        val out = mergeOverlappingText(listOf(text(145, 682, 52, 231), text(180, 853, 87, 241)))
        assertEquals(2, out.size)
    }

    /** `#24` x `#26` = 1,1% — xa nguong hon nua. */
    @Test
    fun `cham 1 phan tram thi giu nguyen`() {
        val out = mergeOverlappingText(listOf(text(823, 1527, 142, 384), text(744, 1633, 80, 326)))
        assertEquals(2, out.size)
    }

    /** Trang khong co cap nao chong nhau thi ham nay khong duoc dong gi. */
    @Test
    fun `trang khong chong nhau thi giu nguyen het`() {
        val page70 = listOf(
            text(520, 87, 129, 308), text(765, 280, 118, 331), text(341, 934, 167, 303),
            text(195, 1000, 86, 200), text(801, 1550, 125, 244), text(139, 1866, 137, 436),
        )
        assertEquals(page70.size, mergeOverlappingText(page70).size)
    }

    /**
     * ⚠️ Vo bong long nhau la chuyen BINH THUONG — `GateFilter` co luat rieng
     * cho no. Gop vo bong lai se pha luat do, nen ham nay khong duoc dung toi.
     */
    @Test
    fun `vo bong long nhau thi khong bao gio bi gop`() {
        val out = mergeOverlappingText(listOf(shell(100, 100, 400, 400), shell(120, 120, 200, 200)))
        assertEquals(2, out.count { it.kind == RegionKind.Bubble })
    }

    /** Ba manh noi duoi nhau phai rut ve MOT, khong phai hai. */
    @Test
    fun `gop bac cau qua ba manh`() {
        val out = mergeOverlappingText(listOf(
            text(0, 0, 100, 100), text(60, 0, 100, 100), text(120, 0, 100, 100),
        ))
        assertEquals(1, out.size)
        assertEquals(Box(0, 0, 220, 100), out[0].box)
    }

    /** Hop gop khong duoc kem tin hon manh tin nhat cua no. */
    @Test
    fun `diem cua hop gop lay theo manh cao nhat`() {
        val out = mergeOverlappingText(listOf(
            text(935, 1457, 73, 332, score = 0.62f),
            text(823, 1527, 142, 384, score = 0.91f),
        ))
        assertTrue(out[0].score == 0.91f)
    }
}
