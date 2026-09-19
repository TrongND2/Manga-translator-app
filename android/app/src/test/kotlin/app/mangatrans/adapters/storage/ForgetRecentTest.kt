package app.mangatrans.adapters.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `forgetRecent` — xoa ban dich cua N trang DICH gan day nhat.
 *
 * Test dung `newestFirst` chu khong dung `FileCache` truc tiep: `FileCache`
 * doc JSON, ma `org.json` trong unit test la stub cua android.jar (moi ham tra
 * gia tri mac dinh), nen dat test o do thi khong kiem duoc gi.
 */
class ForgetRecentTest {

    private fun f(name: String) = File(name)

    @Test
    fun `lay dung N muc moi nhat, moi truoc cu sau`() {
        val got = newestFirst(
            listOf(
                f("cu.json") to 100L,
                f("moi-nhat.json") to 300L,
                f("giua.json") to 200L,
            ),
            2,
        ).map { it.name }
        assertEquals(listOf("moi-nhat.json", "giua.json"), got)
    }

    @Test
    fun `xin nhieu hon so muc dang co thi tra ve het, khong nem loi`() {
        val got = newestFirst(listOf(f("a.json") to 1L, f("b.json") to 2L), 99)
        assertEquals(2, got.size)
    }

    @Test
    fun `xin 0 trang thi khong chon gi`() {
        val got = newestFirst(listOf(f("a.json") to 1L), 0)
        assertEquals(emptyList<File>(), got)
    }

    @Test
    fun `cache rong thi khong chon gi`() {
        assertEquals(emptyList<File>(), newestFirst(emptyList(), 5))
    }

    /**
     * Hai muc trung moc mili giay phai ra thu tu CO DINH, khong phu thuoc thu
     * tu `listFiles()` tra ve. Khong chot thi test do nhap nhay va nguoi dung
     * xoa "3 trang gan nhat" hai lan se ra hai ket qua khac nhau.
     */
    @Test
    fun `trung moc thoi gian thi chot theo ten file`() {
        val a = newestFirst(listOf(f("b.json") to 5L, f("a.json") to 5L), 1).map { it.name }
        val b = newestFirst(listOf(f("a.json") to 5L, f("b.json") to 5L), 1).map { it.name }
        assertEquals(a, b)
        assertEquals(listOf("a.json"), a)
    }

    /**
     * Cot loi cua tinh nang: muc **dich** gan day thang muc **doc** gan day.
     *
     * `FileCache.get` co `setLastModified` de phuc vu LRU, nen mot trang cu mo
     * lai hom nay se co `lastModified` moi tinh. Neu xep theo mtime thi no bi
     * tinh la "vua dich" va bi xoa oan, con trang thuc su vua dich thi song
     * sot. Vi the `put` luu rieng moc `at`.
     */
    @Test
    fun `moc dich thang moc doc`() {
        val vuaDich = f("vua-dich.json") to 2_000L     // at: dich luc 2000
        val cuNhungVuaDoc = f("cu.json") to 1_000L     // at: dich luc 1000
        val got = newestFirst(listOf(cuNhungVuaDoc, vuaDich), 1).map { it.name }
        assertEquals(listOf("vua-dich.json"), got)
    }
}
