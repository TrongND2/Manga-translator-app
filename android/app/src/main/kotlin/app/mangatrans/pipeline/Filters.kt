package app.mangatrans.pipeline

import app.mangatrans.domain.PageJob

/**
 * AD-1 — chu ky DUY NHAT cua moi filter.
 *
 * Filter KHONG duoc sua doi tuong nhan vao. Chi `copy()`.
 * Ket qua tung bubble song BEN TRONG PageJob duoi dang BubbleState ba nhanh,
 * KHONG boc PageJob trong Result (Result hai nhanh khong bieu dien duoc ba).
 */
interface Filter {
    val name: String
    suspend fun apply(job: PageJob): PageJob
}

/**
 * Nguong va tham so — MOT cho duy nhat, khong rai hang so.
 *
 * Moi gia tri o day deu co nguon goc tu mot phep do that, ghi trong spike/FINDINGS.md.
 */
data class PipelineConfig(
    /**
     * AD-5 — ty le dien tich toi thieu cua text_bubble nam trong mot bubble.
     * Do duoc: 53/54 box thoa nguong 0.9 tren 6 trang (F4).
     */
    val containedInBubbleMin: Double = 0.9,

    /**
     * Diem tin cay toi thieu cua detector.
     *
     * 0.5 -> **0.3** (F87). Nguoi dung bao mot khoi tuong thuat tren trang
     * khong duoc dich. Ho so chan doan cho thay khong phai loi dich va cung
     * khong phai loi gate — trang do `vung=24 dua sang dich=12`, **ca 12 o chu
     * tim duoc deu da dich xong**. Khoi kia chua bao gio vao duoc day chuyen.
     *
     * Chay lai dung mo hinh do tren dung tam anh, ha nguong xuong 0.01: no CO
     * thay, chi la duoi nguong. Nam vung trong khoang 0.30..0.50, va **ca nam
     * deu do dung vao mot cho duy nhat bi bo sot**, khong roi lung tung:
     *
     *   0.462 Bubble      704, 771  318x553   (trum ca khoi)
     *   0.445 TextBubble  874, 869  119x425   cot 「ぼろくて狭い部屋に…」
     *   0.439 Bubble      704, 766  181x431
     *   0.346 Bubble      858, 811  159x520
     *   0.343 TextBubble  720, 820  158x348   cot 「窓から入るネオンの光が…」
     *
     * Vi sao 0.3 chu khong phai 0.4: hai cot cua cung mot khoi cham 0.445 va
     * 0.343. Dung 0.4 thi chi vot duoc cot dau ⇒ khoi do **dich nua voi**,
     * te hon la khong dich.
     *
     * Gia phai tra, do tren 30 trang that (chi dem kind=TextBubble):
     *
     *   0.50 -> 192 vung  ·  0.40 -> 195  ·  0.35 -> 197  ·  0.30 -> 201
     *
     * Tuc **+9 vung tren 30 trang (+4,7%)**.
     */
    val detectMinScore: Float = 0.3f,

    /**
     * AD-6 — so ky tu dau nguyen ban dung lam ma doi chieu.
     * Do duoc: 2 ky tu da du — 0 bao dong gia, bat 11/11 o lech (F14).
     * Dai hon khong tang do chinh xac ma ton them token.
     */
    val jaEchoChars: Int = 2,

    /** AD-6 — khoang cach sua toi da coi la khop, sau khi chuan hoa. */
    val jaEchoMaxEdits: Int = 1,

    /**
     * Thu lai DUNG MOT LAN, DUNG MOT CHO (chi TranslateFilter).
     * Nhieu tang cung retry se nhan so lan goi LLM len.
     */
    val translateRetries: Int = 1,

    /**
     * AD-25 — so luong CPU. Do duoc tren M52: 2 luong cho 54 s/trang va 58-62C,
     * mac dinh (~4) cho 40 s va 74-78C. Doi 35% toc do lay 16 do (F23).
     */
    val cpuThreads: Int = 2,

    /** AD-24 — qua bao lau khong cham thi nha engine (3200 MB -> 84 MB). */
    val engineIdleTimeoutMs: Long = 3 * 60 * 1000L,
)
