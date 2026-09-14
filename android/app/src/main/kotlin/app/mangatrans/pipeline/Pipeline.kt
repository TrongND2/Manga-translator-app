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
) {

    companion object {
        /** AD-19 — MVP dung MOT glossary toan cuc. */
        const val SERIES = "default"
    }

    fun run(bitmap: Bitmap): Flow<PageEvent> = flow {
        val t0 = System.currentTimeMillis()
        val image = PageImage(bitmap.width, bitmap.height, bitmap)

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
        val boxes = job.bubbles.map { it.box }
        job = job.copy(contentKey = PageHash.contentKey(bitmap, boxes))

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
