package app.mangatrans.pipeline

import app.mangatrans.adapters.onnx.readingOrder
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.BubbleState
import app.mangatrans.domain.PageJob
import app.mangatrans.domain.RegionKind
import app.mangatrans.ports.Detection
import app.mangatrans.ports.PageImage
import app.mangatrans.ports.TextDetector

/**
 * Buoc 1 — Story 2.2.
 *
 * Gan id va THU TU DOC MOT LAN DUY NHAT. Tang sau khong duoc sap lai.
 */
class DetectFilter(
    private val detector: TextDetector,
    private val imageOf: (PageJob) -> PageImage,
    private val cfg: PipelineConfig = PipelineConfig(),
) : Filter {

    override val name = "detect"

    override suspend fun apply(job: PageJob): PageJob {
        val image = imageOf(job)
        val raw = detector.detect(image, cfg.detectMinScore)
        val ordered = readingOrder(mergeOverlappingText(raw), job.pageWidth)

        val bubbles = ordered.mapIndexed { i, d ->
            Bubble(id = i, box = d.box, kind = d.kind, detectScore = d.score)
        }
        return job.copy(
            bubbles = bubbles,
            readingOrder = bubbles.map { it.id },
        )
    }
}

/**
 * Buoc 2 — Story 2.3. Cong loc vung khong co thoai. AD-5.
 *
 * Vi sao CAN cong nay: manga-ocr la mo hinh image->text, KHONG co dau ra
 * "cho nay khong co chu" va khong tra diem tin cay. Dua cho no mot manh tranh,
 * no VAN bia ra cau tieng Nhat trong hop ly (FINDINGS F2).
 *
 * Thoai bia se duoc dich troi chay roi ve de vao bubble, va nguoi doc KHONG CO
 * CACH NAO phat hien. Lỗi nay te hon dich sai.
 *
 * Co che loc: `text_bubble` phai nam >= 0.9 dien tich trong mot `bubble`.
 * Do duoc: 53/54 box thoa tren 6 trang test (F4).
 */
/**
 * Chong nhau bao nhieu thi coi hai hop chu la MOT.
 *
 * Tinh theo dien tich giao chia cho hop NHO HON, khong phai IoU: hai manh cua
 * cung mot bong thoai thuong rat lech nhau ve kich thuoc, nen IoU se ra so be
 * va bo sot.
 *
 * **Do that tren hai trang (F78):**
 * ```
 *   #24 x #26  1,1%   hai bong rieng, chi cham via     -> khong gop
 *   #13 x #17  8,5%   hai bong rieng, chi cham via     -> khong gop
 *   ---------------------------------------- nguong 20%
 *   #20 x #24 32,4%   MOT bong bi cat lam doi          -> phai gop
 *   trang 70          khong cap nao chong nhau
 * ```
 * 20% cach cai gia te nhat 2,35 lan va cach ca that 2,4 lan.
 */
private const val MERGE_MIN_RATIO = 0.20

/**
 * Gop nhung hop chu CHONG LEN NHAU thanh mot.
 *
 * Vi sao can, do tren may that: mot bong thoai co chu doc dai bi detector cat
 * thanh HAI hop chong nhau 30x262 px. Hau qua day chuyen, ba loi mot luc:
 *
 *   1. **OCR doc trung** — cot 「俺だけなのか!?」 nam trong ca hai hop nen bi doc
 *      hai lan;
 *   2. **Mo hinh nhan manh cau cut** — hop thu hai bat dau bang 「だけなのか!?」
 *      khong dau khong duoi, nen dich ra cau vo nghia;
 *   3. **Hai ban dich ve de len nhau** tren man hinh — dung cho nguoi dung
 *      keu "chu de len nhau".
 *
 * Gop lai thi ca ba deu het: OCR doc mot lan, mo hinh nhan tron cau theo dung
 * thu tu doc, va chi con mot cho de ve.
 *
 * ⚠️ CHI gop `TextBubble`. Vo bong (`Bubble`) long nhau la chuyen binh thuong
 * va `GateFilter` da co luat rieng; gop chung lai se pha luat do.
 */
internal fun mergeOverlappingText(dets: List<Detection>): List<Detection> {
    val text = dets.filter { it.kind == RegionKind.TextBubble }.toMutableList()
    if (text.size < 2) return dets
    val others = dets.filter { it.kind != RegionKind.TextBubble }

    var merged = true
    while (merged) {
        merged = false
        outer@ for (i in text.indices) {
            for (j in i + 1 until text.size) {
                if (overlapRatio(text[i].box, text[j].box) < MERGE_MIN_RATIO) continue
                val a = text[i]
                val b = text[j]
                text[i] = Detection(
                    box = union(a.box, b.box),
                    kind = RegionKind.TextBubble,
                    // Giu diem CAO hon: hop gop chac chan la chu, khong the kem
                    // tin hon manh tin nhat cua no.
                    score = maxOf(a.score, b.score),
                )
                text.removeAt(j)
                merged = true
                break@outer
            }
        }
    }
    return others + text
}

/** Dien tich giao chia cho hop NHO HON. 0 khi khong cham nhau. */
private fun overlapRatio(a: app.mangatrans.domain.Box, b: app.mangatrans.domain.Box): Double {
    val ix1 = maxOf(a.x1, b.x1)
    val iy1 = maxOf(a.y1, b.y1)
    val ix2 = minOf(a.x2, b.x2)
    val iy2 = minOf(a.y2, b.y2)
    if (ix2 <= ix1 || iy2 <= iy1) return 0.0
    val inter = (ix2 - ix1).toLong() * (iy2 - iy1).toLong()
    val small = minOf(a.area, b.area)
    return if (small <= 0L) 0.0 else inter.toDouble() / small.toDouble()
}

private fun union(a: app.mangatrans.domain.Box, b: app.mangatrans.domain.Box) =
    app.mangatrans.domain.Box(
        minOf(a.x1, b.x1), minOf(a.y1, b.y1), maxOf(a.x2, b.x2), maxOf(a.y2, b.y2),
    )

class GateFilter(
    private val cfg: PipelineConfig = PipelineConfig(),
) : Filter {

    override val name = "gate"

    override suspend fun apply(job: PageJob): PageJob {
        val shells = job.bubbles.filter { it.kind == RegionKind.Bubble }.map { it.box }
        val texts = job.bubbles.filter { it.kind == RegionKind.TextBubble }

        val gated = job.bubbles.map { b ->
            when (b.kind) {
                // Vo bong CO vung chu ben trong thi khong phai vung chu — bo.
                // Vo bong KHONG co vung chu nao ben trong la chuyen khac han:
                // xem `rescueOrphanShell`.
                RegionKind.Bubble -> {
                    // ⚠️ HAI phep thu, khong phai mot.
                    //
                    // Phep cu chi hoi "co hop chu nao nam GON trong vo nay
                    // khong" (>= 0.9). No bo sot dung mot truong hop, va truong
                    // hop do sinh ra ngay sau khi co buoc gop (F78): hop chu da
                    // gop **to hon vo bong**, nen no phu 81% dien tich vo ma
                    // chi co 40% cua no nam trong vo. Phep cu tra ve "vo nay
                    // khong co chu" -> cuu no thanh bong rong -> **OCR va dich
                    // lai dung doan vua dich**, roi ve de len chinh no.
                    //
                    // Do duoc tren may: sau khi gop, man hinh co hai ban dich
                    // cua cung mot cau nam chong len nhau.
                    //
                    // Nen hoi them chieu nguoc lai: vo nay co bi mot hop chu
                    // phu phan lon khong. Bi phu thi chu cua no DA duoc xu ly
                    // roi, khong con la bong rong nua.
                    val hasText = texts.any {
                        it.box.containedIn(b.box) >= cfg.containedInBubbleMin ||
                            coveredBy(b.box, it.box) >= ORPHAN_COVERED_MAX
                    }
                    if (!hasText && b.detectScore >= ORPHAN_MIN_SCORE) {
                        b.copy(
                            // OCR doc phan TRONG RUOT, tranh vien bong.
                            box = b.box.inset(ORPHAN_INSET),
                            shell = b.box,
                            state = BubbleState.Accepted,
                        )
                    } else {
                        b.copy(state = BubbleState.Suspect)
                    }
                }

                RegionKind.TextBubble -> {
                    val fitting = shells.filter { b.box.containedIn(it) >= cfg.containedInBubbleMin }
                    if (fitting.isEmpty()) {
                        b.copy(state = BubbleState.Suspect)
                    } else {
                        // Vo dung de TO NEN phai thoa hai dieu kien, neu khong se
                        // to de len bong ben canh (da thay that o anh render):
                        //   1. chi chua DUNG MOT vung chu — detector tra ve cac vo
                        //      long nhau, co vo bao trum nhieu bong
                        //   2. khong qua lon so voi hop chu
                        val shell = fitting
                            .filter { s ->
                                texts.count { it.box.containedIn(s) >= cfg.containedInBubbleMin } == 1
                            }
                            .filter { it.area <= b.box.area * MAX_SHELL_RATIO }
                            .minByOrNull { it.area }
                        b.copy(shell = shell)
                    }
                }

                // Chu ngoai bong (SFX, loi dan). Detector gan nhu khong bat duoc
                // loai nay (F4: chi 1 box tren 6 trang) => ngoai pham vi MVP.
                RegionKind.TextFree -> b.copy(state = BubbleState.Suspect)
            }
        }
        return job.withBubbles(gated)
    }

    private companion object {
        /**
         * Vo lon hon hop chu bao nhieu lan thi coi la khong dang tin.
         * Bong thoai thuc te rong hon vung chu khoang 1.3-2 lan.
         */
        const val MAX_SHELL_RATIO = 2.5

        /**
         * **Cuu vo bong mo coi.**
         *
         * Detector tra ve hai loai vung: `bubble` (vo) va `text_bubble` (chu ben
         * trong). Binh thuong chung di theo cap. Nhung co luc no thay vo ma
         * KHONG thay chu — va khi do ca bong thoai bi bo qua, khong ai doc.
         *
         * Nguoi dung chi thang vao mot bong nhu vay: "bong thoai nay nay, co
         * dich dau". Do lai dung trang do: 17 vung, 9 vo, 8 chu — tat ca deu di
         * theo cap TRU vo `[12]` (495,1055 224x660, diem 0.70), dung la bong
         * ho khoanh (F49).
         *
         * ⚠️ AD-5 sinh ra de chan dung viec nay: dua cho manga-ocr mot manh
         * TRANH thi no van bia ra cau tieng Nhat troi chay (F2). Nen phai hoi:
         * co cach nao biet trong vo co chu that khong?
         *
         * Da thu do do phang cua nen va ty le muc, **khong dung duoc**:
         * ```
         *   vo bong co chu : nen phang 64-88%   muc  9-23%
         *   vung tranh     : nen phang 34-65%   muc  8-30%
         * ```
         * Hai khoang chong nhau, khong co nguong nao tach duoc.
         *
         * Nen dung chinh phan doan cua detector: no da gan nhan **`bubble`**,
         * tuc chinh no noi "day la bong thoai". Bong thoai gan nhu luon co chu.
         * Con lai chi chan bang diem tin cay.
         *
         * Va can nhac hai phia: bo sot ca mot bong thoai la **im lang mat han
         * mot cau**, nguoi dung khong co cach nao biet. Con neu cuu nham mot vo
         * rong thi duoc mot bong dich vo nghia — thay ngay, va cham giu la hien
         * lai nguyen ban.
         *
         * ⚠️ Nguong duoi day dat tu **MOT trang**. Can do them nhieu trang truoc
         * khi tin no.
         */
        const val ORPHAN_MIN_SCORE = 0.5f

        /** Thu vao de OCR khong doc trung vien bong. */
        const val ORPHAN_INSET = 0.06

        /**
         * Vo bong bi mot hop chu phu tu chung nay tro len thi **khong phai bong
         * rong** — chu cua no da co cho xu ly roi.
         *
         * Do that (F78): truong hop can bat phu **81%** dien tich vo, trong khi
         * chi 40% hop chu nam trong vo nen phep `containedIn >= 0.9` khong thay
         * gi. Lay 0,5 de con bien rong, van xa muc "cham via" cua hai bong canh
         * nhau.
         */
        const val ORPHAN_COVERED_MAX = 0.5
    }
}

/** Ty le dien tich cua `shell` bi `text` phu len. Khac `containedIn` o CHIEU. */
private fun coveredBy(shell: app.mangatrans.domain.Box, text: app.mangatrans.domain.Box): Double {
    val ix1 = maxOf(shell.x1, text.x1)
    val iy1 = maxOf(shell.y1, text.y1)
    val ix2 = minOf(shell.x2, text.x2)
    val iy2 = minOf(shell.y2, text.y2)
    if (ix2 <= ix1 || iy2 <= iy1) return 0.0
    val inter = (ix2 - ix1).toLong() * (iy2 - iy1).toLong()
    return if (shell.area <= 0L) 0.0 else inter.toDouble() / shell.area.toDouble()
}

/** So lieu de ghi log va doi chieu voi ty le 53/54 do duoc o Phase 0. */
data class GateStats(val kept: Int, val dropped: Int) {
    val total get() = kept + dropped
}

fun gateStats(job: PageJob): GateStats {
    val text = job.bubbles.count { it.kind == RegionKind.TextBubble }
    val kept = job.bubbles.count { it.kind == RegionKind.TextBubble && it.state == BubbleState.Accepted }
    return GateStats(kept = kept, dropped = text - kept)
}
