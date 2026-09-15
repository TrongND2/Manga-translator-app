package app.mangatrans.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mangatrans.adapters.cloud.GeminiLookup
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
            setPadding(Ui.dp(context, 18), Ui.dp(context, 8), Ui.dp(context, 18), Ui.dp(context, 40))
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

        root.addView(Ui.body(
            this,
            "Mỗi mục ở đây ép mô hình dịch một cụm chữ Nhật theo đúng nghĩa bạn " +
                "đặt. Đây là cách chắc chắn nhất để sửa một chỗ dịch sai.",
            13f,
        ))
        root.addView(Ui.button(this, "＋  Thêm mục", Ui.C.glossary) { edit(null) })
        root.addView(Ui.hint(
            this,
            "Không gõ được chữ Nhật? Giữ icon nổi → chạm ⌖ → khoanh lấy chữ ngay " +
                "trên trang truyện.",
        ))

        // --- Muc cho xac nhan (FR-034) ---
        if (proposed.isNotEmpty()) {
            root.addView(Ui.heading(this, "Chờ bạn duyệt  ·  ${proposed.size}"))
            root.addView(Ui.body(
                this,
                "App tự nhặt các tên này từ trường \"người nói\" mà mô hình trả về. " +
                    "Chúng CHƯA được dùng khi dịch.",
                13f,
            ))
            proposed.sortedBy { it.surface }.forEach { root.addView(proposedRow(it)) }
        }

        if (junk.isNotEmpty()) {
            root.addView(Ui.panel(
                this, Ui.C.warn,
                Ui.hint(
                    this,
                    "Đã bỏ ${junk.size} mục app nhặt nhầm — không phải chữ Nhật nên " +
                        "không bao giờ khớp được với trang truyện: " +
                        junk.joinToString(", ") { it.surface },
                ),
            ))
        }

        // --- Muc dang dung (FR-033) ---
        root.addView(Ui.heading(this, "Đang dùng khi dịch  ·  ${confirmed.size}"))
        if (confirmed.isEmpty()) {
            root.addView(Ui.panel(
                this, Ui.C.neutral,
                Ui.body(
                    this,
                    "Chưa có mục nào. Từ điển trống thì bản dịch hay bịa tên riêng và " +
                        "dịch thành ngữ theo nghĩa đen.",
                    13f,
                ),
            ))
        } else {
            confirmed.sortedWith(compareBy({ it.kind.ordinal }, { it.surface }))
                .forEach { root.addView(confirmedRow(it)) }
        }
    }

    // ---------- cac dong ----------

    private fun proposedRow(e: GlossaryEntry): LinearLayout = row(e, Ui.C.warn) { bar ->
        // ⚠️ KHONG cho bam thang "Dung" khi muc chua co nghia tieng Viet.
        //
        // Muc app tu de xuat sinh ra tu truong "nguoi noi", nen `meaning` ban
        // dau bang chinh `surface`. Xac nhan mot muc nhu the se nhet vao prompt
        // dong `小生 [ten rieng]: 小生` — tuc la **bao mo hinh giu nguyen tieng
        // Nhat** o dung cum do. Nut do khong chi vo dung ma con lam hong ban dich.
        if (!needsMeaning(e)) {
            bar.addView(Ui.smallButton(this, "✓  Dùng", Ui.C.primary, Ui.Weight.Filled) {
                lifecycleScope.launch {
                    store.confirm(SERIES, e.surface)
                    toast("Đã thêm \"${e.surface}\" vào từ điển")
                    refresh()
                }
            })
        }
        bar.addView(Ui.smallButton(
            this,
            if (needsMeaning(e)) "Đặt nghĩa rồi dùng" else "Sửa rồi dùng",
            Ui.C.info,
            if (needsMeaning(e)) Ui.Weight.Filled else Ui.Weight.Tonal,
        ) { edit(e, promote = true) })
        bar.addView(Ui.smallButton(this, "Bỏ", Ui.C.danger, Ui.Weight.Quiet) { confirmDelete(e) })
    }

    /**
     * Quen ket qua dich cua nhung trang CO CHUA cum vua them.
     *
     * Vi sao can: khoa cache tinh tu ANH, khong tu tu dien — nen sua tu dien
     * xong thi cac trang **da dich roi** van tra ve ban cu mai mai, va nguoi
     * dung sua ma khong thay gi doi. Truoc day duong duy nhat la xoa SACH moi
     * trang da dich, ke ca hang tram trang khong lien quan.
     */
    private suspend fun forgetPagesWith(surface: String) {
        val n = runCatching {
            app.mangatrans.adapters.storage.FileCache(
                java.io.File(cacheDir, "pages")
            ).forgetContaining(surface)
        }.getOrDefault(0)
        if (n > 0) toast("$n trang đã dịch có cụm này sẽ được dịch lại khi bạn mở")
    }

    /** Muc chi moi co chu Nhat, chua ai dat nghia tieng Viet cho no. */
    private fun needsMeaning(e: GlossaryEntry) = e.meaning.trim() == e.surface.trim()

    private fun confirmedRow(e: GlossaryEntry): LinearLayout = row(e, Ui.C.glossary) { bar ->
        bar.addView(Ui.smallButton(this, "Sửa", Ui.C.info) { edit(e) })
        bar.addView(Ui.smallButton(this, "Xoá", Ui.C.danger, Ui.Weight.Quiet) { confirmDelete(e) })
    }

    /**
     * Mot muc tu dien.
     *
     * Chu Nhat dung **mot dong rieng va to hon**, nghia tieng Viet o dong duoi.
     * Ban truoc nhet ca hai vao mot dong `A → B` cung co chu: mat phai doc het
     * ca dong moi biet dau la nguyen ban dau la nghia, va chu Nhat dai thi ca
     * dong tran xuong trong nhu mot khoi chu lien.
     */
    private fun row(
        e: GlossaryEntry,
        color: Int,
        buttons: (LinearLayout) -> Unit,
    ): LinearLayout = Ui.card(
        this, color,
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@GlossaryActivity).apply {
                text = e.surface
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Ui.chip(this@GlossaryActivity, kindLabel[e.kind].orEmpty(), color))
        },
        TextView(this).apply {
            // Lap lai y nguyen chu Nhat o dong nghia thi nhin nhu mot loi hien
            // thi. Noi thang ra la chua ai dat nghia thi dung hon.
            if (needsMeaning(e)) {
                text = "Chưa có nghĩa tiếng Việt"
                setTypeface(null, android.graphics.Typeface.ITALIC)
                alpha = 0.55f
            } else {
                text = e.meaning
                alpha = 0.85f
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, Ui.dp(this@GlossaryActivity, 4), 0, 0)
            setLineSpacing(Ui.dp(this@GlossaryActivity, 3).toFloat(), 1f)
        },
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@GlossaryActivity, 8), 0, 0)
            buttons(this)
        },
    )

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
            // Muc de xuat co `meaning` bang chinh chu Nhat — do khong phai nghia,
            // do la cho trong. Do san vao o thi nguoi dung phai xoa tay truoc khi
            // go, ma nhieu nguoi se tuong the la da xong roi bam Luu.
            setText(old?.meaning?.takeIf { it.trim() != old.surface.trim() } ?: "")
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

        // Bang chu quyet dinh ai lam gi: nut o day dat ngay DUOI o nghia, vi no
        // la thu DIEN vao o do. Dat cuoi form thi khong ai noi duoc no dien vao
        // dau.
        val askStatus = Ui.hint(this, "")
        val ask = Ui.smallButton(this, "✨  Hỏi Gemini", Ui.C.info) {
            askGemini(surface.text.toString().trim(), meaning, askStatus)
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 22), Ui.dp(context, 12), Ui.dp(context, 22), 0)
            addView(label("Nguyên bản tiếng Nhật"))
            addView(surface)
            addView(label("Nghĩa tiếng Việt"))
            addView(meaning)
            addView(LinearLayout(this@GlossaryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ask)
            })
            addView(askStatus)
            addView(label("Loại"))
            addView(kind)
        }

        AlertDialog.Builder(this)
            .setTitle(if (old == null) "Thêm mục" else "Sửa mục")
            .setView(ScrollView(this).apply { addView(form) })
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
                    forgetPagesWith(s)
                    refresh()
                }
            }
            .show()
        if (promote) toast("Sửa xong sẽ được dùng ngay khi dịch")
    }

    /**
     * Tra nghia cum chu Nhat dang go, dien thang vao o nghia.
     *
     * ⚠️ Day la lan goi ra ngoai internet DUY NHAT cua man hinh nay, va no chi
     * gui **dung cum chu trong o nguyen ban** — vai chu, do chinh nguoi dung go
     * hoac khoanh. Khong co khoa thi nut nay chi bao cho biet lay khoa o dau,
     * chu khong lam gi ca.
     *
     * Ket qua di vao o de **sua duoc**: mo hinh cua Google cung sai, va muc tu
     * dien sai thi lam hong moi trang dich ve sau (AD-4).
     */
    private fun askGemini(ja: String, into: EditText, status: TextView) {
        if (ja.isEmpty()) {
            status.text = "Điền ô nguyên bản tiếng Nhật trước đã."
            return
        }
        val key = GeminiLookup.key(this)
        if (key.isNullOrBlank()) {
            status.text = "Chưa có khoá Gemini. Vào Cài đặt → Tra nghĩa bằng Gemini."
            return
        }
        status.text = "Đang hỏi Gemini..."
        lifecycleScope.launch {
            GeminiLookup(this@GlossaryActivity, key).meaningOf(ja)
                .onSuccess {
                    into.setText(it)
                    status.text = "Gemini gợi ý — sửa lại nếu thấy chưa đúng rồi bấm Lưu."
                }
                .onFailure { status.text = "Không hỏi được: ${it.message}" }
        }
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.6f
        setPadding(0, Ui.dp(this@GlossaryActivity, 10), 0, 0)
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
