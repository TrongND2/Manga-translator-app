---
title: 'Kiểm chứng công nghệ — ARCHITECTURE-SPINE.md'
type: tech-verification-review
target: 'ARCHITECTURE-SPINE.md § Stack, AD-2, AD-14, AD-15, AD-16'
date: '2026-09-13'
method: 'Tra cứu web, ưu tiên nguồn first-party (Google AI Edge, developer.android.com, HuggingFace model card, GitHub releases)'
---

# Kiểm chứng công nghệ — Manga Translator JA→VI Offline

**Ngày kiểm:** 2026-09-13
**Phạm vi:** phần **Stack**, và AD-2, AD-14, AD-15, AD-16 của `ARCHITECTURE-SPINE.md`

## Verdict tổng

Nền tảng kỹ thuật **về cơ bản đứng vững**: LiteRT-LM có thật, đang được Google phát triển rất tích cực, và **đã chính thức thay thế** MediaPipe LLM Inference API. Gemma 4 E2B có thật, có bản `.litertlm`, và con số **2.58GB trong spine khớp chính xác** với file trên HuggingFace.

Nhưng có **một sai sót phải sửa** và **một rủi ro lớn hơn spine đang thừa nhận**:

- ❌ **Cảnh báo giấy phép Gemma trong spine đã lỗi thời.** Gemma 4 phát hành dưới **Apache 2.0**, không phải "điều khoản sử dụng riêng của Google". Ràng buộc mà spine lo ngại chỉ áp dụng cho Gemma 1–3/3n.
- ⚠️ **Adreno 642L có khả năng cao KHÔNG chạy được backend GPU của LiteRT-LM.** Đây không còn là `[ASSUMPTION]` trung tính — có bằng chứng nghiêng về phía tiêu cực. AD-14 đúng là cổng chặn bắt buộc, nhưng nên chuẩn bị sẵn nhánh CPU-only.

---

## 1. LiteRT-LM — ✅ XÁC MINH ĐƯỢC (kèm cảnh báo về độ chín)

### Còn được Google duy trì không? — ✅ CÓ, rất tích cực

Repo `google-ai-edge/LiteRT-LM` đang hoạt động mạnh. Nhịp phát hành khoảng **2–5 tuần một bản**:

| Phiên bản | Ngày |
|---|---|
| **v0.17.0** | **2026-09-04 / 09-09** (mới nhất tại thời điểm kiểm) |
| v0.16.1 | 2026-08-18 |
| v0.16.0 | 2026-08-11 |
| v0.15.0 | 2026-08-04 |
| v0.14.0 | 2026-07-08 |
| v0.13.0 | 2026-06-02 |

Google mô tả chính thức: *"the production-ready orchestration layer to run LLMs with LiteRT, engineered for high-performance, cross-platform execution."*

**Nguồn:**
- https://github.com/google-ai-edge/LiteRT-LM/releases
- https://developers.google.com/edge/litert-lm/overview
- https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/

### Nó có thay thế MediaPipe LLM Inference API không? — ✅ CÓ, chính thức

Trang tài liệu chính thức của MediaPipe LLM Inference mang thông báo nguyên văn:

> "The MediaPipe LLM Inference API (Android, iOS, and Web) is now in **maintenance-only mode**."
> "We recommend migrating your projects to LiteRT-LM to ensure continued support and performance."

Đường di trú được nêu rõ: **Android → LiteRT-LM Android (Kotlin) API**.

→ **Câu trong Stack của spine — "LiteRT-LM, Kotlin API (thay cho MediaPipe LLM Inference đã khai tử)" — là ĐÚNG.** Chỉ một sắc thái nhỏ: từ chính xác là *maintenance-only*, không phải *đã khai tử* hoàn toàn; API cũ vẫn chạy, chỉ không nhận tính năng mới.

**Nguồn:** https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference

### Kotlin API cho Android có thật sự ổn định không? — ⚠️ CÓ THẬT, NHƯNG CHƯA GỌI LÀ "ỔN ĐỊNH"

**Xác minh được:**
- API Kotlin cho Android/JVM tồn tại, có tài liệu riêng, có artifact Maven chính thức:
  ```kotlin
  implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
  ```
- Hỗ trợ ba backend: **CPU** (mặc định, qua XNNPACK), **GPU** (qua `libOpenCL.so` + `libvndksupport.so`), **NPU**.
- Có app mẫu chính thức: Google AI Edge Gallery (trên Google Play).

**Nhưng KHÔNG xác minh được là "ổn định" theo nghĩa cam kết API:**

| Dấu hiệu | Chi tiết |
|---|---|
| Vẫn ở **0.x** | Sau hơn một năm phát triển vẫn chưa có 1.0 → chưa có cam kết tương thích ngược |
| Không có tuyên bố ổn định | Tài liệu Kotlin **không hề** nói API là stable, cũng không có mục breaking changes |
| Có API đánh dấu thử nghiệm | Ví dụ Multi-Token Prediction yêu cầu `@OptIn(ExperimentalApi::class)` |
| Bản mới nhất có thiếu sót | v0.17.0 **thiếu C-API prebuilts** (issue #3569) — dấu hiệu quy trình phát hành chưa chín |
| Nhịp phát hành rất nhanh | 2–5 tuần/bản → `latest.release` trong Gradle là **rủi ro**, phải ghim phiên bản cứng |

**Khuyến nghị cho AD-2:** AD-2 (adapter cô lập LiteRT-LM trong đúng một package) là **quyết định đúng và cần thiết**, chính vì API còn ở 0.x. Nhưng Stack nên **ghim một phiên bản cụ thể** (`0.17.0`) thay vì `latest.release`, và ghi rõ là "API 0.x, chưa cam kết ổn định".

**Nguồn:**
- https://developers.google.com/edge/litert-lm/android
- https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- https://mvnrepository.com/artifact/com.google.ai.edge.litertlm
- https://github.com/google-ai-edge/LiteRT-LM/issues/3569

---

## 2. Gemma 4 E2B — ⚠️ PHẦN LỚN XÁC MINH ĐƯỢC, RIÊNG ADRENO 642L KHÔNG

### Model có tồn tại không? — ✅ CÓ

Gemma 4 là có thật, phát hành **2026** với bốn cấu hình: **E2B**, **E4B** (edge), **26B MoE**, **31B Dense**; sau đó có thêm bản 12B Unified.

- E2B-it: ~2.3B tham số hiệu dụng (5.1B kể cả embeddings), 35 lớp, vocab 262K, cửa sổ ngữ cảnh **128K**, đa phương thức (text + image + audio).
- Hỗ trợ >140 ngôn ngữ (quan trọng cho JA→VI).

⚠️ **Ghi chú nhỏ:** các nguồn nêu ngày phát hành không khớp nhau (blog Google: 2026-04-02; trang releases: đợt đầu tháng 3/2026, bản 12B Unified 2026-06-03; technical report arXiv 2607.02770 tháng 7). Không ảnh hưởng quyết định, nhưng **không nên trích một ngày cụ thể** vào spine.

**Nguồn:**
- https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
- https://ai.google.dev/gemma/docs/core
- https://ai.google.dev/gemma/docs/releases
- https://huggingface.co/google/gemma-4-E2B-it

### Có bản LiteRT-LM không? — ✅ CÓ

Repo chính thức: **`litert-community/gemma-4-E2B-it-litert-lm`** (và `gemma-4-E4B-it-litert-lm` cho E4B).

### Kích thước int4 thật sự là bao nhiêu? — ✅ 2.58GB — SPINE ĐÚNG CHÍNH XÁC

| File | Kích thước |
|---|---|
| `gemma-4-E2B-it.litertlm` | **2583 MB ≈ 2.58 GB** |
| `gemma-4-E2B-it-web.litertlm` (bản web) | 2008 MB ≈ 2.0 GB |
| Các biến thể theo chip (Tensor G5, Intel PTL…) | ~2.95–3.11 GB |

→ **Con số 2.58GB trong AD-16 và Stack khớp chính xác** với `gemma-4-E2B-it.litertlm`. Đây là điểm mạnh nhất của spine.

⚠️ **Nhưng cách gọi "int4" là không chính xác.** Model card chính thức nói model dùng **"a mixture of 2bit, 4bit and 8 bit weights"** (lượng tử hoá hỗn hợp), đạt footprint trọng số text-only thấp tới 0.8 GB. Không phải int4 thuần.

**Hệ quả cho AD-16:** cảnh báo `[ASSUMPTION]` của AD-16 ("phép đo chạy trên bản ollama 7.2GB độ chính xác cao, không phải bản int4 2.58GB") **vẫn đúng và vẫn cần thiết** — thậm chí còn đúng hơn, vì lượng tử hoá hỗn hợp 2/4/8-bit có đặc tính mất mát khác với int4 đồng nhất. Nên đổi chữ "int4" thành "lượng tử hoá hỗn hợp 2/4/8-bit" cho đúng.

**Nguồn:** https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

### Yêu cầu RAM tối thiểu? — ⚠️ XÁC MINH ĐƯỢC MỘT PHẦN, CON SỐ KHÔNG THỐNG NHẤT

**Google chính thức (bảng inference memory, `ai.google.dev/gemma/docs/core`):**

| Model | BF16 | SFP8 | Q4_0 | **Mobile** |
|---|---|---|---|---|
| **E2B** | 11.4 GB | 5.7 GB | 2.9 GB | **1.1 GB** |
| E4B | 17.9 GB | 8.9 GB | 4.5 GB | 2.5 GB |

Google ghi rõ những số này *"may change based on your specific inference tool and environment"*.

**Đo thực tế trên model card LiteRT-LM (RAM khi chạy):**

| Thiết bị | Backend | RAM dùng |
|---|---|---|
| Galaxy S26 Ultra | CPU | 1,733 MB |
| iPhone 17 Pro | CPU | 607 MB |
| MacBook Pro M4 Max | CPU | 736 MB |
| Raspberry Pi 5 | CPU | 1,546 MB |

**Nguồn bên thứ ba:** "E2B requires 6 GB RAM minimum", "active memory sits under 1.5 GB, which fits any device with 6 GB RAM or more".

→ **Với Galaxy M52 5G (6GB hoặc 8GB RAM tuỳ bản):** đạt ngưỡng tối thiểu, nhưng **sát mép** ở bản 6GB — nhất là khi app còn phải giữ đồng thời RT-DETR + manga-ocr + bitmap ảnh chụp + overlay. **Rủi ro OOM / bị hệ thống kill foreground service là có thật và spine chưa nhắc tới.**

⚠️ **KHÔNG xác minh được:** Google không công bố yêu cầu RAM tối thiểu chính thức cho Android. Con số "6 GB" là từ blog bên thứ ba, không phải first-party.

### Chạy được trên GPU Adreno 642L (Snapdragon 778G, 2021) không? — ❌ KHÔNG XÁC MINH ĐƯỢC; BẰNG CHỨNG NGHIÊNG VỀ "KHÔNG"

Đây là **phát hiện quan trọng nhất của đợt kiểm chứng này.**

**Những gì xác minh được:**

1. **Adreno 642L chỉ hỗ trợ OpenCL 2.0 FP** (cùng OpenGL ES 3.2, Vulkan 1.1) — theo Notebookcheck và product brief của Qualcomm cho Snapdragon 778G.

2. **Backend GPU của LiteRT-LM (`LITERT_CL` / engine MLDrift) nhắm OpenCL 3.0 với hỗ trợ subgroup.** Các nguồn cộng đồng mô tả backend *"runs on all GPUs that support the OpenCL 3.0 standard with subgroup support"*. ⚠️ **Lưu ý:** tôi **KHÔNG tìm được** trang tài liệu first-party nào của Google nêu yêu cầu OpenCL tối thiểu. Trang `developers.google.com/edge/litert-lm/android` chỉ nói cần `libOpenCL.so` và `libvndksupport.so`, **không nêu phiên bản**. Google **không công bố bảng thiết bị tương thích nào cả.**

3. **Có issue đang mở nói thẳng về Adreno 6xx.** Issue tracking #2318 của LiteRT-LM ghi: *"older Qualcomm Adreno 620 GPU on the Pixel 5 hits internal compilation failures or unoptimized execution paths"* trong quá trình LiteRT graph lowering, và đề xuất fix là **phát hiện chữ ký driver Adreno 6xx rồi hạ xuống FP32**. Adreno 642L **cùng thế hệ 6xx** với Adreno 620.

4. **Backend GPU của LiteRT-LM nói chung còn giòn**, ngay cả trên máy cao cấp:
   - Issue #1860: `Backend.GPU()` **im lặng thất bại** trên Pixel 8 Pro — OpenCL không tìm thấy, không có fallback rõ ràng. (Tensor G3 không expose OpenCL.)
   - Issue #2114: GPU engine init **fail** trên Galaxy S26 Exynos (Xclipse 960) — Clspv từ chối kernel MLDrift.
   - Issue #2318: SELinux trên Samsung Android 14+ **chặn** việc dò tìm thư viện OpenCL.
   - Issue #1850: **Gemma 4 E2B trên Pixel 8** — LiteRT-LM 0.10.0 load được lên GPU nhưng **crash lúc decode** (`clEnqueueNDRangeKernel - Invalid command queue`, `Node number 2068 (LITERT_CL) failed to invoke`). Phải tự vá mới chạy. Sau khi vá: prefill nhanh gấp ~8x CPU (426 vs 53 tok/s).
   - Khi OpenCL fail, **toàn bộ `Engine` construction fail** vì `LITERT_CL` là delegate duy nhất đăng ký cho `Backend.GPU()` — **không có fallback tự động sang CPU.**

5. **Có tài liệu nào nói về thiết bị tầm trung không? — ❌ KHÔNG.** Mọi benchmark first-party đều trên **flagship**: Galaxy S26 Ultra, S25 Ultra, iPhone 17 Pro, MacBook M4 Max, Raspberry Pi 5, và NPU Qualcomm Dragonwing IQ8. **Không có một dòng nào về Snapdragon 7-series hay Adreno 6xx.**

**Số đo tầm trung tìm được (bên thứ ba, không phải Adreno):**
- Galaxy A35 5G (Exynos 1380, **Mali-G68 MP5**, 8GB RAM): GPU **không được hỗ trợ đúng** trong PocketPal AI → phải chạy CPU. Khi ép GPU qua LLM Hub thì chỉ nhanh hơn **5–12%** và **crash** khi chạy lâu. **E2B đạt 5–7 tok/s trên CPU.** Kết luận của tác giả: nút thắt là **băng thông bộ nhớ**, không phải GPU.
- Tổng quát cho tầm trung: *"On mid-range hardware with less capable GPUs, the CPU backend gives you 8–12 decode tokens/sec."*
- Đối chiếu: Snapdragon 8 Gen 3 đạt ~20–35 tok/s cho E2B.

**Ý nghĩa cho AD-2 và AD-14:**

AD-2 viết: *"chọn llama.cpp và kẹt với CPU-only"* — hàm ý LiteRT-LM cho GPU nên hơn. **Bằng chứng không ủng hộ giả định đó trên Adreno 642L.** Kịch bản khả dĩ nhất là LiteRT-LM cũng **chạy CPU-only trên M52**, và ở tốc độ ~5–12 tok/s.

AD-14 đã đúng khi bắt đo trước. Nhưng nên **siết lại cho sát thực tế**:
- Nhánh thoát "GPU không dùng được trên Adreno 642L → xét lại AD-2" cần đổi từ *khả năng* sang **kịch bản mặc định phải chuẩn bị sẵn**.
- Bổ sung phép đo: **thời gian `engine.initialize()`** — tài liệu Google cảnh báo *"can take a significant amount of time (e.g., up to 10 seconds)"*. Con số này ăn thẳng vào NFR-005 (bubble đầu ≤ 8s) nếu khởi tạo nằm trong đường tới hạn. **Spine chưa tính đến độ trễ khởi tạo engine.**
- Bổ sung phép đo: **đỉnh RAM toàn app** trên bản M52 6GB.
- Adapter `LiteRtLmTranslator` phải **tự bắt lỗi init GPU và fallback CPU**, vì runtime **không tự làm việc đó**.

**Nguồn:**
- https://www.notebookcheck.net/Qualcomm-Adreno-642L-GPU-Benchmarks-and-Specs.560462.0.html
- https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/product_brief_-_snapdragon_778g_5g_mobile_platform.pdf
- https://github.com/google-ai-edge/LiteRT-LM/issues/2318
- https://github.com/google-ai-edge/LiteRT-LM/issues/1860
- https://github.com/google-ai-edge/LiteRT-LM/issues/2114
- https://github.com/google-ai-edge/LiteRT-LM/issues/1850
- https://dev.to/baiju_rajyaguru_a70384dfd/i-ran-gemma-4-on-a-mid-range-android-phone-heres-what-actually-happened-40ge
- https://www.mindstudio.ai/blog/gemma-4-edge-deployment-e2b-e4b-models

---

## 3. Giấy phép Gemma — ❌ SPINE SAI / LỖI THỜI (tin tốt)

**Spine viết:**
> ⚠️ **Gemma có điều khoản sử dụng riêng của Google**, không phải Apache-2.0. Phải đọc trước khi phát hành.

**Điều này KHÔNG còn đúng với Gemma 4.**

### Bằng chứng

1. **Trang Gemma Terms of Use chính thức của Google tự loại trừ Gemma 4.** `ai.google.dev/gemma/terms` ghi thẳng: *"For Gemma 4 terms, see the **Gemma 4 license**"* — và link trỏ tới `/gemma/apache_2`.

2. **Blog công bố của Google** gọi Gemma 4 là *"commercially permissive **Apache 2.0 license**"* mang lại *"complete developer flexibility and digital sovereignty"*.

3. **Model card HuggingFace `google/gemma-4-E2B-it`** ghi trường license là **`apache-2.0`**, và **không gated** — không phải bấm đồng ý điều khoản riêng nào.

### Phân định rõ

| Model | Giấy phép | Nhúng vào app phát hành? |
|---|---|---|
| Gemma 1, 2, 3, **3n** | **Gemma Terms of Use** (tuỳ chỉnh) + Prohibited Use Policy; phải kèm file Notice, phải chuyển điều khoản cho người nhận | Được, nhưng **kèm nghĩa vụ** |
| **Gemma 4** (E2B, E4B, 26B MoE, 31B, 12B Unified) | **Apache 2.0** | ✅ **Được, tự do** — không carve-out, không ngưỡng MAU, không Prohibited Use Policy gắn với trọng số |

### Hạn chế còn lại đáng chú ý

Apache 2.0 **không cấp quyền thương hiệu**. Nghĩa là:
- ❌ Không được đặt tên sản phẩm là "Gemma…" hay gợi ý Google bảo trợ.
- ✅ Vẫn phải giữ **file `LICENSE` và các notice bản quyền** theo §4 của Apache 2.0 khi phân phối trọng số — điều này **áp dụng cho gói model tải về của AD-15**.

### Hành động đề xuất

- **Sửa Stack:** thay cảnh báo hiện tại bằng: *"Gemma 4 dùng Apache 2.0 — nhúng vào app phát hành tự do. Gói model (AD-15) phải kèm file LICENSE Apache 2.0. Không được dùng tên 'Gemma' trong tên sản phẩm."*
- **Bổ sung vào AD-15:** manifest gói model nên khai báo cả **giấy phép và file notice** đi kèm, không chỉ checksum và phiên bản.
- ⚠️ **Cạm bẫy tiềm ẩn:** nếu sau này cân nhắc lùi về **Gemma 3n E2B** (để có model nhỏ hơn), **giấy phép sẽ đổi lại thành Gemma Terms of Use** với đầy đủ nghĩa vụ. Đây là chi phí ẩn của nhánh thoát trong AD-14/AD-16.

**Nguồn:**
- https://ai.google.dev/gemma/terms
- https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
- https://huggingface.co/google/gemma-4-E2B-it
- https://www.mindstudio.ai/blog/what-is-gemma-4-apache-2-license-commercial-ai-deployment

---

## 4. ONNX Runtime cho Android — ✅ XÁC MINH ĐƯỢC

### Còn được duy trì? — ✅ CÓ, rất tích cực

- ONNX Runtime phát hành **theo nhịp hàng tháng** (theo roadmap chính thức).
- Các bản gần đây trên GitHub: **v1.30.0 (10/9)**, v1.29.1 (10/9), v1.28.2 (3/9), v1.28.1 (18/8), v1.27.1 (11/7/2026), v1.27.0 (22/6/2026).
  ⚠️ Trang GitHub releases hiển thị ngày không kèm năm cho các bản trong năm hiện tại; tôi **suy ra** là 2026 dựa trên v1.27.x đã xác định là 2026. Con số phiên bản chính xác nhất tại ngày kiểm nên tra lại trực tiếp trước khi ghim vào Gradle.
- Gói `com.microsoft.onnxruntime:onnxruntime-android` (AAR) có trên Maven Central, cập nhật liên tục.
- NNAPI Execution Provider **vẫn được hỗ trợ** (yêu cầu Android 8.1+, khuyến nghị Android 9+). **Không tìm thấy thông báo khai tử NNAPI nào.**

### Chạy được RT-DETR không? — ✅ CÓ, và model cụ thể trong spine đã được xác nhận

**Xác minh trực tiếp model mà spine chọn:**

`ogkalu/comic-text-and-bubble-detector` trên HuggingFace:
- Kiến trúc: **RT-DETR-v2 r50vd**, fine-tune trên ~11k ảnh Manga / Webtoon / Manhua / comic phương Tây.
- Giấy phép: **apache-2.0** ✅ (khớp spine)
- Lớp: `0: bubble`, `1: text_bubble` (chữ trong bubble), `2: text_free` (chữ ngoài bubble) ✅ **khớp chính xác** với AD-5 và mục Deferred (`text_free`).
- Kích thước huấn luyện: 640; ảnh được resize (không crop); webtoon dài được cắt dọc.

→ **AD-5 và ghi chú về `text_free` trong Deferred có nền tảng đúng.**

⚠️ **KHÔNG xác minh được chi tiết:** tên file **`detector-v4-s_int8`** và kích thước **11.1MB**. Repo có nhiều file; tôi không xác nhận được đúng biến thể này tồn tại với đúng kích thước đó. **Cần kiểm lại thủ công.**

⚠️ **Cảnh báo về RT-DETR trên Android:**
- **Không có tutorial first-party** nào của ONNX Runtime cho RT-DETR trên Android (các tutorial mobile chính thức chỉ có YOLOv8 và image classification).
- RT-DETR là **detector kiểu transformer**. Thực tiễn cho thấy transformer thường **rơi về CPU** trên NNAPI vì nhiều toán tử không được hỗ trợ → mất tăng tốc phần cứng. **Nên đo riêng thời gian detect trên M52, đừng giả định NNAPI sẽ tăng tốc.**
- Lượng tử hoá int8: khuyến nghị chính thức là dùng **static INT8 với calibration data đúng miền** (ảnh manga thật), không dùng dynamic INT8. Cảnh báo quan trọng: *"INT8 quantization accuracy is sensitive to calibration data quality — poor calibration causes silent precision loss with no error at export time."* → **mất độ chính xác âm thầm, không báo lỗi lúc export.** Điều này khớp đúng tinh thần AD-4 ("đầu ra của model là dữ liệu không đáng tin").

### manga-ocr ONNX — ⚠️ CÓ TỒN TẠI, spine đánh dấu `[ASSUMPTION]` là đúng

Có sẵn nhiều bản ONNX của manga-ocr do cộng đồng chuyển: `onnx-community/manga-ocr-base-ONNX`, `mayocream/manga-ocr-onnx`, `l0wgear/manga-ocr-2025-onnx`, và port `manga-ocr-torchless`. **Không tìm thấy bản int8 đã có sẵn, cũng không tìm thấy số đo CER nào trên Android.** → `[ASSUMPTION]` "chưa convert, chưa đo CER" trong Stack là **đánh giá trung thực và vẫn đúng**.

**Nguồn:**
- https://github.com/microsoft/onnxruntime/releases
- https://onnxruntime.ai/roadmap
- https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html
- https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android
- https://huggingface.co/ogkalu/comic-text-and-bubble-detector
- https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html
- https://huggingface.co/onnx-community/manga-ocr-base-ONNX

---

## 5. MediaProjection trên Android 14/15/16 — ⚠️ XÁC MINH ĐƯỢC, NHƯNG STACK THIẾU CHI TIẾT SỐNG CÒN

Dòng Stack hiện tại chỉ ghi: *"`MediaProjection` + foreground service `mediaProjection`"*. **Đúng nhưng chưa đủ** — có ba ràng buộc bắt buộc mà spine chưa nêu, và cả ba đều chạm trực tiếp vào AD-10/AD-11 và luồng "chạm icon để dịch".

### Yêu cầu hiện hành (xác minh từ developer.android.com)

**Android 14 (API 34) trở lên:**

1. **Bắt buộc foreground service type `mediaProjection`.** Manifest phải khai `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` và `android:foregroundServiceType="mediaProjection"`. Thiếu → ném `MissingForegroundServiceTypeException`.

2. **Thứ tự bắt buộc: khởi động foreground service TRƯỚC, rồi mới chiếu.** Làm ngược → hệ thống ném `SecurityException`.

3. **Đồng ý của người dùng cho MỖI phiên chụp.** `createVirtualDisplay()` ném `SecurityException` nếu:
   - app cache lại `Intent` từ `createScreenCaptureIntent()` rồi truyền vào `getMediaProjection()` **nhiều hơn một lần**; hoặc
   - app gọi `createVirtualDisplay()` **nhiều hơn một lần** trên cùng một instance `MediaProjection`.

   → **Một `MediaProjection` = một `VirtualDisplay` = một phiên.** Không được cache token qua các lần khởi động app.

4. **Chia sẻ một app vs toàn màn hình.** Từ Android 14, hộp thoại mặc định cho người dùng chọn "một app" hoặc "toàn màn hình" — **app phải xử lý cả hai**. Muốn chỉ toàn màn hình thì dùng `MediaProjectionConfig.createConfigForDefaultDisplay()` (nhà sản xuất có quyền ghi đè opt-out này).

**Android 15 QPR1 trở lên:**

5. **Chip trạng thái lớn, nổi bật** trên status bar báo đang chiếu màn hình; người dùng chạm vào là dừng.

6. **Tự động dừng chiếu khi:** người dùng chạm chip, **màn hình bị khoá**, một phiên chiếu khác bắt đầu, hoặc tiến trình app bị kill.

7. **Bắt buộc đăng ký `MediaProjection.Callback.onStop()`.** Không đăng ký → `createVirtualDisplay()` ném `IllegalStateException`.

**Android 16 (API 36):**

8. ✅ **KHÔNG tìm thấy thay đổi mới nào riêng cho MediaProjection.** Trang behavior-changes của Android 16 không có mục nào về screen capture / media projection.

**2026 có gì mới không? — ❌ KHÔNG tìm thấy thay đổi MediaProjection nào trong 2026.** Ràng buộc mới nhất vẫn là mốc Android 15 QPR1.

### Hệ quả cho spine (ba điểm cần bổ sung)

**a) Phiên chiếu là tài nguyên có vòng đời, không phải lời gọi một lần.**
Vì một `MediaProjection` chỉ được `createVirtualDisplay()` **một lần**, app **phải giữ một `VirtualDisplay` sống suốt phiên** và đọc frame lặp lại từ đó — **không được** xin quyền lại mỗi lần chạm icon. Điều này khớp với ghi chú *"`CaptureForegroundService` — chủ sở hữu trạng thái phiên"* trong Structural Seed, nhưng **spine chưa nói rõ rằng đó là yêu cầu của nền tảng, không phải lựa chọn thiết kế.** Nên nâng thành một Rule tường minh.

**b) Phiên có thể bị hệ thống chấm dứt bất cứ lúc nào — và AD-12 chưa phủ trường hợp này.**
Khoá màn hình là **dừng chiếu**, không phải tạm dừng. Sau khi mở khoá, app **phải xin lại quyền từ đầu**. Với app manga mà người dùng có thể khoá máy giữa chừng, đây là đường đi thường gặp, không phải ngoại lệ hiếm. `onStop()` phải **gỡ sạch overlay** (AD-10: "đóng app phải gỡ sạch") và đưa UI về trạng thái "cần cấp quyền lại". Hiện **chưa AD nào nhận trách nhiệm này.**

**c) Chip trạng thái phá vỡ giả định của AD-11.**
AD-11 nói `ScreenSource.capture()` *"tự ẩn icon nổi và mọi lớp phủ, chờ một frame, chụp, rồi hiện lại"*. Nhưng từ Android 15 QPR1, **chip chiếu màn hình của hệ thống luôn hiện trên status bar và app không ẩn được nó**. Nếu ảnh chụp gồm cả status bar, chip sẽ nằm trong ảnh đưa vào detector. Rủi ro thấp (chip không phải bubble manga) nhưng **AD-11 nên nói rõ: cắt bỏ vùng status bar trước khi đưa vào pipeline**, thay vì khẳng định ảnh chụp "sạch".

### Ghi chú thêm về minSdk/targetSdk trong Stack

Stack ghi *"minSdk 29 (Android 10) / targetSdk bản mới nhất"*.

- ⚠️ **targetSdk "bản mới nhất" hiện là API 37 (Android 17)**, phát hành **2026-06-16** (codename Cinnamon Bun). Google Play yêu cầu **API 36 từ 2026-08-31**, và **API 37 từ tháng 8/2027**. Vì PRD D13 nói app chỉ dùng riêng (không lên Play Store), đây không phải ràng buộc cứng — nhưng nên **ghim một API level cụ thể** thay vì "bản mới nhất", vì mỗi bản Android mang behavior changes cần kiểm lại.
- ⚠️ **minSdk 29 KHÔNG xác minh được là khả thi.** Tài liệu LiteRT-LM **không nêu minSdk**. ONNX Runtime NNAPI EP cần Android 8.1+ (API 27), nên ORT ổn. Nhưng chưa có bằng chứng nào cho thấy `litertlm-android` hỗ trợ tới API 29. **Phải kiểm ngay ở story AD-14** — nếu LiteRT-LM đòi minSdk cao hơn, con số 29 phải sửa.
- ℹ️ Android 17 có thay đổi liên quan gián tiếp cần để mắt: quyền tự động reset sau 3 tháng không dùng app (ảnh hưởng `SYSTEM_ALERT_WINDOW` của overlay), và mở rộng cô lập Private Compute Core cho tác vụ AI on-device.

**Nguồn:**
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/about/versions/14/behavior-changes-14#media-projection-consent
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://android-developers.googleblog.com/2026/06/Android-17.html
- https://support.google.com/googleplay/android-developer/answer/11926878

---

## Bảng tổng hợp

| # | Mục | Kết quả | Ghi chú ngắn |
|---|---|---|---|
| 1 | **LiteRT-LM còn được duy trì** | ✅ | v0.17.0 (9/2026), nhịp 2–5 tuần/bản |
| 1b | **LiteRT-LM thay MediaPipe** | ✅ | Thông báo *maintenance-only* chính thức |
| 1c | **Kotlin API "ổn định"** | ⚠️ | Có thật, có Maven, nhưng **vẫn 0.x**, không cam kết ổn định. Ghim phiên bản, đừng `latest.release` |
| 2a | **Gemma 4 E2B tồn tại** | ✅ | 4 cấu hình, E2B là edge tier, 128K context, >140 ngôn ngữ |
| 2b | **Bản LiteRT-LM tồn tại** | ✅ | `litert-community/gemma-4-E2B-it-litert-lm` |
| 2c | **Kích thước 2.58GB** | ✅ | `gemma-4-E2B-it.litertlm` = 2583 MB — **spine đúng chính xác** |
| 2d | **Gọi là "int4"** | ⚠️ | Thực tế là **hỗn hợp 2/4/8-bit**, không phải int4 thuần |
| 2e | **RAM tối thiểu** | ⚠️ | Google: mobile 1.1GB; bên thứ ba: máy cần **≥6GB**. M52 bản 6GB **sát mép**. Không có số first-party cho Android |
| 2f | **GPU Adreno 642L** | ❌ | **Không xác minh được; bằng chứng nghiêng về KHÔNG.** 642L chỉ OpenCL 2.0; backend nhắm OpenCL 3.0+subgroup; issue #2318 ghi rõ Adreno 6xx lỗi compile. **Không có fallback CPU tự động** |
| 2g | **Tài liệu cho tầm trung** | ❌ | **Không có.** Mọi benchmark first-party đều trên flagship |
| 3 | **Giấy phép Gemma** | ❌ | **Spine SAI/lỗi thời.** Gemma 4 là **Apache 2.0**, nhúng tự do. Ràng buộc cũ chỉ áp cho Gemma 1–3/3n |
| 4a | **ONNX Runtime còn duy trì** | ✅ | Phát hành hàng tháng, AAR Android trên Maven Central, NNAPI chưa khai tử |
| 4b | **Chạy được RT-DETR** | ✅ | Model của spine xác nhận là **RT-DETR-v2 r50vd, Apache-2.0**, đúng 3 lớp như AD-5 |
| 4c | **File `detector-v4-s_int8` 11.1MB** | ⚠️ | **Không xác minh được** tên file và kích thước cụ thể |
| 4d | **manga-ocr ONNX** | ⚠️ | Có bản cộng đồng; **không có bản int8 sẵn, không có số CER**. `[ASSUMPTION]` của spine vẫn đúng |
| 5a | **MediaProjection Android 14/15** | ⚠️ | Yêu cầu xác minh đủ, nhưng **Stack thiếu 3 ràng buộc sống còn** (xem §5) |
| 5b | **Android 16 có gì mới** | ✅ | **Không có thay đổi nào** riêng cho MediaProjection |
| 5c | **2026 có gì mới** | ✅ | **Không.** Mốc mới nhất vẫn là Android 15 QPR1 |
| 5d | **targetSdk "bản mới nhất"** | ⚠️ | Hiện là **API 37 / Android 17** (6/2026). Nên ghim số cụ thể |
| 5e | **minSdk 29 khả thi** | ❌ | **Không xác minh được** — LiteRT-LM không công bố minSdk. Phải kiểm ở AD-14 |

---

## Khuyến nghị hành động (theo thứ tự ưu tiên)

### P0 — sửa ngay trong spine

1. **Xoá cảnh báo giấy phép Gemma sai.** Thay bằng: *"Gemma 4 — Apache 2.0. Gói model (AD-15) phải kèm file LICENSE. Không dùng tên 'Gemma' trong tên sản phẩm."* Ghi chú thêm: **nếu lùi về Gemma 3n thì giấy phép đổi lại thành Gemma Terms of Use** — đây là chi phí ẩn của nhánh thoát AD-14/AD-16.

2. **Sửa "int4" thành "lượng tử hoá hỗn hợp 2/4/8-bit"** trong Stack và AD-16 (giữ nguyên 2.58GB — con số đó đúng).

3. **Ghim phiên bản LiteRT-LM** (`0.17.0`) thay cho `latest.release`, và ghi rõ **"API 0.x, chưa cam kết ổn định"** ngay tại dòng Stack. Đây là lý do tồn tại của AD-2, nên nói thẳng ra.

### P1 — siết AD-14 trước khi bắt đầu thi công

4. **Nâng nhánh "GPU không dùng được" từ khả năng lên kịch bản mặc định.** Bổ sung yêu cầu: adapter **phải tự bắt lỗi init GPU và fallback CPU**, vì LiteRT-LM **không tự làm**.

5. **Thêm hai phép đo vào AD-14** (hiện chỉ có ba): **(4)** thời gian `engine.initialize()` — Google cảnh báo *"up to 10 seconds"*, ăn thẳng vào NFR-005; **(5)** đỉnh RAM toàn app trên bản M52 6GB, khi cả RT-DETR + manga-ocr + Gemma cùng nạp.

6. **Kiểm minSdk của `litertlm-android`** ngay ở story đầu tiên. Nếu > 29 thì con số trong Stack phải sửa.

### P2 — bổ sung trước Phase 2

7. **Thêm một AD (hoặc mở rộng AD-10) cho vòng đời phiên MediaProjection:** một `MediaProjection` = một `VirtualDisplay` = một phiên; phiên bị hệ thống chấm dứt khi **khoá màn hình**; `onStop()` phải gỡ sạch overlay và đưa UI về trạng thái cần cấp quyền lại. Đây là yêu cầu nền tảng, không phải lựa chọn thiết kế.

8. **Sửa AD-11: cắt bỏ vùng status bar trước khi vào pipeline.** Chip chiếu màn hình của Android 15 QPR1+ **không ẩn được**, nên ảnh chụp không thể "sạch" theo nghĩa tuyệt đối mà AD-11 đang khẳng định.

9. **Kiểm thủ công file `detector-v4-s_int8` (11.1MB)** trên repo `ogkalu/comic-text-and-bubble-detector` — kiến trúc và giấy phép đã xác nhận, riêng biến thể file thì chưa.

10. **Đo riêng thời gian detect của RT-DETR trên M52.** Đừng giả định NNAPI sẽ tăng tốc — detector transformer thường rơi về CPU. Và khi lượng tử hoá int8, dùng **static quantization với calibration data là ảnh manga thật**, vì mất độ chính xác xảy ra **âm thầm, không báo lỗi lúc export**.
