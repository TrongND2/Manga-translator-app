# Manga Translator — Nhật → Việt, chạy hoàn toàn offline

App Android dịch manga tiếng Nhật sang tiếng Việt **ngay trên máy**, không cần internet sau khi tải gói mô hình một lần. Không server, không API trả phí.

<p align="center">
  <em>Chọn một trang manga → nhận lại trang đã thay chữ Nhật bằng chữ Việt, ngay trong bóng thoại.</em>
</p>

---

## Trạng thái

**Epic 1 xong · Epic 2 gần xong.** Pipeline chạy được đầu-cuối trên máy thật (Samsung Galaxy M52).

| | Kết quả đo trên Galaxy M52 |
|---|---|
| Dịch | 12/12 bubble, ~11/12 đúng nghĩa |
| Thời gian, lần đầu | ~190 s / trang |
| Thời gian, đã cache | **1.3 s** |
| RAM | ~3.2 GB (mmap, không bị OOM-kill) |
| Nhiệt | 58–62 °C (2 luồng CPU) |
| APK | 50.9 MB |
| Unit test | 36 / 36 xanh |

---

## Pipeline

```
Ảnh trang manga
  │
  ├─1─ TextDetector    RT-DETR-v2 int8, 11 MB      → tìm bóng thoại, sắp thứ tự đọc phải→trái
  ├─2─ Gate            text_bubble ⊂ bubble ≥ 0.9  → chặn vùng không có thoại
  ├─3─ OcrEngine       manga-ocr ONNX, 117 MB      → đọc chữ dọc + ngang, chuẩn hoá NFKC
  ├─4─ Translator      Gemma 4 E2B qua LiteRT-LM   → CẢ TRANG trong MỘT lần gọi
  │                    + cổng toàn vẹn id
  └─5─ Render          tô nền che chữ gốc → vẽ chữ Việt
```

### Ba bất biến không được phá

1. **Cả trang trong một lần gọi LLM.** Đo được: nhanh hơn dịch-từng-ô **3.3 lần**, và là cách duy nhất dịch đủ 48/48 bubble.
2. **Đầu ra của model là dữ liệu không đáng tin cho tới khi qua cổng kiểm tra.** JSON hợp lệ và đủ số phần tử *không* tính là đã kiểm tra.
3. **Thoái lui luôn là chữ gốc, không bao giờ là ô trống.** Chỉ tô nền che chữ Nhật khi đã có bản dịch được chấp nhận cho đúng bubble đó.
4. **Vẽ hai lượt trên toàn trang** — tô hết nền, rồi mới vẽ hết chữ. Bóng thoại chồng lấn nhau, nên tô-rồi-vẽ từng cái sẽ khiến nền bubble sau xoá mất chữ bubble trước.

---

## Hai lỗi nguy hiểm nhất đã tìm ra

Cả hai đều **qua được mọi kiểm tra tự động** và **đọc rất trôi chảy** — đó là điều làm chúng nguy hiểm.

**Thoại bịa.** manga-ocr là mô hình image→text, không có đầu ra "chỗ này không có chữ" và không trả điểm tin cậy. Đưa cho nó một mảnh tranh, nó **vẫn sinh ra câu tiếng Nhật trông hợp lý**. Thoại bịa sẽ được dịch trôi chảy rồi vẽ đè vào bubble, và người đọc không có cách nào phát hiện.
→ Chặn bằng cổng `text_bubble ⊂ bubble`.

**Lệch id.** Model trả về đủ 12 bubble, id chạy đúng 0→11, `done_reason: stop`, mọi chỉ số đều xanh — nhưng toàn bộ bản dịch **lệch đi một ô** từ bubble thứ tư. Người đọc thấy mọi bubble đều trôi chảy mà cả trang sai mạch hội thoại.
→ Chặn bằng cổng `jaEcho`: model chép lại 2 ký tự đầu nguyên bản, app đối chiếu sau khi chuẩn hoá NFKC và cho phép sai ≤ 1 ký tự. Đo được: **0 báo động giả, bắt 11/11 ô lệch**.

---

## Bảy giả định ban đầu, sáu sai

Dự án bắt đầu bằng một Project Brief. Sau khi đo thật trên phần cứng thật:

| Giả định ban đầu | Thực tế đo được |
|---|---|
| Model càng to dịch càng tốt | **Sai 2 lần** — Gemma 4 E2B (2.6 GB) thắng Gemma 3 4B (3.3 GB) và bằng Qwen3 8B (5.2 GB) |
| Họ Qwen mạnh tiếng Nhật nên chọn Qwen | **Sai** — Gemma 4 thắng rõ |
| MediaPipe là runtime LLM | **Đã khai tử** — thay bằng LiteRT-LM |
| GPU sẽ tăng tốc | **Sai** — GPU khởi tạo được, nhanh gấp 4, nhưng **sinh ra rác** trên Adreno 642L |
| Glossary là tinh chỉnh giai đoạn sau | **Sai** — là đòn bẩy chất lượng mạnh nhất (46% → 60%), đã nâng lên MVP |
| Ngưỡng 40 s/trang | **Bất khả thi** trên phần cứng này |
| Gói ~3 GB cần 8 GB RAM | Gần đúng |

Toàn bộ 27 phát hiện, mỗi cái kèm phép đo, ở [`spike/FINDINGS.md`](spike/FINDINGS.md).

---

## Thành phần

| Bước | Model | Dung lượng | Giấy phép |
|---|---|---|---|
| Detector | [`ogkalu/comic-text-and-bubble-detector`](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) `detector-v4-s_int8` | 11.1 MB | Apache-2.0 |
| OCR | [`onnx-community/manga-ocr-base-ONNX`](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) | 117 MB (int8) | Apache-2.0 |
| Dịch | [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | 2.59 GB | Apache-2.0 |

Tổng gói tải về ≈ **2.7 GB**. Không model nào bị khoá quyền truy cập.

---

## Chạy thử

**Yêu cầu:** Android 10+, ~4 GB dung lượng trống, JDK 25 (đi kèm Android Studio).

```powershell
# 1. Tải mô hình về máy tính
cd spike
python -m venv .venv
.\.venv\Scripts\pip install huggingface_hub
.\.venv\Scripts\python fetch_gemma4.py

# 2. Đẩy lên điện thoại
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb push spike/models/gemma-4-E2B-it.litertlm  /data/local/tmp/
& $adb push spike/models/detector-v4-s_int8.onnx  /data/local/tmp/
& $adb push spike/models/onnx/encoder_model_fp16.onnx /data/local/tmp/
& $adb push spike/models/onnx/decoder_model_int8.onnx /data/local/tmp/
& $adb push spike/models/vocab.txt                /data/local/tmp/

# 3. Build và cài
cd android
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew :app:assembleDebug
& $adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

> **Lưu ý về ONNX Runtime:** bản Android **không có** toán tử `ConvInteger`, nên phải dùng encoder `fp16` thay vì `int8`. Lỗi này chỉ lộ ra trên máy thật — trên PC chạy bình thường.

---

## Cấu trúc

```
android/
  app/                 app thật
    domain/            PageJob bất biến, Bubble, BubbleState
    pipeline/          5 filter + EchoGate + RenderFilter
    ports/             6 interface — nơi ràng buộc kiến trúc được ép vào chữ ký hàm
    adapters/
      onnx/            detector + OCR
      litertlm/        NƠI DUY NHẤT được import LiteRT-LM
      storage/         glossary, cache, hash
  bench/               app đo hiệu năng trên máy thật

spike/                 script đo trên PC — không phải code app
  FINDINGS.md          27 phát hiện, mỗi cái kèm phép đo
docs/brief.md          Project Brief ban đầu
_bmad-output/          PRD · architecture spine (25 AD) · 4 epic / 32 story
```

---

## Giới hạn đã biết

- **Chữ SFX ngoài bóng thoại không được dịch.** Detector gần như không bắt được lớp `text_free` — 1 box trên 6 trang.
- **GPU không dùng được trên Adreno 642L.** Khởi tạo thành công, nhanh gấp 4, nhưng sinh ra rác. Code vẫn giữ đường GPU cho máy khác, mặc định là CPU.
- **Bubble đầu tiên mất ~90 giây.** 91% là prefill — model phải đọc hết prompt trước khi sinh token nào. Giới hạn của transformer, không sửa được bằng code. Mọi cách chia nhỏ đã đo và không cứu được.
- **Chưa có overlay.** Nguồn ảnh hiện là file; chụp màn hình là Epic 3.
- **Số đo đến từ một bộ truyện**, thuộc loại tiếng Nhật khó (khẩu ngữ cổ trang, tiếng lóng). Không suy ra cho manga nói chung.

---

## Pháp lý

App **chỉ xử lý nội dung đang hiển thị trên máy người dùng**. Không tải, không lưu trữ, không phân phối nội dung có bản quyền, không tích hợp bất kỳ nguồn truyện lậu nào.

Ảnh truyện dùng để test **không nằm trong repo này**.
