package app.mangatrans.adapters.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.mangatrans.domain.Box

/**
 * Story 3.6 — cham giu vao vung ban dich de liec nguyen ban tieng Nhat.
 *
 * **Vi sao phai la nhieu cua so nho thay vi mot cua so to:**
 * Story 3.7 doi lop phu KHONG duoc chan thao tac cua app ben duoi — vuot, cuon,
 * cham deu phai binh thuong. Cach dung de vua ve vua nhan cham trong vung bubble
 * la `TOUCHABLE_INSETS_REGION`, nhung `ViewTreeObserver.OnComputeInternalInsetsListener`
 * va `InternalInsetsInfo` la API `@hide`, khong co trong SDK cong khai (F29).
 *
 * Nen: lop VE la mot cua so `FLAG_NOT_TOUCHABLE` phu toan man (khong bao gio
 * chan gi), con NHAN CHAM la cac cua so nho dat dung tren tung bubble. Ngoai
 * vung bubble khong co cua so nao => khong chan gi.
 *
 * Danh doi da biet va chap nhan: cham vao BEN TRONG bong thoai thi app ben duoi
 * khong nhan duoc cham do. Chap nhan duoc — dung cho do dang bi ban dich che,
 * nguoi dung cham vao la de xem chu goc chu khong phai de bam gi cua app kia.
 */
class PeekTargets(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onPeek: (Boolean) -> Unit,
) {

    private companion object {
        /** Giu bao lau thi coi la "liec", duoi nguong nay coi nhu cham nham. */
        const val HOLD_MS = 250L
    }

    private val views = mutableListOf<View>()
    private var peeking = false

    /**
     * @param boxes hop cac bubble DA VE, toa do man hinh (da bu offset).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setTargets(boxes: List<Box>) {
        clear()
        boxes.forEach { b ->
            if (b.width < 8 || b.height < 8) return@forEach
            val v = View(ctx)
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.postDelayed(::startPeek, HOLD_MS)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(::startPeek)
                        stopPeek()
                        true
                    }
                    else -> false
                }
            }
            runCatching { wm.addView(v, params(b)) }.onSuccess { views += v }
        }
    }

    private fun startPeek() {
        if (peeking) return
        peeking = true
        onPeek(true)
    }

    private fun stopPeek() {
        if (!peeking) return
        peeking = false
        onPeek(false)
    }

    fun clear() {
        stopPeek()
        views.forEach { v ->
            v.removeCallbacks(::startPeek)
            runCatching { wm.removeView(v) }
        }
        views.clear()
    }

    /** AD-11 — an tam de chup. */
    fun setVisibleForCapture(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        views.forEach { it.visibility = vis }
    }

    private fun params(b: Box) = WindowManager.LayoutParams(
        b.width, b.height, overlayType(),
        // KHONG dat NOT_TOUCHABLE: day chinh la cho DUY NHAT duoc nhan cham.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = b.x1
        y = b.y1
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
