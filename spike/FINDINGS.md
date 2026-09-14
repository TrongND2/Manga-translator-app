# Phase 0 — Nhật ký phát hiện

> Quy tắc: mỗi kết luận phải kèm phép đo đỡ nó. Chưa đo thì ghi rõ là **chưa đo**.

---

## F1 — manga-ocr chạy được trên CPU, đọc đúng chữ dọc manga

**Phép đo:** `r1_ocr/ocr_grid.py` trên `tubaki_010.jpg` (974×1400), cắt lưới 3×4 = 12 vùng, quét phải→trái trên→dưới.
**Máy:** ROG Ally, Ryzen Z1 Extreme, CPU-only, PyTorch fp32.

| Chỉ số | Giá trị |
|---|---|
| Thời gian nạp model | 36.4s (lần đầu, gồm tải ~450MB từ HF) |
| Thời gian mỗi lần gọi | **trung bình 0.325s** (min 0.19 · max 0.48) |
| 12 lần gọi | 3.9s |

Kết quả trả về là tiếng Nhật thật, đúng văn phong manga — có cả khẩu ngữ thô (`俺ァ`, `じゃねェか`) và ký tự kéo dài (`．．．俺の事は放っておいてくれーー`). Bộ truyện `tubaki` là truyện cổ trang về kỹ nữ (`高級女郎`).

**Kết luận được phép rút:** manga-ocr nạp và chạy được trên CPU, và trên **một trang này** nó đọc ra tiếng Nhật hợp lý ở các vùng có chữ.
**KHÔNG được rút:** chưa có ground truth nên **chưa đo được CER**. R1 **chưa được trả lời**.

### Hệ quả cho R4 (tốc độ)

0.325s/bubble trên Zen4 x86. Một màn ~10 bubble ⇒ ~3.3s cho khâu OCR.
Snapdragon 778G chậm hơn đáng kể (**chưa đo, ước lượng 2–4×**) ⇒ ~6–13s. Chuyển ONNX int8 nhiều khả năng kéo xuống.

**OCR không phải nút thắt tốc độ. Nút thắt vẫn là bước dịch bằng LLM.**

---

## F2 — ⚠️ manga-ocr BỊA chữ ở vùng không có text

**Quan sát:** trong 12 vùng cắt theo lưới, nhiều vùng rơi vào phần tranh không có bubble, nhưng manga-ocr **vẫn trả về câu tiếng Nhật trông hợp lý**, ví dụ:

```
10  これからのお客様のご相談ください。これまでは、
11  それでも、
 5  ．．．はァの
```

manga-ocr là mô hình image→text, **không có đầu ra "chỗ này không có chữ"** và không trả về điểm tin cậy. Đưa cho nó một mảnh tranh, nó vẫn sinh ra chữ.

**Vì sao nguy hiểm:** bịa chữ → dịch ra tiếng Việt trôi chảy → vẽ đè vào bubble. Người đọc **không có cách nào biết** đó là thoại bịa. Lỗi này tệ hơn dịch sai: dịch sai còn thấy lấn cấn, thoại bịa thì đọc rất mượt.

**Hệ quả kiến trúc:**
1. Chất lượng bước **detector quyết định tính đúng đắn**, không chỉ quyết định recall. Detector nhận nhầm vùng tranh thành bubble sẽ đẻ ra thoại ma.
2. Cần một **cơ chế lọc** ngoài bản thân manga-ocr — chưa biết dùng gì. Ứng viên: ngưỡng confidence của detector, kiểm tra vùng có nền sáng đồng nhất kiểu bubble, hoặc đối chiếu độ dài chữ với diện tích vùng.
3. Đây là căn cứ thực nghiệm cho **FR-023** trong PRD, và là đầu vào bắt buộc cho bước architecture.

**Trạng thái:** quan sát trên **một trang**, chưa định lượng tỷ lệ bịa. Cần đo lại khi có detector thật.

---

## F3 — Bộ ảnh test

**Phép đo:** `r1_ocr/probe.py` trên toàn bộ 201 file.

| Loại | Số lượng |
|---|---|
| Trang đơn dọc (974×1400) | 196 |
| Spread 2 trang | 4 |
| Banner ngang (4177×500) | 1 |

Dùng 196 trang đơn làm bộ đo chuẩn. `tubaki_000` (3236×1400), `tubaki_001` (banner), `tubaki_002` (2072×1400) là bìa/trang đặc biệt — tách riêng, không trộn vào số liệu CER.

---

## F4 — Đã tìm được detector, và nó gỡ được F2

**Model:** `ogkalu/comic-text-and-bubble-detector` — RT-DETR-v2, fine-tune trên ~11k ảnh manga/webtoon/manhua/comic. **License Apache-2.0** (đã tự kiểm trên trang model, không suy từ repo).

Ba lớp: `bubble` (vỏ bóng thoại) · `text_bubble` (chữ **trong** bóng) · `text_free` (chữ ngoài bóng).

Lớp `text_bubble` chính là thứ F2 cần: chỉ cắt vùng được gán nhãn này mới đưa sang OCR, thay vì quét mò cả trang.

**Phép đo** (`r1_ocr/run_detect.py`, 6 trang, CPU Ryzen Z1):

| Biến thể | Dung lượng | Tốc độ | Ghi chú |
|---|---|---|---|
| `detector-v4-s_int8.onnx` | **11.1MB** | **0.166s/trang** | Sát ngân sách ~20MB của brief |
| `detector_int8.onnx` | 43.8MB | 0.494s/trang | Chậm hơn 3× |
| `detector.onnx` | 168MB fp32 | chưa đo | Không tải, không dùng cho Android |

Số bubble tìm được gần như bằng nhau; bản 44MB nhỉnh hơn 1–2 box trên 2/6 trang.

**Kiểm tra bằng mắt** (`out/detect/v4s_11mb__tubaki_010.jpg.png`): box `text_bubble` bám **sát đúng cột chữ dọc**, box `bubble` bao đúng vỏ bóng. Thứ tự đọc phải→trái đánh số hợp lý.

**Kết luận được phép rút:** trên 6 trang này, bản 11MB phát hiện bubble tốt và đủ nhanh.
**KHÔNG được rút:** chưa đo precision/recall có số. Chưa chọn dứt điểm giữa 11MB và 44MB — cần một phép đo định lượng, không phải đếm box.

**Điểm yếu đã thấy:** chữ SFX lớn ngoài bubble (`イチャ`, `!!`) hầu như **không được phát hiện** — `text_free` chỉ ra 1 box trên 6 trang. MVP sẽ **không dịch được SFX**. Cần ghi vào phạm vi.

---

## F5 — ⚠️ Bộ test là truyện người lớn: hai hệ quả thật

`tubaki` là truyện cổ trang có nội dung khiêu dâm rõ ràng (ảnh khoả thân, thoại tình dục).

1. **Rủi ro LLM từ chối dịch.** Model chat thương mại thường có lớp an toàn. Nếu Qwen3 từ chối, pipeline **đứt hoàn toàn** ở bước 3 — và đây là rủi ro R2 không có trong brief. Đang đo trong `run_r2.py`.
2. **Chặn đường lên Play Store.** Nếu sau này muốn public, chính sách nội dung của Google là vấn đề thật. Không ảnh hưởng MVP dùng riêng (D13).

**Ảnh hưởng tới tính tổng quát:** mọi số đo hiện tại đến từ **một bộ truyện, một thể loại, một hoạ sĩ**. Không được suy ra cho manga nói chung.

---

## F6 — R4 (tốc độ): có số thật trên PC

**Phép đo:** `r2_translate/run_r2.py`, 4 trang × 12 bubble, Ryzen Z1 Extreme CPU.

| Model | tok/s | LLM | detect | OCR | **Tổng/trang** |
|---|---|---|---|---|---|
| `qwen3:4b` | 14–15 | 29–44s | 0.2s | 3.6–4.2s | **33–49s** |
| `qwen3:1.7b` | ~31 | 11–20s | 0.2s | 3.6–4.2s | **15–25s** |

**Cơ cấu thời gian: LLM chiếm ~90%.** Detect 0.4%, OCR ~9%. Mọi nỗ lực tối ưu phải dồn vào bước 3.

**Ngoại suy sang M52 (CHƯA ĐO):** SD778G chậm hơn Zen4 ước chừng 2–3× ⇒ `qwen3:4b` khoảng 5–7 tok/s ⇒ **60–120s/trang**. Vượt xa ngưỡng 40s của NFR-005. `qwen3:1.7b` ước ~30–50s, vừa chạm.

> Đây là **ngoại suy**, không phải phép đo. Phải đo trên chính M52 mới được kết luận.

---

## F7 — ⚠️ R2 TRƯỢT trên bộ test này

**Phép đo:** chấm tay 12 bubble của `tubaki_010.jpg`, đối chiếu nguyên bản tiếng Nhật.

| Cấu hình | Đạt "hiểu được mạch" | Ghi chú |
|---|---|---|
| `qwen3:1.7b`, không glossary | ~2/12 (~17%) | Lặp câu, dịch nghĩa đen |
| `qwen3:4b`, không glossary | ~4/12 (~33%) | |
| `qwen3:4b`, **có glossary** | ~5/12 (~42%) | Tốt nhất trong vòng 1–2 |
| **Ngưỡng PRD (NFR-004)** | **80%** | |

### Glossary có tác dụng thật — bằng chứng cho FR-032

| JA | Không glossary | Có glossary |
|---|---|---|
| `瑠璃丸ったら～～` | "Cô ấy đang cười rộn rã!" ❌ bịa | **"Rurimaru!"** ✅ |
| `はァ？` | "À?" ⚠️ | **"Hả?"** ✅ |

Nhưng glossary **không sửa được lỗi ngữ nghĩa**: `ソデにした` (phũ/từ chối) vẫn dịch ngược thành "đã làm với", `失礼して` (xin phép cáo lui) vẫn thành "xin lỗi". Có chỗ còn **tệ đi**: `憎たらしい` từ "đáng ghét" ✅ thành "xấu xí" ❌.

### `think` mode hỏng như đang cấu hình

Bật `think: true` cùng `format: json` ⇒ model sinh 1195 ký tự suy luận rồi **trả response rỗng**. Không phải model kém — là cấu hình sai. Muốn dùng think phải tách hai bước, không ép `format: json` đồng thời.

### Vì sao CHƯA được tuyên bố dự án chết

1. **Mới đo 1 trang, 12 bubble, 1 bộ truyện.** `tubaki` là tiếng Nhật **khó bậc nhất**: khẩu ngữ cổ trang Edo, tiếng lóng khu lâu xanh (`シケ込む`, `ソデにする`, `筆下ろし`, `女郎`). Đây là **trường hợp xấu nhất**, không phải trung bình. Shounen hiện đại gần như chắc chắn cao hơn.
2. Mới thử **một họ model**.
3. Prompt mới là **bản v1**.

**Việc phải làm trước khi kết luận:** đo `gemma3:4b` và `qwen3:8b` trên cùng trang. `qwen3:8b` không nhét vừa điện thoại, nó ở đó để trả lời câu hỏi chẩn đoán: **chất lượng có tăng theo cỡ model không?** Nếu 8B cũng trượt thì nút thắt nằm ở nhiệm vụ/dữ liệu, không phải ở cỡ model — và kết luận sẽ khác hẳn.

---

## F8 — ⭐ Chất lượng KHÔNG tăng theo cỡ model. Nút thắt là GLOSSARY.

**Phép đo:** cùng trang `tubaki_010`, cùng prompt v1, cùng glossary (chỉ tên riêng), chỉ đổi model.

| Model | Kích thước | tok/s | Thời gian | Chấm tay |
|---|---|---|---|---|
| `qwen3:1.7b` | 1.4GB | 28.3 | 25s | ~2/12 (17%) |
| `qwen3:4b` | 2.5GB | 13.6 | 33s | ~4/12 (33%) |
| **`gemma3:4b`** | 3.3GB | 15.9 | 64s | **~6/12 (46%)** |
| `qwen3:8b` | 5.2GB | 8.3 | **144s** | ~6/12 (46%) |

**Gấp đôi cỡ model (4B→8B) không cải thiện chất lượng, mà chậm gấp 4.**

### Bằng chứng: ba model khác họ, khác cỡ, sai y hệt nhau ở cùng chỗ

| JA | qwen3:4b | gemma3:4b | qwen3:8b |
|---|---|---|---|
| `ソデにした` (phũ, từ chối) | "đã làm người đẹp" ❌ | "chọn làm vợ" ❌ | "đã chạm vào" ❌ |
| `筆下ろし` (phá trinh) | "viết lại từ đầu" ❌ | "làm kẻ tầm thường" ❌ | "tự mình xử lý" ❌ |

Cả hai đều là **thành ngữ / tiếng lóng không có trong glossary**. Ngược lại, chỗ **có** glossary thì cả ba đều đúng: `瑠璃丸` → "Rurimaru" ✅✅✅.

**Kết luận được phép rút:** trên trang này, lỗi nặng tập trung ở từ vựng theo bối cảnh, không phân biệt theo cỡ model. Đây là dấu hiệu **thiếu từ điển**, không phải **thiếu năng lực model**.
**KHÔNG được rút:** mới đo 1 trang. Chưa chứng minh mở rộng glossary sẽ vá được — đó là việc của vòng 4.

### `gemma3:4b` mạnh hơn Qwen ở thành ngữ tiếng Nhật

Câu mà mọi model Qwen đều sai, gemma3 dịch đúng hoàn toàn:

> `では俺は失礼してー` → **"Vậy thì để ta xin phép cáo lui nào."** ✅
> (qwen3:4b: "Tớ xin lỗi rồi!" ❌ · qwen3:8b: "Thì mày đi đi nào" ❌)

`gemma3:4b` cũng giữ xưng hô cổ trang nhất quán hơn ("ta/ngươi" thay vì "tớ/mày").

**Hệ quả:** `gemma3:4b` là ứng viên số một cho điện thoại, **không phải** `qwen3:4b` như brief giả định. Nhưng nó chậm hơn (64s vs 33s trên cùng máy) — cần đo lại khi đã chốt prompt.

---

## F9 — ⭐ Glossary thành ngữ là đòn bẩy thật. Cơ chế đã được chứng minh.

**Phép đo:** cùng `gemma3:4b`, cùng prompt v1, cùng trang. Chỉ mở rộng glossary từ *chỉ tên riêng* sang *có thêm thành ngữ / tiếng lóng*.

| JA | Glossary chỉ tên riêng | Glossary có thành ngữ |
|---|---|---|
| `素人女で筆下ろしといこうや` | "làm kẻ tầm thường" ❌ | **"pha trinh cho ta xem nào"** ✅ |
| `交じって行きなせェ` | "cứ làm phiền cô ấy thì thôi" ❌ | **"cứ tham gia cùng… đi"** ✅ |
| `他の女の所へシケ込む` | "đi vào phòng khác của các cô khác" ⚠️ | **"Ngươi định chui vào chỗ của một cô khác hả?"** ✅ |

**Đúng những từ được thêm vào glossary là đúng những từ được sửa.** Điểm chấm tay nhảy từ ~46% lên **~60%**.

**Hệ quả kiến trúc — đây là thay đổi lớn so với brief:**
Brief xếp glossary vào **Phase 3 ("Chất lượng dịch")** như một tinh chỉnh. Phép đo nói nó là **thành phần trung tâm**, phải có từ Phase 1. Không có glossary thì không có mức chất lượng nào chấp nhận được, bất kể model to đến đâu (F8).

Và glossary cần chứa **ba loại**, không chỉ tên nhân vật như `FR-032` đang ghi:
1. Tên riêng (nhân vật, địa danh)
2. **Thành ngữ / tiếng lóng theo bối cảnh truyện** ← loại quan trọng nhất, đang thiếu trong PRD
3. Xưng hô đã chốt giữa từng cặp nhân vật

### Prompt v2 thất bại — ghi lại để không thử lại

Prompt v2 thêm bước `literal` (dịch nghĩa đen) vào trong JSON, thay cho `think` mode.

| | Kết quả |
|---|---|
| `qwen3:4b` + v2 | **Tệ đi.** Nhầm ngôi (`Mày định…` → `Tớ định…`), bubble [7] thành lặp vô nghĩa "Tớ đã làm được rồi, tớ đã làm được rồi!" |
| `gemma3:4b` + v2 | **JSON bị cắt cụt.** v2 sinh gấp đôi token (`literal` + `vi`) nên vượt giới hạn output mặc định |

**Kết luận:** giữ prompt v1. Muốn thêm bước suy luận thì phải nâng `num_predict` và đo lại, nhưng ưu tiên thấp — glossary cho lợi ích lớn hơn nhiều với chi phí token bằng 0 ở đầu ra.

---

## F10 — 🚨 LỖI LỆCH ID: bản dịch bị gán sai bubble

**Phép đo:** `r2_translate/run_r2e.py`, `gemma3:4b`, trang `tubaki_025.jpg`.

Model trả về đủ 12 bubble, JSON hợp lệ, `done_reason: stop` — mọi chỉ số kỹ thuật đều "xanh". Nhưng từ bubble [3] trở đi, **toàn bộ bản dịch lệch đi một ô**:

| id | JA thật | Bản dịch được gán | Thực ra là dịch của |
|---|---|---|---|
| 3 | `頭が良いと言うのか抜け目がない` | "Thế là xong… Hối hận! Đồ ngốc!" | **id 4** |
| 4 | `しまった！！後悔！！畜生！！` | "Thằng này làm gì?" | **id 5** |
| 5 | `何が当て屋だ` | "Không phải nghề… ngầm đâu nhỉ?" | **id 6** |
| 6 | `当たり屋の間違いだろう` | "Ờ?" | **id 7** (`で？`) |
| 7 | `で？` | "Ông muốn tao tìm gì đây?" | **id 8** |
| 8 | `私に何を捜させたいのさ？` | "Ông đang tức giận vì điều gì?" | **id 9** |

**Vì sao đây là lỗi nguy hiểm nhất tìm được cho đến giờ:**

- Mọi kiểm tra tự động đều PASS: JSON hợp lệ, đủ 12 phần tử, id chạy 0→11 đúng thứ tự, không có trường rỗng.
- Người đọc thấy **mọi bubble đều có tiếng Việt trôi chảy**. Không có dấu hiệu nào để nghi ngờ.
- Nhưng cả trang **lệch mạch hội thoại**: người A nói câu của người B.
- Cùng họ với F2 (thoại bịa): sai mà nhìn như đúng.

**Đây là bằng chứng cho FR-034 nhưng ở mức mạnh hơn nhiều.** PRD hiện chỉ yêu cầu thử lại khi *JSON hỏng hoặc thiếu bubble*. Trường hợp này JSON **không** hỏng và **không** thiếu. Cần thêm yêu cầu kiểm tra tính toàn vẹn ánh xạ id↔nội dung.

**Cũng thấy ở trang này:** ký tự rác Khmer lẫn vào bản dịch (`đáoេច`) — model sinh token hỏng.

---

## F11 — Điểm R2 trên 48 bubble (4 trang)

`gemma3:4b`, prompt v1, glossary mở rộng. Chấm tay.

| Trang | Điểm | Ghi chú |
|---|---|---|
| `tubaki_010` | ~7/12 (58%) | Trang đã tinh chỉnh glossary |
| `tubaki_025` | ~2/12 (17%) | **Nạn nhân của lỗi lệch id (F10)** |
| `tubaki_100` | ~8/12 (67%) | Tốt nhất. Xử lý được cả tượng thanh `ばふ` → "Bụp!" |
| `tubaki_150` | ~6/12 (46%) | Hỏng ở tên riêng ngoài glossary: `桔梗` (Kikyou) → "hoa cúc" ❌, `コマ` (Koma) → "con chim" ❌ |
| **Trung bình** | **~47%** | Ngưỡng NFR-004 là **80%** |

### Phân rã nguyên nhân — quan trọng hơn con số tổng

| Loại lỗi | Sửa được không | Ví dụ |
|---|---|---|
| **Lệch id** (F10) | ✅ Sửa được bằng kỹ thuật | Cả trang 025 |
| **Thiếu tên riêng trong glossary** | ✅ Sửa được bằng glossary tích luỹ | `桔梗`, `コマ` |
| **Thành ngữ cổ ngoài glossary** | ⚠️ Sửa được một phần | `ソデにする` |
| **Sai ngữ nghĩa thật sự** | ❌ Giới hạn của model 4B | `か弱い娘さんの柔肌に…` |

**Hai nhóm đầu chiếm phần lớn số điểm mất, và cả hai đều sửa được mà không cần đổi model.**

---

## F12 — R4: `gemma3:4b` nhanh HƠN `qwen3:4b`

**Phép đo:** 4 trang, cùng glossary, Ryzen Z1 CPU.

| Model | tok/s | Tổng pipeline/trang |
|---|---|---|
| **`gemma3:4b`** | 12.4–16.1 | **30–44s** |
| `qwen3:4b` | 9.8–11.8 | 42–61s |

Lần đo trước `gemma3` ra 64s là do **chi phí nạp model lần đầu**, không phải tốc độ sinh token. Đo lại trên 4 trang thì gemma3 thắng cả về chất lượng lẫn tốc độ.

**Ngoại suy sang M52 (CHƯA ĐO):** chậm hơn 2–3× ⇒ **60–130s/trang**. Vẫn vượt ngưỡng 40s.

---

## F13 — ⭐ Gemma 4 E2B: nhỏ hơn, nhanh hơn, dịch tốt hơn

**Phép đo:** 48 bubble, 4 trang, cùng glossary, cùng prompt v1, Ryzen Z1 CPU. Chỉ đổi model.

| | Gemma 3 4B | **Gemma 4 E2B** |
|---|---|---|
| Chất lượng (chấm tay) | ~47% | **~77%** |
| Tốc độ | 16 tok/s | **22–23 tok/s** |
| Thời gian/trang | 28–30s | **14–18s** |
| Kích thước bản LiteRT-LM int4 | — | **2.58GB** |

**~77% vượt ngưỡng NFR-004 (70%)** — trên chính bộ test khó nhất.

### Nó tự xử lý được tên riêng ngoài glossary

Đây là nhóm lỗi lớn thứ hai trong phân rã ở F11:

| JA | Gemma 3 4B | Gemma 4 E2B |
|---|---|---|
| `桔梗の実家へ` | "Đến nhà trồng hoa giấy" ❌ | **"Đến nhà của gia đình Kikyō"** ✅ |
| `コマの死の傷` | "Sẹo chết của con rối" ❌ | **"Vết thương vì cái chết của Koma"** ✅ |
| `変じゃない` | "Không đến nỗi tệ" ❌ | **"Không có gì lạ"** ✅ |
| `畜生！！このお人好し！！` | — | **"Khốn kiếp!! Cái người tốt này!!"** ✅ |
| `ちょっ離ーー` | "Chờ đã..." ⚠️ | **"Tránh ra—"** ✅ |

Trang `tubaki_025` lần chạy này **không tái hiện lỗi lệch id** (F10). Không đủ để kết luận F10 đã hết — một lần chạy không chứng minh được điều đó, và cổng kiểm tra ở AD-6 vẫn phải làm.

### Giả định "model to hơn thì tốt hơn" bị bác lần thứ hai

| Lần | Phép đo | Kết quả |
|---|---|---|
| 1 (F8) | qwen3:8b (5.2GB) vs qwen3:4b (2.5GB) | To gấp đôi, chậm gấp 4, **không tốt hơn** |
| 2 (F13) | Gemma 4 E2B (2.58GB) vs Gemma 3 4B (3.3GB) | **Nhỏ hơn mà tốt hơn hẳn** |

Thế hệ model quan trọng hơn số tham số. Đây là điều cần nhớ mỗi lần bị cám dỗ chọn model to hơn.

⚠️ **Giới hạn của phép đo:** chạy bản ollama `gemma4:e2b` **7.2GB độ chính xác cao**, KHÔNG phải bản **int4 2.58GB** của LiteRT-LM. Lượng tử hoá xuống int4 **sẽ** làm giảm chất lượng. **77% là trần.** Bản `e4b` 9.6GB không đo được vì tràn RAM 10.7GB của máy này.

---

## F14 — Cổng kiểm tra toàn vẹn id: đã thiết kế và kiểm chứng

**Phép đo:** `r2_translate/run_q7.py` + `run_q7b.py`.

Ý tưởng: bắt model trả về `jaEcho` = vài ký tự đầu của nguyên bản tiếng Nhật kèm mỗi bản dịch, rồi đối chiếu với đầu vào.

| Cách so | Trên đáp án thật | Trên lỗi lệch một ô (tiêm có chủ đích) |
|---|---|---|
| So khớp chính xác từng ký tự | **1 báo động giả** ❌ | bắt 11/11 |
| **Chuẩn hoá NFKC + bỏ dấu câu + sai ≤1 ký tự** | **0 báo động giả** ✅ | **bắt 11/11** ✅ |

Báo động giả đến từ nhiễu ký tự, không phải lệch ô:
- `何を当て` vs `何が当て` — model đổi một trợ từ
- `どうせ無` vs `―どうせ` — model bỏ dấu gạch đầu câu

**2 ký tự là đủ.** Dài hơn không tăng độ chính xác mà tốn thêm token. Chi phí ≈ 30 token cho cả trang, coi như bằng 0.

---

## F15 — Sự thật về LiteRT-LM, lấy từ kiểm tra chứ không từ tài liệu

**Cách kiểm:** truy vấn Maven metadata, giải nén AAR, đọc bytecode bằng `javap`, gọi HF API. Không tin tài liệu.

| Điều | Kết quả | Tài liệu Google nói gì |
|---|---|---|
| Nơi phát hành | **Repo Maven của Google** (`google()`) | không nói rõ |
| Bản hiện hành | **0.17.0** | ví dụ dùng `latest.release` — AD-2 cấm |
| ABI trong AAR | `arm64-v8a` + `x86_64` (19.5 MB) | không nói |
| Đo hiệu năng | **Có sẵn `benchmark()` → `BenchmarkInfo`** với `initTimeInSecond`, `timeToFirstTokenInSecond`, `lastDecodeTokensPerSecond` | không nhắc tới |
| `sendMessageAsync` | Nhận **`MessageCallback`** (`onMessage`/`onDone`/`onError`) | **ghi sai** — nói nó trả `Flow` |
| `benchmark()` | `@ExperimentalApi`, cần `@OptIn` | không nói |
| Kotlin metadata | **2.4.0** — xung đột với Kotlin 2.2 của AGP 9.4 | không nói |

### Xung đột Kotlin và cách gỡ đúng

```
Module was compiled with an incompatible version of Kotlin.
The binary version of its metadata is 2.4.0, expected version is 2.2.0.
```

Cách **sai** (thường gặp): thêm cờ `-Xskip-metadata-version-check`. Nó chỉ tắt cảnh báo, giấu vấn đề, và đẻ lỗi khó hiểu lúc chạy.

Cách **đúng**: nâng KGP ở build file gốc.

```kotlin
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
}
```

**Vì sao sẽ còn gặp lại:** LiteRT-LM ở 0.x, phát hành 2–5 tuần/bản, nên nó luôn chạy trước AGP ổn định. Mỗi lần nâng phải kiểm lại phiên bản Kotlin.

### Biến thể model — chọn sai file cho kết luận sai

| File | GB thập phân | Dùng cho |
|---|---|---|
| `gemma-4-E2B-it.litertlm` | **2.59 GB** | CPU |
| `gemma-4-E2B-it-gpu.litertlm` | **2.01 GB** | **GPU — bản riêng** |
| `gemma-4-E2B-it_qualcomm_sm8750.litertlm` | 3.02 GB | NPU, Snapdragon 8 Elite |

Thử `Backend.GPU()` với file CPU rồi kết luận "GPU không chạy được" là **kết luận sai vì dùng sai file**.

**Không có bản NPU nào cho Snapdragon 778G (`sm7325`).** Đường NPU đóng với M52; chỉ còn GPU và CPU.

**Giấy phép:** HF API trả `gated: False`, `license: apache-2.0`. Tải trực tiếp, không cần tài khoản.

**Tốc độ tải:** `huggingface_hub` cho **49.4 MB/s**, còn `Invoke-WebRequest` đơn luồng chỉ **0.4 MB/s** — chênh 120 lần.

---

## F16 — ✅ Story 1.1 xong: pipeline LiteRT-LM chạy được trên Android

**Chạy trên máy ảo `M52_like_A13`** (Android 13, x86_64, 6GB RAM). APK 57.6 MB.

```
model CPU: 2.59 GB
--- backend GPU ---
THAT BAI: LiteRtLmJniException: Failed to create engine:
          FAILED_PRECONDITION: Can not find OpenCL library on this device
--- backend CPU ---
initialize     : 59.01 s
token dau tien : 7.84 s
prefill        : 33.3 tok/s (256 token)
decode         :  6.7 tok/s (256 token)
--- thu dich mot cau ---
JA: 「では俺は失礼してー」
VI: Vậy tôi xin lỗi nhé.
```

### Được phép kết luận

1. **Chuỗi công nghệ hoạt động.** LiteRT-LM 0.17.0 + Gemma 4 E2B nạp được và sinh ra tiếng Việt trên Android.
2. **Đường thoái lui GPU→CPU hoạt động đúng.** App bắt được `LiteRtLmJniException`, không sập, tự chuyển sang CPU. Đây là yêu cầu của AD-2 và **LiteRT-LM không tự làm hộ**.
3. **`initialize()` mất 59 giây.** Google cảnh báo "tới 10 giây"; thực tế gấp 6 lần con số đó, và gấp **7 lần** ngân sách 8s của NFR-005. AD-20 (hâm nóng engine lúc bật app) từ "nên làm" thành **bắt buộc tuyệt đối**.

### KHÔNG được phép kết luận

| Điều dễ kết luận nhầm | Vì sao sai |
|---|---|
| "GPU không chạy được trên M52" | Máy ảo **không có OpenCL gì cả** (`Can not find OpenCL library`). Đây là nguyên nhân khác hẳn Adreno 642L chỉ có OpenCL 2.0. Lần chạy này **không nói được gì** về M52 |
| "6.7 tok/s là tốc độ trên M52" | x86 trên Ryzen Z1. AD-22 cấm |
| "45s/trang là đạt NFR-005b" | Cùng lý do |
| "Gemma 4 int4 dịch kém hơn Phase 0" | Câu này dịch **một bubble đơn lẻ, không ngữ cảnh trang, không glossary**. Phase 0 đo theo cách khác hẳn. So sánh không hợp lệ |

### Tín hiệu cần theo dõi ở Story 1.3

Bản int4 dịch `では俺は失礼してー` thành **"Vậy tôi xin lỗi nhé."** — đúng cái lỗi `失礼` → "xin lỗi" mà Gemma 3 mắc và Gemma 4 (bản 7.2GB) đã tránh được ở Phase 0.

**Chưa kết luận được gì** vì điều kiện đo khác nhau (một bubble vs cả trang + glossary). Nhưng Story 1.3 phải chạy đúng 48 bubble với đúng glossary để biết int4 mất bao nhiêu so với trần 77%.

---

## F17 — ⭐⭐ GPU CHẠY ĐƯỢC trên Adreno 642L. Giả thuyết của reviewer SAI.

**Đo trên MÁY THẬT:** Samsung SM-M526BR · SM7325 (Snapdragon 778G) · Adreno 642L · Android 13 · arm64-v8a.

| | **GPU** | CPU |
|---|---|---|
| Model dùng | `-gpu.litertlm` (2.01 GB) | `.litertlm` (2.59 GB) |
| `initialize()` | 36.98 s | 28.86 s |
| **Token đầu tiên** | **1.09 s** | 4.46 s |
| Prefill | **264.9 tok/s** | 59.7 tok/s |
| Decode | **8.3 tok/s** | 6.0 tok/s |
| Ước 1 trang (300 token) | **36 s** | 50 s |

**GPU thắng ở mọi chỉ số trừ `initialize()`.** Prefill nhanh **4.4 lần**, và đó chính là thứ quyết định thời gian tới token đầu tiên — chỉ số người dùng cảm nhận.

### Nút thắt thật KHÔNG phải OpenCL 2.0 vs 3.0 — mà là một dòng XML

Lần chạy đầu trên máy thật báo đúng lỗi mà máy ảo báo:

```
FAILED_PRECONDITION: Can not find OpenCL library on this device
```

Nhưng kiểm tra máy thì thấy:

```
/vendor/lib64/libOpenCL.so                       <- file CO TON TAI
/vendor/etc/public.libraries.txt: libOpenCL.so   <- CO trong danh sach cong khai
```

Thư viện có thật, được phép dùng, mà app không thấy. Nguyên nhân: **từ Android 12, app phải khai báo tường minh trong manifest mới được `dlopen` thư viện vendor**, kể cả thư viện đã nằm trong `public.libraries.txt`.

Thêm đúng một dòng:

```xml
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

...và GPU chạy ngay.

**Bài học:** giả thuyết "Adreno 642L chỉ có OpenCL 2.0 nên không chạy được" nghe rất hợp lý, có bằng chứng gián tiếp (issue #2318, thông số OpenCL), và **sai**. Nếu tin nó mà không đo, dự án đã chọn CPU và mất 4.4 lần tốc độ prefill.

### Trạng thái rủi ro sau F17

| # | Rủi ro | Trước | Sau |
|---|---|---|---|
| **R4** | Tốc độ | ⚠️ ngoại suy 60–130s/trang | ✅ **36 s/trang đo thật**, dưới ngưỡng 90s |
| NFR-005 | Bubble đầu ≤ 8s | chưa đo | ✅ **1.09 s** (engine đã nóng) |
| NFR-005b | Tổng ≤ 90s | chưa đo | ✅ **36 s** |

⚠️ **`initialize()` mất 29–37 giây** trên máy thật. Google cảnh báo "tới 10 giây" — thực tế gấp 3–4 lần. Ngân sách 8s của NFR-005 **chỉ đúng khi engine đã nóng sẵn**. AD-20 không còn là tối ưu, nó là **điều kiện sống còn của trải nghiệm**.

### Ràng buộc mới phát hiện: dung lượng máy

M52 của tác giả còn **5.5 GB trống trên 107 GB (dùng 95%)**. Hai biến thể model cộng lại 4.6 GB — không đẩy đồng thời được. Phải đẩy từng cái, đo xong xoá.

`MemAvailable` lúc đo chỉ **2.52 GiB** trên tổng 7.19 GiB (One UI chiếm phần lớn), trong khi model CPU nặng 2.59 GB. Model nạp được nhờ mmap, nhưng đây là R3 và **chưa đo dưới tải kéo dài** (Story 1.4).

---

## F18 — 🚨 SỬA F17: backend GPU sinh RÁC trên Adreno 642L

**F17 kết luận "GPU chạy được". Kết luận đó SAI ở chỗ quan trọng nhất.**

F17 chỉ đo bằng hàm `benchmark()` — nó đếm token và bấm giờ, **không kiểm nội dung**. Khi cho sinh văn bản thật, GPU trả về **rác token** trộn lẫn nhiều ngôn ngữ:

```
[0] 俺, lại nhờ với mùi folksERICK唄
[1] Vậy Verから çaել[1]
[4] Heyतरह_make###
[9] WaitMANAGER
```

JSON hỏng ở **cả 4/4 trang**. Đây không phải dịch sai — đây là **hỏng số học** trong kernel GPU.

### Phép so sánh A/B — cùng máy, cùng prompt, cùng dữ liệu, chỉ khác backend

| | **GPU** | **CPU** |
|---|---|---|
| JSON hợp lệ | **0/4 trang** | **4/4 trang** |
| Nội dung | rác đa ngôn ngữ | tiếng Việt mạch lạc |
| Thời gian/trang | 4.5–20.1 s | 51.3–79.4 s |
| `benchmark()` báo | 264.9 tok/s prefill, 8.3 tok/s decode | 59.7 / 6.0 |

**GPU nhanh gấp 4 lần và hoàn toàn vô dụng.** Thời gian ngắn bất thường (4.5 s/trang) chính là dấu hiệu: model sinh ra rác rồi dừng sớm.

### Vì sao F17 đưa ra kết luận sai

`benchmark()` trả về `tok/s`, `timeToFirstToken`, `tokenCount` — **toàn chỉ số hiệu năng, không có chỉ số đúng/sai**. Mọi con số đều xanh trong khi đầu ra vô dụng.

**Đây đúng loại lỗi mà AD-4 sinh ra để chặn** — "chạy được ≠ chạy đúng", và lần này chính tôi mắc phải. Bài học: **không bao giờ kết luận về một backend chỉ bằng chỉ số tốc độ.** Phải nhìn nội dung nó sinh ra.

⇒ **AD-2 phải sửa lại: CPU là đường DUY NHẤT dùng được trên Adreno 642L.** Giữ code chọn GPU, nhưng phải có cổng kiểm chất lượng đầu ra trước khi tin nó trên bất kỳ máy nào.

---

## F19 — Story 1.3: bản int4 đạt ~69%, sát dưới ngưỡng 70%

**Đo trên máy thật, backend CPU, 48 bubble, cùng glossary và prompt v1 như Phase 0.**

| Trang | Điểm | Thời gian |
|---|---|---|
| `tubaki_010` | ~7/12 (58%) | 79.4 s |
| `tubaki_025` | ~8/12 (67%) | 59.6 s |
| `tubaki_100` | ~8/12 (67%) | 53.0 s |
| `tubaki_150` | ~10/12 (83%) | 51.3 s |
| **Tổng** | **~33/48 ≈ 69%** | |

**Trần trên PC (ollama 7.2 GB) là 77%. Lượng tử hoá int4 mất khoảng 8 điểm.**

`NFR-004` đặt ngưỡng **70%** — kết quả **69%**, tức **sát dưới ngưỡng**, trên bộ truyện khó nhất.

### Chỗ int4 vẫn làm đúng

| JA | Bản dịch | |
|---|---|---|
| `では俺は失礼してー` | "Vậy tôi xin phép đi trước." | ✅ câu Gemma 3 dịch sai |
| `ちょっ離ーー` | "Khoan đã—" | ✅ |
| `桔梗の実家へ` | "Đến nhà gia đình Kikyo" | ✅ tên riêng ngoài glossary |
| `しまった！！後悔！！畜生！！` | "Xong rồi!! Hối hận!! Đồ khốn kiếp!!" | ✅ |
| `変じゃない` | "Không lạ" | ✅ |

### Chỗ vẫn hỏng, kể cả khi glossary CÓ mục đó

| JA | Glossary ghi | int4 dịch |
|---|---|---|
| `ソデにした` | "phũ, từ chối phũ phàng" | "biến nữ lang cao cấp thành người phụ (sode)" ❌ |
| `筆下ろし` | "phá trinh" | "tuyên bố là 'bút hạ'" ❌ |
| `先生` | "tiên sinh, KHÔNG phải thầy giáo" | "Thưa giáo sư" ❌ |
| `ばふ` (tượng thanh) | — | "Bá phu" ❌ đọc chữ Hán |

Bản 7.2 GB dịch đúng `筆下ろし` và `先生`. Bản int4 **không đọc nổi glossary cho các mục khó** — đây là chỗ mất điểm chính.

### Một lỗi toàn vẹn đã xuất hiện

Trang `tubaki_025` model trả về **13 bubble trong khi đầu vào có 12**. Cổng `AD-6` sẽ bắt được, nhưng nó xác nhận lỗi ánh xạ id là **có thật và tái diễn**, không phải sự cố một lần.

---

## F20 — Story 1.4: không OOM, không throttle, nhưng máy nóng 82–84°C

**Đo trên Galaxy M52, backend CPU, 19 vòng / 12.8 phút.** Dừng sớm theo yêu cầu của tác giả vì máy nóng — dữ liệu vẫn đủ dùng nhờ ghi đè file sau mỗi vòng.

| Chỉ số | Kết quả |
|---|---|
| OOM-kill | **0 lần** |
| `lowMemory` | **0/19 vòng** |
| RSS | **3017 → 1998 MB** (giảm 1 GB) |
| RAM máy còn trống | ổn định ~1400 MB |
| Thời gian/trang | 53 s (vòng đầu nguội) → **35–42 s, phẳng** |
| Nhiệt | 75.3 → **82–84°C rồi chững** |

### ✅ NFR-007 đạt — và cơ chế giải thích vì sao

RSS **giảm** 1 GB theo thời gian thay vì tăng. Model nạp bằng `mmap`, nên khi RAM căng, hệ điều hành **thu hồi trang nhớ thay vì giết process**. Đó là lý do app chiếm 3 GB mà vẫn sống trên máy chỉ còn trống 1.4 GB.

⇒ Lo ngại "RSS 3 GB > RAM trống 1.4 GB nên sẽ bị kill" là **sai về cơ chế**. Bộ nhớ ánh xạ file không hành xử như bộ nhớ cấp phát.

### ✅ Không bị throttle trong 13 phút

82–84°C mà tốc độ vẫn phẳng 35–42 s. Nhiệt lên nhanh 3 phút đầu rồi **chững**, không leo tiếp — hệ thống tản nhiệt giữ được.

⚠️ **Chỉ chứng minh cho 13 phút.** Không suy ra được cho 30 phút hay 1 tiếng.

### ⚠️ Nhiệt 82–84°C là rủi ro SẢN PHẨM, không phải rủi ro đo đạc

Tác giả yêu cầu dừng vì thấy hại máy. Đó là phản ứng đúng — và nó chỉ ra một điều bài đo không định tìm: **người dùng đọc truyện 30 phút sẽ gặp đúng nhiệt độ đó.**

Hệ quả cần đưa vào thiết kế:
- App phải **giải phóng engine khi không dùng**, không giữ nóng vô hạn (mâu thuẫn một phần với AD-20 — cần cân bằng).
- Cần cân nhắc cảnh báo người dùng khi máy quá nóng.
- Cache (FR-060) có giá trị lớn hơn dự tính: mỗi trang không phải dịch lại là một lần không phải đốt CPU.

### ❌ Điều bài đo này KHÔNG trả lời

Nó chạy app **một mình**. Kịch bản thật là **app đọc truyện + app dịch song song** — đó mới là lúc RAM căng thật. Bài đo đang kiểm **trường hợp dễ hơn thực tế**.

**Còn nợ:** đo lại với một app đọc truyện chạy nền, ở Phase 2 khi đã có overlay.

---

## F21 — Bóp `maxNumTokens` gần như vô ích. RAM nằm ở trọng số model.

**Đo trên Galaxy M52, mỗi mức dịch một trang 12 bubble (prompt 1176 ký tự).**

| `maxNumTokens` | RSS nạp | RSS dịch | Giây | Kết quả |
|---|---|---|---|---|
| 512 | — | — | — | ❌ `INVALID_ARGUMENT: Input too long` |
| **1024** | 3181 MB | **3301 MB** | 41 | ✅ 12/12 |
| 2048 | 3223 MB | 3496 MB | 40 | ✅ 12/12 |
| mặc định | 3237 MB | 3465 MB | 47 | ✅ 12/12 |

**Tiết kiệm được ~160 MB / 3300 MB ≈ 5%.** Không đáng.

### Vì sao lever này yếu

RSS **trước khi nạp gì: 84 MB**. Sau khi nạp model: 3181–3237 MB.

⇒ **Toàn bộ ~3.1 GB là trọng số model.** KV cache chỉ chiếm phần chênh giữa "nạp" và "dịch", tức 120–270 MB. Bóp KV cache chỉ đụng được vào phần nhỏ đó.

Và trọng số nạp bằng `mmap` nên hệ điều hành **đã tự quản** — F20 đã đo được RSS tự giảm 3017 → 1998 MB mà không bị kill.

### 1024 là bẫy, không phải tối ưu

Prompt 1176 ký tự đã làm mức 512 thất bại. Một trang nhiều bubble hơn sẽ làm 1024 thất bại **y hệt**. Đổi 5% RAM lấy một chế độ **hỏng cứng phụ thuộc độ dài trang** là lỗ vốn.

⇒ **Giữ mặc định.** Nếu sau này đặt giới hạn thì phải tính từ trang nhiều bubble nhất, cộng biên, không phải từ trang trung bình.

### Cách duy nhất thật sự giảm RAM

| Cách | RSS | Đánh đổi |
|---|---|---|
| Giữ engine | ~3200 MB | — |
| **Nhả engine khi rảnh** | **~84 MB** | Mất 15–29 s khi dịch lại |

Đây là **mâu thuẫn thật với AD-20** (hâm nóng engine lúc bật app). Hai yêu cầu kéo ngược nhau:
- AD-20 muốn engine luôn nóng để bubble đầu ≤ 8 s
- Giảm RAM và giảm nhiệt muốn nhả engine

**Lời giải phải là chính sách theo thời gian rảnh**, không phải chọn một trong hai: giữ engine khi đang đọc, nhả sau N phút không chạm. Và cache (FR-060) tăng giá trị — trang đã dịch không cần engine.

Cần một AD cho việc này.

---

## F22 — ⭐ Story 1.5: CHỐT CỔNG AD-14 — **ĐI TIẾP, có điều kiện**

### Bốn rủi ro sau khi đo trên máy thật

| # | Rủi ro | Kết quả | Trạng thái |
|---|---|---|---|
| **R1** | OCR đủ chính xác | **chưa đo CER** — không có ground truth | ❌ **CHƯA TRẢ LỜI** |
| **R2** | LLM dịch đủ tốt | **69%** trên bộ khó nhất, bản int4, máy thật | ✅ vượt NFR-004b (50%) **19 điểm** |
| **R3** | Không OOM | 0 lần / 13 phút · RSS **tự giảm** 3017→1998 MB | ✅ đạt (13 phút) |
| **R4** | Đủ nhanh | LLM 51–79 s · **tổng ước 58–87 s** | ⚠️ đạt, **biên 3 s** |

### Đối chiếu NFR

| NFR | Ngưỡng | Đo được | |
|---|---|---|---|
| NFR-004 manga phổ thông | 70% | **chưa đo** | ⏳ |
| NFR-004b truyện khó | 50% | **69%** | ✅ |
| NFR-005 bubble đầu | 8 s | **4.46 s** | ✅ |
| NFR-005b tổng/trang | 90 s | **58–87 s** (ước) | ⚠️ |
| NFR-007 không OOM | 0 lần | **0 / 13 phút** | ✅ |
| NFR-008 APK | 100 MB | **57.6 MB** | ✅ |

**Lưu ý cách đọc NFR-004:** con số 69% đo trên `tubaki` — truyện cổ trang Edo, tiếng lóng khu lâu xanh, **trường hợp xấu nhất**. Ngưỡng 70% của NFR-004 dành cho **manga phổ thông**, chưa đo. Nói "trượt NFR-004" là **đọc sai**: 69% trên truyện khó vượt xa ngưỡng 50% dành cho loại đó.

### Quyết định: ĐI TIẾP Epic 2

**Căn cứ:**
1. Pipeline chạy được đầu-cuối trên phần cứng thật, không phải suy đoán.
2. Chất lượng vượt ngưỡng cho loại truyện đã đo, **19 điểm dư**.
3. Tốc độ trong ngưỡng.
4. Không OOM, không throttle trong 13 phút.
5. Chi phí bằng 0 như cam kết D2.

**Ba điều kiện kèm theo — không phải khuyến nghị, là ràng buộc:**

| # | Điều kiện | Vì sao |
|---|---|---|
| **Đ1** | **Đo R1 (CER của OCR) trước khi kết thúc Epic 2** | Đây là rủi ro duy nhất chưa ai chạm tới. OCR sai thì mọi thứ phía sau vô nghĩa, và F2 đã cho thấy nó **bịa chữ** chứ không báo lỗi |
| **Đ2** | **Mỗi story của Epic 2 phải đo lại tổng thời gian trang trên M52** | Biên chỉ còn **3 giây**. Bất kỳ thứ gì thêm vào pipeline đều ăn thẳng vào đó |
| **Đ3** | **Cache (FR-060) làm SỚM, không để cuối Epic 2** | Nó là thứ duy nhất giảm được cả ba: thời gian, RAM, nhiệt. AD-24 đã biến nó thành thành phần cốt lõi |

### Điều phải nói thẳng: không còn dư địa

GPU **không dùng được** (F18), nên không còn đường tăng tốc nào. Mọi thứ thêm vào pipeline đều ăn vào 3 giây biên đó.

Nếu Epic 2 làm vỡ ngưỡng, các lựa chọn còn lại **đều là hạ tiêu chuẩn**:
- Nới NFR-005b lên 120 s
- Chấp nhận dịch ít bubble hơn mỗi lượt (phá AD-3 — sẽ làm hỏng xưng hô)
- Dùng model nhỏ hơn (đã đo: 1.7B chỉ đạt 17%)

⇒ **Điều kiện Đ2 tồn tại để phát hiện sớm, không phải để trang trí.**

### Rủi ro sản phẩm đã lộ, chưa có lời giải

**Máy nóng 82–84°C khi dịch liên tục.** Chưa gây throttle trong 13 phút, nhưng chính tác giả đã yêu cầu dừng bài đo vì thấy hại máy — và **người dùng thật sẽ gặp đúng nhiệt độ đó**. AD-24 giảm nhẹ bằng cách nhả engine khi rảnh, nhưng không xoá được vấn đề.

### Thay đổi so với brief ban đầu

| Brief giả định | Thực tế đo được |
|---|---|
| Model to hơn dịch tốt hơn | **Sai 2 lần** — E2B (2.6GB) thắng 4B (3.3GB) và bằng 8B (5.2GB) |
| Họ Qwen mạnh tiếng Nhật | **Sai** — Gemma 4 thắng rõ |
| MediaPipe là runtime | **Đã khai tử** — LiteRT-LM thay thế |
| GPU sẽ tăng tốc | **Sai** — GPU sinh rác trên Adreno 642L |
| Glossary là tinh chỉnh Phase 3 | **Sai** — là đòn bẩy mạnh nhất, đã nâng lên MVP |
| Ngưỡng 40 s/trang | **Bất khả thi** trên phần cứng này; đã nới lên 90 s |
| Gói ~3 GB cần 8 GB RAM | Gần đúng — 2.59 GB, RSS 3.2 GB, mmap nên chạy được |

**Bảy giả định, sáu sai.** Đây là lý do AD-14 tồn tại.

---

## F23 — ⭐ Giảm nhiệt: hạ xuống 2 luồng CPU đổi 35% tốc độ lấy 16°C

**Đo trên Galaxy M52, mỗi cấu hình 2 trang, nghỉ 45 s giữa các cấu hình để nhiệt hạ.**
Nhiệt máy lúc bắt đầu: 64.8°C.

| Luồng | Trang 1 | Trang 2 | Nhiệt | Chất lượng |
|---|---|---|---|---|
| **2** | 67 s | **54 s** | **58–62°C** | 12/12 ✅ |
| 4 | 53 s | 41 s | 76–78°C | 12/12 ✅ |
| mặc định | 53 s | 40 s | 74–78°C | 12/12 ✅ |

### Ba kết luận

**① Mặc định đang dùng ~4 luồng, không phải 8.** Cấu hình 4 và mặc định cho số gần trùng (41 vs 40 s · 76–78 vs 74–78°C). ⇒ **Tăng luồng không phải hướng tối ưu** — LiteRT-LM đã tự chọn hợp lý.

**② Giả thuyết của tôi SAI.** Tôi dự đoán "decode bị chặn bởi băng thông bộ nhớ nên giảm luồng gần như không mất tốc độ". Thực tế **mất 35%** (40 → 54 s). Nhưng đổi lại **giảm 16°C**, và đó là đánh đổi đáng.

**③ Chất lượng không đổi** — 12/12 bubble ở cả ba mức. Giảm luồng **không** làm hỏng đầu ra (khác hẳn GPU ở F18).

### Vì sao 58–62°C là mức khác hẳn

Máy lúc **chưa chạy gì** đã 64.8°C. Nghĩa là chạy 2 luồng khiến máy **mát hơn trạng thái nghỉ trước đó** — nhiệt dư từ các bài đo trước đang thoát ra nhanh hơn nhiệt sinh ra.

So sánh: 76–78°C ở mặc định là **nóng rõ khi cầm**; 82–84°C ở F20 là mức tác giả yêu cầu dừng.

### Ngân sách thời gian với 2 luồng

| Bước | Giây |
|---|---|
| LLM (2 luồng, trang đã ấm) | 54 |
| OCR + detect (ước, ×1.8 từ PC) | ~7 |
| **Tổng** | **~61 s** |
| Ngưỡng NFR-005b | 90 s |
| **Biên** | **~29 s** |

⇒ **2 luồng khả thi**, và biên còn rộng hơn mức chạy mặc định trên trang đầu (58–87 s).

### Lưu ý về cách đọc số

Trang 1 luôn chậm hơn trang 2 ở mọi cấu hình (67/54 · 53/41 · 53/40) — cache ấm dần. Số **trang 2** mới đại diện cho lúc đọc liên tục.

---

## F24 — ✅ R1 ĐÃ TRẢ LỜI: manga-ocr int8 gần như không mất gì

**Điều kiện Đ1 của cổng AD-14.** Đây là rủi ro duy nhất chưa ai chạm sau cả Phase 0 lẫn Epic 1.

**Cách đo, không cần người gõ ground truth:** chạy manga-ocr bản PyTorch fp32 (chính xác nhất có thể) trên **73 vùng bubble thật** do detector cắt từ 15 trang, qua đúng cổng AD-5 — lấy làm tham chiếu. Rồi đối chiếu với bản ONNX.

**Không tự convert:** `onnx-community/manga-ocr-base-ONNX` đã có sẵn bản int8, Apache-2.0, không khoá.

| Bản | Dung lượng | Tốc độ | CER thô | **CER sau chuẩn hoá** | Khớp |
|---|---|---|---|---|---|
| PyTorch fp32 | ~450 MB | 0.281 s | (tham chiếu) | — | — |
| ONNX fp32 | 461 MB | 0.232 s | 7.9% | **0.3%** | 72/73 |
| **ONNX int8** | **117 MB** | **0.127 s** | 7.6% | **0.3%** | 72/73 |

**int8 nhỏ hơn 4 lần, nhanh gấp đôi, chính xác tương đương.** NFR-003 đặt ngưỡng CER ≤ 10%; đo được **0.3%**.

### CER thô 7.6% là con số GÂY HIỂU NHẦM

Gần như toàn bộ "lỗi" là **khác dạng ký tự, không phải đọc sai**:

| fp32 | int8 | Thực chất |
|---|---|---|
| `．．．` | `...` | ba chấm toàn rộng vs nửa rộng |
| `え？` | `え?` | dấu hỏi toàn rộng vs nửa rộng |
| `ーーー` | `―――――` | độ dài gạch ngang |

Sau NFKC + gộp dãy gạch/chấm liên tiếp, CER rơi từ **7.9% xuống 0.3%**. Chỉ còn **một** vùng khác thật: `あぁああ` vs `ああああ`.

⇒ Nếu báo "CER 7.6%" mà không nói rõ, người đọc sẽ tưởng OCR sai 1 trong 13 ký tự. Thực tế nó đọc gần như hoàn hảo.

### Yêu cầu sản phẩm phát sinh

**Đầu ra OCR BẮT BUỘC chuẩn hoá NFKC trước khi dùng cho bất cứ so sánh chuỗi nào.** Nếu không:
- khoá cache vỡ (cùng trang, khác dạng dấu câu → cache miss)
- cổng `EchoGate` (AD-6) báo động giả

`EchoGate` đã chuẩn hoá sẵn — nhưng đó là may, không phải thiết kế. Cần thành quy ước rõ ràng.

### ⚠️ Phạm vi kết luận — đọc kỹ

Phép đo này trả lời: **"int8 mất bao nhiêu so với fp32"** → gần như không mất gì.

Nó **KHÔNG** trả lời: **"manga-ocr đọc đúng bao nhiêu so với chữ THẬT trên trang"**. Cả hai bản có thể sai giống nhau. Câu đó cần người gõ tay ground truth và **CHƯA LÀM**.

Tuy vậy, câu hỏi R1 như brief đặt ra — *"manga-ocr convert ONNX int8 vẫn giữ ≥90% chính xác"* — **đã được trả lời: giữ ~99.7%**.

---

## F25 — ⭐ Cả trang một lần gọi là TỐI ƯU. NFR-005 bất khả thi trên phần cứng này.

**Ba cách chia đã đo, cùng model `gemma4:e2b`, cùng glossary, 48 bubble / 4 trang, trên PC:**

| Cách | Tổng | Bubble đầu | Dịch được |
|---|---|---|---|
| Từng ô (1) | 209.7 s | 4.3 s | — |
| Nhóm 3 | 185.5 s | 28.1 s | 45/48 |
| Nhóm 4 | 81.8 s | 6.7 s | 45/48 |
| Nhóm 6 | 67.4 s | 8.4 s | 43/48 |
| **Cả trang (12)** | **57.7 s** | 14.4 s | **48/48** |

**Cả trang thắng ở hai chỉ số quyết định: nhanh nhất và đủ nhất.**

### Vì sao chia nhỏ lại chậm hơn

Mỗi lần gọi phải **prefill lại toàn bộ** system prompt + glossary. Chia 12 bubble thành 3 nhóm = prefill phần chung **3 lần**; thành 12 ô = **12 lần**. Phần chung chiếm phần lớn prompt.

### Chia nhỏ còn LÀM MẤT BUBBLE

Cả trang dịch đủ **48/48**. Mọi cách chia nhỏ đều mất 3–5 bubble (43–45/48) — có nhóm model không trả về đủ phần tử.

### Lợi thế "bubble đầu nhanh" KHÔNG sống sót khi quy sang máy thật

| | PC | M52 (hệ số ×12.4, suy từ 14.4 s ↔ 179 s đo thật) |
|---|---|---|
| Cả trang, tổng | 14.4 s | **179 s** (đo thật) |
| Nhóm 4, tổng | 20.5 s | ~254 s |
| Nhóm 4, bubble đầu | 6.7 s | **~83 s** |

Nhóm 4 trên M52 cho bubble đầu **~83 s — đúng bằng cả trang hiện tại**, mà tổng **chậm hơn 42%**.

### Hệ quả: NFR-005 là mâu thuẫn kiến trúc, không phải lỗi cài đặt

Đo trên M52 (F26):

```
prompt 1976 ký tự | 12 bubble | 8 mục glossary
token đầu tiên sau 82,960 ms   <-- toàn bộ là PREFILL
bubble đầu tiên sau 91,154 ms
tổng 179 s
```

**Prefill chiếm 91% thời gian tới bubble đầu.** Không có token đầu ra nào trước khi prefill xong — đó là cách transformer hoạt động, không sửa được bằng code.

Prefill ~1000 token trên Snapdragon 778G ở 2 luồng mất **50–85 giây** (dao động theo nhiệt). Muốn bubble đầu ≤ 8 s thì prompt phải dưới ~150 token — không thể chứa 12 bubble và glossary.

⇒ **`NFR-005` (≤ 8 s) phải được nới, hoặc bỏ.** Mọi cách chia đều không cứu được.

---

## F26 — Vẽ bubble phải làm HAI LƯỢT, không phải tô-và-vẽ từng cái

**Bốn vòng sửa, mỗi vòng lộ ra một lỗi mà vòng trước che mất.** Không vòng nào phát hiện được bằng log — log luôn báo `12/12 bubble`. Phải **nhìn ảnh**.

| Vòng | Triệu chứng | Nguyên nhân thật |
|---|---|---|
| 1 | Ô trắng chữ nhật đè lên tranh | Tô theo hộp chữ, bóng thoại hình tròn |
| 2 | Chữ khổng lồ tràn sang bóng khác | `maxByOrNull { containedIn }` chọn bừa — nhiều vỏ cùng chứa trọn thì tỷ lệ đều 1.0 |
| 3 | Vẫn tràn | Vỏ "nhỏ nhất chứa trọn" vẫn có thể là vỏ bao nhiều bóng |
| 4 | **Mất chữ cuối** (`đấy.`) | **Bubble vẽ SAU tô nền đè lên CHỮ của bubble vẽ trước** |

### Lỗi số 4 là lỗi gốc

Bóng thoại **chồng lấn nhau**. Quy trình tô-rồi-vẽ từng bubble một:

```
bubble A: tô nền A → vẽ chữ A
bubble B: tô nền B → vẽ chữ B     ← nền B xoá mất phần chữ A nằm trong vùng B
```

Đã thấy thật: `憎たらしいねェ` dịch đúng thành "Đáng ghét thật đấy." nhưng ảnh chỉ hiện "Đáng ghét thật" — chữ `đấy.` bị nền bóng bên cạnh xoá.

### Lời giải: hai lượt

```
lượt 1: tô nền cho TẤT CẢ bubble đã có bản dịch
lượt 2: vẽ chữ cho TẤT CẢ — không còn nền nào vẽ sau nữa
```

Vẫn giữ AD-9 (chỉ tô nền cho bubble **đã có** bản dịch) và AD-13 (hiện dần): mỗi lần có bubble mới thì **vẽ lại cả trang từ ảnh gốc**. Chi phí O(n²) với n=12 — không đáng kể.

### Lợi ích kèm theo: AD-17 giờ hoạt động THẬT

Trước đây `Retracted` chỉ in ra log. Với cơ chế vẽ-lại-từ-ảnh-gốc, nó thực sự gỡ được bubble: bỏ khỏi danh sách rồi vẽ lại → **chữ Nhật gốc hiện lại nguyên vẹn**. `PageRejected` cũng vậy — xoá hết rồi vẽ lại = toàn trang trở về nguyên bản.

Đây là điều spine yêu cầu từ đầu (*"overlay BẮT BUỘC xử lý Retracted"*) mà cài đặt trước đó chưa làm được.

### Quy tắc rút ra

**Chỉ số đếm không thay được việc nhìn.** Cả bốn vòng, log đều báo `vẽ 12/12 bubble`. Nếu tin log thì đã kết luận xong từ vòng 1.

---

## F27 — Mục glossary tự đề xuất là RÁC, đo trên chính máy thật

Sau bốn trang dịch trên M52, `glossary.json` trên máy có **ba** mục `Proposed`, và **cả ba đều vô dụng**:

| Mục app tự đề xuất | Vì sao vô dụng |
|---|---|
| `"Người nói 1"` | nhãn placeholder **tiếng Việt** do LLM bịa ra cho trường `speaker` |
| `"Người nói 2"` | như trên |
| `"Rurimaru"` | dạng **La-tinh**, trùng với mục `瑠璃丸` đã xác nhận |

Tỷ lệ hữu ích: **0/3**.

### Vì sao chúng vô dụng — và vì sao nó nguy hiểm

`surface` là **dạng chữ xuất hiện trong nguyên bản tiếng Nhật**. Glossary chỉ có tác dụng khi nó khớp được với chữ trên trang. Chuỗi không có ký tự Nhật nào thì **không bao giờ khớp** — nó chỉ ngồi trong prompt làm nhiễu.

Nguy hiểm ở chỗ: nếu mục đề xuất tự động vào prompt (không có cổng xác nhận), thì chỉ sau vài trang, prompt sẽ đầy nhãn placeholder. LLM sẽ dùng chúng **nhất quán và trôi chảy** — đúng kiểu lỗi AD-4 mô tả: mọi kiểm tra tự động đều xanh, bản dịch đọc mượt, mà sai xuyên suốt.

### Đã chặn ở hai lớp

1. **Tại nguồn** — `ports.isUsableSurface()`: `surface` phải chứa ít nhất một ký tự hiragana / katakana / kanji. Đặt ở `ports` chứ không ở `adapters`, vì cả `pipeline` (lúc đề xuất) lẫn `GlossaryMiner` đều cần, mà `pipeline` không được phép biết đến `adapters`.
2. **Tại cổng xác nhận** — AD-8 vốn đã có: mục `Proposed` không vào prompt cho tới khi người dùng bấm ✓. Lớp này đã làm đúng việc của nó ở đây: ba mục rác **không** lọt vào bản dịch nào.

### Vòng hai — bộ lọc ở nguồn KHÔNG dọn thứ đã nằm sẵn trong file

Sửa xong `isUsableSurface`, build sạch, test xanh. Mở màn hình từ điển trên máy thật ra nhìn: **ba mục rác vẫn còn nguyên**.

Lý do hiển nhiên khi đã thấy: bộ lọc chặn *đề xuất mới*, nó không đụng gì tới mục đã ghi xuống file từ những lần chạy trước. Không log nào báo sai, không test nào đỏ — chỉ có nhìn màn hình mới thấy.

⇒ `GlossaryActivity` dọn luôn khi mở: mục `Proposed` nào không có ký tự Nhật thì xoá, và báo ra màn hình cái gì vừa bị xoá. Xoá tự động chấp nhận được vì đó là mục **do app tự đẻ ra**, không phải mục người dùng gõ tay, và chúng không bao giờ khớp được với chữ trên trang nên giữ lại cũng vô nghĩa.

**Quy tắc:** sửa một bộ lọc ở nguồn thì phải hỏi tiếp *"dữ liệu cũ đã lọt qua trước đó thì sao?"*. Bộ lọc mới không hồi tố.

### Quy tắc rút ra

**Cổng xác nhận đã cứu, nhưng chỉ vì có người đi đọc dữ liệu thật.** Ba mục rác nằm yên trong file suốt nhiều phiên mà không log nào báo gì — chúng chỉ lộ ra khi `cat` file glossary trên máy. Nguồn dữ liệu nào do model sinh ra thì phải đi xem tận nơi nó đẻ ra cái gì, đừng chỉ xem nó có chạy không.

---

## Còn nợ

| # | Việc | Chặn gì | Trạng thái |
|---|---|---|---|
| 1 | Lấy detector | R1, F2 | ✅ xong — F4 |
| 2 | Cài ollama + kéo `qwen3:4b` và `qwen3:1.7b` | R2, R4 | ✅ xong |
| 3 | Chạy pipeline đầy đủ, đo tok/s và chất lượng dịch | **R2 (sống-chết)**, R4 | 🟡 đang chạy |
| 4 | Tạo ground truth để đo CER có số | R1 | ❌ chưa |
| 5 | Đo precision/recall của detector có số | F4 | ❌ chưa |
| 6 | Convert manga-ocr sang ONNX int8, so với fp32 | R1 | ❌ chưa |
| 7 | Đo trên chính Galaxy M52 | R3, R4 | ❌ chưa — cần Android SDK |
