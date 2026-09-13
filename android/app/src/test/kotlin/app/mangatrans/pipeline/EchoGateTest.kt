package app.mangatrans.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 2.6 — cong toan ven phai bat duoc lech THAT ma khong bao dong gia.
 *
 * Du lieu lay tu phep do that o Phase 0 (spike/out/story13_*.json), khong phai
 * du lieu bia. Do la diem mau chot: test nay tai hien dung nhung gi model
 * that su da tra ve.
 */
class EchoGateTest {

    /** 12 bubble that cua trang tubaki_010. */
    private val truth = mapOf(
        0 to "俺ァまた悦の色香でアソコが疼いて",
        1 to "では俺は失礼してー",
        2 to "他の女の所へシケ込むつもりなのかとよォ",
        3 to "瑠璃丸ったら～～",
        4 to "まァまァ先生せっかくだ交じって行きなせェ",
        5 to "噂じゃまだなんだろう？",
        6 to "はァ？",
        7 to "高級女郎をソデにしたって言うじゃねェか！",
        8 to "憎たらしいねェ",
        9 to "ちょっ離ーー",
        10 to "俺の事は放っておいてくれー",
        11 to "素人女で筆下ろしといこうや",
    )

    private fun echoOf(m: Map<Int, String>, n: Int = 2) =
        m.mapValues { EchoGate.normalize(it.value, n) }

    @Test
    fun `dap an dung thi khong bao dong`() {
        val answer = echoOf(truth)
        assertEquals(emptyList<Int>(), EchoGate.mismatches(truth, answer))
    }

    @Test
    fun `lech mot o thi bat duoc het`() {
        // Tiem dung loi F10: bubble i nhan noi dung cua bubble i+1.
        val shifted = truth.keys.associateWith { id ->
            EchoGate.normalize(truth[id + 1] ?: truth[id], 2)
        }
        val bad = EchoGate.mismatches(truth, shifted)
        // Bubble cuoi tro ve chinh no nen khop; 11 bubble con lai phai bi bat.
        assertEquals(11, bad.size)
    }

    @Test
    fun `nhieu ky tu KHONG duoc coi la lech`() {
        // Hai truong hop bao dong gia THAT SU da gap khi so khop chinh xac (F14).
        val noisy = echoOf(truth).toMutableMap()
        noisy[5] = EchoGate.normalize("何を当て", 2)   // model doi tro tu を/が
        noisy[10] = EchoGate.normalize("どうせ無", 2)  // model bo dau gach dau cau

        // Chung khong khop nguyen ban cua id 5 va 10, nen VAN bi bat — dung.
        // Diem can kiem la co che chuan hoa: dau gach dau cau khong duoc tinh la lech.
        val dash = mapOf(1 to "―どうせ無理に決まっている")
        val noDash = mapOf(1 to EchoGate.normalize("どうせ無理", 2))
        assertEquals(
            "dau gach dau cau phai bi bo khi chuan hoa",
            emptyList<Int>(), EchoGate.mismatches(dash, noDash)
        )
    }

    @Test
    fun `chuan hoa bo dau cau va dang toan rong`() {
        assertEquals("俺ァ", EchoGate.normalize("俺ァまた", 2))
        assertEquals("どう", EchoGate.normalize("―どうせ", 2))
        assertEquals("はァ", EchoGate.normalize("はァ？", 2))
        // Dang toan rong phai duoc NFKC dua ve nua rong.
        assertEquals("AB", EchoGate.normalize("ＡＢＣ", 2))
    }

    @Test
    fun `sai mot ky tu van duoc chap nhan`() {
        assertTrue(EchoGate.withinOneEdit("何を", "何が"))
        assertTrue(EchoGate.withinOneEdit("どう", "どう"))
        assertTrue(EchoGate.withinOneEdit("あい", "あいう"))   // thieu 1
        // Sai hai ky tu tren chuoi dai 2 = khac hoan toan -> phai bi bat.
        assertTrue(!EchoGate.withinOneEdit("何を", "誰が"))
    }

    @Test
    fun `bat duoc id thua`() {
        // Da gap that: trang tubaki_025 model tra 13 bubble trong khi vao 12 (F19).
        val answer = echoOf(truth) + (12 to "XX")
        assertEquals(listOf(12), EchoGate.extraneousIds(truth, answer))
    }

    @Test
    fun `box nam trong bubble tinh dung ty le`() {
        val outer = app.mangatrans.domain.Box(0, 0, 100, 100)
        val inside = app.mangatrans.domain.Box(10, 10, 20, 20)
        val half = app.mangatrans.domain.Box(90, 90, 110, 110)
        val outside = app.mangatrans.domain.Box(200, 200, 210, 210)
        assertEquals(1.0, inside.containedIn(outer), 1e-9)
        assertEquals(0.25, half.containedIn(outer), 1e-9)
        assertEquals(0.0, outside.containedIn(outer), 1e-9)
    }
}
