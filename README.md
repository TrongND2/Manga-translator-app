# Manga Translator

Dịch manga tiếng Nhật sang tiếng Việt **ngay trên điện thoại Android**.

Mở trang truyện → chạm icon nổi → bản dịch hiện đè lên, ngay trong bóng thoại.

Không server, không tài khoản. Sau khi tải gói mô hình một lần, **tắt mạng vẫn dịch được**. Ảnh màn hình và bản dịch không rời khỏi máy.

---

## Máy cần gì

| | |
|---|---|
| Android | 10 trở lên |
| Dung lượng trống | ~4 GB (gói mô hình 2,7 GB, tải một lần) |
| RAM | nên có 6 GB trở lên |

## Cách dùng

1. [Tải APK](../../releases/latest), cài vào máy, mở app → **Bật icon dịch màn hình**
2. Mở app đọc truyện, **chạm icon** → cho phép chụp màn hình
3. **Chạm icon lần nữa** → dịch

Ba cử chỉ:

| | |
|---|---|
| **Chạm icon** | dịch trang đang hiện |
| **Giữ icon** | 📖 hướng dẫn · ✕ tắt app |
| **Chạm giữ vào bóng thoại** | xem lại chữ Nhật gốc |

> Trang đầu mất khoảng 3 phút — mô hình phải đọc hết trang trước khi dịch, không phải app treo. Trang đã dịch rồi thì gần như tức thì.

---

<sub>Dùng ba mô hình nguồn mở giấy phép Apache-2.0: [detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) · [manga-ocr](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) · [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)</sub>

## Pháp lý

App **chỉ xử lý nội dung đang hiển thị trên máy người dùng**. Không tải, không lưu trữ, không phân phối nội dung có bản quyền, không tích hợp bất kỳ nguồn truyện nào.
