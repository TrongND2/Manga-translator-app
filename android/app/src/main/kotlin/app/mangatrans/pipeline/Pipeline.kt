package app.mangatrans.pipeline

import android.graphics.Bitmap
import app.mangatrans.adapters.storage.PageHash
import app.mangatrans.domain.BubbleState
import app.mangatrans.domain.PageEvent
import app.mangatrans.domain.PageJob
import app.mangatrans.domain.Stage
import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.GlossaryKind
import app.mangatrans.ports.GlossaryStatus
import app.mangatrans.ports.GlossaryStore
import app.mangatrans.ports.isUsableSurface
import app.mangatrans.ports.OcrEngine
import app.mangatrans.ports.PageCache
import app.mangatrans.ports.PageImage
import app.mangatrans.ports.TextDetector
import app.mangatrans.ports.Translator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Noi 5 filter thanh chuoi, phat `Flow<PageEvent>` (AD-13).
 *
 * Thu tu co y nghia:
 *   detect -> gate -> [CACHE] -> ocr -> translate
 *
 * AD-24: cache tra o day TRUOC khi can nhac nap engine. Trang da dich phai tra
 * ra ngay o trang thai Cold — do la ly do cache giam duoc ca thoi gian, RAM
 * va nhiet cung luc.
 */
class Pipeline(
    private val detector: TextDetector,
    private val ocr: OcrEngine,
    private val translator: Translator,
    private val glossary: GlossaryStore,
    private val cache: PageCache,
    private val cfg: PipelineConfig = PipelineConfig(),
    /** Vung status bar can cat truoc khi tinh frameHash (AD-11, AD-18). */
    private val statusBarPx: Int = 0,
    /**
     * Goi SAU khi OCR xong, TRUOC khi LLM chay.
     *
     * Detector va OCR da lam xong viec o thoi diem nay, nhung van giu bo nho.
     * Ma dung luc LLM chay moi la luc cang nhat: da do duoc PSS 3 570 MB trong
     * khi may chi con 3 633 MB kha dung, va Android DA giet app that giua mot
     * luot dich (F37).
     *
     * `pipeline` khong biet ai dang nghe — no chi bao "xong phan nhin roi".
     * Goc lap rap quyet dinh lam gi voi tin do.
     */
    private val onVisionDone: suspend () -> Unit = {},
    /**
     * Goi TRUOC khi detect, tuc truoc khi bat ky mo hinh thi giac nao chay.
     *
     * Doi xung voi `onVisionDone`, va ly do ton tai cung nam o mot phep do:
     * LLM ham nong xong chiem ~2 GB RSS va **nam nguyen do trong suot buoc doc
     * chu**. Cong them phan thi giac la vuot nguong, va Android giet app ngay
     * giua buoc doc chu — hai lan lien, cung mot cho:
     *
     * ```
     * lmkd: Reclaim 'app.mangatrans' ... to free 2912480kB rss;
     *       min2x watermark is breached even after kill
     * ActivityManager: Process app.mangatrans (pid 8040) has died: prcp FGS
     * ```
     *
     * Nen luat o day la: **khong bao gio giu ca hai cung luc**. Nhin thi nha
     * dich, dich thi nha nhin. Gia phai tra la 14,6 giay nap lai LLM moi trang
     * (do duoc tren M52) — dat, nhung re hon la bi giet.
     */
    private val onVisionStart: suspend () -> Unit = {},
) {

    companion object {
        /** AD-19 — MVP dung MOT glossary toan cuc. */
        const val SERIES = "default"
    }

    fun run(bitmap: Bitmap): Flow<PageEvent> = flow {
        val t0 = System.currentTimeMillis()
        val image = PageImage(bitmap.width, bitmap.height, bitmap)

        // Nha LLM truoc khi phan nhin bat dau — xem `onVisionStart`.
        runCatching { onVisionStart() }

        // --- buoc 1: detect ---
        emit(PageEvent.Progress(Stage.Detecting))
        val detectFilter = DetectFilter(detector, { image }, cfg)
        var job = detectFilter.apply(
            PageJob(
                jobId = UUID.randomUUID().toString(),
                frameHash = PageHash.frameHash(bitmap, statusBarPx),
                contentKey = "",          // chua tinh duoc, can box truoc
                pageWidth = bitmap.width,
                pageHeight = bitmap.height,
            )
        )

        // --- buoc 2: cong loc (AD-5) ---
        job = GateFilter(cfg).apply(job)
        // CHI so do hinh hoc — khong bao gio ghi noi dung anh hay chu da OCR.
        // `shell` quyet dinh o nen phu toi dau: khong co vo bong thi chi to
        // duoc hop chu, ma hop chu om sat chu nen chu goc de lo ra.
        android.util.Log.i(
            "Pipeline",
            "gate: ${job.bubbles.size} vung, ${job.bubbles.count { it.shell != null }} co vo bong",
        )
        // Hinh hoc tung vung, de doi chieu voi anh. Toa do va kich thuoc KHONG
        // phai noi dung man hinh — ghi duoc. Chu da OCR thi khong.
        job.bubbles.filter { it.kind == app.mangatrans.domain.RegionKind.TextBubble }
            .forEach { b ->
                val s = b.shell
                android.util.Log.i(
                    "Geom",
                    "#${b.id} ${b.state} box=${b.box.x1},${b.box.y1} ${b.box.width}x${b.box.height}" +
                        if (s == null) " shell=none"
                        else " shell=${s.x1},${s.y1} ${s.width}x${s.height}",
                )
            }
        val boxes = job.bubbles.map { it.box }
        job = job.copy(contentKey = PageHash.contentKey(bitmap, boxes))
        // Story 3.3 — bang chung cho "anh chup sach": dich cung mot trang hai lan
        // lien tiep phai cho contentKey GIONG HET. Neu lan hai chup trung ban
        // dich vua ve thi vung bubble da khac, va hash se khac ngay.
        // Hash chu khong phai noi dung — ghi duoc.
        android.util.Log.i("Pipeline", "contentKey=${job.contentKey.take(16)}")

        // --- cache: TRUOC khi nap engine (AD-24) ---
        cache.get(job.contentKey, boxes)?.let { cached ->
            val hit = job.withBubbles(cached)
            hit.bubbles.filter { it.vi != null }.forEach { emit(PageEvent.BubbleReady(it)) }
            emit(PageEvent.Done(hit, System.currentTimeMillis() - t0))
            return@flow
        }

        // --- buoc 3: OCR ---
        emit(PageEvent.Progress(Stage.Reading, 0, job.translatable.size))
        job = OcrFilter(ocr, { image }).apply(job)

        if (job.translatable.isEmpty()) {
            emit(PageEvent.Done(job, System.currentTimeMillis() - t0))
            return@flow
        }

        // Nha detector + OCR truoc khi buoc nang nhat bat dau.
        runCatching { onVisionDone() }

        // --- buoc 4: dich (AD-3, AD-6, AD-17) ---
        val translateFilter = TranslateFilter(
            translator,
            { glossary.confirmed(SERIES) },   // AD-7: chi muc DA XAC NHAN vao prompt
            cfg,
        )
        var finished: PageJob? = null
        translateFilter.stream(job).collect { ev ->
            if (ev is PageEvent.Done) {
                // Chan Done cua TranslateFilter: no chi biet buoc dich, khong biet
                // tong thoi gian ca pipeline. Done cuoi cung phat o duoi.
                finished = ev.job
            } else {
                emit(ev)
            }
        }

        val done = finished
        if (done != null) {
            cache.put(done.contentKey, boxes, done.bubbles)
            // AD-8: de xuat glossary tu `speaker` LLM von da tra ve — chi phi bang 0.
            // `surface` phai la chu xuat hien trong nguyen ban tieng Nhat, khong
            // thi khong bao gio khop. Da thay that: LLM tra `speaker` = "Người
            // nói 1" / "Rurimaru" — rac thuan tuy (ports.isUsableSurface).
            done.bubbles.mapNotNull { it.speaker }
                .filter { isUsableSurface(it) }
                .toSet()
                .forEach { name ->
                    glossary.propose(GlossaryEntry(
                        seriesKey = SERIES, surface = name, meaning = name,
                        kind = GlossaryKind.ProperNoun, status = GlossaryStatus.Proposed,
                    ))
                }
        }
        emit(PageEvent.Done(done ?: job, System.currentTimeMillis() - t0))
    }
}

/** FR-046 / AD-9 — bubble nao khong dich duoc thi giu nguyen chu goc. */
fun PageJob.drawable() = bubbles.filter {
    it.state == BubbleState.Accepted && !it.vi.isNullOrBlank()
}
