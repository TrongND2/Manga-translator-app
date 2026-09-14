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

    @SuppressLint("WrongConstant")
    private fun ensureDisplay() {
        if (display != null) return
        val r = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, MAX_IMAGES)
        reader = r
        display = projection.createVirtualDisplay(
            "mangatrans-capture",
            widthPx, heightPx, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
    }

    /**
     * AD-11 — TU an lop phu, cho mot frame, chup, roi hien lai. Nguoi goi
     * khong phai nho gi ca; `OverlayGate` lam viec hien lai trong `finally`.
     */
    override suspend fun capture(): PageImage {
        if (stopped.get()) throw CaptureException(CaptureFailure.SessionRevoked)
        if (released.get()) throw CaptureException(CaptureFailure.NoPermission)

        val bitmap = overlays.hiddenForCapture {
            // Cho he thong ve xong mot nhip sau khi go lop phu. Khong co cho nay
            // thi anh chup con dinh icon va ban dich cu — dung loi AD-11 chan.
            delay(HIDE_SETTLE_MS)
            ensureDisplay()
            grabFrame()
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
    fun peekFrameHash(): String? {
        if (stopped.get() || released.get()) return null
        val r = reader ?: return null
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return null
        return image.use {
            val bmp = toBitmap(it)
            val hash = runCatching { PageHash.frameHash(bmp, statusBarPx) }.getOrNull()
            bmp.recycle()
            hash
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

    private suspend fun grabFrame(): Bitmap {
        val r = reader ?: throw CaptureException(CaptureFailure.NoPermission)

        // Bo vai frame dau: VirtualDisplay vua tao hay tra frame rong hoac con
        // dinh lop phu cu.
        repeat(WARMUP_FRAMES) { runCatching { r.acquireLatestImage()?.close() } }

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
