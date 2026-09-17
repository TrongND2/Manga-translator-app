package app.mangatrans.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import app.mangatrans.Composition
import app.mangatrans.adapters.capture.MediaProjectionSource
import app.mangatrans.adapters.overlay.EditBubbleOverlay
import app.mangatrans.adapters.overlay.FloatingIcon
import app.mangatrans.adapters.overlay.GrabResultOverlay
import app.mangatrans.adapters.overlay.OverlayController
import app.mangatrans.adapters.storage.PageHash
import app.mangatrans.domain.ErrorKind
import app.mangatrans.domain.PageEvent
import app.mangatrans.domain.Stage
import app.mangatrans.pipeline.Pipeline
import app.mangatrans.ports.CaptureException
import app.mangatrans.ports.CaptureFailure
import app.mangatrans.ui.GuideActivity
import app.mangatrans.ui.ProjectionRequestActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Epic 3 — dich man hinh dang doc.
 *
 * AD-21: service la **chu so huu duy nhat** cua `MediaProjection`, va chi co
 * MOT `VirtualDisplay`. Khong noi nao khac duoc tao them.
 *
 * Vi sao phai la foreground service chu khong phai Activity: phien chup phai
 * song trong khi nguoi dung dang o app KHAC (FR-016). Activity bi dung lai
 * ngay khi mat foreground.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureSvc"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1

        const val ACTION_START = "app.mangatrans.START"
        const val ACTION_STOP = "app.mangatrans.STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** Cho lop phu ve xong roi moi lay moc so sanh. */
        private const val SETTLE_MS = 700L

        /** Nhip hoi "co frame moi khong". Du nhanh de bat cu vuot sang trang. */
        private const val POLL_MS = 350L

        /**
         * Khac bao nhieu thi coi la "nguoi dung sang trang".
         *
         * Don vi la do lech chuan cua chinh khung hinh (xem
         * `PageHash.frameSignature`), nen khong phu thuoc do sang hay bo truyen.
         *
         * **Do that, khong doan** (F43):
         * ```
         *   giam sang con 80%            0.0052
         *   giam sang con 60%            0.0030
         *   giam sang con 40%            0.0132
         *   giam sang con 25%            0.0202   <- truong hop "giong" te nhat
         *   ---------------------------------------------------- nguong 0.05
         *   cung trang, da ve ban dich   0.0947
         *   lat sang trang khac (tren may) 1.00 - 1.06
         * ```
         *
         * 0.05 cach cai te nhat 2,5 lan va cach cu lat trang 20 lan. Ha xuong
         * thi ban dich bi xoa oan; nang len thi ban dich trang cu nam de len
         * trang moi — AD-12 goi do la loi nang nhat cua tang hien thi, nen khi
         * phai chon thi chon phia xoa oan.
         */
        private const val PAGE_CHANGE_THRESHOLD = 0.05f

        /**
         * Man hinh phai YEN bao lau thi moc so sanh moi duoc chot.
         *
         * Khong co cho nay thi moc co the bi lay nham mot frame giua chung —
         * app doc truyen con dang dung hinh sau cu vuot, hoac lop phu cua chinh
         * ta con dang hien ra. Frame yen sau do khac moc, va ban dich bi xoa
         * oan ngay vai giay sau khi ve xong. Da thay that: `noi dung ben duoi
         * doi (khac 0.43)` dung 5,5 giay sau `xong: ve 6 bubble` (F43).
         */
        /** Nhip hoi cua bo canh trang. Cang nho cang dung nhanh. */
        private const val WATCH_POLL_MS = 150L

        /**
         * Phai vuot nguong BAO NHIEU nhip LIEN TIEP moi ket luan la doi trang.
         *
         * Lat trang la chuyen keo dai — trang moi nam do cho den khi nguoi dung
         * lat tiep. Con mot nhip le vuot nguong thi hau het la nhieu: dung luc
         * app ve mot bong thoai, mot frame bat duoc giua chung, hay mot cai
         * toast luot qua.
         *
         * Gia phai tra la 150 ms cham hon — khong dang ke so voi 4 giay cua ban
         * truoc F61, va doi lai la khong vut ca luot dich vi mot nhip nhieu.
         */
        private const val PAGE_CHANGE_CONFIRM = 2

        /**
         * Icon noi dang bat hay khong — man hinh chinh dung de doi mot nut duy
         * nhat giua Bat va Tat.
         *
         * Dat o day chu khong hoi `ActivityManager.getRunningServices()`: ham do
         * da bi khai tu va tu Android 8 chi tra ve service cua CHINH app goi —
         * dung duoc nhung vong vo hon mot bien.
         */
        @Volatile
        var isRunning = false
            private set

        fun stopIntent(ctx: Context) = Intent(ctx, CaptureService::class.java)
            .setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var overlays: OverlayController? = null
    private var projection: MediaProjection? = null
    private var source: MediaProjectionSource? = null
    private var engines: Composition.Engines? = null
    private var pipeline: Pipeline? = null

    /** Mot luot dich dang chay. Cham lan nua khi dang chay thi bo qua, khong xep hang. */
    private var running: Job? = null

    /**
     * Trang dang hien tren man hinh, GIU LAI sau khi dich xong.
     *
     * Can no de sua duoc mot bong thoai: phai biet `contentKey` va danh sach
     * box thi moi ghi de dung muc cache cua trang nay. Giu ca khi trang lay tu
     * cache — `Done` phat o ca hai duong.
     */
    @Volatile private var currentPage: app.mangatrans.domain.PageJob? = null


    /** Story 3.7 — canh noi dung ben duoi doi de go lop phu. */
    private var watching: Job? = null

    /** Panel sua bong thoai — tao mot lan roi dung lai. */
    private var editor: EditBubbleOverlay? = null

    /** Panel hien chu vua khoanh duoc. */
    private var grabPanel: GrabResultOverlay? = null

    /**
     * Anh chup luc khoanh chu, giu lai de lam NEN neu nguoi dung bam "De len
     * trang" ma chua co trang nao dang hien. `null` = da giao cho lop phu.
     */
    @Volatile private var grabShot: Bitmap? = null

    /**
     * Id cho lop de THU CONG. Dem lui tu -1 nen khong bao gio dung id cua
     * bubble do day chuyen sinh ra (luon >= 0) — va dau am chinh la cach phan
     * biet hai loai o cho khac.
     */
    private var nextManualId = -1

    /**
     * Nguoi dung DA dong y cho chup chua.
     *
     * Tach rieng khoi `projection != null` vi thu tu bat buoc tren Android 14+ la:
     *   co quyen  ->  NANG LOAI foreground service  ->  roi moi lay projection
     * Luc nang loai thi `projection` con null, nen khong the dung no lam co.
     */
    private var hasProjectionConsent = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundProperly()
        overlays = OverlayController(
            this,
            onTap = { onIconTapped() },
            onGuide = { openGuide() },
            onGrab = { startGrabText() },
            onClose = { closeEverything() },
            onEditBubble = { id -> openBubbleEditor(id) },
        ).also { it.show() }

        loadEngines()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { closeEverything(); return START_NOT_STICKY }
            ACTION_START -> adoptProjection(intent)
        }
        // KHONG START_STICKY: he dieu hanh khoi dong lai service ma khong co
        // `resultData` thi no song ma khong chup duoc gi — te hon la tat han.
        return START_NOT_STICKY
    }

    // ---------- phien chup ----------

    /** Nhan ket qua `MediaProjectionManager` tu `ProjectionRequestActivity`. */
    private fun adoptProjection(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return

        releaseProjection()

        // ⚠️ THU TU BAT BUOC tren Android 14+, va no la vong tron neu lam sai:
        //
        //   `getMediaProjection()` doi service DA o loai `mediaProjection`
        //   nhung khoi dong loai do lai doi DA CO quyen chup
        //
        // Loi thoat: quyen chup duoc cap ngay khi nguoi dung bam dong y (appop
        // `android:project_media`), TRUOC khi ta goi `getMediaProjection`. Nen
        // dung thu tu la:  co quyen -> nang loai -> roi moi lay projection.
        //
        // Lam nguoc thi: "Media projections require a foreground service of type
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION" (F34).
        hasProjectionConsent = true
        startForegroundProperly()

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(code, data) ?: run {
            hasProjectionConsent = false
            say(CaptureFailure.NoPermission)
            return
        }
        projection = p

        val size = realScreenSize()
        android.util.Log.i(TAG, "man hinh ${size.width()}x${size.height()}, status bar ${statusBarHeight()}px")
        source = MediaProjectionSource(
            projection = p,
            overlays = overlays!!,
            widthPx = size.width(),
            heightPx = size.height(),
            densityDpi = resources.displayMetrics.densityDpi,
            statusBarPx = statusBarHeight(),
        )
        setIconState(if (engines == null) FloatingIcon.State.Preparing else FloatingIcon.State.Ready)
    }

    private fun releaseProjection() {
        source?.release(); source = null
        projection = null       // `release()` da goi `stop()`
        hasProjectionConsent = false
    }

    // ---------- nap engine ----------

    private fun loadEngines() = scope.launch {
        Composition.seedGlossaryIfEmpty(this@CaptureService) { Log.i(TAG, it) }
        runCatching { Composition.engines(this@CaptureService) { Log.i(TAG, it) } }
            .onSuccess { e ->
                engines = e
                pipeline = e.pipeline
                // ⚠️ KHONG ham nong LLM o day nua (AD-20 cu).
                //
                // Ham nong som nghe hop ly, nhung do tren M52 thi no mua rat it
                // ma tra rat dat:
                //   - mua: cham dau tien van cho 62 giay moi ra bong dau tien,
                //     ham nong hay khong cung vay — vi phan lau la LLM doc het
                //     trang truoc khi sinh chu, khong phai nap mo hinh;
                //   - tra: 2 GB nam trong RAM tu luc bat icon, ke ca khi nguoi
                //     dung chua dich gi. Cong voi phan doc chu la Android giet
                //     app — da xay ra hai lan lien, deu ngay dau buoc doc chu.
                //
                // Gio LLM chi song trong luc dich (xem `Pipeline.onVisionStart`).
                setIconState(
                    if (source == null) FloatingIcon.State.NeedPermission
                    else FloatingIcon.State.Ready
                )
            }
            .onFailure { err ->
                Log.e(TAG, "khong nap duoc engine: ${err.javaClass.simpleName}")
                setIconState(FloatingIcon.State.Failed)
                val msg = when (err) {
                    is Composition.MissingModels ->
                        "Thiếu ${err.files.size} file mô hình trong ${Composition.TMP}"
                    else -> "Không nạp được mô hình dịch"
                }
                toast(msg)
            }
    }

    // ---------- mot cham la dich (Story 3.4) ----------

    private fun onIconTapped() {
        // Chua co phien chup -> xin quyen. Day cung la duong quay lai sau khi
        // nguoi dung khoa man hinh (AD-21), nen no phai muot chu khong phai loi.
        val src = source
        if (src == null || !src.isAlive) {
            requestProjection()
            return
        }
        val p = pipeline ?: run { toast("Đang chuẩn bị, đợi một chút"); return }

        // Cham lan nua trong khi dang dich = DUNG LAI.
        //
        // Truoc day cham luc nay bi bo qua im lang, nen nguoi dung ngoi cho het
        // hai phut du da doi y. Khong co API dung rieng trong LiteRT-LM (da soi
        // `javap` tren AAR: `Conversation` chi co `close`), nhung huy coroutine
        // thi dong tra ve dung ngay va `use` dong luon phien hoi thoai.
        //
        // Phan sinh chu ben trong thu vien co the con chay them mot luc roi moi
        // dung han — nguoi dung khong thay, nhung may van am them vai giay.
        if (running?.isActive == true) {
            running?.cancel()
            scope.launch {
                overlays?.clearPage()
                setIconState(if (src.isAlive) FloatingIcon.State.Ready else FloatingIcon.State.NeedPermission)
            }
            toast("Đã dừng dịch trang này.")
            return
        }

        watching?.cancel()
        running = scope.launch { translateOnce(src, p) }
    }

    private suspend fun translateOnce(src: MediaProjectionSource, p: Pipeline) {
        val ov = overlays ?: return
        setIconState(FloatingIcon.State.Capturing)

        val shot = runCatching { src.capture() }.getOrElse { err ->
            val failure = (err as? CaptureException)?.failure ?: CaptureFailure.Timeout
            say(failure)
            setIconState(
                if (failure == CaptureFailure.SessionRevoked) FloatingIcon.State.NeedPermission
                else FloatingIcon.State.Failed
            )
            return
        }

        val bitmap = shot.handle as Bitmap
        dumpCaptureForDiagnosis(bitmap)
        val tf = engines?.typeface ?: android.graphics.Typeface.SANS_SERIF
        // AD-12/AD-18 — lop phu mang `frameHash` cua anh sinh ra no, KHONG phai
        // `contentKey`. Anh da bi cat status bar roi nen cropTop = 0.
        val frameHash = PageHash.frameHash(bitmap, 0)

        setIconState(FloatingIcon.State.Reading)
        var drawn = 0
        // Vung app TU VE len — bo canh trang phai bo qua, neu khong chinh ban
        // dich vua ve lai bi coi la "nguoi dung sang trang".
        val drawnRects = java.util.concurrent.CopyOnWriteArrayList<app.mangatrans.domain.Box>()
        // Tim thay bao nhieu bong thoai — de phan biet "khong thay bong nao"
        // voi "co bong nhung dich hong". Hai cai do doi hai cau bao khac han.
        var found = 0
        runCatching {
            p.run(bitmap).collect { ev ->
                when (ev) {
                    is PageEvent.Progress -> {
                        // CHI ghi so dem va ma trang thai. KHONG bao gio ghi noi
                        // dung anh hay chu da OCR — do la man hinh rieng cua
                        // nguoi dung.
                        Log.i(TAG, "${ev.stage} ${ev.done}/${ev.total}")
                        if (ev.total > found) found = ev.total
                        setIconState(
                        when (ev.stage) {
                            Stage.Capturing, Stage.Detecting -> FloatingIcon.State.Capturing
                            Stage.Reading -> FloatingIcon.State.Reading
                            Stage.Translating, Stage.Drawing -> FloatingIcon.State.Translating
                        }
                        )
                    }

                    is PageEvent.BubbleReady -> {
                        val first = drawn == 0
                        // Lan dau co bubble moi dung anh chup lam nen — truoc do
                        // chua biet co ve duoc gi khong, ma AD-9 cam to nen som.
                        if (first) ov.beginPage(bitmap, frameHash, statusBarHeight(), tf)
                        drawn++
                        drawnRects += app.mangatrans.pipeline.BubbleRenderer.drawnRect(ev.bubble)
                        ov.addBubble(ev.bubble)
                        // Bat bo canh NGAY tu bong dau tien, khong doi ca trang
                        // xong. Nguoi dung lat trang / chuyen app giua chung thi
                        // phai dung ngay, neu khong ban dich cu nam de len man
                        // hinh moi (F61).
                        //
                        // ⚠️ Nhung bat SAU `addBubble`, khong phai truoc. Bat
                        // truoc thi nhip hoi dau tien roi dung luc cua so lop
                        // phu **dang duoc them vao** — bat duoc mot khung dang
                        // do. Do duoc dung mot nhip nhu the: d=0.1614 (gap 60
                        // lan sang nhieu thuong), nhip ke tiep 0.0024. Bat sau
                        // `addBubble` thi co `selfChanging` dang gio, nhip do
                        // bi bo qua han.
                        if (first) watchForPageChange(src, bitmap, drawnRects)
                    }

                    // AD-17 — go bubble da ve, chu Nhat goc hien lai nguyen ven.
                    is PageEvent.Retracted -> {
                        // Tru lai, neu khong thi lan thu hai dem don va
                        // "xong: ve N bubble" noi sai han so bong tren man.
                        drawn -= ev.bubbleIds.size
                        if (drawn < 0) drawn = 0
                        ov.retract(ev.bubbleIds)
                    }

                    // AD-9 — ca trang tro ve nguyen ban.
                    is PageEvent.PageRejected -> { ov.clearPage(); drawn = 0 }

                    is PageEvent.Done -> { currentPage = ev.job; dumpForDiagnosis(ev.job) }

                    // Hong ca luot — khac han ket qua tung bubble. Noi ro ly do
                    // bang tieng nguoi (Story 3.8), khong hien ma loi.
                    is PageEvent.Failed -> {
                        Log.i(TAG, "luot hong: ${ev.error.kind}")
                        when (ev.error.kind) {
                            ErrorKind.ScreenCaptureBlocked -> say(CaptureFailure.ScreenProtected)
                            ErrorKind.ScreenCaptureRevoked -> say(CaptureFailure.SessionRevoked)
                            ErrorKind.ModelNotReady, ErrorKind.ModelLoadFailed ->
                                toast("Mô hình dịch chưa sẵn sàng")
                            ErrorKind.OcrFailed -> toast("Không đọc được chữ trên trang này")
                            ErrorKind.TranslateFailed -> toast("Không dịch được trang này")
                            ErrorKind.Unknown -> toast("Có lỗi khi dịch trang này")
                        }
                    }
                }
            }
        }.onFailure { Log.e(TAG, "luot dich hong: ${it.javaClass.simpleName}") }

        // ⚠️ `runCatching` nuot ca `CancellationException`, nen khi nguoi dung
        // cham de DUNG thi than ham van chay tiep xuong day: bao "dich khong
        // tron trang", ve lai lop phu, bat lai bo canh trang. Nguoi dung nhan
        // hai thong bao nguoc nhau cho mot cu cham.
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) return

        Log.i(TAG, "xong: ve $drawn bubble")
        // Cua so ban dich duoc them SAU icon nen nam tren no. Bong thoai nao
        // gan mep la de len icon va nuot cu cham — dua icon len lai (F44).
        if (drawn > 0) ov.raiseIcon()
        // Bo canh da chay tu bong dau tien roi; o day chi con don khi khong ve
        // duoc gi.
        if (drawn == 0) {
            watching?.cancel()
            ov.clearPage()
        }
        setIconState(if (src.isAlive) FloatingIcon.State.Ready else FloatingIcon.State.NeedPermission)
        if (drawn == 0) toast(
            if (found == 0) "Không tìm thấy bóng thoại nào trên màn hình"
            else "Dịch không trọn trang này nên giữ nguyên bản gốc. Chạm icon để thử lại."
        )
    }

    /**
     * Ghi cap "chu Nhat doc duoc -> ban dich" ra FILE RIENG cua app, de tach
     * duoc hai nguyen nhan lam ban dich sai: **doc sai (OCR)** va **dich sai
     * (LLM)**. Sua ben nay ma that ra hong ben kia thi cong coc.
     *
     * ⚠️ Day la noi dung MAN HINH RIENG cua nguoi dung, nen:
     *   - KHONG BAO GIO ghi ra logcat — do la log dung chung;
     *   - chi ghi khi co co `/data/local/tmp/mangatrans-diag`, ma file do chi
     *     tao duoc bang `adb`. Nguoi dung binh thuong khong bat nham duoc.
     *
     *   adb shell touch /data/local/tmp/mangatrans-diag      # bat
     *   adb shell rm    /data/local/tmp/mangatrans-diag      # tat
     */
    /**
     * Luu ANH MA OCR THUC SU NHIN THAY. Cung co `/data/local/tmp/mangatrans-diag`
     * nhu `dumpForDiagnosis` — xem ghi chu o do ve rieng tu.
     *
     * Can cai nay vi co mot nghi van khong the tra loi bang log: lan chup THU HAI
     * tren cung mot trang co bi dinh chinh ban dich dang hien khong. Mat thuong
     * khong thay duoc, log cung khong — chi co dung tam anh do moi noi duoc.
     */
    private fun dumpCaptureForDiagnosis(bmp: Bitmap) {
        if (!java.io.File("/data/local/tmp/mangatrans-diag").exists()) return
        val copy = runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(filesDir, "diag").apply { mkdirs() }
                java.io.File(dir, "shot-${System.currentTimeMillis()}.png").outputStream().use {
                    copy.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                copy.recycle()
            }
        }
    }

    /**
     * Co chan duong chan doan — chi `adb` tao duoc file nay, nguoi dung binh
     * thuong khong bat nham. Doc mot lan moi lan hoi chu khong cache: bat/tat
     * giua chung phai an ngay.
     */
    private val diagOn: Boolean
        get() = java.io.File("/data/local/tmp/mangatrans-diag").exists()

    /**
     * Luu khung hinh lam bo canh trang bao dong, de nhin tan mat cai gi doi.
     *
     * ⚠️ Day la noi dung man hinh rieng — chi ghi vao kho rieng cua app va chi
     * khi co `/data/local/tmp/mangatrans-diag` (chi adb tao duoc).
     */
    private fun dumpAlarmFrame(src: MediaProjectionSource, d: Float) {
        if (!diagOn) return
        val bmp = src.peekBitmap() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(filesDir, "diag").apply { mkdirs() }
                val f = java.io.File(dir, "alarm-%.3f-%d.png".format(d, System.currentTimeMillis()))
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "da luu khung hinh gay bao dong")
            }
            runCatching { bmp.recycle() }
        }
    }

    private fun dumpForDiagnosis(job: app.mangatrans.domain.PageJob) {
        if (!java.io.File("/data/local/tmp/mangatrans-diag").exists()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(filesDir, "diag").apply { mkdirs() }
                java.io.File(dir, "page-${System.currentTimeMillis()}.txt").writeText(
                    buildString {
                        appendLine("contentKey=${job.contentKey}")
                        appendLine("vung=${job.bubbles.size} dua sang dich=${job.translatable.size}")
                        appendLine()
                        job.bubbles.forEach { b ->
                            appendLine(
                                "[${b.id}] ${b.kind} diem=${"%.2f".format(b.detectScore)} " +
                                    "${b.box.x1},${b.box.y1} ${b.box.width}x${b.box.height} " +
                                    "vo=${if (b.shell != null) "co" else "khong"}"
                            )
                            appendLine("  JA: ${b.ja}")
                            appendLine("  VI: ${b.vi ?: "(giu nguyen)"}")
                            appendLine("  speaker: ${b.speaker ?: "-"}")
                        }
                    }
                )
                Log.i(TAG, "da ghi ho so chan doan")
            }
        }
    }

    /**
     * Story 3.7 / AD-12 — noi dung ben duoi doi thi go lop phu NGAY, khong cho
     * luot dich moi.
     *
     * **Tha mat ban dich con hon hien ban dich sai cho.** Ban dich trang truoc
     * nam de len trang sau la loi nang nhat cua tang hien thi: nguoi doc thay
     * chu Viet troi chay ma noi dung khong lien quan gi den tranh, va khong co
     * dau hieu nao bao do la cua trang khac.
     *
     * Cach biet: `VirtualDisplay` von da dang chay, va no CHI sinh frame khi man
     * hinh co thay doi. Nen chi can hoi no co frame moi khong. Luc dung yen thi
     * phep hoi nay khong ton gi.
     *
     * Moc so sanh lay SAU khi da ve xong lop phu — lop phu dung yen nen khong
     * lam hash doi; chi noi dung ben duoi doi moi lam doi.
     */
    private fun watchForPageChange(
        src: MediaProjectionSource,
        page: android.graphics.Bitmap,
        mask: List<app.mangatrans.domain.Box>,
    ) {
        watching?.cancel()
        watching = scope.launch {
            // ⚠️ Moc so sanh la **ANH DA CHUP**, khong phai mot frame bat duoc
            // luc chay. Anh do khong bao gio doi, nen khong can cho man hinh
            // yen, khong can "len nong", khong can lay moc lai.
            //
            // Ban truoc phai cho vi no so CA man hinh, ma chinh app dang ve ban
            // dich len do — moi bong vua ve deu trong nhu "nguoi dung sang
            // trang". Cai gia la **hon 4 giay** moi phat hien nguoi dung lat
            // trang, va chuyen app thi co khi khong bat duoc (F61).
            //
            // Gio bo qua dung vung bong thoai va vung icon — hai cho duy nhat
            // app dong toi. Phan con lai la tranh: doi la nguoi dung that su
            // doi man hinh, va bat ngay o nhip hoi dau tien.
            var known = -1
            var baseline: FloatArray? = null
            /** So nhip LIEN TIEP da vuot nguong. */
            var hits = 0
            while (isActive && src.isAlive) {
                val ov = overlays ?: return@launch
                // Dang tu an lop phu de chup — bo qua nhip nay.
                if (ov.selfChanging.get()) { delay(WATCH_POLL_MS); continue }

                val boxes = mask + ov.iconBox().let {
                    val dy = statusBarHeight()
                    app.mangatrans.domain.Box(it.x1, it.y1 - dy, it.x2, it.y2 - dy)
                }
                if (boxes.size != known) {
                    known = boxes.size
                    baseline = PageHash.frameSignature(page, 0, boxes)
                    // Moc vua doi thi chuoi dang dem khong con y nghia.
                    hits = 0
                }

                val now = src.peekFrameSignature(boxes)
                val base = baseline
                // `null` = vung bo qua da nuot gan het man hinh, hoac chua bat
                // duoc frame nao. KHONG ket luan gi ca — dung im con hon doan,
                // vi doan sai o day la vut ca luot dich sap xong.
                if (now != null && base != null) {
                    val d = PageHash.distance(base, now)
                    // Duong DO nguong, khong phai log thuong. Chi so do — khong
                    // co noi dung man hinh — va chi chay khi co co adb, vi no
                    // ghi 7 dong moi giay.
                    if (diagOn) Log.i(
                        TAG,
                        "canh trang: d=%.4f live=%d bo-qua=%d".format(
                            d, now.count { !it.isNaN() }, boxes.size,
                        ),
                    )
                    if (d > PAGE_CHANGE_THRESHOLD) hits++ else hits = 0
                    if (hits >= PAGE_CHANGE_CONFIRM) {
                        Log.i(TAG, "man hinh doi (khac %.2f) — dung dich, go lop phu".format(d))
                        // Luu lai DUNG khung hinh da gay bao dong. Con so `d`
                        // noi duoc "khac bao nhieu" nhung khong noi duoc "khac
                        // o dau" — ma bao dong gia thi cau hoi luon la cai gi
                        // vua doi tren man hinh. Chi chay khi co co adb.
                        dumpAlarmFrame(src, d)
                        running?.cancel()
                        ov.clearPage()
                        setIconState(
                            if (src.isAlive) FloatingIcon.State.Ready
                            else FloatingIcon.State.NeedPermission
                        )
                        return@launch
                    }
                } else {
                    hits = 0
                }
                delay(WATCH_POLL_MS)
            }
        }
    }

    /** Service da chay roi, chi can cap lai quyen (vi du sau khi khoa man hinh). */
    private fun requestProjection() =
        startActivity(ProjectionRequestActivity.intent(this, alsoStartService = false))

    // ---------- dong (Story 3.5) ----------

    private fun closeEverything() {
        isRunning = false
        running?.cancel()
        watching?.cancel()
        editor?.hide()
        editor = null
        grabPanel?.hide()
        grabPanel = null
        scope.launch {
            runCatching { engines?.translator?.release() }   // AD-24
        }
        releaseProjection()
        overlays?.destroy(); overlays = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        releaseProjection()
        overlays?.destroy(); overlays = null
        super.onDestroy()
    }

    // ---------- lay chu trong vung tu chon ----------

    private var selection: app.mangatrans.adapters.overlay.SelectionOverlay? = null

    /**
     * Nguoi dung khong go duoc chu Nhat. Chuc nang nay de ho **khoanh** lay mot
     * cum chu tren truyen, app doc ra, roi ho nhet vao tu dien rieng — duong
     * duy nhat ep duoc mo hinh dich theo y minh (F60).
     */
    // ---------- sua ban dich mot bong thoai ----------

    /**
     * Cham hai cai vao mot bong -> mo man sua ban dich cua rieng no.
     *
     * Vi sao sua chu khong phai xoa roi dich lai: do duoc, dich lai cung mot
     * trang cho **5/5 cau giong het tung chu**. Xoa chi tra ve dung cai sai cu.
     */
    private fun openBubbleEditor(id: Int) {
        val b = currentPage?.bubbles?.firstOrNull { it.id == id } ?: return
        val ov = overlays ?: return
        val panel = editor ?: EditBubbleOverlay(
            this,
            getSystemService(WINDOW_SERVICE) as WindowManager,
            onSavePage = { vi -> applyEdit(id, vi) },
            onRemove = { removeManual(id) },
            onSaveGlossary = { vi -> saveToGlossary(id, b.ja, vi) },
            onAsk = { ja, back -> askGemini(ja, back) },
            onLocal = { ja, back -> translateOnePhrase(ja, back) },
            // Tam ngung bo canh trang trong luc panel mo. Panel la lop phu cua
            // CHINH ta va no phu kin trang, nen khong ngung thi bo canh ket
            // luan nguoi dung da lat trang roi go sach ban dich dang sua. Do
            // duoc voi ban lam bang Activity: `man hinh doi (khac 0.94)` dung
            // giay mo man sua.
            //
            // Dung `selfChanging` chu khong huy han bo canh: moc so sanh giu
            // nguyen, nen dong panel la no chay tiep ngay, khong phai lay moc lai.
            // ⚠️ Phai VUT HANG DOI FRAME truoc khi bat bo canh lai.
            //
            // `ImageReader` giu toi vai frame, nen nhip hoi dau tien sau khi
            // dong panel se bat phai mot frame chup TU LUC PANEL CON CHE man
            // hinh. Frame do khac moc mot troi mot vuc va bo canh ket luan
            // ngay la doi trang. Do duoc: `man hinh doi (khac 1.31)` dung giay
            // dong panel, va ca trang mat ban dich.
            onClosed = {
                source?.drainFrames()
                ov.selfChanging.set(false)
            },
        ).also { editor = it }

        ov.selfChanging.set(true)
        // Id am = lop de thu cong (xem `nextManualId`) -> cho go han.
        panel.show(b.ja, b.vi.orEmpty(), removable = id < 0)
    }

    /** Go mot lop de thu cong, tra lai tranh goc o dung cho do. */
    private fun removeManual(id: Int) {
        val ov = overlays ?: return
        currentPage = currentPage?.let { p -> p.withBubbles(p.bubbles.filterNot { it.id == id }) }
        scope.launch {
            withContext(Dispatchers.Main) { ov.translation.retract(listOf(id)) }
            toast("Đã gỡ lớp đè.")
        }
    }

    /** Goi Gemini ho panel — panel khong giu scope rieng. */
    private fun askGemini(ja: String, back: (Result<String>) -> Unit) {
        val key = app.mangatrans.adapters.cloud.GeminiLookup.key(this)
        if (key.isNullOrBlank()) {
            back(Result.failure(IllegalStateException(
                "chưa có khoá. Vào app → Cài đặt → Tra nghĩa bằng Gemini."
            )))
            return
        }
        scope.launch {
            val r = app.mangatrans.adapters.cloud.GeminiLookup(this@CaptureService, key)
                .meaningOf(ja)
            withContext(Dispatchers.Main) { back(r) }
        }
    }

    /**
     * Luu mot muc tu dien rieng, VA sua luon bong thoai dang mo.
     *
     * Sua luon vi nguoi dung vua go nghia do ra — bat ho doi den lan dich sau
     * moi thay la vo ly.
     */
    private fun saveToGlossary(id: Int, ja: String, vi: String) {
        if (!app.mangatrans.ports.isUsableSurface(ja)) {
            toast("Nguyên bản không phải chữ Nhật nên không khớp được trang truyện")
            return
        }
        scope.launch {
            runCatching {
                app.mangatrans.adapters.storage.JsonGlossaryStore(
                    Composition.glossaryFile(this@CaptureService)
                ).upsertByUser(
                    app.mangatrans.ports.GlossaryEntry(
                        seriesKey = "default", surface = ja, meaning = vi,
                        kind = app.mangatrans.ports.GlossaryKind.Idiom,
                        status = app.mangatrans.ports.GlossaryStatus.Confirmed,
                    )
                )
            }.onSuccess {
                // Khoa cache tinh tu ANH chu khong tu tu dien, nen nhung trang
                // DA dich van tra ve ban cu mai mai. Quen dung nhung trang co
                // chua cum nay — khong dung toi hang tram trang khong lien quan.
                val n = runCatching {
                    app.mangatrans.adapters.storage.FileCache(
                        java.io.File(cacheDir, "pages")
                    ).forgetContaining(ja)
                }.getOrDefault(0)
                toast(
                    if (n > 0) "Đã lưu. $n trang đã dịch có cụm này sẽ được dịch lại khi bạn mở."
                    else "Đã lưu vào từ điển riêng — các trang dịch sau sẽ dùng nghĩa này."
                )
            }.onFailure { toast("Không lưu được vào từ điển") }
        }
        applyEdit(id, vi)
    }

    /**
     * Ghi ban dich nguoi dung vua go: len man hinh NGAY, va vao cache de lan
     * sau mo lai dung trang nay van con.
     *
     * ⚠️ Ghi lai CA trang chu khong chi mot bong: khoa cache di kem chu ky cua
     * ca danh sach box (AD-18), nen ghi thieu la lan sau doc ra coi nhu hong.
     */
    private fun applyEdit(id: Int, vi: String) {
        val page = currentPage ?: return
        if (id < 0 || vi.isBlank()) return
        val next = page.bubbles.map { if (it.id == id) it.copy(vi = vi) else it }
        currentPage = page.withBubbles(next)

        scope.launch {
            val edited = next.firstOrNull { it.id == id } ?: return@launch
            overlays?.translation?.let { ov ->
                withContext(Dispatchers.Main) { ov.replace(edited) }
            }
            // `contentKey` rong = trang nay khong den tu day chuyen (lop de thu
            // cong khi chua dich trang nao). Khong co khoa that thi khong ghi
            // cache — ghi vao se de lai mot file ten rong, doc ra la rac.
            if (page.contentKey.isBlank()) return@launch
            runCatching {
                app.mangatrans.adapters.storage.FileCache(
                    java.io.File(cacheDir, "pages")
                ).put(page.contentKey, page.bubbles.map { it.box }, next)
            }.onFailure { Log.w(TAG, "khong ghi duoc cache sau khi sua: ${it.javaClass.simpleName}") }
        }
    }

    private fun startGrabText() {
        val ov = overlays ?: return
        val src = source
        if (src == null || !src.isAlive) { requestProjection(); return }
        if (engines == null) { toast("Đang chuẩn bị, đợi một chút"); return }
        if (selection != null) return

        selection = app.mangatrans.adapters.overlay.SelectionOverlay(
            this,
            getSystemService(WINDOW_SERVICE) as WindowManager,
            onPick = { box ->
                selection?.hide(); selection = null
                scope.launch { grabText(src, box) }
            },
            onCancel = { selection?.hide(); selection = null },
        ).also { it.show() }
        toast("Kéo một khung quanh chữ cần lấy")
    }

    private suspend fun grabText(src: MediaProjectionSource, boxOnScreen: app.mangatrans.domain.Box) {
        setIconState(FloatingIcon.State.Reading)
        val ocr = engines?.ocr
        val shot = runCatching { src.capture() }.getOrNull()
        if (shot == null || ocr == null) {
            say(CaptureFailure.Timeout)
            setIconState(FloatingIcon.State.Ready)
            return
        }
        val bmp = shot.handle as Bitmap
        // Anh chup da cat status bar, con khung nguoi dung keo la toa do man
        // hinh — phai tru lai, neu khong vung doc bi lech xuong duoi.
        val dy = statusBarHeight()
        val box = app.mangatrans.domain.Box(
            boxOnScreen.x1.coerceIn(0, bmp.width),
            (boxOnScreen.y1 - dy).coerceIn(0, bmp.height),
            boxOnScreen.x2.coerceIn(0, bmp.width),
            (boxOnScreen.y2 - dy).coerceIn(0, bmp.height),
        )
        val text = runCatching { ocr.read(shot, box) }.getOrDefault("")
        // ⚠️ KHONG thu hoi `bmp` o day nua. Neu nguoi dung bam "De len trang"
        // thi chinh anh nay lam nen de lay mau mau — thu hoi som la `getPixel`
        // no ngay va **chet ca app** (da mac dung mot lan).
        //
        // Quyen so huu: giu o `grabShot`. Giao cho lop phu thi dat null (lop
        // phu tu thu hoi khi `clear()`); khong giao thi thu hoi luc dong panel.
        setIconState(if (src.isAlive) FloatingIcon.State.Ready else FloatingIcon.State.NeedPermission)

        if (text.isBlank()) {
            runCatching { bmp.recycle() }
            toast("Không đọc được chữ nào trong khung đó.")
            return
        }
        grabShot = bmp
        // KHONG ghi `text` ra log — do la noi dung man hinh rieng cua nguoi dung.
        withContext(Dispatchers.Main) { showGrabResult(src, bmp, box, text) }
    }

    // ---------- khoanh chu -> dich -> de len trang ----------

    /**
     * ⚠️ Anh chup **khong** duoc `recycle()` o day.
     *
     * Muon ve de len trang thi phai co anh goc de lay mau mau nen. Neu chua co
     * trang nao dang hien thi chinh anh vua chup se lam nen — nen no phai song
     * den luc do. `TranslationOverlay.clear()` se thu hoi no sau.
     */
    private fun showGrabResult(
        src: MediaProjectionSource,
        shot: Bitmap,
        box: app.mangatrans.domain.Box,
        ja: String,
    ) {
        val ov = overlays ?: return
        val panel = grabPanel ?: GrabResultOverlay(
            this,
            getSystemService(WINDOW_SERVICE) as WindowManager,
            onLocal = { s, back -> translateOnePhrase(s, back) },
            onGemini = { s, back -> askGemini(s, back) },
            onDraw = { vi -> drawManual(box, ja, vi) },
            onSaveGlossary = { vi -> saveToGlossary(-1, ja, vi) },
            onClosed = {
                // Khong giao cho lop phu thi phai tu don.
                grabShot?.let { s -> runCatching { s.recycle() } }
                grabShot = null
                source?.drainFrames()
                ov.selfChanging.set(false)
            },
        ).also { grabPanel = it }

        ov.selfChanging.set(true)
        panel.show(ja)
    }

    /**
     * Dich MOT cum le tren may.
     *
     * Noi vao phien cua trang dang mo neu con (`continuing = true`) — do duoc
     * la ~3,9 giay thay vi ~17 giay, vi khong phai doc lai prompt he thong.
     * Khong con phien thi mo phien moi va chiu ca gia do.
     */
    private fun translateOnePhrase(ja: String, back: (Result<String>) -> Unit) {
        val tr = engines?.translator
        if (tr == null) { back(Result.failure(IllegalStateException("mô hình chưa sẵn sàng"))); return }
        scope.launch {
            val r = runCatching {
                val b = app.mangatrans.domain.Bubble(
                    id = 0, box = app.mangatrans.domain.Box(0, 0, 1, 1),
                    kind = app.mangatrans.domain.RegionKind.TextBubble,
                    detectScore = 1f, ja = ja,
                )
                val job = app.mangatrans.domain.PageJob(
                    jobId = "grab", frameHash = "", contentKey = "",
                    pageWidth = 0, pageHeight = 0,
                    bubbles = listOf(b), readingOrder = listOf(0),
                )
                var out: String? = null
                // ⚠️ KHONG loc theo `id == 0`. Chi co MOT bong trong yeu cau,
                // nhung mo hinh khong phai luc nao cung tra ve dung id da cho —
                // do that: no sinh ra ban dich (log "bubble dau tien sau
                // 18606 ms") ma van bao "khong tra loi" vi id lech. Lay cau
                // dau tien co chu la dung.
                tr.translate(job, emptyList(), continuing = currentPage != null).collect { bt ->
                    if (out == null) out = bt.vi?.takeIf { v -> v.isNotBlank() }
                }
                out ?: error("mô hình không trả lời")
            }
            withContext(Dispatchers.Main) { back(r) }
        }
    }

    /**
     * Ve ban dich de len dung khung nguoi dung vua khoanh.
     *
     * Lop de nay la **mot bubble nhu moi bubble khac** — nho vay cham giu de he
     * nguyen ban va cham hai cai de sua deu tu chay dung, khong phai viet rieng.
     * Chi khac o `id` am, de phan biet voi bubble do day chuyen sinh ra.
     */
    private fun drawManual(box: app.mangatrans.domain.Box, ja: String, vi: String) {
        val ov = overlays ?: return
        val tf = engines?.typeface ?: android.graphics.Typeface.SANS_SERIF
        val shot = grabShot
        scope.launch {
            // Chua co trang nao dang hien -> lay chinh anh vua chup lam nen.
            // Giao luon quyen so huu: tu day lop phu chiu trach nhiem thu hoi.
            if (ov.translation.frameHash == null) {
                if (shot == null || shot.isRecycled) {
                    toast("Ảnh nền không còn — chạm icon để dịch trang trước đã.")
                    return@launch
                }
                grabShot = null
                ov.beginPage(shot, PageHash.frameHash(shot, 0), statusBarHeight(), tf)
            }
            val id = nextManualId--
            val b = app.mangatrans.domain.Bubble(
                id = id, box = box,
                kind = app.mangatrans.domain.RegionKind.TextFree,
                // Giu nguyen ban: o sua can no de hien, va no la thu duy nhat
                // cho biet lop de nay tu chu nao ra.
                detectScore = 1f, ja = ja, vi = vi,
            )
            // ⚠️ Phai TU TAO `currentPage` neu chua co.
            //
            // Nguoi dung hoan toan co the khoanh chu ma chua tung dich trang
            // nao — luc do `currentPage` la null, va neu chi `?.let` thi lop de
            // khong duoc ghi vao dau ca. Hau qua: no ve ra man hinh nhung cham
            // hai cai vao khong mo duoc o sua, vi `openBubbleEditor` tim trong
            // `currentPage` khong thay. Da mac dung mot lan.
            //
            // `contentKey` de rong co chu y — xem `applyEdit`: rong thi khong
            // ghi cache, vi khong co trang that nao de gan vao.
            val page = currentPage ?: app.mangatrans.domain.PageJob(
                jobId = "manual", frameHash = "", contentKey = "",
                pageWidth = 0, pageHeight = 0,
            )
            currentPage = page.withBubbles(page.bubbles + b)
            withContext(Dispatchers.Main) { ov.translation.add(b) }
            toast("Đã đè lên trang. Chạm hai cái vào đó để sửa hoặc gỡ.")
        }
    }

    private fun openGuide() = startActivity(
        Intent(this, GuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // ---------- bao loi bang tieng nguoi (Story 3.8) ----------

    /**
     * FR-013/014 — noi bang ngon ngu nguoi dung hieu, khong phai ma loi.
     * `when` tren `sealed` nen them nhanh loi moi la bi bien dich bat ngay.
     */
    private fun say(f: CaptureFailure) {
        // Chi ghi MA loi. Toast co the bi nguoi dung tat trong Cai dat
        // ("Suppressing toast ... by user request"), luc do khong con dau vet nao.
        Log.i(TAG, "chup hong: ${f.javaClass.simpleName}")
        toast(
            when (f) {
                CaptureFailure.ScreenProtected ->
                    "App đang đọc chặn chụp màn hình (bảo vệ bản quyền). Đây là giới hạn của Android, không có cách vòng."
                CaptureFailure.SessionRevoked ->
                    "Phiên chụp đã dừng (thường là do khoá màn hình). Chạm icon để cấp lại."
                CaptureFailure.NoPermission ->
                    "Chưa được cấp quyền chụp màn hình. Chạm icon để cấp."
                CaptureFailure.Timeout ->
                    "Không lấy được ảnh màn hình. Thử lại một lần nữa."
            }
        )
    }

    private fun toast(msg: String) = scope.launch(Dispatchers.Main) {
        Toast.makeText(this@CaptureService, msg, Toast.LENGTH_LONG).show()
    }

    private fun setIconState(s: FloatingIcon.State) = scope.launch(Dispatchers.Main) {
        overlays?.state = s
    }

    // ---------- thong bao thuong truc ----------

    /**
     * Tu Android 14 (API 34), foreground service dung MediaProjection **bat buoc**
     * khai bao `foregroundServiceType=mediaProjection` ca trong manifest lan luc
     * goi `startForeground`. Thieu la nem `SecurityException`.
     */
    private fun startForegroundProperly() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Dịch màn hình", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Manga Translator đang bật")
            // FR-012 — noi ro app dang co kha nang chup man hinh.
            .setContentText("App có thể chụp màn hình khi bạn chạm icon")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Tắt", stop).build())
            .build()

        val type = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> null

            // ⚠️ Tu Android 14, loai `mediaProjection` doi quyen chup PHAI CO
            // SAN. Chua co ma khoi dong la SecurityException => app sap ngay.
            // Da thay that tren may ao Android 16 (FINDINGS F34).
            hasProjectionConsent -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

            // Chua xin quyen: service luc nay chi giu icon noi, chua chup gi.
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (type == null) startForeground(NOTIF_ID, n) else startForeground(NOTIF_ID, n, type)
    }

    /**
     * Kich thuoc man hinh THAT, **ke ca vung thanh he thong**.
     *
     * ⚠️ KHONG dung `resources.displayMetrics`: no tru di thanh dieu huong, nen
     * tra ve 1080x2196 thay vi 1080x2400. `VirtualDisplay` tao theo kich thuoc
     * do se co ty le khac man hinh that, va `AUTO_MIRROR` thu nho anh cho vua
     * => anh chup bi **thu nho ~0.915 lan va day sang phai ~46 px**.
     *
     * Hau qua nhin thay duoc tren may: o nen to lech sang trai va nho hon bong
     * thoai, nen **chu Nhat goc van lo ra o mep phai** — trong nhu dich thieu.
     * Khong log nao bao gi; chi nhin anh moi thay.
     */
    private fun realScreenSize(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val d = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

    /**
     * AD-11 — vung status bar phai cat bo truoc khi detect, vi tu Android 15
     * QPR1 he dieu hanh ve chip "dang chia se man hinh" o do va app KHONG an duoc.
     */
    private fun statusBarHeight(): Int {
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}
