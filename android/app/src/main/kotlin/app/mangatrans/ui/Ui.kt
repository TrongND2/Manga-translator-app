package app.mangatrans.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bo phan dung chung cho cac man hinh cai dat.
 *
 * Vi sao co file nay: ba man hinh (`SetupActivity`, `GlossaryActivity`,
 * `GuideActivity`) deu tu dung giao dien bang code, va moi man tu chon co chu,
 * khoang cach, mau nut rieng. Ket qua la ba man **khong giong nhau** va man nao
 * cung la mot buc tuong chu voi mot day nut xam in hoa giong het nhau — nguoi
 * dung phai doc het moi biet bam cai nao, ke ca nut xoa.
 *
 * Quy tac o day chi co ba:
 *
 *  1. **Nut phai noi ra hau qua bang mau.** Viec chinh mot mau dac, viec phu
 *     mot mau nhat, viec xoa mau do. Khong bao gio de ba loai do cung mot mau.
 *  2. **Chu dai phai co cap bac.** Tieu de muc, cau dan, rooi moi den than —
 *     chu khong phai mot khoi `<p>` lien tuc.
 *  3. **Kich co cham duoc.** Moi nut cao it nhat 44 dp; nut xoa khong duoc nam
 *     sat nut dung nhieu nhat.
 *
 * Khong dung file layout XML: ca app khong co file nao, them mot lop XML chi de
 * ve vai cai nut thi de lech giua code va XML hon la duoc gi ([MenuButton] da
 * chon nhu vay tu dau, day chi la mo rong ra).
 */
object Ui {

    /** Mau theo Y NGHIA cua viec, khong phai theo man hinh. */
    object C {
        /** Viec chinh cua man hinh. */
        val primary = 0xFF00695C.toInt()
        /** Viec phu, vo hai. */
        val neutral = 0xFF546E7A.toInt()
        /** Viec khong hoan tac duoc. */
        val danger = 0xFFC62828.toInt()
        /** Khoi thong tin can chu y nhung khong phai loi. */
        val info = 0xFF00838F.toInt()
        /** Khoi canh bao. */
        val warn = 0xFFEF6C00.toInt()
        val glossary = 0xFFEF6C00.toInt()
        val guide = 0xFF5E35B1.toInt()
    }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // ---------- chu ----------

    /** Tieu de mot muc lon. */
    fun heading(ctx: Context, s: String) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(ctx, 26), 0, dp(ctx, 10))
    }

    /** Than van thuong. `setLineSpacing` la thu tao ra khac biet lon nhat. */
    fun body(ctx: Context, s: CharSequence, size: Float = 14f) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        alpha = 0.82f
        setLineSpacing(dp(ctx, 5).toFloat(), 1f)
        setPadding(0, 0, 0, dp(ctx, 10))
    }

    /** Chu phu, dung cho ghi chu nho duoi mot dong chinh. */
    fun hint(ctx: Context, s: CharSequence) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.6f
        setLineSpacing(dp(ctx, 3).toFloat(), 1f)
    }

    @Suppress("DEPRECATION")
    fun html(s: String): CharSequence = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)

    // ---------- khoi ----------

    /**
     * Khoi noi dung co nen mau nhat va mot vach mau doc ben trai.
     *
     * Vach doc lam viec ma nen nhat khong lam duoc: no cho biet khoi **bat dau
     * va ket thuc o dau** khi chu tran nhieu dong.
     */
    fun panel(ctx: Context, color: Int, vararg children: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 12) }
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(0x12000000 or (color and 0x00FFFFFF))
        }
        addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 4), LinearLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 2).toFloat()
                setColor(color)
            }
        })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 12))
            children.forEach { addView(it) }
        })
    }

    /** Khoi don gian khong co vach, dung cho danh sach muc. */
    fun card(ctx: Context, color: Int, vararg children: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 10) }
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 10))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(0x10000000 or (color and 0x00FFFFFF))
        }
        children.forEach { addView(it) }
    }

    /** Nhan nho tron goc — dung cho loai muc, trang thai. */
    fun chip(ctx: Context, s: String, color: Int) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(color)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 8).toFloat()
            setColor(0x22000000 or (color and 0x00FFFFFF))
        }
    }

    // ---------- nut ----------

    /** Muc do "to tieng" cua mot nut. */
    enum class Weight { Filled, Tonal, Quiet }

    /**
     * Nut tu ve.
     *
     * Vi sao khong dung `Button`: nut mac dinh cua Android IN HOA het chu va to
     * mau xam giong het nhau, nen "Xong", "Xoá gói mô hình" va "Dịch lại các
     * trang đã dịch" nhin y het nhau — trong khi mot cai la thoat, mot cai xoa
     * 2,6 GB. Chu thuong de doc hon, va mau noi duoc viec nao nang.
     */
    fun button(
        ctx: Context,
        label: String,
        color: Int = C.primary,
        weight: Weight = Weight.Filled,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(ctx, 18), dp(ctx, 13), dp(ctx, 18), dp(ctx, 13))
        minHeight = dp(ctx, 46)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 10) }
        applySkin(ctx, this, label, color, weight)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Nut nho nam ngang hang, dung trong mot dong danh sach. */
    fun smallButton(
        ctx: Context,
        label: String,
        color: Int,
        weight: Weight = Weight.Tonal,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        minHeight = dp(ctx, 44)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(ctx, 8) }
        applySkin(ctx, this, label, color, weight)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * Doi mau / chu cua mot nut da tao. Dung cho nut chinh cua man cai dat —
     * no doi vai tro lien tuc (Tai ve / Tam dung / Thu lai / Xong).
     */
    fun restyle(ctx: Context, v: TextView, label: String, color: Int, weight: Weight) {
        v.text = label
        applySkin(ctx, v, label, color, weight)
    }

    private fun applySkin(ctx: Context, v: TextView, label: String, color: Int, weight: Weight) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(ctx, 12).toFloat()
            when (weight) {
                Weight.Filled -> setColor(color)
                Weight.Tonal -> setColor(0x1F000000 or (color and 0x00FFFFFF))
                Weight.Quiet -> {
                    setColor(0x00000000)
                    setStroke(dp(ctx, 1), 0x44000000 or (color and 0x00FFFFFF))
                }
            }
        }
        v.background = RippleDrawable(
            ColorStateList.valueOf(0x33000000 or (color and 0x00FFFFFF)), shape, null,
        )
        v.setTextColor(if (weight == Weight.Filled) Color.WHITE else color)
        v.text = label
    }

    /** Hang ngang chua cac nut nho. */
    fun buttonRow(ctx: Context, vararg buttons: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 8), 0, 0)
        buttons.forEach { addView(it) }
    }

    /** Duong ke mo — chi dung khi that su can cat hai khoi. */
    fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1),
        ).apply { topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 8) }
        setBackgroundColor(0x18808080)
    }

    /** Khoang tho giua hai khoi. */
    fun gap(ctx: Context, height: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, height),
        )
    }

    /**
     * Mot buoc duoc danh so, dung cho huong dan nhieu buoc.
     *
     * Danh so bang o tron chu khong bang "1." dau dong: mat bat duoc so buoc va
     * dang o buoc nao ma khong phai doc chu.
     */
    fun step(ctx: Context, n: Int, label: CharSequence, color: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
        addView(TextView(ctx).apply {
            text = n.toString()
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22))
                .apply { marginEnd = dp(ctx, 10); topMargin = dp(ctx, 2) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        })
        addView(TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            alpha = 0.9f
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
        })
    }
}
