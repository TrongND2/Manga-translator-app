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

## F28 — Thêm một thành phần là nạp thêm một bộ model, không ai báo gì

Ngay lần đầu chạy Epic 3 trên M52, log chứng minh app nạp **hai** bộ model trong **cùng một tiến trình**:

```
11:44:18.161  31347 31378  I MangaTrans: encoder: encoder_model_fp16.onnx (171 MB)
11:44:18.467  31347 31380  I CaptureSvc: encoder: encoder_model_fp16.onnx (171 MB)
```

Cùng `pid 31347`, khác thread. Tức là **2 × 171 MB ONNX + 2 × 2.6 GB LLM** trên máy 8 GB.

### Vì sao chốt cũ không cứu được

`MainActivity` đã có `AtomicBoolean setupOnce` — thêm vào từ Epic 2 sau khi bị OOM vì Activity tạo lại. Nhưng nó chỉ chặn **Activity tự nạp lại chính nó**. `CaptureService` là một thành phần khác, nó không biết gì về cái cờ đó.

Đây là kiểu lỗi sinh ra khi **thêm thành phần thứ hai vào một thiết kế vốn chỉ có một**. Chốt đặt đúng ở mức thành phần hôm qua, hôm nay thành đặt sai mức.

⇒ Chuyển sang **singleton cấp tiến trình** trong `Composition`, khoá bằng `Mutex` (`suspend`, nên không dùng `@Synchronized` được — và bên gọi sau phải **chờ** bản nạp đầu tiên xong rồi dùng chung, chứ không được nạp song song).

Đo lại sau khi sửa: đúng **1** dòng `encoder:` trong log, LLM nóng sau 14–15 s.

### Quy tắc rút ra

**Chốt chống-nạp-hai-lần phải đặt ở phạm vi của tài nguyên, không phải phạm vi của người gọi.** Model sống theo tiến trình, nên chốt cũng phải ở tiến trình. Cờ nằm trong Activity chỉ đúng chừng nào Activity còn là nơi duy nhất nạp — một giả định không ai viết ra, và không ai kiểm.

---

## F29 — Ba thứ chỉ lộ ra khi chạy Epic 3 trên máy

Cả ba đều biên dịch sạch hoặc không có dấu hiệu gì cho tới khi chạy thật.

| Thứ | Triệu chứng | Lời giải |
|---|---|---|
| `TOUCHABLE_INSETS_REGION` | `ViewTreeObserver.OnComputeInternalInsetsListener` và `InternalInsetsInfo` là API **`@hide`**, không có trong SDK công khai — build đỏ | Lớp phủ dùng `FLAG_NOT_TOUCHABLE`: không bao giờ nhận chạm nên không thể chặn gì. Story 3.6 (chạm giữ để liếc nguyên bản) sẽ cần **cửa sổ nhỏ riêng đặt đè lên từng bubble** |
| Theme của Activity trong suốt | `AppCompatActivity` + `@android:style/Theme.Translucent.NoTitleBar` ⇒ `IllegalStateException: You need to use a Theme.AppCompat theme`. **Sập thật trên máy** | Dùng `androidx.activity.ComponentActivity` — đủ cho `registerForActivityResult`, không ràng buộc theme |
| Service `exported="false"` | `am start-foreground-service` báo `Requires permission not exported from uid` — đúng về bảo mật, nhưng không kiểm bằng `adb` được | Vào qua `MainActivity --ez overlay true`, cùng kiểu với `--ez auto` / `--ez glossary` đã có |

**Quy tắc:** ba thứ này không có cái nào lộ ra từ đọc tài liệu. Nối lại đúng bài học đã ghi ở CLAUDE.md — *đọc mã nguồn, đừng tin tài liệu* — và thêm một vế: **API `@hide` trông y hệt API thật trong tài liệu và trong IDE.**

---

## F30 — `displayMetrics` KHÔNG phải kích thước màn hình, và sai đó làm chữ Nhật ló ra

Lượt dịch qua chụp màn hình đầu tiên chạy được đầu-cuối: 12 bóng thoại đều ra tiếng Việt, vẽ đúng chỗ. Nhưng nhìn ảnh thì **chữ Nhật gốc vẫn ló ra ở mép phải mỗi bóng**, và bản dịch hơi lệch sang trái.

### Đo, không đoán

```
$ adb shell wm size
Physical size: 1080x2400

$ adb shell dumpsys window displays
init=1080x2400 420dpi   cur=1080x2400   app=1080x2184
```

`resources.displayMetrics` trả về **`app=1080x2184`** — đã trừ thanh điều hướng. Màn hình thật là **1080x2400**.

### Vì sao sai đó biến thành chữ Nhật ló ra

`VirtualDisplay` tạo theo 1080×2184 có **tỷ lệ khác** màn hình thật. Cờ `AUTO_MIRROR` xử lý bằng cách thu nhỏ cho vừa:

```
hệ số  = 2184 / 2400 = 0.910
bề rộng nội dung sau khi thu = 1080 × 0.910 = 983 px
viền đen mỗi bên = (1080 − 983) / 2 = 48 px
```

Nên ảnh chụp là trang truyện **nhỏ hơn 0.91 lần, nằm giữa hai viền đen 48 px**. Hộp bóng thoại detector tìm ra nằm trong hệ toạ độ đó. Vẽ 1:1 lên màn hình thật thì mọi thứ **dịch sang trái ~48 px và nhỏ hơn ~9%** — ô nền không phủ hết bóng, phần chữ Nhật bên phải lộ ra.

Con số 48 px khớp đúng độ lệch đo được trên ảnh.

⇒ Dùng `WindowManager.currentWindowMetrics.bounds` (API 30+) / `Display.getRealMetrics()` — cả hai đều tính cả vùng thanh hệ thống.

### Quy tắc rút ra

**Lỗi hình học không báo lỗi, nó báo "dịch thiếu".** Triệu chứng nhìn thấy là *nội dung* sai — chữ Nhật còn sót — nên phản xạ đầu tiên là đi ngờ OCR hoặc mô hình dịch. Cả hai đều vô tội. Nối tiếp F26: **một lượt vẽ trông gần đúng vẫn có thể sai ở tầng hoàn toàn khác.**

Và: tên `displayMetrics` gợi ý nó là số đo của màn hình. Nó là số đo **vùng mà app được vẽ**. Hai thứ khác nhau, chỉ trùng nhau trên máy không có thanh điều hướng.

---

## F31 — Lớp phủ bị cộng offset HAI LẦN, và tôi đã đoán sai ba vòng trước khi chịu đi đo

**Triệu chứng:** bản dịch vẽ đúng bóng thoại, nhưng **đỉnh mỗi bóng vẫn còn chữ Nhật** và ô nền tràn xuống dưới bóng. Nhìn thì y hệt "ô nền quá nhỏ".

### Ba vòng đoán, cả ba đều sai

| Giả thuyết | Kết quả |
|---|---|
| Detector bắt thiếu bóng | ❌ Đo: `gate: 26 vùng` — detector tìm **đủ 12/12**, bằng đúng Epic 2 trên ảnh gốc |
| Thiếu vỏ bóng nên chỉ tô được hộp chữ | ❌ Đo: **11/12 có vỏ bóng** |
| Ellipse nội tiếp hở rìa → đổi sang chữ nhật bo góc | ❌ Ảnh **không đổi gì** |

Mỗi vòng đều "hợp lý" và đều sai. Vòng thứ ba còn tệ hơn: tôi tự tay viết một lỗi mới (`padded()` lấy *giao* với vỏ bóng, mà cổng AD-5 cho phép hộp chữ thò ra 10% — phép giao cắt đúng phần thò ra, làm ô nền **nhỏ hơn cả hộp chữ**).

### Đo thì ra ngay

Ghi hình học từng vùng — toạ độ và kích thước, **không** ghi nội dung:

```
#4 Accepted box=236,499 90x131  shell=217,484 121x178
```

Vỏ bóng #4 có tâm y = 484 + 178/2 = **573** trong toạ độ ảnh chụp. Trên màn hình, chữ Việt của chính nó hiện ra ở tâm y ≈ **726**.

```
lệch = 726 − 573 = 153 px
status bar ≈ 76 px
153 ≈ 2 × 76
```

**Đúng gấp đôi.** Ảnh chụp bị cắt `statusBarPx` ở trên (AD-11) nên khi vẽ phải cộng lại chừng ấy — nhưng **cửa sổ lớp phủ đã bắt đầu sẵn ở ngay dưới status bar**, nên phép cộng đó là lần thứ hai.

### Lời giải: tự đo, đừng giả định

```kotlin
getLocationOnScreen(loc)
canvas.translate(0f, (offsetY - loc[1]).toFloat())
```

Công thức này đúng ở **cả hai** trường hợp: cửa sổ bắt đầu ở y=0 thì `loc[1]=0` và offset giữ nguyên; cửa sổ bắt đầu dưới status bar thì `loc[1]=statusBarPx` và offset thành 0. Không phải đoán cửa sổ nằm ở đâu, cũng không phải viết ngoại lệ cho từng hãng máy.

### Quy tắc rút ra

**F26 nói "phải nhìn ảnh". Chưa đủ — nhìn ảnh rồi vẫn suy diễn sai nguyên nhân được.** Tôi đã nhìn ảnh cả ba vòng, và cả ba lần đều đọc ra sai nguyên nhân từ cùng một tấm ảnh, vì "ô nền không phủ hết" và "ô nền bị đẩy lệch" trông **giống hệt nhau**.

Thứ cắt đứt được vòng lặp là **một con số**: 153 so với 76. Nhìn để biết *có lỗi*; đo để biết *lỗi ở đâu*. Nối tiếp F30 — cả hai lần, lỗi hình học đều cải trang thành lỗi nội dung.

---

## F32 — Hai tính năng đều đúng, ghép lại thì tự huỷ nhau

Story 3.6 (chạm giữ để liếc nguyên bản) và Story 3.7 (lớp phủ tự biến mất khi sang trang) viết riêng đều chạy đúng. Ghép vào thì:

```
chạm giữ  →  lớp phủ ẩn đi để lộ chữ Nhật
          →  bộ canh của 3.7 thấy màn hình đổi
          →  tưởng người dùng sang trang  →  XOÁ CẢ TRANG
thả tay   →  bản dịch mất hẳn
```

Không lỗi nào sai theo tiêu chí của chính nó. 3.7 làm **đúng** việc nó được giao: màn hình đổi thì gỡ lớp phủ. Nó chỉ không phân biệt được *ai* làm màn hình đổi.

### Vòng một: đánh dấu "app tự làm đổi" — vẫn hỏng

Thêm cờ `selfChanging`, bộ canh bỏ qua khi cờ bật. Chạy lại: **vẫn bị xoá**.

### Vòng hai: `ImageReader` giữ frame cũ

`ImageReader` đệm tới `MAX_IMAGES` ảnh. Sau khi thả tay:

```
t=0     thả tay, cờ selfChanging hạ, lớp phủ hiện lại
t=+ε    bộ canh gọi acquireLatestImage()
        → trả về frame chụp LÚC ĐANG ẨN (còn nằm trong hàng đợi)
        → lấy làm mốc so sánh
t=+350  frame mới (đã hiện lại) khác mốc  →  XOÁ
```

Mốc so sánh là ảnh của **trạng thái đã qua**. ⇒ Khi cờ hạ: **vứt hết frame đang xếp hàng**, chờ một nhịp, vứt lần nữa, rồi mới lấy mốc.

### Đo sau khi sửa

| | kích thước ảnh chụp màn hình |
|---|---|
| trước khi giữ | 2 286 234 |
| **đang giữ** | 2 314 428 ← chữ Nhật gốc hiện |
| sau khi thả | **2 286 234** ← bằng đúng lúc trước |

Và bấm HOME (đổi thật) vẫn gỡ đúng: `noi dung ben duoi doi — go lop phu`.

### Quy tắc rút ra

**Một bộ phát hiện thay đổi phải biết phân biệt thay đổi do mình gây ra.** Bất kỳ tính năng nào về sau cũng tự làm màn hình đổi — hiện hộp thoại, đổi trạng thái icon, chớp một hiệu ứng — và mỗi cái sẽ lại giết lớp phủ theo đúng cách này. Cờ `selfChanging` là chỗ chung để khai báo, không phải vá riêng cho Story 3.6.

**Và: hàng đợi frame làm cho "hiện tại" không phải hiện tại.** Cờ hạ không có nghĩa là ảnh tiếp theo đã phản ánh trạng thái mới. Đây là biến thể của cùng một sai lầm ở F31 — giả định về thời điểm/vị trí thay vì đo nó.

---

## F33 — Chứng minh "ảnh chụp sạch" bằng hash, không bằng mắt

AD-11 đòi `ScreenSource.capture()` tự ẩn icon và mọi lớp phủ trước khi chụp. Nếu hỏng thì OCR sẽ đọc lại **chính chữ Việt app vừa vẽ** rồi dịch tiếng Việt sang tiếng Việt — và kết quả vẫn trông trôi chảy, vẫn đủ 12 bubble. Nhìn ảnh không đủ để phân biệt.

### Phép thử có tín hiệu rõ ràng

Dịch **cùng một trang hai lượt liên tiếp**, lượt hai chạy **trong khi bản dịch lượt một vẫn đang hiện trên màn hình**. So `contentKey` — hash tính trên đúng các vùng bubble đã phát hiện:

| | lượt 1 | lượt 2 |
|---|---|---|
| `contentKey` | `e70f4c0d573f9d47` | **`e70f4c0d573f9d47`** |
| gate | 26 vùng, 11 vỏ bóng | 26 vùng, 11 vỏ bóng |
| vẽ | 12 bubble | 12 bubble |

Hash **giống hệt**. Nếu lớp phủ lọt vào ảnh chụp thì nội dung trong vùng bubble đã khác, và `contentKey` phải khác — nó được thiết kế đúng để nhạy với chỗ đó.

### Vì sao chọn `contentKey` chứ không phải `frameHash`

`frameHash` tính trên **cả khung hình**, nên đồng hồ nhảy phút cũng làm nó đổi — không dùng làm bằng chứng được. `contentKey` chỉ tính trên vùng bubble, trên ảnh đã hạ mẫu, nên nó bỏ qua đồng hồ và mức pin nhưng **không** bỏ qua chữ vẽ đè trong bóng thoại. Đúng hai vai, đúng AD-18.

### Quy tắc rút ra

**Khi lỗi và không-lỗi trông giống nhau, đừng kiểm bằng mắt — tìm một đại lượng mà chúng khác nhau.** Nối tiếp F31: ở đó tôi nhìn ảnh ba vòng và đọc sai nguyên nhân ba lần. Ở đây phép thử được thiết kế sao cho câu trả lời là một phép so sánh chuỗi, không phải một phán đoán thị giác.

---

## F34 — App SẬP NGAY trên Android 14+, và M52 không bao giờ lộ ra được

Story 3.9 tồn tại để chạy những đường code mà thiết bị đo chuẩn không chạm tới (AD-22). Lần chạy đầu tiên trên máy ảo Android 16 (SDK 36): **app sập trước cả khi hiện được icon**.

```
SecurityException: Starting FGS with type mediaProjection targetSDK=36
  requires all of  [FOREGROUND_SERVICE_MEDIA_PROJECTION]
  and     any of   [CAPTURE_VIDEO_OUTPUT, android:project_media]
```

Manifest **đã** khai `FOREGROUND_SERVICE_MEDIA_PROJECTION`. Vế thiếu là `android:project_media` — đó không phải quyền khai trong manifest mà là **appop được cấp khi người dùng bấm "Start now"**.

Nghĩa là: từ Android 14, **không được khởi động foreground service loại `mediaProjection` trước khi có quyền chụp**. Mà thiết kế của app làm đúng thế — icon nổi phải hiện trước, người dùng chạm icon rồi mới hỏi quyền (Story 3.1 → 3.2).

### Hai loại, không phải một

Service khởi động ở loại `specialUse` (lúc đó nó chỉ giữ icon nổi, chưa chụp gì), rồi **nâng cấp** sang `mediaProjection` ngay sau khi người dùng đồng ý — gọi lại `startForeground` với loại mới.

### Vòng hai: mỗi LOẠI đòi một QUYỀN riêng

Đổi xong, chạy lại vẫn sập:

```
SecurityException: Starting FGS with type specialUse targetSDK=36
  requires all of [FOREGROUND_SERVICE_SPECIAL_USE]
```

Khai loại trong `<service>` là **chưa đủ** — phải khai thêm `<uses-permission>` tương ứng. Và đây là lỗi **lúc chạy**, không phải lỗi lúc build: manifest hợp lệ, APK cài được, chỉ sập khi service khởi động.

### Vì sao M52 không bao giờ bắt được

M52 chạy Android 13. Toàn bộ cơ chế `foregroundServiceType` + quyền theo loại chỉ áp dụng từ Android 14. Đường code này **chưa từng chạy** trong suốt Epic 3, dù Epic 3 đã được kiểm bằng mắt rất nhiều lần trên máy thật.

### Vòng ba: thứ tự là một vòng tròn nếu làm sai

Sửa xong hai cái trên, chạy lại vẫn sập — lần này ở chỗ khác:

```
SecurityException: Media projections require a foreground service of type
  ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
```

`getMediaProjection()` đòi service **đã** ở loại `mediaProjection`; mà khởi động loại đó lại đòi **đã có** quyền chụp. Nhìn qua thì là vòng tròn.

Lối thoát: quyền được cấp ngay khi người dùng bấm đồng ý (appop `android:project_media`), **trước** khi ta gọi `getMediaProjection`. Nên thứ tự đúng là:

```
có quyền  →  NÂNG loại foreground service  →  rồi mới lấy projection
```

Code cũ làm ngược hai bước cuối.

### Đo sau khi sửa, trên Android 16

| | máy ảo Android 16 | M52 Android 13 |
|---|---|---|
| Màn hình | 1080×2340, status bar **145 px** | 1080×2400, status bar 76 px |
| Khởi động service | ✅ không ngoại lệ | ✅ |
| Hộp thoại xin quyền | ✅ có thêm lựa chọn **"Share one app"** của Android 14+ | không có lựa chọn này |
| Chụp + phát hiện | ✅ 25 vùng, 11 vỏ bóng | 26 vùng, 11 vỏ bóng |

Hai máy khác nhau cả kích thước màn hình lẫn chiều cao status bar, nên đây cũng là phép kiểm thật cho bản sửa offset tự-đo của F31.

### Chip "đang chia sẻ màn hình" — kiểm bằng toạ độ, không bằng cảm giác

AD-11 nói từ Android 15 QPR1 hệ điều hành vẽ một chip mà app **không ẩn được**. Trên máy ảo nó hiện thật: một viên thuốc đỏ `⬆ 00:25` ở status bar.

| | Android 16 (status bar **145 px**) | M52 (status bar 76 px) |
|---|---|---|
| Vùng cao nhất phát hiện được | `shell y = 257` | `shell y = 401` |
| Chip nằm ở | màn hình y ≈ 35–105 | *(không có chip)* |

Cắt 145 dòng đầu là bỏ trọn dải chứa chip, và **không vùng nào** được phát hiện ở trên y = 257.

Tiện thể đây cũng là phép kiểm chéo cho F31: cùng một trang, hai máy khác cả kích thước màn hình lẫn chiều cao status bar, mà toạ độ `x` của bubble #1 trùng khít (894 ở cả hai). Nếu offset còn bị đoán thay vì đo thì hai con số đã lệch nhau.

⚠️ **Android 14+ cho người dùng chọn "chia sẻ một app" thay vì cả màn hình.** Chọn thế thì nội dung chụp được là app đó chứ không phải màn hình — **chưa kiểm đường này**.

⚠️ **Không có số hiệu năng nào trong mục này lấy từ máy ảo** (AD-22). Máy ảo x86 chạy ONNX + LLM chậm hơn máy thật nhiều bậc; lượt dịch trên đó kẹt ở bước OCR hơn mười phút. Điều đó **không nói gì** về máy thật.

### Quy tắc rút ra

**AD-22 không phải thủ tục giấy tờ.** Nó tồn tại vì "đã kiểm kỹ trên máy thật" và "chạy được trên mọi phiên bản được hỗ trợ" là **hai mệnh đề khác nhau**, và mệnh đề thứ hai cần phép đo riêng. Nếu bỏ qua Story 3.9, app sẽ sập với 100% người dùng Android 14 trở lên — trong khi mọi phép kiểm khác đều xanh.

Và: **một khai báo trong manifest thường đi kèm một quyền.** Thiếu quyền không làm hỏng build, chỉ làm sập lúc chạy.

---

## F35 — Tải tiếp chỗ dở: chứng minh bằng lần tải thật, và bằng checksum chứ không bằng số byte

Story 4.2 sống chết ở chỗ "rớt mạng thì tải tiếp, không làm lại từ đầu". 2,7 GB trên mạng di động **sẽ** đứt giữa chừng.

### Probe trước khi viết

Trước khi viết một dòng nào, hỏi thẳng năm máy chủ:

```
$ curl -sIL <url>
HTTP/1.1 200 OK
accept-ranges: bytes
content-length: 2588147712      ← khớp đúng file đã dùng đo Phase 0
```

Cả năm URL Hugging Face đều có `Accept-Ranges: bytes`. Nếu thiếu, cả Story 4.2 phải thiết kế khác — nên đây là thứ phải biết **trước**, không phải sau.

### Đo trên máy thật

| | |
|---|---|
| Tải mới | `detector-v4-s_int8.onnx: tai tu byte 0 / 11120765` |
| Dựng sẵn phần dở 4 MB, tải lại | `tai tu byte 4194304 / 11120765` |
| File cuối | **11 120 765 byte**, đã đổi tên từ `.part` |

### Điều đáng tin không phải con số 4194304

Mà là **việc đổi tên có xảy ra**. Thiết kế: `.part` chỉ được đổi thành tên thật khi **và chỉ khi** SHA-256 của *toàn bộ* file khớp manifest. Nếu phần nối vào lệch một byte — sai offset, máy chủ trả cả file thay vì phần đuôi, ghi đè thay vì nối — thì hash lệch và file bị xoá.

Nên "file có mặt với tên thật" **tự nó** là bằng chứng nối đúng. Không cần tin vào con số byte, cũng không cần tin rằng máy chủ xử lý `Range` đúng.

Hệ quả kèm theo: **không bao giờ tồn tại một file "xong" mà hỏng.** `find()` trả về file nào thì file đó dùng được.

⚠️ Ngược lại, nếu máy chủ trả `200` khi ta xin `Range` (tức không hỗ trợ tải tiếp), lớp tải **báo `ResumeNotSupported`** chứ không âm thầm tải lại 2,7 GB từ đầu. Âm thầm làm lại là cách chắc chắn nhất để người dùng bỏ cuộc mà không hiểu vì sao.

### Quy tắc rút ra

**Khi không thể tin vào từng bước, hãy tìm một phép kiểm bao trùm cả quá trình.** Ở đây một lần băm cuối cùng thay thế cho việc phải tin vào offset, vào máy chủ, vào chế độ ghi nối, và vào chính mình. Cùng tinh thần với F33 — chọn một đại lượng mà đúng và sai khác nhau rõ ràng.

---

## F36 — Đường dự phòng che mất lỗi thật, và tôi tin vào lời giải thích sai suốt một vòng

Manifest gói mô hình không lấy được từ GitHub. Log của app nói:

```
W Download: khong lay duoc manifest tu xa (DownloadException), dung ban dong goi
```

Cùng lúc đó, API GitHub trả 404 cho cả repo lẫn file trong khi file **có** trong `origin/main`. Kết luận rất tự nhiên: **repo đang private**. Tôi viết nguyên một đoạn giải thích chuyện đó vào code và vào commit.

### Repo công khai rồi, vẫn hỏng y nguyên

Dòng log không đổi một chữ. Nên nguyên nhân tôi tin suốt từ đầu là **sai**.

Thêm nguyên nhân gốc vào log:

```
khong lay duoc manifest tu xa: DownloadException / NoNetwork
  / cause=NetworkOnMainThreadException: null
```

`fetchManifest` là `suspend` — nhưng **`suspend` không tự đổi luồng**. Nó chạy trên luồng của người gọi, mà người gọi là `lifecycleScope.launch` tức main thread. Thiếu `withContext(Dispatchers.IO)`.

Code này **chưa bao giờ chạy được**, kể cả khi repo công khai ngay từ đầu.

### Hai thứ cùng nhau tạo ra vòng lặp

1. **Đường dự phòng nuốt lỗi.** Nó bắt mọi ngoại lệ và lặng lẽ chuyển sang bản đóng gói. App vẫn chạy, màn hình vẫn đúng, không có gì đỏ.
2. **Log chỉ in tên lớp ngoại lệ**, không in nguyên nhân gốc. `DownloadException` không nói gì cả — nó là lớp bọc của chính tôi.

Và có sẵn một lời giải thích *đúng về mặt sự thật nhưng sai về nhân quả*: repo lúc đó **thật sự** đang private. Nó khớp với triệu chứng, nên tôi ngừng đào.

### Quy tắc rút ra

**Một đường dự phòng phải ồn ào về lý do nó được dùng.** Dự phòng im lặng biến lỗi thành hành vi, và hành vi thì không ai đi sửa. Ở đây chỉ cần in thêm `cause` là xong — mà thiếu nó thì mất trọn một vòng.

**Và: một lời giải thích khớp với triệu chứng chưa chắc là nguyên nhân.** Nối tiếp F31 (nhìn ảnh ba vòng, đọc sai nguyên nhân ba lần) và F30 (lỗi hình học cải trang thành lỗi nội dung). Cùng một cái bẫy, lần này khoác áo "đã tìm ra lý do rồi".

---

## F37 — App bị Android giết giữa lúc dịch, và chỗ chết không phải chỗ tôi đoán

Người dùng báo: *"nó chạy một lúc rồi icon dịch biến mất hoặc hiện chấm than nền đỏ"*.

Không đoán, đi lấy dòng log:

```
lmkd: Reclaim 'app.mangatrans' (26063), uid 11439, oom_score_adj 200, state 4
      to free 2797960kB rss, 1006528kB swap;
      reason: min2x watermark is breached even after kill
ActivityManager: Process app.mangatrans (pid 26063) has died: prcp FGS
```

Mốc thời gian nói điều bất ngờ: app chết **1,2 giây sau khi bước đọc chữ bắt đầu**, chứ không phải giữa bước dịch. Lặp lại y hệt ở lần chạy sau (pid 8040).

Lý do: `AD-20` bảo hâm nóng LLM sớm cho cái chạm đầu tiên đỡ phải chờ. Hâm nóng xong thì **2 GB nằm nguyên trong RAM** từ lúc bật icon. Đến khi người dùng chạm, ONNX dựng phiên đọc chữ chồng thêm lên — và đó là đỉnh.

### Ba phép cắt, đo trước–sau từng cái

| Cắt gì | Trước | Sau |
|---|---|---|
| Nhả detector + OCR trước khi LLM chạy | 3.474 MB PSS | 2.326 MB PSS (−1.149 MB) |
| Tắt vùng nhớ đệm của ONNX Runtime | 1.432 MB RSS lúc đọc chữ | 938 MB RSS (−494 MB) |
| Không hâm nóng LLM sớm nữa | 2.9xx MB RSS lúc bật icon | **74 MB PSS / 130 MB RSS** |

Phép cắt thứ hai đáng nói riêng: ba mô hình thị giác cộng lại chỉ **213 MB trên đĩa** nhưng bước đọc chữ ngốn **858 MB RSS**. Khoảng 645 MB chênh lệch là arena allocator của ONNX Runtime — xin được bao nhiêu thì giữ nguyên, không trả lại hệ điều hành. Tắt bằng `setCPUArenaAllocator(false)` + `setMemoryPatternOptimization(false)`. Đọc chữ còn **nhanh hơn** một chút (10,5s so với 12,5s), không chậm đi.

### Điều làm đổi cả quyết định kiến trúc

Đo được, trên đúng máy M52:

```
engine san sang sau 13435 ms      <- nạp LLM
token dau tien sau 27661 ms       <- prefill
bubble dau tien sau 37434 ms
```

Hâm nóng sớm **không** mua được cái nó hứa. Chạm đầu tiên vẫn chờ ~62 giây cả khi đã hâm nóng, vì phần lâu là **prefill** (LLM đọc hết trang trước khi sinh chữ đầu), không phải nạp mô hình. Tệ hơn: LLM hâm nóng rồi ngồi chờ thì bị đẩy vào zram, đến lúc dùng phải kéo ngược về — **đắt hơn nạp mới**.

Số toàn lượt, cùng một trang, cùng một máy:

| | có hâm nóng sớm | không hâm nóng sớm |
|---|---|---|
| chạm → xong 12 bóng | 2 phút 26 | **2 phút 11** |
| RAM lúc bật icon | ~2.900 MB | 74 MB |

Nên AD-20 bị thu hẹp lại: **không bao giờ giữ cả mô hình nhìn lẫn mô hình dịch cùng lúc**. Nhìn thì nhả dịch, dịch thì nhả nhìn. Cài bằng `Pipeline.onVisionStart` / `onVisionDone`.

### Quy tắc rút ra

**"Tối ưu cho nhanh" phải được đo bằng đồng hồ, không bằng lý lẽ.** AD-20 nghe rất hợp lý suốt bốn epic và chưa ai bấm giờ nó. Khi bấm thì nó vừa không nhanh hơn, vừa là nguyên nhân chính làm app bị giết.

---

## F38 — Mọi lời báo lỗi của app đều bị hệ thống nuốt, suốt bốn epic

Lần theo F37, thấy dòng này trong log:

```
NotificationService: Suppressing toast from package app.mangatrans by user request
```

Kiểm tra quyền:

```
android.permission.POST_NOTIFICATIONS: granted=false
```

App khai quyền này trong manifest nhưng **chưa bao giờ xin lúc chạy**. Từ Android 13 đó là quyền phải xin. Chưa xin thì hỏng hai thứ cùng lúc, và cả hai đều hỏng im lặng:

1. thông báo thường trực của service không hiện — mất đường tắt nhanh, mất dấu hiệu "app đang có thể chụp màn hình" (FR-012);
2. **mọi `Toast` đều bị chặn** — tức mọi câu báo lỗi app định nói đều biến mất.

Đây chính là nửa sau của gợi ý #11: icon hiện chấm than đỏ mà **không nói gì cả**. Không phải app im lặng, mà là app nói vào chỗ đã bị bịt.

Đã sửa: xin quyền thông báo ngay khi người dùng bấm Bật, nối tiếp trước quyền chụp màn hình — một chuỗi, không chồng hộp thoại.

Và thêm log mã lỗi ở `CaptureService.say()`: Toast có thể bị tắt, log thì không. Chỉ ghi **mã lỗi**, không bao giờ ghi nội dung màn hình.

### Quy tắc rút ra

**Một kênh báo lỗi mà người dùng tắt được thì không phải kênh báo lỗi.** Phải luôn có một bản sao ở nơi không ai tắt được.

---

## F39 — Chữ Nhật mờ dưới bản dịch: Android chặn trần độ đục của lớp phủ ở 0.8

Người dùng: *"sao tôi vẫn thấy chữ nhật mờ mờ nằm dưới bản dịch nhỉ?"*

Đây là vòng dài nhất, và tôi **sai hai lần trước khi đúng**.

**Đoán sai lần 1** — "ảnh chụp có alpha 212, màu lấy mẫu mang alpha đó sang nước sơn". Nghe rất khớp: đo được nét chữ 27,5 → 198,6 trên nền 240,6, tỉ lệ 0,83. Ép `alpha = 255`, chạy lại: **vẫn 0,83**. Giả thuyết chết.

**Đoán sai lần 2** — "hình học lệch, ô nền không phủ hết". Vẽ khung detector đè lên ảnh thì thấy khung ôm đúng bóng. Và histogram cho một đỉnh duy nhất ở 210 (89% số pixel), tức **mờ đều**, không phải chỗ che chỗ hở. Giả thuyết chết.

**Phép thử dứt điểm:** tô nền màu **đỏ nguyên chất** rồi đọc pixel trên ảnh chụp.

```
có FLAG_NOT_TOUCHABLE : nền (255, 51, 51)   chữ (51, 51, 51)
bỏ FLAG_NOT_TOUCHABLE : nền (255,  0,  0)   chữ ( 0,  0,  0)
```

51 = đúng 20% của 255. Android chặn trần độ đục của cửa sổ phủ **cho chạm đi xuyên qua** — chống tapjacking, trần mặc định 0.8. Không có lỗi nào trong code vẽ cả; màu gì vẽ ra cũng chỉ còn 80%, 20% còn lại là nội dung bên dưới lọt lên.

Bằng chứng đối chứng có sẵn ngay trong app: **icon nổi không dính lỗi này** vì nó nhận chạm — đo được `(0, 105, 92)` đúng y màu đặt trong code.

### Sửa

Không thể bỏ `FLAG_NOT_TOUCHABLE` trên cửa sổ phủ toàn màn — chính nó cho người dùng cuộn truyện xuyên qua (Story 3.7).

Nên: **mỗi bóng thoại một cửa sổ riêng, khít vùng vẽ, và nhận chạm.** Ngoài bóng thoại không có cửa sổ nào nên app bên dưới cuộn bình thường. Bên trong bóng thoại thì chạm rơi vào ta — mà đó đúng là điều Story 3.6 cần (chạm giữ để liếc nguyên bản). `PeekTargets` vốn đã tạo đúng những cửa sổ đó cho cử chỉ liếc; giờ gộp làm một, bớt hẳn một chỗ tính lại toạ độ (F31 đã dạy chi phí của việc tính hai lần).

**Mỗi cửa sổ vẽ CẢ TRANG rồi để hệ thống xén theo khung của nó**, chứ không vẽ riêng bóng của mình. Nhờ vậy luật hai lượt của `BubbleRenderer` (tô hết nền rồi mới vẽ hết chữ) vẫn giữ nguyên ý nghĩa: hai bóng chồng nhau thì mọi cửa sổ đều cho ra cùng một kết quả, không phụ thuộc cửa sổ nào nằm trên.

Bẫy kèm theo: thiếu `FLAG_LAYOUT_IN_SCREEN` thì `x/y` tính theo vùng nội dung (đã trừ status bar), cửa sổ tụt xuống đúng bằng chiều cao status bar và **xén mất đỉnh bóng thoại** — chữ Nhật ở đỉnh hiện nguyên vẹn.

Kết quả đo sau khi sửa, cùng một bóng, cùng một trang:

| | trước | sau |
|---|---|---|
| màu nền tô ra | (255, 51, 51) | (255, 0, 0) |
| nét chữ Nhật gốc | dồn một đỉnh ở 210 (mờ đều) | hai cực: 255 (che kín) hoặc là chữ Việt |
| chạm giữ để liếc | có | có — vùng liếc trùng khít 100% ảnh gốc |

### Quy tắc rút ra

**Khi hai giả thuyết hợp lý đều chết, đừng đẻ giả thuyết thứ ba — hãy dựng một phép thử phân biệt được.** Tô màu đỏ nguyên chất mất 2 phút và trả lời dứt điểm câu mà hai vòng suy luận không trả lời nổi.

**Và: tìm sẵn một đối chứng trong chính hệ thống của mình.** Icon nổi dùng cùng loại cửa sổ, cùng đường vẽ, chỉ khác mỗi cờ nhận chạm — nó là thí nghiệm đối chứng có sẵn mà tôi bỏ qua suốt hai vòng.

---

## F40 — Một byte NUL lọt vào mã nguồn Kotlin, git coi cả file là nhị phân

`MangaOcrOnnx.kt` chứa **một ký tự NUL thật** trong `var prev = '<NUL>'`, lẽ ra phải là `'\u0000'`. Đúng vết của cái bẫy heredoc đã ghi ở CLAUDE.md §5.4.

Hỏng im lặng theo đúng nghĩa: Kotlin vẫn biên dịch, app vẫn chạy, `grep` vẫn khớp. Chỉ có một dấu hiệu duy nhất — `git diff` báo `Bin 7122 -> 7982 bytes` thay vì hiện diff. Tức **file mã nguồn này không review được**, suốt nhiều commit.

Quy tắc rút ra: `git diff --stat` mà hiện `Bin` cho một file mã nguồn là **báo động**, không phải chuyện nhỏ.

---

## F41 — Vẫn bị giết sau khi đã sửa F37, và lần này nguyên nhân nằm ở *loại* bộ nhớ chứ không phải lượng

Người dùng mở truyện bằng Perfect Viewer (trang lưu sẵn trong máy) và app vẫn chết giữa lúc dịch, dù F37 đã kéo bộ nhớ lúc rảnh xuống 74 MB.

```
lmkd: Reclaim 'app.mangatrans' ... to free 3817964kB rss, 248224kB swap
ActivityManager: Process app.mangatrans (pid 26734) has died: prcp FGS
```

**3,82 GB RSS** — cao hơn hẳn đỉnh 2,97 GB tôi đo được ở lần trước. Lấy mẫu mỗi giây thay vì mỗi 5 giây thì thấy tại sao: đỉnh thật nằm gọn giữa hai lần lấy mẫu cũ. Bài học nhỏ: **nhịp lấy mẫu là một phần của phép đo, không phải chi tiết kỹ thuật.**

Đường đi của bộ nhớ, trang chỉ có 2 bóng thoại:

```
01:11:59  RSS   115 MB   ← lúc rảnh (F37 chạy đúng)
01:12:07  RSS   368 MB   ← đọc chữ
01:12:23  RSS  2855 MB   ← nạp xong LLM
01:12:37  RSS  2810 MB   ← đang prefill, phẳng 14 giây
01:12:45  RSS  3652 MB   ← vọt 800 MB trong 5 giây rồi chết
```

### Đoán sai một lần nữa

`javap` trên AAR cho thấy `EngineConfig` có `maxNumTokens`. Giả thuyết: không đặt thì bộ đệm ngữ cảnh cấp phát theo ngữ cảnh mặc định của Gemma 4, thừa rất nhiều. Đặt `maxNumTokens = 4096`, chạy lại: **chết ở 3.722 MB, đường bộ nhớ giống hệt**. Giả thuyết chết.

(Đã gỡ lại. Một cái chốt không chứng minh được tác dụng mà lại có đường hỏng âm thầm — trang nhiều thoại bị cắt cụt bản dịch, không báo gì — thì là nợ chứ không phải lãi.)

### Phép đo trả lời

Không đoán nữa, đọc phân loại bộ nhớ lúc đang dịch:

| | RSS | tính chất |
|---|---|---|
| **Native Heap** | **1.854 MB** (cấp phát 2.607 MB) | ẩn danh, **bẩn** — chỉ nén vào swap được |
| Other mmap | 944 MB | ánh xạ file, **sạch** — hệ thống vứt đi rồi đọc lại được |

Đây mới là câu trả lời, và nó không phải câu hỏi "tốn bao nhiêu" mà là **"tốn loại gì"**. Trang sạch thì lúc thiếu bộ nhớ hệ thống chỉ việc vứt đi; trang bẩn thì phải nén vào swap, swap hết thì nó giết app. Thư viện đang **chép phần lớn trọng số vào heap** thay vì ánh xạ từ file.

Cũng vì thế mà hai lần đo trước có vẻ mâu thuẫn: lần chạy với Gallery sống sót vì nén được 1,2 GB vào swap, lần với Perfect Viewer chết vì chỉ nén được 296 MB. **Cùng một lượng bộ nhớ, khác kết cục** — sống sót lần đó là may, không phải là đã sửa xong.

### Sửa: `EngineConfig.cacheDir`

Nút cuối cùng còn lại trong `EngineConfig`. Đặt nó trỏ vào `cacheDir` của app. LiteRT-LM ghi ra một file:

```
gemma-4-E2B-it.litertlm_1789301505_2588147712.xnnpack_cache   788 MB
```

— trọng số đã sắp xếp lại cho XNNPACK, ghi xuống đĩa một lần rồi **mmap** vào. Trang sạch, vứt được.

Đo trên cùng một trang, cùng máy:

| | không `cacheDir` | có `cacheDir` |
|---|---|---|
| Native Heap (bẩn) | 1.854 MB | **724 MB** |
| Other mmap (sạch) | 944 MB | 974 MB |
| đỉnh RSS | 3.739 MB → **bị giết** | **1.820–2.056 MB**, xong việc |
| nạp engine | 13.574 ms | **654 ms** |
| prefill | 27.661 ms | **13.183 ms** |
| cả trang 12 bóng | 2 phút 11 | **1 phút 44** |

Giá phải trả: **753 MB đĩa**. Nó nằm trong `cacheDir` nên Android được phép xoá khi máy hết chỗ — mất thì lần nạp sau tự dựng lại, chậm một lần rồi thôi.

### Quy tắc rút ra

**Với chuyện bị giết vì thiếu bộ nhớ, "bao nhiêu MB" là câu hỏi sai. Câu đúng là "bẩn hay sạch".** `dumpsys meminfo` trả lời câu đó trong một dòng, mà tôi đi qua nó hai vòng mới chịu đọc.

**Và: một lần chạy sống sót không phải bằng chứng đã sửa.** Lần chạy với Gallery sống chỉ vì lúc đó máy còn swap. Muốn biết đã sửa thật thì phải nhìn cơ chế, không nhìn kết cục một lần chạy.

---

## F42 — Chụp màn hình hết giờ trên màn hình quá tĩnh, và chỉ lộ ra ở app đọc truyện thật

Khi dựng lại cảnh của người dùng (Perfect Viewer mở toàn màn), `capture()` **hết giờ lần nào cũng như lần nào**:

```
CaptureSvc: chup hong: Timeout
```

Chính cái log mã lỗi thêm ở F38 chỉ ra được ngay — nếu không thì đây lại là một lần "chạm icon mà chẳng thấy gì".

Nguyên nhân nằm ở thứ tự trong `grabFrame()`:

```kotlin
repeat(WARMUP_FRAMES) { r.acquireLatestImage()?.close() }   // vứt vài frame đầu
val image = withTimeoutOrNull(FRAME_TIMEOUT_MS) { awaitImage(r) }
```

`VirtualDisplay` **chỉ sinh frame khi màn hình có thay đổi**. Việc ẩn lớp phủ đi để chụp là một thay đổi, nên nó để lại đúng **một** frame — và đó chính là frame ta cần. Dòng `repeat` vứt trúng ngay nó, rồi ngồi chờ một frame nữa không bao giờ đến.

**Vì sao suốt 4 epic không ai thấy:** mọi lần thử đều dùng Gallery hoặc ảnh mẫu, nơi thanh trạng thái luôn hiện và **đồng hồ nhảy giây** nên lúc nào cũng có frame mới. App đọc truyện thật chạy toàn màn hình, trang đứng im tuyệt đối, không cả đồng hồ — không có gì sinh frame cả.

Sửa: vứt frame cũ **trước khi** ẩn lớp phủ (`drainFrames()` ở đầu `capture()`), và chỉ bỏ frame khởi động khi `VirtualDisplay` **vừa được tạo mới** — `ensureDisplay()` giờ trả về `Boolean` để nói điều đó.

### Quy tắc rút ra

**Môi trường thử nghiệm có thể đang âm thầm che lỗi bằng một đặc điểm không ai để ý.** Ở đây là cái đồng hồ trên thanh trạng thái. Danh sách "cần thử trên gì" phải có **đúng loại app mà người dùng thật sẽ dùng**, không phải thứ tiện tay nhất để dựng.

---

## F43 — Bản dịch tự biến mất, và nguyên nhân tôi nêu lúc đầu là sai

Sau khi sửa F41/F42, bản dịch đứng được 65 giây rồi biến mất. Trong log, ngay trước đó một giây:

```
01:46:04  I/DeviceType: isSupportBrightnessControl: context : com.android.systemui...
01:46:05  I/CaptureSvc: noi dung ben duoi doi — go lop phu
```

Tôi đọc hai dòng đó rồi **nói với người dùng rằng màn hình tự giảm sáng làm xoá bản dịch**. Hai dòng log cách nhau một giây là tương quan, không phải nhân quả — và lần này tương quan dẫn sai.

Đi đo thì: **đổi độ sáng hệ thống không sinh frame nào cả.** Độ sáng do phần cứng màn hình áp, khung hình không được vẽ lại, nên `VirtualDisplay` không có gì để phát. Đặt ngưỡng lên 999 (chỉ đo, không xoá) rồi hạ độ sáng từ 18 xuống 30 và ngược lại: **không một dòng `khac` nào**. Giả thuyết chết.

### Nguyên nhân thật: mốc so sánh bị lấy nhằm lúc màn hình chưa yên

Bộ canh trang lấy **frame đầu tiên nhìn thấy được** làm mốc, 700 ms sau khi vẽ xong. Nếu lúc đó màn hình còn đang ổn định — app đọc truyện còn dựng hình sau cú vuốt, hoặc chính lớp phủ của ta còn đang hiện ra — thì mốc là một khung hình **giữa chừng**. Khung hình yên sau đó khác mốc, và bản dịch bị xoá oan.

Đo được đúng cảnh đó:

```
07:15:22  xong: ve 6 bubble
07:15:27  noi dung ben duoi doi (khac 0.43) — go lop phu     <- 5,5 giây sau
```

Còn khi màn hình đã yên sẵn từ trước, chụp hai ảnh cách nhau 6 giây thì **trùng khít từng pixel**, không có gì để xoá cả. Cùng một đoạn mã, hai kết cục — khác nhau ở chỗ lúc chốt mốc màn hình yên hay chưa.

### Hai thay đổi

**1. Chữ ký bất biến với độ sáng, thay cho mã băm chính xác.**
`frameHash` là SHA-1: đổi một bit là khác. Thay bằng chữ ký độ xám 16×32 ô, **chuẩn hoá về trung bình 0 và độ lệch chuẩn 1**. Chuẩn hoá như thế triệt tiêu mọi phép biến đổi tuyến tính `v -> a*v + b`, tức mọi thay đổi độ sáng/tương phản đều đều.

Giữ lại dù giả thuyết ban đầu sai, vì nó vẫn đúng cho những thứ có thật: chế độ ban đêm bật lên, lọc ánh sáng xanh, màn hình tự chỉnh theo môi trường.

**2. "Lên nòng" sau khi màn hình đã yên.**
Còn frame chạy về thì chỉ cập nhật mốc, chưa so sánh gì. Yên `ARM_QUIET_MS = 1.000 ms` mới chốt mốc và bắt đầu canh.

### Ngưỡng — đo, không đoán

| tình huống | khoảng cách |
|---|---|
| giảm sáng còn 80% | 0,0052 |
| giảm sáng còn 60% | 0,0030 |
| giảm sáng còn 40% | 0,0132 |
| **giảm sáng còn 25%** | **0,0202** ← "giống" tệ nhất |
| — ngưỡng chọn — | **0,05** |
| cùng trang, đã vẽ bản dịch | 0,0947 |
| **lật sang trang khác (đo trên máy)** | **0,99 – 1,06** |

Cách trường hợp "giống" tệ nhất 2,5 lần, cách cú lật trang 20 lần. Khi phải chọn lệch về phía nào thì chọn phía **xoá oan**: AD-12 nói bản dịch trang cũ nằm đè lên trang mới là lỗi nặng nhất của tầng hiển thị.

Kiểm chứng hai chiều trên máy, app đọc truyện thật:

```
đứng yên 45 giây      -> không xoá
vuốt sang trang mới   -> "noi dung ben duoi doi (khac 0.99) — go lop phu"
```

### Một lỗi đo lường của chính tôi, đáng ghi lại

Lần đo đầu, cú lật trang cho `khac 0.0000` — vô lý, vì ảnh chụp cho thấy màn hình đổi 95,6%. Lý do: tôi vuốt từ toạ độ **nằm trong một bóng thoại**, mà cửa sổ bản dịch nhận chạm nên nuốt luôn cú vuốt — trang không hề lật. Phép đo đúng, thao tác sai.

Kèm theo đó là một hệ quả có thật cho người dùng: **vuốt bắt đầu từ trong bóng thoại thì app đọc truyện không nhận được.** Đây là đánh đổi đã biết và đã chấp nhận của thiết kế chạm (xem `TranslationOverlay`), nhưng từ F39 thì vùng nuốt chạm đúng bằng vùng bóng thoại nên nó rõ hơn trước.

### Quy tắc rút ra

**Hai dòng log cách nhau một giây là tương quan, không phải nhân quả.** Tôi đã nói nguyên nhân cho người dùng trước khi đi đo, và nguyên nhân đó sai. Nối tiếp F36 (tin vào một lời giải thích khớp triệu chứng) và F39 (hai giả thuyết hợp lý đều chết). Cùng một cái bẫy, lần này khoác áo "log nói thế".

**Và: một phép đo cho kết quả vô lý thì nghi thao tác trước, đừng nghi phép đo.** `khac 0.0000` cho một màn hình đổi 95,6% là vô lý — chỗ hỏng nằm ở cú vuốt, không nằm ở công thức.

---

## F44 — Cua so ban dich de len icon noi va nuot cu cham

Nguoi dung: *"dang dich thi treo bao loi"*. Dung lai thao tac do — dich cung mot
trang nhieu lan lien tiep — thi den luot thu ba, **cham icon khong co gi xay ra
ca**: khong log, khong loi, khong phan ung.

Tu F39, lop phu ban dich dat **mot cua so rieng cho moi bong thoai**, va chung
duoc them SAU cua so icon. Trong cung mot loai cua so, cai them sau nam tren.
Nen bong thoai nao gan mep man hinh la de len icon.

Do duoc bang `dumpsys input`, sau mot luot dich:

```
icon      frame=[0,818][136,954]
mot bong  frame=[26,624][163,1027]    <- trum gan het icon
```

Cua so bong thoai nhan cham (bat buoc, xem F39) nen no nuot luon cu cham — nhin
y het app treo.

Sua: `FloatingIcon.raise()` — go ra roi gan lai de len tren cung. Chi goi MOT
lan sau khi ve xong ca trang; goi moi lan them mot bong se lam icon chop giat
12 lan mot trang.

### Quy tac rut ra

**Moi cua so them vao la mot lan sap xep lai chieu sau, va chieu sau thi khong
ai nhin thay.** F39 doi mot cua so lay muoi hai cua so; loi ich do duoc ngay
(chu Nhat bien mat), con cai gia thi nam im ba ngay moi lo ra.

---

## F45 — Duoc tat hoac mat tat: sau bong dich dung bi vut vi nam bong con thieu

Cung lan dung lai thao tac cua nguoi dung, bat duoc mot luot nhu sau:

```
10:12:36  Translating 0/11      <- chay lai tu dau
10:12:53  Translating 1/11
...
10:13:18  Translating 6/11      <- dung o day
10:13:42  xong: ve 0 bubble     <- vut sach, bao loi
```

Mo hinh tra ve 6 trong 11 bong, hai lan lien. `TranslateFilter` doi
`accepted.size == truth.size` moi coi la xong, nen ca 6 bong **da qua cong
jaEcho** bi vut het, va nguoi dung nhan mot cau bao loi.

Cai sai o day la gop hai chuyen khac han vao mot nhanh:

| | nghia | phai lam gi |
|---|---|---|
| `mismatch` | mo hinh gan ban dich vao NHAM bong | vut het — dung, day la loi toan ven (AD-6) |
| thieu bong | mo hinh **dung som** | nhung bong da ve van dung tung cai mot |

Sua: het luot thu ma chi THIEU (khong mismatch), thi giu lai phan da xac thuc,
phan con lai de nguyen tieng Nhat.

Kem theo, cau bao loi cung sai: `"Khong tim thay bong thoai nao tren man hinh"`
trong khi tim thay 11 bong va dich duoc 6. Gio phan biet hai canh bang so bong
TIM DUOC, khong phai so bong VE DUOC.

### Quy tac rut ra

**Mot dieu kien gop hai nguyen nhan lai se xu ly sai it nhat mot trong hai.**
`accepted.size == truth.size` dung cho "co sai khong" nhung bi dung luon cho
"co du khong" — va cau tra loi cho hai cau hoi do doi hai cach xu ly nguoc nhau.

---

## F46 — Dau ba cham bi bop thanh mot dau cham, va mo hinh phai bia phan con thieu

Nguoi dung: *"dich van chua dung lam"*. Chat luong kem co HAI nguon doi hai cach
sua nguoc nhau — **doc sai (OCR)** hay **dich sai (LLM)** — nen viec dau tien la
do xem cai nao hong, chu khong phai sua lien.

Cach do: ghi cap `JA -> VI` ra **file rieng trong bo nho app**, bat bang mot co
`/data/local/tmp/mangatrans-diag` ma chi `adb` tao duoc. **Khong bao gio ghi ra
logcat** — do la noi dung man hinh rieng cua nguoi dung.

### Ket qua bat ngo: OCR khong phai thu phai

Doi chieu tung bong voi trang goc thi OCR doc **dung 6/6**. Gia dinh cua chinh
toi ("chac do anh chup man hinh nho nen OCR doc kem") bi bac ngay.

Nhung co mot chi tiet le ra: tren trang la `…`, ma mo hinh nhan duoc `.`

### Nguyen nhan

```kotlin
val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
...
if ((c == '-' || c == '.') && c == prev) continue    // gop day dau cham
```

NFKC bien `…` (U+2026) thanh **ba dau cham**. Dong gop ngay duoi bop ba dau cham
thanh **mot**. Tuc cau bo lung thanh cau tron ven, truoc khi mo hinh kip nhin.

Bo lung la tin hieu quan trong nhat cua thoai manga — nhan vat ngap ngung, noi
hut, bi cat loi. Mat no thi mo hinh **buoc phai doan**:

| tren trang | mo hinh thay | dich ra |
|---|---|---|
| `な、なんで わたしとその…` | `な、なんでわたしとその.` | "Sao lai la toi va **anh**?" |

`その` la "cai do", cau bo lung. Mo hinh tu viet not thanh mot cau hoi tron ven
voi mot nguoi khong he co trong nguyen ban.

### Sua, va do lai tren dung trang do

Gop day dau cham thanh `…` chu khong phai `.`. Cong voi ba dong them vao prompt
(giu noi lap, giu bo lung, dung bia them cho mau cau cut).

| chu Nhat | truoc | sau |
|---|---|---|
| `そ、それは…!` | "Cai do la!" | **"C-cai do la...!"** |
| `な、なんでわたしとその…` | "Sao lai la toi va anh?" | **"Sao lai toi va..."** |
| `わけわかんない…` | "Khong hieu gi ca." | **"Khong hieu..."** |
| `ませぬぅ!` | "**Em** khong the nao!" | **"Khong the nao!"** |
| `すべて小生の不手際でござります…!` | "Tat ca la do so suat cua tieu sinh..." | giu nguyen — von da dung |

Bon tren sau tot len, khong cai nao te di.

### Quy tac rut ra

**Mot buoc "chuan hoa" la mot buoc LAM MAT THONG TIN, va no khong bao giu noi
gi da mat.** Dong gop day dau cham viet ra de don rac OCR, nhung no khong phan
biet duoc rac voi dau cau — ma dau cau o day lai la thu mang nhieu nghia nhat.

**Va: do truoc khi sua.** Toi dinh sua prompt vi tuong loi o mo hinh. Neu lam
the thi da sua nham tang: nut that nam o mot dong chuan hoa chuoi, cach cho toi
dinh sua ba lop.

---

## F47 — "Phai dich di dich lai moi duoc" KHONG phai do mo hinh sinh ngau nhien

Nguoi dung: *"dich doi cho van hoi ngu, phai dich di dich lai moi tam chap nhan
duoc"*. Nghe la nghi ngay: dau ra ngau nhien. `javap` cho thay
`SamplerConfig(topK, topP, temperature, seed)` va app **khong truyen gi ca**,
tuc dung mac dinh cua Gemma — thuong la nhiet do ~1.0. Rat khop.

Toi da viet xong ban sua (topK = 1, sinh tat dinh) truoc khi do. **May la co do
truoc khi cai.**

Phep do: dich CUNG MOT TRANG hai lan, xoa cache giua hai lan, so tung cau.

```
luot 1:  Dung ga nay co le la lan cuoi...
luot 2:  Dung ga nay co le la lan cuoi...          === giong nhau
luot 1:  Hom nay la ngay di hoc cuoi cung truoc khi chuyen truong...
luot 2:  Hom nay la ngay di hoc cuoi cung truoc khi chuyen truong...   === giong nhau
```

**5/5 cau giong het nhau tung chu.** Mo hinh khong he sinh ngau nhien. Ban sua
bi go bo — mot thay doi khong do duoc tac dung thi khong duoc vao ma nguon.

Vay "dich lai thi tot hon" o dau ra? Gan nhu chac chan la truong hop F45: mo
hinh dung som (6/11 bong) roi ca trang bi tu choi, dich lai thi lan sau du bong.
Nguoi dung thay "lan sau tot hon" nhung do khong phai chat luong doi — do la
lan truoc **khong ra gi ca**.

### Gia tri phu: co mot ban thu nghiem sach

Dau ra tat dinh nghia la moi khac biet giua hai lan chay deu quy duoc ve thay
doi minh vua lam. Dung ngay no de sua mot loi thay bang mat:

```
truoc:  帰りの駅で… -> "O nha ga ve, khong ngo lai co chuyen nhu the dang cho doi -"
sau  :  帰りの駅で… -> "O ga tren duong ve, khong ngo lai co chuyen nhu the nay cho doi minh -"
```

Them mot muc vao prompt ("viet nhu nguoi Viet noi, dung bam trat tu chu Nhat")
kem dung vi du do. Bon cau con lai giu nguyen hoac tot hon, khong cau nao te di.
Thu tiep tren mot trang KHAC de kiem xem co "hoc vet" theo vi du khong: giong co
trang van dung (`くださりませぬか` -> "Xin nguoi cho duoc khong a!"), dau bo lung
van giu. Khong thay dau hieu hong.

### Quy tac rut ra

**Viet xong ban sua khong co nghia la duoc cai ban sua do.** Gia thuyet nay hop
ly den muc toi da code xong roi moi do. Neu cai luon thi da co mot thay doi vinh
vien trong ma nguon dua tren mot nguyen nhan khong ton tai — va lan sau co ai do
(hoac chinh toi) doc lai se tuong day la ket luan da duoc kiem chung.

**Va: dau ra tat dinh la mot cong cu, khong chi la mot tinh chat.** Biet no tat
dinh thi moi phep sua prompt deu do duoc bang mot lan chay, khong can chay nhieu
lan lay trung binh.

---

## F48 — App dich lai chinh ban dich cua no, va bang chung nam trong anh no nhin thay

Nguoi dung: *"bam dich trang do lan 2 thi no cha dich gi ca"*. Log cho thay lan
hai KHONG lay cache ma chup lai va dich lai — roi hong:

```
11:58:59  xong: ve 8 bubble        <- lan 1, du 8 bong
11:59:34  Translating 0/8
12:00:01  Translating 1/8          <- chi ra duoc 1 bong roi hong
12:00:40  Translating 0/8          <- chay lai tu dau
12:01:04  Translating 1/8          <- lai hong dung cho do
12:01:44  xong: ve 0 bubble        <- tu choi ca trang
```

Nghi anh chup lan hai dinh lop phu. Log khong tra loi duoc cau nay, ma mat
thuong cung khong: phai nhin **dung tam anh ma OCR nhin thay**. Them duong ghi
anh do ra file rieng (cung co `/data/local/tmp/mangatrans-diag`), chay lai, va:

```
JA: Cau…!
JA: Cauconchamvaodichbaogiorood
JA: Khongphaitenthatdau!
JA: Tieusinhbienthaina!
```

Do la **ban dich tieng Viet cua lan mot**, mat dau, bi OCR tieng Nhat doc lai.
App dang dich chinh ban dich cua no. Mo tam PNG ra nhin thi thay ro: mot so
bong da an, mot so **van con nguyen chu Viet**.

### Nguyen nhan

Tu F39, lop phu khong con la MOT cua so ma la **mot cua so cho moi bong thoai**.
An chung di khong con tuc thi: he thong go tung cua so, moi buoc trung gian sinh
mot frame. Ban cu doi `HIDE_SETTLE_MS = 120 ms` roi lay frame dau tien thay duoc
— tuc lay dung mot buoc giua chung.

F39 doi mot cua so lay muoi hai cua so de sua loi mo chu. Cai gia thu hai cua no
nam o day, va phai ba ngay sau moi lo ra. (Cai gia thu nhat la F44 — cua so de
len icon.)

### Sua

Khong lay frame dau tien nua ma **doi man hinh yen**: giu frame moi nhat, tiep
tuc doi; khong con frame moi trong 220 ms thi coi nhu he thong ve xong. Vua chiu
duoc man hinh tinh (khong frame nao thi dung frame dang giu — chinh la loi F42)
vua chiu duoc man hinh con dang doi.

Do lai: dich cung mot trang hai lan lien tiep, ca hai deu `gate: 17 vung, 9 co
vo bong` va `xong: ve 9 bubble` — giong het nhau.

### Quy tac rut ra

**Khi nghi ngo dau vao, hay luu lai dung cai dau vao do.** Toi da co log, co
anh chup man hinh, co ket qua dich — khong cai nao tra loi duoc cau hoi. Tam
anh ma OCR thuc su nhin thay tra loi trong mot lan nhin.

---

## F49 — Vo bong mo coi: detector thay cai bong nhung khong thay chu ben trong

Nguoi dung gui anh khoanh do mot bong thoai: *"bong thoai nay nay, co dich dau"*.

Ho so chan doan cua dung trang do:

```
vung=17  dua sang dich=8
[12] Bubble diem=0.70  495,1055  224x660     <- khong co vung chu nao ben trong
```

Detector tra ve hai loai vung: `bubble` (vo) va `text_bubble` (chu ben trong).
Tren trang nay 8 cap di voi nhau dung dan, rieng `[12]` co vo ma khong co chu —
va `GateFilter` bo thang moi vung `Bubble`. Ket qua: ca bong thoai bien mat khoi
pipeline, khong ai doc, nguoi dung khong co dau hieu nao de biet.

### Da thu mot cach, va do cho thay no khong dung duoc

AD-5 sinh ra de chan dung viec nay: dua cho manga-ocr mot manh TRANH thi no van
bia ra cau tieng Nhat troi chay (F2). Nen truoc khi cuu vo mo coi phai co cach
biet trong do co chu that khong. Thu do do phang cua nen va ty le muc:

```
vo bong co chu : nen phang 64-88%   muc  9-23%
vung tranh     : nen phang 34-65%   muc  8-30%
```

Hai khoang **chong nhau** — khong co nguong nao tach duoc. Bo.

### Cach dung: tin chinh nhan cua detector

Vung do da duoc gan nhan **`bubble`**, tuc chinh detector noi "day la bong
thoai". Bong thoai gan nhu luon co chu. Chi can chan them bang diem tin cay
(`>= 0.5`) va OCR phan trong ruot (thu vao 6% de khong doc trung vien).

Can nhac hai phia cho ro: bo sot ca mot bong la **im lang mat han mot cau**,
nguoi dung khong co cach nao biet. Cuu nham mot vo rong thi duoc mot bong dich
vo nghia — thay ngay bang mat, va cham giu la hien lai nguyen ban.

Do lai tren dung trang do: `17 vung, 9 co vo bong` (truoc la 8), `xong: ve 9
bubble`. Dung them mot bong — dung cai bong nguoi dung khoanh — khong phinh them
cai nao.

⚠️ Nguong 0.5 dat tu **MOT trang**. Can do them nhieu trang truoc khi tin no.

### Quy tac rut ra

**Khi mot cong loc bo nham, dung tim cach do lai thu no da bo — hay hoi xem
buoc TRUOC do da noi gi.** Toi mat mot vong di do do phang/muc de doan xem
trong vo co chu khong, trong khi detector da tra loi san bang chinh cai nhan
`bubble` cua no.

---

## F50 — Android thu nho anh lam OCR doc sai chu, va PC doc dung cung tam anh do

Nguoi dung chi mot bong dich sai. Ho so chan doan cho thay OCR doc
`ただの本名はじゃない` trong khi tren trang la `ただの変態じゃない` — doc nham
**変態** (bien thai) thanh **本名** (ten that).

De thu nhanh nhieu cach tien xu ly ma khong phai vong qua dien thoai moi lan,
dung mot ban thu tren PC: chay **dung encoder/decoder ONNX ma app dung**, tren
**dung tam anh ma app da nhin thay** (luu tu duong chan doan F48).

Ban thu lap tuc lo ra mot dieu khong ngo: mot bong khac, `柔らかいものに…`, tren
PC doc **dung**, tren may doc **sai** thanh `予末らかいものに`. Cung mo hinh, cung
anh, cung toa do. Khac biet duy nhat con lai: **cach thu nho anh**.

### Do

```
                                  柔らかいものに…
  bilinear tho (nhu Android)  ->  予末らかいものに…   SAI
  co chong rang cua (PIL)     ->  柔らかいものに…     DUNG
  LANCZOS                     ->  柔らかいものに…     DUNG
  trung binh vung (BOX)       ->  柔らかいものに…     DUNG
```

`Bitmap.createScaledBitmap(..., filter = true)` **nghe nhu** da loc, nhung
bilinear cua Android chi lay 2x2 diem lan can. Thu nho 2-2,5 lan — dung ty le
cua hop chu bong thoai (cao 400-550 px xuong 224) — thi phan lon diem anh khong
duoc nhin den, va net chu manh bien mat.

### Sua, khong can thu vien ngoai

**Ha dan tung nua.** Ha mot nua bang bilinear tuong duong lay trung binh 2x2,
tuc moi diem anh deu duoc tinh den; lap den khi con trong pham vi 2x cua dich
roi ha not. Thu truoc tren PC bang cach mo phong dung thuat toan se cai:

```
  bilinear tho      ->  予末らかいものに…
  ha dan tung nua   ->  柔らかいものに…     <- TOT LEN
```

Cai len may, do lai tren dung trang do: OCR doc dung `柔らかい`, ban dich tu
*"thu gi do dang so"* thanh *"thu mem mai"*.

Rieng `変態` van doc sai (`本人` thay vi `本名`) — chu do in rat to va cach dieu,
la truong hop kho that, khong phai loi thu nho anh.

### Quy tac rut ra

**Hai moi truong chay cung mot mo hinh cho hai ket qua khac nhau thi khac biet
nam o phan KHONG PHAI mo hinh.** Toi da di tim loi trong mo hinh, trong mau sac,
trong prompt — trong khi no nam o mot dong thay doi kich thuoc anh.

**Va: mot ban thu chay tren PC dang gia hon nhieu vong thu tren may.** Moi vong
tren dien thoai mat ~2 phut va chi thu duoc mot cach; ban thu PC thu bon cach
trong vai giay, va chinh no lo ra su khac biet ma tren may khong the thay.

---

## F51 — Khong co API dung sinh chu, nhung huy coroutine la du

Nguoi dung hoi: dang dich co bam de dung duoc khong? Truoc day cham luc dang
dich bi **bo qua im lang**, nen doi y thi van phai ngoi cho het gan hai phut.

`javap` tren AAR: `Conversation` khong co `cancel`/`stop`, chi co `close`. Nhung
huy coroutine thi `done.await()` bat CancellationException va `use` dong luon
phien hoi thoai. Thu tren may: dung ngay, icon ve xanh san sang, va **dich lai
duoc binh thuong ngay sau do** (9/9 bong).

Kem theo mot bay: `runCatching { ... }` bao quanh vong thu su kien **nuot ca
CancellationException**, nen than ham van chay tiep xuong phan cuoi — bao "dich
khong tron trang", ve lai lop phu, bat lai bo canh trang. Mot cu cham cho hai
thong bao nguoc nhau. Phai kiem `currentCoroutineContext().isActive` ngay sau do.

### Quy tac rut ra

**`runCatching` khong phan biet "loi" voi "bi huy".** Moi cho dung no bao quanh
mot doan `suspend` deu can kiem lai xem coroutine con song khong.

---

## F52 — Prompt da cham tran: them mot dong lam ca trang tu 9/9 xuong 0/9

Trong mot phien toi them dan bon muc vao prompt, moi muc deu do rieng tren mot
trang va deu cho ket qua tot hon. Muc thu nam — mot dong 143 ky tu ve tieng keu
— lam **ca trang hong**:

```
  prompt 3.377 ky tu  ->  xong: ve 9 bubble
  prompt 3.520 ky tu  ->  xong: ve 0 bubble     (mo hinh tra 1/9 roi lech jaEcho)
```

Go dong do ra: quay lai 9/9. Dau ra tat dinh (F47) nen day khong phai may rui —
dung mot prompt cho dung mot ket qua.

Prompt da tu 1.984 ky tu phinh len 3.377. Gemma 4 E2B la mo hinh 2 ti tham so:
cang nhieu chi thi thi cang de bo quen chi thi quan trong nhat, ma o day chi thi
quan trong nhat la **khuon JSON va cong jaEcho** — bo quen no la mat ca trang.

### Quy tac rut ra

**Prompt co ngan sach, va no khong hien ra o bat ky phep do le nao.** Moi muc
them vao deu "do duoc la tot hon" khi thu rieng. Cai gia chi hien khi cong don,
va no khong tra dan ma **sap mot lan**: dang 9/9 xuong 0/9.

**Nen: tu gio them mot muc vao prompt thi phai do CA TRANG co con ra du bong
khong, chu khong chi do cau minh dinh sua.**

---

## F53 — Doc anh HAI LAN roi lay ban mo hinh tu tin hon

Nguoi dung chi lai dung bong cu: *"sao van dich la 'Khong phai ten that dau'"*.
Lan truoc toi da giai thich do la OCR doc nham `変態` (bien thai) thanh `本名`
(ten that) va goi do la gioi han cua mo hinh. **Giai thich khong phai la sua.**

### Doc ma nguon that cua manga-ocr

`spike/.venv` co san goi `manga_ocr`. `ocr.py`:

```python
img = img.convert("L").convert("RGB")          # <- app CHUA lam
pixel_values = self.processor(img, ...)        # ViTImageProcessor -> keo gian 224x224
```

Hai dieu: (1) mo hinh an anh **xam**, app dang dua anh mau vao; (2) `ViTImage
Processor` resize thang ve 224x224, tuc **keo gian moi la dung chuan** — chen
vien cho vuong la sai chuan.

### Nhung "dung chuan" lai doc sai bong nay

```
keo gian (dung chuan)  -> ただの本名はじゃない…    SAI
chen vuong (sai chuan) -> ただの変態じゃない…      DUNG
```

Va dieu nguoc lai cung dung — bong ngan thi chen vuong lai hong:

```
keo gian    -> キャッ!          DUNG
chen vuong  -> ハキャッし       SAI
```

Hop chu hai bong deu ty le ~1:4, nen **khong the chon theo ty le**. Thu do do
phang, do muc, mau sac — khong cai nao tach duoc.

### Cach chon co nguyen tac: hoi chinh mo hinh

manga-ocr khong tra diem tin cay, nhung bo giai ma la **tham lam tren logits**,
nen tinh duoc log-prob trung binh moi token. Do ca hai cach cat roi lay ban tu
tin hon:

```
                          do tu tin    ket qua
キャッ!        keo gian    -0.000      キャッ!            <- chon
              chen vuong  -0.262      ハキャッし
ただの変態…    keo gian    -0.244      ただの本名は…
              chen vuong  -0.084      ただの変態…        <- chon
柔らかい…      keo gian    -0.036      dung               <- chon
              chen vuong  -0.110      dung
小生変態…      keo gian    -0.001      dung               <- chon
              chen vuong  -0.019      thua mot dau "!"
```

**5/5 bong chon dung ban tot hon.** Cai len may, do lai: `ただの変態じゃない`
doc dung, ban dich tu *"Khong phai ten that dau"* thanh *"Khong chi la mot bien
thai"*.

Gia: OCR chay hai lan moi bong. Buoc doc chu tu ~9 s len ~18 s tren trang 9
bong — khoang 10% tong thoi gian mot trang.

### Quy tac rut ra

**"Mo hinh chi doc duoc the thoi" la ket luan, va ket luan thi can bang chung.**
Toi da noi cau do khi moi thu mot cach cat anh. Thu cach thu hai thi no doc
dung ngay.

**Va: khi hai cau hinh deu dung mot nua, dung chon mot cai — chay ca hai roi
hoi mo hinh cai nao chac hon.** Thong tin do co san trong logits, chi la khong
ai lay ra.

---

## F54 — Rut prompt tu 3.027 xuong 1.110 ky tu, va do la viec BAT BUOC chu khong phai don dep

F52 da canh bao prompt cham tran. F53 lam OCR doc dung hon — de bai doi mot chut
— va ca trang **hong ngay**: `xong: ve 0 bubble`. Cung trang do, cung prompt do,
truoc khi OCR tot len thi van 9/9.

Do la dinh nghia cua he thong chay sat mep: **mot cai sua lam moi thu tot len o
mot tang lai lam tang khac vo**.

Rut prompt con 1.110 ky tu (giu nguyen moi luat da do duoc la co ich: xung ho,
giu nhip noi, dung muc do, trat tu tieng Viet — chi viet chat lai), va cung
trang do quay lai **9/9 bong**, chat luong khong kem di.

### Quy tac rut ra

**Ngan sach prompt la mot tai nguyen chia se giua cac tang.** Tang OCR tot len
cung "tieu" vao no, vi de bai doi la duong sinh chu doi. Khong the coi prompt la
cho de tha vao moi thu minh nghi ra.

---

## F55 — Vi du mau lam VO ca trang, va no khong phai chuyen do dai

Nguoi dung xac nhan OCR da doc dung, con lai la *"cau lung cung"*. Voi mo hinh
nho, **cho vi du** thuong an hon **ta luat** — nen thu them mot khoi vi du
Nhat -> Viet.

Ket qua, cung mot trang, dau ra tat dinh:

```
prompt 2.234 ky tu, KHONG vi du                  -> xong: ve 9 bubble
prompt 2.507 ky tu, vi du TRUNG chu tren trang   -> xong: ve 0 bubble
prompt 2.421 ky tu, vi du KHONG trung chu nao    -> xong: ve 0 bubble
```

Gia thuyet dau: vi du trung chu voi de bai nen mo hinh lan giua vi du va bong
that. Thay bang vi du khong trung mot chu nao — **van hong**.

Va do khong phai chuyen do dai: **3.377 ky tu toan van xuoi thi chay duoc**,
ma 2.421 ky tu co khoi vi du thi hong. Thu hong khong phai so ky tu ma la
**hinh dang** cua khoi vi du: no trong giong chinh cai viec dang giao, nen mo
hinh bat chuoc dinh dang vi du (`Nhat -> "Viet"`) thay vi tra ve khoi JSON.

Da go. Giu prompt 1.110 ky tu khong vi du.

### Quy tac rut ra

**"Cho vi du tot hon ta luat" la kinh nghiem cho mo hinh lon; o mo hinh 2 ti
tham so co khuon dau ra chat, no lai la nguon nhieu.** Khi dau ra phai dung mot
khuon cung, moi thu trong prompt trong giong "dau ra mau" deu canh tranh voi
khuon that.

**Va: ba lan thu prompt trong mot phien deu ket thuc bang 0/9.** Do la tin hieu
du manh de dung, chu khong phai de thu tiep cach thu tu.

---

## F56 — Kiem xem cac ban sua co ap dung duoc cho bo truyen KHAC khong

Nguoi dung hoi: nhung gi vua sua co dung cho truyen khac khong? Cau tra loi tu
suy luan la "co, vi no la co che chu khong phai chinh tay tung trang" — nhung
rule 1 cua du an cam ket luan rong hon pham vi da do. Nen di do.

Bo doi chieu: **tubaki** — 6 trang, **den trang**, khac han bo mau da dung de
sua (truyen mau). Dung ban thu tren PC voi ket qua detector da luu tu Phase 0.

### OCR: duong cu vs duong moi, 54 bong

```
giong het nhau : 50 / 54
khac nhau      :  4 / 54   — ca 4 chi khac SO LUONG dau gach dai
```

Va trong 4 cho do co mot cho ban moi **dung hon han**:

```
cu : ...咬みついてるよ―――――――――――――――――――――――――――っっ   (35 dau gach)
moi: ...咬みついてるよーーーーーーっっ                              (6 dau gach)
```

Khong cho nao te di.

### Cuu vo bong mo coi: co de ra rac khong?

Day moi la rui ro that — AD-5 sinh ra de chan OCR bia chu tren vung khong co
chu (F2). Do tren bo den trang: **~1 vo mo coi moi trang**, va doc ra:

```
'......'   '♪'   'はいってっ'   'ああ'   '?'   '.........'
```

**Ca 6 deu la noi dung that**, khong co cai nao la chu bia. Trong do `はいってっ`
("vao di!") la **thoai that ma ban cu bo sot hoan toan**.

### Cai da do va cai CHUA do

| | pham vi da do |
|---|---|
| chup khong dinh lop phu, icon khong bi nuot cham, giu ban dich mot phan, nut dung, bo nho | co che — dung o moi trang theo cau truc |
| OCR: xam + ha dan tung nua + doc hai lan chon theo do tu tin | **hai bo truyen** (mau + den trang), 63 bong |
| cuu vo bong mo coi, nguong diem 0.5 | **hai bo truyen**, khong ra rac |
| nguong doi trang 0.05 | **mot bo** — chua thu tren app doc truyen khac |
| chat luong DICH (prompt) | **mot bo, hai trang** — chua do tren bo khac |

### Quy tac rut ra

**"Day la co che nen no dung o moi noi" cung la mot ket luan can bang chung.**
Co che dung khap noi, nhung moi CON SO di kem no thi khong: nguong 0.5, nguong
0.05, va toan bo prompt deu duoc chon tu mot bo truyen. Doi chieu voi bo thu hai
mat 10 phut va bien hai trong nam dong o bang tren tu "doan" thanh "da do".

---

## F57 — Vut 10 ban dich DUNG vi mot muc hong la danh doi sai phia

Nguoi dung: *"cu dich duoc mot so bong thoai, con lai ko dich nua"*. Log:

```
14:20:06  Translating 0/18 -> 10/18    roi hong
14:22:06  Translating 0/18 -> 10/18    thu lai, hong dung cho do
14:24:01  xong: ve 0 bubble            vut sach
```

F45 da cho giu lai phan da dich khi mo hinh **dung som**, nhung dieu kien viet
la `reason == null` — tuc chi giu khi KHONG co muc nao hong. O day mo hinh sinh
muc thu 11 lech jaEcho, nen `reason != null`, va ca 10 bong dung bi vut.

Ly do vut sach ban dau (F19): so mo hinh gan ban dich lech mot nac cho CA trang.
Nhung **cong jaEcho kiem tung bong mot** — moi bong da nhan deu doi chieu chu
Nhat cua chinh id do. Mot muc hong o cuoi khong lam nhung muc da kiem tro nen
dang ngo. Da bo dieu kien `reason == null`: het luot thu thi giu moi bong da qua
cong, bat ke co muc hong hay khong.

Kem theo mot loi dem: `drawn` trong `CaptureService` cong don qua CA HAI luot
thu (10 + 10 = 20) vi `Retracted` khong tru lai — nen `"xong: ve N bubble"` noi
sai so bong thuc su tren man hinh.

---

## F58 — Mo hinh dung o dung 10 bong, va KHONG phai vi bi cat do dai

Sau F57, trang 18 bong ve duoc 10. Nhung tai sao dung dung 10?

Hai luot lien tiep deu dung o 10 — dau ra tat dinh (F47) nen day khong phai rui.
Con so tron nhu the giong dau hieu cua mot **tran do dai bai lam**: moi bong
trong JSON ton ~35-45 token, 10 bong ~400 token.

`javap`: `ConversationConfig` co `maxOutputToken`, app khong dat. Dat
`maxOutputToken = 2048`, chay lai — **van dung o 10**. Gia thuyet chet.

Vay khong phai bi cat, ma la mo hinh 2 ti tham so **mat mach** sau chung ay muc:
no tu dong JSON lai va coi nhu xong.

---

## F59 — Chia trang thanh tung dot: 0/18 thanh 18/18, va nhanh hon

AD-3 chot dua CA TRANG trong mot lan goi, va ly do cua no van dung (xung ho,
mach hoi thoai, tong thoi gian nhanh gap 3,3 lan). Nhung AD-3 **gia dinh mo
hinh tra duoc het** — do tren may thi voi 18 bong no khong tra noi.

Chia thanh dot toi da 10 bong, theo dung thu tu doc:

```
truoc:  1 lan goi x 2 luot thu  ->  0/18 bong,   4 phut
sau:    dot 10 bong + dot 8 bong -> 18/18 bong,  2 phut 25
```

**Vua du hon vua nhanh hon** — vi truoc do mot nua thoi gian dung de thu lai mot
lan chac chan se hong.

Mat mat that, phai ghi ro: hai nhan vat noi chuyen vat qua ranh gioi dot thi
xung ho co the lech, vi moi dot la mot lan goi rieng khong thay dot kia. Bong
lien nhau nam cung dot nen phan lon mach hoi thoai van duoc giu.

### Quy tac rut ra

**Mot quyet dinh kien truc dung co the dua tren mot gia dinh chua ai kiem.**
AD-3 dung ve ly do, nhung "mo hinh tra du ca trang" la dieu kien ngam cua no —
va dieu kien do sai o trang dong bong thoai. Quyet dinh khong sai; pham vi cua
no bi ke rong hon thuc te.

---

## F60 — Mo hinh TU BO vet tuc, va tu dien rieng la duong vong duy nhat hieu qua

Nguoi dung khoanh do mot bong: *"sao khong dich tu te o bong nay"*. Ho so chan
doan:

```
JA: わ…っこんなに硬くしてる…っ      <- OCR doc DUNG hoan toan
VI: W... oi!                        <- dich cut, mat sach ve sau
```

Khong phai loi doc chu. Mo hinh dich moi tieng thot dau cau roi **bo luon ve
「こんなに硬くしてる」**. Cac bong khac cung trang, ke ca bong co noi dung nguoi
lon nhe hon, deu dich binh thuong.

### Sua bang cau chu: KHONG an

Them mot dong vao prompt: *"Dich HET cac ve cua cau. Cau co ve tuc thi ve do van
phai co trong ban dich."* Chay lai:

```
VI: W... oi!        <- Y HET nhu cu
```

Cac bong khac co doi chut (chung to prompt CO tac dung), nhung dung bong do thi
khong nhuc nhich. Da go dong them vao — no khong dat muc dich ma con lam mot
bong khac te di (`すすごい反りかた…` mat chu "kinh khung").

Day khong phai chuyen dien dat chi thi. Gemma duoc huan luyen de tranh noi dung
tinh duc tuong minh, va no **lang le bo ve do** thay vi tu choi ca cau — nen nhin
vao ban dich khong co dau hieu gi bao la co phan bi bo.

### Duong vong: tu dien rieng

Them mot muc `硬くしてる` -> `"dang cuong cung"` (kind = Idiom, Confirmed):

```
truoc:  "W... oi!"
sau  :  "W...っ, toi dang cuong cung the nay..."
```

Muc tu dien di vao prompt nhu mot **dinh nghia co tham quyen**, va no de hon
moi cach dien dat chi thi. Day la dieu quan trong cho nguoi dung: gap cho nao bi
bo bot, **tu them mot muc tu dien la ep duoc**.

(Con mot vet nho: `W...っ` con sot ky tu Nhat. Chua sua.)

### Quy tac rut ra

**Mo hinh im lang bo bot thi khong the phat hien bang cach nhin dau ra.** Khac
voi tu choi — tu choi thi thay ngay. Bo bot chi lo ra khi doi chieu voi nguyen
ban, ma nguoi dung thi khong doc duoc nguyen ban. Duong chan doan (F48) la thu
duy nhat bat duoc loai loi nay.

**Va: khi mo hinh khong chiu lam theo chi thi, dung to giong chi thi — doi kenh.**
Prompt la loi khuyen, muc tu dien la dinh nghia. Hai thu di qua hai duong khac
nhau trong dau mo hinh, va duong thu hai o day thang.

---

## F61 — Dung dich NGAY khi nguoi dung roi trang, thay vi sau 4 giay

Nguoi dung: *"dang dich ma chuyen anh, chuyen app thi dung dich nhe. Hien tai
bong thoai dich van hien tren man hinh, choang cho."*

Ban dau toi cho bo canh trang chay ngay tu bong DAU TIEN (thay vi doi ca trang
xong) va huy luon luot dang chay. Do duoc: lat trang -> dung sau **4 giay**.
Nguoi dung tra loi ngay: *"toi muon ngung dich luon chu khong phai mat tan 4
giay"*. Va phep thu chuyen app thi **khong bat duoc gi ca**.

### 4 giay do o dau ra

Khong phai gioi han ky thuat ma la **do tre toi tu dat de chong bao nham**:

```
450 ms   co `selfChanging` sau moi bong vua ve
700 ms   settle sau khi thoi tu-lam-doi
1000 ms  ARM_QUIET_MS — cho man hinh yen roi moi chot moc
350 ms   nhip hoi
```

Tat ca ton tai vi mot ly do duy nhat: bo canh so **CA man hinh**, ma chinh app
dang ve ban dich len do — moi bong vua ve deu trong nhu "nguoi dung sang trang".

### Sua gocs: bo qua dung vung app tu ve

Moc so sanh doi tu "mot frame bat duoc luc chay" sang **chinh anh da chup** —
anh do khong bao gio doi, nen khong can cho yen, khong can len nong, khong can
lay moc lai. Va khi so thi **bo qua vung bong thoai + vung icon**, hai cho duy
nhat app dong toi.

Phan con lai la tranh. Doi la nguoi dung that su doi man hinh.

Cai gia: moi nhip phai tinh hai chu ky (moc + hien tai) thay vi mot. Chu ky la
~4.600 lan doc diem anh, vai mili giay — de thu nhip hoi tu 350 ms xuong 150 ms.

### Do lai

```
                 truoc          sau
lat trang        > 4 giay       0,9 giay   (phan lon la animation lat trang)
chuyen app       KHONG bat duoc 1,7 giay   (phan lon la thoi gian app kia mo len)
```

Ca hai truong hop deu dung ngay khi man hinh thuc su doi.

### Quy tac rut ra

**Do tre chong bao nham la dau hieu phep do dat sai cho.** Toi da chong bao nham
bang cach doi — 2,15 giay do tre chi de tranh nhin nham ban dich cua chinh minh.
Sua dung cho (bo qua vung minh ve) thi do tre bien mat, ma do chinh xac con tang.

---

## F62 — Mat xich con thieu: khoanh lay chu de nap vao tu dien

F60 ket luan: mo hinh **im lang bo bot** nhung cum no ngai dich, va **muc tu
dien la duong duy nhat ep duoc**. Nhung ket luan do co mot lo hong thuc dung ma
nguoi dung chi ra ngay:

> *"nhieu tu tieng Nhat tren truyen toi khong biet viet"*

Tu dien la duong duy nhat, ma nguoi dung **khong nhap noi** vao duong do. Chep
tay tung net kanji la khong tuong. Nen tren ly thuyet co cach chua, tren thuc te
thi khong.

Da lam: giu icon -> `⌖` -> keo mot khung tren man hinh -> app chup, cat dung
khung do, doc chu -> hien ra kem ba nut: **Sao chep** · **Hoi Gemini** · **Luu
vao tu dien rieng**.

Dung lai dung bo doc chu san co, khong them mo hinh nao. Do tren may, khoanh mot
bong bat ky: `そ、溢れちゃりゅ…。` -> luu duoc vao tu dien o trang thai da xac
nhan, ngay lan dich sau la co hieu luc.

### Gemini: ngoai le DUY NHAT cua rang buoc offline, va pham vi da chot voi nguoi dung

D1 (offline hoan toan) va D3 (khong lam che do cloud) van dung cho viec **dich
trang**. Cho nay la ngoai le, va nguoi dung chon dung pham vi nay: *"offline van
la mac dinh, cloud chi la cong cu tra tu"*.

Rang buoc ky thuat da cai theo dung cau do:

  - chi chay khi nguoi dung **tu bam** "Hoi Gemini";
  - chi gui **dung cum chu vua khoanh** — vai chu, khong bao gio ca trang, khong
    bao gio anh man hinh;
  - khong co khoa thi khong goi gi, va app chay day du nhu cu.

Vi sao gioi han o "mot cum tu" chu khong phai "ca trang": goi mien phi cua Google
thuong cho phep dung du lieu de huan luyen. Gui mot cum tu le thi muc phoi bay
nho hon han — va mot cum tu le cung it bi bo loc noi dung chan hon ca trang
truyen nguoi lon.

Loi tu may chu duoc chuyen **nguyen van** ra man hinh: sai khoa, het han muc va
sai ten model la ba chuyen khac han nhau, nguoi dung can biet minh dinh phai cai
nao. (Google doi ten model kha thuong xuyen — day la cho se hong truoc tien.)

### Quy tac rut ra

**Mot cach chua ma nguoi dung khong thao tac noi thi chua phai cach chua.** F60
tim ra dung dap an va toi dung o do. Dap an do chi thanh that khi co duong de ho
nhap chu Nhat vao ma khong can biet viet chu Nhat.

---

## F63 — Goi Gemini that: ten model bi khai tu, va boi canh quyet dinh dung/sai

Do ngay 2026-09-15, bang khoa that cua nguoi dung, tren may M52.

### 1. Ten model mac dinh chet truoc khi app kip phat hanh

Lan goi dau tien trong doi cua ma nay tra ve 404:

```
This model models/gemini-2.0-flash is no longer available.
Please update your code to use models/gemini-3.6-flash
```

`gemini-2.0-flash` la ten toi chon vi no la ten "an toan" nhat luc viet ma. No
da bi go. **Doi ten model la chuyen thuong, khong phai tai nan.**

Diem dang gia: may chu **noi thang ten thay the ngay trong cau loi**. Nen cach
chua khong phai la nho doi hang so moi quy, ma la doc ten do ra:

```kotlin
runCatching { call(ja, first) }.recoverCatching { e ->
    val alt = MODEL_RE.findAll(e.message.orEmpty())
        .map { it.groupValues[1] }.firstOrNull { it != first } ?: throw e
    call(ja, alt).also { model = alt }   // nho lai cho lan sau
}
```

Mot lan 404 -> goi lai ngay voi ten moi -> ghi vao prefs. Nguoi dung khong thay
gi ca. Neu Google lai doi ten nua, app tu di theo ma khong can ban cap nhat.

**Dieu kien de cach nay dung:** phai ghi lai ten moi (`model = alt`), neu khong
moi lan tra tu deu ton mot vong 404 thua.

### 2. Hoi troc mot cum tu cho ket qua SAI — boi canh khong phai trang tri

Cung mot cum 「硬くしてる」, cung model, khac moi cau hoi:

| Prompt | Tra loi |
|---|---|
| `Dich sang tieng Viet: 「硬くしてる」` | "Lam nham, dong dai" — **sai han** |
| `Trong truyen tranh Nhat, nhan vat noi: 「硬くしてる」. Cum nay nghia tieng Viet la gi?` | "Dang gong cung" — **dung** |

Mot cum tu tach khoi ngu canh la mo ho that su; model chon nghia pho bien nhat
trong huan luyen, va voi cum nay nghia pho bien nhat khong phai nghia trong
truyen. Noi ro **the loai van ban** va **ai dang noi** la du de keo no ve dung
nhanh nghia.

Day la cung mot bai hoc voi AD quan trong nhat cua du an (dich ca trang trong
MOT lan goi, kem thu tu doc va glossary), chi khac cho ap dung.

### 3. Bo loc noi dung khong chan truyen nguoi lon

Thu that voi 「自分から膣内射精…」 — cum tuc tuong minh. Gemini tra:

> Tự chủ động xuất tinh vào trong.

HTTP 200, khong `promptFeedback.blockReason`, dich dung. Truoc do toi da lo
cho nay se hong va da viet san cau bao loi "co the do bo loc noi dung". Cau do
van nen giu (bo loc co that, va no thay doi theo thoi gian), nhung **gia dinh
"gui cum tuc thi chac chan bi chan" la sai** — da do, khong bi chan.

Ly do co le la pham vi: gui **mot cum vai chu** khac han gui ca trang truyen
nguoi lon. Day la them mot ly do de giu nguyen gioi han "chi gui dung cum
nguoi dung khoanh".

### 4. Toan tuyen chay that tren may

Giu icon me -> icon con `⌖` -> keo khung quanh mot bong thoai -> `GrabTextActivity`
hien 「自分から膣内射精:」 (OCR doc `…` thanh `:`) -> bam **Hoi Gemini** -> 8 giay
sau o nghia hien "Tự chủ động xuất tinh vào trong."

### Quy tac rut ra

**Voi API cua ben thu ba, cau bao loi la du lieu chu khong chi la thong bao.**
Doc no ra va tu chua duoc thi nguoi dung khong bao gio phai biet co chuyen gi.

**Va: truoc khi viet cau bao loi cho mot gia dinh, hay do gia dinh do da.** Toi
suyt de lai trong ma mot loi giai thich cho mot chuyen khong xay ra.
---

## F64 — Bo canh trang tu huy luot dich sap xong: o bi bong de len MOT PHAN

Nguoi dung: *"dich sai voi dich chua het thi tu dung roi"*. Log lay ra ba luot
lien tiep, ca ba deu chet o gan cuoi:

```
17:31:08  Translating 1/4
17:31:09  man hinh doi (khac 0.09) — dung dich      <- 0,7 giay sau
17:32:38  Translating 6/10
17:32:40  man hinh doi (khac 0.95)
17:34:24  Translating 8/10
17:34:25  man hinh doi (khac 0.05)                  <- DUNG BANG nguong
```

Khong phai nguoi dung lat trang. Bo canh trang cua F61 dang bat nham **chinh
ban dich app vua ve**.

### Vi sao mat na khong che duoc

F61 che vung bong thoai bang `Float.NaN` roi chi so phan con lai. Cach danh dau
o bi che la:

```kotlin
exclude.any { cx in it.x1..it.x2 && cy in it.y1 + top..it.y2 + top }
```

`cx, cy` la **tam** cua o. Luoi la 16 x 32 nen moi o rong 67 x 75 px, con bong
thoai thi vien cong queo. Rat nhieu o co tam nam ngoai bong ma van bi bong de
len mot phan — nhung o do khong bi che, va gia tri cua chung doi ngay khi app
ve bong len.

Sai so moi o nho, nhung cong lai theo so bong da ve. Nen loi **chi lo ra o cuoi
trang**, dung luc sap xong — nghia la luc dat nhat.

Chua: danh dau o bi che khi **o va vung che cham nhau**, kem no them 8 px cho
vien va bong do.

### Cai bay thu hai: duong du phong quay ve so CA man hinh

```kotlin
if (live.size < MIN_LIVE_CELLS) return frameSignature(bmp, cropTopPx, emptyList())
```

Y dinh thi hop ly — che nhieu qua thi so sanh vo nghia. Nhung **luc duy nhat
vung che nuot het man hinh la luc app da ve gan xong ca trang**, tuc la luc man
hinh khac anh goc nhieu nhat. Quay ve so ca man hinh dung luc do thi chac chan
vuot nguong.

Chua: tra `null`, va nguoi goi **khong ket luan gi ca**. Khong biet thi dung im
con hon doan — doan sai o day la vut ca luot dich.

### Cai bay thu ba: nhip hoi dau tien roi vao luc lop phu dang dung

Do sau khi chua hai cho tren, van con dung mot nhip le:

```
18:03:40.479  canh trang: d=0.1614 live=485 bo-qua=2
18:03:41.126  canh trang: d=0.0024 live=485 bo-qua=2
```

Hai nhip **cung mat na** (`live` va `bo-qua` y het) ma d lech 60 lan, roi tro
lai binh thuong. Do la khung hinh dang do, bat duoc dung luc cua so lop phu vua
duoc them vao.

Nguyen nhan o thu tu goi: `watchForPageChange` duoc bat **truoc** `addBubble`,
nen no chay truoc khi co `selfChanging` che chan. Chua: bat bo canh SAU khi bong
dau tien da len man hinh. Co `selfChanging` khi do dang gio va nhip do bi bo qua
han.

### Do lai: nhieu va tin hieu cach nhau 250 lan

Duong do gac sau co `/data/local/tmp/mangatrans-diag`, chi ghi so — khong ghi
noi dung man hinh.

| | so nhip | d |
|---|---|---|
| App dang ve, trang dung yen (ca trang 11 bong) | 12 | **max 0.0038** |
| Nguoi dung lat trang that | 3 | **0.94 – 1.15** |
| Nguong dang dat | | **0.05** |

Nguong nam giua, cao gap 13 lan nhieu va thap gap 19 lan tin hieu. Truoc khi
chua, sai so do bong de len mot phan day d len tan 0.05–0.09 — tuc la **nguong
nam ngay trong dam nhieu**, va do la ly do that su chu khong phai nguong dat sai.

O con song: 328–485 tren tong 512, khong lan nao cham nguong bo cuoc 80. Nghia
la duong du phong o tren **chua bao gio can den** tren trang nay — no chi nam
do va cho de pha.

### Them mot lop: phai vuot nguong HAI NHIP LIEN TIEP

Lat trang la chuyen keo dai; mot nhip le vuot nguong hau het la nhieu. Gia phai
tra do duoc: **208 ms**.

```
18:13:33.442  bam phim lat trang
18:13:34.334  d=0.9995   (nhip 1)
18:13:34.542  d=0.9424   (nhip 2) -> dung dich
```

Toan bo do tre tu luc lat den luc go lop phu: **1,10 giay**. Con rat xa con so
4 giay ma F61 phai chua.

### Quy tac rut ra

**Duong du phong chay dung luc hong nhat la duong du phong lam hong them.** Cai
`MIN_LIVE_CELLS` fallback duoc viet ra de "cho chac", va no chi kich hoat dung
trong hoan canh ma no chac chan tra loi sai.

**Va: khi mot phep so dung luoi o, hay hoi o do la DIEM hay la VUNG.** Ca lo
loi nay chi la mot cho lay tam o lam dai dien cho ca o rong 67 x 75 px.

---

## F65 — Mo hinh CHEP VI DU trong prompt ra ban dich, va dot le mot bong luon hong

Do tren dung trang nguoi dung dang mo (11 bong, truyen mau), ba lan chay lien
tiep, moi lan doi mot thu.

### 1. Dot le mot bong: dich 0/1, hai lan, roi bi bo im lang

`all.chunked(MAX_PER_CALL)` voi 11 bong ra `[10, 1]`. Dot mot bong khong bao gio
thanh cong:

```
Translating 10/10 · Translating 0/1 · Translating 0/1 · xong: ve 10 bubble
```

Bong thu 11 bi de nguyen tieng Nhat. **Khong mot loi bao nao** — vi khi dang
chia dot thi `translateOnce` ghi vao `sink` chu khong phat `PageRejected`, nen
duong bao loi khong chay.

Hai cai sai chong len nhau: dot le vua **mat het ngu canh** (no bi tach khoi
dung cau dung truoc no trong mach thoai), vua **im lang khi hong**.

Chua: chia deu thay vi cat 10 roi lay phan du.

```kotlin
val parts = (all.size + MAX_PER_CALL - 1) / MAX_PER_CALL
val per   = (all.size + parts - 1) / parts
all.chunked(per)
```

11 -> `[6, 5]` · 18 -> `[9, 9]` · 21 -> `[7, 7, 7]`. Khong dot nao vuot
`MAX_PER_CALL`, va khong bao gio con dot le. Do lai: **11/11 bong**, ca hai dot
deu du. Kem mot dong log khi mot dot nhan thieu bong, de lan sau con lan duoc.

### 2. Mo hinh chep thang chuoi vi du trong SYSTEM prompt ra ban dich

Day la phat hien dang gia nhat cua lan do nay, va no giai thich **hai loi khac
han nhau** ma toi tuong la hai chuyen.

**Loi a — ten nhan vat moc tu hu khong.** 「溢れちゃってりゅ…♡」 ra *"Tran ra roi
**Rurimaru**..."*. Trong cau khong he co ten ai. 「りゅ」 la cach noi nhiu cua
「る」.

Toi tuong la glossary khop chuoi bay ba. Khong phai: glossary co muc 「瑠璃丸」,
nhung SYSTEM prompt cung co dung dong nay —

```
Ten rieng phien am (瑠璃丸 → Rurimaru)
```

Mo hinh 2 ti tham so duoc moi san chuoi `Rurimaru`, gap mot am gan gan la no
tha ra.

**Loi b — noi lap thanh mot cau khac han.** 「も、申し訳御座いませぬ…!」 ra
*"**C-cai do la**, toi xin loi..."*. Cau goc khong co "cai do" nao. Va SYSTEM
prompt co:

```
Noi lap → dich ra noi lap (「そ、それは」 → "C-cai do la")
```

Lai la chep nguyen chuoi vi du. Cung mot co che, khac cho.

**Chua:** bo MOI vi du chep duoc ra khoi SYSTEM, thay bang quy tac truu tuong
("lap phu am dau cua CHINH tu tieng Viet minh vua chon roi them gach noi"). Do
lai tren cung trang:

| | truoc | sau |
|---|---|---|
| 溢れちゃってりゅ…♡ | "Tran ra roi Rurimaru...♡" | "Tran qua roi...♡" |
| も、申し訳御座いませぬ…! | "C-cai do la, toi xin loi..." | "Toi, toi xin loi...!" |

### 3. Ba luat them, hai cai an

- **Tieng tho khong phai tu co nghia.** 「はぁ♡」 tung ra *"Ha..."* (hieu thanh
  "Ha?" = gi co?). Them luat phien am tieng tho -> ra `"Hã♡"`.
- **Giu ky hieu ♡ ♪ ★.** Chung bi rung gan het o lan do thu hai.
- **Chieu cua 〜て貰う／〜てくれる.** 「取って貰うからね?」 van ra *"Toi se nhan
  lay"* — **nguoc chieu**, va luat viet vao prompt **khong an**. Ghi lai la con
  no chu khong ghi la da chua.

### Con lai chua chua duoc (do that, khong phai suy doan)

| Bong | Van sai the nao |
|---|---|
| 取って貰うからね? | Nguoc chieu nguoi lam / nguoi nhan |
| どれだけ射精せば… | Ra "phong ra" thay vi "xuat tinh" — mo hinh van noi tranh du co luat "dung muc do" |
| 転校前に…こんなふうにして | Them "thi sao" ma cau goc khong co |

Ba cai nay nam o **kha nang cua mo hinh 2 ti tham so**, khong phai o prompt.
Duong vong dung dan cho chung la muc tu dien rieng (F60), va do chinh la ly do
`⌖` ton tai (F62).

### Quy tac rut ra

**Vi du trong prompt la con dao hai luoi voi mo hinh nho: no hoc CHUOI chu khong
hoc QUY TAC.** Mot vi du cang cu the thi cang de bi chep nguyen van ra dau ra.
Voi mo hinh nho, ta quy tac truu tuong an toan hon mot vi du hay.

**Va: dung lay ten that trong du lieu cua nguoi dung lam vi du trong prompt.**
`瑠璃丸 → Rurimaru` vua la vi du vua la mot muc glossary that — hai vai tro do
chong nhau va sinh ra ban dich bia.
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
