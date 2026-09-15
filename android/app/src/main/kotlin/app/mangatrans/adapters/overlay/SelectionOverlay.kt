package app.mangatrans.adapters.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.mangatrans.domain.Box

/**
 * Keo mot khung tren man hinh de lay chu trong do.
 *
 * Vi sao co man nay: nguoi dung khong go duoc chu Nhat. Ho nhin thay mot tu
 * trong truyen, muon tra nghia va nhet vao tu dien rieng, nhung khong co cach
 * nao **nhap** no vao — chep tay tung net kanji la khong tuong.
 *
 * ⚠️ Cua so nay NUOT MOI CU CHAM (khong dat `FLAG_NOT_TOUCHABLE`). Do la co y:
 * dang o che do chon vung thi cham vao app ben duoi se lat trang, va anh chup
 * sau do se khong con la trang nguoi dung dinh chon. Che do nay phai co dau va
 * cuoi ro rang, khong duoc nhap nhang voi thao tac doc truyen.
 */
class SelectionOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onPick: (Box) -> Unit,
    private val onCancel: () -> Unit,
) {

    private companion object {
        /** Keo ngan hon chung nay thi coi la cham nham, khong phai chon vung. */
        const val MIN_SIDE_PX = 24
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f
    private var dragging = false

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val clear = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        color = 0xFF00E5B0.toInt()
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(15).toFloat()
        isFakeBoldText = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private val view = object : View(ctx) {
        init {
            // Lop toi + o khoet can ve tren layer rieng, neu khong `CLEAR`
            // se khoet thung ca cua so xuong app ben duoi.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x1 = e.rawX; y1 = e.rawY; x2 = e.rawX; y2 = e.rawY
                        dragging = true; invalidate(); true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        x2 = e.rawX; y2 = e.rawY; invalidate(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        val b = picked()
                        if (b == null) onCancel() else onPick(b)
                        true
                    }
                    else -> false
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val loc = IntArray(2); getLocationOnScreen(loc)
            if (dragging || picked() != null) {
                val r = Rect(
                    minOf(x1, x2).toInt() - loc[0], minOf(y1, y2).toInt() - loc[1],
                    maxOf(x1, x2).toInt() - loc[0], maxOf(y1, y2).toInt() - loc[1],
                )
                canvas.drawRect(r, clear)
                canvas.drawRect(r, frame)
            } else {
                canvas.drawText(
                    "Kéo một khung quanh chữ cần lấy",
                    width / 2f, height * 0.42f, hint,
                )
                canvas.drawText(
                    "Chạm một cái để thoát",
                    width / 2f, height * 0.42f + dp(26), hint,
                )
            }
        }
    }

    /** Khung da chon, theo TOA DO MAN HINH. `null` khi keo qua ngan. */
    private fun picked(): Box? {
        val b = Box(
            minOf(x1, x2).toInt(), minOf(y1, y2).toInt(),
            maxOf(x1, x2).toInt(), maxOf(y1, y2).toInt(),
        )
        return if (b.width >= MIN_SIDE_PX && b.height >= MIN_SIDE_PX) b else null
    }

    private var attached = false

    fun show() {
        if (attached) return
        wm.addView(view, params())
        attached = true
    }

    fun hide() {
        if (!attached) return
        runCatching { wm.removeView(view) }
        attached = false
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        // KHONG dat NOT_TOUCHABLE: cua so nay song de nhan cu keo.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
