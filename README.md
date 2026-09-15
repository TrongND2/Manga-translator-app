# Manga Translator

Dịch manga tiếng Nhật sang tiếng Việt **ngay trên điện thoại Android**.

Mở trang truyện → chạm icon nổi → bản dịch hiện đè lên, ngay trong bóng thoại.

Không server, không tài khoản. Sau khi tải gói mô hình một lần, **tắt mạng vẫn dịch được**. Ảnh màn hình và bản dịch không rời khỏi máy.

---

## Ba mô hình AI chạy thẳng trên điện thoại

Toàn bộ dây chuyền nằm trong máy bạn — không có bước nào gọi ra ngoài:

| Bước | Mô hình | Chạy gì |
|---|---|---|
| 👁️ **Tìm bóng thoại** | comic-text-and-bubble-detector (ONNX int8) | khoanh từng bóng thoại trên ảnh màn hình |
| 🈶 **Đọc chữ Nhật** | manga-ocr (ViT + BERT, ONNX) | đọc cả chữ dọc, chữ viết tay, furigana |
| 🧠 **Dịch** | **Gemma 4 E2B** (2,6 GB, LiteRT-LM) | dịch **cả trang trong một lần gọi** |

Vì sao dịch cả trang một lần chứ không từng bóng: mô hình nhìn thấy toàn bộ cuộc hội thoại theo thứ tự đọc phải→trái nên **giữ được mạch truyện và xưng hô nhất quán** (anh/em/cậu/tớ...). Dịch lẻ từng bóng là nguyên nhân số một gây sai xưng hô — và đo thật thì nó còn **chậm hơn 3,3 lần**.

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

Bốn cử chỉ:

| | |
|---|---|
| **Chạm icon** | dịch trang đang hiện |
| **Giữ icon** | 📖 hướng dẫn · ⌖ khoanh lấy chữ · ✕ tắt app |
| **Chạm giữ vào bóng thoại** | xem lại chữ Nhật gốc |
| **Lật trang / chuyển app** | dừng dịch ngay, gỡ bản dịch cũ |

> Trang đầu mất khoảng 3 phút — mô hình phải đọc hết trang trước khi dịch, không phải app treo. Trang đã dịch rồi thì gần như tức thì.

---

## ⌖ Khoanh lấy chữ → từ điển riêng

Giữ icon → chạm `⌖` → kéo một khung quanh chữ cần lấy. App đọc chữ Nhật trong khung ra, bạn gõ nghĩa tiếng Việt rồi lưu.

**Từ điển riêng là cách bạn dạy app.** Mô hình chạy trên máy đôi khi bỏ qua một cụm, hoặc dịch một thành ngữ theo nghĩa đen — một mục từ điển ép được nó dịch đúng, và áp dụng cho **mọi lần dịch sau**. App cũng tự đề xuất tên nhân vật lặp lại để bạn duyệt.

## 🔍 Tra nghĩa bằng Gemini — tuỳ chọn, mặc định tắt

Khi khoanh được một cụm chữ, có nút **Hỏi Gemini** để lấy nhanh nghĩa gợi ý.

Đây là **đường ra mạng duy nhất** của app, và nó được giữ rất hẹp:

- **Dịch trang vẫn chạy 100% trên máy** — không có gì thay đổi ở đó
- Chỉ **đúng cụm chữ bạn khoanh** mới được gửi đi. Không bao giờ gửi cả trang, không bao giờ gửi ảnh màn hình
- Chỉ chạy khi bạn **tự bấm nút**
- Không nhập khoá thì tính năng tắt hẳn, app chạy đủ như cũ

Hướng dẫn lấy [khoá Gemini miễn phí](https://aistudio.google.com/apikey) nằm sẵn trong **Cài đặt / gói mô hình**, kèm nút mở thẳng trang tạo khoá. Khoá chỉ nằm trong máy bạn.

---

<sub>Dùng ba mô hình nguồn mở giấy phép Apache-2.0: [detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) · [manga-ocr](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) · [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)</sub>

## Pháp lý

App **chỉ xử lý nội dung đang hiển thị trên máy người dùng**. Không tải, không lưu trữ, không phân phối nội dung có bản quyền, không tích hợp bất kỳ nguồn truyện nào.
