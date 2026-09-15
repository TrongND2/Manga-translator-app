package app.mangatrans.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mangatrans.Composition
import app.mangatrans.adapters.cloud.GeminiLookup
import app.mangatrans.adapters.storage.JsonGlossaryStore
import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.GlossaryKind
import app.mangatrans.ports.GlossaryStatus
import kotlinx.coroutines.launch

/**
 * Chu vua lay duoc tu vung nguoi dung khoanh: xem, chep, tra nghia, luu vao
 * tu dien rieng.
 *
 * Vi sao man nay ton tai: mo hinh dich chay tren may **im lang bo bot** nhung
 * cum no ngai dich (F60), va cach duy nhat ep duoc no la mot muc tu dien. Nhung
 * nguoi dung khong go duoc chu Nhat de tao muc do. Day la mat xich con thieu.
 */
class GrabTextActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TEXT = "ja"

        fun intent(ctx: Context, ja: String) =
            Intent(ctx, GrabTextActivity::class.java)
                .putExtra(EXTRA_TEXT, ja)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private lateinit var meaning: EditText
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Chữ vừa lấy"
        val ja = intent?.getStringExtra(EXTRA_TEXT).orEmpty()

        val jaView = TextView(this).apply {
            text = ja
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(4))
        }
        val note = TextView(this).apply {
            text = "Chạm giữ để bôi đen và chép, hoặc dùng nút bên dưới."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            setPadding(0, 0, 0, dp(16))
        }
        meaning = EditText(this).apply {
            hint = "Nghĩa tiếng Việt — bạn tự gõ, hoặc bấm Hỏi Gemini"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.8f
            setPadding(0, dp(8), 0, dp(8))
        }

        val copy = Button(this).apply {
            text = "Sao chép chữ Nhật"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("manga", ja))
                Toast.makeText(this@GrabTextActivity, "Đã chép.", Toast.LENGTH_SHORT).show()
            }
        }
        val ask = Button(this).apply {
            text = "Hỏi Gemini"
            setOnClickListener { askGemini(ja) }
        }
        val save = Button(this).apply {
            text = "Lưu vào từ điển riêng"
            setOnClickListener { save(ja) }
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@GrabTextActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(32))
                addView(jaView); addView(note)
                addView(meaning); addView(status)
                addView(ask); addView(copy); addView(save)
            })
        })
    }

    /**
     * ⚠️ Day la LAN DUY NHAT app goi ra ngoai internet de dich, va no chi gui
     * **dung cum chu nguoi dung vua khoanh** — khong bao gio gui ca trang hay
     * anh man hinh. Dich trang van chay hoan toan tren may (D1).
     */
    private fun askGemini(ja: String) {
        val key = GeminiLookup.key(this)
        if (key.isNullOrBlank()) {
            status.text = "Chưa có khoá Gemini. Vào Cài đặt / gói mô hình để dán khoá."
            return
        }
        status.text = "Đang hỏi Gemini..."
        lifecycleScope.launch {
            val r = GeminiLookup(key).meaningOf(ja)
            r.onSuccess {
                meaning.setText(it)
                status.text = "Gemini trả lời xong — sửa lại nếu cần rồi bấm Lưu."
            }.onFailure {
                status.text = "Không hỏi được: ${it.message}"
            }
        }
    }

    private fun save(ja: String) {
        val m = meaning.text.toString().trim()
        if (m.isEmpty()) { status.text = "Nhập nghĩa tiếng Việt trước đã."; return }
        lifecycleScope.launch {
            val store = JsonGlossaryStore(Composition.glossaryFile(this@GrabTextActivity))
            // Vao thang trang thai DA XAC NHAN: muc nguoi dung tu nhap thi khong
            // can duyet lai (AD-7).
            store.propose(
                GlossaryEntry(
                    seriesKey = "default", surface = ja, meaning = m,
                    kind = GlossaryKind.Idiom, status = GlossaryStatus.Confirmed,
                )
            )
            store.confirm("default", ja)
            Toast.makeText(
                this@GrabTextActivity,
                "Đã lưu. Trang dịch mới sẽ dùng nghĩa này.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }
}
