package app.mangatrans.adapters.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mangatrans.ui.Ui

/**
 * Chu vua khoanh duoc: dich no, va de ban dich len dung cho do tren trang.
 *
 * Vi sao la cua so noi chu khong phai Activity: ban truoc la `GrabTextActivity`
 * — mot man hinh kin. Ma man hinh kin thi bo canh trang (F61/F64) ket luan
 * nguoi dung da roi trang va **go sach lop phu** — dung cai lop ma tinh nang
 * nay muon ve them vao. Cung bai hoc voi F68, chi khac cho.
 *
 * Hai duong dich, de canh nhau va ghi ro GIA cua tung duong, vi chung chenh
 * nhau hang chuc lan:
 *
 *   - **tren may**  : ~3 s neu trang nay vua dich xong (noi vao phien dang mo),
 *                     ~20 s neu chua (phai doc lai prompt he thong tu dau).
 *   - **Gemini**    : ~1 s, nhung can mang va an vao han muc ngay.
 */
class GrabResultOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onLocal: (String, (Result<String>) -> Unit) -> Unit,
    private val onGemini: (String, (Result<String>) -> Unit) -> Unit,
    private val onDraw: (String) -> Unit,
    private val onSaveGlossary: (String) -> Unit,
    private val onClosed: () -> Unit,
) {

    private fun dp(v: Int) = Ui.dp(ctx, v)

    private var attached = false
    private var root: View? = null
    private var input: EditText? = null
    private var status: TextView? = null

    val isOpen: Boolean get() = attached

    fun show(ja: String) {
        if (attached) hide()

        val jaView = TextView(ctx).apply {
            text = ja
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val field = EditText(ctx).apply {
            hint = "Nghĩa tiếng Việt"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        input = field
        val st = Ui.hint(ctx, "").also { status = it }

        fun typed(): String = field.text.toString().trim()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFFFAFAFA.toInt())
            }
            elevation = dp(8).toFloat()

            addView(TextView(ctx).apply {
                text = "Chữ vừa lấy"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF212121.toInt())
                setPadding(0, 0, 0, dp(10))
            })
            addView(Ui.panel(ctx, Ui.C.neutral, jaView))
            addView(field)
            addView(Ui.buttonRow(
                ctx,
                Ui.smallButton(ctx, "📱  AI trên máy", Ui.C.primary) {
                    st.text = "Đang dịch trên máy..."
                    onLocal(ja) { r -> back(r) }
                },
                Ui.smallButton(ctx, "✨  Hỏi Gemini", Ui.C.info) {
                    st.text = "Đang hỏi Gemini..."
                    onGemini(ja) { r -> back(r) }
                },
            ))
            addView(st)
            addView(Ui.hint(
                ctx,
                "\"AI trên máy\" dùng chính mô hình dịch trong điện thoại — không cần " +
                    "mạng, không tốn lượt. Chậm hơn Gemini nhưng luôn dùng được.",
            ))

            addView(Ui.gap(ctx, 6))
            addView(Ui.button(ctx, "Đè bản dịch lên trang") {
                val s = typed()
                if (s.isEmpty()) st.text = "Chưa có nghĩa để đè. Dịch hoặc tự gõ trước."
                else { onDraw(s); hide() }
            })
            addView(Ui.hint(
                ctx,
                "Vẽ đè lên đúng khung bạn vừa khoanh. Chạm giữ để hé chữ gốc, " +
                    "chạm hai cái để sửa hoặc gỡ.",
            ))

            addView(Ui.gap(ctx, 12))
            addView(Ui.button(ctx, "Lưu vào từ điển riêng", Ui.C.glossary, Ui.Weight.Tonal) {
                val s = typed()
                if (s.isEmpty()) st.text = "Chưa có nghĩa để lưu."
                else { onSaveGlossary(s); hide() }
            })
            addView(Ui.gap(ctx, 8))
            addView(Ui.button(ctx, "Đóng", Ui.C.neutral, Ui.Weight.Quiet) { hide() })
        }

        val frame = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) {
                    hide(); return true
                }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            setBackgroundColor(0xB0000000.toInt())
            setOnClickListener { hide() }
            addView(ScrollView(ctx).apply {
                addView(card, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ))
                setPadding(dp(16), dp(24), dp(16), dp(24))
                clipToPadding = false
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            ))
        }
        card.isClickable = true
        card.setOnClickListener { }

        root = frame
        runCatching { wm.addView(frame, params()) }.onSuccess { attached = true }
    }

    private fun back(r: Result<String>) {
        val st = status ?: return
        r.onSuccess {
            input?.setText(it)
            st.text = "Xong — sửa lại nếu cần, rồi bấm Đè hoặc Lưu."
        }.onFailure { st.text = "Không dịch được: ${it.message}" }
    }

    fun hide() {
        if (!attached) return
        root?.let { runCatching { wm.removeView(it) } }
        root = null; input = null; status = null
        attached = false
        onClosed()
    }

    /** Xem ghi chu o `EditBubbleOverlay.params` — cung ly do, cung bay. */
    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
