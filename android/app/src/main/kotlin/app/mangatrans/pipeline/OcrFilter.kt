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
            else b.copy(ja = text)
        }
        return job.withBubbles(next)
    }
}
