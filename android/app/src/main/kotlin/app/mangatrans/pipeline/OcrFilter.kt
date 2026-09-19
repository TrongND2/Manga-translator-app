package app.mangatrans.pipeline

import app.mangatrans.domain.BubbleState
import app.mangatrans.domain.PageJob
import app.mangatrans.ports.OcrEngine
import app.mangatrans.ports.PageImage

/**
 * Buoc 3 — Story 2.4. FR-022, FR-023.
 *
 * CHI doc vung da qua cong AD-5. Khong quet ca trang.
 * Vung tra ve chuoi rong bi ha xuong Suspect — khong day rac sang buoc dich.
 */
class OcrFilter(
    private val ocr: OcrEngine,
    private val imageOf: (PageJob) -> PageImage,
) : Filter {

    override val name = "ocr"

    override suspend fun apply(job: PageJob): PageJob {
        val image = imageOf(job)
        val next = job.bubbles.map { b ->
            if (b.state != BubbleState.Accepted) return@map b
            val text = ocr.read(image, b.box)
            // FR-023: rong = khong co chu doc duoc. Ha xuong Suspect thay vi
            // de chuoi rong di tiep, vi buoc dich khong nen nhan bubble rong.
            if (text.isBlank()) b.copy(state = BubbleState.Suspect)
            else b.copy(ja = fixProlongedMark(text))
        }
        return job.withBubbles(next)
    }
}

/** U+30FC — dau keo dai am cua kana. */
private const val PROLONG = 'ー'

/** Nhung ky tu gach ma OCR hay tra ve THAY CHO `ー`. */
private val DASHES = charArrayOf(
    '-',   // '-'  gach noi ASCII  <- cai thuc su gap tren may
    '－',   // '－'  gach noi toan rong
    'ｰ',   // 'ｰ'  dau keo dai nua rong
    '‐', '‑', '‒', '–', '—', '−',
)

private fun Char.isKana() = this in '぀'..'ゟ' || this in '゠'..'ヿ'

/**
 * Tra dau keo dai `ー` ve dung ky tu cua no.
 *
 * ⚠️ Day la mot loi **im lang** cua tang OCR, va no dat hon ve ngoai rat nhieu.
 *
 * Do tren 153 cau tieng Nhat khac nhau lay tu may that (F84):
 * ```
 *   dau 'ー' dung chuan (U+30FC) :  0 cau
 *   dau '-' ASCII      (U+002D) :  8 cau
 * ```
 * Tuc la **chua bao gio doc dung**. Hau qua khong chi la mot ky tu xau:
 *
 *   1. **Muc tu dien chua `ー` khong bao gio khop.** `ザーメン` nam san trong tu
 *      dien ma bong 「パパのザ-メンアズの…」 van dich sai thanh "cái mông của
 *      Azu" — vi chuoi OCR doc ra la `ザ-メン`, khong khop.
 *   2. Mo hinh nhan mot chuoi **khong phai tieng Nhat** nen no doan mo.
 *
 * CHI doi khi ky tu ngay TRUOC la kana. `2-3` hay `A-B` phai giu nguyen.
 */
internal fun fixProlongedMark(s: String): String {
    if (s.none { it in DASHES }) return s
    val out = StringBuilder(s.length)
    for ((i, c) in s.withIndex()) {
        val prev = if (i > 0) out[out.length - 1] else null
        out.append(if (c in DASHES && prev != null && prev.isKana()) PROLONG else c)
    }
    return out.toString()
}
