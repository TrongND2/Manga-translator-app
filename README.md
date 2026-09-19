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

Cử chỉ:

| | |
|---|---|
| **Chạm icon** | dịch trang đang hiện |
| **Giữ icon** | 📖 hướng dẫn · ⌖ khoanh lấy chữ · ✕ tắt app |
| **Chạm giữ vào bóng thoại** | xem lại chữ Nhật gốc của riêng bóng đó |
| **Chạm hai cái vào bóng thoại** | sửa bản dịch của bóng đó, ngay trên trang |
| **Giữ icon → ⌖** | khoanh chữ ngoài bóng thoại, dịch rồi đè lên trang |
| **Lật trang / chuyển app** | dừng dịch ngay, gỡ bản dịch cũ |

> Trang đầu mất khoảng 3 phút — mô hình phải đọc hết trang trước khi dịch, không phải app treo. Trang đã dịch rồi thì gần như tức thì.

---

## ✎ Dịch sai một chỗ? Sửa thẳng chỗ đó

Chạm hai cái vào bóng thoại dịch sai — ô sửa hiện ra **ngay trên trang đang đọc**, không phải rời app đọc truyện. Sửa xong bấm Lưu là thấy đổi ngay tại chỗ.

Hai cách lưu, khác nhau rõ:

| | |
|---|---|
| **Lưu cho riêng trang này** | chỉ đổi đúng chỗ đó |
| **Lưu vào từ điển riêng** | áp cho **mọi trang**, kể cả trang đã dịch rồi |

Vì sao phải sửa chứ không phải xoá rồi dịch lại: đo trên máy thật, dịch lại cùng một trang cho **5/5 câu giống hệt từng chữ** — mô hình không hề ngẫu nhiên. Xoá chỉ trả về đúng cái sai cũ.

### Khi nào app không cho lưu vào từ điển riêng

Ô sửa lấy **nguyên văn cả bóng thoại** làm mặt chữ, mà cả một câu thì chỉ khớp đúng bóng đó trên đúng trang đó — không bao giờ dùng lại được. Tệ hơn: thêm một mục từ điển khiến app quên bản dịch của mọi trang chứa cụm đó, tức **xoá luôn bản dịch của chính trang bạn vừa đọc xong**, lần sau mở lại phải dịch lại từ đầu.

Nên app chặn hai trường hợp và nói rõ lý do ngay dưới nút:

| Nguyên bản | Vì sao không nhận |
|---|---|
| **dài từ 10 chữ trở lên** | là cả một câu, chỉ khớp một trang |
| **có dấu câu hoặc ký hiệu** (`! ? ♡ 。…「」`) | mô hình chép nguyên dấu đó vào bản dịch |

Gặp hai trường hợp này thì dùng **Lưu cho riêng trang này** — nhanh hơn, giữ đúng chỗ bạn vừa sửa, và không đụng tới trang nào khác. Nếu vẫn muốn dạy app một cụm, vào **Từ điển riêng** thêm cụm ngắn đã bỏ dấu câu.

## ⌖ Chữ ngoài bóng thoại — khoanh rồi đè bản dịch lên

Chữ hiệu ứng và chữ nằm ngoài bóng thoại thì bộ nhận diện gần như không bắt được. Giữ icon → chạm `⌖` → kéo một khung quanh chữ đó. App đọc chữ Nhật trong khung, dịch, rồi bấm **Đè bản dịch lên trang**.

Lớp đè này hành xử **y như một bóng thoại bình thường**: chạm giữ để hé chữ gốc, chạm hai cái để sửa hoặc **Gỡ lớp này** trả lại tranh.

> Chữ hiệu ứng thường nằm đè lên tranh, nên chỗ đè sẽ là một mảng màu che mất nét vẽ. Xem xong thì gỡ đi.

## ⌖ Khoanh lấy chữ → từ điển riêng

Giữ icon → chạm `⌖` → kéo một khung quanh chữ cần lấy. App đọc chữ Nhật trong khung ra, bạn gõ nghĩa tiếng Việt rồi lưu.

**Từ điển riêng là cách bạn dạy app.** Mô hình chạy trên máy đôi khi bỏ qua một cụm, hoặc dịch một thành ngữ theo nghĩa đen — một mục từ điển ép được nó dịch đúng, và áp dụng cho **mọi lần dịch sau**. App cũng tự đề xuất tên nhân vật lặp lại để bạn duyệt.

Thêm một mục xong, app **tự quét lại những trang đã dịch và chỉ quên đúng những trang có chứa cụm đó** — các trang khác giữ nguyên, và nó báo cho bạn biết bao nhiêu trang sẽ được dịch lại. Không phải sửa từng trang một, cũng không phải xoá sạch.

### Cài xong là đã có sẵn 413 mục

Không phải bắt đầu từ con số không. App đi kèm một bộ từ điển soạn sẵn cho manga: giải phẫu, hành vi, vai và quan hệ, trạng thái, bối cảnh trường học, cùng những câu chào cố định. Nó được nạp tự động ở lần chạy đầu tiên.

Bộ này chỉ chứa **cụm ngắn, nghĩa rõ một chiều**. Những từ đa nghĩa như 大丈夫, やばい, 生 bị bỏ ra có chủ ý: ép cứng một nghĩa cho chúng sẽ làm hỏng những trang bình thường mà không ai nhận ra.

Từ điển to **không làm app chậm đi**: mỗi lần dịch, app chỉ đưa vào những mục thật sự xuất hiện trên trang đó — đo trên 15 trang thật thì trung bình chỉ 3 mục mỗi trang.

### Nhập / xuất để sao lưu và mang sang máy khác

Trong màn hình Từ điển riêng có **⬇ Nhập từ file** và **⬆ Xuất ra file**.

- **Nhập** thì **gộp** vào những mục đang có; trùng chữ Nhật thì mục mới thắng. Không xoá gì của bạn.
- **Xuất** ra một file `.json` đặt ở đâu tuỳ bạn.

Cả hai đi qua trình chọn file của hệ thống nên **app không cần quyền bộ nhớ nào**.

## 🗑 Dịch lại chỉ mấy trang vừa đọc

App nhớ kết quả các trang đã dịch để mở lại là hiện ngay. Nhưng khi bạn vừa đọc vài trang thấy dịch chưa ổn, trước đây chỉ có hai lựa chọn: giữ hết, hoặc xoá sạch cả thư viện — mà mỗi trang dịch lại tốn 1–2 phút.

Trong **Cài đặt / gói mô hình**, mục *Bản dịch đã lưu*, giờ có hai nút cho hai tình huống khác nhau:

| | Dùng khi |
|---|---|
| **Dịch lại các trang đã dịch** | vừa sửa từ điển, muốn áp nghĩa mới cho **cả** thư viện |
| **Chỉ xoá vài trang vừa dịch** | mấy trang vừa đọc dịch chưa ổn |

Nút thứ hai cho chọn **1 / 3 / 10 / 30 trang gần nhất, hoặc tất cả**. Xoá xong, mở lại trang nào thì trang đó dịch mới.

"Gần nhất" tính theo lúc **dịch**, không phải lúc **đọc** — nên mở lại một trang cũ không đẩy nó vào diện bị xoá.

> Còn một cách quên có chọn lọc nữa, theo **nội dung**: thêm một mục từ điển thì app chỉ quên đúng những trang có chứa cụm đó.

## Hai nút dịch, ở cả ba chỗ

Ô sửa bóng thoại, màn khoanh lấy chữ, và ô thêm mục ở Từ điển riêng đều có hai nút:

| | Thời gian | Điều kiện |
|---|---|---|
| **📱 AI trên máy** | ~20 giây, hoặc vài giây nếu vừa dịch trang đó | không cần mạng, **không giới hạn số lần** |
| **✨ Hỏi Gemini** | ~1 giây | cần mạng, cần khoá, có hạn mức theo ngày |

"AI trên máy" dùng **chính mô hình vẫn dịch cả trang** cho bạn — nên khi Gemini hết lượt hoặc máy chủ quá tải, bạn vẫn còn đường dùng.

## 🔍 Tra nghĩa bằng Gemini — tuỳ chọn, mặc định tắt

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
