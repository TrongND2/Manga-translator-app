package app.mangatrans.pipeline

import android.util.Log
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
    /**
     * Trang nhieu bong thi CHIA THANH TUNG DOT, khong dua het mot lan.
     *
     * AD-3 chot dua ca trang trong MOT lan goi, va ly do do van dung — nhung no
     * gia dinh mo hinh tra duoc het. Do tren may thi khong:
     *
     * ```
     *   trang 18 bong, luot 1 -> dung o 10/18
     *   trang 18 bong, luot 2 -> dung o 10/18     (dau ra tat dinh, khong phai rui)
     * ```
     *
     * Da thu nghi ngo tran do dai bai lam: dat `maxOutputToken = 2048` — **van
     * dung o 10**. Khong phai bi cat, ma la mo hinh 2 ti tham so mat mach sau
     * chung ay muc.
     *
     * Nen chia thanh dot <= `MAX_PER_CALL` bong, theo DUNG THU TU DOC nen bong
     * lien nhau van nam cung dot va mach hoi thoai phan lon duoc giu. Mat mat
     * that: hai nhan vat noi chuyen vat qua ranh gioi dot thi xung ho co the
     * lech. Doi lai la **dich duoc het trang** thay vi mat mot nua (F59).
     */
    private companion object {
        const val TAG = "MangaTrans"

        /**
         * Bao nhieu bong moi lan goi LLM. Do tren M52: trang 18 bong thi mo
         * hinh dung o dung 10, hai luot lien tiep deu vay. Trang 12 bong thi
         * tra du. Lay 10 cho co bien.
         */
        const val MAX_PER_CALL = 10
    }

    fun stream(job: PageJob): Flow<PageEvent> = flow {
        val all = job.translatable
        if (all.isEmpty()) {
            emit(PageEvent.Done(job, 0)); return@flow
        }
        if (all.size > MAX_PER_CALL) {
            val merged = LinkedHashMap<Int, Bubble>()
            // ⚠️ KHONG dung `chunked(MAX_PER_CALL)` thang: no de lai mot dot le
            // o cuoi. Trang 11 bong ra [10, 1], va **dot mot bong luon hong**
            // — do tren may, hai lan thu lien tiep deu nhan 0 bong, roi bong
            // do bi bo lai nguyen tieng Nhat khong mot loi bao:
            //
            //   Translating 10/10 · Translating 0/1 · Translating 0/1 · xong
            //
            // Bong le lai con la bong **mat het ngu canh**: no vua bi tach
            // khoi dung cau dung truoc no trong mach thoai.
            //
            // Chia deu thi khong bao gio de ra dot le: 11 -> [6, 5], 18 ->
            // [9, 9], 21 -> [7, 7, 7]. Khong dot nao vuot `MAX_PER_CALL`.
            val parts = (all.size + MAX_PER_CALL - 1) / MAX_PER_CALL
            val per = (all.size + parts - 1) / parts
            all.chunked(per).forEach { chunk ->
                val ids = chunk.map { it.id }.toSet()
                val sub = job.withBubbles(
                    job.bubbles.map {
                        if (it.id in ids || it.vi != null) it
                        else it.copy(state = BubbleState.Suspect)
                    }
                )
                translateOnce(sub, merged)
            }
            val out = job.bubbles.map { merged[it.id] ?: it }
            emit(PageEvent.Done(job.withBubbles(out), 0))
            return@flow
        }
        translateOnce(job, null)
    }

    /**
     * @param sink neu khac null thi ghi ket qua vao day va KHONG phat `Done`
     *   (dang chia dot — nguoi goi phat `Done` mot lan o cuoi).
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<PageEvent>.translateOnce(
        job: PageJob,
        sink: MutableMap<Int, Bubble>?,
    ) {
        val truth = job.translatable.associate { it.id to it.ja }
        if (truth.isEmpty()) {
            if (sink == null) emit(PageEvent.Done(job, 0))
            return
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
                if (sink != null) { sink.putAll(accepted); return }
                emit(PageEvent.Done(job.withBubbles(job.bubbles.map { accepted[it.id] ?: it }), 0))
                return
            }

            // ⚠️ THIEU bubble KHAC HAN voi SAI bubble.
            //
            // `mismatch` nghia la mo hinh gan ban dich vao nham bong — loi toan
            // ven, phai vut het (AD-6). Con `accepted.size < truth.size` ma
            // khong mismatch chi nghia la mo hinh **dung som**: nhung bong da
            // ve deu da qua cong jaEcho, tung cai mot deu dung.
            //
            // Truoc day hai truong hop nay bi xu ly nhu nhau: vut sach. Do tren
            // may, mot trang 11 bong duoc dich dung 6 bong, hai lan lien, roi
            // **ca 6 bi vut va nguoi dung nhan mot cau bao loi**. Gio: het luot
            // thu ma chi thieu, thi giu lai phan da xac thuc, phan con lai de
            // nguyen tieng Nhat. Dich mot nua van hon khong dich gi (F45).
            // Het luot thu ma van khong du: GIU nhung bong da qua cong jaEcho.
            //
            // Truoc day cho nay doi `reason == null`, tuc chi giu khi mo hinh
            // **dung som**; con khi no sinh ra mot muc HONG thi vut sach. Do
            // tren may, mot trang 18 bong: mo hinh dich dung 10 bong roi sinh
            // muc thu 11 lech jaEcho — va ca 10 bong dung bi vut, nguoi dung
            // nhan mot trang trang tron.
            //
            // Ly do vut sach ngay tu dau la so mo hinh gan ban dich lech mot
            // nac cho CA trang (F19). Nhung cong jaEcho kiem TUNG BONG MOT:
            // moi bong da nhan deu da doi chieu chu Nhat cua chinh id do. Mot
            // muc hong o cuoi khong lam nhung muc da kiem tro nen dang ngo.
            //
            // Vut 10 ban dich dung vi mot muc hong la danh doi sai phia (F57).
            // Mot dot nhan thieu bong la chuyen tung bi NUOT HOAN TOAN: khi
            // dang chia dot (`sink != null`) thi khong co `PageRejected` nao
            // duoc phat, nen bong bi bo lai nguyen tieng Nhat ma khong ai bao
            // gi. Ghi ra de lan sau con lan duoc.
            //
            // CHI ghi so dem va ma ly do — `mismatch` chua id chu khong chua
            // chu da OCR, nen khong lo noi dung man hinh nguoi dung ra log.
            if (accepted.size < truth.size) Log.i(
                TAG,
                "dot nhan ${accepted.size}/${truth.size} bong" +
                    " (lan ${attempt + 1}/${cfg.translateRetries + 1})" +
                    if (reason != null) " — $reason" else " — mo hinh dung som",
            )

            val lastTry = attempt == cfg.translateRetries
            if (lastTry && accepted.isNotEmpty()) {
                if (sink != null) { sink.putAll(accepted); return }
                emit(PageEvent.Done(job.withBubbles(job.bubbles.map { accepted[it.id] ?: it }), 0))
                return
            }

            // AD-17 — go nhung bubble DA VE. Bat buoc, khong phai truong hop ngoai le.
            if (drawn.isNotEmpty()) {
                emit(PageEvent.Retracted(drawn, reason ?: "thieu bubble"))
            }
            attempt++
        }

        // Thu lai het luot ma van lech -> tro ve nguyen ban (AD-9).
        if (sink == null) {
            emit(PageEvent.PageRejected("lech anh xa id sau ${cfg.translateRetries + 1} lan thu"))
        }
    }
}
