---
stepsCompleted: ['step-01', 'step-02', 'step-03']
inputDocuments:
  - '_bmad-output/planning-artifacts/prds/prd-Auto translate android-2026-09-13/prd.md'
  - '_bmad-output/planning-artifacts/architecture/architecture-Auto translate android-2026-09-13/ARCHITECTURE-SPINE.md'
  - 'spike/FINDINGS.md'
---

# Manga Translator JA→VI Offline - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Manga Translator JA→VI Offline, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

- **FR-001**: Bật app từ launcher sẽ hiện một **icon nổi (shortcut icon)** nằm đè lên mọi app khác. Đây là toàn bộ giao diện điều khiển khi đang đọc
- **FR-002**: Icon nổi kéo di chuyển được và tự nép vào mép màn hình, không che mất nội dung đang đọc
- **FR-003**: **Một chạm vào icon** chạy trọn luồng dịch cho trang đang hiển thị: chụp → phát hiện bubble → OCR → dịch → vẽ đè. Không hỏi lại, không bước trung gian nào
- **FR-004**: Trong lúc xử lý, chính icon hiện trạng thái tiến trình (đang chụp / đang đọc chữ / đang dịch)
- **FR-005**: **Giữ icon** hiện ra một **icon con hình X** ngay cạnh nó. Chạm vào X sẽ **đóng app**: gỡ mọi lớp phủ, dừng foreground service, thu hồi phiên chụp màn hình
- **FR-006**: Khi app đã đóng, **trang truyện trở lại nguyên bản tiếng Nhật y như cũ**. App không bao giờ sửa nội dung của app bên dưới — bản dịch chỉ là một lớp vẽ đè, gỡ lớp đó là chữ gốc hiện lại nguyên vẹn
- **FR-007**: Chạm giữ **vào vùng bản dịch** (không phải vào icon) để tạm ẩn lớp phủ và liếc nguyên bản tiếng Nhật; thả ra thì bản dịch hiện lại. Cử chỉ này phải phân biệt rõ với FR-005 để không vô tình đóng app
- **FR-008**: Chạm vào **icon con hình quyển sách** mở **hướng dẫn sử dụng**: giải thích các cử chỉ, ý nghĩa trạng thái trên icon, và cách xử lý khi không dịch được
- **FR-009**: Hướng dẫn **nằm trong APK, hoạt động hoàn toàn offline**. Đóng hướng dẫn thì quay lại đúng trạng thái trước đó, phiên chụp **không bị mất**
- **FR-009b**: Hai icon con **đủ xa nhau để không bấm nhầm**, và **X không được là đích dễ chạm nhất**
- **FR-010**: App chụp màn hình hiện tại qua MediaProjection
- **FR-011**: Việc chụp chạy trong foreground service có `foregroundServiceType="mediaProjection"` khi hệ điều hành yêu cầu
- **FR-012**: Hiện thông báo thường trực cho biết app đang có khả năng chụp màn hình
- **FR-013**: Khi gặp app đặt `FLAG_SECURE`, app **báo rõ lý do không chụp được**, không im lặng thất bại hay hiện ảnh đen
- **FR-014**: Nếu hệ điều hành thu hồi quyền chụp (Android 15+ yêu cầu xác nhận lại mỗi phiên), app xin lại quyền một cách rõ ràng thay vì crash
- **FR-015**: **Trước mỗi lần chụp, app tự ẩn icon nổi và mọi lớp phủ bản dịch đang hiện.** Nếu không, ảnh chụp sẽ chứa chính icon và bản dịch cũ, khiến OCR đọc lại chữ Việt mình vừa vẽ
- **FR-016**: Phiên chụp màn hình **xin quyền một lần rồi giữ sống** suốt thời gian app bật, để mỗi lần chạm icon không bị bật lại dialog hệ thống. Đây là điều kiện để FR-003 thật sự là "một chạm"
- **FR-020**: Phát hiện các vùng chữ / khung thoại trên ảnh chụp, trả về bounding box
- **FR-021**: Sắp bounding box theo **thứ tự đọc manga: phải→trái, trên→dưới**
- **FR-022**: OCR đọc được chữ **dọc** (縦書き) và chữ **ngang** trong cùng một trang
- **FR-023**: **Phải có cơ chế loại vùng không chứa thoại TRƯỚC khi đưa sang bước dịch.** manga-ocr là mô hình image→text, không có đầu ra "chỗ này không có chữ" và không trả điểm tin cậy — đưa cho nó một mảnh tranh, nó **vẫn bịa ra câu tiếng Nhật trông hợp lý** (đã quan sát thực tế, xem `spike/FINDINGS.md` F2). Thoại bịa sẽ được dịch trôi chảy rồi vẽ đè vào bubble, và người đọc không có cách nào phát hiện. Cơ chế lọc phải nằm ngoài manga-ocr
- **FR-024**: Bước phát hiện và bước OCR là hai thành phần thay thế được (interface tách rời)
- **FR-030**: **Toàn bộ bubble của một màn hình được gửi cho LLM trong MỘT lần gọi duy nhất**, kèm thứ tự đọc và glossary. Đây là ràng buộc kiến trúc, không phải tối ưu hoá tuỳ chọn
- **FR-031**: Prompt hướng dẫn LLM suy ra người nói / người nghe từ ngữ cảnh cả trang rồi mới chọn xưng hô tiếng Việt
- **FR-032**: App duy trì **glossary theo từng bộ truyện**, gồm **ba loại**: (a) tên riêng — nhân vật, địa danh; (b) **thành ngữ / tiếng lóng theo bối cảnh truyện**; (c) xưng hô đã chốt giữa từng cặp nhân vật. Glossary được đưa vào prompt mỗi lần gọi. **Đây là thành phần trung tâm, không phải tinh chỉnh** — xem `spike/FINDINGS.md` F8/F9: cùng model cùng prompt, chỉ mở rộng glossary đã kéo điểm từ 46% lên 60%, trong khi tăng gấp đôi cỡ model không cải thiện gì
- **FR-033**: Người dùng xem và sửa được mọi mục glossary
- **FR-034**: Glossary **tự tích luỹ**: tên riêng và thuật ngữ gặp lại nhiều lần được đề xuất thêm vào, người dùng xác nhận. Không bắt người dùng gõ tay từ số không
- **FR-035**: Nếu LLM trả về JSON hỏng hoặc thiếu bubble, app tự thử lại một lần; vẫn hỏng thì hiện nguyên bản tiếng Nhật cho bubble đó thay vì bỏ trống
- **FR-036**: **Kiểm tra toàn vẹn ánh xạ id ↔ nội dung trước khi vẽ.** JSON hợp lệ và đủ số phần tử **KHÔNG** đủ để tin. Đã quan sát thực tế (`spike/FINDINGS.md` F10): model trả về đủ 12 bubble, id chạy đúng 0→11, `done_reason: stop`, mọi kiểm tra tự động đều xanh — nhưng toàn bộ bản dịch **lệch đi một ô** từ bubble thứ 4 trở đi. Người đọc thấy mọi bubble đều trôi chảy mà cả trang sai mạch hội thoại
- **FR-037**: Dịch chạy **100% trên máy**. Không có đường nào gửi dữ liệu ra ngoài
- **FR-038**: Interface `Translator` tách rời để sau này thay impl mà không sửa phần còn lại
- **FR-040**: Che kín chữ Nhật gốc bằng cách fill màu nền lấy mẫu từ chính trong bubble đó, sao cho không còn nhìn thấy nét chữ gốc thò ra
- **FR-041**: Vẽ chữ Việt **nằm gọn bên trong đúng bóng thoại tương ứng** — đúng bubble, không lệch sang bubble khác, không tràn ra ngoài viền
- **FR-042**: Tự co cỡ chữ cho vừa bubble; xuống dòng theo ranh giới từ, không cắt giữa từ
- **FR-043**: Font dùng để vẽ phải có **đầy đủ dấu tiếng Việt** — kiểm tra bằng cách render thử chuỗi đủ dấu, không tin tên font
- **FR-044**: Bản dịch hiện **dần từng bubble ngay khi có**, không chờ đủ cả màn
- **FR-045**: Lớp phủ không chặn thao tác của app bên dưới ngoài vùng bubble — vuốt sang trang, cuộn vẫn bình thường
- **FR-046**: Bubble nào không dịch được thì **để nguyên chữ Nhật gốc**, không che bằng ô trống. Che mất chữ mà không thay được gì là tệ hơn không làm
- **FR-047**: Khi nội dung bên dưới đổi (vuốt sang trang, cuộn), **lớp phủ của trang cũ phải tự biến mất**. Bản dịch trang trước nằm đè lên trang sau là lỗi nặng — sai nội dung mà nhìn vẫn như đúng
- **FR-050**: Lần chạy đầu, app hướng dẫn tải gói mô hình, **nói rõ dung lượng và khuyến nghị dùng Wi-Fi** trước khi bắt đầu
- **FR-051**: Việc tải tạm dừng / tiếp tục được, và **tải tiếp chỗ dở** khi rớt mạng chứ không làm lại từ đầu
- **FR-052**: Kiểm tra checksum sau khi tải; hỏng thì tải lại phần hỏng
- **FR-053**: App đo RAM máy; dưới ngưỡng thì **cảnh báo rõ trước khi tải**, cho người dùng chọn tiếp tục hay dừng
- **FR-054**: Màn hình quyền giải thích **từng quyền dùng để làm gì** trước khi xin, không đẩy thẳng dialog hệ thống
- **FR-055**: Xoá gói mô hình để giải phóng dung lượng
- **FR-060**: Kết quả dịch cache theo hash ảnh chụp; màn đã dịch thì trả ra ngay
- **FR-061**: Xem dung lượng cache và xoá được
- **FR-062**: Cache có giới hạn dung lượng, tự dọn bản cũ nhất khi đầy

### NonFunctional Requirements

- **NFR-001**: Hoạt động khi **tắt hoàn toàn mạng** | 100% luồng chính | Kiểm bằng chế độ máy bay
- **NFR-002**: Không gửi bất kỳ dữ liệu nào ra ngoài sau khi tải xong gói | 0 kết nối | Kiểm bằng công cụ theo dõi mạng
- **NFR-003**: Độ chính xác OCR trên bộ test | CER ≤ 10% | `[ASSUMPTION]` R1 — chưa có ground truth nên **chưa đo được**
- **NFR-004**: Chất lượng dịch "hiểu được mạch truyện", đo trên **manga phổ thông** | ≥ 70% bubble | **Đã hạ từ 80%.** Lý do ở §8.4. Chấm tay theo rubric; **xưng hô sai KHÔNG tính Fail** nếu vẫn hiểu mạch
- **NFR-004b**: Chất lượng trên **truyện khó** (khẩu ngữ cổ, tiếng lóng nhiều) | ≥ 50% bubble | Ngưỡng riêng. Đo được **47%** ở Phase 0 trước khi sửa lỗi lệch id và glossary tích luỹ
- **NFR-005**: **Bubble đầu tiên xuất hiện** | **≤ 8s** | **Đây mới là chỉ số người dùng cảm nhận**, vì FR-044 hiện dần từng bubble. Đã thăng lên làm chỉ số tốc độ chính
- **NFR-005b**: Tổng thời gian một màn | ≤ 90s trên M52 | **Đã nới từ 40s.** Lý do ở §8.3 — ngưỡng 40s đặt trước khi có phép đo nào, và đặt sai chỗ
- **NFR-007**: Không OOM-kill trong 30 phút đọc liên tục | 0 lần | `[ASSUMPTION]` R3
- **NFR-008**: Kích thước APK | ≤ 100MB | Mô hình tải riêng
- **NFR-009**: UI không bị chặn trong lúc xử lý | 0 ANR | Toàn bộ pipeline chạy ngoài main thread
- **NFR-010**: Chi phí vận hành | 0 đồng | Không server, không API trả phí, không CDN tự dựng

### Additional Requirements

Từ ARCHITECTURE-SPINE (22 quyết định kiến trúc — mọi story phải tuân thủ):

- **AD-1** — `PageJob` bất biến là kiểu dữ liệu duy nhất đi qua pipeline
- **AD-2** — LiteRT-LM là runtime LLM duy nhất, nấp sau port `Translator` `[ADOPTED]`
- **AD-3** — Toàn bộ bubble của một trang đi trong MỘT lần gọi LLM `[ADOPTED]`
- **AD-4** — Đầu ra của model là dữ liệu KHÔNG đáng tin cho tới khi qua cổng kiểm tra
- **AD-5** — Cổng vùng chữ: `text_bubble` phải nằm trong một `bubble`
- **AD-6** — Cổng toàn vẹn ánh xạ: đối chiếu nguyên bản, không tin id
- **AD-7** — Glossary là state có DUY NHẤT một chủ sở hữu, ghi theo bộ truyện
- **AD-8** — Glossary tự tích luỹ KHÔNG dùng model riêng
- **AD-9** — Thoái lui luôn là chữ gốc, không bao giờ là ô trống
- **AD-10** — Lớp phủ chỉ vẽ, không bao giờ sửa app bên dưới
- **AD-11** — Ảnh chụp phải sạch: tự ẩn mọi lớp phủ trước khi chụp
- **AD-12** — Lớp phủ gắn với `sourceHash`, đổi nội dung là tự xoá
- **AD-13** — Mọi thứ ngoài main thread, kết quả chảy ra theo dòng
- **AD-14** — Cổng chặn: đo LiteRT-LM trên M52 trước khi xây phần còn lại
- **AD-15** — Gói mô hình không nằm trong APK, và có phiên bản
- **AD-16** — Gemma 4 **E2B** là model dịch, không phải E4B
- **AD-17** — Hợp đồng streaming: `Translator` trả `Flow`, chấp nhận theo từng bubble
- **AD-18** — Tách `frameHash` và `contentKey`: một hash không gánh nổi hai vai
- **AD-19** — MVP dùng MỘT glossary chung; `seriesKey` chưa tồn tại
- **AD-20** — Hâm nóng engine lúc bật app, không phải lúc chạm icon
- **AD-21** — Phiên chụp màn hình là tài nguyên có chủ, và nó chết bất ngờ
- **AD-22** — Phân vai máy ảo và máy thật

**Không dùng starter template.** Dự án Android thuần Kotlin, dựng từ Android Studio project mới.

### UX Design Requirements

Không có tài liệu UX riêng. Yêu cầu UX nằm trong PRD §3 (UJ-1, UJ-2) và §5.1/§5.5, đã được tính vào danh sách FR ở trên.

### FR Coverage Map

| FR | Epic | Ghi chú |
|---|---|---|
| FR-001..009b | **Epic 3** | Icon nổi, hai icon con (hướng dẫn + đóng), hướng dẫn sử dụng offline |
| FR-010..016 | **Epic 3** | Chụp màn hình, quyền, tự ẩn overlay trước khi chụp |
| FR-020..024 | **Epic 2** | Phát hiện bubble, OCR, cổng lọc vùng không có thoại |
| FR-030..038 | **Epic 2** | Dịch, glossary, cổng toàn vẹn id |
| FR-040..044, 046 | **Epic 2** | Hiển thị, thay chữ tại chỗ, thoái lui giữ chữ gốc |
| FR-045, FR-047 | **Epic 3** | Chỉ có nghĩa khi đã có lớp phủ: không chặn thao tác, tự xoá khi sang trang |
| FR-050..055 | **Epic 4** | Gói mô hình & onboarding |
| FR-060..062 | **Epic 2** | Cache |

**Phủ 45/45 FR.** Không FR nào rơi.

### NFR gắn vào đâu

| NFR | Nghiệm thu ở Epic | Đo ở đâu (AD-22) |
|---|---|---|
| NFR-001 offline, NFR-002 không rò rỉ | Epic 2 | Máy ảo — bật chế độ máy bay |
| NFR-003 CER ≤10% | Epic 2 | Máy ảo (đúng/sai không phụ thuộc phần cứng) |
| NFR-004 / 004b chất lượng dịch | **Epic 1** rồi lại Epic 2 | **Chỉ M52** cho bản int4 |
| NFR-005 bubble đầu ≤8s | Epic 3 | **Chỉ M52** |
| NFR-005b tổng ≤90s | **Epic 1** | **Chỉ M52** |
| NFR-007 không OOM-kill | **Epic 1** | **Chỉ M52** |
| NFR-008 APK ≤100MB | Epic 4 | Máy nào cũng được |
| NFR-009 không ANR | Epic 2, 3 | Máy ảo |
| NFR-010 chi phí 0đ | mọi Epic | — |

## Epic List

### Epic 1: Cổng khả thi — đo LiteRT-LM trên Galaxy M52

Trả lời dứt điểm câu hỏi *dự án này có chạy nổi trên máy thật không*, trước khi viết bất cứ dòng code sản phẩm nào. Ba con số: tok/s trên GPU, tok/s trên CPU, và chất lượng bản int4 so với trần 77% đo được ở Phase 0.

**FRs covered:** KHÔNG CÓ — đây là ngoại lệ cố ý.

Epic này **không tạo ra giá trị nào cho người dùng**, và tôi không nguỵ trang điều đó. Nó tồn tại vì AD-14, và AD-14 tồn tại vì Phase 0 đã bác **ba** giả định của brief liên tiếp: "model to hơn thì tốt hơn" (sai hai lần), "họ Qwen mạnh tiếng Nhật nên chọn Qwen" (sai), "MediaPipe là runtime" (đã khai tử). Xác suất giả định thứ tư sai không nhỏ, và phát hiện sau khi viết xong app thì phải đập đi.

**Hai nhánh thoát:** GPU không dùng được trên Adreno 642L → xét lại AD-2. Bản int4 tụt dưới 70% → xét lại AD-16 hoặc ngưỡng NFR-004.

### Epic 2: Dịch được một trang ảnh

Người dùng chọn một ảnh trang manga có sẵn trên máy và nhận lại ảnh đã **thay chữ Nhật bằng chữ Việt ngay trong bóng thoại**. Chưa có overlay, chưa chụp màn hình — nguồn ảnh là file, để gỡ lỗi pipeline dễ.

**FRs covered:** FR-020, FR-021, FR-022, FR-023, FR-024, FR-030, FR-031, FR-032, FR-033, FR-034, FR-035, FR-036, FR-037, FR-038, FR-040, FR-041, FR-042, FR-043, FR-044, FR-046, FR-060, FR-061, FR-062

Epic lớn nhất (23 FR) và **cố ý không tách nhỏ**: toàn bộ 5 filter, 5 port và các adapter đều bị sửa cùng nhau. Tách ra sẽ tạo nhiều epic cùng đụng một bộ file — đúng thứ nguyên tắc thiết kế epic cấm.

Bao gồm cả glossary, vì FINDINGS F9 đã chứng minh nó là **đòn bẩy chất lượng mạnh nhất** (46%→60% trên cùng model), không phải tính năng phụ.

Model đưa lên máy bằng `adb push` — việc này Epic 1 đã phải làm rồi.

### Epic 3: Dịch màn hình đang đọc

Sản phẩm thật. Một icon nổi là toàn bộ giao diện: **một chạm** dịch cả trang đang hiển thị, **giữ icon → X** để đóng, và đóng xong thì **chữ Nhật gốc hiện lại nguyên vẹn**.

**FRs covered:** FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008, FR-009, FR-009b, FR-010, FR-011, FR-012, FR-013, FR-014, FR-015, FR-016, FR-045, FR-047

Dựng trên pipeline đã chạy được của Epic 2, chỉ thay nguồn ảnh từ "chọn file" sang MediaProjection. Đứng độc lập vì Epic 2 đã cho pipeline hoàn chỉnh.

### Epic 4: Máy sẵn sàng dịch — tải gói mô hình

Người khác cài được app: hiểu rõ cần tải gì và vì sao, tải ~3.1GB có tạm dừng/tiếp tục/chống rớt mạng, app xác minh checksum trước khi dùng.

**FRs covered:** FR-050, FR-051, FR-052, FR-053, FR-054, FR-055

**Xếp cuối cùng là cố ý.** PRD §1 chốt *dùng riêng trước, public chỉ tính sau khi tự dùng ổn* — nên trong giai đoạn này onboarding đẹp là thứ ít giá trị nhất, không phải nhiều nhất. Epic 1 đã đưa model lên máy bằng `adb push`; phần tải tự động là trải nghiệm cài đặt **cho người khác**, không phải điều kiện để pipeline chạy.

---

## Epic 1: Cổng khả thi — đo LiteRT-LM trên Galaxy M52

Trả lời dứt điểm *dự án này có chạy nổi trên máy thật không*, trước khi viết dòng code sản phẩm nào. Không có FR nào — đây là cổng chặn của AD-14.

> **Quy tắc xuyên suốt epic này (AD-22):** mọi con số hiệu năng **chỉ** lấy từ Galaxy M52 thật. Máy ảo dùng để làm app chạy được, **cấm** dùng để lấy số.

### Story 1.1: Nạp được Gemma 4 E2B qua LiteRT-LM

**Thoả:** _Không thoả FR nào — cổng chặn AD-14_

As a nhà phát triển,
I want một app Android tối giản nạp được model Gemma 4 E2B qua LiteRT-LM và sinh ra một câu tiếng Việt,
So that tôi biết chuỗi công nghệ này hoạt động trước khi xây bất cứ thứ gì lên trên.

**Acceptance Criteria:**

**Given** project Android mới với LiteRT-LM được ghim **phiên bản chính xác** (không dùng `latest.release`, vì thư viện còn ở 0.x — AD-2)
**When** đẩy file `gemma-4-E2B-it.litertlm` lên `/data/local/tmp` bằng `adb push` và chạy app trên máy ảo `M52_like_A13`
**Then** app nạp model thành công và in ra một câu tiếng Việt từ một prompt cố định
**And** log ghi rõ backend đang dùng là CPU hay GPU
**And** thời gian `engine.initialize()` được đo và ghi log riêng — Google cảnh báo nó có thể mất tới 10 giây (AD-20)

### Story 1.2: Đo tok/s trên M52 — cả GPU lẫn CPU

**Thoả:** _Không thoả FR nào — trả lời NFR-005b_

As a nhà phát triển,
I want biết Gemma 4 E2B chạy được bao nhiêu token mỗi giây trên chính Galaxy M52,
So that tôi biết NFR-005b (≤90s/trang) có đạt được không, hay phải xét lại cỡ model.

**Acceptance Criteria:**

**Given** app của Story 1.1 chạy trên **Galaxy M52 thật** qua `adb`
**When** app thử khởi tạo backend GPU
**Then** hoặc GPU khởi tạo thành công và tok/s được ghi lại, hoặc app **bắt được lỗi và tự lùi về CPU** mà không sập — LiteRT-LM **không có fallback tự động**, GPU init thất bại làm hỏng cả Engine (AD-2)
**And** tok/s trên CPU được đo riêng trong mọi trường hợp
**And** kết quả ghi vào `spike/FINDINGS.md` kèm ghi chú rõ đây là số đo trên máy thật, không phải ngoại suy

**Given** Adreno 642L chỉ hỗ trợ OpenCL 2.0 trong khi backend `LITERT_CL` nhắm OpenCL 3.0
**When** GPU không dùng được
**Then** kết luận này được ghi lại và AD-2 được cập nhật — **không** coi là thất bại của story

### Story 1.3: Chấm lại 48 bubble bằng bản int4 trên M52

**Thoả:** _Không thoả FR nào — trả lời NFR-004_

As a nhà phát triển,
I want biết bản int4 mất bao nhiêu điểm chất lượng so với trần 77% đo được trên PC,
So that tôi biết NFR-004 (≥70%) có còn đạt sau lượng tử hoá không.

**Acceptance Criteria:**

**Given** 48 bubble tiếng Nhật đã OCR sẵn từ Phase 0 (`spike/out/r2_scenes.json`) và glossary đã dùng ở Phase 0
**When** chạy chúng qua Gemma 4 E2B **bản int4 trên M52** với đúng prompt v1
**Then** bản dịch được ghi ra file và chấm tay theo cùng rubric đã dùng ở Phase 0
**And** điểm được so trực tiếp với trần 77% của bản ollama 7.2GB
**And** nếu tụt dưới 70% thì AD-16 được xét lại (cân nhắc E4B) hoặc ngưỡng NFR-004 được xét lại — quyết định ghi vào memlog

### Story 1.4: Đo RAM và nhiệt trong 30 phút chạy liên tục

**Thoả:** _Không thoả FR nào — trả lời NFR-007_

As a nhà phát triển,
I want biết M52 có bị OOM-kill hay throttle khi dịch liên tục không,
So that NFR-007 được trả lời bằng phép đo thay vì suy đoán.

**Acceptance Criteria:**

**Given** app chạy trên M52 với model đã nạp
**When** dịch lặp lại liên tục trong 30 phút
**Then** app **không bị OOM-kill lần nào**
**And** RAM thực sự được cấp cho process được ghi lại — 8GB RAM danh nghĩa không có nghĩa app được dùng hết
**And** tok/s được đo ở phút thứ 1 và phút thứ 30 để phát hiện throttle do nhiệt

### Story 1.5: Chốt cổng — đi tiếp hay đổi hướng

**Thoả:** _Không thoả FR nào — chốt cổng AD-14_

As a nhà phát triển,
I want một kết luận được ghi lại dựa trên bốn story trên,
So that Epic 2 khởi động trên nền số liệu thật chứ không phải giả định.

**Acceptance Criteria:**

**Given** đã có tok/s GPU, tok/s CPU, điểm chất lượng int4, và số liệu RAM/nhiệt
**When** đối chiếu với NFR-004, NFR-005b và NFR-007
**Then** một trong hai kết luận được ghi vào `spike/FINDINGS.md` và memlog: **đi tiếp Epic 2**, hoặc **đổi hướng** kèm chỉ rõ AD nào phải sửa
**And** PRD §8 được cập nhật, thay mọi chữ "ngoại suy" bằng số đo thật
**And** nếu đổi hướng thì Epic 2 **không** được bắt đầu cho tới khi AD liên quan đã sửa xong

---

## Epic 2: Dịch được một trang ảnh

Người dùng chọn một ảnh trang manga có sẵn trên máy và nhận lại ảnh đã thay chữ Nhật bằng chữ Việt ngay trong bóng thoại. Chưa overlay, chưa chụp màn hình.

### Story 2.1: Khung pipeline và `PageJob` bất biến

**Thoả:** _Không thoả FR nào — khung cho AD-1, AD-18_

As a nhà phát triển,
I want khung pipeline 5 filter với `PageJob` bất biến và các port đã định nghĩa,
So that mọi story sau cắm vào một chỗ đã biết trước thay vì mỗi người tự nghĩ ra một kiểu.

**Acceptance Criteria:**

**Given** cấu trúc package theo AD paradigm (`domain`, `pipeline`, `ports`, `adapters`, `ui`)
**When** dựng khung với adapter giả (trả dữ liệu cứng)
**Then** `PageJob` là `data class` toàn `val`, mang `jobId`, `frameHash`, `contentKey` (AD-18)
**And** mọi filter có chữ ký `suspend fun apply(job: PageJob): PageJob` và **chỉ dùng `copy()`**, không sửa đối tượng nhận vào (AD-1)
**And** kết quả từng bubble sống **bên trong** `PageJob` dưới dạng `BubbleState` ba nhánh (`Accepted`/`Suspect`/`Rejected`), **không** bọc `PageJob` trong `Result`
**And** có kiểm tra tự động chặn `domain` import bất cứ thứ gì ngoài Kotlin stdlib và coroutines

### Story 2.2: Phát hiện bubble và sắp thứ tự đọc

**Thoả:** `FR-020`, `FR-021`, `FR-024`

As a người đọc manga,
I want app tìm đúng các bóng thoại trên trang,
So that bản dịch về sau nằm đúng chỗ.

**Acceptance Criteria:**

**Given** `detector-v4-s_int8.onnx` (11.1MB) chạy qua ONNX Runtime
**When** đưa vào một trang `tubaki_*.jpg` 974×1400
**Then** trả về bounding box với ba nhãn `bubble` / `text_bubble` / `text_free`
**And** box được sắp theo **thứ tự đọc manga: phải→trái, trên→dưới**, và thứ tự này gán **một lần duy nhất** ở `DetectFilter` — tầng sau không được sắp lại
**And** toạ độ là **pixel của ảnh gốc**, `[x1,y1,x2,y2]`, gốc góc trên-trái
**And** thời gian chạy được ghi log để đối chiếu với 0.166s/trang đo trên PC

### Story 2.3: Cổng lọc vùng không có thoại

**Thoả:** `FR-023`

As a người đọc manga,
I want app bỏ qua những vùng không phải bóng thoại,
So that tôi không bao giờ đọc phải thoại do máy bịa ra.

**Acceptance Criteria:**

**Given** manga-ocr **không có** đầu ra "chỗ này không có chữ" và sẽ bịa ra câu tiếng Nhật hợp lý nếu đưa cho nó mảnh tranh (FINDINGS F2)
**When** `GateFilter` chạy trên kết quả của Story 2.2
**Then** chỉ vùng `text_bubble` có **≥ 0.9 diện tích nằm trong** một box `bubble` mới được chuyển sang OCR (AD-5)
**And** vùng không thoả bị đánh `Suspect` và **không bao giờ được vẽ đè** — để nguyên chữ gốc
**And** ngưỡng 0.9 nằm trong object cấu hình chung, không phải hằng số rải rác
**And** chạy trên 20 trang test, số vùng bị loại và số vùng giữ lại được ghi lại để đối chiếu với tỷ lệ 53/54 đo ở Phase 0

### Story 2.4: Đọc chữ tiếng Nhật, cả dọc lẫn ngang

**Thoả:** `FR-022`, `FR-024`

As a người đọc manga,
I want app đọc đúng chữ trong bóng thoại kể cả khi viết dọc,
So that bước dịch nhận được đúng nguyên bản.

**Acceptance Criteria:**

**Given** manga-ocr chạy qua ONNX Runtime trên các vùng đã qua cổng Story 2.3
**When** đưa vào một trang có cả chữ dọc (縦書き) và chữ ngang
**Then** trả về chuỗi tiếng Nhật cho từng vùng, mã hoá UTF-8 đúng
**And** vùng nào trả về chuỗi rỗng thì bị loại, không đẩy rác sang bước dịch
**And** thời gian mỗi lần gọi được ghi log để đối chiếu với 0.325s/bubble đo trên PC
**And** `OcrEngine` và `TextDetector` là hai port tách rời, thay được độc lập

### Story 2.5: Dịch cả trang trong một lần gọi

**Thoả:** `FR-030`, `FR-031`, `FR-037`, `FR-038`

As a người đọc manga,
I want toàn bộ thoại của một trang được dịch cùng lúc với đầy đủ ngữ cảnh,
So that xưng hô và mạch hội thoại không bị vỡ.

**Acceptance Criteria:**

**Given** `Translator` chạy trên LiteRT-LM, **chỉ** được import trong package `adapters.litertlm`
**When** `TranslateFilter` dịch một trang
**Then** **toàn bộ** bubble đã qua cổng đi trong **MỘT** lần gọi, kèm thứ tự đọc và glossary (AD-3)
**And** port **không có** hàm nào nhận một bubble đơn lẻ
**And** id gửi vào prompt là id gốc do `DetectFilter` cấp, **có thể không liên tục** sau khi lọc (ví dụ 0,1,3,6) — không đánh lại số
**And** không có đường nào gửi dữ liệu ra khỏi máy (NFR-002), kiểm bằng chế độ máy bay

### Story 2.6: Cổng toàn vẹn — bản dịch phải đúng bubble của nó

**Thoả:** `FR-035`, `FR-036`

As a người đọc manga,
I want chắc chắn bản dịch được gán đúng bóng thoại,
So that tôi không đọc một trang trôi chảy mà sai mạch hội thoại.

**Acceptance Criteria:**

**Given** đã quan sát thực tế: model trả đủ 12 bubble, id chạy đúng 0→11, `done_reason: stop`, nhưng bản dịch **lệch một ô** từ bubble thứ tư (FINDINGS F10)
**When** kết quả dịch về
**Then** mỗi bubble mang `jaEcho` = 2 ký tự đầu nguyên bản, và **thứ tự trường JSON là `id` → `jaEcho` → `vi`** (AD-6)
**And** đối chiếu qua **ba bước bắt buộc**: chuẩn hoá NFKC → bỏ dấu câu và khoảng trắng → chấp nhận nếu khoảng cách sửa ≤ 1
**And** so khớp chính xác từng ký tự **không được dùng** — nó cho báo động giả khi model đổi trợ từ (`何を` vs `何が`) hoặc bỏ dấu gạch đầu câu
**And** có unit test tiêm lỗi lệch một ô và xác nhận cổng bắt được 100%, đồng thời 0 báo động giả trên dữ liệu đúng

### Story 2.7: Dịch chảy về dần và gỡ được khi phát hiện lệch

**Thoả:** `FR-035`, `FR-044`

As a người đọc manga,
I want thấy bản dịch hiện dần thay vì chờ cả trang,
So that tôi không phải nhìn màn hình đứng im cả phút.

**Acceptance Criteria:**

**Given** `Translator.translate(...)` trả **`Flow<BubbleTranslation>`**, không trả trọn gói (AD-17)
**When** từng bubble chảy về
**Then** `jaEcho` được xác thực ngay tại chỗ, bubble `Accepted` được phát ra ngoài để vẽ
**And** gặp bubble không khớp thì **huỷ Flow ngay** và phát `PageEvent.Retracted(danh sách bubble đã vẽ)`
**And** bên nhận **bắt buộc** xử lý `Retracted`: gỡ đúng các bubble đó, khôi phục chữ Nhật gốc
**And** thử lại **đúng một lần, đúng một chỗ** — chỉ `TranslateFilter` có retry, không filter nào khác

### Story 2.8: Glossary — nhớ tên riêng và thành ngữ

**Thoả:** `FR-032`, `FR-033`

As a người đọc manga,
I want app nhớ tên nhân vật và cách dịch các thành ngữ của bộ truyện,
So that bản dịch nhất quán và không bịa tên.

**Acceptance Criteria:**

**Given** FINDINGS F9 đã chứng minh glossary kéo chất lượng từ 46% lên 60% trên cùng model
**When** glossary được đưa vào prompt
**Then** nó chứa **ba loại**: tên riêng, **thành ngữ/tiếng lóng theo bối cảnh**, và xưng hô đã chốt giữa từng cặp nhân vật
**And** mọi thay đổi đi qua `GlossaryStore`, không ai ghi thẳng xuống DB (AD-7)
**And** khoá là `(seriesKey, surfaceForm)` với `seriesKey` cố định bằng `"default"` ở MVP — **cấm** suy từ app đang chạy trước vì nó đòi quyền `UsageStats` (AD-19)
**And** người dùng xem và sửa được mọi mục

### Story 2.9: Glossary tự tích luỹ

**Thoả:** `FR-034`

As a người đọc manga,
I want app tự đề xuất tên riêng nó gặp nhiều lần,
So that tôi không phải gõ tay glossary từ số không.

**Acceptance Criteria:**

**Given** không có giải pháp NER tiếng Nhật nào chạy nhẹ được on-device (đã tra web)
**When** app đề xuất mục glossary mới
**Then** nguồn **chỉ** gồm hai thứ đã có sẵn, chi phí bằng 0: trường `speaker` mà LLM vốn đã trả về, và cụm katakana/kanji lặp lại qua nhiều trang (AD-8)
**And** **không** thêm model nào, không thêm file tải về
**And** mục tự đề xuất vào trạng thái `Proposed` và **không** được đưa vào prompt cho tới khi người dùng chuyển sang `Confirmed`
**And** mục người dùng nhập tay luôn thắng mục tự đề xuất

### Story 2.10: Vẽ chữ Việt vào đúng bóng thoại

**Thoả:** `FR-040`, `FR-041`, `FR-042`, `FR-043`

As a người đọc manga,
I want nhìn vào trang và thấy một trang truyện tiếng Việt, không phải trang tiếng Nhật có chú thích,
So that tôi đọc được tự nhiên.

**Acceptance Criteria:**

**Given** đã có bản dịch được chấp nhận cho một bubble
**When** `RenderFilter` vẽ
**Then** thứ tự **bắt buộc** là: có bản dịch → tô nền che chữ gốc → vẽ chữ. **Không bao giờ tô nền trước** (AD-9)
**And** màu nền lấy mẫu từ chính trong bubble đó, không còn nhìn thấy nét chữ gốc thò ra
**And** chữ Việt nằm **gọn bên trong đúng bóng thoại tương ứng**, không lệch sang bubble khác, không tràn ra ngoài viền
**And** cỡ chữ tự co cho vừa, xuống dòng theo ranh giới từ, không cắt giữa từ
**And** font được kiểm bằng cách **render thử chuỗi đủ dấu tiếng Việt** — không tin tên font

### Story 2.11: Bubble không dịch được thì giữ nguyên chữ gốc

**Thoả:** `FR-046`

As a người đọc manga,
I want những chỗ app không dịch được vẫn hiện chữ Nhật,
So that tôi còn tự đoán được, thay vì nhìn một ô trống.

**Acceptance Criteria:**

**Given** một bubble bị `Reject`, hoặc bị đánh `Suspect` ở cổng Story 2.3
**When** trang được vẽ ra
**Then** bubble đó **giữ nguyên chữ Nhật gốc**, không bị che bằng ô trống
**And** cả trang bị `Reject` (lệch id sau khi đã thử lại) thì **toàn bộ** trang trở về nguyên bản
**And** có cách cho người dùng biết chỗ nào chưa dịch được, nhưng không gây hoảng

### Story 2.12: Không dịch lại trang đã dịch

**Thoả:** `FR-060`, `FR-061`, `FR-062`

As a người đọc manga,
I want quay lại trang cũ thì thấy bản dịch ngay,
So that tôi không phải chờ lại từ đầu.

**Acceptance Criteria:**

**Given** `contentKey` tính trên ảnh đã hạ mẫu và chuẩn hoá, **chỉ trên vùng các bubble đã phát hiện** (AD-18)
**When** một trang đã dịch được mở lại
**Then** kết quả trả ra ngay, không gọi lại LLM
**And** mục cache lưu kèm danh sách bounding box và **đối chiếu lại trước khi dùng**; lệch thì coi như cache miss — va chạm khoá dẫn tới vẽ sai trang là lỗi không thể chấp nhận
**And** `contentKey` **không bao giờ** được dùng để phát hiện nội dung thay đổi, và `frameHash` **không bao giờ** được dùng làm khoá cache
**And** người dùng xem được dung lượng cache và xoá được; cache tự dọn bản cũ nhất khi đầy

---

## Epic 3: Dịch màn hình đang đọc

Sản phẩm thật. Một icon nổi là toàn bộ giao diện. Dựng trên pipeline đã chạy được của Epic 2, chỉ thay nguồn ảnh từ "chọn file" sang MediaProjection.

### Story 3.1: Icon nổi

**Thoả:** `FR-001`, `FR-002`

As a người đọc manga,
I want một icon nhỏ nổi trên mọi app và nép vào mép màn hình,
So that tôi gọi được app dịch mà không rời trang truyện đang đọc.

**Acceptance Criteria:**

**Given** quyền `SYSTEM_ALERT_WINDOW` đã được cấp
**When** bật app từ launcher
**Then** một icon nổi hiện lên đè trên mọi app khác
**And** kéo di chuyển được và tự nép vào mép màn hình
**And** không che mất nội dung đang đọc
**And** icon hiện trạng thái "đang chuẩn bị" và **không nhận chạm** cho tới khi engine nạp xong (AD-20)

### Story 3.2: Phiên chụp màn hình xin một lần, giữ sống

**Thoả:** `FR-010`, `FR-011`, `FR-012`, `FR-016`

As a người đọc manga,
I want cấp quyền chụp màn hình một lần rồi thôi,
So that mỗi lần chạm icon không bị hỏi lại.

**Acceptance Criteria:**

**Given** foreground service có `foregroundServiceType="mediaProjection"` khi hệ điều hành yêu cầu
**When** app xin quyền chụp lần đầu
**Then** phiên chụp được **giữ sống** suốt thời gian app bật — đây là điều kiện để "một chạm" của Story 3.4 thật sự là một chạm
**And** service là **chủ sở hữu duy nhất** của `MediaProjection`, và chỉ có **một `VirtualDisplay`** (AD-21)
**And** `MediaProjection.Callback.onStop()` **bắt buộc** được đăng ký — thiếu nó thì `createVirtualDisplay()` ném `IllegalStateException`
**And** thông báo thường trực hiện rõ app đang có khả năng chụp màn hình

**Given** khoá màn hình làm **dừng phiên chiếu**
**When** người dùng khoá máy rồi mở lại
**Then** icon hiện trạng thái cần cấp lại quyền thay vì im lặng hỏng — đây là hành vi bình thường, không phải lỗi

### Story 3.3: Ảnh chụp phải sạch

**Thoả:** `FR-015`

As a người đọc manga,
I want app chụp đúng trang truyện chứ không chụp cả giao diện của chính nó,
So that nó không đọc lại chữ Việt mình vừa vẽ rồi dịch tiếp.

**Acceptance Criteria:**

**Given** `ScreenSource.capture()` chịu trách nhiệm này, **không** giao cho người gọi — người gọi sẽ quên (AD-11)
**When** một lượt chụp bắt đầu
**Then** icon nổi và **mọi lớp phủ bản dịch đang hiện** được tự ẩn, chờ một frame, chụp, rồi hiện lại
**And** vùng **status bar được cắt bỏ** khỏi ảnh trước khi detect — từ Android 15 QPR1 hệ điều hành vẽ chip "đang chia sẻ màn hình" mà app **không ẩn được**
**And** kiểm chứng bằng cách dịch một trang hai lần liên tiếp: lần hai phải cho kết quả giống lần đầu, không phải dịch chồng chất

### Story 3.4: Một chạm là dịch cả trang

**Thoả:** `FR-003`, `FR-004`, `FR-044`

As a người đọc manga,
I want chạm một cái vào icon và thấy trang được dịch,
So that việc dịch không cắt ngang mạch đọc.

**Acceptance Criteria:**

**Given** engine đã nóng (Story 3.1) và phiên chụp đang sống (Story 3.2)
**When** tôi chạm một cái vào icon
**Then** trọn luồng chạy: chụp → phát hiện → OCR → dịch → vẽ đè. **Không hỏi lại, không bước trung gian nào**
**And** icon hiện trạng thái tiến trình: đang chụp / đang đọc chữ / đang dịch
**And** bản dịch hiện **dần từng bubble ngay khi có**, không chờ đủ cả màn
**And** **bubble đầu tiên xuất hiện trong ≤ 8 giây** (NFR-005) — đo trên M52, không phải máy ảo (AD-22)
**And** toàn bộ pipeline chạy ngoài main thread, 0 ANR (NFR-009)

### Story 3.5: Giữ icon để đóng, đóng thì chữ gốc hiện lại

**Thoả:** `FR-005`, `FR-006`, `FR-009b`

As a người đọc manga,
I want đóng app nhanh và trang truyện trở lại nguyên bản,
So that tôi đọc tiếp bình thường như chưa có gì xảy ra.

**Acceptance Criteria:**

**Given** icon nổi đang hiện
**When** tôi **giữ icon**
**Then** **hai icon con** hiện ra ngay cạnh nó: **hình quyển sách** (hướng dẫn) và **hình X** (đóng)
**And** hai icon con **đủ xa nhau để không bấm nhầm**, và **X không phải là đích dễ chạm nhất** — chạm nhầm X làm mất phiên chụp và phải xin lại quyền (FR-009b)
**And** chạm X sẽ gỡ mọi lớp phủ, dừng foreground service, thu hồi phiên chụp, giải phóng engine
**And** trang truyện trở lại **nguyên bản tiếng Nhật y như cũ** — vì bản dịch chỉ là một lớp vẽ đè, app chưa bao giờ sửa nội dung app bên dưới (AD-10)
**And** không còn dấu vết nào của app trên màn hình

### Story 3.6: Liếc nguyên bản mà không mất bản dịch

**Thoả:** `FR-007`

As a người học tiếng Nhật,
I want xem lại chữ gốc ở một bubble bất kỳ,
So that tôi đối chiếu được với bản dịch.

**Acceptance Criteria:**

**Given** một trang đã được dịch và vẽ đè
**When** tôi **chạm giữ vào vùng bản dịch** (không phải vào icon)
**Then** lớp phủ tạm ẩn và chữ Nhật gốc hiện ra trong lúc tôi giữ
**And** thả ra thì bản dịch hiện lại
**And** cử chỉ này **phân biệt rõ** với thao tác giữ icon của Story 3.5 — không được vô tình đóng app

### Story 3.7: Lớp phủ không cản trở, và tự biến mất khi sang trang

**Thoả:** `FR-045`, `FR-047`

As a người đọc manga,
I want vuốt sang trang bình thường và không bao giờ thấy bản dịch của trang trước,
So that tôi không đọc nhầm nội dung.

**Acceptance Criteria:**

**Given** lớp phủ mang `frameHash` của ảnh sinh ra nó (AD-18)
**When** nội dung bên dưới đổi — vuốt sang trang, cuộn
**Then** lớp phủ của trang cũ **tự biến mất ngay**, không chờ lượt dịch mới
**And** thà mất bản dịch còn hơn hiện bản dịch sai chỗ — bản dịch trang trước đè lên trang sau là lỗi nặng nhất của tầng hiển thị
**And** ngoài vùng bubble, lớp phủ **không chặn** thao tác của app bên dưới: vuốt, cuộn, chạm đều bình thường

### Story 3.8: Báo rõ khi không chụp được

**Thoả:** `FR-013`, `FR-014`

As a người đọc manga,
I want biết vì sao app không dịch được thay vì thấy nó im lặng hỏng,
So that tôi không ngồi đoán.

**Acceptance Criteria:**

**Given** app đang đọc đặt `FLAG_SECURE` — đây là **giới hạn nền tảng, không có cách vòng**
**When** tôi chạm icon
**Then** app **báo rõ lý do không chụp được**, không im lặng thất bại, không hiện ảnh đen
**And** khi hệ điều hành thu hồi quyền chụp, app xin lại một cách rõ ràng thay vì crash
**And** mọi thông báo lỗi nói bằng ngôn ngữ người dùng hiểu, không phải mã lỗi kỹ thuật

### Story 3.9: Kiểm đường code Android 14/15/16 trên máy ảo

**Thoả:** _Không thoả FR nào — lấp khoảng trống test của AD-22_

As a nhà phát triển,
I want chạy thử các đường code mà Galaxy M52 không bao giờ chạm tới,
So that tôi không tuyên bố "đã hỗ trợ" một phiên bản chưa từng chạy.

**Acceptance Criteria:**

**Given** M52 chạy tối đa Android 13, còn AVD `Modern_A16` chạy Android 16 (AD-22)
**When** chạy app trên `Modern_A16`
**Then** đường `foregroundServiceType="mediaProjection"` của Android 14+ được chạy qua thật
**And** đường xin lại quyền mỗi phiên của Android 15+ được chạy qua thật
**And** việc cắt status bar để loại chip "đang chia sẻ màn hình" được kiểm bằng mắt trên ảnh chụp
**And** **cấm** ghi bất kỳ con số hiệu năng nào lấy từ máy ảo vào tài liệu

### Story 3.10: Hướng dẫn sử dụng ngay trong app

**Thoả:** `FR-008`, `FR-009`

As a người mới dùng app,
I want mở được hướng dẫn mà không rời khỏi trang truyện đang đọc,
So that tôi biết các cử chỉ mà không phải đoán mò hay đi tìm tài liệu ở đâu khác.

**Acceptance Criteria:**

**Given** tôi đang giữ icon và thấy hai icon con
**When** tôi chạm vào **icon hình quyển sách**
**Then** hướng dẫn sử dụng hiện ra, giải thích: một chạm để dịch · giữ icon để mở icon con · chạm giữ vùng dịch để liếc nguyên bản
**And** giải thích **ý nghĩa từng trạng thái trên icon** (đang chuẩn bị / đang chụp / đang đọc chữ / đang dịch)
**And** giải thích **phải làm gì khi không dịch được** — app đặt `FLAG_SECURE`, mất quyền chụp sau khi khoá màn hình, bubble giữ nguyên chữ Nhật

**Given** app phải chạy hoàn toàn offline (D1, NFR-001)
**When** hướng dẫn được mở
**Then** toàn bộ nội dung **nằm trong APK**, không mở trình duyệt, không tải gì từ mạng
**And** kiểm chứng bằng cách bật chế độ máy bay rồi mở hướng dẫn — phải hiện đầy đủ

**Given** tôi đang đọc dở một chương
**When** tôi đóng hướng dẫn
**Then** quay lại **đúng trạng thái trước đó**, kể cả lớp phủ bản dịch đang hiện
**And** **phiên chụp màn hình không bị mất** — mở hướng dẫn không được kéo theo việc phải xin lại quyền

---

## Epic 4: Máy sẵn sàng dịch — tải gói mô hình

Người khác cài được app. Xếp cuối cùng là cố ý: PRD §1 chốt dùng riêng trước, nên onboarding đẹp là thứ ít giá trị nhất hiện giờ.

### Story 4.1: Giải thích từng quyền trước khi xin

**Thoả:** `FR-054`

As a người mới cài app,
I want hiểu app xin quyền để làm gì trước khi bấm đồng ý,
So that tôi không thấy một app lạ đòi quyền chụp màn hình và gỡ luôn.

**Acceptance Criteria:**

**Given** app cần hai quyền: hiện overlay và chụp màn hình
**When** mở app lần đầu
**Then** một màn hình giải thích **từng quyền dùng để làm gì**, bằng ngôn ngữ thường
**And** **không** đẩy thẳng dialog hệ thống ra trước khi giải thích
**And** nói rõ dữ liệu **không rời khỏi máy** — đây là điểm bán hàng chính, không phải chú thích nhỏ

### Story 4.2: Tải gói mô hình, tạm dừng và tiếp tục được

**Thoả:** `FR-050`, `FR-051`

As a người mới cài app,
I want tải ~3.1GB mà không sợ mất công khi rớt mạng,
So that tôi không bỏ cuộc giữa chừng.

**Acceptance Criteria:**

**Given** APK **không chứa** trọng số model (NFR-008: APK ≤ 100MB)
**When** bắt đầu tải
**Then** app nói rõ **dung lượng và khuyến nghị dùng Wi-Fi** trước khi tải
**And** tiến trình chạy nền, tạm dừng và tiếp tục được
**And** rớt mạng thì **tải tiếp chỗ dở**, không làm lại từ đầu

### Story 4.3: Kiểm tra gói trước khi dùng

**Thoả:** `FR-052`

As a người mới cài app,
I want app tự phát hiện file tải hỏng,
So that tôi không gặp lỗi khó hiểu lúc đang đọc truyện.

**Acceptance Criteria:**

**Given** mỗi gói có **manifest** khai báo phiên bản, checksum từng file, và khoảng phiên bản app tương thích (AD-15)
**When** tải xong
**Then** checksum từng file được kiểm; file hỏng thì tải lại **phần hỏng**, không tải lại cả gói
**And** app **từ chối nạp** model có manifest ngoài khoảng phiên bản tương thích
**And** thông báo từ chối nói rõ phải làm gì tiếp

### Story 4.4: Cảnh báo trước khi tải nếu máy yếu

**Thoả:** `FR-053`

As a người dùng máy tầm trung,
I want biết máy mình có chạy nổi không trước khi tải 3.1GB,
So that tôi không tốn băng thông vô ích.

**Acceptance Criteria:**

**Given** ngưỡng RAM được chốt từ số đo thật của Epic 1 Story 1.4
**When** người dùng chuẩn bị tải
**Then** app đo RAM máy và **cảnh báo rõ trước khi tải** nếu dưới ngưỡng
**And** người dùng tự chọn tiếp tục hay dừng — app không tự quyết thay
**And** cảnh báo nói con số cụ thể, không nói chung chung

### Story 4.5: Xoá gói mô hình

**Thoả:** `FR-055`

As a người dùng sắp hết dung lượng,
I want gỡ gói mô hình mà không phải gỡ cả app,
So that tôi lấy lại được 3.1GB khi cần.

**Acceptance Criteria:**

**Given** gói mô hình đã cài
**When** tôi chọn xoá
**Then** app nói rõ sẽ giải phóng bao nhiêu và hậu quả là gì
**And** xoá xong thì app quay về trạng thái chưa có model, mời tải lại
**And** glossary và cấu hình **không** bị xoá theo
