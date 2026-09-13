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

    /** Diem tin cay toi thieu cua detector. */
    val detectMinScore: Float = 0.5f,

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
