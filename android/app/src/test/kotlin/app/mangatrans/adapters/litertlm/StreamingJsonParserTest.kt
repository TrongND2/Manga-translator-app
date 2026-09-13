package app.mangatrans.adapters.litertlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser doc tung bubble NGAY KHI hoan chinh — dieu kien de dat NFR-005 (<= 8s).
 *
 * Neu doi ca trang sinh xong moi parse thi bubble dau ve sau 169 giay
 * (da do that tren M52).
 */
class StreamingJsonParserTest {

    @Test
    fun `phat bubble ngay khi hoan chinh, khong doi ca trang`() {
        val p = StreamingJsonParser()

        // Chua du mot object nao.
        assertEquals(0, p.feed("""{"bubbles":[{"id":0,"jaEcho":"俺ァ",""").size)

        // Object dau tien vua du -> phai ra NGAY.
        val first = p.feed(""""vi":"Tao lại nóng rực","speaker":"Rurimaru"},""")
        assertEquals(1, first.size)
        assertEquals(0, first[0].id)
        assertEquals("Tao lại nóng rực", first[0].vi)
        assertEquals("俺ァ", first[0].jaEcho)

        // Object thu hai.
        val second = p.feed("""{"id":1,"jaEcho":"では","vi":"Vậy tôi xin phép"}]}""")
        assertEquals(1, second.size)
        assertEquals(1, second[0].id)
    }

    @Test
    fun `ban dich chua ngoac nhon khong lam vo parser`() {
        val p = StreamingJsonParser()
        val out = p.feed("""{"bubbles":[{"id":3,"jaEcho":"はァ","vi":"Hả {cái gì} đây?"}]}""")
        assertEquals(1, out.size)
        assertEquals("Hả {cái gì} đây?", out[0].vi)
    }

    @Test
    fun `dau nhay escape trong ban dich`() {
        val p = StreamingJsonParser()
        val out = p.feed("""{"bubbles":[{"id":4,"jaEcho":"あの","vi":"Nó nói \"khoan\" rồi"}]}""")
        assertEquals(1, out.size)
        assertTrue(out[0].vi.contains("khoan"))
    }

    @Test
    fun `chia nho tung ky tu van doc duoc`() {
        val full = """{"bubbles":[{"id":7,"jaEcho":"瑠璃","vi":"Rurimaru à"},{"id":9,"jaEcho":"まァ","vi":"Này thầy ơi"}]}"""
        val p = StreamingJsonParser()
        val got = buildList { full.forEach { addAll(p.feed(it.toString())) } }
        assertEquals(2, got.size)
        assertEquals(listOf(7, 9), got.map { it.id })
    }

    @Test
    fun `bo qua object bao ngoai khong co id`() {
        val p = StreamingJsonParser()
        // Model doi khi boc them mot lop.
        val out = p.feed("""{"result":{"bubbles":[{"id":2,"jaEcho":"他の","vi":"Mày định"}]}}""")
        assertEquals(1, out.size)
        assertEquals(2, out[0].id)
    }

    @Test
    fun `speaker rong hoac dau hoi thi coi nhu khong co`() {
        val p = StreamingJsonParser()
        val out = p.feed("""{"bubbles":[{"id":1,"jaEcho":"あ","vi":"A","speaker":"?"}]}""")
        assertEquals(null, out[0].speaker)
    }

    @Test
    fun `drain doc not phan con lai khi thieu dau dong`() {
        val p = StreamingJsonParser()
        // Model bi cat cut, thieu `]}` o cuoi.
        p.feed("""{"bubbles":[{"id":0,"jaEcho":"俺","vi":"Tao"}""")
        val rest = p.drain()
        // Object dau da duoc phat o feed roi, drain khong duoc phat trung.
        assertEquals(0, rest.size)
    }
}
