package app.mangatrans.adapters.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.BubbleState
import app.mangatrans.pipeline.BubbleRenderer

/**
 * Story 3.4 / 3.6 / 3.7 — lop ve ban dich de len man hinh.
 *
 * AD-10: chi VE, khong bao gio sua app ben duoi. Go lop nay ra la chu Nhat goc
 * hien lai nguyen ven — do la ly do FR-006 dung mien phi.
 *
 * AD-12: lop phu mang `frameHash` cua anh sinh ra no. Doi noi dung ben duoi la
 * tu xoa ngay, khong cho luot dich moi.
 *
 * ---
 *
 * ⚠️ **MOI BONG THOAI MOT CUA SO RIENG, va cua so do PHAI nhan cham.**
 *
 * Truoc day day la MOT cua so phu toan man hinh dat `FLAG_NOT_TOUCHABLE`, de
 * cham/vuot/cuon cua app ben duoi di xuyen qua. Cach do lam lo ra mot loi rat
 * kho ngo: **chu Nhat goc van hien mo mo duoi ban dich**, o MOI bong, ke ca khi
 * o nen da to trang duc hoan toan.
 *
 * Nguyen nhan khong nam trong code ve. Android chan tran do duc cua cua so phu
 * cho cham di xuyen qua — chong "tapjacking", tran mac dinh la **0.8**. Nen dù
 * ve mau gi thi len man hinh cung chi con 80%, 20% con lai la noi dung ben duoi.
 *
 * Do duoc bang cach to nen mau do nguyen chat roi doc pixel tren anh chup:
 *
 * ```
 *   FLAG_NOT_TOUCHABLE   : nen (255, 51, 51)   chu (51, 51, 51)
 *   khong co co do        : nen (255,  0,  0)   chu ( 0,  0,  0)
 * ```
 *
 * 51 = 20% cua 255, dung bang phan ben duoi lot qua. Icon noi khong dinh loi
 * nay vi no nhan cham, nen khong bi chan tran — do duoc (0,105,92) dung y mau
 * dat trong code.
 *
 * Nen gio: cua so ve = cua so nhan cham, dat KHIT tung bong thoai. Ngoai bong
 * thoai khong co cua so nao, nen Story 3.7 van dung — app ben duoi cuon binh
 * thuong. Ben trong bong thoai thi cham roi vao ta, va do chinh la dieu Story
 * 3.6 muon: cham giu de liec nguyen ban.
 *
 * **Moi cua so ve CA TRANG roi de he thong xen theo khung cua no**, chu khong
 * ve rieng bong cua minh. Nho vay luat hai luot cua `BubbleRenderer` (to het
 * nen roi moi ve het chu) van giu nguyen y nghia: hai bong chong nhau thi ca
 * hai cua so deu cho ra dung mot ket qua, khong phu thuoc cua so nao nam tren.
 */
class TranslationOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    /** Bao ra ngoai khi nguoi dung dang giu de liec nguyen ban (Story 3.6). */
    private val onPeek: (Boolean) -> Unit = {},
    /** Cham hai cai vao mot bong thoai = muon sua ban dich cua rieng no. */
    private val onEditBubble: (Int) -> Unit = {},
    /**
     * Khung icon noi tren man hinh, va cach dua no len tren cung.
     *
     * Vi sao o day chu khong o goc lap rap: chi `sync()` moi biet luc nao mot
     * cua so MOI duoc them — ma cua so them sau luon nam tren icon (xem
     * `FloatingIcon.raise`). Goi o cuoi luot dich nhu truoc la khong du: lop
     * de thu cong ve sau do chon mat icon, va do duoc bang `dumpsys window`
     * la cua so lop de nam TREN cua so icon, nuot luon cu cham (F77).
     */
    private val iconBoxOnScreen: () -> Box? = { null },
    private val raiseIcon: () -> Unit = {},
) {

    private companion object {
        /** Giu bao lau thi coi la "liec", duoi nguong nay coi nhu cham nham. */
        const val HOLD_MS = 250L

        /** Hai cu cham cach nhau duoi chung nay thi coi la cham hai cai. */
        const val DOUBLE_TAP_MS = 400L

        /** Duoi co nay thi khong dang mot cua so. */
        const val MIN_SIDE_PX = 8
    }

    /**
     * `frameHash` cua anh da sinh ra lop phu dang hien (AD-12/AD-18).
     * **Khong** phai `contentKey` — hai hash, hai vai.
     */
    var frameHash: String? = null
        private set

    private var typeface: Typeface = Typeface.SANS_SERIF

    /** Anh chup goc — dung de lay mau mau nen bubble. */
    private var source: Bitmap? = null

    /** AD-11 — anh da bi cat status bar, nen toa do phai bu lai khi ve len man hinh. */
    private var offsetY = 0

    private val bubbles = LinkedHashMap<Int, Bubble>()
    private val panes = LinkedHashMap<Int, Pane>()

    /** Bong dang duoc giu de liec nguyen ban. `null` = khong liec. */
    private var peekedId: Int? = null
    private var visible = true

    /**
     * ⚠️ Offset phai TU DO, khong duoc gia dinh.
     *
     * Anh chup da bi cat `statusBarPx` o tren (AD-11), nen toa do bubble phai
     * cong lai chung ay de ve dung cho tren man hinh. NHUNG cua so co the DA
     * bat dau ngay duoi status bar — luc do cong them lan nua la cong HAI LAN.
     *
     * Da xay ra that (F31): lech 153 px trong khi status bar chi ~76 px, dung
     * gap doi. Hau qua nhin thay la dinh moi bong thoai con chu Nhat lo ra.
     *
     * `getLocationOnScreen` tra ve vi tri THAT cua cua so, nen cong thuc duoi
     * tu dung o ca hai truong hop.
     */
    private inner class Pane(val bubbleId: Int) : View(ctx) {
        private val loc = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            val src = source ?: return
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), (offsetY - loc[1]).toFloat())
            // Ve CA TRANG; he thong tu xen theo khung cua so nay.
            //
            // Bo dung bong dang duoc liec — o MOI cua so, khong chi cua so cua
            // no. Bong thoai chong nhau thi mot phan cua no duoc cua so ben
            // canh ve; khong bo o do thi giu de liec van con thay mot manh ban
            // dich dinh lai.
            val list = peekedId?.let { p -> bubbles.values.filter { it.id != p } }
                ?: bubbles.values.toList()
            BubbleRenderer.drawPage(canvas, list, typeface) {
                BubbleRenderer.sampleBackground(src, it)
            }
        }
    }

    /** Bat dau mot luot moi. Xoa sach lop cu TRUOC khi ve cai gi khac len. */
    fun begin(source: Bitmap, frameHash: String, statusBarPx: Int, typeface: Typeface) {
        clearPanes()
        this.source = source
        this.frameHash = frameHash
        this.offsetY = statusBarPx
        this.typeface = typeface
        bubbles.clear()
    }

    /** FR-044 — hien dan tung bubble ngay khi co, khong cho du ca man. */
    fun add(bubble: Bubble) {
        bubbles[bubble.id] = bubble
        sync()
    }

    /**
     * Thay ban dich cua mot bong da ve — nguoi dung vua sua tay.
     *
     * Khac `add` o cho no khong tao cua so moi: bong nay da co cua so roi, chi
     * can ve lai. `sync()` van chay vi chu moi co the dai/ngan hon nen khung ve
     * doi theo.
     */
    fun replace(bubble: Bubble) {
        if (bubble.id !in bubbles) return
        bubbles[bubble.id] = bubble
        sync()
    }

    /** AD-17 — go bubble da ve. Ve lai tu dau => chu Nhat goc hien lai. */
    fun retract(ids: Collection<Int>) {
        ids.forEach { bubbles.remove(it) }
        sync()
    }

    /** AD-12 — noi dung ben duoi doi, hoac ca trang bi tu choi: xoa het. */
    fun clear() {
        bubbles.clear()
        clearPanes()
        frameHash = null
        source?.let { if (!it.isRecycled) it.recycle() }
        source = null
    }

    /** AD-11 — an tam de chup. */
    fun setVisibleForCapture(visible: Boolean) {
        this.visible = visible
        applyVisibility()
    }

    val hasContent: Boolean get() = bubbles.isNotEmpty()

    // ---------- cua so theo tung bubble ----------

    private fun drawable(): List<Bubble> = bubbles.values.filter {
        it.state == BubbleState.Accepted && !it.vi.isNullOrBlank() &&
            it.box.width >= MIN_SIDE_PX && it.box.height >= MIN_SIDE_PX
    }

    /**
     * Dua danh sach cua so ve dung voi danh sach bubble dang co, roi ve lai HET.
     *
     * Ve lai het chu khong chi cua so moi: them mot bong co the lam doi ket qua
     * o cua so ben canh (hai bong chong nhau), va bo canh do la loi da tung gay
     * mat chu that.
     */
    private fun sync() {
        val want = drawable().associateBy { it.id }

        panes.keys.toList().forEach { id ->
            if (id !in want) panes.remove(id)?.let { runCatching { wm.removeView(it) } }
        }

        // Cua so nao VUA duoc them ma trum len icon thi phai dua icon len lai.
        var buriedIcon = false
        val icon = iconBoxOnScreen()

        want.forEach { (id, b) ->
            val r = BubbleRenderer.drawnRect(b)
            if (r.width < MIN_SIDE_PX || r.height < MIN_SIDE_PX) return@forEach
            val p = panes[id]
            if (p == null) {
                val pane = Pane(id)
                wirePeek(pane)
                pane.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                runCatching { wm.addView(pane, params(r)) }.onSuccess {
                    panes[id] = pane
                    if (icon != null && overlaps(onScreen(r), icon)) buriedIcon = true
                }
            } else {
                runCatching { wm.updateViewLayout(p, params(r)) }
            }
        }

        // ⚠️ CHI goi khi that su bi trum. Goi moi lan them mot bong se lam icon
        // chop giat mot lan cho moi bong — ghi chu cu o `FloatingIcon.raise`
        // noi dung ve cai gia, chi thieu ve dieu kien.
        if (buriedIcon) raiseIcon()

        panes.values.forEach { it.invalidate() }
    }

    /** Khung cua so cua mot bubble tren MAN HINH (da bu status bar). */
    private fun onScreen(r: Box) = Box(r.x1, r.y1 + offsetY, r.x2, r.y2 + offsetY)

    private fun overlaps(a: Box, b: Box) =
        a.x1 < b.x2 && a.x2 > b.x1 && a.y1 < b.y2 && a.y2 > b.y1

    /**
     * Khung MAN HINH cua moi cua so ban dich dang mo — ke ca lop de thu cong.
     *
     * Bo canh trang phai bo qua chung: day la nhung cho CHINH APP ve len, nen
     * chung khac anh goc la le duong nhien. Ban truoc chi bo qua danh sach
     * bubble do day chuyen sinh ra, nen moi lop de thu cong deu bi ket luan la
     * "nguoi dung sang trang" va xoa sach ban dich dang co (F76).
     */
    fun paneBoxesOnScreen(): List<Box> =
        panes.keys.mapNotNull { id -> bubbles[id] }
            .map { onScreen(BubbleRenderer.drawnRect(it)) }

    private fun clearPanes() {
        stopPeek()
        panes.values.forEach { runCatching { wm.removeView(it) } }
        panes.clear()
    }

    /**
     * Chi con phuc vu viec AN TAM DE CHUP (AD-11). Liec nguyen ban khong dung
     * duong nay nua — xem ghi chu o `startPeek`.
     */
    private fun applyVisibility() {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        panes.values.forEach { it.visibility = v }
    }

    // ---------- Story 3.6: cham giu de liec nguyen ban ----------

    /**
     * ⚠️ Phai GIU LAI `Runnable`, khong duoc dung `::startPeek`.
     *
     * Moi lan viet `::startPeek` Kotlin **tao mot doi tuong moi**, nen
     * `removeCallbacks(::startPeek)` go mot doi tuong khac va khong huy duoc
     * gi. Hau qua nguoi dung thay: cham nhanh mot cai vao bong thoai, 250 ms
     * sau `startPeek` van chay, ma `ACTION_UP` thi da qua roi nen khong con ai
     * goi `stopPeek` — **ca trang mat ban dich va khong tu quay lai**.
     */
    private var pending: Runnable? = null

    /** Lan cham gan nhat, de nhan ra cham hai cai (Story sua bong thoai). */
    private var lastTapAt = 0L
    private var lastTapId = -1

    @SuppressLint("ClickableViewAccessibility")
    private fun wirePeek(v: Pane) = v.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPending(v)
                val r = Runnable { startPeek(v.bubbleId) }
                pending = r
                v.postDelayed(r, HOLD_MS)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasPeeking = peekedId != null
                cancelPending(v)
                stopPeek()
                if (!wasPeeking && e.actionMasked == MotionEvent.ACTION_UP) tapped(v.bubbleId)
                true
            }
            else -> false
        }
    }

    private fun cancelPending(v: View) {
        pending?.let { v.removeCallbacks(it) }
        pending = null
    }

    private fun tapped(id: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (id == lastTapId && now - lastTapAt < DOUBLE_TAP_MS) {
            lastTapAt = 0L; lastTapId = -1
            onEditBubble(id)
        } else {
            lastTapAt = now; lastTapId = id
        }
    }

    /**
     * Liec nguyen ban CUA MOT BONG.
     *
     * ⚠️ Khong dung `visibility` de an. An chinh cai view dang nhan cu cham thi
     * Android **huy luon chuoi cham do** (gui `ACTION_CANCEL`), nen `stopPeek`
     * chay ngay lap tuc — nguoi dung thay mot cai nhay roi ban dich tro lai,
     * chu khong giu duoc. Thay vao do giu view nguyen ven va **ve thieu** dung
     * bong do; cho do trong suot nen nguyen ban ben duoi hien len.
     */
    private fun startPeek(id: Int) {
        if (peekedId == id) return
        peekedId = id
        redraw()
        onPeek(true)
    }

    private fun stopPeek() {
        if (peekedId == null) return
        peekedId = null
        redraw()
        onPeek(false)
    }

    private fun redraw() = panes.values.forEach { it.invalidate() }

    /**
     * Cua so dat theo TOA DO MAN HINH. `FLAG_LAYOUT_NO_LIMITS` de no khong bi
     * he thong day ra khoi vung status bar.
     *
     * KHONG dat `FLAG_NOT_TOUCHABLE`: xem khoi ghi chu dau lop — chinh co do
     * lam ca lop phu chi con 80% do duc.
     */
    private fun params(r: Box) = WindowManager.LayoutParams(
        r.width, r.height, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // ⚠️ Thieu co nay thi x/y duoc tinh theo vung NOI DUNG (da tru
            // status bar), nen cua so tut xuong dung bang chieu cao status bar
            // va **xen mat dinh bong thoai** — chu Nhat o dinh hien nguyen ven.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.x1
        y = r.y1 + offsetY
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
