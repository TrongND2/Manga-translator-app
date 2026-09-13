---
name: 'Review đối kháng — Architecture Spine'
type: architecture-review
target: 'ARCHITECTURE-SPINE.md (Manga Translator JA→VI Offline)'
date: '2026-09-13'
method: 'adversarial — dựng cặp đơn vị cùng tuân thủ AD nhưng không tương thích'
---

# Review đối kháng — ARCHITECTURE-SPINE.md

**Luật chơi:** mỗi lỗ hổng dưới đây là một cặp đơn vị **A** và **B**, mỗi bên đọc spine
độc lập, mỗi bên tuân thủ **đúng từng chữ** của mọi AD liên quan, và kết quả là hai thứ
không lắp được vào nhau. Không tính gu cá nhân, không tính thứ "thiếu chi tiết" mà chỉ có
một cách hiểu hợp lý.

**Kết luận chung:** spine chắc ở tầng quyết định công nghệ (AD-2, AD-14, AD-16) và ở tầng
chống lỗi model (AD-4, AD-5, AD-6) — đây là phần tốt nhất của tài liệu. Chỗ vỡ nằm ở
**tầng hợp đồng giữa các đơn vị**: hình dạng dữ liệu chảy qua pipeline, ai sở hữu cái gì,
và đơn vị "chấp nhận" là bubble hay cả trang. Bảy lỗ hổng dưới đây, ba cái đầu ở mức
CRITICAL.

| # | Lỗ hổng | Mức | AD liên quan |
|---|---|---|---|
| G1 | Đơn vị chấp nhận: bubble hay cả trang — chữ ký `Translator` phân nhánh | CRITICAL | AD-3, AD-6, AD-13 |
| G2 | `sourceHash`: một cái tên, hai hợp đồng ngược nhau | CRITICAL | AD-1, AD-12, FR-060..062 |
| G3 | `seriesKey` là khoá chính của glossary nhưng không ai sinh ra nó | CRITICAL | AD-7, AD-8 |
| G4 | Tập bubble vào prompt + ánh xạ ngược: theo id hay theo vị trí | HIGH | AD-3, AD-5, AD-6 |
| G5 | `RenderFilter` được giao việc mà tầng của nó không làm được | HIGH | AD-9, bảng layer, Deferred |
| G6 | Chữ ký filter: `PageJob` hay `Result<PageJob, PipelineError>` | HIGH | AD-1, AD-4, quy ước Lỗi |
| G7 | AD-11 khôi phục lớp phủ mà AD-12 vừa xoá | MEDIUM-HIGH | AD-11, AD-12 |

---

## G1 — Đơn vị chấp nhận là bubble hay cả trang? `[CRITICAL]`

### Tình huống

**Đơn vị A** viết `ports.Translator` và `pipeline.TranslateFilter`. A đọc:

> AD-13: "`pipeline` trả `Flow<PageEvent>`, **phát từng bubble ngay khi được chấp nhận**"
> NFR-005: bubble đầu tiên ≤ 8 giây

A kết luận: không thể chờ LLM sinh xong 12 bubble rồi mới phát — trên 22 tok/s thì cả
trang mất 30–90 giây, bubble đầu không bao giờ kịp 8 giây. Nên A định nghĩa:

```kotlin
interface Translator {
    fun translate(page: PageText, glossary: Glossary): Flow<TranslatedBubble>
}
```

Adapter stream theo token khi LiteRT-LM decode, TranslateFilter kiểm `jaEcho` cho từng
bubble ngay khi nó về, `Accept` thì phát `PageEvent.Bubble` liền. A vẫn đúng **AD-3** —
vẫn đúng **một** lần gọi LLM, chữ ký vẫn nhận cả trang, không có hàm nào nhận bubble đơn
lẻ. Vẫn đúng **AD-6** — vẫn đối chiếu `jaEcho` ba bước. Đúng **AD-13** từng chữ.

**Đơn vị B** viết cùng port đó ở nhánh khác. B đọc:

> AD-6: "Lệch ở bất kỳ bubble nào thì **cả trang** là `Retry` […] Không vá từng bubble —
> lệch là hiện tượng của cả trang"
> AD-4: cổng kiểm tra nằm **giữa** filter sinh dữ liệu và filter tiêu thụ nó

B kết luận: không bubble nào được coi là "đã chấp nhận" trước khi cả trang qua cổng —
đó chính là nội dung AD-6. Nên B định nghĩa đúng chữ ký mà AD-2 viết ra:

```kotlin
interface Translator {
    suspend fun translate(page: PageText, glossary: Glossary): TranslatedPage
}
```

TranslateFilter chạy cổng AD-6 trên toàn bộ kết quả, rồi Pipeline phát 12 `PageEvent.Bubble`
liên tiếp. B cũng đúng AD-13 từng chữ: vẫn "phát từng bubble", chỉ là tất cả cùng một lúc.

### Vỡ ở đâu

1. **Vỡ ở mức biên dịch.** `Flow<TranslatedBubble>` và `suspend … : TranslatedPage` là hai
   port khác nhau. Adapter của A không lắp vào TranslateFilter của B. AD-2 có gợi ý
   `translate(page, glossary): TranslatedPage` nhưng viết trong văn xuôi, không trong Rule,
   và AD-13 kéo ngược lại.
2. **Vỡ ở mức hành vi, nghiêm trọng hơn.** Với A: đã vẽ 4 bubble ra màn hình, bubble thứ 5
   lệch `jaEcho` → AD-6 buộc `Retry` **cả trang** → phải **gỡ 4 bubble đã vẽ**. Không AD nào
   cho phép thao tác đó: AD-12 chỉ cho xoá lớp phủ khi *nội dung bên dưới* đổi, AD-9 chỉ nói
   thứ tự tô-nền-rồi-vẽ-chữ. A phải tự bịa ra cơ chế rút lại, và người dùng thấy chữ Việt
   hiện ra rồi biến mất rồi hiện lại khác đi. Với B: NFR-005 gần như chắc chắn trượt và
   không có cách cứu trong kiến trúc hiện tại.
3. AD-6 nói thẳng "lệch là hiện tượng của **cả trang**" — tức về bản chất, chấp nhận sớm
   từng bubble là **sai**. Nhưng chữ của AD-13 lại khuyến khích đúng điều đó. Hai AD đang
   dạy hai mô hình chấp nhận khác nhau, và không AD nào nhận mình là bên nhường.

### Bịt lại

**Sửa Rule AD-13** (thêm câu phân biệt *chấp nhận* với *hiển thị*):

> Rule (bổ sung): **đơn vị chấp nhận là cả trang, không phải bubble.** `Flow<PageEvent>`
> phát sự kiện *tiến độ* sớm (`Captured`, `Detected(n)`, `Ocr(i/n)`, `Translating`) để UI
> không chết cứng, nhưng `PageEvent.Bubble` — sự kiện mang bản dịch để vẽ — **chỉ được phát
> sau khi cổng AD-6 trả `Accept` cho toàn trang**. Không lớp phủ bản dịch nào được vẽ rồi
> gỡ vì lý do nội bộ pipeline; lớp phủ chỉ bị gỡ theo AD-12.

**Sửa Rule AD-3** (chốt chữ ký, hết đường phân nhánh):

> Rule (bổ sung): chữ ký chính xác là
> `suspend fun translate(page: PageText, glossary: Glossary): TranslatedPage`.
> **Không trả `Flow`.** Adapter được phép stream nội bộ để hiển thị tiến độ, nhưng ranh giới
> port là một giá trị duy nhất cho cả trang.

**Hệ quả phải chấp nhận tường minh:** NFR-005 ("bubble đầu ≤ 8s") không đạt được bằng cách
vẽ chữ sớm. Cần viết lại NFR-005 thành "**phản hồi thị giác** đầu tiên ≤ 8s" và định nghĩa
phản hồi đó là chỉ báo tiến trình / khung mờ trên các bubble đã phát hiện — **không phải**
bản dịch. Nếu không chấp nhận được, thì AD-6 mới là thứ phải xét lại, và phải xét trong
AD-14 (đo trên máy) chứ không phải lúc đang code.

---

## G2 — `sourceHash`: một cái tên, hai hợp đồng ngược nhau `[CRITICAL]`

### Tình huống

**Đơn vị A** viết `adapters.capture` + `service`. A đọc AD-1 ("mọi `PageJob` mang một
`jobId` và `sourceHash` để truy vết") và AD-12 ("mỗi lớp phủ mang `sourceHash` của ảnh sinh
ra nó […] có bất kỳ tín hiệu nào cho thấy nội dung bên dưới đã đổi thì lớp phủ tự xoá ngay").
A cần hash **nhạy**: mục đích của nó là phát hiện màn hình đã đổi. A viết:

```kotlin
sourceHash = sha256(bitmap.allPixels)   // toàn khung, không bỏ gì
```

Đúng AD-1, đúng AD-12, đúng cả sequence diagram (`capture() → Bitmap + sourceHash`).

**Đơn vị B** viết `adapters.storage.FileCache` cho FR-060..062. B đọc cũng chính AD-1:

> "`sourceHash` để truy vết và **làm khoá cache**"

B cần hash **ổn định**: cùng một trang truyện phải cho cùng một khoá ở lần chụp sau, nếu
không cache vô dụng. Nên B loại thanh trạng thái và thanh điều hướng ra khỏi vùng hash, và
hạ mẫu ảnh để chịu được nhiễu nén và lệch cuộn vài pixel:

```kotlin
sourceHash = sha256(bitmap.crop(contentRect).scaleTo(256).quantize())
```

Cũng đúng AD-1 từng chữ.

### Vỡ ở đâu

Đây là cùng một trường, phục vụ hai yêu cầu **ngược dấu nhau**: AD-12 cần độ nhạy tối đa,
cache cần độ bất biến tối đa. Không tồn tại một hàm hash thoả cả hai.

- **Lấy định nghĩa của A** → **đồng hồ trên thanh trạng thái nhảy phút là đổi hash.** Cache
  gần như không bao giờ trúng (FR-060..062 chết lâm sàng), và nếu AD-12 hiện thực bằng
  "so hash toàn khung theo chu kỳ" — một trong ba cách mà chính mục Deferred để ngỏ — thì
  **cứ mỗi phút lớp phủ tự xoá một lần** trong khi người dùng vẫn đang đọc đúng trang đó.
  Bản dịch biến mất vô cớ.
- **Lấy định nghĩa của B** → AD-12 mù với thay đổi nhỏ, và tệ hơn: hai trang manga khác nhau
  cùng bố cục khung, sau khi hạ mẫu 256px, hoàn toàn có thể **đụng hash** → lớp phủ trang
  trước được coi là còn hợp lệ trên trang sau. Đó chính xác là FR-047, cái mà AD-12 sinh ra
  để chặn.

**Lỗ thứ hai đi kèm, cùng gốc:** quy ước toạ độ nói "**luôn là pixel của ảnh chụp gốc**".
Nếu B crop thanh trạng thái trước khi đưa vào `DetectFilter` thì "ảnh chụp gốc" của B lệch
so với của A đúng bằng chiều cao status bar. `adapters.overlay` chuyển sang toạ độ màn hình
bằng một phép biến đổi duy nhất — và phép biến đổi đó **khác nhau** tuỳ ai crop. Kết quả:
mọi lớp phủ lệch xuống/lên vài chục pixel, đều tăm tắp, trông như lỗi hiệu chỉnh chứ không
như lỗi kiến trúc — rất tốn thời gian truy.

**Lỗ thứ ba, nhỏ hơn:** ai tính hash? AD-11 bắt `ScreenSource.capture()` tự lo ẩn/hiện, gợi
ý nó trả về một struct; sequence diagram lại cho `CaptureService` tính (`S->>S: capture() →
Bitmap + sourceHash`). `ScreenSource` trả `Bitmap` hay trả `Capture`? Không ai biết.

### Bịt lại

**AD mới — AD-17 · Một ảnh chụp sinh ra hai định danh, không phải một**

- **Binds:** `ports.ScreenSource`, `adapters.capture`, `adapters.overlay`, `adapters.storage`, AD-1, AD-12
- **Prevents:** một trường duy nhất phải vừa nhạy vừa bất biến; và hai tầng hiểu khác nhau về "ảnh chụp gốc"
- **Rule:**
  1. `ScreenSource.capture()` trả **một** kiểu duy nhất:
     `Capture(bitmap, contentRect, frameHash, contentKey, capturedAtMs)`. Không tầng nào khác
     được tự tính hash.
  2. **`contentRect`** = vùng nội dung = ảnh chụp trừ thanh trạng thái và thanh điều hướng.
     Đây là **định nghĩa duy nhất** của "ảnh chụp gốc" trong quy ước toạ độ. `DetectFilter`
     nhận ảnh **đã crop**; mọi box trong `PageJob` là pixel của vùng này. `adapters.overlay`
     cộng lại offset của `contentRect` khi đổi sang toạ độ màn hình.
  3. **`frameHash`** — nhạy, dùng cho AD-12. Tính trên `contentRect` (đã bỏ đồng hồ/pin/sóng),
     không hạ mẫu, không lượng tử hoá.
  4. **`contentKey`** — bất biến, dùng làm khoá cache (FR-060..062). Cho phép hạ mẫu và lượng
     tử hoá. **Không bao giờ** dùng cho AD-12.
  5. `PageJob` mang cả hai. AD-1 sửa "`sourceHash`" thành "`frameHash` và `contentKey`", và
     AD-12 sửa "`sourceHash`" thành "**`frameHash`**".
  6. Đụng `contentKey` (hai trang khác nhau cùng khoá) chấp nhận được **chỉ khi** cache lưu
     kèm `frameHash` và xác nhận lại trước khi dùng.

---

## G3 — `seriesKey` là khoá chính của glossary nhưng không ai sinh ra nó `[CRITICAL]`

### Tình huống

AD-7 chốt: "Khoá là **`(seriesKey, surfaceForm)`**". AD-8 chốt nguồn đề xuất là "cụm
katakana/kanji lặp lại qua nhiều trang **cùng `seriesKey`**". Toàn bộ giá trị của glossary —
giữ xưng hô nhất quán trong một bộ truyện — nằm ở chỗ `seriesKey` phân tách đúng.

Nhưng: **không AD nào, không port nào, không filter nào sinh ra `seriesKey`.** AD-1 chỉ bắt
`PageJob` mang `jobId` và `sourceHash`. Structural Seed không có gì cho nó. Nó xuất hiện
trong tài liệu đúng hai lần, cả hai lần với tư cách thứ đã tồn tại sẵn.

**Đơn vị A** viết `adapters.storage.RoomGlossary` + `ui.GlossaryScreen`. A suy: đã có một
màn hình glossary, và spine ghi `service/ # CaptureForegroundService — chủ sở hữu trạng thái
phiên`. Vậy "phiên đọc" là khái niệm có sẵn → `seriesKey` là **tên bộ do người dùng chọn**,
giữ trong trạng thái phiên của service. Đúng AD-7, đúng AD-10 (không đụng app khác).

**Đơn vị B** viết `pipeline.TranslateFilter`. B suy: triết lý sản phẩm là "**một icon nổi là
toàn bộ giao diện**" — bắt người dùng khai báo bộ truyện trước khi đọc là phá đúng triết lý
đó. Nên `seriesKey` phải **tự suy ra**: package name của app đang ở foreground, hoặc hash
tiêu đề cửa sổ. Cũng đúng AD-7 từng chữ (AD-7 chỉ nói khoá là gì, không nói nó từ đâu ra).

### Vỡ ở đâu

1. **Dữ liệu vô hình lẫn nhau.** Hàng glossary A ghi với `seriesKey = "Kimetsu"` và hàng B
   ghi với `seriesKey = "app.mihon.tachiyomi"` nằm cùng một bảng, không bao giờ gặp nhau.
   Người dùng xác nhận một mục ở màn hình glossary (A) và nó không bao giờ vào prompt (B).
   Triệu chứng: "glossary không có tác dụng" — cực khó truy vì cả hai đường ghi đều chạy đúng.
2. **Lựa chọn của B tự đánh sập AD-7 về mặt ngữ nghĩa.** Một app đọc truyện mở **mọi** bộ.
   Package name gộp 20 bộ vào một bucket → glossary trộn nhân vật của 20 bộ → xưng hô sai
   chéo giữa các truyện. Đó đúng là thứ AD-7 sinh ra để chặn, và nó bị phá bởi một hiện thực
   **hợp lệ theo Rule**.
3. **Lựa chọn của B kéo theo quyền mới.** Đọc foreground package cần `UsageStatsManager`
   hoặc một `AccessibilityService`. Cả hai đều là bề mặt mới, đều va vào tinh thần AD-10
   ("app chỉ vẽ, trạng thái duy nhất để lại là lớp phủ và cache") và NFR-001/002. Một quyết
   định về *khoá dữ liệu* vừa âm thầm thêm một quyền nhạy cảm vào app.
4. **Lựa chọn của A không có chỗ để thao tác.** UI là một icon nổi. Không có màn hình nào
   trong luồng đọc để chuyển bộ. Nếu người dùng không chuyển, mọi thứ rơi vào bộ đang chọn
   từ tuần trước — sai âm thầm.

### Bịt lại

**AD mới — AD-18 · `seriesKey` do người dùng xác nhận; `service` là chủ sở hữu duy nhất của phiên đọc**

- **Binds:** `service.CaptureForegroundService`, `ports.GlossaryStore`, `pipeline.TranslateFilter`, `ui.GlossaryScreen`, AD-1
- **Prevents:** hai nơi cùng quyết định khoá chính của glossary; và việc suy `seriesKey` từ
  môi trường vừa sai ngữ nghĩa (một app đọc chứa mọi bộ) vừa kéo theo quyền không cần thiết
- **Rule:**
  1. `PageJob` **bắt buộc** mang `seriesKey` ngay từ lúc tạo. Filter nào cũng đọc được,
     không filter nào đặt lại.
  2. `seriesKey` do `CaptureForegroundService` gán từ **phiên đọc đang mở**. Service là nơi
     **duy nhất** giữ giá trị này.
  3. Phiên đọc chỉ đổi bằng **hành động tường minh của người dùng** — nhấn giữ icon nổi →
     chọn bộ có sẵn hoặc tạo bộ mới. Không tự đoán.
  4. **Cấm** mọi tầng suy `seriesKey` từ môi trường: package name, tiêu đề cửa sổ,
     accessibility, tên file. Muốn tự đoán về sau thì phải sửa AD này trước.
  5. MVP có một bộ mặc định `__unsorted__` để app dùng được ngay khi chưa chọn gì. Mục
     glossary gắn `seriesKey` **tại thời điểm tạo** và không được suy lại sau; đổi bộ không
     di chuyển mục cũ.
  6. Icon nổi hiển thị bộ đang mở ở dạng tối giản (một chấm màu / chữ cái đầu) để người dùng
     thấy mình đang tích luỹ vào đâu — nếu không, Rule 3 sai âm thầm.

---

## G4 — Tập bubble vào prompt và ánh xạ ngược: theo id hay theo vị trí `[HIGH]`

### Tình huống

Bốn quy tắc, mỗi cái đúng một mình, hợp lại thì mâu thuẫn:

> AD-3: "**Toàn bộ** bubble của một trang đi trong MỘT lần gọi LLM"
> AD-5: vùng không thoả ngưỡng 0.9 "bị hạ xuống `Suspect` và **không vẽ đè**" — không sang OCR
> AD-6: "đối chiếu nguyên bản, **không tin id**"
> Quy ước: "Id bubble: cấp bởi `DetectFilter`, **ổn định suốt vòng đời** `PageJob`. Không tầng nào được đánh lại số"

**Đơn vị A** (`TranslateFilter`): prompt chỉ gồm các bubble **có `jaText`** — tức đã qua cổng
AD-5 và đã OCR. Đánh số 0..n-1 trong prompt vì model cần danh sách liên tục; quy ước "không
đánh lại số" chỉ nói về id trong `PageJob`, còn prompt là chuyện nội bộ adapter. Ánh xạ kết
quả về bubble **theo vị trí** trong danh sách đã lọc — vì AD-6 bảo thẳng là đừng tin id.

**Đơn vị B** (viết adapter `LiteRtLmTranslator` và contract JSON): prompt gồm **tất cả**
bubble detect được — đó là chữ của AD-3 — với bubble `Suspect` mang text rỗng hoặc
`<không đọc được>`. Ánh xạ về **theo id**, vì quy ước nói id ổn định suốt vòng đời; `jaEcho`
chỉ dùng để *kiểm tra*, không dùng để *ánh xạ* (AD-6 nói "đối chiếu", không nói "ánh xạ bằng").

### Vỡ ở đâu

1. **Độ dài và thứ tự danh sách khác nhau** → cùng một `TranslatedPage` JSON không giải mã
   được giữa hai bên. Prompt của B có 14 phần tử, parser của A chờ 12.
2. **Bubble `Suspect` không có nguyên bản tiếng Nhật.** AD-6 bắt mỗi bubble trả kèm
   `jaEcho` = "2 ký tự đầu của **nguyên bản tiếng Nhật của chính bubble đó**". Với bubble
   `Suspect`, nguyên bản không tồn tại. Cổng AD-6 của B hoặc luôn fail, hoặc phải có một
   ngoại lệ mà **không AD nào định nghĩa**. Fail → AD-6 bắt `Reject` **cả trang**. Nghĩa là:
   **một mảnh tranh bị detector bắt nhầm làm hỏng bản dịch của toàn bộ trang.** Đây là chế độ
   hỏng thật, tần suất cao (FINDINGS F2/F4 cho thấy detector có bắt nhầm), và nó sinh ra từ
   việc ghép hai Rule đều đúng.
3. **Khi model bỏ sót một phần tử** (chuyện thường với LLM nhỏ int4), hai bên hành xử ngược
   nhau: A lệch toàn bộ phần đuôi → `jaEcho` bắt được → `Retry` rồi `Reject` cả trang (0/12
   bubble được dịch). B thấy thiếu một id → bubble đó không có bản dịch, 11 bubble còn lại
   vẫn đúng và vẫn vẽ. Cùng một đầu vào, một bên cho 0 bubble, một bên cho 11. AD-6 hoàn
   toàn không nói về trường hợp thiếu/thừa phần tử.
4. **`Suspect` không nằm trong `Verdict`.** AD-4 liệt kê `Verdict.Accept | Retry | Reject`.
   AD-5 giới thiệu trạng thái thứ tư `Suspect` mà không nói nó sống ở đâu. A đưa `Suspect`
   vào enum `Verdict` (phá tính đủ của AD-4); B tạo `BubbleStatus` riêng (sinh ra hai từ vựng
   trạng thái song song mà `RenderFilter` phải tra cả hai). Thêm nữa: `Verdict` là của **cổng**
   (một giá trị cho cả trang, theo AD-6) hay của **bubble** (theo AD-5)? Cả hai cách đọc đều
   đúng chữ AD-4.

### Bịt lại

**Sửa Rule AD-3:**

> Rule (thay thế): toàn bộ bubble **đủ điều kiện dịch** của một trang đi trong một lần gọi.
> "Đủ điều kiện dịch" = qua cổng AD-5 **và** có `jaText` không rỗng. Tập này gọi là
> `translatableSet`, được **đóng băng** trước khi gọi LLM và không đổi cho tới hết lượt.
> Bubble `Suspect` **không** vào prompt.

**Sửa Rule AD-6** (bổ sung ba câu, bịt cả ánh xạ lẫn thiếu/thừa):

> Rule (bổ sung): contract trả về là một **danh sách có độ dài đúng bằng `translatableSet`,
> theo đúng thứ tự đọc của nó**. Thiếu hoặc thừa phần tử = `Retry` cả trang, không cố cứu
> phần khớp được. Ánh xạ bản dịch về bubble **theo vị trí trong `translatableSet`**; trường
> `id` do model trả về bị **bỏ qua hoàn toàn** — nó tồn tại chỉ để prompt dễ đọc.

**Sửa Rule AD-4 / AD-5** (tách hai từ vựng trạng thái):

> `Verdict` (AD-4) là kết quả của **một cổng cho một trang**: `Accept | Retry | Reject`.
> `BubbleStatus` là trạng thái của **một bubble** trong `PageJob`:
> `Detected → Gated(Accepted|Suspect) → Ocred → Translated`. `Suspect` **không** phải
> `Verdict`. `RenderFilter` chỉ vẽ bubble có `BubbleStatus.Translated` **và** thuộc trang
> đã nhận `Verdict.Accept`.

---

## G5 — `RenderFilter` được giao việc mà tầng của nó không làm được `[HIGH]`

### Tình huống

Bốn chỗ trong spine nói về typesetting, và chúng không tương thích:

> Bảng layer: `pipeline` — "Được phép biết: **`domain`**". `domain` — "Chỉ Kotlin stdlib + coroutines"
> Deferred: "Thuật toán **co chữ và xuống dòng** cho typesetting — **nội bộ `RenderFilter`**, không ai ở ngoài thấy"
> Quy ước toạ độ: "Chuyển sang toạ độ màn hình **chỉ** xảy ra trong `adapters.overlay`"
> Sequence diagram: `O->>O: tô nền rồi vẽ chữ (AD-9)` — tức **overlay** là nơi thực thi AD-9

**Đơn vị A** làm đúng Deferred: thuật toán co chữ nằm trong `RenderFilter`. Nhưng co chữ cần
đo bề rộng chuỗi với đúng font và size → `Paint.measureText` / `StaticLayout` → `android.graphics`
→ **`pipeline` không được import**. A xử lý đúng bài: thêm một port `TextMeasurer` và adapter
`AndroidTextMeasurer`. Hợp lệ hoàn toàn với paradigm ("filter nặng nấp sau port") — nhưng port
này **không có trong danh sách 5 port** của spine, nên không ai khác biết nó tồn tại.

**Đơn vị B** thấy `pipeline` không đo được chữ và thấy sequence diagram giao AD-9 cho overlay,
nên chuyển **toàn bộ** typesetting sang `adapters.overlay` — nơi đã có Canvas, Paint và toạ độ
màn hình. `RenderFilter` co lại thành filter chỉ gắn cờ "sẵn sàng vẽ". B đúng AD-9 từng chữ
(AD-9 chỉ bắt **thứ tự**: có bản dịch → tô nền → vẽ chữ), đúng bảng layer, đúng sequence diagram.

### Vỡ ở đâu

1. **Không có port nào cho lớp phủ.** Danh sách ports: `TextDetector`, `OcrEngine`, `Translator`,
   `GlossaryStore`, `ScreenSource`. `RenderFilter` của A sinh ra một `RenderPlan` mà **không ai
   tiêu thụ** — nó phải hoặc thêm port thứ 6 không có trong spine, hoặc nhét plan vào `PageJob`
   và hy vọng overlay biết đọc.
2. **AD-9 được ép ở hai chỗ hoặc không chỗ nào.** Với A, thứ tự "tô nền → vẽ chữ" được quyết
   trong `domain`, nơi không vẽ được gì cả — nên nó chỉ là *ý định*, và overlay vẫn có thể tô
   nền trước. Với B, nó được ép ở đúng chỗ. Nếu A và B gặp nhau: hai nơi cùng quyết định thứ
   tự vẽ, một nơi không thi hành được.
3. **Vỡ hình học, tinh vi nhất.** Quy ước bắt `RenderFilter` (của A) làm việc bằng **pixel ảnh
   chụp**, còn overlay mới đổi sang pixel màn hình. Nhưng ảnh chụp `MediaProjection` thường
   **đã bị hạ mẫu** so với màn hình thật. A co chữ vừa khít bubble ở tỉ lệ ảnh chụp, overlay
   scale kết quả lên tỉ lệ màn hình → **chữ đã xuống dòng rồi mới bị scale** → số dòng đúng
   nhưng bề rộng tràn, hoặc font size thành số lẻ và hinting vỡ. Kiểu lỗi "gần đúng" này rất
   tốn thời gian truy vì không có gì crash.

### Bịt lại

**AD mới — AD-19 · `RenderFilter` quyết định *cái gì*, `adapters.overlay` quyết định *pixel***

- **Binds:** `pipeline.RenderFilter`, `adapters.overlay`, AD-9, quy ước toạ độ, mục Deferred
- **Prevents:** giao thuật toán cần font metrics cho một tầng bị cấm biết Android; và co chữ ở
  một tỉ lệ rồi vẽ ở tỉ lệ khác
- **Rule:**
  1. `RenderFilter` chỉ sinh `RenderPlan`: với mỗi bubble đủ điều kiện — rect nền theo **pixel
     vùng nội dung** (AD-17), chuỗi tiếng Việt, và **ràng buộc** (số dòng tối đa, khoảng font
     scale cho phép, canh giữa/dọc). **Không** đo chữ, **không** xuống dòng, **không** chọn
     font size.
  2. **Đo chữ, xuống dòng và co chữ nằm trong `adapters.overlay`**, vì chỉ nơi đó có font
     metrics thật và **đúng tỉ lệ màn hình cuối cùng**. Sửa mục Deferred tương ứng.
  3. `RenderFilter` **không vẽ**. Nó không gọi overlay. Kết quả của nó đi ra ngoài qua
     `PageEvent.Bubble` và `service` là nơi chuyển cho overlay — đúng như sequence diagram.
  4. Thứ tự bắt buộc của AD-9 được **thi hành trong `adapters.overlay`** (nơi duy nhất thi
     hành được) và được bảo vệ bằng cấu trúc: overlay chỉ phơi ra **một** hàm
     `drawBubble(plan)` tự làm cả tô nền lẫn vẽ chữ trong một lần; không có API tô nền riêng
     để ai đó gọi sớm.

---

## G6 — Chữ ký filter: `PageJob` hay `Result<PageJob, PipelineError>` `[HIGH]`

### Tình huống

Hai câu, cả hai là Rule/quy ước chính thức, mâu thuẫn trực tiếp:

> AD-1: "mọi filter có chữ ký `suspend fun apply(job: PageJob): PageJob`"
> Quy ước Lỗi: "`Result<T, PipelineError>`, **không dùng exception cho luồng nghiệp vụ**.
> `PipelineError` luôn mang `jobId` và tên filter"

**Đơn vị A** viết `DetectFilter` và `GateFilter`: giữ đúng chữ ký AD-1, mã hoá lỗi **vào
trong** `PageJob` (thêm `status` / `errors: List<PipelineError>`). Đúng AD-1 từng chữ, và
cũng không dùng exception → đúng luôn quy ước Lỗi.

**Đơn vị B** viết `OcrFilter` và `TranslateFilter`: dùng
`suspend fun apply(job: PageJob): Result<PageJob, PipelineError>` — vì quy ước Lỗi là bắt
buộc, và AD-1 không thể có nghĩa là "nuốt lỗi". B coi chữ ký trong AD-1 là lược giản cho dễ đọc.

### Vỡ ở đâu

1. **Vỡ ở mức biên dịch, ngay lần nối chuỗi đầu tiên.** `Pipeline` (nối 5 filter) được viết
   một lần bởi một trong hai; filter của bên kia không lắp vào được. Sửa thì phải sửa cả 5
   filter cùng lúc — đúng loại chi phí mà spine sinh ra để tránh.
2. **Sâu hơn: `Result` hai nhánh không biểu diễn được `Verdict` ba nhánh.** AD-4 bắt mỗi cổng
   trả `Accept | Retry | Reject`. `Retry` **không phải** thành công và **không phải** lỗi. B
   buộc phải bịa thêm — `Result.Err(PipelineError.Retryable)` chẳng hạn — và từ đó phải quyết
   định *ai thử lại*: B để filter tự lặp một lần trong `apply` (AD-6 nói "thử lại một lần",
   nghe như việc của filter), A để `Pipeline` lặp (khớp với quy ước "số lần thử lại nằm trong
   object cấu hình duy nhất"). Nếu cả hai cùng cài: **thử lại lồng nhau, 2×2 = 4 lần gọi LLM**
   cho một trang — trên 22 tok/s là vài phút — và không ai nhận ra, vì mỗi bên đều "thử lại
   đúng một lần".
3. Với cách của A, `PageJob` vừa là dữ liệu vừa là kênh lỗi → cổng kiểm tra AD-4 ("**tường
   minh**") trở thành ngầm: không nhìn chữ ký nào thấy được là có cổng.

### Bịt lại

**Sửa Rule AD-1** (chốt một chữ ký duy nhất, ba nhánh khớp đúng AD-4):

```kotlin
sealed interface FilterOutcome {
    data class Proceed(val job: PageJob) : FilterOutcome
    data class Retry(val job: PageJob, val reason: PipelineError) : FilterOutcome
    data class Rejected(val job: PageJob, val error: PipelineError) : FilterOutcome
}

suspend fun apply(job: PageJob): FilterOutcome
```

> Rule (bổ sung AD-1): ba nhánh của `FilterOutcome` ánh xạ 1-1 với `Verdict` của AD-4.
> **Filter không bao giờ tự thử lại.** `Pipeline` là nơi duy nhất xử lý `Retry`, với số lần
> lấy từ object cấu hình. Filter chỉ *báo* rằng nên thử lại.

> Sửa quy ước Lỗi: "`Result<T, PipelineError>` dùng ở **`ports` và `adapters`** (nơi chỉ có
> thành công/thất bại). `pipeline` dùng `FilterOutcome`. Không dùng exception cho luồng
> nghiệp vụ ở cả hai nơi."

---

## G7 — AD-11 khôi phục đúng lớp phủ mà AD-12 vừa xoá `[MEDIUM-HIGH]`

### Tình huống

> AD-11: "`ScreenSource.capture()` **tự** ẩn icon nổi và **mọi lớp phủ**, chờ một frame,
> chụp, rồi **hiện lại**. Không để trách nhiệm này cho người gọi"
> AD-12: "Có bất kỳ tín hiệu nào cho thấy nội dung bên dưới đã đổi thì lớp phủ **tự xoá ngay**"
> Sequence diagram: `S->>O: ẩn mọi lớp phủ (AD-11)` … `S->>O: **hiện lại icon**` — chỉ icon

**Đơn vị A** viết `adapters.capture`, làm đúng chữ AD-11: lưu danh sách view đang hiện, ẩn
tất cả, chụp, rồi **khôi phục tất cả** trong khối `finally` (icon **và** các lớp phủ bản dịch
cũ) — vì AD-11 nói "hiện lại" và nói rõ đây là trách nhiệm của `capture()`, không phải của
người gọi.

**Đơn vị B** viết `adapters.overlay`, làm đúng AD-12: so hash mới với hash của lớp phủ đang
treo, khác → **xoá ngay**, không chờ lượt dịch mới.

### Vỡ ở đâu

Đúng trình tự thời gian trong sequence diagram:

1. `capture()` của A ẩn lớp phủ cũ, **giữ tham chiếu để restore**.
2. Chụp → hash mới ≠ hash cũ (người dùng đã vuốt sang trang khác).
3. B thấy tín hiệu đổi nội dung → **xoá lớp phủ cũ ngay** (đúng AD-12).
4. Khối `finally` của A chạy → **khôi phục hiển thị các view mà B vừa gỡ**.

Kết quả tuỳ hiện thực: hoặc crash/`IllegalStateException` vì view đã detach; hoặc — tệ hơn và
im lặng hơn — A `addView` lại vào `WindowManager` và **bản dịch của trang cũ nằm đè lên trang
mới suốt 30–90 giây** trong khi lượt dịch mới đang chạy. Đó chính xác là FR-047, thứ AD-12
sinh ra để chặn, và nó bị phá bởi hai đơn vị **đều tuân thủ Rule của mình từng chữ**. AD-11
còn nói "không để trách nhiệm này cho người gọi" — tức nó **chủ động** giành quyền sở hữu
vòng đời của những view mà nó không sở hữu.

**Lỗ đi kèm: "chờ một frame" không đo được.** A dùng `postOnAnimation` (~16ms), B dùng
`Thread.sleep(100)`, người thứ ba dùng `ViewTreeObserver.OnDrawListener`. Nếu chọn quá ngắn,
`MediaProjection` vẫn bắt được frame còn lớp phủ → OCR đọc lại chữ Việt mình vừa vẽ → dịch
tiếng Việt sang tiếng Việt. Đúng thứ AD-11 tồn tại để chặn, và Rule của nó không cho cách nào
kiểm chứng là đã chặn được.

### Bịt lại

**Sửa Rule AD-11:**

> Rule (thay thế): trước khi chụp, `capture()` **gỡ hẳn** mọi lớp phủ bản dịch (không phải ẩn
> tạm) và ẩn icon nổi. Gỡ hẳn là đúng theo AD-12: ngay khi người dùng yêu cầu một lượt mới,
> lớp phủ cũ **đã hết giá trị** — không có trường hợp nào cần khôi phục nó. Sau khi chụp,
> `capture()` chỉ **hiện lại icon** (khớp sequence diagram).
>
> `capture()` **không tự đụng vào view**. Nó gọi đúng hai hàm của `adapters.overlay`:
> `overlay.detachAllTranslations()` và `overlay.hideIcon()` / `overlay.showIcon()`.
> **Chủ sở hữu duy nhất của vòng đời mọi view lớp phủ là `adapters.overlay`**;
> `adapters.capture` không bao giờ `addView` / `removeView` / đổi visibility của view nó
> không tạo ra.
>
> "Chờ một frame" thay bằng `captureSettleMs` trong object cấu hình (mặc định ≈ 2 vsync, đo
> lại trên M52 trong AD-14), **và** một self-check ở bản debug: chạy `DetectFilter` trên ảnh
> vừa chụp, nếu có box trùng > 50% với rect lớp phủ vừa gỡ thì ghi log mã lỗi `DIRTY_CAPTURE`.

---

## Quan sát phụ (thật, nhưng rẻ hơn để sửa)

Không đủ nặng để viết thành mục riêng, nhưng mỗi cái vẫn là chỗ hai người chọn khác nhau được:

1. **Ai lọc `Confirmed` khỏi glossary?** AD-7 cấm mục `Proposed` vào prompt, nhưng không nói
   lọc ở đâu. Nếu `GlossaryStore.getAll(seriesKey)` trả cả `Proposed` và `TranslateFilter`
   quên lọc → `Proposed` rò vào prompt, phá đúng AD-7, và không có gì báo lỗi.
   **Sửa:** `GlossaryStore` phơi ra `confirmedFor(seriesKey): Glossary` là hàm **duy nhất** mà
   `pipeline` được gọi; các hàm trả `Proposed` chỉ `ui` được gọi.
2. **Ai ghi mục `Proposed`, và ở đâu?** AD-8 lấy nguồn từ trường `speaker` của LLM và từ
   heuristic "lặp lại qua nhiều trang". Nhưng AD-1 mô tả filter là hàm thuần trả `PageJob`.
   Một người cho `TranslateFilter` gọi thẳng `glossaryStore.propose(...)` (tác dụng phụ trong
   một filter "thuần"); một người giữ filter sạch và cho `service` thu hoạch từ
   `Flow<PageEvent>` — nhưng khi đó `speaker` phải được mang trong `PageEvent`, nếu không nó bị
   mất và AD-8 nguồn (a) không hiện thực được. Ngoài ra heuristic "lặp qua nhiều trang" cần
   **state xuyên `PageJob`**, mà không đơn vị nào trong spine sở hữu state đó.
   **Sửa:** chốt rằng filter không có tác dụng phụ; `PageEvent` mang `speaker`; và
   `GlossaryStore` (không phải pipeline) là nơi giữ bộ đếm tần suất xuyên trang.
3. **Cache trúng thì bỏ qua cái gì?** FR-060..062 không có AD nào chi phối ngoài "sourceHash là
   khoá". Một người cache `TranslatedPage` cả trang (trúng → bỏ qua cả 5 filter, không chạy lại
   cổng AD-6 → một kết quả sai từng được chấp nhận sẽ sai mãi mãi); một người cache kết quả OCR
   theo crop hash (trúng → vẫn gọi LLM).
   **Sửa:** một AD ngắn chốt "cache lưu đầu ra của filter nào, và kết quả lấy từ cache có phải
   chạy lại cổng không". Đề xuất: cache `TranslatedPage` đã `Accept`, **không** chạy lại cổng
   (đã qua rồi), nhưng lưu kèm phiên bản model + phiên bản prompt; đổi một trong hai thì cache
   vô hiệu.
4. **"Thử lại một lần" (AD-6) vs "số lần thử lại nằm trong object cấu hình" (quy ước).**
   Một bên hard-code 1, một bên đọc cấu hình. Chốt một chỗ — xem G6.
5. **Sơ đồ mermaid đầu tiên mâu thuẫn với sequence diagram:** `F1 -.-> P5 -.-> A5` cho thấy
   `DetectFilter` gọi `ScreenSource`, nhưng sequence diagram cho thấy `CaptureService` chụp
   **trước** rồi mới `run(PageJob)`. Nếu `DetectFilter` tự chụp thì AD-11 phải chạy bên trong
   pipeline, và `PageJob` khởi tạo lúc chưa có ảnh — mâu thuẫn với AD-1 ("mọi `PageJob` mang
   `sourceHash`"). Sequence diagram mới là đúng; **sửa sơ đồ** để `ScreenSource` treo dưới
   `service`, không dưới `F1`.

---

## Xếp thứ tự sửa

| Thứ tự | Việc | Vì sao trước |
|---|---|---|
| 1 | **G1** — chốt đơn vị chấp nhận + chữ ký `Translator` | Quyết định này định hình `PageEvent`, `RenderFilter`, và cả cách viết NFR-005. Sửa sau là sửa cả pipeline |
| 2 | **G2** — tách `frameHash` / `contentKey` + định nghĩa `contentRect` | Chạm vào AD-1, AD-12, quy ước toạ độ và cache cùng lúc |
| 3 | **G3** — AD-18 `seriesKey` | Là khoá chính của một bảng DB; sửa sau là migration |
| 4 | **G6** — chốt `FilterOutcome` | Chữ ký của cả 5 filter |
| 5 | **G4** — `translatableSet` + ánh xạ theo vị trí | Chốt được contract JSON trước khi viết prompt |
| 6 | **G5** — AD-19 ranh giới typesetting | Có thể hiện thực ở Phase 4, nhưng chốt ranh giới ngay |
| 7 | **G7** — quyền sở hữu vòng đời lớp phủ | Rẻ, sửa hai câu trong AD-11 |

**Lưu ý về AD-14:** cổng chặn này vẫn đứng vững và nên giữ nguyên. Nhưng G1 cho thấy AD-14 nên
đo thêm **một số thứ tư**: *thời gian tới token cuối cùng cho một trang 12 bubble*, không chỉ
`tok/s`. Vì nếu chốt "chấp nhận theo cả trang" (đề xuất G1), thì con số quyết định NFR-005 có
đạt hay không chính là số đó — và nếu nó là 90 giây, quyết định cần ra **trước** khi viết UI,
đúng tinh thần AD-14.
