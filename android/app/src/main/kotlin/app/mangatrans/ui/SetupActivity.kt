package app.mangatrans.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mangatrans.Composition
import app.mangatrans.adapters.cloud.GeminiLookup
import app.mangatrans.adapters.assets.ModelStore
import app.mangatrans.adapters.assets.PackageDownloader
import app.mangatrans.ports.DownloadException
import app.mangatrans.ports.DownloadFailure
import app.mangatrans.ports.PackageManifest
import app.mangatrans.ports.PackageState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Epic 4 — man hinh cai dat lan dau. Story 4.1, 4.2, 4.4, 4.5.
 *
 * Thu tu co chu y: **giai thich truoc, xin sau**. Mot app la doi quyen chup man
 * hinh la thu nguoi ta go ngay, nen hop thoai he thong khong duoc la thu dau
 * tien nguoi dung nhin thay (Story 4.1).
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var store: ModelStore
    private lateinit var downloader: PackageDownloader

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var detail: TextView
    private lateinit var actionBtn: TextView
    private lateinit var deleteBtn: TextView

    /** Khoi Gemini — dung lai tu dau moi lan khoa doi trang thai. */
    private lateinit var geminiBox: LinearLayout

    private var manifest: PackageManifest? = null
    private var job: Job? = null

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Cài đặt"
        store = ModelStore(this, Composition.appVersion(this))
        downloader = PackageDownloader(store)

        status = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        detail = Ui.hint(this, "")
        actionBtn = Ui.button(this, "Kiểm tra") {}
        deleteBtn = Ui.button(this, "Xoá gói mô hình", Ui.C.danger, Ui.Weight.Quiet) {
            confirmDelete()
        }.apply { visibility = android.view.View.GONE }

        val redoBtn = Ui.button(
            this, "Dịch lại các trang đã dịch", Ui.C.neutral, Ui.Weight.Tonal,
        ) { confirmClearPageCache() }

        geminiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@SetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), dp(40))
                gravity = Gravity.FILL_HORIZONTAL

                addView(Ui.heading(this@SetupActivity, "Gói mô hình dịch"))
                addView(Ui.panel(this@SetupActivity, Ui.C.info, status, bar, detail))
                addView(actionBtn)
                // Khoang tho truoc nut xoa: no khong duoc nam sat nut duoc bam
                // nhieu nhat (Ui, quy tac 3).
                addView(Ui.gap(this@SetupActivity, 10))
                addView(deleteBtn)

                addView(Ui.heading(this@SetupActivity, "Bản dịch đã lưu"))
                addView(Ui.body(
                    this@SetupActivity,
                    "App nhớ kết quả của những trang đã dịch để mở lại là hiện ngay. " +
                        "Sửa từ điển riêng xong thì xoá phần nhớ này để các trang cũ " +
                        "được dịch lại theo nghĩa mới.",
                    13f,
                ))
                addView(redoBtn)

                addView(Ui.heading(this@SetupActivity, "Tra nghĩa bằng Gemini"))
                addView(geminiBox)

                addView(Ui.gap(this@SetupActivity, 8))
                addView(explainer())
            })
        })

        buildGemini()
        refresh()
    }

    // ---------- khoi Gemini ----------

    /**
     * Hai canh khac han nhau, nen ve hai kieu khac han nhau:
     *
     *  - **chua co khoa** -> huong dan tung buoc, vi nguoi dung chua biet lay o dau;
     *  - **da co khoa**   -> mot dong xac nhan ngan, va khoa **bi che di**.
     *
     * ⚠️ Truoc day o nay hien nguyen van khoa API tren man hinh, luc nao cung
     * hien. Do la thu khong nen nam san tren man hinh de ai cam may len cung
     * doc duoc — va no cung khong giup gi, vi nguoi dung chi can biet "da luu
     * chua" chu khong can doc lai tung ky tu.
     */
    private fun buildGemini() {
        geminiBox.removeAllViews()
        val saved = GeminiLookup.key(this)

        geminiBox.addView(Ui.body(
            this,
            Ui.html(
                "Dùng cho nút <b>Hỏi Gemini</b> khi bạn khoanh lấy một cụm chữ bằng " +
                    "icon ⌖. <b>Dịch trang vẫn chạy hoàn toàn trên máy</b> — chỉ đúng " +
                    "cụm chữ bạn khoanh mới được gửi đi, không bao giờ gửi cả trang " +
                    "hay ảnh màn hình."
            ),
            13f,
        ))

        if (!saved.isNullOrBlank()) {
            geminiBox.addView(Ui.panel(
                this, Ui.C.primary,
                TextView(this).apply {
                    text = "Đã lưu khoá  •  ${mask(saved)}"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                },
                Ui.hint(this, "Nút Hỏi Gemini đang bật. Khoá chỉ nằm trong máy bạn."),
            ))
            geminiBox.addView(Ui.buttonRow(
                this,
                Ui.smallButton(this, "Đổi khoá", Ui.C.info) { askForKey(replacing = true) },
                Ui.smallButton(this, "Xoá khoá", Ui.C.danger, Ui.Weight.Quiet) {
                    GeminiLookup.setKey(this, "")
                    toast("Đã xoá khoá — tắt tra cứu Gemini.")
                    buildGemini()
                },
            ))
            return
        }

        geminiBox.addView(Ui.panel(
            this, Ui.C.info,
            TextView(this).apply {
                text = "Lấy khoá miễn phí, 4 bước"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            },
            Ui.step(this, 1, Ui.html("Bấm <b>Mở trang lấy khoá</b> bên dưới."), Ui.C.info),
            Ui.step(this, 2, "Đăng nhập bằng tài khoản Google của bạn.", Ui.C.info),
            Ui.step(this, 3, Ui.html("Bấm <b>Create API key</b> — chọn project nào cũng được."), Ui.C.info),
            Ui.step(this, 4, Ui.html("Sao chép khoá, quay lại đây bấm <b>Dán khoá vào đây</b>."), Ui.C.info),
            Ui.gap(this, 4),
            Ui.hint(
                this,
                "Tài khoản Gemini để chat KHÔNG phải là khoá API — vẫn phải tạo khoá " +
                    "riêng ở bước trên. Không nhập khoá thì app vẫn chạy đủ, chỉ là " +
                    "không có nút Hỏi Gemini.",
            ),
        ))
        geminiBox.addView(Ui.button(this, "Mở trang lấy khoá", Ui.C.info, Ui.Weight.Tonal) {
            openKeyPage()
        })
        geminiBox.addView(Ui.button(this, "Dán khoá vào đây", Ui.C.primary) {
            askForKey(replacing = false)
        })
    }

    /** Chi giu 4 ky tu cuoi — du de doi chieu, khong du de dung lai. */
    private fun mask(key: String): String =
        if (key.length <= 4) "••••" else "••••" + key.takeLast(4)

    private fun askForKey(replacing: Boolean) {
        val input = android.widget.EditText(this).apply {
            hint = "Dán khoá vào đây"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        AlertDialog.Builder(this)
            .setTitle(if (replacing) "Đổi khoá Gemini" else "Lưu khoá Gemini")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(12), dp(22), 0)
                addView(input)
                addView(Ui.hint(this@SetupActivity, "Khoá chỉ được lưu trong máy bạn."))
            })
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isEmpty()) { toast("Chưa dán khoá nào."); return@setPositiveButton }
                GeminiLookup.setKey(this, k)
                toast("Đã lưu khoá.")
                buildGemini()
            }
            .show()
    }

    private fun openKeyPage() {
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(GeminiLookup.KEY_URL),
                )
            )
        }.onFailure {
            toast("Không mở được trình duyệt. Vào: ${GeminiLookup.KEY_URL}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /**
     * Story 4.1 — FR-054. Giai thich TUNG quyen bang ngon ngu thuong, truoc khi
     * bat ky hop thoai he thong nao hien ra.
     *
     * "Du lieu khong roi khoi may" la diem ban hang chinh (D1/D3), nen no nam o
     * dau, khong phai chu thich nho o cuoi.
     */
    private fun explainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(Ui.heading(this@SetupActivity, "Dữ liệu của bạn đi đâu"))
        addView(Ui.panel(
            this@SetupActivity, Ui.C.primary,
            TextView(this@SetupActivity).apply {
                text = "Không đi đâu cả"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            },
            Ui.body(
                this@SetupActivity,
                Ui.html(
                    "Ảnh màn hình, chữ đọc được và bản dịch <b>không bao giờ rời khỏi " +
                        "máy</b> — không máy chủ, không tài khoản. App chỉ dùng mạng " +
                        "<b>một lần</b> để tải gói mô hình; xong rồi tắt mạng vẫn dịch được."
                ),
                13f,
            ),
        ))

        addView(Ui.heading(this@SetupActivity, "Hai quyền app xin, và để làm gì"))
        addView(Ui.card(
            this@SetupActivity, Ui.C.neutral,
            TextView(this@SetupActivity).apply {
                text = "Hiển thị trên ứng dụng khác"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            },
            Ui.body(
                this@SetupActivity,
                "Để icon dịch nổi lên trên app đọc truyện. Không có quyền này thì mỗi " +
                    "lần muốn dịch bạn phải thoát ra vào app này. Android bắt tự bật " +
                    "trong Cài đặt, không có hộp thoại xin nhanh.",
                13f,
            ),
        ))
        addView(Ui.card(
            this@SetupActivity, Ui.C.neutral,
            TextView(this@SetupActivity).apply {
                text = "Chụp màn hình"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            },
            Ui.body(
                this@SetupActivity,
                "Để đọc trang truyện đang hiện. Ảnh chụp được xử lý trong máy rồi bỏ, " +
                    "không lưu lại, không gửi đi. Android hỏi lại quyền này mỗi lần bạn " +
                    "mở app — đó là cách Android bảo vệ bạn, không phải lỗi.",
                13f,
            ),
        ))
    }

    // ---------- trang thai ----------

    /**
     * Than KHOI, khong phai than bieu thuc: cac nut ben trong goi lai `refresh()`,
     * nen than bieu thuc lam kieu tra ve phu thuoc vao chinh no. Trinh bien dich
     * bao "Type checking has run into a recursive problem" — da mac o
     * `GlossaryActivity`, gio mac lai y het.
     */
    private fun refresh() {
        lifecycleScope.launch { reload() }
    }

    private suspend fun reload() {
        actionBtn.isEnabled = false
        status.text = "Đang kiểm tra gói mô hình..."
        detail.text = ""

        val m = manifest ?: runCatching { downloader.fetchManifest() }.getOrElse {
            status.text = "Không lấy được thông tin gói mô hình."
            detail.text = "Cần mạng để tải lần đầu. Kiểm tra kết nối rồi thử lại."
            Ui.restyle(this, actionBtn, "Thử lại", Ui.C.warn, Ui.Weight.Filled)
            actionBtn.setOnClickListener { manifest = null; refresh() }
            actionBtn.isEnabled = true
            return
        }
        manifest = m

        // Bam sha256 ca 2.7 GB rat cham. Da kiem dung goi nay roi thi tin.
        if (store.verifiedVersion() == m.packageVersion && store.looksComplete(m)) {
            showReady(m); return
        }

        when (val s = store.verify(m)) {
            is PackageState.Ready -> showReady(m)
            is PackageState.Incompatible -> showIncompatible(s)
            is PackageState.Corrupt -> showCorrupt(s, m)
            is PackageState.Partial -> showNeedsDownload(m, s.bytesHave)
            PackageState.Absent -> showNeedsDownload(m, 0)
        }
    }

    private fun showReady(m: PackageManifest) {
        status.text = "Gói mô hình đã sẵn sàng (phiên bản ${m.packageVersion})."
        detail.text = "Tắt mạng vẫn dịch được."
        bar.visibility = android.view.View.GONE
        Ui.restyle(this, actionBtn, "Xong", Ui.C.primary, Ui.Weight.Filled)
        actionBtn.setOnClickListener { finish() }
        actionBtn.isEnabled = true
        deleteBtn.visibility = android.view.View.VISIBLE
    }

    /** Story 4.3 / AD-15 — noi RO phai lam gi tiep, khong chi bao "khong tương thích". */
    private fun showIncompatible(s: PackageState.Incompatible) {
        status.text = "Gói mô hình không dùng được với bản app này."
        detail.text = "Gói ${s.manifest.packageVersion} cần app phiên bản " +
            "${s.manifest.minAppVersion}–${s.manifest.maxAppVersion}, " +
            "app này là ${s.appVersion}.\n\n" +
            "Cập nhật app lên bản mới nhất rồi mở lại màn hình này."
        bar.visibility = android.view.View.GONE
        Ui.restyle(this, actionBtn, "Kiểm tra lại", Ui.C.warn, Ui.Weight.Filled)
        actionBtn.setOnClickListener { manifest = null; refresh() }
        actionBtn.isEnabled = true
    }

    /** Story 4.3 — tai lai PHAN HONG, khong phai ca goi. */
    private fun showCorrupt(s: PackageState.Corrupt, m: PackageManifest) {
        status.text = "${s.badFiles.size} file bị hỏng khi tải."
        detail.text = "Sẽ tải lại đúng những file đó, không tải lại cả gói:\n" +
            s.badFiles.joinToString("\n") { "  • $it" }
        Ui.restyle(this, actionBtn, "Tải lại phần hỏng", Ui.C.warn, Ui.Weight.Filled)
        actionBtn.setOnClickListener {
            lifecycleScope.launch {
                s.badFiles.forEach { name -> store.find(name)?.delete() }
                startDownload(m)
            }
        }
        actionBtn.isEnabled = true
    }

    private fun showNeedsDownload(m: PackageManifest, have: Long) {
        // Chi liet ke va cong don thu THUC SU con thieu.
        //
        // Truoc do man hinh bao "can tai 2.8 GB" ngay ca khi chi thieu 11 MB, vi
        // no lay tong ca goi. Nhin tren may moi thay: bon file kia da co san
        // (day tay bang adb push), chi thieu detector.
        val missing = m.files.filter { store.find(it.name) == null }
        val need = remaining(m)

        status.text =
            if (have > 0) "Đang dở: còn ${human(need)} nữa."
            else "Cần tải ${human(need)}."

        detail.text = buildString {
            if (missing.size < m.files.size) {
                append("${m.files.size - missing.size}/${m.files.size} file đã có sẵn trên máy.\n")
            }
            append("Nên dùng Wi-Fi. Tải được tạm dừng và tiếp tục — ")
            append("rớt mạng thì lần sau tải tiếp chỗ dở, không làm lại từ đầu.\n")
            append(missing.joinToString("\n") { f ->
                val done = store.bytesOnDisk(f.name)
                if (done > 0) "  • ${f.name} — ${human(f.sizeBytes - done)} còn lại"
                else "  • ${f.name} — ${human(f.sizeBytes)}"
            })
        }
        bar.visibility = android.view.View.GONE
        Ui.restyle(this, actionBtn, if (have > 0) "Tải tiếp" else "Tải về", Ui.C.primary, Ui.Weight.Filled)
        actionBtn.setOnClickListener { warnIfWeakThenDownload(m) }
        actionBtn.isEnabled = true
    }

    /**
     * Doc duoc voi ca file 24 KB lan file 2.6 GB.
     *
     * Truoc do chia cung cho 1e6 nen `vocab.txt` hien la "0 MB" — dung ve so
     * hoc, vo nghia voi nguoi doc.
     */
    /** So byte THUC SU con phai tai — dung chung cho moi cho noi ve dung luong. */
    private fun remaining(m: PackageManifest): Long =
        m.files.filter { store.find(it.name) == null }
            .sumOf { it.sizeBytes - store.bytesOnDisk(it.name) }

    private fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%d MB".format(bytes / 1_000_000)
        bytes >= 1_000L -> "%d KB".format(bytes / 1_000)
        else -> "$bytes B"
    }

    // ---------- Story 4.4: canh bao may yeu ----------

    /**
     * FR-053 — do RAM that va canh bao TRUOC khi tai, kem con so cu the.
     *
     * Nguong tu so do that cua Epic 1: LLM chiem ~3.2 GB RSS khi chay. May 4 GB
     * se bi he dieu hanh giet giua chung. App **khong tu quyet thay** — chi noi
     * ro roi de nguoi dung chon.
     */
    private fun warnIfWeakThenDownload(m: PackageManifest) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = info.totalMem / 1e9

        if (ramGb >= MIN_RAM_GB) { startDownload(m); return }

        AlertDialog.Builder(this)
            .setTitle("Máy có thể không chạy nổi")
            .setMessage(
                "Máy này có %.1f GB RAM. Đo trên máy thật, mô hình dịch chiếm khoảng ".format(ramGb) +
                    "3,2 GB khi đang chạy, nên máy dưới %.0f GB thường bị Android tắt app giữa chừng.\n\n"
                        .format(MIN_RAM_GB) +
                    // Noi so THUC SU con phai tai, khong phai tong ca goi —
                    // cung loi da sua o `showNeedsDownload`, lap lai o day.
                    "Còn phải tải ${human(remaining(m))}. Bạn vẫn muốn tải chứ?"
            )
            .setNegativeButton("Thôi", null)
            .setPositiveButton("Vẫn tải") { _, _ -> startDownload(m) }
            .show()
    }

    // ---------- Story 4.2: tai ----------

    private fun startDownload(m: PackageManifest) {
        bar.visibility = android.view.View.VISIBLE
        Ui.restyle(this, actionBtn, "Tạm dừng", Ui.C.neutral, Ui.Weight.Tonal)
        actionBtn.setOnClickListener { job?.cancel() }
        deleteBtn.visibility = android.view.View.GONE

        job = lifecycleScope.launch {
            runCatching {
                downloader.download(m).collect { p ->
                    val doneBytes = m.files.take(p.fileIndex).sumOf { it.sizeBytes } + p.bytesHave
                    bar.progress = (doneBytes * 100 / m.totalBytes).toInt()
                    status.text = "Đang tải ${human(doneBytes)} / ${human(m.totalBytes)}"
                    detail.text = "${p.fileName} — ${p.percent}%  " +
                        "(file ${p.fileIndex + 1}/${p.fileCount})"
                }
            }.onSuccess {
                refresh()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) {
                    status.text = "Đã tạm dừng. Phần đã tải được giữ lại."
                    detail.text = "Bấm Tải tiếp để chạy tiếp từ chỗ dở."
                    Ui.restyle(this@SetupActivity, actionBtn, "Tải tiếp", Ui.C.primary, Ui.Weight.Filled)
                    actionBtn.setOnClickListener { startDownload(m) }
                } else {
                    say(e)
                    Ui.restyle(this@SetupActivity, actionBtn, "Thử lại", Ui.C.warn, Ui.Weight.Filled)
                    actionBtn.setOnClickListener { startDownload(m) }
                }
            }
        }
    }

    /** FR-013 kieu noi bang tieng nguoi — `when` tren `sealed` nen khong sot nhanh. */
    private fun say(e: Throwable) {
        val f = (e as? DownloadException)?.failure
        status.text = when (f) {
            DownloadFailure.NoNetwork ->
                "Mất mạng giữa chừng. Phần đã tải được giữ lại."
            DownloadFailure.NotEnoughSpace ->
                "Máy không đủ dung lượng trống cho gói này."
            DownloadFailure.ResumeNotSupported ->
                "Máy chủ không cho tải tiếp chỗ dở."
            is DownloadFailure.ChecksumMismatch ->
                "File ${f.fileName} tải về bị hỏng."
            is DownloadFailure.ServerError ->
                "Máy chủ trả lỗi ${f.code}."
            DownloadFailure.Cancelled, null ->
                "Tải không xong: ${e.message ?: e.javaClass.simpleName}"
        }
        detail.text = when (f) {
            DownloadFailure.NoNetwork -> "Nối mạng lại rồi bấm Thử lại — nó tiếp từ chỗ dở."
            DownloadFailure.NotEnoughSpace ->
                "Cần khoảng ${human(manifest?.let { remaining(it) } ?: 0L)} trống. Xoá bớt rồi thử lại."
            is DownloadFailure.ChecksumMismatch -> "Bấm Thử lại để tải lại đúng file đó."
            else -> "Bấm Thử lại."
        }
    }

    // ---------- Story 4.5: xoa goi ----------

    /**
     * Xoa bo nho dem trang da dich.
     *
     * Vi sao can nut nay: khoa cache tinh tu ANH va vi tri bong thoai, khong
     * tinh tu tu dien. Nen sua tu dien xong thi trang **da dich roi** van tra
     * lai ban cu mai mai — nguoi dung sua ma khong thay gi doi, va khong co
     * cach nao ep dich lai.
     *
     * Chi xoa ket qua dich. Goi mo hinh va tu dien rieng deu giu nguyen.
     */
    private fun confirmClearPageCache() {
        val dir = java.io.File(cacheDir, "pages")
        val files = dir.listFiles().orEmpty()
        val n = files.size
        // Do tren may: mot trang ~2,9 KB (chi luu CHU, khong luu anh). Tran 64 MB
        // tuong duong hon 22.000 trang, nen thuc te khong bao gio cham tran —
        // nhung nguoi dung khong biet dieu do neu app khong noi ra.
        val kb = files.sumOf { it.length() } / 1024.0
        val size = if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024)
        if (n == 0) {
            Toast.makeText(this, "Chưa có trang nào được lưu.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Dịch lại các trang đã dịch?")
            .setMessage(
                "App đang nhớ kết quả của $n trang ($size — chỉ là chữ, không " +
                    "lưu ảnh; đầy 64 MB thì tự dọn trang cũ nhất). Xoá đi thì lần sau mở lại " +
                    "những trang đó, app sẽ dịch mới — và áp dụng từ điển riêng " +
                    "bạn vừa sửa.\n\nMỗi trang dịch mới mất khoảng 1–2 phút.\n\n" +
                    "Gói mô hình và từ điển riêng KHÔNG bị xoá."
            )
            .setNegativeButton("Để sau", null)
            .setPositiveButton("Xoá") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    Toast.makeText(
                        this@SetupActivity,
                        "Đã xoá $n trang. Mở lại trang nào thì trang đó dịch mới.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .show()
    }

    private fun confirmDelete() {
        lifecycleScope.launch { askDelete() }
    }

    private suspend fun askDelete() {
        val bytes = store.sizeOnDisk()
        AlertDialog.Builder(this@SetupActivity)
            .setTitle("Xoá gói mô hình?")
            .setMessage(
                "Sẽ giải phóng ${human(bytes)}.\n\n" +
                    "Sau khi xoá, app không dịch được nữa cho tới khi tải lại.\n\n" +
                    "Từ điển riêng và các trang đã dịch KHÔNG bị xoá."
            )
            .setNegativeButton("Không", null)
            .setPositiveButton("Xoá") { _, _ ->
                lifecycleScope.launch {
                    val freed = store.deletePackage()
                    android.widget.Toast.makeText(
                        this@SetupActivity,
                        "Đã giải phóng ${human(freed)}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    deleteBtn.visibility = android.view.View.GONE
                    refresh()
                }
            }
            .show()
    }

    private companion object {
        /** Do that o Epic 1: LLM chiem ~3.2 GB RSS. May 4 GB bi giet giua chung. */
        const val MIN_RAM_GB = 6.0
    }
}
