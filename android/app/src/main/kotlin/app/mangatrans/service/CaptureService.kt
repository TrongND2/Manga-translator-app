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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(code, data) ?: run {
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
    }

    // ---------- nap engine ----------

    private fun loadEngines() = scope.launch {
        Composition.seedGlossaryIfEmpty(this@CaptureService) { Log.i(TAG, it) }
        runCatching { Composition.engines(this@CaptureService) { Log.i(TAG, it) } }
            .onSuccess { e ->
                engines = e
                pipeline = e.pipeline
                // AD-20 — ham nong truoc, khong de den luc nguoi dung cham icon.
                runCatching { e.translator.warmUp() }
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
        val tf = engines?.typeface ?: android.graphics.Typeface.SANS_SERIF
        // AD-12/AD-18 — lop phu mang `frameHash` cua anh sinh ra no, KHONG phai
        // `contentKey`. Anh da bi cat status bar roi nen cropTop = 0.
        val frameHash = PageHash.frameHash(bitmap, 0)

        setIconState(FloatingIcon.State.Reading)
        var drawn = 0
        runCatching {
            p.run(bitmap).collect { ev ->
                when (ev) {
                    is PageEvent.Progress -> {
                        // CHI ghi so dem va ma trang thai. KHONG bao gio ghi noi
                        // dung anh hay chu da OCR — do la man hinh rieng cua
                        // nguoi dung.
                        Log.i(TAG, "${ev.stage} ${ev.done}/${ev.total}")
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

                    is PageEvent.Done -> Unit

                    // Hong ca luot — khac han ket qua tung bubble. Noi ro ly do
                    // bang tieng nguoi (Story 3.8), khong hien ma loi.
                    is PageEvent.Failed -> when (ev.error.kind) {
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
        }.onFailure { Log.e(TAG, "luot dich hong: ${it.javaClass.simpleName}") }

        Log.i(TAG, "xong: ve $drawn bubble")
        setIconState(if (src.isAlive) FloatingIcon.State.Ready else FloatingIcon.State.NeedPermission)
        if (drawn == 0) toast("Không tìm thấy bóng thoại nào trên màn hình")
    }

    private fun requestProjection() {
        startActivity(
            Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- dong (Story 3.5) ----------

    private fun closeEverything() {
        running?.cancel()
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
    private fun say(f: CaptureFailure) = toast(
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
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
