package app.mangatrans.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Nut menu o man hinh chinh: mot o mau tron goc, icon ben trai, chu ben phai,
 * kem mot dong mo ta nho.
 *
 * Vi sao tu ve thay vi dung `Button` mac dinh: nut mac dinh cua Android chi co
 * chu IN HOA tren nen xam, sau cai la mot khoi xam giong het nhau — nguoi dung
 * phai doc het moi biet bam cai nao. Icon va mau lam mat nhan ra ngay.
 *
 * Khong dung file layout XML: ca app khong co file nao, va them mot lop XML
 * chi de ve mot nut thi de lech giua code va XML hon la duoc gi.
 */
object MenuButton {

    fun make(
        ctx: Context,
        icon: String,
        title: String,
        subtitle: String,
        color: Int,
        onClick: () -> Unit,
    ): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val iconView = TextView(ctx).apply {
            text = icon
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(14)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }

        val titleView = TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subView = TextView(ctx).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.65f
        }

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView)
            if (subtitle.isNotEmpty()) addView(subView)
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(10)) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x14000000 or (color and 0x00FFFFFF))
            }
            isClickable = true
            addView(iconView)
            addView(texts)
            setOnClickListener { onClick() }
        }
    }

    /** Doi noi dung mot nut da tao (dung cho nut bat/tat doi trang thai). */
    fun update(row: LinearLayout, icon: String, title: String, subtitle: String, color: Int) {
        val iconView = row.getChildAt(0) as TextView
        iconView.text = icon
        (iconView.background as GradientDrawable).setColor(color)
        val texts = row.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as TextView).text = title
        if (texts.childCount > 1) (texts.getChildAt(1) as TextView).text = subtitle
        (row.background as GradientDrawable).setColor(0x14000000 or (color and 0x00FFFFFF))
    }

    /** Mau dung chung — de moi man hinh khong tu chon mau rieng roi lech nhau. */
    object Colors {
        val on = 0xFF00695C.toInt()        // dang bat
        val off = 0xFF546E7A.toInt()       // dang tat
        val setup = 0xFF00838F.toInt()
        val guide = 0xFF5E35B1.toInt()
        val glossary = 0xFFEF6C00.toInt()
    }
}
