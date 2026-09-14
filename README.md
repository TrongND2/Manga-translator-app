# Manga Translator — Nhật → Việt, chạy hoàn toàn trên máy

Dịch manga tiếng Nhật sang tiếng Việt **ngay trên điện thoại Android**. Không server, không tài khoản, không API trả phí. Sau khi tải gói mô hình một lần, tắt mạng vẫn dịch được.

> Mở trang truyện → chạm icon nổi → bản dịch hiện đè lên, ngay trong bóng thoại.

**[⬇ Tải APK](../../releases/latest)** · Android 10+ · ~4 GB trống

---

## Dùng thế nào

1. Cài APK, mở app → **Bật icon dịch màn hình** (Android bắt tự bật trong Cài đặt)
2. Mở app đọc truyện, **chạm icon** → cho phép chụp màn hình
3. **Chạm icon lần nữa** → dịch

Ba cử chỉ: **chạm** để dịch · **giữ icon** ra 📖 hướng dẫn và ✕ tắt · **chạm giữ vào bóng thoại** để liếc chữ Nhật gốc.

Lần đầu app tải gói mô hình 2,7 GB. Tải tạm dừng và tiếp tục được; rớt mạng thì lần sau tải tiếp chỗ dở.

---

## Đo trên Galaxy M52 (Snapdragon 778G, 8 GB RAM, Android 13)

| | |
|---|---|
| Dịch | 12/12 bóng thoại, ~11/12 đúng nghĩa |
| Trang đầu | ~190 s (91% là prefill — giới hạn của transformer) |
| Trang đã dịch rồi | **0,1 s** |
| RAM khi chạy | ~3,2 GB |
| Nhiệt | 58–62 °C |
| APK | 51 MB |

---

## Cách hoạt động

```
Ảnh màn hình
  ├─ RT-DETR-v2 int8, 11 MB   → tìm bóng thoại, sắp thứ tự đọc phải→trái
  ├─ Cổng: text ⊂ bubble      → chặn vùng không có thoại
  ├─ manga-ocr ONNX, 117 MB   → đọc chữ dọc + ngang
  ├─ Gemma 4 E2B (LiteRT-LM)  → CẢ TRANG trong MỘT lần gọi + cổng toàn vẹn id
  └─ Vẽ đè                     → tô nền, vẽ chữ Việt
```

| Thành phần | Nguồn | Giấy phép |
|---|---|---|
| Detector | [`ogkalu/comic-text-and-bubble-detector`](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) | Apache-2.0 |
| OCR | [`onnx-community/manga-ocr-base-ONNX`](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) | Apache-2.0 |
| Dịch | [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | Apache-2.0 |

---

## Thứ đáng đọc nhất: [`spike/FINDINGS.md`](spike/FINDINGS.md)

36 phát hiện, mỗi cái kèm phép đo. Vài thứ có thể tiết kiệm cho bạn nhiều ngày:

- **GPU trên Adreno 642L khởi tạo được, nhanh gấp 4, và sinh ra rác.** `benchmark()` chỉ trả chỉ số hiệu năng — không có chỉ số đúng/sai. Kết luận "GPU chạy được" dựa trên tok/s là sai.
- **ONNX Runtime bản Android không có `ConvInteger`**, nên mọi bản int8 của manga-ocr đều chết. Chỉ lộ trên máy thật; trên PC chạy bình thường.
- **Từ Android 14, không được khởi động foreground service loại `mediaProjection` trước khi có quyền chụp** — mà `getMediaProjection()` lại đòi service đã ở loại đó. Vòng tròn, và lối thoát.
- **Hai lớp lỗi "sai mà nhìn như đúng":** OCR bịa ra thoại ở vùng không có chữ, và bản dịch lệch đi một bóng. Cả hai qua được mọi kiểm tra tự động và đọc rất trôi chảy.
- **Bảy giả định ban đầu, sáu sai.** Model to hơn không dịch tốt hơn — Gemma 4 E2B (2,6 GB) thắng Gemma 3 4B (3,3 GB).

---

## Giới hạn đã biết

- **Chữ hiệu ứng ngoài bóng thoại vẫn là tiếng Nhật** — detector gần như không bắt được loại đó.
- **Máy dưới 6 GB RAM** nhiều khả năng bị Android tắt app giữa chừng. App cảnh báo trước khi tải chứ không chặn.
- **App đặt `FLAG_SECURE` thì không chụp được** — giới hạn nền tảng, không có cách vòng. App báo rõ lý do.
- **Android 14+ cho chọn "chia sẻ một app"** thay vì cả màn hình — chưa kiểm đường này.
- **Số đo đến từ một bộ truyện** thuộc loại tiếng Nhật khó (khẩu ngữ cổ trang, tiếng lóng). Không suy ra cho manga nói chung.

---

## Tự build

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
cd android
.\gradlew :app:assembleDebug
```

APK ở `android/app/build/outputs/apk/debug/`. Bản `arm64-v8a` cho máy thật, `x86_64` cho máy ảo.

---

## Pháp lý

App **chỉ xử lý nội dung đang hiển thị trên máy người dùng**. Không tải, không lưu trữ, không phân phối nội dung có bản quyền, không tích hợp bất kỳ nguồn truyện nào. Ảnh truyện dùng để test không nằm trong repo này.
