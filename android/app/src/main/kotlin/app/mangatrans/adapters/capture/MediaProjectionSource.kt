package app.mangatrans.adapters.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.mangatrans.adapters.storage.PageHash
import app.mangatrans.ports.CaptureException
import app.mangatrans.ports.CaptureFailure
import app.mangatrans.ports.OverlayGate
import app.mangatrans.ports.PageImage
import app.mangatrans.ports.ScreenSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Story 3.2 / 3.3 — AD-11, AD-21.
 *
 * CHU SO HUU DUY NHAT cua mot `VirtualDisplay`. `CaptureService` tao doi tuong
 * nay mot lan va giu no; khong noi nao khac duoc tao them (AD-21).
 *
 * Ba cach `MediaProjection` chet that, ma FR-016 ("xin mot lan roi giu song")
 * che mat:
 *   1. nguoi dung khoa man hinh    -> he dieu hanh DUNG phien chieu
 *   2. he dieu hanh thu hoi quyen  -> `Callback.onStop()`
 *   3. app duoi dat `FLAG_SECURE`  -> chup ra anh DEN, khong nem loi gi
 *
 * Cach 3 nguy hiem nhat vi no KHONG bao loi. Xem `looksBlank()`.
 */
class MediaProjectionSource(
    private val projection: MediaProjection,
    private val overlays: OverlayGate,
    private val widthPx: Int,
    private val heightPx: Int,
    private val densityDpi: Int,
    /** AD-11 — cat bo vung status bar truoc khi detect. */
    private val statusBarPx: Int,
) : ScreenSource {

    private companion object {
        const val TAG = "Capture"

        /**
         * So frame bo di truoc khi lay. `VirtualDisplay` vua tao thuong tra ve
         * vai frame rong hoac con dinh lop phu cu.
         */
        const val WARMUP_FRAMES = 2

        /** Cho mot frame sau khi an lop phu — mot nhip ve cua he thong. */
        const val HIDE_SETTLE_MS = 120L

        const val FRAME_TIMEOUT_MS = 3_000L

        /** Khong con frame moi trong chung nay thi coi nhu he thong ve xong. */
        const val QUIET_MS = 220L

        /** Nhip hoi trong luc cho man hinh yen. */
        const val POLL_MS = 30L

        /** ImageReader giu toi da bao nhieu anh. 2 la du va it ton bo nho nhat. */
        const val MAX_IMAGES = 2
    }

    /** `onStop()` co the den bat cu luc nao, tu thread khac. */
    private val stopped = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // AD-21: dang ky callback nay la BAT BUOC — thieu no thi
            // `createVirtualDisplay()` nem IllegalStateException tren Android moi.
            Log.i(TAG, "phien chieu dung")
            stopped.set(true)
            releaseDisplay()
        }
    }

    init {
        projection.registerCallback(projectionCallback, handler)
    }

    /** Phien con dung duoc khong — `ui` hoi de hien dung trang thai icon. */
    val isAlive: Boolean get() = !stopped.get() && !released.get()

    /** @return true neu vua TAO MOI display (frame dau tien chua dang tin). */
    @SuppressLint("WrongConstant")
    private fun ensureDisplay(): Boolean {
        if (display != null) return false
        val r = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, MAX_IMAGES)
        reader = r
        display = projection.createVirtualDisplay(
            "mangatrans-capture",
            widthPx, heightPx, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
        return true
    }

    /**
     * AD-11 — TU an lop phu, cho mot frame, chup, roi hien lai. Nguoi goi
     * khong phai nho gi ca; `OverlayGate` lam viec hien lai trong `finally`.
     */
    override suspend fun capture(): PageImage {
        if (stopped.get()) throw CaptureException(CaptureFailure.SessionRevoked)
        if (released.get()) throw CaptureException(CaptureFailure.NoPermission)

        // ⚠️ Vut frame cu **TRUOC KHI** an lop phu, khong phai sau.
        //
        // `VirtualDisplay` chi sinh frame khi man hinh CO thay doi. An lop phu
        // la mot thay doi, nen no de lai dung MOT frame — va do la frame ta
        // can. Ban cu vut vai frame o `grabFrame()` sau khi da an, tuc vut
        // trung ngay chinh no, roi ngoi cho mot frame nua khong bao gio den.
        //
        // Man hinh cang tinh thi cang chac chet: do tren may, Perfect Viewer
        // mo toan man mot trang tinh (khong ca dong ho) thi **lan nao cung
        // Timeout**. Mo Gallery thi thoat, vi dong ho tren thanh trang thai
        // nhay giay nen luc nao cung co frame moi. Mot loi chi lo ra o dung
        // canh dung that (F42).
        drainFrames()

        val bitmap = overlays.hiddenForCapture {
            delay(HIDE_SETTLE_MS)
            val fresh = ensureDisplay()
            grabSettledFrame(warmup = fresh)
        }

        if (stopped.get()) throw CaptureException(CaptureFailure.SessionRevoked)

        // FLAG_SECURE khong nem loi — no tra ve anh den. Day la CACH DUY NHAT
        // phat hien duoc, va no la suy doan chu khong phai tin hieu chac chan:
        // mot trang truyen den kin cung cho ket qua nay. Chap nhan, vi bao nham
        // "khong chup duoc" van tot hon lang le dua anh den vao OCR.
        if (looksBlank(bitmap)) {
            bitmap.recycle()
            throw CaptureException(CaptureFailure.ScreenProtected)
        }

        val cropped = cropStatusBar(bitmap)
        return PageImage(cropped.width, cropped.height, cropped)
    }

    /**
     * Story 3.7 / AD-12 — nhin mot frame MOI neu co, de biet noi dung ben duoi
     * da doi chua. Tra `null` khi chua co frame moi.
     *
     * Tra ve CHU KY da chuan hoa do sang, khong phai ma bam chinh xac — xem
     * `PageHash.frameSignature` de biet vi sao (man hinh tu mo di lam xoa mat
     * ban dich, F43).
     *
     * Re vi hai le:
     *   1. `VirtualDisplay` chi sinh frame khi man hinh CO thay doi, nen luc
     *      dung yen thi `acquireLatestImage()` tra null ngay.
     *   2. Ham nay **khong** an lop phu. Lop phu dung yen nen no khong lam hash
     *      doi; chi noi dung ben duoi doi moi lam hash doi. An lop phu de "nhin
     *      cho ky" se lam man hinh chop giat lien tuc.
     *
     * Cat bo status bar y nhu `capture()`: dong ho nhay phut khong duoc tinh la
     * "nguoi dung sang trang".
     */
    /**
     * Khung hinh hien tai, GIU NGUYEN lop phu cua chinh app.
     *
     * Khac `capture()`: ham do AN lop phu di truoc khi chup vi no phuc vu viec
     * doc chu. Cho nay can dung cai nguoc lai — chup y het nhung gi bo canh
     * trang dang nhin thay, de xem cai gi da lam no bao dong.
     *
     * Chi dung cho duong chan doan. Nguoi goi phai tu `recycle()`.
     */
    fun peekBitmap(): Bitmap? {
        if (stopped.get() || released.get()) return null
        val r = reader ?: return null
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return null
        return image.use { runCatching { toBitmap(it) }.getOrNull() }
    }

    fun peekFrameSignature(exclude: List<app.mangatrans.domain.Box> = emptyList()): FloatArray? {
        if (stopped.get() || released.get()) return null
        val r = reader ?: return null
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return null
        return image.use {
            val bmp = toBitmap(it)
            val sig = runCatching { PageHash.frameSignature(bmp, statusBarPx, exclude) }.getOrNull()
            bmp.recycle()
            sig
        }
    }

    /**
     * Vut bo moi frame dang xep hang.
     *
     * `ImageReader` giu toi da `MAX_IMAGES` anh, nen `acquireLatestImage()` co
     * the tra ve anh chup tu VAI TRAM MILI GIAY TRUOC. Sau khi app tu lam man
     * hinh doi (an lop phu de liec hoac de chup), nhung frame cu do la anh cua
     * trang thai DA QUA — lay chung lam moc so sanh se sai ngay.
     *
     * Da xay ra that: tha tay sau khi liec nguyen ban thi frame cu (luc dang an)
     * thanh moc, frame moi (da hien lai) khac moc do, va lop phu bi xoa mat.
     */
    fun drainFrames() {
        val r = reader ?: return
        repeat(MAX_IMAGES + 1) { runCatching { r.acquireLatestImage()?.close() } }
    }

    /**
     * @param warmup chi dat true khi `VirtualDisplay` VUA DUOC TAO — luc do vai
     *   frame dau that su chua dang tin. Dat true cho moi lan chup la cach
     *   chac chan de vut mat dung frame minh dang cho (xem `capture()`).
     */
    /**
     * Cho man hinh **YEN** roi moi lay anh, thay vi lay frame dau tien thay duoc.
     *
     * ⚠️ Day la loi nang nhat cua ca tang chup, va no chi lo ra o lan chup THU
     * HAI tren cung mot trang.
     *
     * Tu F39, lop phu ban dich khong con la MOT cua so ma la **mot cua so cho
     * moi bong thoai**. An chung di khong con la mot thao tac tuc thi: he thong
     * go tung cua so mot, va moi buoc trung gian deu sinh ra mot frame. Lay
     * frame dau tien sau `HIDE_SETTLE_MS` la lay dung mot buoc giua chung — anh
     * do con **dinh mot phan ban dich cu**.
     *
     * Hau qua do duoc, chup lai chinh anh ma OCR nhin thay o lan thu hai:
     * ```
     *   JA: Cau…!
     *   JA: Cauconchamvaodichbaogiorood
     *   JA: Khongphaitenthatdau!
     * ```
     * Do la ban dich tieng Viet cua lan mot, bi OCR doc lai. App dang dich
     * chinh ban dich cua no. Ket qua: mo hinh nhan de bai vo nghia, jaEcho
     * lech, ca trang bi tu choi — nguoi dung thay "bam dich lan hai thi khong
     * co gi xay ra" (F48).
     *
     * Cach dung: giu frame MOI NHAT, tiep tuc doi; khi khong con frame moi nao
     * trong `QUIET_MS` thi coi nhu he thong ve xong. Vua chiu duoc man hinh
     * tinh (khong frame nao => dung frame dang giu) vua chiu duoc man hinh con
     * dang doi (doi den khi yen).
     */
    private suspend fun grabSettledFrame(warmup: Boolean): Bitmap {
        val r = reader ?: throw CaptureException(CaptureFailure.NoPermission)
        if (warmup) repeat(WARMUP_FRAMES) { runCatching { r.acquireLatestImage()?.close() } }

        var held: Image? = null
        var lastSeen = System.currentTimeMillis()
        val deadline = lastSeen + FRAME_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                val next = runCatching { r.acquireLatestImage() }.getOrNull()
                if (next != null) {
                    held?.close()
                    held = next
                    lastSeen = System.currentTimeMillis()
                } else {
                    if (held != null && System.currentTimeMillis() - lastSeen > QUIET_MS) break
                    delay(POLL_MS)
                }
            }
            val img = held ?: throw CaptureException(CaptureFailure.Timeout)
            return toBitmap(img)
        } finally {
            held?.close()
        }
    }

    private suspend fun grabFrame(warmup: Boolean): Bitmap {
        val r = reader ?: throw CaptureException(CaptureFailure.NoPermission)

        if (warmup) repeat(WARMUP_FRAMES) { runCatching { r.acquireLatestImage()?.close() } }

        val image = withTimeoutOrNull(FRAME_TIMEOUT_MS) { awaitImage(r) }
            ?: throw CaptureException(CaptureFailure.Timeout)

        return image.use { toBitmap(it) }
    }

    private suspend fun awaitImage(r: ImageReader): Image =
        suspendCancellableCoroutine { cont ->
            // Anh co the da san sang truoc khi ta kip dang ky listener.
            r.acquireLatestImage()?.let { cont.resume(it); return@suspendCancellableCoroutine }

            val done = AtomicBoolean(false)
            r.setOnImageAvailableListener({ src ->
                if (done.compareAndSet(false, true)) {
                    r.setOnImageAvailableListener(null, null)
                    src.acquireLatestImage()?.let { cont.resumeIfActive(it) }
                }
            }, handler)
            cont.invokeOnCancellation { r.setOnImageAvailableListener(null, null) }
        }

    private fun CancellableContinuation<Image>.resumeIfActive(img: Image) {
        if (isActive) resume(img) else img.close()
    }

    /**
     * `Image` cua ImageReader co **rowStride >= width * 4** (padding cua phan
     * cung). Bo qua cho nay thi anh bi xe cheo — loi kinh dien.
     */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val paddedWidth = rowStride / pixelStride

        val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)

        if (paddedWidth == image.width) return full
        // Cat bo phan dem ben phai.
        val exact = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        full.recycle()
        return exact
    }

    /**
     * AD-11 — tu Android 15 QPR1, he dieu hanh ve chip "dang chia se man hinh"
     * ma app KHONG an duoc. No nam o status bar, nen cat di la xong.
     */
    private fun cropStatusBar(src: Bitmap): Bitmap {
        if (statusBarPx <= 0 || statusBarPx >= src.height) return src
        val out = Bitmap.createBitmap(src, 0, statusBarPx, src.width, src.height - statusBarPx)
        if (out != src) src.recycle()
        return out
    }

    /**
     * Lay mau thua thay vi duyet ca anh: 4 trieu diem moi lan chup la lang phi
     * ro rang, va anh den thi diem nao cung den.
     */
    private fun looksBlank(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 32).coerceAtLeast(1)
        val stepY = (bmp.height / 32).coerceAtLeast(1)
        var first: Int? = null
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                if (first == null) first = c else if (c != first) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private fun releaseDisplay() {
        display?.release(); display = null
        reader?.close(); reader = null
    }

    /** Story 3.5 — dong app phai go sach, khong con dau vet (AD-10). */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        releaseDisplay()
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
    }
}
