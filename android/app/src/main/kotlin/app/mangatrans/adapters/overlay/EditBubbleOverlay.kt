package app.mangatrans.adapters.overlay

import android.content.Context
import android.graphics.Color
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
 * Sua ban dich cua MOT bong thoai, **ngay tren trang dang doc**.
 *
 * Vi sao la cua so noi chu khong phai mot Activity: ban dau man nay lam bang
 * Activity, va no hong theo mot kieu rat dep — Activity kin man hinh nen bo
 * canh trang (F61/F64) thay khung hinh doi that va ket luan nguoi dung da lat
 * trang, roi **go sach lop phu dung cai ta dang sua**. Do duoc tren may:
 *
 * ```
 * 20:23:13  man hinh doi (khac 0.94) — dung dich, go lop phu
 * ```
 *
 * Co the va bang cach tam dung bo canh, nhung nhu the la chua trieu chung.
 * Nguyen nhan that la **roi trang** — nen dung roi trang nua. Cua so noi thi
 * app doc truyen khong he bi day xuong nen, trang van nam nguyen do, va bong
 * thoai dang sua van nhin thay ngay ben canh o nhap.
 *
 * ⚠️ Cua so nay **nhan ban phim** (khong dat `FLAG_NOT_FOCUSABLE`), vi nguoi
 * dung phai go chu vao day. Doi lai la no nuot phim Back, nen phai tu bat
 * `KEYCODE_BACK` de dong — khong co Activity nao lam ho viec do.
 */
class EditBubbleOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onSavePage: (String) -> Unit,
    /** Go han lop de thu cong. Chi hien voi lop do, khong hien voi bong thoai. */
    private val onRemove: () -> Unit,
    private val onSaveGlossary: (String) -> Unit,
    /** Goi Gemini o ngoai (service giu scope); tra ket qua ve qua callback. */
    private val onAsk: (String, (Result<String>) -> Unit) -> Unit,
    /** Dich bang mo hinh chay tren may — duong du phong khi Gemini het luot. */
    private val onLocal: (String, (Result<String>) -> Unit) -> Unit,
    private val onClosed: () -> Unit,
) {

    private fun dp(v: Int) = Ui.dp(ctx, v)

    private var attached = false
    private var root: View? = null
    private var input: EditText? = null
    private var status: TextView? = null

    val isOpen: Boolean get() = attached

    /**
     * @param removable lop nay co go han duoc khong. Bong thoai do day chuyen
     *   sinh ra thi KHONG — go mot bong le se de lai mot lo tren trang ma
     *   khong co duong nao lay lai. Lop de thu cong thi go duoc, vi no che len
     *   tranh goc va nguoi dung phai lay lai tranh duoc.
     */
    fun show(ja: String, vi: String, removable: Boolean = false) {
        if (attached) hide()

        val jaView = TextView(ctx).apply {
            text = ja
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val field = EditText(ctx).apply {
            setText(vi)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setSelection(text.length)
        }
        input = field
        val st = Ui.hint(ctx, "").also { status = it }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFFFAFAFA.toInt())
            }
            elevation = dp(8).toFloat()

            addView(TextView(ctx).apply {
                text = "Sửa bóng thoại này"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF212121.toInt())
                setPadding(0, 0, 0, dp(10))
            })
            addView(Ui.panel(ctx, Ui.C.neutral, jaView))
            addView(Ui.hint(ctx, "Bản dịch"))
            addView(field)
            addView(Ui.buttonRow(
                ctx,
                Ui.smallButton(ctx, "📱  AI trên máy", Ui.C.primary) {
                    status?.text = "Đang dịch trên máy..."
                    onLocal(ja) { r -> fill(r) }
                },
                Ui.smallButton(ctx, "✨  Hỏi Gemini", Ui.C.info) { ask(ja) },
            ))
            addView(st)
            addView(Ui.gap(ctx, 6))
            addView(Ui.button(ctx, "Lưu cho riêng trang này") {
                val s = field.text.toString().trim()
                if (s.isEmpty()) { st.text = "Bản dịch đang để trống." }
                else { onSavePage(s); hide() }
            })
            // ⚠️ Nguyen ban o day la NGUYEN VAN ca bong thoai, nen phan lon la
            // ca mot cau. Dua ca cau vao glossary vua vo dung vua dat: no chi
            // khop dung cau do tren dung trang do, ma van keo theo
            // `forgetContaining` — tuc xoa cache cua chinh trang vua dich xong.
            //
            // Do tren may that (F86): trang dang hit cache trong 2,6 giay; sau
            // khi luu mot cau vao glossary thi lan sau phai dich lai tu dau.
            // Nguoi dung tao 4 muc ca cau nhu vay chi trong mot phien.
            val wholeSentence = app.mangatrans.ports.looksLikeWholeSentence(ja)
            addView(Ui.button(ctx, "Lưu vào từ điển riêng", Ui.C.glossary, Ui.Weight.Tonal) {
                val s = field.text.toString().trim()
                when {
                    s.isEmpty() -> st.text = "Bản dịch đang để trống."
                    // Chan chu khong chi canh bao: dong chu canh bao cu da nam
                    // ngay duoi nut va van khong ngan duoc 4 muc rac.
                    wholeSentence -> st.text =
                        "Nguyên bản ở trên là cả một câu nên chỉ khớp đúng trang này, " +
                            "mà lưu vào từ điển sẽ xoá cache và bắt dịch lại cả trang. " +
                            "Dùng \"Lưu cho riêng trang này\" — nhanh hơn và giữ đúng chỗ sửa."
                    else -> { onSaveGlossary(s); hide() }
                }
            })
            addView(Ui.hint(
                ctx,
                if (wholeSentence)
                    "Nguyên bản ở trên là cả một câu — hãy dùng \"Lưu cho riêng trang này\". " +
                        "Từ điển riêng chỉ hợp với cụm ngắn lặp lại nhiều trang."
                else
                    "\"Từ điển riêng\" áp cho MỌI trang về sau — chỉ nên dùng khi cụm " +
                        "chữ Nhật ở trên là cụm lặp lại (tên nhân vật, thành ngữ, xưng hô).",
            ))
            if (removable) {
                addView(Ui.gap(ctx, 12))
                addView(Ui.button(ctx, "Gỡ lớp này", Ui.C.danger, Ui.Weight.Quiet) {
                    onRemove(); hide()
                })
                addView(Ui.hint(ctx, "Trả lại tranh gốc ở đúng chỗ này."))
            }
            addView(Ui.gap(ctx, 8))
            addView(Ui.button(ctx, "Đóng", Ui.C.neutral, Ui.Weight.Quiet) { hide() })
        }

        // Nen toi: vua tach cai the ra khoi trang truyen, vua la cho de cham
        // ra ngoai cho dong.
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
                isFillViewport = false
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
        // Cham vao the thi khong duoc coi la cham ra ngoai.
        card.isClickable = true
        card.setOnClickListener { }

        root = frame
        runCatching { wm.addView(frame, params()) }.onSuccess { attached = true }
    }

    private fun ask(ja: String) {
        status?.text = "Đang hỏi Gemini..."
        onAsk(ja) { r -> fill(r) }
    }

    private fun fill(r: Result<String>) {
        val st = status ?: return
        r.onSuccess {
            input?.setText(it)
            st.text = "Xong — sửa lại nếu thấy chưa đúng rồi bấm Lưu."
        }.onFailure { st.text = "Không dịch được: ${it.message}" }
    }

    fun hide() {
        if (!attached) return
        root?.let { runCatching { wm.removeView(it) } }
        root = null; input = null; status = null
        attached = false
        onClosed()
    }

    /**
     * ⚠️ KHONG dat `FLAG_NOT_FOCUSABLE`: cua so nay phai nhan duoc ban phim.
     *
     * Cung vi the KHONG dat `FLAG_LAYOUT_NO_LIMITS` — co do chan `ADJUST_RESIZE`,
     * va khi do ban phim len se che mat cac nut Luu.
     */
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
