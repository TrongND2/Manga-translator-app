package app.mangatrans.adapters.assets

import app.mangatrans.ports.PackageManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Epic 4 / AD-15 — manifest quyet dinh tai gi va co duoc nap hay khong.
 *
 * Thu nguy hiem duoc kiem o day: mot manifest doc sai se lam app tai nham file,
 * hoac **chap nhan mot goi khong tuong thich**. Ca hai deu khong bao loi ngay
 * ma hong o cho khac, muon hon nhieu.
 */
class ModelStoreTest {

    private val json = """
        {
          "packageVersion": "1.0.0",
          "minAppVersion": 2,
          "maxAppVersion": 5,
          "files": [
            { "name": "a.onnx", "sizeBytes": 100, "sha256": "AABB", "url": "https://x/a" },
            { "name": "b.litertlm", "sizeBytes": 2588147712, "sha256": "ccdd", "url": "https://x/b" }
          ]
        }
    """.trimIndent()

    @Test
    fun `doc du cac truong cua manifest`() {
        val m = ModelStore.parseManifest(json)
        assertEquals("1.0.0", m.packageVersion)
        assertEquals(2, m.files.size)
        assertEquals("a.onnx", m.files[0].name)
        assertEquals("https://x/b", m.files[1].url)
    }

    /**
     * sha256 chu HOA trong manifest phai khop voi chuoi chu thuong ma
     * `MessageDigest` sinh ra. Khong chuan hoa thi moi file deu bi coi la hong
     * — va thong bao se noi "file tai ve bi hong", tuc la chi sai cho khac.
     */
    @Test
    fun `sha256 duoc ha ve chu thuong`() {
        val m = ModelStore.parseManifest(json)
        assertEquals("aabb", m.files[0].sha256)
        assertEquals("ccdd", m.files[1].sha256)
    }

    /** File 2.59 GB vuot Int — doc bang Long, neu khong se tran am. */
    @Test
    fun `kich thuoc file lon hon 2GB khong bi tran`() {
        val m = ModelStore.parseManifest(json)
        assertEquals(2_588_147_712L, m.files[1].sizeBytes)
        assertTrue("tong phai duong", m.totalBytes > 0)
    }

    /** AD-15 — app ngoai khoang thi TU CHOI NAP, khong phai canh bao roi nap. */
    @Test
    fun `khoang phien ban tuong thich la khoang dong`() {
        val m = ModelStore.parseManifest(json)
        assertFalse("duoi khoang", m.supports(1))
        assertTrue("can duoi", m.supports(2))
        assertTrue("giua", m.supports(3))
        assertTrue("can tren", m.supports(5))
        assertFalse("tren khoang", m.supports(6))
    }

    @Test
    fun `tong dung luong cong het cac file`() {
        val m = ModelStore.parseManifest(json)
        assertEquals(100L + 2_588_147_712L, m.totalBytes)
    }

    @Test(expected = org.json.JSONException::class)
    fun `manifest thieu truong thi nem loi chu khong im lang tra rong`() {
        ModelStore.parseManifest("""{ "packageVersion": "1.0.0" }""")
    }

    @Test
    fun `manifest khong co file nao thi tong bang khong`() {
        val m: PackageManifest = ModelStore.parseManifest(
            """{"packageVersion":"1","minAppVersion":1,"maxAppVersion":1,"files":[]}"""
        )
        assertEquals(0L, m.totalBytes)
    }
}
