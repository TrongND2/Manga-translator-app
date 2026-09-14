package app.mangatrans.adapters.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.BubbleState
import app.mangatrans.pipeline.BubbleRenderer

/**
 * Story 3.4 / 3.7 — lop ve ban dich de len man hinh.
 *
 * AD-10: chi VE, khong bao gio sua app ben duoi. Go lop nay ra la chu Nhat goc
 * hien lai nguyen ven — do la ly do FR-006 dung mien phi.
 *
 * AD-12: lop phu mang `frameHash` cua anh sinh ra no. Doi noi dung ben duoi la
 * tu xoa ngay, khong cho luot dich moi. Tha mat ban dich con hon hien ban dich
 * sai cho.
 */
class TranslationOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
) {

    /**
     * `frameHash` cua anh da sinh ra lop phu dang hien (AD-12/AD-18).
     * **Khong** phai `contentKey` — hai hash, hai vai.
     */
    var frameHash: String? = null
        private set

    private var typeface: Typeface = Typeface.SANS_SERIF

    /** Anh chup goc — dung de lay mau mau nen bubble. */
    private var source: Bitmap? = null

    /** AD-11 — anh da bi cat status bar, nen toa do phai bu lai khi ve len man hinh. */
    private var offsetY = 0

    private val bubbles = LinkedHashMap<Int, Bubble>()

    private val loc = IntArray(2)

    private val view = object : View(ctx) {
        /**
         * ⚠️ Offset phai TU DO, khong duoc gia dinh.
         *
         * Anh chup da bi cat `statusBarPx` o tren (AD-11), nen toa do bubble
         * phai cong lai chung ay de ve dung cho tren man hinh. NHUNG cua so lop
         * phu co the DA bat dau ngay duoi status bar — luc do cong them lan nua
         * la cong HAI LAN.
         *
         * Da xay ra that. Do duoc tren may: bubble #4 co tam vo bong o y=573
         * (toa do anh chup), nhung chu Viet cua no hien ra o tam y≈726 tren man
         * hinh — lech 153 px, trong khi status bar chi ~76 px. Dung gap doi.
         *
         * Hau qua nhin thay: ca lop phu tut xuong mot nhip, nen **dinh moi bong
         * thoai con chu Nhat lo ra** va o nen tran xuong duoi bong. Trong y het
         * "dich thieu".
         *
         * `getLocationOnScreen` tra ve vi tri THAT cua cua so, nen cong thuc
         * duoi tu dung o ca hai truong hop.
         */
        override fun onDraw(canvas: Canvas) {
            val src = source ?: return
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(0f, (offsetY - loc[1]).toFloat())
            BubbleRenderer.drawPage(canvas, bubbles.values.toList(), typeface) {
                BubbleRenderer.sampleBackground(src, it)
            }
            canvas.restore()
        }
    }

    private var attached = false

    /** Bat dau mot luot moi. Xoa sach lop cu TRUOC khi ve cai gi khac len. */
    fun begin(source: Bitmap, frameHash: String, statusBarPx: Int, typeface: Typeface) {
        this.source = source
        this.frameHash = frameHash
        this.offsetY = statusBarPx
        this.typeface = typeface
        bubbles.clear()
        attach()
        view.invalidate()
    }

    /** FR-044 — hien dan tung bubble ngay khi co, khong cho du ca man. */
    fun add(bubble: Bubble) {
        bubbles[bubble.id] = bubble
        refresh()
    }

    /** AD-17 — go bubble da ve. Ve lai tu dau => chu Nhat goc hien lai. */
    fun retract(ids: Collection<Int>) {
        ids.forEach { bubbles.remove(it) }
        refresh()
    }

    /** AD-12 — noi dung ben duoi doi, hoac ca trang bi tu choi: xoa het. */
    fun clear() {
        bubbles.clear()
        frameHash = null
        source?.let { if (!it.isRecycled) it.recycle() }
        source = null
        detach()
    }

    /**
     * Story 3.6 — an tam trong luc nguoi dung giu de liec nguyen ban.
     * `PeekTargets` goi vao day; lop nay khong biet cu chi duoc nhan o dau.
     */
    fun setPeeking(peeking: Boolean) {
        view.visibility = if (peeking) View.INVISIBLE else View.VISIBLE
    }

    /** AD-11 — an tam de chup. */
    fun setVisibleForCapture(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Story 3.6 — hop cac bubble DA VE, quy ve TOA DO MAN HINH.
     *
     * Dung chinh phep bu offset ma `onDraw` dung, de vung cham trung khop voi
     * vung nhin thay. Tinh rieng hai cho la cach chac chan de chung troi khoi
     * nhau (F31 da day mot lan roi).
     */
    fun drawnBoxesOnScreen(): List<Box> {
        view.getLocationOnScreen(loc)
        val dy = offsetY - loc[1]
        return bubbles.values
            .filter { it.state == BubbleState.Accepted && !it.vi.isNullOrBlank() }
            .map { Box(it.box.x1, it.box.y1 + dy, it.box.x2, it.box.y2 + dy) }
    }

    val hasContent: Boolean get() = bubbles.isNotEmpty()

    private fun refresh() = view.invalidate()

    private fun attach() {
        if (attached) return
        wm.addView(view, params())
        attached = true
    }

    private fun detach() {
        if (!attached) return
        runCatching { wm.removeView(view) }
        attached = false
    }

    /**
     * Story 3.7 — NGOAI vung bubble, lop phu khong duoc chan gi: vuot, cuon,
     * cham cua app ben duoi phai binh thuong.
     *
     * Cach dat duoc dieu do: cua so nay `FLAG_NOT_TOUCHABLE` — no KHONG BAO GIO
     * nhan cham, nen khong the chan gi ca.
     *
     * Ban dau toi dinh dung `TOUCHABLE_INSETS_REGION` de vua ve vua nhan cham
     * trong vung bubble (phuc vu Story 3.6). **Khong dung duoc:**
     * `ViewTreeObserver.OnComputeInternalInsetsListener` va `InternalInsetsInfo`
     * la API `@hide`, khong co trong SDK cong khai.
     *
     * Nen Story 3.6 (cham giu de liec nguyen ban) dung cac cua so nho RIENG dat
     * de len tung bubble — xem `PeekTargets`.
     */
    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
