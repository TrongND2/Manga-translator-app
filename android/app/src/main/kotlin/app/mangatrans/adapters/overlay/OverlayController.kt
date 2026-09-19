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
    /** Nguoi dung cham hai cai vao mot bong thoai de sua ban dich cua no. */
    private val onEditBubble: (Int) -> Unit = {},
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
    /**
     * ⚠️ DEM CHONG chu khong phai mot co bat/tat.
     *
     * Ban truoc la `AtomicBoolean`, va no hong ngay khi hai cho cung giu: vi du
     * panel "chu vua lay" dang mo (giu) thi nguoi dung bam dich mot cum, buoc
     * do lai chup man hinh (giu roi NHA) — cu nha cua cai trong lam mat luon
     * cai giu cua cai ngoai, va bo canh trang chay tiep giua luc panel con che
     * kin man hinh. Dem chong thi chi khi nguoi cuoi cung nha, co moi ha.
     */
    private val selfDepth = java.util.concurrent.atomic.AtomicInteger(0)

    /** App co dang tu lam man hinh doi khong. */
    val isSelfChanging: Boolean get() = selfDepth.get() > 0

    /** Giu: bao "tu day den luc nha, man hinh doi la do CHINH TA". */
    fun holdSelfChange() { selfDepth.incrementAndGet() }

    /** Nha. Khong bao gio tut xuong duoi 0 — nha thua thi bo qua. */
    fun releaseSelfChange() { selfDepth.updateAndGet { if (it > 0) it - 1 else 0 } }

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
    val translation = TranslationOverlay(
        ctx, wm,
        onPeek = { peeking -> if (peeking) holdSelfChange() else releaseSelfChange() },
        onEditBubble = { id -> onEditBubble(id) },
        iconBoxOnScreen = { icon.boxOnScreen() },
        raiseIcon = { icon.raise() },
    )

    fun show() = icon.show()

    /** Khung icon tren man hinh — bo canh trang bo qua vung nay. */
    fun iconBox() = icon.boxOnScreen()

    /**
     * MOI cho tren man hinh do chinh app ve len: icon me, ba icon con, va tung
     * cua so ban dich (ke ca lop de thu cong).
     *
     * Day la nguon su that DUY NHAT cho bo canh trang. Truoc day noi goi tu
     * ghep danh sach nay, va no thieu dung nhung thu hay doi nhat — nen mo mot
     * cai menu cua chinh app cung du lam app tuong nguoi dung sang trang.
     *
     * Toa do MAN HINH; nguoi goi tu tru status bar de ve toa do anh chup.
     */
    fun appOwnedBoxesOnScreen(): List<app.mangatrans.domain.Box> =
        icon.boxesOnScreen() + translation.paneBoxesOnScreen()

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
        holdSelfChange()
        withContext(Dispatchers.Main) {
            icon.setVisibleForCapture(false)
            translation.setVisibleForCapture(false)
        }
        try {
            return block()
        } finally {
            // ⚠️ `NonCancellable`: khi nguoi dung cham de DUNG giua luc dang
            // chup, coroutine bi huy va `withContext` thuong se **khong chay
            // than ham** — lop phu nam nguyen o INVISIBLE va icon bien mat khoi
            // man hinh cho toi lan chup sau. Da thay that: icon khong con, cham
            // vao cho cu khong co gi xay ra.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                withContext(Dispatchers.Main) {
                    icon.setVisibleForCapture(true)
                    translation.setVisibleForCapture(true)
                }
            }
            releaseSelfChange()
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
        holdSelfChange()
        translation.add(b)
        scope.launch {
            delay(SELF_SETTLE_MS)
            releaseSelfChange()
        }
        Unit
    }

    /**
     * Lop de THU CONG (`⌖`). Khac `addBubble` o cho no khong den tu day chuyen,
     * nhung ve mat hien thi va canh trang thi y het — nen di chung mot duong.
     */
    suspend fun addManual(b: Bubble) = addBubble(b)

    suspend fun retract(ids: Collection<Int>) =
        withContext(Dispatchers.Main) { translation.retract(ids) }

    suspend fun clearPage() = withContext(Dispatchers.Main) { translation.clear() }

}
