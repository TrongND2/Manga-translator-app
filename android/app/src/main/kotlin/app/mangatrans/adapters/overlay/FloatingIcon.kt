package app.mangatrans.adapters.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Story 3.1 + 3.5 — icon noi, keo duoc, nep mep, giu de mo hai icon con.
 *
 * Toan bo giao dien cua san pham that la cai icon nay. Khong dung file layout
 * hay drawable: mot hinh tron ve bang `GradientDrawable` khong can res nao, va
 * bot mot lop co the lech giua code va XML.
 */
class FloatingIcon(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
    private val onGuide: () -> Unit,
    private val onClose: () -> Unit,
) {

    /**
     * Trang thai hien tren icon. Story 3.10 bat buoc giai thich duoc TUNG cai,
     * nen moi trang thai phai co nhan tieng Viet ro rang.
     */
    enum class State(val glyph: String, val label: String, val color: Int) {
        /** AD-20 — dang nap engine. KHONG nhan cham. */
        Preparing("···", "Đang chuẩn bị", 0xFF9E9E9E.toInt()),
        Ready("VI", "Sẵn sàng — chạm để dịch", 0xFF00695C.toInt()),
        Capturing("◎", "Đang chụp màn hình", 0xFF0277BD.toInt()),
        Reading("···", "Đang đọc chữ", 0xFF0277BD.toInt()),
        Translating("···", "Đang dịch", 0xFF0277BD.toInt()),

        /** AD-21 — khoa man hinh lam dung phien chieu. Binh thuong, khong phai loi. */
        NeedPermission("!", "Chạm để cấp lại quyền chụp màn hình", 0xFFEF6C00.toInt()),
        Failed("!", "Không dịch được — chạm để xem lý do", 0xFFC62828.toInt()),
    }

    private companion object {
        const val ICON_DP = 52
        const val SUB_DP = 44

        /** Giu bao lau thi coi la "giu" chu khong phai "cham". */
        const val LONG_PRESS_MS = 450L

        /** Xe dich qua nguong nay thi la keo, khong phai cham. */
        const val DRAG_SLOP_DP = 8
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    var state: State = State.Preparing
        set(value) {
            field = value
            art.set(value.color, value.glyph)
        }

    /** Hinh bong thoai — xem `BubbleIconDrawable` de biet vi sao khong dung chu Han. */
    private val art = BubbleIconDrawable(State.Preparing.color, State.Preparing.glyph)

    private val badge = View(ctx).apply { background = art }

    private val root = FrameLayout(ctx).apply {
        addView(badge, FrameLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
    }

    private val lp = WindowManager.LayoutParams(
        dp(ICON_DP), dp(ICON_DP),
        overlayType(),
        // NOT_FOCUSABLE: khong cuop ban phim cua app dang doc.
        // Khong dat NOT_TOUCHABLE vi chinh icon nay PHAI nhan cham.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = screenH / 3
    }

    private var subIcons: SubIcons? = null
    private var attached = false

    fun show() {
        if (attached) return
        wm.addView(root, lp)
        attached = true
        state = state          // ve lai mau/glyph
    }

    /**
     * Dua icon len TREN cung.
     *
     * Trong cung mot loai cua so, cai them SAU nam tren. Lop phu ban dich dat
     * mot cua so rieng cho moi bong thoai (xem `TranslationOverlay`), va chung
     * duoc them sau icon — nen bong thoai nao nam gan mep man hinh la **de len
     * icon va nuot luon cu cham**.
     *
     * Do duoc tren may, sau mot luot dich:
     * ```
     *   icon         frame=[0,818][136,954]
     *   mot bong     frame=[26,624][163,1027]   <- trum len icon
     * ```
     * Trieu chung: cham icon ma khong co gi xay ra, nhin y nhu app treo (F44).
     *
     * `WindowManager` khong cho dat thu tu z truc tiep, nen cach duy nhat la
     * go ra roi gan lai. Chi goi mot lan sau khi ve xong ca trang — goi moi lan
     * them mot bong se lam icon chop giat 12 lan.
     */
    /**
     * Khung icon dang chiem tren man hinh. Bo canh trang phai BO QUA vung nay:
     * anh chup goc khong co icon (no bi an di luc chup), nen neu khong bo qua
     * thi chinh icon lam lech phep so sanh.
     */
    fun boxOnScreen(): app.mangatrans.domain.Box =
        app.mangatrans.domain.Box(lp.x, lp.y, lp.x + dp(ICON_DP), lp.y + dp(ICON_DP))

    fun raise() {
        if (!attached) return
        runCatching { wm.removeView(root) }
        runCatching { wm.addView(root, lp) }
        state = state
    }

    /** Story 3.5 — dong thi khong con dau vet nao tren man hinh (AD-10). */
    fun hide() {
        dismissSubIcons()
        if (!attached) return
        runCatching { wm.removeView(root) }
        attached = false
    }

    /** AD-11 — an tam de chup, KHONG thao view (thao roi dung lai hay chop giat). */
    fun setVisibleForCapture(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        subIcons?.setVisible(visible)
    }

    // ---------- cham, keo, giu ----------

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressFired = false

    private val longPress = Runnable {
        longPressFired = true
        showSubIcons()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireTouch() = badge.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = lp.x; startY = lp.y
                dragging = false; longPressFired = false
                badge.postDelayed(longPress, LONG_PRESS_MS)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && (abs(dx) > dp(DRAG_SLOP_DP) || abs(dy) > dp(DRAG_SLOP_DP))) {
                    dragging = true
                    badge.removeCallbacks(longPress)   // keo thi khong phai giu
                    dismissSubIcons()
                }
                if (dragging) {
                    lp.x = (startX + dx).toInt()
                    lp.y = (startY + dy).toInt()
                    runCatching { wm.updateViewLayout(root, lp) }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                badge.removeCallbacks(longPress)
                when {
                    dragging -> snapToEdge()
                    longPressFired -> Unit          // icon con da hien, khong lam gi them
                    // AD-20: chua nap xong thi KHONG nhan cham. Bao cho nguoi
                    // dung biet thay vi im lang bo qua.
                    state == State.Preparing -> toastState()
                    else -> onTap()
                }
                true
            }

            else -> false
        }
    }

    init { wireTouch() }

    /** Story 3.1 — tu nep vao mep man hinh gan nhat. */
    private fun snapToEdge() {
        val half = dp(ICON_DP) / 2
        lp.x = if (lp.x + half < screenW / 2) 0 else screenW - dp(ICON_DP)
        lp.y = lp.y.coerceIn(0, screenH - dp(ICON_DP))
        runCatching { wm.updateViewLayout(root, lp) }
        // Icon con bam theo icon me.
        subIcons?.let { dismissSubIcons(); showSubIcons() }
    }

    private fun toastState() =
        android.widget.Toast.makeText(ctx, state.label, android.widget.Toast.LENGTH_SHORT).show()

    // ---------- hai icon con (Story 3.5 + 3.10) ----------

    private fun showSubIcons() {
        if (subIcons != null) return
        subIcons = SubIcons(
            ctx, wm,
            anchorX = lp.x, anchorY = lp.y,
            iconPx = dp(ICON_DP), subPx = dp(SUB_DP),
            screenW = screenW, screenH = screenH,
            onGuide = { dismissSubIcons(); onGuide() },
            onClose = { dismissSubIcons(); onClose() },
            onOutside = { dismissSubIcons() },
        ).also { it.show() }
    }

    private fun dismissSubIcons() {
        subIcons?.hide()
        subIcons = null
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
