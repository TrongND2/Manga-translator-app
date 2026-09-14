package app.mangatrans.adapters.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import app.mangatrans.domain.Box
import app.mangatrans.domain.RegionKind
import app.mangatrans.ports.Detection
import app.mangatrans.ports.PageImage
import app.mangatrans.ports.TextDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Buoc 1 — phat hien bubble.
 *
 * Model: `ogkalu/comic-text-and-bubble-detector`, ban `detector-v4-s_int8.onnx`.
 * 11.1 MB, Apache-2.0, RT-DETR-v2. Do tren PC: 0.166 s/trang (F4).
 *
 * Ban ONNX nay DA CO san hau xu ly: nhan `orig_target_sizes` va tra thang
 * box theo toa do anh goc. Khong phai tu giai ma cxcywh.
 *
 * Tien xu ly theo `preprocessor_config.json` cua model:
 *   resize 640x640 · rescale 1/255 · do_normalize = FALSE (khong tru mean/std)
 */
class OnnxTextDetector(
    private val modelPath: String,
    private val inputSize: Int = 640,
) : TextDetector, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * Session nap LUOI va **nap lai duoc**.
     *
     * Truoc day no la `val` tao trong ham khoi tao: dong mot lan la hong han,
     * nen khong the nha ra roi lay lai. Ma nha ra dung luc la dieu can lam —
     * detector xong viec truoc khi LLM chay, va LLM la thu suyt lam app bi
     * Android giet (F37).
     *
     * Nap tu DUONG DAN, khong qua `readBytes()`: heap Java cua app bi gioi han
     * ~256 MB, doc 171 MB vao mang byte lam OOM ngay (da gap that tren M52).
     * ONNX Runtime tu mmap file, khong ton heap.
     */
    private var session: OrtSession? = null

    private fun session(): OrtSession =
        session ?: env.createSession(modelPath, OnnxOptions.lean()).also { session = it }

    private companion object {
        // id2label tu config.json cua model.
        val LABELS = mapOf(
            0 to RegionKind.Bubble,
            1 to RegionKind.TextBubble,
            2 to RegionKind.TextFree,
        )
        const val MIN_SIDE_PX = 4
    }

    override suspend fun detect(image: PageImage, minScore: Float): List<Detection> =
        withContext(Dispatchers.Default) {
            val bmp = image.handle as Bitmap
            val scaled = Bitmap.createScaledBitmap(bmp, inputSize, inputSize, true)

            // NCHW float32, gia tri [0,1]. KHONG chuan hoa mean/std.
            val px = IntArray(inputSize * inputSize)
            scaled.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
            if (scaled !== bmp) scaled.recycle()

            val plane = inputSize * inputSize
            val buf = FloatBuffer.allocate(3 * plane)
            val arr = buf.array()
            for (i in 0 until plane) {
                val p = px[i]
                arr[i] = ((p shr 16) and 0xFF) / 255f              // R
                arr[plane + i] = ((p shr 8) and 0xFF) / 255f       // G
                arr[2 * plane + i] = (p and 0xFF) / 255f           // B
            }

            val sizes = LongBuffer.wrap(longArrayOf(image.width.toLong(), image.height.toLong()))

            OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())).use { imgT ->
                OnnxTensor.createTensor(env, sizes, longArrayOf(1, 2)).use { sizeT ->
                    session().run(mapOf("images" to imgT, "orig_target_sizes" to sizeT)).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        val labels = (res[0].value as Array<LongArray>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val boxes = (res[1].value as Array<Array<FloatArray>>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val scores = (res[2].value as Array<FloatArray>)[0]

                        buildList {
                            for (i in scores.indices) {
                                val sc = scores[i]
                                if (sc < minScore) continue
                                val kind = LABELS[labels[i].toInt()] ?: continue
                                val b = boxes[i]
                                val x1 = b[0].coerceIn(0f, image.width.toFloat()).toInt()
                                val y1 = b[1].coerceIn(0f, image.height.toFloat()).toInt()
                                val x2 = b[2].coerceIn(0f, image.width.toFloat()).toInt()
                                val y2 = b[3].coerceIn(0f, image.height.toFloat()).toInt()
                                val box = Box(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
                                if (box.width < MIN_SIDE_PX || box.height < MIN_SIDE_PX) continue
                                add(Detection(box, kind, sc))
                            }
                        }
                    }
                }
            }
        }

    override fun close() {
        runCatching { session?.close() }
        session = null
    }
}

/**
 * FR-021 — thu tu doc manga: PHAI -> TRAI, TREN -> DUOI.
 *
 * Gan MOT LAN duy nhat o DetectFilter. Tang sau coi thu tu trong danh sach la
 * chan ly, khong tu sap lai (quy uoc trong spine).
 *
 * Gom cac box co tam y gan nhau thanh mot "hang", trong hang sap theo x giam dan.
 * Dung nguong tinh theo chieu rong trang de khong phu thuoc do phan giai.
 */
fun readingOrder(
    dets: List<Detection>,
    pageWidth: Int,
    rowToleranceRatio: Double = 0.06,
): List<Detection> {
    if (dets.isEmpty()) return emptyList()
    val tol = pageWidth * rowToleranceRatio
    val byY = dets.sortedBy { (it.box.y1 + it.box.y2) / 2.0 }

    val rows = mutableListOf<MutableList<Detection>>()
    var cur = mutableListOf(byY.first())
    var curY = (byY.first().box.y1 + byY.first().box.y2) / 2.0
    for (d in byY.drop(1)) {
        val cy = (d.box.y1 + d.box.y2) / 2.0
        if (kotlin.math.abs(cy - curY) <= tol) {
            cur.add(d)
        } else {
            rows.add(cur); cur = mutableListOf(d); curY = cy
        }
    }
    rows.add(cur)

    return rows.flatMap { row ->
        row.sortedByDescending { (it.box.x1 + it.box.x2) / 2.0 }
    }
}
