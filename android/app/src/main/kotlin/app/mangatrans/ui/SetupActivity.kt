package app.mangatrans.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mangatrans.Composition
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
    private lateinit var actionBtn: Button
    private lateinit var deleteBtn: Button

    private var manifest: PackageManifest? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Cài đặt lần đầu"
        store = ModelStore(this, Composition.appVersion(this))
        downloader = PackageDownloader(store)

        status = TextView(this).apply { textSize = 14f; setPadding(0, 16, 0, 8) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        detail = TextView(this).apply { textSize = 11f; alpha = 0.7f }
        actionBtn = Button(this).apply { text = "Kiểm tra" }
        deleteBtn = Button(this).apply {
            text = "Xoá gói mô hình"
            visibility = android.view.View.GONE
            setOnClickListener { confirmDelete() }
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@SetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 24, 40, 64)
                gravity = Gravity.FILL_HORIZONTAL
                addView(explainer())
                addView(status); addView(bar); addView(detail)
                addView(actionBtn); addView(deleteBtn)
            })
        })

        refresh()
    }

    /**
     * Story 4.1 — FR-054. Giai thich TUNG quyen bang ngon ngu thuong, truoc khi
     * bat ky hop thoai he thong nao hien ra.
     *
     * "Du lieu khong roi khoi may" la diem ban hang chinh (D1/D3), nen no nam o
     * dau, khong phai chu thich nho o cuoi.
     */
    private fun explainer() = TextView(this).apply {
        textSize = 14f
        @Suppress("DEPRECATION")
        setText(
            Html.fromHtml(
                """
                <h3>Mọi thứ chạy ngay trên máy bạn</h3>
                <p>App dịch manga Nhật → Việt <b>hoàn toàn trên điện thoại</b>. Ảnh màn hình,
                chữ đọc được và bản dịch <b>không bao giờ rời khỏi máy</b> — không có máy chủ,
                không có tài khoản, không gửi gì đi đâu.</p>
                <p>App chỉ dùng mạng <b>một lần duy nhất</b>: tải gói mô hình dịch về. Xong rồi
                thì tắt mạng vẫn dịch được.</p>

                <h3>Hai quyền app sẽ xin, và để làm gì</h3>
                <p><b>Hiển thị trên ứng dụng khác.</b> Để icon dịch nổi lên trên app đọc truyện.
                Không có quyền này thì bạn phải thoát ra vào app này mỗi lần muốn dịch.
                Android bắt bạn tự bật trong Cài đặt, không có hộp thoại xin nhanh.</p>
                <p><b>Chụp màn hình.</b> Để đọc trang truyện đang hiện. Ảnh chụp được xử lý
                trong máy rồi bỏ, không lưu lại, không gửi đi. Android sẽ hỏi lại quyền này
                mỗi lần bạn mở app — đó là cách Android bảo vệ bạn, không phải lỗi.</p>
                <hr/>
                """.trimIndent(),
                Html.FROM_HTML_MODE_COMPACT,
            )
        )
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
            actionBtn.text = "Thử lại"
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
        actionBtn.text = "Xong"
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
        actionBtn.text = "Kiểm tra lại"
        actionBtn.setOnClickListener { manifest = null; refresh() }
        actionBtn.isEnabled = true
    }

    /** Story 4.3 — tai lai PHAN HONG, khong phai ca goi. */
    private fun showCorrupt(s: PackageState.Corrupt, m: PackageManifest) {
        status.text = "${s.badFiles.size} file bị hỏng khi tải."
        detail.text = "Sẽ tải lại đúng những file đó, không tải lại cả gói:\n" +
            s.badFiles.joinToString("\n") { "  • $it" }
        actionBtn.text = "Tải lại phần hỏng"
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
        val need = missing.sumOf { it.sizeBytes - store.bytesOnDisk(it.name) }

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
        actionBtn.text = if (have > 0) "Tải tiếp" else "Tải về"
        actionBtn.setOnClickListener { warnIfWeakThenDownload(m) }
        actionBtn.isEnabled = true
    }

    /**
     * Doc duoc voi ca file 24 KB lan file 2.6 GB.
     *
     * Truoc do chia cung cho 1e6 nen `vocab.txt` hien la "0 MB" — dung ve so
     * hoc, vo nghia voi nguoi doc.
     */
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
                    "Gói tải về nặng %.1f GB. Bạn vẫn muốn tải chứ?".format(m.totalGigabytes)
            )
            .setNegativeButton("Thôi", null)
            .setPositiveButton("Vẫn tải") { _, _ -> startDownload(m) }
            .show()
    }

    // ---------- Story 4.2: tai ----------

    private fun startDownload(m: PackageManifest) {
        bar.visibility = android.view.View.VISIBLE
        actionBtn.text = "Tạm dừng"
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
                    actionBtn.text = "Tải tiếp"
                    actionBtn.setOnClickListener { startDownload(m) }
                } else {
                    say(e)
                    actionBtn.text = "Thử lại"
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
                "Cần khoảng %.1f GB trống. Xoá bớt rồi thử lại.".format(
                    (manifest?.totalGigabytes ?: 0.0)
                )
            is DownloadFailure.ChecksumMismatch -> "Bấm Thử lại để tải lại đúng file đó."
            else -> "Bấm Thử lại."
        }
    }

    // ---------- Story 4.5: xoa goi ----------

    private fun confirmDelete() {
        lifecycleScope.launch { askDelete() }
    }

    private suspend fun askDelete() {
        val bytes = store.sizeOnDisk()
        AlertDialog.Builder(this@SetupActivity)
            .setTitle("Xoá gói mô hình?")
            .setMessage(
                "Sẽ giải phóng %.1f GB.\n\n".format(bytes / 1e9) +
                    "Sau khi xoá, app không dịch được nữa cho tới khi tải lại.\n\n" +
                    "Từ điển riêng và các trang đã dịch KHÔNG bị xoá."
            )
            .setNegativeButton("Không", null)
            .setPositiveButton("Xoá") { _, _ ->
                lifecycleScope.launch {
                    val freed = store.deletePackage()
                    android.widget.Toast.makeText(
                        this@SetupActivity,
                        "Đã giải phóng %.1f GB".format(freed / 1e9),
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
