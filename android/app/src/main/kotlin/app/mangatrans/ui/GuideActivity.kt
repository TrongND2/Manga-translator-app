package app.mangatrans.ui

import android.os.Bundle
import android.text.Html
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.mangatrans.adapters.overlay.FloatingIcon

/**
 * Story 3.10 — FR-008, FR-009. Mo tu icon con hinh quyen sach.
 *
 * Bang trang thai KHONG viet tay o day: no sinh tu chinh `FloatingIcon.State`.
 * Neu viet tay thi them mot trang thai moi vao icon se lam huong dan noi sai,
 * ma khong co gi bao.
 */
class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Hướng dẫn sử dụng"

        val states = FloatingIcon.State.entries.joinToString("") {
            "<tr><td>&nbsp;<b>${it.glyph}</b>&nbsp;&nbsp;</td><td>${it.label}</td></tr>"
        }

        val html = """
            <h3>Ba cử chỉ, hết</h3>
            <p><b>Chạm một cái</b> vào icon → dịch cả trang đang hiện trên màn hình.
            Bản dịch hiện dần từng bóng thoại, không phải chờ xong cả trang.</p>

            <p><b>Giữ icon</b> → hiện hai icon con:<br/>
            &nbsp;&nbsp;📖 mở đúng trang hướng dẫn này<br/>
            &nbsp;&nbsp;✕ tắt app</p>
            <p style="color:#777"><small>Icon ✕ cố ý để xa hơn 📖. Chạm nhầm ✕ là mất
            phiên chụp màn hình và phải cấp quyền lại từ đầu.</small></p>

            <p><b>Chạm giữ vào chỗ có bản dịch</b> → chữ Nhật gốc hiện lại trong lúc
            bạn giữ; thả ra là bản dịch quay về. Dùng để đối chiếu.</p>

            <h3>Icon đang nói gì</h3>
            <table>$states</table>

            <h3>Những điều nên biết trước</h3>
            <p><b>Trang đầu tiên mất khoảng một phút rưỡi.</b> Phần lớn thời gian đó là
            mô hình đọc xong đề bài trước khi sinh chữ đầu tiên. Đây là giới hạn của
            kiểu mô hình này, không phải app bị treo. Trang đã dịch rồi thì lần sau
            chỉ mất hơn một giây.</p>

            <p><b>App không bao giờ sửa nội dung app bạn đang đọc.</b> Bản dịch chỉ là
            một lớp vẽ đè lên trên. Tắt app là trang truyện trở lại nguyên bản tiếng
            Nhật y như cũ.</p>

            <p><b>Khoá màn hình sẽ làm dừng phiên chụp.</b> Đây là cách Android hoạt
            động, không phải lỗi. Mở lại máy thì icon chuyển sang 🔑 — chạm một cái
            để cấp lại.</p>

            <p><b>Có app chặn chụp màn hình.</b> Một số app đặt cờ bảo vệ bản quyền,
            Android sẽ không cho chụp. Không có cách vòng. Khi đó app báo rõ lý do
            chứ không im lặng hỏng.</p>

            <p><b>Chữ hiệu ứng nằm ngoài bóng thoại vẫn là tiếng Nhật.</b> Bộ nhận
            diện gần như không bắt được loại chữ này, nên nó nằm ngoài phạm vi bản
            hiện tại.</p>

            <h3>Từ điển riêng</h3>
            <p>App tự nhặt tên nhân vật nó gặp nhiều lần và <b>hỏi bạn</b> trước khi
            dùng. Mở màn hình chính → <i>Từ điển riêng</i> để xem, sửa, xác nhận.
            Từ điển càng đúng thì bản dịch càng nhất quán.</p>
        """.trimIndent()

        val text = TextView(this).apply {
            setPadding(40, 32, 40, 64)
            textSize = 15f
            @Suppress("DEPRECATION")
            setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT))
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
