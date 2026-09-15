package app.mangatrans.domain

/**
 * AD-1 — kieu du lieu DUY NHAT di qua pipeline.
 *
 * Bat bien: toan `val`. Filter khong duoc sua doi tuong nhan vao, chi `copy()`.
 * Nho vay moi buoc deu tai hien duoc khi go loi, va khong filter nao lam hong
 * du lieu cua filter truoc.
 *
 * AD-18 — mang HAI hash, hai vai, KHONG duoc dung lan:
 *   frameHash  : nhay   -> phat hien noi dung ben duoi da doi (AD-12)
 *   contentKey : ben    -> khoa cache (FR-060)
 */
data class PageJob(
    val jobId: String,
    /** Nhay. Tinh tren khung DA CAT status bar. Chi de phat hien doi noi dung. */
    val frameHash: String,
    /** Ben. Tinh tren anh ha mau, chi tren vung bubble. Chi de lam khoa cache. */
    val contentKey: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val bubbles: List<Bubble> = emptyList(),
    /** Thu tu doc manga (phai->trai, tren->duoi). Gan MOT LAN o DetectFilter. */
    val readingOrder: List<Int> = emptyList(),
) {
    /** Bubble da qua cong AD-5 va co chu — tap duy nhat duoc dua sang buoc dich. */
    val translatable: List<Bubble>
        get() = bubbles.filter { it.state == BubbleState.Accepted && it.ja.isNotBlank() }

    fun withBubbles(next: List<Bubble>): PageJob = copy(bubbles = next)
}

/**
 * Mot vung chu tren trang.
 *
 * `id` do DetectFilter cap, on dinh suot vong doi PageJob. Sau khi loc o cong
 * AD-5, tap id CO THE khong lien tuc (vi du 0,1,3,6) — day la co y, khong phai loi.
 * Khong tang nao duoc danh lai so.
 */
data class Bubble(
    val id: Int,
    val box: Box,
    val kind: RegionKind,
    val detectScore: Float,
    val state: BubbleState = BubbleState.Accepted,
    /** Nguyen ban tieng Nhat. Rong cho toi khi OcrFilter chay. */
    val ja: String = "",
    /** Ban dich tieng Viet. Null cho toi khi TranslateFilter chap nhan no. */
    val vi: String? = null,
    /** Nguoi noi do LLM suy ra — nguon de xuat glossary (AD-8). */
    val speaker: String? = null,
    /**
     * Box cua VO bong thoai chua vung chu nay (lop `bubble`), do GateFilter gan.
     *
     * Dung de to nen: to theo hop chu thi ra o CHU NHAT de len tranh, vi bong
     * thoai hinh tron. To mot hinh ELLIP noi tiep vo bong moi che dung cho.
     * Null khi khong tim duoc vo (vung `text_free`, hoac bi danh Suspect).
     */
    val shell: Box? = null,
)

/**
 * Toa do PIXEL cua anh chup GOC, goc o gioc tren-trai.
 * Chuyen sang toa do man hinh CHI xay ra trong adapters.overlay.
 */
data class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
    val width get() = x2 - x1
    val height get() = y2 - y1
    val area get() = width.toLong() * height.toLong()

    /** Ty le dien tich cua `this` nam trong `other`. Dung cho cong AD-5. */
    fun containedIn(other: Box): Double {
        val ix1 = maxOf(x1, other.x1); val iy1 = maxOf(y1, other.y1)
        val ix2 = minOf(x2, other.x2); val iy2 = minOf(y2, other.y2)
        if (ix2 <= ix1 || iy2 <= iy1) return 0.0
        val inter = (ix2 - ix1).toLong() * (iy2 - iy1).toLong()
        return if (area == 0L) 0.0 else inter.toDouble() / area.toDouble()
    }

    /** Thu vao moi phia theo ty le canh. Khong bao gio thu qua thanh hop rong. */
    fun inset(ratio: Double): Box {
        val dx = (width * ratio).toInt().coerceAtMost((width - 2) / 2).coerceAtLeast(0)
        val dy = (height * ratio).toInt().coerceAtMost((height - 2) / 2).coerceAtLeast(0)
        return Box(x1 + dx, y1 + dy, x2 - dx, y2 - dy)
    }
}

/** Ba lop cua detector `ogkalu/comic-text-and-bubble-detector`. */
enum class RegionKind { Bubble, TextBubble, TextFree }

/**
 * Ket qua tung bubble, song BEN TRONG PageJob.
 *
 * Ba nhanh, khong phai hai — day la ly do KHONG boc PageJob trong `Result`:
 * `Result` chi co Ok/Err, khong bieu dien duoc `Suspect`.
 */
enum class BubbleState {
    /** Qua cong, duoc dich va duoc ve de. */
    Accepted,
    /** Khong qua cong AD-5 (khong nam trong bubble nao). KHONG ve de — giu chu goc. */
    Suspect,
    /** Da thu dich nhung that bai. Giu chu goc (AD-9, FR-046). */
    Rejected,
}
