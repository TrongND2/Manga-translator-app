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
        val ordered = readingOrder(raw, job.pageWidth)

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
                    val hasText = texts.any {
                        it.box.containedIn(b.box) >= cfg.containedInBubbleMin
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
    }
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
