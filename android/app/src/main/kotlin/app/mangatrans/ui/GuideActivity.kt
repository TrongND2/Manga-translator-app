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

        root.addView(heading("Bốn cử chỉ, hết"))
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
                "Hiện ba icon con:\n" +
                    "📖  mở trang hướng dẫn này\n" +
                    "⌖  khoanh lấy chữ trên màn hình\n" +
                    "✕  tắt app\n" +
                    "✕ cố ý để xa hơn hai cái kia — chạm nhầm là mất quyền chụp, phải cấp lại.",
            )
        )
        root.addView(
            card(
                "◐", 0xFF0277BD.toInt(), "Chạm giữ vào bóng thoại",
                "Chữ Nhật gốc của ĐÚNG bóng đó hiện lại trong lúc bạn giữ — các bóng " +
                    "khác vẫn là bản dịch. Thả ra là quay về. Dùng để đối chiếu.",
            )
        )
        root.addView(
            card(
                "✎", 0xFFEF6C00.toInt(), "Chạm hai cái vào bóng thoại",
                "Mở ô sửa bản dịch của riêng bóng đó, ngay trên trang đang đọc — " +
                    "không phải rời app đọc truyện. Sửa xong bấm Lưu là thấy đổi ngay " +
                    "tại chỗ.",
            )
        )
        root.addView(
            note(
                "Lật trang hay chuyển app là app dừng dịch ngay.",
                "Bản dịch của trang cũ được gỡ khỏi màn hình trong khoảng một giây, " +
                    "để nó không nằm chắn trang mới. Muốn dịch trang mới thì chạm " +
                    "icon lại.",
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
                "Chữ hiệu ứng ngoài bóng thoại không được dịch tự động.",
                "Bộ nhận diện gần như không bắt được loại chữ này. Nhưng bạn khoanh " +
                    "tay được — xem mục ⌖ bên dưới.",
            )
        )

        root.addView(heading("Khi có chỗ dịch sai"))
        root.addView(
            card(
                "✎", 0xFFEF6C00.toInt(), "Cách nhanh nhất: sửa thẳng bóng thoại đó",
                "Chạm hai cái vào bóng dịch sai → ô sửa hiện ra ngay trên trang. " +
                    "Có sẵn nút Hỏi Gemini nếu bạn không chắc nghĩa.\n\n" +
                    "Hai cách lưu, khác nhau rõ:\n" +
                    "• Lưu cho riêng trang này — chỉ đổi đúng chỗ đó.\n" +
                    "• Lưu vào từ điển riêng — áp cho MỌI trang, và app tự quên kết quả " +
                    "cũ của những trang đã dịch CÓ CHỨA cụm đó để chúng được dịch lại.",
            )
        )
        root.addView(
            card(
                "⌖", 0xFF00695C.toInt(), "Chữ NGOÀI bóng thoại: khoanh rồi đè bản dịch lên",
                "Chữ hiệu ứng và chữ nằm ngoài bóng thoại thì bộ nhận diện gần như " +
                    "không bắt được. Giữ icon → chạm ⌖ → kéo một khung quanh chữ đó. " +
                    "App đọc chữ Nhật trong khung, dịch, rồi bạn bấm \"Đè bản dịch lên " +
                    "trang\" là bản dịch hiện ngay tại chỗ đó.\n\n" +
                    "Lớp đè này hành xử như một bóng thoại bình thường: chạm giữ để hé " +
                    "chữ gốc, chạm hai cái để sửa hoặc \"Gỡ lớp này\" trả lại tranh.\n\n" +
                    "Lưu ý: chữ hiệu ứng thường nằm đè lên tranh, nên chỗ đè sẽ là một " +
                    "mảng màu che mất nét vẽ. Xem xong thì gỡ đi.",
            )
        )
        root.addView(
            card(
                "A", 0xFFEF6C00.toInt(), "Khoanh lấy chữ rồi tự đặt nghĩa",
                "Giữ icon → chạm ⌖ → kéo một khung quanh chữ cần lấy. App đọc chữ " +
                    "Nhật trong khung ra, bạn gõ nghĩa tiếng Việt rồi lưu.\n\n" +
                    "Đây là cách CHẮC CHẮN NHẤT để sửa một chỗ dịch sai: mô hình chạy " +
                    "trên máy đôi khi bỏ qua một cụm hoặc dịch thành ngữ theo nghĩa " +
                    "đen, và một mục từ điển ép được nó dịch đúng.",
            )
        )
        root.addView(
            note(
                "Thêm một mục từ điển sẽ tự dọn đúng những trang liên quan.",
                "App nhớ sẵn kết quả của các trang đã dịch, nên nếu không dọn thì " +
                    "chúng vẫn trả về bản cũ. Khi bạn lưu một mục, app quét phần nhớ " +
                    "đó và chỉ quên những trang CÓ CHỨA cụm chữ Nhật vừa thêm — các " +
                    "trang khác giữ nguyên. Nó báo cho bạn biết bao nhiêu trang.\n\n" +
                    "Muốn dọn sạch tất cả thì vào Cài đặt → \"Dịch lại các trang đã dịch\".",
            )
        )
        root.addView(
            note(
                "App cũng tự đề xuất tên nhân vật, nhưng hỏi bạn trước.",
                "Tên nào gặp nhiều lần thì app đưa vào mục \"Chờ bạn duyệt\" ở Từ điển " +
                    "riêng. Chưa bấm duyệt thì chưa được dùng khi dịch.",
            )
        )

        root.addView(heading("Hai nút dịch, chọn cái nào?"))
        root.addView(
            note(
                "📱 AI trên máy — luôn dùng được",
                "Dùng chính mô hình dịch nằm trong điện thoại, cùng mô hình vẫn dịch " +
                    "cả trang cho bạn. Không cần mạng, không tốn lượt, không giới hạn " +
                    "số lần.\n\n" +
                    "Chậm hơn: khoảng 20 giây cho một cụm, vì mô hình phải đọc lại " +
                    "hướng dẫn trước khi dịch. Nếu vừa dịch trang đó xong thì chỉ mất " +
                    "vài giây, vì nó nối tiếp phiên đang mở.",
            )
        )
        root.addView(
            note(
                "✨ Hỏi Gemini — nhanh, nhưng có hạn mức",
                "Khoảng 1 giây. Cần mạng và cần khoá miễn phí (hướng dẫn lấy nằm ở " +
                    "Cài đặt). Hạn mức tính theo ngày và do Google quyết, nên có lúc " +
                    "báo hết lượt hoặc máy chủ quá tải — khi đó dùng AI trên máy.\n\n" +
                    "Cả hai nút đều có ở ba chỗ: ô sửa bóng thoại, màn khoanh lấy chữ, " +
                    "và ô thêm mục ở Từ điển riêng.",
            )
        )
        root.addView(
            note(
                "Đây là đường ra mạng DUY NHẤT của app.",
                "Và nó rất hẹp: chỉ đúng cụm chữ bạn khoanh mới được gửi đi, không bao " +
                    "giờ gửi cả trang hay ảnh màn hình; chỉ chạy khi bạn tự bấm nút. " +
                    "Dịch trang vẫn chạy 100% trên máy. Không nhập khoá thì tính năng " +
                    "tắt hẳn và app chạy đủ như cũ.\n\n" +
                    "Hướng dẫn lấy khoá miễn phí nằm ở Cài đặt.",
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
