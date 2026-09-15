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
import app.mangatrans.adapters.overlay.FloatingIcon
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
        private const val ARM_QUIET_MS = 1_000L

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

    /** Story 3.7 — canh noi dung ben duoi doi de go lop phu. */
    private var watching: Job? = null

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
            onClose = { closeEverything() },
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
        if (running?.isActive == true) return   // dang chay, bo qua cham thua

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
                        // Lan dau co bubble moi dung anh chup lam nen — truoc do
                        // chua biet co ve duoc gi khong, ma AD-9 cam to nen som.
                        if (drawn == 0) {
                            ov.beginPage(bitmap, frameHash, statusBarHeight(), tf)
                        }
                        drawn++
                        ov.addBubble(ev.bubble)
                    }

                    // AD-17 — go bubble da ve, chu Nhat goc hien lai nguyen ven.
                    is PageEvent.Retracted -> ov.retract(ev.bubbleIds)

                    // AD-9 — ca trang tro ve nguyen ban.
                    is PageEvent.PageRejected -> { ov.clearPage(); drawn = 0 }

                    is PageEvent.Done -> dumpForDiagnosis(ev.job)

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

        Log.i(TAG, "xong: ve $drawn bubble")
        // Cua so ban dich duoc them SAU icon nen nam tren no. Bong thoai nao
        // gan mep la de len icon va nuot cu cham — dua icon len lai (F44).
        if (drawn > 0) ov.raiseIcon()
        if (drawn > 0) {
            // Story 3.6 da nam trong chinh cac cua so ve — khong con buoc rieng.
            watchForPageChange(src)   // Story 3.7
        } else {
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
    private fun watchForPageChange(src: MediaProjectionSource) {
        watching?.cancel()
        watching = scope.launch {
            // Cho lop phu ve xong roi moi lay moc, neu khong thi chinh no lam
            // hash doi va lop phu tu xoa minh ngay lap tuc.
            delay(SETTLE_MS)
            var baseline: FloatArray? = null
            var wasSelfChanging = false
            // Chua "len nong": con dang lay moc, chua so sanh. Xem ARM_QUIET_MS.
            var armed = false
            var lastFrameMs = System.currentTimeMillis()
            while (isActive && src.isAlive) {
                val ov = overlays ?: return@launch
                if (ov.selfChanging.get()) {
                    // App dang tu lam man hinh doi (liec nguyen ban, hoac an lop
                    // phu de chup). Bo moc cu va lay moc moi khi xong — neu
                    // khong thi chinh app lam mat ban dich cua no.
                    baseline = null
                    armed = false
                    wasSelfChanging = true
                } else if (wasSelfChanging) {
                    // Vua thoi tu-lam-doi. KHONG lay moc ngay: `ImageReader` con
                    // giu frame cua trang thai DA QUA (luc dang an lop phu), va
                    // lay chung lam moc se khien lop phu tu xoa minh o vong sau.
                    wasSelfChanging = false
                    src.drainFrames()
                    delay(SETTLE_MS)
                    src.drainFrames()
                    baseline = null
                    armed = false
                    lastFrameMs = System.currentTimeMillis()
                } else {
                    val now = src.peekFrameSignature()
                    if (now != null) lastFrameMs = System.currentTimeMillis()
                    if (now != null && !armed) {
                        // Con dang co frame chay ve => man hinh chua yen. Lay
                        // moc moi, chua so sanh gi ca.
                        baseline = now
                    } else if (now == null && !armed && baseline != null &&
                        System.currentTimeMillis() - lastFrameMs > ARM_QUIET_MS
                    ) {
                        armed = true
                    } else if (now != null) {
                        val base = baseline
                        if (base == null) {
                            baseline = now
                        } else {
                            val d = PageHash.distance(base, now)
                            if (d > PAGE_CHANGE_THRESHOLD) {
                                Log.i(TAG, "noi dung ben duoi doi (khac %.2f) — go lop phu".format(d))
                                ov.clearPage()
                                return@launch
                            }
                        }
                    }
                }
                delay(POLL_MS)
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
