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

        /** Ngan hon chung nay lan nguyen ban thi coi la ban dich bi cut. */
        const val MIN_LEN_RATIO = 0.5

        /**
         * Nguyen ban ngan hon chung nay ky tu thi KHONG xet do dai.
         *
         * Cam than va tieng dong (「え!?」「ぅぁあ」) von dich ra ngan bang nguyen
         * ban — do la dung, khong phai cut.
         */
        const val MIN_JA_LEN = 8
    }

    /**
     * Chi giu nhung muc tu dien co chuoi chu Nhat **that su xuat hien** trong
     * trang nay.
     *
     * Hai cai loi, va cai thu hai moi la cai quan trong:
     *
     * 1. **Ngan prompt.** Do tren may: prompt 3019 ky tu cho 10 bong, trong do
     *    phan co dinh (SYSTEM + khung + ca glossary) chiem 72%. Glossary gui
     *    het 15 muc moi lan du trang chi dinh toi vai muc.
     *
     * 2. **Bot bia.** F65 do duoc: mo hinh sinh ra ten "Rurimaru" o mot bong
     *    KHONG HE co ten do, chi vi 「りゅ」 nghe gan giong va cai ten do dang
     *    nam san trong prompt. Muc nao khong co mat tren trang thi chi la moi
     *    nhu de mo hinh nham — bo di la bot dung mot nguon sai.
     *
     * Khop chuoi con, dung y nhu luat da viet trong prompt: *"Chi thay mot muc
     * glossary khi bubble chua DUNG chuoi chu Nhat cua muc do"*. Loc o day va
     * luat trong prompt la mot, khong the lech nhau.
     *
     * ⚠️ Danh doi da biet: OCR doc sai mot net thi muc do bi loai oan. Nhung
     * khi do mo hinh cung se khong khop duoc chuoi ay — nen giu lai cung khong
     * cuu duoc gi, chi ton cho.
     */
    /**
     * Don not chu Nhat con sot lai trong ban dich.
     *
     * Prompt da dan "Không để sót chữ Nhật nào" nhung mo hinh van sot — do tren
     * ba trang that (F81):
     * ```
     *   おぁぁッ                  ->  "ÁÁッ"
     *   あぁあ♡きたぁッ♡         ->  "Đến rồiッ♡"
     *   ナマの粘膜擦れあってるッッ ->  "Niêm mạc ... cọ xát nhauッッ"
     * ```
     * 3 tren 7 bong cua mot trang. Ky tu hay sot nhat la 「ッ」 — trong manga no
     * chi la dau ngat hoi cuoi cau, khong mang nghia, nen bo di la dung.
     *
     * ⚠️ CHI bo nhung doan chu Nhat **dai toi da 2 ky tu**.
     *
     * Day la cho de lam hong nhat, nen phai dat cho ro: mot doan dai HON hai ky
     * tu thi rat co the la **mot cai ten hay mot cum mo hinh khong dich noi** —
     * cat no di la xoa mat thong tin va giau luon cai loi. Do that o `#29`:
     * mo hinh tung de nguyen ten `アズ`; neu ta cat bua thi cau thanh "cái mông
     * của" cut ngu, con te hon la de nguyen.
     *
     * Con doan mot–hai ky tu dinh vao duoi mot tu tieng Viet thi gan nhu chac
     * chan la rac: 「ッ」 la dau ngat hoi, 「ー」 la dau keo dai — khong mang nghia.
     *
     * Giu nguyen ♡ ♪ ★ (prompt yeu cau giu), giu chu Viet, chu so, dau cau.
     * Bo xong ma khong con chu nao thi tra lai nguyen van — dung tu tay tao ra
     * mot o trong (AD-9).
     */
    private fun stripJapanese(vi: String): String {
        if (vi.none { it.isJapaneseScript() }) return vi
        val cleaned = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\uFF66-\\uFF9F]{1,2}")
            .replace(vi) { m ->
                // Doan dai hon 2 ky tu da bi regex tach thanh nhieu manh 2 ky
                // tu; kiem lai bang cach nhin hai ben — cham chu Nhat thi giu.
                val i = m.range.first
                val j = m.range.last
                val leftJa = i > 0 && vi[i - 1].isJapaneseScript()
                val rightJa = j + 1 < vi.length && vi[j + 1].isJapaneseScript()
                if (leftJa || rightJa) m.value else ""
            }
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
        return if (cleaned.any { it.isLetterOrDigit() }) cleaned else vi
    }

    /** Hiragana, katakana (ca ban nho) va kanji. */
    private fun Char.isJapaneseScript(): Boolean =
        this in '぀'..'ゟ' ||   // hiragana
            this in '゠'..'ヿ' ||   // katakana
            this in '一'..'鿿' ||   // kanji
            this in 'ｦ'..'ﾟ'      // katakana nua rong

    /**
     * Ban dich co bi cut khong.
     *
     * Hai dieu kien, va dieu kien thu hai moi la cai giu cho phep thu nay khong
     * bat oan:
     *
     *   1. ngan hon `MIN_LEN_RATIO` lan nguyen ban;
     *   2. **nguyen ban phai du dai** (`MIN_JA_LEN`). Cam than 「え!?」 dich
     *      thanh "Ể!?" co ty le dung 1,00 — hoan toan binh thuong. Bo dieu kien
     *      nay thi moi cau cam than deu bi bo oan.
     *
     * Do tren mot trang 15 bong (F79): bong bi cut nam o **0,24**, 14 bong con
     * lai deu **>= 1,00**. Lay 0,5 thi cach cai cut hai lan va cach cai binh
     * thuong thap nhat hai lan.
     */
    private fun looksTruncated(ja: String, vi: String): Boolean {
        val j = ja.trim()
        val v = vi.trim()
        if (j.length < MIN_JA_LEN) return false
        return v.length < j.length * MIN_LEN_RATIO
    }

    private fun relevant(all: List<GlossaryEntry>, ja: Collection<String>): List<GlossaryEntry> {
        if (all.isEmpty()) return all
        // ⚠️ Chuan hoa CA HAI phia truoc khi so.
        //
        // OCR tung tra ve `ザ-メン` (gach noi ASCII) thay vi `ザーメン`, va muc
        // tu dien nguoi dung go tay thi lai dung `ー` that. Hai chuoi trong
        // giong nhau ma khong bao gio khop — muc tu dien nam do vo dung ma
        // khong ai biet (F84). `OcrFilter` da sua phia chu doc ra; sua not phia
        // tu dien de nhung muc go nham dau gach cung van khop.
        val page = fixProlongedMark(ja.joinToString("\n"))
        return all.filter {
            it.surface.isNotBlank() && page.contains(fixProlongedMark(it.surface))
        }
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
            all.chunked(per).forEachIndexed { i, chunk ->
                val ids = chunk.map { it.id }.toSet()
                val sub = job.withBubbles(
                    job.bubbles.map {
                        if (it.id in ids || it.vi != null) it
                        else it.copy(state = BubbleState.Suspect)
                    }
                )
                // Tu dot thu hai: noi tiep phien cua dot truoc thay vi mo phien
                // moi. Do tren may: mo phien moi phai doc lai 2171 ky tu co
                // dinh, mat ~16 giay; noi tiep chi mat ~2 giay.
                translateOnce(sub, merged, continuing = i > 0)
            }
            translator.endPage()
            val out = job.bubbles.map { merged[it.id] ?: it }
            emit(PageEvent.Done(job.withBubbles(out), 0))
            return@flow
        }
        translateOnce(job, null, continuing = false)
        translator.endPage()
    }

    /**
     * @param sink neu khac null thi ghi ket qua vao day va KHONG phat `Done`
     *   (dang chia dot — nguoi goi phat `Done` mot lan o cuoi).
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<PageEvent>.translateOnce(
        job: PageJob,
        sink: MutableMap<Int, Bubble>?,
        continuing: Boolean,
    ) {
        val truth = job.translatable.associate { it.id to it.ja }
        if (truth.isEmpty()) {
            if (sink == null) emit(PageEvent.Done(job, 0))
            return
        }

        val glossary = relevant(glossaryOf(), truth.values)
        var attempt = 0
        Log.i(TAG, "glossary: ${glossary.size} muc lien quan toi trang nay")

        while (attempt <= cfg.translateRetries) {
            val drawn = mutableListOf<Int>()
            val accepted = mutableMapOf<Int, Bubble>()
            var mismatch: String? = null

            emit(PageEvent.Progress(app.mangatrans.domain.Stage.Translating, 0, truth.size))

            // Thu lai thi LUON mo phien moi: phien cu dang chua mot bai lam
            // hong, de lai chi lam mo hinh bam theo cai sai do.
            translator.translate(job, glossary, continuing && attempt == 0).collect { t ->
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

                // ⚠️ Cong jaEcho KHONG bat duoc ban dich bi cut.
                //
                // No chi doi mo hinh cheo lai hai ky tu dau cua nguyen ban —
                // cheo dung thi coi nhu "gan dung bong". Mo hinh van co the
                // cheo dung roi **bo di gan het cau**.
                //
                // Do that tren mot trang 15 bong (F79):
                // ```
                //   ty le do dai VI/JA
                //   0.24  うおお出る!ホントにいいんだな!?  ->  "Ước!"   <- cut
                //   1.00  え!?                             ->  "Ể!?"
                //   ...
                //   3.20  パパだけ♡                        ->  "Chỉ là bố thôi ♡"
                // ```
                // Ca 14 bong con lai deu >= 1,00; chi mot bong cut nam o 0,24.
                //
                // Khong nhan thi bong do roi vao duong "mo hinh dung som" da co
                // san: het luot thu ma van thieu thi giu nguyen chu Nhat (AD-9).
                // Doc mot bong tieng Nhat van hon doc mot cau tieng Viet sai ma
                // khong co dau hieu gi bao la sai.
                if (looksTruncated(truth.getValue(t.id), t.vi)) {
                    Log.i(TAG, "bo bubble ${t.id}: ban dich cut bat thuong")
                    return@collect
                }

                // Don not chu Nhat con sot — xem `stripJapanese`. Lam SAU cong
                // do dai: cat bo ky tu roi moi do thi mot ban dich vua du dai
                // co the tut xuong duoi nguong va bi bo oan.
                val vi = stripJapanese(t.vi)
                if (vi != t.vi) Log.i(TAG, "bubble ${t.id}: da don chu Nhat sot lai")

                val src = job.bubbles.first { it.id == t.id }
                val done = src.copy(vi = vi, speaker = t.speaker, state = BubbleState.Accepted)
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

            // ⚠️ Luot thu lai chi dang chay khi PROMPT SE KHAC.
            //
            // Do duoc hom nay: mo hinh **tat dinh** — chay hai lan cung mot
            // prompt cho **15/15 bong giong het tung chu** (F80). Ma luot thu
            // lai luon dung `continuing = false`: mo phien moi, gui lai dung
            // prompt day du. Nen neu luot vua roi CUNG da la `continuing =
            // false` (dot dau cua trang), thi prompt sap gui y het prompt vua
            // gui — va ket qua se y het.
            //
            // Tuc la luot thu lai do **chac chan khong cuu duoc gi**, chi ton
            // them mot luot sinh chu day du (~40-60 giay tren M52). Truoc day
            // no van chay, va nguoi dung ngoi cho gap doi thoi gian de nhan lai
            // dung cai ket qua cu.
            //
            // Dot thu hai tro di thi khac: luot dau dung lai phien cu
            // (`buildFollowUp`, prompt ngan), luot thu lai mo phien moi voi
            // prompt day du — hai prompt khac nhau that, nen van dang thu.
            val justUsedFreshSession = !(continuing && attempt == 0)
            val retryWouldRepeat = justUsedFreshSession
            if (retryWouldRepeat && attempt < cfg.translateRetries) {
                Log.i(TAG, "bo qua luot thu lai: prompt se y het luot vua roi")
                if (accepted.isNotEmpty()) {
                    if (sink != null) { sink.putAll(accepted); return }
                    emit(PageEvent.Done(
                        job.withBubbles(job.bubbles.map { accepted[it.id] ?: it }), 0))
                    return
                }
                // Khong nhan duoc bong nao: van phai bao hong theo duong cu.
                val why = reason ?: "mo hinh dung som"
                if (drawn.isNotEmpty()) emit(PageEvent.Retracted(drawn.toList(), why))
                if (sink == null) emit(PageEvent.PageRejected(why))
                return
            }

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
