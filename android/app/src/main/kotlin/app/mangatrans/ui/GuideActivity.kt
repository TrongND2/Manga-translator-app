package app.mangatrans.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.mangatrans.adapters.overlay.FloatingIcon

/**
 * Huong dan su dung. Mo tu icon con hinh dau hoi, hoac tu man hinh chinh.
 *
 * Ban truoc do la mot khoi HTML dai lien tuc — dung thong tin nhung doc met, va
 * nguoi can huong dan thuong dang boi roi san roi. Ban nay chia thanh the: moi
 * y mot the, co icon, co khoang tho.
 *
 * Bang trang thai KHONG viet tay: no sinh tu chinh `FloatingIcon.State`. Viet
 * tay thi them mot trang thai moi vao icon se lam huong dan noi sai ma khong co
 * gi bao.
 */
class GuideActivity : AppCompatActivity() {

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Hướng dẫn sử dụng"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        root.addView(heading("Ba cử chỉ, hết"))
        root.addView(
            card(
                "◉", 0xFF00695C.toInt(), "Chạm icon",
                "Dịch cả trang đang hiện. Bản dịch hiện dần từng bóng thoại — " +
                    "không phải chờ xong cả trang mới thấy gì.",
            )
        )
        root.addView(
            card(
                "≡", 0xFF5E35B1.toInt(), "Giữ icon",
                "Hiện hai icon con: ? mở trang này, ✕ tắt app.\n" +
                    "✕ cố ý để xa hơn — chạm nhầm là mất quyền chụp, phải cấp lại.",
            )
        )
        root.addView(
            card(
                "◐", 0xFF0277BD.toInt(), "Chạm giữ vào bóng thoại",
                "Chữ Nhật gốc hiện lại trong lúc bạn giữ. Thả ra là bản dịch quay về. " +
                    "Dùng để đối chiếu.",
            )
        )

        root.addView(heading("Icon đang nói gì"))
        FloatingIcon.State.entries.forEach { s ->
            root.addView(stateRow(s.glyph, s.color, s.label))
        }

        root.addView(heading("Những điều nên biết"))
        root.addView(
            note(
                "Trang đầu mất khoảng 3 phút.",
                "Phần lớn thời gian đó là mô hình đọc hết trang trước khi sinh chữ đầu tiên. " +
                    "Không phải app treo. Trang đã dịch rồi thì gần như tức thì.",
            )
        )
        root.addView(
            note(
                "App không bao giờ sửa app bạn đang đọc.",
                "Bản dịch chỉ là một lớp vẽ đè lên trên. Tắt app là trang truyện trở lại " +
                    "nguyên bản tiếng Nhật y như cũ.",
            )
        )
        root.addView(
            note(
                "Khoá màn hình sẽ làm dừng quyền chụp.",
                "Đây là cách Android hoạt động, không phải lỗi. Mở lại máy thì chạm icon " +
                    "một cái để cấp lại.",
            )
        )
        root.addView(
            note(
                "Có app chặn chụp màn hình.",
                "Một số app đặt cờ bảo vệ bản quyền, Android sẽ không cho chụp. Không có " +
                    "cách vòng. Khi đó app báo rõ lý do chứ không im lặng hỏng.",
            )
        )
        root.addView(
            note(
                "Chữ hiệu ứng ngoài bóng thoại vẫn là tiếng Nhật.",
                "Bộ nhận diện gần như không bắt được loại chữ này.",
            )
        )

        root.addView(heading("Từ điển riêng"))
        root.addView(
            note(
                "Dịch sai tên nhân vật? Sửa một lần, các trang sau nhớ theo.",
                "App tự nhặt tên nó gặp nhiều lần và hỏi bạn trước khi dùng. " +
                    "Mở từ màn hình chính → Từ điển riêng.",
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------- cac khoi ----------

    private fun heading(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(10))
    }

    private fun card(icon: String, color: Int, title: String, body: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(10)) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x14000000 or (color and 0x00FFFFFF))
            }
            addView(TextView(this@GuideActivity).apply {
                text = icon
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                    .apply { marginEnd = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            })
            addView(LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@GuideActivity).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(this@GuideActivity).apply {
                    text = body
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    alpha = 0.75f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }

    private fun stateRow(glyph: String, color: Int, label: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@GuideActivity).apply {
                text = glyph
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                    .apply { marginEnd = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            })
            addView(TextView(this@GuideActivity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
        }

    private fun note(title: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(14))
        addView(TextView(this@GuideActivity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
        })
        addView(TextView(this@GuideActivity).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.75f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(4), 0, 0)
        })
        addView(View(this@GuideActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(12) }
            setBackgroundColor(0x18808080)
        })
    }
}
