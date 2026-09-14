package app.mangatrans.adapters.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Icon cua app: mot **bóng thoại** co duoi, ben trong la chu.
 *
 * Vi sao khong dung chu Han `訳` nhu truoc: nguoi dung Viet doc khong ra, va no
 * khong goi len dieu gi ve manga ca. Bong thoai thi ai nhin cung biet la thoai
 * truyen tranh — dung ngon ngu cua chinh thu app dang lam viec tren do.
 *
 * Ve bang `Path` thay vi nhung file anh: khong phai lam nam kich thuoc mdpi ->
 * xxxhdpi, va doi mau theo trang thai chi la mot dong.
 */
class BubbleIconDrawable(
    private var bg: Int,
    private var glyph: String,
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFFFFF
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()

    fun set(bg: Int, glyph: String) {
        this.bg = bg
        this.glyph = glyph
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        fill.color = bg
        ring.strokeWidth = w * 0.045f

        // Than bong: hinh chu nhat bo goc that tron, chua het be ngang, chua
        // ~78% chieu cao — phan con lai danh cho duoi.
        val body = RectF(w * 0.06f, h * 0.10f, w * 0.94f, h * 0.76f)
        val r = body.height() * 0.42f

        path.reset()
        path.addRoundRect(body, r, r, Path.Direction.CW)
        // Duoi bong, chech ve trai duoi — dung huong ma bong thoai manga hay chi.
        path.moveTo(w * 0.30f, h * 0.70f)
        path.lineTo(w * 0.22f, h * 0.95f)
        path.lineTo(w * 0.50f, h * 0.73f)
        path.close()

        canvas.drawPath(path, fill)
        canvas.drawPath(path, ring)

        text.textSize = body.height() * 0.62f
        // Can giua theo chieu doc cua THAN bong, khong phai ca icon — neu khong
        // chu se bi duoi keo lech xuong.
        val fm = text.fontMetrics
        val cy = body.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(glyph, body.centerX(), cy, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
    @Deprecated("Drawable API cu", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
