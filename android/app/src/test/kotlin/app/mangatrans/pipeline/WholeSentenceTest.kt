package app.mangatrans.pipeline

import app.mangatrans.ports.SurfaceProblem
import app.mangatrans.ports.glossarySurfaceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F86 — o sua bong thoai dua NGUYEN VAN ca bong lam mat chu glossary.
 *
 * Bon chuoi "phai chan" duoi day la bon muc NGUOI DUNG THAT da lo tao trong
 * mot phien; bon chuoi "phai cho qua" lay tu bo mau 413 muc.
 *
 * Kiem ca LY DO chu khong chi kiem chan/khong: mot chuoi ngan co dau `!` va
 * mot cau dai 23 ky tu deu bi chan, nhung neu bao chung cung mot ly do thi
 * dong chu hien ra noi sai su that voi mot trong hai truong hop.
 */
class WholeSentenceTest {

    @Test
    fun `chan ca cau — bon muc that nguoi dung da lo tao`() {
        // 23, 18 va 10 ky tu: qua nguong do dai.
        assertEquals(SurfaceProblem.TooLong, glossarySurfaceProblem("固形物みたいなせーしブリブリひねり出してるッ♡"))
        assertEquals(SurfaceProblem.TooLong, glossarySurfaceProblem("娘と同い年のJKにナマ中だし・・・ッ"))
        assertEquals(SurfaceProblem.TooLong, glossarySurfaceProblem("こんなに濃いのッ!?"))
        // 8 ky tu — duoi nguong do dai, chi dau ket cau moi bat duoc.
        assertEquals(SurfaceProblem.HasMarks, glossarySurfaceProblem("せーし重いい…ッ"))
    }

    @Test
    fun `cho qua cum ngan — thu glossary sinh ra de phuc vu`() {
        assertNull(glossarySurfaceProblem("ちんこ"))
        assertNull(glossarySurfaceProblem("中に出し"))
        assertNull(glossarySurfaceProblem("幼馴染"))
        assertNull(glossarySurfaceProblem("アズ"))
        assertNull(glossarySurfaceProblem("ザーメン"))
    }

    /**
     * Muc DAI NHAT trong bo mau 413 (9 ky tu, khong dau ket cau) — nguong 9 se
     * chan oan no, nen nguong phai la 10.
     */
    @Test
    fun `cum co dinh 9 ky tu khong bi chan oan`() {
        assertNull(glossarySurfaceProblem("お世話になりました"))
    }

    /**
     * Do tren may that: bong `でるっ!` chi dai 4 ky tu. Chan la dung — dau `!`
     * se bi mo hinh chep ra ban dich — nhung ly do phai la HasMarks, khong
     * duoc bao la "ca mot cau".
     */
    @Test
    fun `dau ket cau bat duoc chuoi ngan, va bao dung ly do`() {
        assertEquals(SurfaceProblem.HasMarks, glossarySurfaceProblem("でるっ!"))
        assertEquals(SurfaceProblem.HasMarks, glossarySurfaceProblem("え!?"))
        assertEquals(SurfaceProblem.HasMarks, glossarySurfaceProblem("パパだけ♡"))
        assertEquals(SurfaceProblem.HasMarks, glossarySurfaceProblem("そうか。"))
    }

    @Test
    fun `khoang trang thua khong lam doi ket qua`() {
        assertNull(glossarySurfaceProblem("  ちんこ  "))
    }
}
