package app.mangatrans.adapters.onnx

import ai.onnxruntime.OrtSession

/**
 * Tuy chon phien ONNX dung chung cho detector va OCR — **uu tien it ton RAM**.
 *
 * Vi sao phai co file nay: do tren M52, ba mo hinh thi giac cong lai chi **213
 * MB tren dia** (detector 11 + encoder 171 + decoder 29) nhung buoc doc chu
 * chiem toi **858 MB RSS**. Tuc khoang 645 MB khong phai trong so ma la vung
 * nho ONNX Runtime tu giu lai.
 *
 * Do la vi mac dinh ONNX Runtime dung **arena allocator**: xin duoc bao nhieu
 * thi giu nguyen, khong tra lai he dieu hanh, va moi bubble lai noi them mot
 * chut. Voi app nay thi do la kieu giu sai cho — bubble chay tuan tu, xong la
 * khong can nua, ma dung cai luc giu nhieu nhat lai la luc LLM 2,6 GB cung dang
 * nam trong RAM.
 *
 * Hau qua da do duoc: `lmkd: Reclaim 'app.mangatrans' ... to free 2797960kB
 * rss ... min2x watermark is breached even after kill` — app bi giet ngay giua
 * buoc doc chu (F37).
 *
 * Danh doi: tat arena thi moi lan cap phat deu phai hoi he dieu hanh, cham hon
 * mot chut. Cham hon van hon bi giet.
 */
internal object OnnxOptions {

    /**
     * ⚠️ Moi phien PHAI tao mot `SessionOptions` RIENG. Dung lai mot the hien
     * cho nhieu phien la dung sau khi da `close()` — ONNX Runtime nem
     * `IllegalStateException`.
     */
    fun lean(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        // Tra bo nho ve he dieu hanh ngay sau moi lan chay, khong giu arena.
        setCPUArenaAllocator(false)
        // Khong cap phat truoc theo "hinh mau" bo nho cua lan chay truoc. Hinh
        // mau chi co ich khi kich thuoc dau vao lap lai; o day moi bubble mot
        // co khac nhau nen no chi doi cho.
        setMemoryPatternOptimization(false)
    }
}
