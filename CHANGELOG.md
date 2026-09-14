# Nhật ký thay đổi

Dự án dùng [phiên bản ngữ nghĩa](https://semver.org/lang/vi/). `versionCode` trong APK là số mà gói mô hình đối chiếu để biết có tương thích không.

---

## 1.0.0 — 2026-09-14

Bản đầu tiên dùng được đầu-cuối. Đã kiểm bằng mắt trên Samsung Galaxy M52 (Android 13) và máy ảo Android 16.

### Dịch màn hình đang đọc

- Icon nổi trên mọi app, kéo được, tự nép mép, hiện 7 trạng thái
- **Một chạm là dịch cả trang** — chụp → phát hiện bóng thoại → OCR → dịch → vẽ đè
- Bản dịch hiện **dần từng bóng**, không chờ xong cả trang
- **Giữ icon** → 📖 hướng dẫn · ✕ tắt. ✕ cố ý để xa hơn: chạm nhầm là mất phiên chụp
- **Chạm giữ vào bóng thoại** → chữ Nhật gốc hiện lại trong lúc giữ
- **Sang trang thì lớp phủ tự biến mất** — thà mất bản dịch còn hơn hiện bản dịch sai chỗ
- Ngoài vùng bóng thoại, lớp phủ không chặn thao tác của app bên dưới
- Tắt app là trang truyện trở lại nguyên bản — app chưa bao giờ sửa nội dung app khác

### Chất lượng dịch

- **Cả trang trong một lần gọi LLM** — nhanh hơn dịch từng ô 3,3 lần, và là cách duy nhất dịch đủ 48/48 bóng
- **Cổng toàn vẹn id**: mô hình chép lại 2 ký tự đầu nguyên bản, app đối chiếu sau chuẩn hoá NFKC. Đo được: 0 báo động giả, bắt 11/11 ô lệch
- **Cổng chặn thoại bịa**: vùng chữ phải nằm trong bóng thoại ≥ 90%
- Bóng nào không dịch được thì **giữ nguyên chữ gốc**, không bao giờ để ô trống
- **Từ điển riêng**: tên nhân vật, thành ngữ, xưng hô. App tự đề xuất nhưng **hỏi trước khi dùng**
- Xưng hô đổi theo ngữ cảnh, không dịch sót chữ Nhật

### Cài đặt lần đầu

- Giải thích **từng quyền** bằng tiếng người trước khi hiện hộp thoại hệ thống
- Tải gói mô hình 2,7 GB, **tạm dừng và tiếp tục được**; rớt mạng thì tải tiếp chỗ dở
- Kiểm SHA-256 từng file; file hỏng thì tải lại đúng file đó
- Cảnh báo kèm con số cụ thể nếu máy dưới 6 GB RAM — người dùng tự quyết
- Xoá gói mô hình mà **không** mất từ điển riêng và các trang đã dịch

### Hiệu năng

- Trang đầu ~190 s · trang đã dịch rồi **0,1 s** (cache)
- 2 luồng CPU: 58–62 °C thay vì 76–84 °C, chất lượng không đổi
- Engine là singleton cấp tiến trình — trước đó Activity và Service mỗi bên nạp một bộ, 2 × 2,6 GB trên máy 8 GB

### Giới hạn đã biết

- Chữ hiệu ứng ngoài bóng thoại vẫn là tiếng Nhật
- Đường "chia sẻ một app" của Android 14+ chưa kiểm
- APK ký bằng khoá debug — cài tay được, không lên Play Store được
