# Project Brief — Manga Translator (JA → VI, Offline-first)

> Tài liệu này là đầu ra bước Analyst của BMAD. Đầu vào cho `/pm` để viết PRD.
> Ngày: 2026-09-13

---

## 1. Tóm tắt

Ứng dụng Android cho phép người đọc manga tiếng Nhật xem bản dịch tiếng Việt **ngay trên màn hình đang đọc**, hoạt động **hoàn toàn offline** sau khi tải gói mô hình một lần.

Người dùng bật một bong bóng nổi (floating overlay), app chụp màn hình hiện tại, nhận diện các khung thoại, đọc chữ tiếng Nhật, dịch sang tiếng Việt và vẽ đè bản dịch lên đúng vị trí bubble.

---

## 2. Vấn đề

- Lượng manga được dịch sang tiếng Việt rất nhỏ so với lượng phát hành tại Nhật; bản dịch thường chậm hàng tháng đến hàng năm.
- Các công cụ dịch phổ thông (ML Kit Translate, Google Lens) thất bại với manga vì:
  - OCR được huấn luyện cho chữ in **ngang**, trong khi manga dùng chữ **dọc** (縦書き), font viết tay, có furigana.
  - Dịch máy câu-đơn-lẻ không có ngữ cảnh, trong khi tiếng Nhật lược chủ ngữ liên tục và tiếng Việt bắt buộc phải chọn đại từ xưng hô.
- Các giải pháp chất lượng tốt hiện có (manga-image-translator, BallonsTranslator) đều chạy trên desktop/server, không dùng được khi đang đọc trên điện thoại.

---

## 3. Người dùng mục tiêu

**Chính:** Người đọc manga raw tiếng Nhật trên điện thoại Android, không biết hoặc biết ít tiếng Nhật, muốn đọc ngay không chờ nhóm dịch.

**Phụ:** Người học tiếng Nhật muốn đối chiếu nguyên bản và bản dịch.

**Đặc điểm kỹ thuật của nhóm chính:** dùng điện thoại tầm trung–cao, quan tâm đến việc không tốn tiền và không cần mạng.

---

## 4. Mục tiêu & chỉ số thành công

| Mục tiêu | Chỉ số | Ngưỡng MVP |
|---|---|---|
| Dịch được offline | Tỷ lệ chức năng hoạt động khi tắt mạng | 100% luồng chính |
| Đọc chữ chính xác | Character accuracy của OCR trên bộ test 100 trang | ≥ 90% |
| Dịch đọc được | Đánh giá người thật: "hiểu được mạch truyện" | ≥ 80% số bubble |
| Tốc độ chấp nhận được | Thời gian từ lúc chụp đến khi hiện đủ bản dịch | ≤ 40s/màn trên máy 8GB RAM |
| Không cản trở đọc | Bản dịch hiện dần theo từng bubble, không chặn UI | Bubble đầu tiên hiện ≤ 8s |

---

## 5. Phạm vi

### Trong phạm vi MVP
- Bong bóng nổi (floating overlay) bật/tắt được.
- Chụp màn hình qua MediaProjection.
- Phát hiện vùng chữ + khung thoại.
- OCR tiếng Nhật hỗ trợ chữ dọc và chữ ngang.
- Dịch JA→VI bằng LLM chạy trên máy, có ngữ cảnh toàn màn hình.
- Vẽ đè bản dịch lên vị trí bubble gốc.
- Màn hình tải gói mô hình lần đầu (có thể tạm dừng/tiếp tục).
- Cache kết quả theo hash ảnh để không dịch lại màn đã dịch.

### Ngoài phạm vi MVP
- Chế độ cloud/API. **Không phải mục tiêu của dự án.** Vẫn tách sẵn interface `Translator` để không tự khoá mình về sau, nhưng sẽ không cài đặt.
- Nhập file zip/cbz và trình đọc truyện riêng.
- Chụp bằng camera để dịch truyện giấy.
- Inpainting xoá nền chữ gốc bằng AI (MVP chỉ fill màu nền bubble).
- Ngôn ngữ khác ngoài JA→VI.
- iOS.

---

## 6. Ràng buộc kỹ thuật

| Ràng buộc | Chi tiết |
|---|---|
| Offline bắt buộc | Sau khi tải gói, mọi bước phải chạy on-device |
| **Chi phí vận hành = 0** | Không server, không API trả phí, không dịch vụ đám mây. Mọi thành phần phải miễn phí và chạy trên máy người dùng |
| **Chi phí phát triển ≈ 0** | Chỉ dùng công cụ miễn phí: Android Studio, Kotlin, model open-weights. Spike chạy trên PC cá nhân (CPU), không thuê GPU |
| Dung lượng | APK ≤ 100MB; mô hình tải riêng sau cài đặt, ~1.2GB (gói nhẹ) đến ~3GB (gói chất lượng) |
| Phần cứng tối thiểu | Android 10+, 6GB RAM cho gói nhẹ, 8GB RAM cho gói chất lượng |
| MediaProjection | Android 14+ bắt buộc foreground service type `mediaProjection`; Android 15+ yêu cầu người dùng xác nhận lại mỗi phiên |
| Overlay | Cần quyền `SYSTEM_ALERT_WINDOW` |
| Giới hạn không vượt qua được | Không chụp được màn hình của app đặt `FLAG_SECURE` |
| Font | Font đích phải có đầy đủ dấu tiếng Việt (Anime Ace và đa số font comic phương Tây KHÔNG có) |

---

## 7. Kiến trúc đề xuất

Pipeline 5 bước, mỗi bước là một thành phần thay thế được:

```
Screenshot (MediaProjection)
   │
   ├─1─> TextDetector      : tìm bounding box vùng chữ / bubble
   │                         comic-text-detector → ONNX, ~20MB
   │
   ├─2─> OcrEngine         : đọc chữ JA (dọc + ngang)
   │                         manga-ocr → ONNX int8, ~120MB
   │
   ├─3─> Translator        : JA→VI, nhận CẢ MÀN HÌNH làm ngữ cảnh
   │                         LLM 1–4B q4 chạy local (llama.cpp / MediaPipe)
   │                         ⚠️ CHỈ có impl Local. Interface để ngỏ, không làm Cloud
   │
   ├─4─> BackgroundCleaner : xoá chữ gốc (MVP: fill màu nền bubble)
   │
   └─5─> Typesetter        : vẽ chữ Việt vào bubble, auto co cỡ chữ, wrap
                             Canvas overlay
```

**Quyết định kiến trúc quan trọng:** bước 3 nhận toàn bộ các bubble của một màn hình trong **một lần gọi duy nhất**, kèm thứ tự đọc phải→trái và một glossary tên nhân vật do người dùng/app tích luỹ. Dịch từng bubble riêng lẻ là nguyên nhân số một khiến bản dịch sai xưng hô và mất mạch.

**Lựa chọn mô hình dịch:** ưu tiên họ Qwen (mạnh tiếng Nhật) ở mức 4B lượng tử 4-bit cho gói chất lượng, 1–2B cho gói nhẹ. Cần benchmark thực tế ở Phase 0.

---

## 8. Rủi ro & giả định PHẢI kiểm chứng trước khi viết PRD chi tiết

> Đây là các giả định mà nếu sai sẽ làm đổ toàn bộ kế hoạch. Phase 0 tồn tại để trả lời chúng.

| # | Giả định | Rủi ro nếu sai | Cách kiểm chứng |
|---|---|---|---|
| R1 | manga-ocr convert sang ONNX int8 vẫn giữ ≥90% độ chính xác | Mất trụ cột OCR, phải tìm giải pháp khác | Convert, chạy trên 100 trang test, đo CER |
| R2 | LLM 4B q4 dịch JA→VI manga đủ tốt | Phải chấp nhận cloud, phá lời hứa offline | Lấy 50 đoạn thoại thật, chấm điểm thủ công |
| R3 | Máy 8GB RAM chạy được 4B q4 mà không bị OOM-kill | Phải hạ xuống 1–2B, chất lượng giảm | Đo trên máy thật, theo dõi RAM |
| R4 | Tốc độ ≤ 40s/màn | Trải nghiệm không dùng được | Đo tok/s thực tế |
| R5 | Người dùng chấp nhận tải 3GB | Tỷ lệ bỏ cuộc cao ở onboarding | Khảo sát / thiết kế 2 mức gói |

---

## 9. Lộ trình

- **Phase 0 — Spike (ưu tiên cao nhất, ~1 tuần).** Chạy trên PC + 1 máy Android thật để trả lời R1–R4. Không viết UI. Kết quả spike quyết định nội dung PRD.
- **Phase 1 — Pipeline chạy được.** App tối giản: chọn 1 ảnh từ máy → ra ảnh đã dịch. Chưa có overlay. Mục đích: gỡ lỗi pipeline dễ dàng.
- **Phase 2 — Overlay.** MediaProjection + floating bubble + foreground service.
- **Phase 3 — Chất lượng dịch.** Glossary nhân vật, thứ tự đọc, prompt tinh chỉnh, cache.
- **Phase 4 — Typesetting đẹp.** Auto co cỡ chữ, wrap theo âm tiết, font comic có dấu tiếng Việt.
- **Phase 5 — Mở rộng.** Chế độ cloud tuỳ chọn, nhập zip/cbz, trình đọc riêng.

---

## 10. Pháp lý & chính sách

- App **chỉ xử lý nội dung đang hiển thị trên máy người dùng**, không tải/lưu trữ/phân phối nội dung có bản quyền. Cần nêu rõ trong mô tả store và điều khoản sử dụng.
- Không tích hợp nguồn truyện lậu — đây là lý do bị gỡ khỏi Play Store và là rủi ro pháp lý thật.
- **Rà giấy phép trước khi nhúng bất cứ thứ gì.** Các project tham khảo (`manga-image-translator`, `BallonsTranslator`) dùng giấy phép copyleft họ GPL — cần tự xác minh lại bản hiện hành. Nếu đúng là GPL thì đọc/học được thoải mái, nhưng nhúng vào sản phẩm đóng sẽ buộc phải mở mã nguồn theo.
- Từng model thành phần có giấy phép riêng và KHÁC với giấy phép của repo chứa nó. Phải kiểm tra riêng: model detector, manga-ocr, và LLM dịch (họ Qwen thường Apache-2.0; họ Gemma có điều khoản sử dụng riêng cần đọc kỹ nếu định phát hành).
- Nếu phát hành lên Play Store: tài khoản nhà phát triển có phí đăng ký một lần. Nếu không chi được khoản này, phương án là phát hành APK trực tiếp hoặc qua F-Droid/GitHub Releases.
- MediaProjection: phải hiện thông báo rõ ràng khi đang chụp màn hình theo yêu cầu của Google Play.

---

## 11. Câu hỏi mở cần chốt

1. Mức gói mặc định: ép dùng gói chất lượng 3GB, hay cho chọn nhẹ/nặng ngay ở onboarding?
2. Có hỗ trợ máy dưới 6GB RAM không? Nếu có thì bằng cách nào (chấp nhận chất lượng thấp, hay chặn cài)?
3. ~~Mô hình kinh doanh~~ — ĐÃ CHỐT: miễn phí hoàn toàn, không API trả phí, không chi phí vận hành.
4. Mục tiêu phát hành: Play Store công khai (tốn phí đăng ký một lần), hay chỉ APK cá nhân / GitHub Releases (miễn phí)?
5. Host gói mô hình ~3GB ở đâu miễn phí? Phương án khả dĩ: tải thẳng từ Hugging Face về máy người dùng thay vì tự dựng CDN.
