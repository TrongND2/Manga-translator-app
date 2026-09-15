package app.mangatrans.adapters.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.WindowManager
import app.mangatrans.domain.Bubble
import app.mangatrans.ports.OverlayGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gom icon noi va lop phu ban dich vao MOT chu so huu.
 *
 * Day cung la cho cai dat `OverlayGate` — AD-11 doi `ScreenSource.capture()`
 * tu an MOI lop phu, va "moi" nghia la ca hai thu nay, khong phai chi cai icon.
 * Quen lop phu thi OCR se doc lai chinh chu Viet vua ve roi dich tiep sang
 * tieng Viet.
 */
class OverlayController(
    private val ctx: Context,
    private val onTap: () -> Unit,
    private val onGuide: () -> Unit,
    private val onGrab: () -> Unit,
    private val onClose: () -> Unit,
) : OverlayGate {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val icon = FloatingIcon(
        ctx, wm, onTap = onTap, onGuide = onGuide, onGrab = onGrab, onClose = onClose,
    )

    /**
     * ⚠️ App TU lam man hinh doi trong hai truong hop: dang liec nguyen ban
     * (Story 3.6) va dang an lop phu de chup (AD-11).
     *
     * Bo canh cua Story 3.7 phai bo qua nhung luc nay, neu khong no se tuong
     * nguoi dung sang trang va **xoa mat ban dich**.
     *
     * Da xay ra that: cham giu de liec -> lop phu an di -> bo canh thay man hinh
     * doi -> xoa ca trang. Tha tay ra thi ban dich mat han. Hai story tu danh
     * nhau, va ca hai deu "dung" neu xet rieng.
     */
    val selfChanging = java.util.concurrent.atomic.AtomicBoolean(false)

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main
    )

    private companion object {
        /** Ve xong mot bong thi bao nhieu lau nua coi la man hinh da yen. */
        const val SELF_SETTLE_MS = 450L
    }

    /**
     * Lop phu TU nhan cu chi cham giu (Story 3.6) — cac cua so ve cua no cung
     * chinh la cac cua so nhan cham. Xem ghi chu dau `TranslationOverlay`.
     */
    val translation = TranslationOverlay(ctx, wm) { peeking -> selfChanging.set(peeking) }

    fun show() = icon.show()

    /** Khung icon tren man hinh — bo canh trang bo qua vung nay. */
    fun iconBox() = icon.boxOnScreen()

    /** Xem `FloatingIcon.raise` — goi sau khi ve xong ca trang. */
    suspend fun raiseIcon() = withContext(Dispatchers.Main) { icon.raise() }

    /** Story 3.5 — go sach, khong con dau vet nao (AD-10). */
    fun destroy() {
        translation.clear()
        icon.hide()
    }

    var state: FloatingIcon.State
        get() = icon.state
        set(v) { icon.state = v }

    // ---------- OverlayGate ----------

    /**
     * Khoi lenh chu khong phai cap hide()/show(): `finally` bao dam giao dien
     * hien lai KE CA khi chup nem loi. Neu de nguoi goi tu goi show(), thi mot
     * nhanh loi quen goi la nguoi dung mat sach giao dien ma khong hieu vi sao.
     */
    override suspend fun <T> hiddenForCapture(block: suspend () -> T): T {
        selfChanging.set(true)
        withContext(Dispatchers.Main) {
            icon.setVisibleForCapture(false)
            translation.setVisibleForCapture(false)
        }
        try {
            return block()
        } finally {
            withContext(Dispatchers.Main) {
                icon.setVisibleForCapture(true)
                translation.setVisibleForCapture(true)
            }
            selfChanging.set(false)
        }
    }

    // ---------- chuyen tiep cho pipeline ----------

    suspend fun beginPage(source: Bitmap, frameHash: String, statusBarPx: Int, tf: Typeface) =
        withContext(Dispatchers.Main) {
            translation.begin(source, frameHash, statusBarPx, tf)
        }

    /**
     * Ve them mot bong, va bao cho bo canh trang biet **chinh ta vua lam man
     * hinh doi** — neu khong no se tuong nguoi dung sang trang va xoa lop phu
     * dang ve do.
     *
     * Co ha xuong sau `SELF_SETTLE_MS` de bo canh lay lai moc. Giua hai bong
     * cach nhau vai giay, nen van con du khoang lang de bat cu chuyen app hay
     * lat trang that.
     */
    suspend fun addBubble(b: Bubble) = withContext(Dispatchers.Main) {
        selfChanging.set(true)
        translation.add(b)
        scope.launch {
            delay(SELF_SETTLE_MS)
            selfChanging.set(false)
        }
        Unit
    }

    suspend fun retract(ids: Collection<Int>) =
        withContext(Dispatchers.Main) { translation.retract(ids) }

    suspend fun clearPage() = withContext(Dispatchers.Main) { translation.clear() }

}
