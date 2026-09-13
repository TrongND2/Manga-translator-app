# CLAUDE.md — Dự án Auto Translate Android

App Android dịch manga Nhật → Việt, **chạy offline hoàn toàn**, chi phí 0 đồng.
Dự án **cá nhân**, không phải dự án công ty.

> Bản QA playbook cũ (Playwright / Notion checklist / Allure / Saleshub-THRM-TREC) đã lưu ở `CLAUDE.md.company.bak`. Nó viết cho test automation web của công ty, **không áp dụng ở đây**. File này chỉ giữ lại các rule kỷ luật còn đúng, và thay bằng quy ước riêng của dự án.

---

## 0. Ràng buộc không được phá (đã chốt)

| # | Ràng buộc | Ghi chú |
|---|---|---|
| D1 | **Offline hoàn toàn** | Tải gói mô hình một lần, sau đó không cần internet |
| D2 | **Chi phí 0 đồng** | Không server, không API trả phí, không thuê GPU |
| D3 | **Không làm chế độ cloud** | Vẫn tách interface `Translator` để không tự khoá về sau |
| D4 | Nguồn ảnh MVP = **chụp màn hình / overlay** | Không pick ảnh thư viện / camera / zip-cbz trong MVP |

**Quyết định kiến trúc quan trọng nhất:** bước dịch nhận **toàn bộ bubble của một màn hình trong MỘT lần gọi**, kèm thứ tự đọc phải→trái và glossary tên nhân vật. Dịch từng bubble riêng lẻ là nguyên nhân số một gây sai xưng hô và mất mạch truyện. Đừng "tối ưu" thành gọi từng bubble.

---

## 1. Kỷ luật báo cáo — chống kết luận quá rộng (BẮT BUỘC)

> Giữ từ playbook cũ (rule 0.6). Dự án này sống bằng số đo (CER, tok/s, RAM, điểm dịch) nên rule này là rule quan trọng nhất ở đây.

- **Mỗi mệnh đề phải chỉ ra được dòng log / số đo đỡ nó.** Không chỉ ra được thì **thu hẹp câu chữ** cho khớp phép đo, hoặc **đi đo thêm** rồi mới viết.
- Cảnh giác lượng từ trùm: *"tất cả"*, *"mọi"*, *"đã đủ"*, *"chạy tốt"*, *"không còn"*. Mỗi từ như vậy phải có phép đo phủ **đúng phạm vi đó**.
- Cụ thể cho dự án này:
  - Đo trên 5 trang → viết *"trên 5 trang test"*, **không** viết *"OCR đọc tốt manga"*.
  - Đo trên ROG Ally (x86, 10.7GB) → **không** kết luận gì về điện thoại Android 8GB. Phải đo lại trên máy thật.
  - Đo một bộ truyện (`tubaki`) → không suy ra mọi thể loại. Font viết tay, truyện shounen action, 4-koma khác nhau rõ rệt.

## 2. CHẠY ĐƯỢC ≠ CHẠY ĐÚNG (BẮT BUỘC)

> Giữ từ playbook cũ (rule 2.2.1 / 2.2.2) — chống "Pass giả".

- Script không crash **không phải** là bằng chứng nó đúng. Phải có phép đo riêng cho *đúng*:
  - OCR: không crash ≠ đọc đúng → phải có **CER/WER trên ground truth**.
  - Detector: ra bounding box ≠ ra đúng bubble → phải **đếm** bubble tìm thấy / bubble thật, và nhìn ảnh overlay bằng mắt.
  - Dịch: ra tiếng Việt ≠ dịch đúng → phải **chấm tay** theo rubric, riêng cột xưng hô.
- Tách Expected thành **từng mệnh đề**, mỗi mệnh đề một phép đo. "Pipeline chạy end-to-end" là 5 mệnh đề, không phải 1.

## 3. Probe trước khi kết luận "không làm được" (BẮT BUỘC)

> Giữ từ playbook cũ (rule 2.1).

Cấm ghi *"không convert ONNX được"* / *"model này không chạy nổi"* dựa trên suy đoán hoặc một lần thử. Phải **thử thật, dán log lỗi**, rồi mới kết luận. Nếu là giới hạn cứng (ví dụ `FLAG_SECURE` chặn chụp màn hình) thì nói rõ đó là giới hạn nền tảng, kèm nguồn.

## 4. Không dừng giữa chừng để báo cáo (BẮT BUỘC)

> Giữ từ playbook cũ (rule 0.9), rút gọn.

Chỉ kết thúc lượt vì một trong 4 lý do: xong toàn bộ việc được giao · cần user **quyết định** · bị chặn cứng đã probe · cần user **thao tác tay** (cài Android Studio SDK, bấm installer, cấp quyền trên điện thoại).

**Phép thử một câu:** *"Lần dừng này có kèm MỘT CÂU HỎI mà chỉ user trả lời được không?"* Không có câu hỏi thì không được dừng.

**Chống nguyên nhân kỹ thuật:** lệnh chạy quá ~10 phút **bắt buộc chạy nền** (`run_in_background`) — tool Bash cắt cứng ở **600 giây**. Ở dự án này các việc chắc chắn vượt 600s: `pip install torch`, tải model từ Hugging Face, convert ONNX, chạy OCR 201 trang, Gradle build lần đầu.

---

## 5. Quy ước dự án

### 5.1 Thư mục

```
docs/          brief.md, PRD, architecture — đầu ra BMAD
handoff/       doc sống (xem handoff/README.md)
spike/         Phase 0 — script Python đo R1/R2 trên PC, KHÔNG phải code app
Support test file/japanese page/    201 trang manga thật (tubaki_*.jpg)
_bmad/         BMAD v6 (không sửa tay)
_bmad-output/  đầu ra BMAD
```

Ảnh test **không** đẩy lên git (bản quyền). `spike/out/` cũng không đẩy.

### 5.2 BMAD

Bản cài ở đây là **BMAD v6 — dạng skills**, không phải v4 dạng `/analyst`. Gọi qua skill: `bmad-prd`, `bmad-architecture`, `bmad-create-epics-and-stories`, `bmad-sprint-planning`, `bmad-build`.

### 5.3 Môi trường

- Máy dev: **ROG Ally, Ryzen Z1 Extreme, 10.7GB RAM, iGPU AMD**. Không đại diện cho điện thoại — mọi số đo hiệu năng phải lặp lại trên Android thật.
- Python 3.11.0 · Node v26.3.0 · JDK 26.0.2.1
- **Android SDK ✅** — `%LOCALAPPDATA%\Android\Sdk` · platform **android-37.0** · build-tools 36.0.0 · adb 1.0.41 · Android Studio 2026.1.3. `ANDROID_HOME` chưa set (Gradle vẫn tự tìm được). `cmdline-tools` chưa có — cần nếu muốn dùng `sdkmanager` từ dòng lệnh.
- Chưa phải git repo.

### 5.4 Bẫy đã mắc khi dựng build — đừng lặp lại

| Bẫy | Đúng phải là |
|---|---|
| Thêm plugin `org.jetbrains.kotlin.android` | **AGP 9.0+ đã tích hợp sẵn Kotlin.** Thêm plugin này làm build thất bại |
| Viết `local.properties` với `\` | File `.properties` coi `\` là escape (`
` = xuống dòng). Dùng `/` trong đường dẫn |
| Viết file Kotlin/Gradle bằng heredoc bash | Heredoc nuốt `\` — **kể cả heredoc đã quote `<< 'EOF'`**. Dùng công cụ Write/Edit cho file có ký tự escape |
| Chạy script Python qua heredoc, script có chuỗi chứa `\` | Nuốt một lớp `\` rồi Python đọc lớp còn lại là escape: `\a` → BEL, `\b` → backspace. **Hỏng âm thầm** — file trông gần đúng, `grep` vẫn khớp, chỉ `cat -A` mới lộ. Đã mắc: `platform-tools\adb.exe` thành `platform-tools␇db.exe`. Nếu buộc phải dùng, dựng `\` bằng `chr(92)` và `assert chr(7) not in t` |
| Dùng `java` trên PATH (26.0.2) | Dùng **JDK 25 đi kèm Android Studio**: `%ProgramFiles%\Android\Android Studio\jbr` |
| Tìm LiteRT-LM trên Maven Central | Nó nằm trên **repo Maven của Google** (`google()`), không có trên Maven Central |
| Kotlin của AGP (2.2.x) không đọc được metadata của LiteRT-LM (2.4.0) | Nâng KGP bằng `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") } }` ở build file **gốc**. **Đừng** dùng `-Xskip-metadata-version-check` — nó giấu vấn đề và đẻ lỗi lúc chạy |

> **Vì sao hay vỡ:** LiteRT-LM còn ở **0.x**, phát hành 2–5 tuần/bản, nên nó chạy trước cả AGP ổn định. Mỗi lần nâng nó, kiểm lại phiên bản Kotlin trước.

| `AutoTokenizer` của manga-ocr lỗi `Couldn't instantiate the backend tokenizer` | Nạp với `tokenizer_type="bert-japanese"`. `transformers` nhận nhầm lớp tokenizer cho config `VisionEncoderDecoder`. Chính `manga_ocr` cũng phải vá chỗ này |

### Quy tắc rút ra: đọc mã nguồn, đừng tin tài liệu

Bốn lần trong dự án này, câu trả lời đúng nằm trong **mã nguồn hoặc bytecode**, không nằm trong tài liệu:

| Tìm ra bằng | Điều tài liệu không nói |
|---|---|
| `javap` trên AAR | `sendMessageAsync` nhận `MessageCallback`, **không** trả `Flow` như doc ghi |
| `javap` trên AAR | Có sẵn hàm `benchmark()` trả đúng ba chỉ số cần |
| Đọc `public.libraries.txt` trên máy | `uses-native-library` là điều kiện để GPU khởi tạo |
| `inspect.getsource()` | `tokenizer_type="bert-japanese"` |

**Trước khi viết code gọi một thư viện lạ: giải nén và đọc chữ ký thật.** Rẻ hơn nhiều so với vài vòng build hỏng.

### 5.4 Tiếng Việt

- Trả lời bằng **tiếng Việt**.
- Font đích phải có **đầy đủ dấu tiếng Việt**. Anime Ace và đa số font comic phương Tây **không có** — kiểm tra trước khi chọn font, đừng tin tên font.

### 5.5 Giấy phép — phải tự xác minh

Các project tham khảo (`manga-image-translator`, `BallonsTranslator`) dùng giấy phép họ GPL. **Từng model thành phần có giấy phép RIÊNG, khác với repo chứa nó.** Trước khi đưa model nào vào app: mở trang model đó đọc license, ghi lại vào `docs/`. Không suy từ license của repo.

---

## 6. Continuity giữa các phiên

Handoff doc sống ở **`handoff/`** (không phải `chat-handoffs/`). Quy ước đầy đủ ở `handoff/README.md` — đọc file đó khi cần cập nhật hoặc đọc lại handoff.
