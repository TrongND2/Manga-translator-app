package app.mangatrans.ports

import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.PageJob
import app.mangatrans.domain.RegionKind
import kotlinx.coroutines.flow.Flow

/**
 * Cac port — interface ma `adapters` cai dat.
 *
 * Khai bao bang khai niem MIEN, khong lo cong nghe: khong co `Bitmap`, `Tensor`,
 * `OrtSession`, token hay bat cu thu gi cua runtime. Doi adapter khong duoc lam
 * doi chu ky o day.
 */

/** Buoc 1 — phat hien vung chu. FR-020, FR-021, FR-024. */
interface TextDetector {
    suspend fun detect(image: PageImage, minScore: Float): List<Detection>
}

data class Detection(val box: Box, val kind: RegionKind, val score: Float)

/** Buoc 3 — doc chu Nhat. FR-022, FR-024. */
interface OcrEngine {
    /** Tra ve chuoi rong neu vung khong co chu doc duoc — KHONG nem exception. */
    suspend fun read(image: PageImage, box: Box): String
}

/**
 * Buoc 4 — dich. FR-030, FR-035..038.
 *
 * AD-3: KHONG co ham nao nhan mot bubble don le. Chu ky chi nhan CA TRANG.
 * Ai muon chia nho phai sua port nay, tuc phai doc lai AD-3 truoc.
 *
 * AD-17: tra `Flow`, khong tra tron goi. Moi phan tu duoc xac thuc ngay khi
 * ve (AD-6) roi moi phat ra ngoai de ve.
 */
interface Translator {
    /**
     * @param continuing dot nay la dot THU HAI tro di cua **cung mot trang**.
     *   Nguoi cai dat duoc phep dung lai phien lam viec cua dot truoc de khoi
     *   phai doc lai phan dau vao co dinh.
     *
     *   Do tren may (M52): gui luot dau 2287 ky tu mat **16.200 ms** truoc khi
     *   ra chu dau tien; gui tiep 24 ky tu tren CUNG mot phien chi mat **1.949
     *   ms**. Tuc la phan da doc duoc giu lai, khong phai doc lai.
     *
     *   Nguoi goi phai bao [endPage] khi trang xong, du thanh hay bai.
     */
    fun translate(
        page: PageJob,
        glossary: List<GlossaryEntry>,
        continuing: Boolean = false,
    ): Flow<BubbleTranslation>

    /** Ket thuc mot trang — bo phien lam viec dang giu (neu co). */
    suspend fun endPage() {}

    /** AD-20/AD-24 — nap truoc, khong de den luc nguoi dung cham icon. */
    suspend fun warmUp()

    /** AD-24 — nha engine khi qua N phut khong dung. 3200 MB -> 84 MB. */
    suspend fun release()

    val isWarm: Boolean
}

/**
 * Mot bubble do LLM tra ve.
 *
 * AD-6: `jaEcho` la 2 ky tu dau cua NGUYEN BAN tieng Nhat, do chinh LLM chep lai.
 * Thu tu truong trong JSON BAT BUOC la id -> jaEcho -> vi, de xac thuc duoc
 * ngay khi tung bubble chay ve, truoc khi ban dich cua no ve xong.
 */
data class BubbleTranslation(
    val id: Int,
    val jaEcho: String,
    val vi: String,
    val speaker: String? = null,
)

/** AD-7 — MOI thay doi glossary di qua day. Khong ai duoc ghi thang xuong DB. */
interface GlossaryStore {
    suspend fun confirmed(seriesKey: String): List<GlossaryEntry>
    suspend fun proposed(seriesKey: String): List<GlossaryEntry>
    suspend fun propose(entry: GlossaryEntry)
    suspend fun confirm(seriesKey: String, surface: String)
    suspend fun upsertByUser(entry: GlossaryEntry)
    suspend fun delete(seriesKey: String, surface: String)
}

/**
 * AD-7 — glossary co BA loai, khong chi ten rieng.
 * FINDINGS F9: loai `Idiom` la thu keo chat luong tu 46% len 60%.
 */
data class GlossaryEntry(
    /** AD-19 — MVP dung mot glossary toan cuc, seriesKey = "default". */
    val seriesKey: String,
    /** Dang chu xuat hien trong nguyen ban, vi du `ソデにする`. */
    val surface: String,
    val meaning: String,
    val kind: GlossaryKind,
    val status: GlossaryStatus,
)

enum class GlossaryKind {
    /** Ten nhan vat, dia danh. */
    ProperNoun,
    /** Thanh ngu / tieng long theo boi canh truyen. Loai quan trong nhat (F9). */
    Idiom,
    /** Xung ho da chot giua mot cap nhan vat. */
    Address,
}

enum class GlossaryStatus {
    /** Tu tich luy (AD-8). KHONG duoc dua vao prompt cho toi khi nguoi dung xac nhan. */
    Proposed,
    /** Da xac nhan — duoc dua vao prompt. */
    Confirmed,
}

/** Hiragana, katakana, kanji. */
private val JAPANESE = Regex("[\u3040-\u30FF\u4E00-\u9FFF]")

/**
 * `surface` la dang chu xuat hien trong NGUYEN BAN tieng Nhat — glossary chi co
 * tac dung khi no khop duoc voi chu tren trang. Chuoi khong co ky tu Nhat nao
 * thi khong bao gio khop, chi lam ban prompt.
 *
 * KHONG phai lo xa, da thay that tren may: truong `speaker` do LLM tra ve sinh
 * ra `"Nguoi noi 1"`, `"Nguoi noi 2"` (nhan placeholder tieng Viet) va
 * `"Rurimaru"` (dang La-tinh, trung voi muc `瑠璃丸` da xac nhan). Ba muc rac
 * chi sau mot lan chay bon trang.
 *
 * De o `ports` chu khong o `adapters`: ca `pipeline` (luc de xuat) va
 * `adapters.storage` (GlossaryMiner) deu can, ma `pipeline` khong duoc phep
 * biet den `adapters`.
 */
fun isUsableSurface(s: String): Boolean =
    s.isNotBlank() && s != "?" && JAPANESE.containsMatchIn(s)

/**
 * Chuoi nay la CA MOT CAU chu khong phai mot cum dang cho vao glossary.
 *
 * Vi sao can chan: o sua bong thoai dua **nguyen van ca bong** lam mat chu khi
 * nguoi dung bam "Luu vao tu dien rieng". Mot cau nhu
 * 「固形物みたいなせーしブリブリひねり出してるッ♡」 chi khop dung cau do tren
 * dung trang do — khong bao gio dung lai duoc, ma van ngon prompt moi lan
 * trang do duoc dich.
 *
 * Va no con dat hon the: luu vao glossary keo theo `forgetContaining`, tuc
 * **xoa cache cua chinh trang vua dich xong**. Do tren may that: trang
 * `26b2dd420ce31ce1` dang hit cache trong 2,6 giay, sau khi luu mot cau vao
 * glossary thi lan sau phai dich lai tu dau (F86).
 *
 * Nguoi dung da tao **4 muc ca cau** theo duong nay chi trong mot phien, va
 * truoc do da phai don 16 muc tuong tu ra khoi tu dien.
 *
 * Nguong lay tu SO DO chu khong uoc chung. Tren bo mau 413 muc, mat chu dai
 * nhat la `お世話になりました` (9), ke tiep la 8 — con bon muc rac nguoi dung vua
 * tao dai 8, 10, 18, 23. Nen:
 *
 *   - **10 ky tu** tro len: dai hon moi muc hop le trong bo mau.
 *   - hoac co **dau ket cau / ky hieu cam xuc** — cai nay bat not muc 8 ky tu
 *     `せーし重いい…ッ` ma nguong do dai bo sot.
 *
 * Do lai voi nguong nay: **bon muc rac bi chan het, khong muc hop le nao bi
 * chan oan**.
 */
fun looksLikeWholeSentence(s: String): Boolean {
    val t = s.trim()
    return t.length >= 10 || t.any { it in SENTENCE_MARKS }
}

private const val SENTENCE_MARKS = "…‥。、！？!?♡♥★☆「」『』"

/**
 * AD-11 — thu ma `ScreenSource` phai tam an truoc khi chup.
 *
 * Chu ky co y bat buoc dung khoi lenh chu khong phai cap `hide()` / `show()`:
 * quen goi `show()` lam nguoi dung mat sach giao dien ma khong hieu vi sao.
 * Voi khoi lenh thi `finally` lo viec hien lai, ke ca khi chup nem loi.
 */
interface OverlayGate {
    suspend fun <T> hiddenForCapture(block: suspend () -> T): T
}

/** Khong co lop phu nao de an — dung cho man hinh chon file cua Epic 2 va cho test. */
object NoOverlays : OverlayGate {
    override suspend fun <T> hiddenForCapture(block: suspend () -> T): T = block()
}

/**
 * Vi sao mot lan chup that bai — de `ui` noi bang tieng nguoi (FR-013/014, Story 3.8).
 *
 * La `sealed` chu khong phai chuoi loi: moi nhanh doi mot cach xu ly KHAC NHAU,
 * va trinh bien dich se bao neu quen nhanh nao.
 */
sealed interface CaptureFailure {
    /** App dang doc dat `FLAG_SECURE`. Gioi han nen tang, KHONG co cach vong. */
    data object ScreenProtected : CaptureFailure
    /** He dieu hanh thu hoi phien, hoac nguoi dung khoa man hinh (AD-21). Phai xin lai. */
    data object SessionRevoked : CaptureFailure
    /** Chua tung xin quyen. */
    data object NoPermission : CaptureFailure
    /** Het gio cho frame. */
    data object Timeout : CaptureFailure
}

class CaptureException(val failure: CaptureFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)

/** Nguon anh. Buoc 0. */
interface ScreenSource {
    /**
     * AD-11: TU an icon noi va moi lop phu, cho mot frame, chup, roi hien lai.
     * Khong giao trach nhiem nay cho nguoi goi — nguoi goi se quen.
     * Cung TU cat bo vung status bar (chip "dang chia se man hinh" cua
     * Android 15 QPR1+ khong an duoc).
     */
    suspend fun capture(): PageImage
}

/**
 * Anh mot trang. Che giau kieu anh cu the cua nen tang.
 * `handle` la `Any` de `domain`/`ports` khong phai import `android.graphics.Bitmap`.
 */
class PageImage(
    val width: Int,
    val height: Int,
    val handle: Any,
)

/** FR-060..062 — cache. AD-24: tra o day TRUOC khi can nhac nap engine. */
interface PageCache {
    suspend fun get(contentKey: String, boxes: List<Box>): List<Bubble>?
    suspend fun put(contentKey: String, boxes: List<Box>, bubbles: List<Bubble>)
    suspend fun sizeBytes(): Long
    suspend fun clear()
}
