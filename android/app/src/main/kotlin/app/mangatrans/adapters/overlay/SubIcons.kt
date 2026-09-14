package app.mangatrans.adapters.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Story 3.5 + 3.10 — hai icon con hien khi giu icon me.
 *
 * Ràng buộc quan trọng nhất o day KHONG phai thẩm mỹ ma la **chống bấm nhầm**:
 * cham nham X lam mat phien chup va phai xin lai quyen (FR-009b). Nen:
 *
 *   - 📖 (huong dan) nam GAN icon me  -> la dich de cham nhat
 *   - ✕ (dong)       nam XA hon, kem mot khoang trong to hon nua
 *
 * Nghia la thao tac vo hai chiem cho tot, thao tac ton kem phai voi tay them.
 * Day la lua chon co chu y, dung "sua" thanh xep deu nhau.
 */
class SubIcons(
    private val ctx: Context,
    private val wm: WindowManager,
    anchorX: Int,
    anchorY: Int,
    private val iconPx: Int,
    private val subPx: Int,
    private val screenW: Int,
    private val screenH: Int,
    private val onGuide: () -> Unit,
    private val onClose: () -> Unit,
    private val onOutside: () -> Unit,
) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** Khoang cach toi icon me. */
    private val nearGap = dp(10)

    /** Khoang cach THEM giua 📖 va ✕ — chinh cho nay chong bam nham. */
    private val farGap = dp(28)

    private val book = circle("📖", 0xFF37474F.toInt(), onGuide)
    private val close = circle("✕", 0xFFC62828.toInt(), onClose)

    /**
     * Lop bat cham ngoai vung. Cham ra ngoai thi dong icon con — nguoi dung
     * khong bi ket voi mot cai menu khong biet tat kieu gi.
     */
    @SuppressLint("ClickableViewAccessibility")
    private val catcher = View(ctx).apply {
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onOutside()
            false   // khong nuot: app ben duoi van nhan duoc cham
        }
    }

    // Mo len tren hay xuong duoi tuy cho trong. Tinh mot lan luc tao.
    private val downwards = anchorY + iconPx + nearGap + subPx * 2 + farGap < screenH
    private val xPos = if (anchorX < screenW / 2) anchorX else anchorX + iconPx - subPx

    private val bookY = if (downwards) anchorY + iconPx + nearGap
    else anchorY - nearGap - subPx
    private val closeY = if (downwards) bookY + subPx + farGap
    else bookY - farGap - subPx

    private var attached = false

    fun show() {
        if (attached) return
        // Catcher vao truoc de no nam DUOI hai icon con.
        wm.addView(catcher, fullScreenParams())
        wm.addView(book, params(xPos, bookY))
        wm.addView(close, params(xPos, closeY))
        attached = true
    }

    fun hide() {
        if (!attached) return
        listOf(close, book, catcher).forEach { runCatching { wm.removeView(it) } }
        attached = false
    }

    fun setVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        book.visibility = v
        close.visibility = v
    }

    private fun circle(glyph: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), 0x55FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    private fun params(x: Int, y: Int) = WindowManager.LayoutParams(
        subPx, subPx, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x.coerceIn(0, screenW - subPx)
        this.y = y.coerceIn(0, screenH - subPx)
    }

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    )

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
