package app.mangatrans.domain

/**
 * AD-13 — pipeline tra `Flow<PageEvent>`, phat tung bubble ngay khi duoc chap nhan.
 * AD-17 — `Retracted` la hanh vi BAT BUOC ho tro, khong phai truong hop ngoai le.
 */
sealed interface PageEvent {

    /** Doi trang thai de icon hien cho nguoi dung biet dang lam gi (FR-004). */
    data class Progress(val stage: Stage, val done: Int = 0, val total: Int = 0) : PageEvent

    /** Mot bubble da qua moi cong va san sang ve de. */
    data class BubbleReady(val bubble: Bubble) : PageEvent

    /**
     * AD-17 — phat hien lech id GIUA CHUNG stream.
     *
     * Cac bubble da ve phai duoc GO va khoi phuc chu Nhat goc. `adapters.overlay`
     * BAT BUOC xu ly su kien nay; bo qua no la vi pham AD-17.
     */
    data class Retracted(val bubbleIds: List<Int>, val reason: String) : PageEvent

    /** Ca trang bi tu choi. Toan bo tro ve nguyen ban (AD-9). */
    data class PageRejected(val reason: String) : PageEvent

    data class Done(val job: PageJob, val elapsedMs: Long) : PageEvent

    /** Hong ca luot: mat quyen chup, khong nap duoc model... */
    data class Failed(val error: PipelineError) : PageEvent
}

enum class Stage { Capturing, Detecting, Reading, Translating, Drawing }

/**
 * Hong CA LUOT — khac han voi ket qua tung bubble (song trong `BubbleState`).
 * Khong dung exception cho luong nghiep vu.
 */
data class PipelineError(
    val jobId: String,
    val filter: String,
    val kind: ErrorKind,
    val message: String,
    val cause: Throwable? = null,
)

enum class ErrorKind {
    /** App dich dat FLAG_SECURE — gioi han nen tang, khong co cach vong (FR-013). */
    ScreenCaptureBlocked,
    /** He dieu hanh thu hoi quyen chup (Android 15+ moi phien, hoac khoa man hinh) (FR-014, AD-21). */
    ScreenCaptureRevoked,
    ModelNotReady,
    ModelLoadFailed,
    OcrFailed,
    TranslateFailed,
    Unknown,
}

/**
 * AD-4 — ket qua cua MOT cong kiem tra.
 *
 * Ba nhanh vi `Result` hai nhanh khong du: can phan biet "thu lai" voi "bo han".
 */
sealed interface Verdict {
    data object Accept : Verdict
    data class Retry(val reason: String) : Verdict
    data class Reject(val reason: String) : Verdict
}
