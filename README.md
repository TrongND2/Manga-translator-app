# Manga Translator

Dịch manga tiếng Nhật sang tiếng Việt **ngay trên điện thoại Android**.

Mở trang truyện → chạm icon nổi → bản dịch hiện đè lên, ngay trong bóng thoại.

Không server, không tài khoản, không API trả phí. Sau khi tải gói mô hình một lần, **tắt mạng vẫn dịch được**. Ảnh màn hình và bản dịch không rời khỏi máy.

**[⬇ Tải APK](../../releases/latest)**

---

## Yêu cầu

- Android 10 trở lên
- ~4 GB dung lượng trống (gói mô hình 2,7 GB, tải một lần)
- Nên có 6 GB RAM trở lên

## Dùng thế nào

1. Cài APK, mở app → **Bật icon dịch màn hình**
2. Mở app đọc truyện, **chạm icon** → cho phép chụp màn hình
3. **Chạm icon lần nữa** → dịch

Ba cử chỉ:

- **Chạm icon** → dịch trang đang hiện
- **Giữ icon** → 📖 hướng dẫn · ✕ tắt app
- **Chạm giữ vào bóng thoại** → xem lại chữ Nhật gốc

## Nên biết trước

- **Trang đầu mất khoảng 3 phút.** Phần lớn là mô hình đọc đề bài trước khi sinh chữ đầu tiên — không phải app treo. Trang đã dịch rồi thì gần như tức thì.
- **Chữ hiệu ứng ngoài bóng thoại vẫn là tiếng Nhật.**
- **Máy dưới 6 GB RAM** dễ bị Android tắt app giữa chừng.
- Một số app chặn chụp màn hình — đó là giới hạn của Android, không có cách vòng.

---

## Ghi công

Dùng ba mô hình nguồn mở, đều giấy phép Apache-2.0:
[`ogkalu/comic-text-and-bubble-detector`](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) ·
[`onnx-community/manga-ocr-base-ONNX`](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) ·
[`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)

## Pháp lý

App **chỉ xử lý nội dung đang hiển thị trên máy người dùng**. Không tải, không lưu trữ, không phân phối nội dung có bản quyền, không tích hợp bất kỳ nguồn truyện nào.
