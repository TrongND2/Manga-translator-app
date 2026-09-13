package app.mangatrans.pipeline

import app.mangatrans.domain.Bubble
import app.mangatrans.domain.BubbleState
import app.mangatrans.domain.PageEvent
import app.mangatrans.domain.PageJob
import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.Translator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Buoc 4 — Story 2.5 + 2.6 + 2.7. Cho ba AD gap nhau:
 *
 *   AD-3  : CA TRANG trong MOT lan goi. Khong chia nho.
 *   AD-6  : xac thuc `jaEcho` — JSON hop le KHONG tinh la da kiem tra.
 *   AD-17 : tra Flow, chap nhan tung bubble, gap lech thi huy va phat Retracted.
 *
 * Khac voi cac filter kia: buoc nay khong dung chu ky `apply(job): PageJob` vi
 * no phai phat su kien dan ra ngoai (AD-13). No la mot `Filter` ve mat khai niem
 * nhung expose them `stream()`.
 */
class TranslateFilter(
    private val translator: Translator,
    private val glossaryOf: suspend () -> List<GlossaryEntry>,
    private val cfg: PipelineConfig = PipelineConfig(),
) {
    val name = "translate"

    /**
     * Phat su kien theo AD-13/AD-17.
     *
     * Vong doi mot luot:
     *   1. moi BubbleTranslation ve -> xac thuc jaEcho ngay (AD-6)
     *   2. khop  -> phat BubbleReady de ve
     *   3. lech  -> HUY ngay, phat Retracted(nhung id da ve), thu lai ca trang
     *   4. van lech -> PageRejected, toan bo tro ve nguyen ban (AD-9)
     */
    fun stream(job: PageJob): Flow<PageEvent> = flow {
        val truth = job.translatable.associate { it.id to it.ja }
        if (truth.isEmpty()) {
            emit(PageEvent.Done(job, 0)); return@flow
        }

        val glossary = glossaryOf()
        var attempt = 0

        while (attempt <= cfg.translateRetries) {
            val drawn = mutableListOf<Int>()
            val accepted = mutableMapOf<Int, Bubble>()
            var mismatch: String? = null

            emit(PageEvent.Progress(app.mangatrans.domain.Stage.Translating, 0, truth.size))

            translator.translate(job, glossary).collect { t ->
                if (mismatch != null) return@collect   // da hong, bo qua phan con lai

                // Id khong co trong dau vao — da gap that o tubaki_025 (F19).
                if (t.id !in truth) {
                    mismatch = "id ${t.id} khong co trong dau vao"
                    return@collect
                }
                // AD-6 — cong toan ven, xac thuc NGAY khi bubble ve.
                val bad = EchoGate.mismatches(
                    mapOf(t.id to truth.getValue(t.id)),
                    mapOf(t.id to t.jaEcho),
                    cfg,
                )
                if (bad.isNotEmpty()) {
                    mismatch = "jaEcho lech o bubble ${t.id}"
                    return@collect
                }

                val src = job.bubbles.first { it.id == t.id }
                val done = src.copy(vi = t.vi, speaker = t.speaker, state = BubbleState.Accepted)
                accepted[t.id] = done
                drawn.add(t.id)
                emit(PageEvent.BubbleReady(done))
                emit(PageEvent.Progress(
                    app.mangatrans.domain.Stage.Translating, accepted.size, truth.size))
            }

            val reason = mismatch
            if (reason == null && accepted.size == truth.size) {
                val merged = job.bubbles.map { accepted[it.id] ?: it }
                emit(PageEvent.Done(job.withBubbles(merged), 0))
                return@flow
            }

            // AD-17 — go nhung bubble DA VE. Bat buoc, khong phai truong hop ngoai le.
            if (drawn.isNotEmpty()) {
                emit(PageEvent.Retracted(drawn, reason ?: "thieu bubble"))
            }
            attempt++
        }

        // Thu lai het luot ma van lech -> ca trang tro ve nguyen ban (AD-9).
        emit(PageEvent.PageRejected("lech anh xa id sau ${cfg.translateRetries + 1} lan thu"))
    }
}
