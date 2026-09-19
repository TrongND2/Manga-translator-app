package app.mangatrans.pipeline

import app.mangatrans.ports.looksLikeWholeSentence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F86 — o sua bong thoai dua NGUYEN VAN ca bong lam mat chu glossary.
 *
 * Bon chuoi "phai chan" duoi day la bon muc NGUOI DUNG THAT da lo tao trong
 * mot phien; bon chuoi "phai cho qua" lay tu bo mau 413 muc.
 */
class WholeSentenceTest {

    @Test
    fun `chan ca cau — bon muc that nguoi dung da lo tao`() {
        assertTrue(looksLikeWholeSentence("せーし重いい…ッ"))
        assertTrue(looksLikeWholeSentence("固形物みたいなせーしブリブリひねり出してるッ♡"))
        assertTrue(looksLikeWholeSentence("娘と同い年のJKにナマ中だし・・・ッ"))
        assertTrue(looksLikeWholeSentence("こんなに濃いのッ!?"))
    }

    @Test
    fun `cho qua cum ngan — thu glossary sinh ra de phuc vu`() {
        assertFalse(looksLikeWholeSentence("ちんこ"))
        assertFalse(looksLikeWholeSentence("中に出し"))
        assertFalse(looksLikeWholeSentence("幼馴染"))
        assertFalse(looksLikeWholeSentence("アズ"))
        assertFalse(looksLikeWholeSentence("ザーメン"))
    }

    /**
     * Muc DAI NHAT trong bo mau 413 (9 ky tu, khong dau ket cau) — nguong 9 se
     * chan oan no, nen nguong phai la 10.
     */
    @Test
    fun `cum co dinh 9 ky tu khong bi chan oan`() {
        assertFalse(looksLikeWholeSentence("お世話になりました"))
    }

    /** Dau ket cau bat duoc ca chuoi NGAN ma nguong do dai bo sot. */
    @Test
    fun `dau ket cau bat duoc chuoi ngan`() {
        assertTrue(looksLikeWholeSentence("え!?"))
        assertTrue(looksLikeWholeSentence("パパだけ♡"))
        assertTrue(looksLikeWholeSentence("そうか。"))
    }

    @Test
    fun `khoang trang thua khong lam doi ket qua`() {
        assertFalse(looksLikeWholeSentence("  ちんこ  "))
    }
}
