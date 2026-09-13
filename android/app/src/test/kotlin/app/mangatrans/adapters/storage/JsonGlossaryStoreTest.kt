package app.mangatrans.adapters.storage

import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.GlossaryKind
import app.mangatrans.ports.GlossaryStatus
import app.mangatrans.ports.isUsableSurface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Story 2.8 / 2.9 — AD-7 (moi thay doi di qua store) va AD-8 (muc tu de xuat
 * KHONG duoc vao prompt truoc khi nguoi dung xac nhan).
 *
 * Thu nguy hiem duoc kiem o day: mot muc `Proposed` lot vao `confirmed()` se
 * duoc dua thang vao prompt cua MOI trang dich sau do. LLM se dung no nhat
 * quan va troi chay, nen ban dich sai TRONG SUOT ma khong co dau hieu gi —
 * dung kieu loi ma AD-4 mo ta.
 */
class JsonGlossaryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(): JsonGlossaryStore =
        JsonGlossaryStore(File(tmp.root, "glossary.json"))

    private fun entry(
        surface: String,
        meaning: String = "nghia",
        kind: GlossaryKind = GlossaryKind.ProperNoun,
        status: GlossaryStatus = GlossaryStatus.Proposed,
    ) = GlossaryEntry("default", surface, meaning, kind, status)

    @Test
    fun `muc de xuat khong lot vao danh sach dua vao prompt`() = runTest {
        val s = store()
        s.propose(entry("コマ"))

        assertEquals(listOf("コマ"), s.proposed("default").map { it.surface })
        assertTrue("Proposed KHONG duoc vao prompt", s.confirmed("default").isEmpty())
    }

    @Test
    fun `xac nhan chuyen muc sang danh sach dua vao prompt`() = runTest {
        val s = store()
        s.propose(entry("桔梗"))
        s.confirm("default", "桔梗")

        assertEquals(listOf("桔梗"), s.confirmed("default").map { it.surface })
        assertTrue(s.proposed("default").isEmpty())
    }

    @Test
    fun `propose khong ghi de muc nguoi dung da sua`() = runTest {
        val s = store()
        s.upsertByUser(entry("コマ", meaning = "Koma (tên nhân vật)"))
        // Pipeline gap lai ten nay o trang sau va de xuat tiep.
        s.propose(entry("コマ", meaning = "コマ"))

        val all = s.confirmed("default")
        assertEquals(1, all.size)
        assertEquals("Koma (tên nhân vật)", all.single().meaning)
        assertTrue("khong duoc ha muc da xac nhan xuong Proposed", s.proposed("default").isEmpty())
    }

    @Test
    fun `nguoi dung nhap tay thi vao thang danh sach dung`() = runTest {
        val s = store()
        s.upsertByUser(entry("ソデにする", "cho leo cây", GlossaryKind.Idiom, GlossaryStatus.Proposed))

        // Du truyen vao Proposed, `upsertByUser` phai ep thanh Confirmed:
        // nguoi dung vua go tay thi khong con gi de tu xac nhan nua.
        assertEquals(GlossaryStatus.Confirmed, s.confirmed("default").single().status)
    }

    @Test
    fun `sua surface phai xoa muc cu chu khong de lai hai muc mau thuan`() = runTest {
        val s = store()
        s.upsertByUser(entry("コマ", "con chim"))

        // Day dung la cach GlossaryActivity lam khi nguoi dung doi o nguyen ban.
        s.delete("default", "コマ")
        s.upsertByUser(entry("コマ鳥", "Komadori"))

        val all = s.confirmed("default")
        assertEquals("chi duoc con mot muc", 1, all.size)
        assertEquals("コマ鳥", all.single().surface)
    }

    @Test
    fun `ghi roi doc lai giu nguyen ca ba loai`() = runTest {
        val a = store()
        a.upsertByUser(entry("桔梗", "Kikyou", GlossaryKind.ProperNoun))
        a.upsertByUser(entry("ソデにする", "cho leo cây", GlossaryKind.Idiom))
        a.upsertByUser(entry("お前", "cậu", GlossaryKind.Address))

        // Store moi tren cung file = mo lai app.
        val kinds = store().confirmed("default").map { it.kind }.toSet()
        assertEquals(GlossaryKind.entries.toSet(), kinds)
    }

    @Test
    fun `file hong thi tra ve rong chu khong lam sap app`() = runTest {
        val f = File(tmp.root, "glossary.json")
        f.writeText("{ day khong phai JSON hop le")

        assertTrue(JsonGlossaryStore(f).confirmed("default").isEmpty())
    }

    @Test
    fun `xoa muc khong ton tai khong lam hong cac muc con lai`() = runTest {
        val s = store()
        s.upsertByUser(entry("桔梗", "Kikyou"))
        s.delete("default", "khong-co-muc-nay")

        assertEquals(1, s.confirmed("default").size)
    }

    /**
     * Do thay TREN MAY THAT: `speaker` do LLM tra ve sinh ra ba muc rac chi sau
     * mot lan chay bon trang. Muc rac lot vao prompt thi lam hong moi trang sau.
     */
    @Test
    fun `chi nhan surface co ky tu tieng Nhat`() {
        // Rac that, chep nguyen tu glossary.json tren may.
        assertTrue("Nguoi noi 1 phai bi loai", !isUsableSurface("Người nói 1"))
        assertTrue("Rurimaru phai bi loai", !isUsableSurface("Rurimaru"))
        assertTrue(!isUsableSurface("?"))
        assertTrue(!isUsableSurface("   "))

        // That su la tieng Nhat — ca ba he chu.
        assertTrue(isUsableSurface("瑠璃丸"))       // kanji
        assertTrue(isUsableSurface("ソデにする"))    // katakana + hiragana
        assertTrue(isUsableSurface("お前"))         // hiragana + kanji
    }
}
