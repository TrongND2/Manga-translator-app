package app.mangatrans.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mangatrans.adapters.storage.JsonGlossaryStore
import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.GlossaryKind
import app.mangatrans.ports.GlossaryStatus
import app.mangatrans.ports.GlossaryStore
import app.mangatrans.ports.isUsableSurface
import kotlinx.coroutines.launch
import java.io.File

/**
 * Story 2.8 / 2.9 — FR-033 (xem va sua moi muc) + FR-034 (xac nhan muc tu de xuat).
 *
 * AD-7: MOI thay doi di qua `GlossaryStore`. Man hinh nay KHONG duoc dung toi
 * file JSON. No chi biet den interface.
 *
 * AD-19: MVP mot glossary toan cuc, `seriesKey = "default"`. Khong suy seriesKey
 * tu app dang chay truoc — viec do doi quyen `UsageStats`.
 *
 * Vi sao muc de xuat phai tach rieng va phai bam xac nhan: nguon de xuat la
 * truong `speaker` do LLM tra ve (AD-8), ma AD-4 da chot dau ra cua model la
 * du lieu KHONG dang tin. De xuat sai ma tu dong vao prompt thi no se lam hong
 * moi trang dich sau do, va khong ai phat hien duoc.
 */
class GlossaryActivity : AppCompatActivity() {

    companion object {
        /** Mot cho duy nhat quyet dinh glossary nam o dau. MainActivity dung chung. */
        fun file(ctx: Context): File = File(ctx.filesDir, "glossary.json")

        fun intent(ctx: Context) = Intent(ctx, GlossaryActivity::class.java)

        private const val SERIES = "default"
    }

    private lateinit var store: GlossaryStore
    private lateinit var root: LinearLayout

    private val kindLabel = mapOf(
        GlossaryKind.ProperNoun to "Tên riêng",
        GlossaryKind.Idiom to "Thành ngữ / tiếng lóng",
        GlossaryKind.Address to "Xưng hô",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Từ điển riêng"
        store = JsonGlossaryStore(file(this))

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 48)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    /**
     * Than KHOI chu khong phai than bieu thuc: cac nut trong danh sach goi lai
     * `refresh()`, nen than bieu thuc lam kieu tra ve phu thuoc vao chinh no
     * ("recursive problem" luc bien dich).
     */
    private fun refresh() {
        lifecycleScope.launch { rebuild() }
    }

    private suspend fun rebuild() {
        // Don muc app tu nhat nham. `isUsableSurface` chan de xuat MOI, nhung
        // muc da nam san trong file thi no khong dong toi — va da co ba muc rac
        // nhu vay tren may that (FINDINGS F27).
        //
        // Xoa tu dong duoc vi day la muc DO APP TU DE XUAT, khong phai muc
        // nguoi dung go tay, va chung khong bao gio khop voi chu tren trang nen
        // giu lai cung vo nghia. Van bao ra man hinh chu khong lam len.
        val junk = store.proposed(SERIES).filterNot { isUsableSurface(it.surface) }
        junk.forEach { store.delete(SERIES, it.surface) }

        val proposed = store.proposed(SERIES)
        val confirmed = store.confirmed(SERIES)

        root.removeAllViews()

        root.addView(Button(this@GlossaryActivity).apply {
            text = "+ Thêm mục"
            setOnClickListener { edit(null) }
        })

        // --- Muc cho xac nhan (FR-034) ---
        if (proposed.isNotEmpty()) {
            root.addView(header("Chờ xác nhận (${proposed.size})"))
            root.addView(note(
                "App tự nhặt các tên này từ trường \"người nói\" mà mô hình trả về. " +
                    "Chúng CHƯA được dùng khi dịch — bấm ✓ thì mới dùng."
            ))
            proposed.sortedBy { it.surface }.forEach { root.addView(proposedRow(it)) }
        }

        if (junk.isNotEmpty()) {
            root.addView(note(
                "Đã bỏ ${junk.size} mục app nhặt nhầm (không phải chữ Nhật nên không bao giờ " +
                    "khớp được với trang truyện): ${junk.joinToString(", ") { it.surface }}"
            ))
        }

        // --- Muc dang dung (FR-033) ---
        root.addView(header("Đang dùng khi dịch (${confirmed.size})"))
        if (confirmed.isEmpty()) {
            root.addView(note("Chưa có mục nào. Từ điển trống thì bản dịch hay bịa tên riêng."))
        } else {
            confirmed.sortedWith(compareBy({ it.kind.ordinal }, { it.surface }))
                .forEach { root.addView(confirmedRow(it)) }
        }
    }

    // ---------- cac dong ----------

    private fun proposedRow(e: GlossaryEntry): LinearLayout = row(e) { bar ->
        bar.addView(smallButton("✓ Dùng") {
            lifecycleScope.launch {
                store.confirm(SERIES, e.surface)
                toast("Đã thêm \"${e.surface}\" vào từ điển")
                refresh()
            }
        })
        bar.addView(smallButton("✎ Sửa rồi dùng") { edit(e, promote = true) })
        bar.addView(smallButton("✗ Bỏ") { confirmDelete(e) })
    }

    private fun confirmedRow(e: GlossaryEntry): LinearLayout = row(e) { bar ->
        bar.addView(smallButton("✎ Sửa") { edit(e) })
        bar.addView(smallButton("✗ Xoá") { confirmDelete(e) })
    }

    private fun row(e: GlossaryEntry, buttons: (LinearLayout) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 4)
            addView(TextView(this@GlossaryActivity).apply {
                text = "${e.surface}  →  ${e.meaning}"
                textSize = 16f
            })
            addView(TextView(this@GlossaryActivity).apply {
                text = kindLabel[e.kind]
                textSize = 11f
                alpha = 0.6f
            })
            addView(LinearLayout(this@GlossaryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons(this)
            })
            addView(View(this@GlossaryActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                setBackgroundColor(0x22808080)
            })
        }

    // ---------- them / sua ----------

    /**
     * @param promote muc de xuat: sua xong thi chuyen thang sang "dang dung".
     *
     * Sua `surface` = doi KHOA, nen phai xoa muc cu roi ghi muc moi. Neu chi
     * `upsert` thi muc cu o lai va glossary co hai muc mau thuan nhau — mo hinh
     * se thay ca hai trong prompt.
     */
    private fun edit(old: GlossaryEntry?, promote: Boolean = false) {
        val surface = EditText(this).apply {
            hint = "Nguyên bản tiếng Nhật, ví dụ ソデにする"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(old?.surface ?: "")
        }
        val meaning = EditText(this).apply {
            hint = "Dịch sang tiếng Việt"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(old?.meaning ?: "")
        }
        val kinds = GlossaryKind.entries.toList()
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@GlossaryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                kinds.map { kindLabel[it] },
            )
            setSelection(kinds.indexOf(old?.kind ?: GlossaryKind.ProperNoun))
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(surface); addView(meaning); addView(kind)
        }

        AlertDialog.Builder(this)
            .setTitle(if (old == null) "Thêm mục" else "Sửa mục")
            .setView(form)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ ->
                val s = surface.text.toString().trim()
                val m = meaning.text.toString().trim()
                if (s.isEmpty() || m.isEmpty()) { toast("Phải điền cả hai ô"); return@setPositiveButton }
                // Cung mot luat voi muc app tu de xuat (F27): o nguyen ban phai
                // khop duoc voi chu tren trang truyen, khong thi vo dung.
                if (!isUsableSurface(s)) {
                    toast("Ô nguyên bản phải là chữ Nhật — nó cần khớp với chữ in trên trang truyện")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    // Doi surface = doi khoa => phai xoa muc cu.
                    if (old != null && old.surface != s) store.delete(SERIES, old.surface)
                    store.upsertByUser(GlossaryEntry(
                        seriesKey = SERIES,
                        surface = s,
                        meaning = m,
                        kind = kinds[kind.selectedItemPosition],
                        // `upsertByUser` tu dat Confirmed — nguoi dung go tay thi
                        // khong con gi de xac nhan nua.
                        status = GlossaryStatus.Confirmed,
                    ))
                    refresh()
                }
            }
            .show()
        if (promote) toast("Sửa xong sẽ được dùng ngay khi dịch")
    }

    private fun confirmDelete(e: GlossaryEntry) {
        AlertDialog.Builder(this)
            .setMessage("Xoá \"${e.surface}\"?")
            .setNegativeButton("Không", null)
            .setPositiveButton("Xoá") { _, _ ->
                lifecycleScope.launch { store.delete(SERIES, e.surface); refresh() }
            }
            .show()
    }

    // ---------- vun vat ----------

    private fun header(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 40, 0, 4)
    }

    private fun note(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 11f
        alpha = 0.7f
        setPadding(0, 0, 0, 8)
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(20, 0, 20, 0)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
