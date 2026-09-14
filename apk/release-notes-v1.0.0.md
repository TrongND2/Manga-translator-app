Bản đầu tiên dùng được đầu-cuối. Dịch manga Nhật → Việt **ngay trên máy**, không server, không tài khoản.

Mở trang truyện → chạm icon nổi → bản dịch hiện đè lên, ngay trong bóng thoại.

## Tải bản nào

| File | Dùng cho |
|---|---|
| `MangaTranslator-v1.0.0-arm64.apk` | **Mọi điện thoại thật** |
| `MangaTranslator-v1.0.0-x86_64.apk` | Chỉ máy ảo Android |

Yêu cầu: Android 10 trở lên, ~4 GB dung lượng trống, nên có 6 GB RAM trở lên.

APK ký bằng khoá debug — cài tay được, Android sẽ hỏi cho phép "cài từ nguồn không xác định".

## Lần đầu dùng

1. Mở app → **Bật icon dịch màn hình** (Android bắt tự bật trong Cài đặt)
2. Mở app đọc truyện, **chạm icon** → cho phép chụp màn hình
3. **Chạm icon lần nữa** → dịch

App sẽ tải gói mô hình 2,7 GB lần đầu. Nên dùng Wi-Fi; tải tạm dừng và tiếp tục được, rớt mạng thì lần sau tải tiếp chỗ dở.

**Trang đầu mất khoảng 190 giây.** Phần lớn thời gian đó là mô hình đọc hết đề bài trước khi sinh chữ đầu tiên — không phải app treo. Trang đã dịch rồi thì chỉ 0,1 giây.

## Ba cử chỉ

- **Chạm icon** → dịch trang đang hiện
- **Giữ icon** → 📖 hướng dẫn · ✕ tắt app
- **Chạm giữ vào bóng thoại** → chữ Nhật gốc hiện lại trong lúc giữ

## Nên biết trước

- **Chữ hiệu ứng ngoài bóng thoại vẫn là tiếng Nhật** — bộ nhận diện gần như không bắt được loại đó
- **Máy dưới 6 GB RAM** nhiều khả năng bị Android tắt app giữa chừng; app cảnh báo trước khi tải
- **App đặt `FLAG_SECURE` thì không chụp được** — giới hạn của Android, không có cách vòng
- Khoá màn hình làm dừng phiên chụp; mở lại thì chạm icon một cái để cấp lại quyền
- Máy nóng khoảng 58–62 °C khi dịch liên tục

Chi tiết thay đổi: [CHANGELOG.md](CHANGELOG.md) · Những gì đo được và những giả định bị bác bỏ: [spike/FINDINGS.md](spike/FINDINGS.md)
