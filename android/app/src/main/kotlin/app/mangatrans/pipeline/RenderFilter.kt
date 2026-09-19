package app.mangatrans.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import app.mangatrans.domain.Box
import app.mangatrans.domain.Bubble
import app.mangatrans.domain.BubbleState

/**
 * Buoc 5 — Story 2.10 + 2.11. FR-040..043, FR-046.
 *
 * AD-9 la luat quan trong nhat o day:
 *   thu tu BAT BUOC la  co ban dich -> to nen -> ve chu
 *   KHONG BAO GIO to nen truoc.
 *
 * Che mat chu goc roi khong ve duoc gi con te hon khong lam gi.
 */
object BubbleRenderer {

    /** Chuoi du dau tieng Viet — dung de kiem font THAT SU ho tro, khong tin ten font. */
    const val VN_PROBE = "ắằẳẵặấầẩẫậéèẻẽẹếềểễệóòỏõọốồổỗộơớờởỡợúùủũụưứừửữựđĐ"

    /**
     * FR-043 — font phai co DU dau tieng Viet.
     *
     * Kiem bang cach render thu chuoi du dau roi so be rong voi chuoi khong dau.
     * Font thieu glyph se ve o vuong hoac bo qua, cho be rong khac han.
     */
    fun supportsVietnamese(tf: Typeface): Boolean {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = 48f }
        val withMarks = p.measureText(VN_PROBE)
        // Neu font khong co glyph, Android thay bang tofu — be rong deu nhau bat thuong.
        // Kiem them: chu co dau phai CAO hon chu khong dau.
        val r1 = Rect(); val r2 = Rect()
        p.getTextBounds("ằ", 0, 1, r1)
        p.getTextBounds("a", 0, 1, r2)
        return withMarks > 0 && r1.height() > r2.height()
    }

    /**
     * Ve ban dich cua MOT bubble len canvas.
     *
     * @param sample ham lay mau mau nen tu ben trong bubble
     * @return false neu khong ve duoc (khi do PHAI giu nguyen chu goc — AD-9)
     */
    /**
     * Ve CA TRANG bang HAI LUOT: to het nen truoc, roi moi ve het chu.
     *
     * Vi sao phai hai luot: bong thoai chong lan nhau. Neu to-va-ve tung bong
     * mot thi o nen cua bong ve SAU se DE MAT CHU cua bong ve truoc — da thay
     * that: "Đáng ghét thật đấy." bi mat chu "đấy." vi bong ben canh to de len.
     *
     * Van dung AD-9: chi to nen cho bong DA CO ban dich duoc chap nhan.
     */
    fun drawPage(
        canvas: Canvas,
        bubbles: List<Bubble>,
        typeface: Typeface,
        sample: (Box) -> Int,
    ): Int {
        val ready = bubbles.filter {
            it.state == BubbleState.Accepted && !it.vi.isNullOrBlank() &&
                it.box.width >= 8 && it.box.height >= 8
        }
        // Luot 1 — to het nen
        ready.forEach { fillBackground(canvas, it, sample) }
        // Luot 2 — ve het chu, khong con o nen nao de len nua
        return ready.count { drawText(canvas, it, typeface, sample) }
    }

    /**
     * Hop chu detector tra ve om SAT chu, va no khong phai luc nao cung om het.
     * Do thay tren anh chup man hinh (trang truyen bi thu nho trong khung 1080
     * x2400): hop hep hon chu that, nen **chu Nhat con lo ra o ria bong**.
     *
     * Noi hop ra mot chut de hap thu sai so do. Nhung PHAI kep trong vo bong
     * khi co vo — noi tu do se to de len tranh va len bong ben canh, dung loi
     * ma F26 da phai sua bon vong.
     */
    private const val PAD_RATIO = 0.08

    /**
     * Noi them TOI DA bao nhieu pixel — **chi ap cho vung KHONG co vo bong**.
     *
     * Vi sao can tran nay: phan noi them sinh ra de hap thu sai so cua detector,
     * ma sai so do tinh bang **vai pixel**, khong tinh theo phan tram. Lay 8%
     * cua mot hop cao 454 px la noi ra **36 px** moi phia — gap nhieu lan sai so
     * that.
     *
     * Khi CO vo bong thi khong sao: `padded` da kep phan noi them nam trong vo.
     * Khong co vo thi khong co gi kep, va do chinh la truong hop do duoc tren
     * may (F78): bong thoai hinh **bac thang**, app to mot hinh chu nhat bao
     * ngoai, va mang trang **tran len ca ranh den lan tranh** o goc tren-trai.
     *
     * Kep lai con 8 px thi phan tran giam tu 36 px xuong 8 px, ma van phu kin
     * hop chu — vi hop chu von da om het net chu.
     */
    private const val PAD_MAX_PX_NO_SHELL = 8

    /** Ban kinh bo goc cua o nen, tinh theo canh ngan cua vo bong. */
    private const val CORNER_RATIO = 0.30f

    private fun padded(b: Box, shell: Box?): Box {
        var px = (b.width * PAD_RATIO).toInt().coerceAtLeast(2)
        var py = (b.height * PAD_RATIO).toInt().coerceAtLeast(2)
        if (shell == null) {
            // Khong co vo bong => khong co gi kep phan noi them, va moi pixel
            // noi ra la mot pixel TRANH bi xoa. Xem `PAD_MAX_PX_NO_SHELL`.
            px = px.coerceAtMost(PAD_MAX_PX_NO_SHELL)
            py = py.coerceAtMost(PAD_MAX_PX_NO_SHELL)
            return Box(b.x1 - px, b.y1 - py, b.x2 + px, b.y2 + py)
        }

        // Kep phan NOI THEM vao trong vo bong, nhung KHONG BAO GIO cat vao
        // chinh hop chu.
        //
        // Ban dau toi viet cho nay la phep GIAO voi vo bong. Sai: cong AD-5
        // chi doi hop chu nam trong vo >= 0.9, nen 10% con lai duoc phep tho
        // ra — va phep giao cat dung phan tho ra do. Ket qua la o nen NHO HON
        // ca hop chu, tuc la ban sua lam chu goc lo ra NHIEU HON truoc.
        return Box(
            maxOf(b.x1 - px, shell.x1).coerceAtMost(b.x1),
            maxOf(b.y1 - py, shell.y1).coerceAtMost(b.y1),
            minOf(b.x2 + px, shell.x2).coerceAtLeast(b.x2),
            minOf(b.y2 + py, shell.y2).coerceAtLeast(b.y2),
        )
    }

    /**
     * Khung MA MOT BUBBLE THUC SU VE RA — hop cua vo bong va hop chu da noi.
     *
     * Co mot ham cong khai cho viec nay vi tang hien thi can dat cua so dung
     * khit vung ve. Tinh lai o cho khac la cach chac chan de hai ben troi khoi
     * nhau (F31 da day mot lan).
     */
    fun drawnRect(bubble: Bubble): Box {
        val p = padded(bubble.box, bubble.shell)
        val s = bubble.shell ?: return p
        return Box(minOf(p.x1, s.x1), minOf(p.y1, s.y1), maxOf(p.x2, s.x2), maxOf(p.y2, s.y2))
    }

    private fun fillBackground(canvas: Canvas, bubble: Bubble, sample: (Box) -> Int) {
        val b = bubble.box
        val shell = bubble.shell
        val c = sample(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c; style = Paint.Style.FILL
        }
        if (shell != null) {
            // HINH CHU NHAT BO GOC, khong phai ellipse.
            //
            // Do tren may: 11/12 bubble CO vo bong, tuc ellipse van duoc to —
            // nhung chu Nhat con sot nam o RIA bong, dung cho ellipse noi tiep
            // thu hep lai. Ellipse chi cham khung o 4 diem giua canh; phan con
            // lai cua canh bo trong.
            //
            // Chu nhat bo goc noi tiep CUNG khung thi KHONG BAO GIO vuot ra
            // ngoai vo bong — bien ngoai y het ellipse — ma phu nhieu hon han.
            // Doi hinh la duoc, khong phai danh doi "che nhieu hay it".
            val r = minOf(shell.width, shell.height) * CORNER_RATIO
            canvas.drawRoundRect(
                shell.x1.toFloat(), shell.y1.toFloat(),
                shell.x2.toFloat(), shell.y2.toFloat(), r, r, fill
            )
        }
        val r = padded(b, shell)
        canvas.drawRect(r.x1.toFloat(), r.y1.toFloat(), r.x2.toFloat(), r.y2.toFloat(), fill)
    }

    private fun drawText(
        canvas: Canvas,
        bubble: Bubble,
        typeface: Typeface,
        sample: (Box) -> Int,
    ): Boolean {
        val vi = bubble.vi ?: return false
        val b = bubble.box
        val shell = bubble.shell
        val bg = sample(b)

        val fg = if (isLight(bg)) Color.BLACK else Color.WHITE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = fg
            textAlign = Paint.Align.CENTER
        }

        // Vung dat chu: giao cua hop chu va phan an toan ben trong ellip.
        // Chu Viet dai hon chu Nhat nen neu de no lap day ca hop thi se tran
        // khoi vien bong tron (da thay that).
        val area = shell?.let { s ->
            // Hinh chu nhat noi tiep ellip: nua truc x1/sqrt(2) ~ 0.707.
            val cx = (s.x1 + s.x2) / 2.0
            val cy = (s.y1 + s.y2) / 2.0
            val hw = s.width / 2.0 * 0.70
            val hh = s.height / 2.0 * 0.70
            Box((cx - hw).toInt(), (cy - hh).toInt(), (cx + hw).toInt(), (cy + hh).toInt())
        } ?: b

        // Chan co chu theo DIEN TICH hop chu goc: chu Nhat goc chiem bao nhieu
        // cho thi chu Viet cung nen quanh do. Khong chan thi mot cau ngan nam
        // trong bong to se bi phong len rat lon (da thay that).
        val capBySource = kotlin.math.sqrt(b.area.toDouble() / maxOf(4, vi.length)) * 2.2
        val layout = fitText(vi, area, paint, capBySource.toFloat()) ?: return false
        var y = area.y1 + (area.height - layout.totalHeight) / 2f - layout.ascent
        val cx = (area.x1 + area.x2) / 2f
        for (line in layout.lines) {
            canvas.drawText(line, cx, y, paint)
            y += layout.lineHeight
        }
        return true
    }

    private data class Layout(
        val lines: List<String>, val lineHeight: Float,
        val totalHeight: Float, val ascent: Float,
    )

    /**
     * FR-042 — tu co co chu cho vua bubble, xuong dong theo RANH GIOI TU,
     * khong cat giua tu.
     */
    private fun fitText(text: String, box: Box, paint: Paint, capPx: Float = 64f): Layout? {
        val maxW = box.width * 0.92f
        val maxH = box.height * 0.92f
        var size = minOf(box.height * 0.34f, 64f, capPx).coerceAtLeast(9f)

        while (size >= 9f) {
            paint.textSize = size
            val lines = wrap(text, maxW, paint)
            val fm = paint.fontMetrics
            val lineH = (fm.descent - fm.ascent) * 1.06f
            val total = lineH * lines.size
            if (total <= maxH && lines.all { paint.measureText(it) <= maxW }) {
                return Layout(lines, lineH, total, fm.ascent)
            }
            size -= 1f
        }
        return null   // khong vua duoc => AD-9: giu nguyen chu goc
    }

    /** Xuong dong theo tu. Tu qua dai mot minh thi danh chap nhan tran. */
    private fun wrap(text: String, maxW: Float, paint: Paint): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) <= maxW || cur.isEmpty()) {
                cur = StringBuilder(test)
            } else {
                out.add(cur.toString()); cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun isLight(c: Int): Boolean =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 140

    /**
     * Lay mau nen: mau pho bien nhat trong vien bubble.
     *
     * ⚠️ **Luon tra ve mau DUC.** Anh chup man hinh khong bao gio dam bao kenh
     * alpha bang 255 — do tren M52 thi pixel tu `MediaProjection` co alpha ~212.
     * `getPixel` mang nguyen alpha do sang `Paint`, nen o nen chi to duoc ~83%,
     * va **chu Nhat goc hien mo mo xuyen qua ca ban dich**.
     *
     * Do duoc: net chu Nhat 27.5 (thang do xam) sau khi to thanh 198.6, trong
     * khi nen cua chinh lop phu la 240.6 — ty le 0.83, dung bang alpha 212/255.
     *
     * Day la loi im lang dung nghia: khong log, khong crash, chi nhin ky anh
     * moi thay. Chinh la gop y #1 cua nguoi dung.
     */
    fun sampleBackground(bmp: Bitmap, box: Box): Int = opaque(rawSample(bmp, box))

    /** Ep alpha = 255, giu nguyen ba kenh mau. */
    private fun opaque(c: Int): Int = c or 0xFF000000.toInt()

    private fun rawSample(bmp: Bitmap, box: Box): Int {
        val counts = HashMap<Int, Int>()
        val stepX = maxOf(1, box.width / 12)
        val stepY = maxOf(1, box.height / 12)
        // Lay o vien, tranh vung giua vi cho do la chu.
        for (x in box.x1 until box.x2 step stepX) {
            for (y in listOf(box.y1, box.y2 - 1)) {
                if (x in 0 until bmp.width && y in 0 until bmp.height)
                    counts.merge(bmp.getPixel(x, y), 1, Int::plus)
            }
        }
        for (y in box.y1 until box.y2 step stepY) {
            for (x in listOf(box.x1, box.x2 - 1)) {
                if (x in 0 until bmp.width && y in 0 until bmp.height)
                    counts.merge(bmp.getPixel(x, y), 1, Int::plus)
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: Color.WHITE
    }
}
