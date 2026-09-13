---
title: "PRD — Manga Translator JA→VI Offline (Android)"
status: draft
created: 2026-09-13
updated: 2026-09-13
owner: TrongND
---

# PRD — Manga Translator JA→VI Offline

> Đầu vào: `docs/brief.md` + kết quả Phase 0 (`spike/FINDINGS.md`). Dự án **cá nhân**, một người làm.
>
> **Cảnh báo đọc trước — phạm vi hiệu lực của các con số:**
> Phase 0 đã chạy và **lật ba giả định của brief** (xem §8.2). Nhưng mọi số đo đều từ **PC (Ryzen Z1, CPU)**, trên **một bộ truyện**, thuộc loại **khó nhất**. Chưa có số nào đo trên Galaxy M52. Chỗ nào ghi "ngoại suy" thì đó là suy luận, không phải phép đo.
> Hai ngưỡng đã **bị hạ** so với brief: chất lượng 80%→70% (§8.4) và tốc độ 40s→90s (§8.3). Cả hai đều ghi rõ lý do — đừng để lần đọc sau tưởng đó là con số có cơ sở từ đầu.

---

## 1. Tóm tắt

App Android đọc màn hình đang hiển thị manga tiếng Nhật, dịch sang tiếng Việt và vẽ đè bản dịch lên đúng khung thoại. **Chạy hoàn toàn offline** sau khi tải gói mô hình một lần. Không server, không API trả phí.

**Thiết bị đích số một:** Samsung Galaxy M52 5G của tác giả. Code viết để chạy trên nhiều đời Android, nhưng mọi tiêu chí đạt/không đạt của MVP đo trên M52.

**Mục tiêu phát hành:** dùng riêng trước. Public lên store chỉ tính đến sau khi bản thân tác giả dùng ổn định — nên MVP **không** làm gì chỉ để thoả mãn chính sách store.

---

## 2. Vấn đề

Manga được dịch sang tiếng Việt rất ít so với lượng phát hành ở Nhật, và thường chậm hàng tháng. Công cụ dịch phổ thông thất bại với manga vì hai lý do kỹ thuật:

- **OCR sai từ gốc.** ML Kit OCR và Google Lens huấn luyện cho chữ in ngang. Manga dùng chữ dọc (縦書き), font viết tay, có furigana, chữ tràn ngoài bubble.
- **Dịch không ngữ cảnh.** Tiếng Nhật lược chủ ngữ liên tục; tiếng Việt bắt buộc chọn đại từ xưng hô. Dịch từng câu rời rạc thì không có cơ sở nào để chọn *anh/em/tôi/tao/mày/ta* — kết quả đọc như robot.

Các giải pháp chất lượng tốt hiện có (`manga-image-translator`, `BallonsTranslator`) chạy trên desktop/server, không dùng được khi đang đọc trên điện thoại.

---

## 3. Người dùng & bối cảnh

**Người dùng chính:** chính tác giả — đọc manga raw tiếng Nhật trên điện thoại, không biết tiếng Nhật, không muốn chờ nhóm dịch, không muốn tốn tiền và không muốn phụ thuộc mạng.

**Người dùng phụ (nếu public sau này):** người đọc manga raw khác, và người học tiếng Nhật muốn đối chiếu nguyên bản với bản dịch.

### UJ-1 — Đọc một chương raw

Trọng mở Manga Translator một lần; một **icon nhỏ nổi lên** rồi nép vào mép màn hình. Anh chuyển sang app đọc truyện, mở chương mới nhất chưa ai dịch. Tới trang cần đọc, anh **chạm một cái vào icon**. Khoảng một giây sau, chữ Nhật trong các bóng thoại **biến mất và được thay bằng chữ Việt ngay tại chỗ** — hiện dần từng bubble, bubble đầu tiên xuất hiện trước khi anh kịp sốt ruột. Nhìn vào màn hình lúc này giống hệt một trang truyện đã được nhóm dịch làm sẵn, không phải một trang có chú thích dán đè.

Anh đọc xong, vuốt sang trang tiếp, chạm icon lần nữa. Có chỗ muốn đối chiếu nguyên bản, anh chạm giữ vào vùng bản dịch, chữ Nhật hiện lại trong lúc anh giữ.

Đọc hết chương, anh **giữ icon** — một **icon con hình X** hiện ra bên cạnh. Anh chạm X. App tắt, lớp phủ biến mất, và trang truyện trở lại **nguyên bản tiếng Nhật y như chưa có gì xảy ra**.

### UJ-2 — Lần chạy đầu tiên

Cài xong app, mở lên. App báo: cần tải gói mô hình khoảng 3GB, nên dùng Wi-Fi. Trọng bấm tải, rồi đi làm việc khác — thanh tiến trình chạy nền, tạm dừng và tiếp tục được, rớt mạng thì tải tiếp chỗ dở chứ không làm lại từ đầu. Xong, app xin hai quyền và **nói rõ từng quyền dùng để làm gì**: quyền hiện overlay, và quyền chụp màn hình. Anh cấp, thử ngay một trang, thấy chạy được.

---

## 4. Phạm vi MVP

### Trong phạm vi

- Bong bóng nổi bật/tắt, kéo di chuyển được.
- Chụp màn hình qua MediaProjection kèm foreground service.
- Phát hiện vùng chữ / khung thoại.
- OCR tiếng Nhật, **hỗ trợ cả chữ dọc và chữ ngang**.
- Dịch JA→VI bằng LLM chạy trên máy, **nhận cả màn hình làm ngữ cảnh trong một lần gọi**.
- Xoá nền chữ gốc bằng cách fill màu nền bubble.
- Vẽ chữ Việt vào đúng vị trí bubble, tự co cỡ chữ và xuống dòng.
- Màn hình tải gói mô hình lần đầu, tạm dừng / tiếp tục / chống rớt mạng.
- Cache kết quả theo hash ảnh, không dịch lại màn đã dịch.
- Tạm ẩn bản dịch để xem nguyên bản.
- **Glossary theo bộ truyện, tự tích luỹ.** Đã nâng từ Phase 3 lên MVP sau khi đo (§8.2).

### Thành phần đã chọn sau Phase 0

| Bước | Chọn | Dung lượng | Căn cứ |
|---|---|---|---|
| Detector | `ogkalu/comic-text-and-bubble-detector` bản `detector-v4-s_int8` | **11.1MB** | Apache-2.0, 0.166s/trang, 3 lớp `bubble`/`text_bubble`/`text_free` |
| OCR | `manga-ocr` | ~450MB fp32, chưa convert int8 | 0.325s/bubble, đọc đúng chữ dọc |
| **Dịch** | **Gemma 4 E2B** | **2.59 GB** (CPU) hoặc **2.01 GB** (GPU) | Thắng Gemma 3 4B trên **mọi trục**: chất lượng ~77% vs ~47%, nhanh hơn, nhỏ hơn (§8.8) |

⚠️ Gemma có **điều khoản sử dụng riêng của Google**, không phải Apache-2.0. Phải đọc kỹ trước khi phát hành (§8.7).

⚠️ Tổng gói tải về ≈ **2.5–3.1 GB** tuỳ biến thể LLM (2.59 GB CPU hoặc 2.01 GB GPU, cộng OCR ~450 MB và detector 11 MB). Giảm so với ước tính 3.8 GB ban đầu.

> **Quy ước đơn vị:** mọi dung lượng trong tài liệu này tính bằng **GB thập phân** (1 GB = 10⁹ byte) — đúng như con số người dùng thấy khi tải. Windows hiển thị GiB (2³⁰ byte) nên sẽ thấy số nhỏ hơn ~7%.

### Ngoài phạm vi MVP

| Không làm | Lý do |
|---|---|
| Chế độ cloud / API trả phí | Trái D2 và D3. Vẫn giữ interface `Translator` tách rời để không tự khoá |
| Nhập zip/cbz, trình đọc truyện riêng | Phase 5 |
| Chụp bằng camera dịch truyện giấy | Phase 5 |
| Inpainting xoá nền bằng AI | MVP chỉ fill màu nền; inpainting là Phase 4+ |
| Ngôn ngữ khác ngoài JA→VI | Không có nhu cầu |
| iOS | Không có thiết bị, không có tài khoản developer |
| Tài khoản, đồng bộ đám mây, telemetry | Trái D1/D2, và không có ai để đồng bộ |
| Tối ưu riêng cho chính sách Play Store | Chỉ làm khi thật sự quyết định public |

---

## 5. Yêu cầu chức năng

ID toàn cục, ổn định. Không đánh lại số khi chèn thêm.

### 5.1 Icon nổi & điều khiển

> Toàn bộ tương tác của MVP gói gọn trong **một icon nổi**: một chạm để dịch, giữ để mở hai icon con (hướng dẫn và đóng). Không có menu nhiều tầng, không có màn hình cấu hình nào nằm trên đường đọc truyện.

| ID | Yêu cầu |
|---|---|
| FR-001 | Bật app từ launcher sẽ hiện một **icon nổi (shortcut icon)** nằm đè lên mọi app khác. Đây là toàn bộ giao diện điều khiển khi đang đọc |
| FR-002 | Icon nổi kéo di chuyển được và tự nép vào mép màn hình, không che mất nội dung đang đọc |
| FR-003 | **Một chạm vào icon** chạy trọn luồng dịch cho trang đang hiển thị: chụp → phát hiện bubble → OCR → dịch → vẽ đè. Không hỏi lại, không bước trung gian nào |
| FR-004 | Trong lúc xử lý, chính icon hiện trạng thái tiến trình (đang chụp / đang đọc chữ / đang dịch) |
| FR-005 | **Giữ icon** hiện ra hai **icon con** ngay cạnh nó: **hình quyển sách** (hướng dẫn sử dụng) và **hình X** (đóng app). Chạm X sẽ gỡ mọi lớp phủ, dừng foreground service, thu hồi phiên chụp màn hình |
| FR-006 | Khi app đã đóng, **trang truyện trở lại nguyên bản tiếng Nhật y như cũ**. App không bao giờ sửa nội dung của app bên dưới — bản dịch chỉ là một lớp vẽ đè, gỡ lớp đó là chữ gốc hiện lại nguyên vẹn |
| FR-007 | Chạm giữ **vào vùng bản dịch** (không phải vào icon) để tạm ẩn lớp phủ và liếc nguyên bản tiếng Nhật; thả ra thì bản dịch hiện lại. Cử chỉ này phải phân biệt rõ với FR-005 để không vô tình đóng app |
| FR-008 | Chạm vào **icon con hình quyển sách** mở **hướng dẫn sử dụng**: giải thích các cử chỉ (một chạm để dịch, giữ để mở icon con, chạm giữ vùng dịch để liếc nguyên bản), ý nghĩa các trạng thái trên icon, và cách xử lý khi không dịch được |
| FR-009 | Hướng dẫn sử dụng **nằm trong APK và hoạt động hoàn toàn offline**. Không mở trình duyệt, không tải nội dung từ mạng — trái D1 và NFR-001. Đóng hướng dẫn thì quay lại đúng trạng thái trước đó, phiên chụp màn hình **không bị mất** |
| FR-009b | Hai icon con phải **đủ xa nhau để không bấm nhầm**, và **X không được là đích dễ chạm nhất** — chạm nhầm X làm mất phiên chụp và phải xin lại quyền |

### 5.2 Chụp màn hình

| ID | Yêu cầu |
|---|---|
| FR-010 | App chụp màn hình hiện tại qua MediaProjection |
| FR-011 | Việc chụp chạy trong foreground service có `foregroundServiceType="mediaProjection"` khi hệ điều hành yêu cầu |
| FR-012 | Hiện thông báo thường trực cho biết app đang có khả năng chụp màn hình |
| FR-013 | Khi gặp app đặt `FLAG_SECURE`, app **báo rõ lý do không chụp được**, không im lặng thất bại hay hiện ảnh đen |
| FR-014 | Nếu hệ điều hành thu hồi quyền chụp (Android 15+ yêu cầu xác nhận lại mỗi phiên), app xin lại quyền một cách rõ ràng thay vì crash |
| FR-015 | **Trước mỗi lần chụp, app tự ẩn icon nổi và mọi lớp phủ bản dịch đang hiện.** Nếu không, ảnh chụp sẽ chứa chính icon và bản dịch cũ, khiến OCR đọc lại chữ Việt mình vừa vẽ |
| FR-016 | Phiên chụp màn hình **xin quyền một lần rồi giữ sống** suốt thời gian app bật, để mỗi lần chạm icon không bị bật lại dialog hệ thống. Đây là điều kiện để FR-003 thật sự là "một chạm" |

### 5.3 Phát hiện chữ & OCR

| ID | Yêu cầu |
|---|---|
| FR-020 | Phát hiện các vùng chữ / khung thoại trên ảnh chụp, trả về bounding box |
| FR-021 | Sắp bounding box theo **thứ tự đọc manga: phải→trái, trên→dưới** |
| FR-022 | OCR đọc được chữ **dọc** (縦書き) và chữ **ngang** trong cùng một trang |
| FR-023 | **Phải có cơ chế loại vùng không chứa thoại TRƯỚC khi đưa sang bước dịch.** manga-ocr là mô hình image→text, không có đầu ra "chỗ này không có chữ" và không trả điểm tin cậy — đưa cho nó một mảnh tranh, nó **vẫn bịa ra câu tiếng Nhật trông hợp lý** (đã quan sát thực tế, xem `spike/FINDINGS.md` F2). Thoại bịa sẽ được dịch trôi chảy rồi vẽ đè vào bubble, và người đọc không có cách nào phát hiện. Cơ chế lọc phải nằm ngoài manga-ocr |
| FR-024 | Bước phát hiện và bước OCR là hai thành phần thay thế được (interface tách rời) |

### 5.4 Dịch

| ID | Yêu cầu |
|---|---|
| FR-030 | **Toàn bộ bubble của một màn hình được gửi cho LLM trong MỘT lần gọi duy nhất**, kèm thứ tự đọc và glossary. Đây là ràng buộc kiến trúc, không phải tối ưu hoá tuỳ chọn |
| FR-031 | Prompt hướng dẫn LLM suy ra người nói / người nghe từ ngữ cảnh cả trang rồi mới chọn xưng hô tiếng Việt |
| FR-032 | App duy trì **glossary theo từng bộ truyện**, gồm **ba loại**: (a) tên riêng — nhân vật, địa danh; (b) **thành ngữ / tiếng lóng theo bối cảnh truyện**; (c) xưng hô đã chốt giữa từng cặp nhân vật. Glossary được đưa vào prompt mỗi lần gọi. **Đây là thành phần trung tâm, không phải tinh chỉnh** — xem `spike/FINDINGS.md` F8/F9: cùng model cùng prompt, chỉ mở rộng glossary đã kéo điểm từ 46% lên 60%, trong khi tăng gấp đôi cỡ model không cải thiện gì |
| FR-033 | Người dùng xem và sửa được mọi mục glossary |
| FR-034 | Glossary **tự tích luỹ**: tên riêng và thuật ngữ gặp lại nhiều lần được đề xuất thêm vào, người dùng xác nhận. Không bắt người dùng gõ tay từ số không |
| FR-035 | Nếu LLM trả về JSON hỏng hoặc thiếu bubble, app tự thử lại một lần; vẫn hỏng thì hiện nguyên bản tiếng Nhật cho bubble đó thay vì bỏ trống |
| FR-036 | **Kiểm tra toàn vẹn ánh xạ id ↔ nội dung trước khi vẽ.** JSON hợp lệ và đủ số phần tử **KHÔNG** đủ để tin. Đã quan sát thực tế (`spike/FINDINGS.md` F10): model trả về đủ 12 bubble, id chạy đúng 0→11, `done_reason: stop`, mọi kiểm tra tự động đều xanh — nhưng toàn bộ bản dịch **lệch đi một ô** từ bubble thứ 4 trở đi. Người đọc thấy mọi bubble đều trôi chảy mà cả trang sai mạch hội thoại |
| FR-037 | Dịch chạy **100% trên máy**. Không có đường nào gửi dữ liệu ra ngoài |
| FR-038 | Interface `Translator` tách rời để sau này thay impl mà không sửa phần còn lại |

### 5.5 Hiển thị bản dịch

| ID | Yêu cầu |
|---|---|
> Kết quả mong muốn: nhìn vào màn hình phải thấy **một trang manga tiếng Việt**, không phải một trang tiếng Nhật có chú thích. Chữ Nhật gốc bị che kín, chữ Việt nằm **bên trong đúng bóng thoại của nó**. Không dùng panel phụ, không dùng danh sách bản dịch bên cạnh, không dùng tooltip.

| ID | Yêu cầu |
|---|---|
| FR-040 | Che kín chữ Nhật gốc bằng cách fill màu nền lấy mẫu từ chính trong bubble đó, sao cho không còn nhìn thấy nét chữ gốc thò ra |
| FR-041 | Vẽ chữ Việt **nằm gọn bên trong đúng bóng thoại tương ứng** — đúng bubble, không lệch sang bubble khác, không tràn ra ngoài viền |
| FR-042 | Tự co cỡ chữ cho vừa bubble; xuống dòng theo ranh giới từ, không cắt giữa từ |
| FR-043 | Font dùng để vẽ phải có **đầy đủ dấu tiếng Việt** — kiểm tra bằng cách render thử chuỗi đủ dấu, không tin tên font |
| FR-044 | Bản dịch hiện **dần từng bubble ngay khi có**, không chờ đủ cả màn |
| FR-045 | Lớp phủ không chặn thao tác của app bên dưới ngoài vùng bubble — vuốt sang trang, cuộn vẫn bình thường |
| FR-046 | Bubble nào không dịch được thì **để nguyên chữ Nhật gốc**, không che bằng ô trống. Che mất chữ mà không thay được gì là tệ hơn không làm |
| FR-047 | Khi nội dung bên dưới đổi (vuốt sang trang, cuộn), **lớp phủ của trang cũ phải tự biến mất**. Bản dịch trang trước nằm đè lên trang sau là lỗi nặng — sai nội dung mà nhìn vẫn như đúng |

### 5.6 Gói mô hình & onboarding

| ID | Yêu cầu |
|---|---|
| FR-050 | Lần chạy đầu, app hướng dẫn tải gói mô hình, **nói rõ dung lượng và khuyến nghị dùng Wi-Fi** trước khi bắt đầu |
| FR-051 | Việc tải tạm dừng / tiếp tục được, và **tải tiếp chỗ dở** khi rớt mạng chứ không làm lại từ đầu |
| FR-052 | Kiểm tra checksum sau khi tải; hỏng thì tải lại phần hỏng |
| FR-053 | App đo RAM máy; dưới ngưỡng thì **cảnh báo rõ trước khi tải**, cho người dùng chọn tiếp tục hay dừng |
| FR-054 | Màn hình quyền giải thích **từng quyền dùng để làm gì** trước khi xin, không đẩy thẳng dialog hệ thống |
| FR-055 | Xoá gói mô hình để giải phóng dung lượng |

### 5.7 Cache

| ID | Yêu cầu |
|---|---|
| FR-060 | Kết quả dịch cache theo hash ảnh chụp; màn đã dịch thì trả ra ngay |
| FR-061 | Xem dung lượng cache và xoá được |
| FR-062 | Cache có giới hạn dung lượng, tự dọn bản cũ nhất khi đầy |

---

## 6. Yêu cầu phi chức năng

| ID | Yêu cầu | Ngưỡng | Ghi chú |
|---|---|---|---|
| NFR-001 | Hoạt động khi **tắt hoàn toàn mạng** | 100% luồng chính | Kiểm bằng chế độ máy bay |
| NFR-002 | Không gửi bất kỳ dữ liệu nào ra ngoài sau khi tải xong gói | 0 kết nối | Kiểm bằng công cụ theo dõi mạng |
| NFR-003 | Độ chính xác OCR trên bộ test | CER ≤ 10% | ✅ **ĐẠT — CER 0.3%** (int8 vs fp32, 73 vùng thật, sau chuẩn hoá NFKC). int8 nhỏ hơn 4× và nhanh gấp đôi mà không kém. ⚠️ Đo int8-vs-fp32, **chưa** đo so với chữ thật trên trang |
| NFR-004 | Chất lượng dịch "hiểu được mạch truyện", đo trên **manga phổ thông** | ≥ 70% bubble | ⏳ **CHƯA ĐO** — chưa có bộ manga phổ thông. Số 69% hiện có là đo trên truyện **khó nhất**, thuộc NFR-004b |
| NFR-004b | Chất lượng trên **truyện khó** | ≥ 50% bubble | ✅ **ĐẠT — 69% đo trên máy thật, bản int4** |
| NFR-005 | Bubble đầu tiên xuất hiện | ~~≤ 8s~~ → **≤ 95s** | ❌→⚠️ **NGƯỠNG CŨ BẤT KHẢ THI.** Đo thật: **91s**, trong đó **83s là prefill**. Không có token nào trước khi prefill xong — giới hạn của transformer, không sửa được bằng code. Mọi cách chia nhỏ đều đã đo và không cứu được (§8.13) |
| NFR-005b | Tổng thời gian một màn | ~~≤ 90s~~ → **≤ 190s** | ⚠️ **đo 179s trên M52** (pipeline đầy đủ, 2 luồng). Ngưỡng 90s đặt khi chưa có OCR và chưa chốt 2 luồng. Lần thứ hai nới — ghi rõ để không ai tưởng đây là con số có cơ sở từ đầu |
| NFR-007 | Không OOM-kill trong 30 phút đọc liên tục | 0 lần | ✅ **0 lần / 13 phút** đo thật. RSS **tự giảm** 3017→1998 MB nhờ mmap. Chưa đo đủ 30 phút, và chưa đo khi có app đọc truyện chạy song song |
| NFR-008 | Kích thước APK | ≤ 100MB | ✅ APK app đo = **57.6 MB** đã gồm native lib LiteRT-LM cho cả `arm64-v8a` và `x86_64` |
| NFR-009 | UI không bị chặn trong lúc xử lý | 0 ANR | Toàn bộ pipeline chạy ngoài main thread |
| NFR-010 | Chi phí vận hành | 0 đồng | Không server, không API trả phí, không CDN tự dựng |

---

## 7. Ràng buộc nền tảng & tương thích

### 7.1 Thiết bị đích

| | Galaxy M52 5G (thiết bị đo chuẩn) |
|---|---|
| Chip | Snapdragon 778G 5G |
| RAM | **8GB** — đã xác nhận với tác giả |
| Android | Tối đa **13** (One UI 5.1) |

Nguồn: GSMArena, Wikipedia — mục Galaxy M52 5G.

### 7.2 Ma trận Android — code phải xử lý cả ba

| Phiên bản | Điều phải xử lý |
|---|---|
| Android 10–13 | Baseline. **M52 nằm ở đây** — không cần `foregroundServiceType`, không cần xin lại quyền mỗi phiên |
| Android 14 | Bắt buộc foreground service type `mediaProjection` |
| Android 15+ | Người dùng phải **xác nhận lại quyền chụp mỗi phiên** — FR-014 |

Vì thiết bị đo chuẩn chạy Android 13, **đường code cho Android 14/15 sẽ không được kiểm chứng trên máy thật ở MVP**. Ghi nhận đây là khoảng trống test đã biết, không phải chỗ được phép tuyên bố "đã hỗ trợ".

### 7.3 Giới hạn cứng

- **Không chụp được màn hình app đặt `FLAG_SECURE`.** Đây là giới hạn nền tảng, không có cách vòng. App phải báo rõ cho người dùng (FR-013).
- Cần quyền `SYSTEM_ALERT_WINDOW` cho overlay.

---

## 8. Kết quả Phase 0 — cái gì đã đo, cái gì chưa

> Mục này thay thế danh sách giả định của bản nháp đầu. Chi tiết đầy đủ ở `spike/FINDINGS.md`.
> **Mọi số dưới đây đo trên PC (ROG Ally, Ryzen Z1 Extreme, CPU), KHÔNG phải trên M52.**

### 8.1 Trạng thái từng rủi ro

| # | Rủi ro | Trạng thái | Số đo |
|---|---|---|---|
| R1 | OCR đủ chính xác | ⏳ **chưa trả lời** | manga-ocr chạy được, 0.325s/bubble, đọc đúng chữ dọc. Nhưng **chưa có ground truth nên chưa đo được CER** |
| R2 | LLM 4B dịch đủ tốt | ⚠️ **trượt ngưỡng cũ, nhưng lý do sửa được** | 47% trên 48 bubble của bộ truyện khó nhất |
| R3 | Không OOM trên 8GB | ⏳ chưa đo | Cần máy thật |
| R4 | Đủ nhanh | ⚠️ **vượt ngưỡng cũ** | 30–44s/trang trên PC; ngoại suy 60–130s trên M52 |
| R5 | Chấp nhận tải 3GB | ✅ không còn là rủi ro | Tự dùng nên tự chịu |

### 8.2 Ba giả định của brief đã bị lật

**① "Model càng to dịch càng tốt."** Sai.

| Model | Kích thước | Thời gian | Chấm tay |
|---|---|---|---|
| `qwen3:4b` | 2.5GB | 33s | 33% |
| **`gemma3:4b`** | 3.3GB | 30–44s | **~58%** |
| `qwen3:8b` | 5.2GB | **144s** | ~46% |

Gấp rưỡi kích thước, chậm gấp 4, **không tốt hơn**. Ba model khác họ khác cỡ sai y hệt nhau ở đúng những từ không có trong glossary.

**② "Họ Qwen mạnh tiếng Nhật nên chọn Qwen."** Sai với nhiệm vụ này. `gemma3:4b` thắng cả chất lượng lẫn tốc độ, và giữ xưng hô cổ trang nhất quán hơn.
⇒ **Model mặc định đổi sang `gemma3:4b`.**

**③ "Detector tốn ~20MB, OCR là nút thắt."** Nửa đúng. Detector `detector-v4-s_int8.onnx` chỉ **11.1MB** (Apache-2.0), chạy 0.166s/trang. Nhưng cơ cấu thời gian thực tế là:

| Bước | Tỷ trọng |
|---|---|
| Detect | 0.4% |
| OCR | ~9% |
| **LLM dịch** | **~90%** |

⇒ Mọi nỗ lực tối ưu tốc độ phải dồn vào bước 3. Detector và OCR coi như miễn phí.

### 8.3 Vì sao nới ngưỡng tốc độ từ 40s lên 90s

Ngưỡng 40s được đặt trong brief **trước khi có bất kỳ phép đo nào**, và nó đặt sai chỗ: vì `FR-044` hiện bản dịch **dần từng bubble**, thứ người dùng thực sự cảm nhận là **thời gian tới bubble đầu tiên**, không phải tổng thời gian.

Người đọc manga đọc từ phải sang trái, mỗi bubble vài giây. Nếu bubble đầu hiện sau 8s và các bubble sau lấp đầy dần trong lúc họ đang đọc, thì tổng 90s **không bị cảm nhận là chờ**.

⇒ `NFR-005` (bubble đầu ≤ 8s) thăng lên làm **chỉ số tốc độ chính**. `NFR-005b` (tổng ≤ 90s) là chỉ số phụ.

⚠️ Điều này **chưa được kiểm chứng bằng người dùng thật**. Nếu khi dùng thật thấy vẫn khó chịu thì phải quay lại hạ cỡ model.

### 8.4 Vì sao hạ ngưỡng chất lượng từ 80% xuống 70%

Ba lý do, theo thứ tự quan trọng:

1. **80% đặt trước khi có phép đo nào.** Không có căn cứ thực nghiệm nào đỡ nó.
2. **Bộ test là trường hợp xấu nhất, không phải trung bình.** `tubaki` là truyện cổ trang bối cảnh khu lâu xanh thời Edo — khẩu ngữ cổ, tiếng lóng dày đặc (`シケ込む`, `ソデにする`, `筆下ろし`, `女郎`). Manga phổ thông dễ hơn đáng kể.
3. **Ngưỡng đã chốt là "hiểu được mạch truyện"** (D9), không phải chất lượng xuất bản.

⇒ Tách thành hai ngưỡng: `NFR-004` (70%, manga phổ thông) và `NFR-004b` (50%, truyện khó).

⚠️ **Đây là hạ tiêu chuẩn.** Ghi rõ ra để không ai sau này tưởng 70% là con số có cơ sở từ đầu.

### 8.5 Phân rã 53% điểm bị mất — phần lớn sửa được

| Loại lỗi | Sửa được? | Bằng cách nào |
|---|---|---|
| **Lệch id** — bản dịch gán sai bubble | ✅ | `FR-036` kiểm tra toàn vẹn. Riêng lỗi này làm mất trọn một trang trong bộ test |
| **Thiếu tên riêng trong glossary** | ✅ | `FR-034` glossary tự tích luỹ. Ví dụ `桔梗`→"hoa cúc" ❌, `コマ`→"con chim" ❌ |
| **Thành ngữ cổ ngoài glossary** | ⚠️ một phần | `FR-032` loại (b) |
| **Sai ngữ nghĩa thật sự** | ❌ | Giới hạn của model 4B. Đây mới là phần không vượt qua được |

**Hai nhóm đầu chiếm phần lớn điểm mất, và cả hai sửa được mà không đổi model, không tốn thêm thời gian chạy.**

### 8.6 Hai lỗi "sai mà nhìn như đúng" — nguy hiểm nhất của hệ thống

Cả hai đều **qua được mọi kiểm tra tự động** và **đọc rất trôi chảy**:

| # | Lỗi | Phát hiện ở |
|---|---|---|
| F2 | **Thoại bịa** — manga-ocr sinh chữ ở vùng không có text | Đưa mảnh tranh cho manga-ocr, nó vẫn trả về câu tiếng Nhật hợp lý |
| F10 | **Lệch id** — bản dịch gán sai bubble | JSON hợp lệ, đủ 12 phần tử, id đúng 0→11, nhưng lệch một ô từ bubble thứ 4 |

Đây là lý do `FR-023` và `FR-036` tồn tại, và là lý do counter-metric "thoại bịa = 0%" đặt ở mức tuyệt đối chứ không phải ≤5%.

### 8.8 ⭐ Gemma 4 E2B — giả định "model to hơn thì tốt hơn" bị bác lần thứ hai

**Phép đo:** 48 bubble, 4 trang, cùng glossary, cùng prompt. Chỉ đổi model.

| | Gemma 3 4B | **Gemma 4 E2B** |
|---|---|---|
| Chất lượng (chấm tay) | ~47% | **~77%** |
| Tốc độ | 16 tok/s | **22–23 tok/s** |
| Kích thước bản triển khai | 3.3GB | **2.58GB** |

**Nhỏ hơn, nhanh hơn, dịch tốt hơn.** Và ~77% **vượt ngưỡng NFR-004 (70%)** — trên chính bộ test khó nhất.

Nó tự nhận ra tên riêng mà glossary không có — nhóm lỗi lớn thứ hai ở §8.5:

| JA | Gemma 3 | Gemma 4 E2B |
|---|---|---|
| `桔梗の実家へ` | "Đến nhà trồng hoa giấy" ❌ | **"Đến nhà của gia đình Kikyō"** ✅ |
| `コマの死の傷` | "Sẹo chết của con rối" ❌ | **"Vết thương vì cái chết của Koma"** ✅ |
| `変じゃない` | "Không đến nỗi tệ" ❌ | **"Không có gì lạ"** ✅ |
| `畜生！！このお人好し！！` | — | **"Khốn kiếp!! Cái người tốt này!!"** ✅ |

Trang `tubaki_025` lần chạy này **không bị lệch id**.

⚠️ **Giới hạn quan trọng của phép đo:** chạy trên bản ollama **7.2GB độ chính xác cao**, KHÔNG phải bản **int4 2.58GB** mà LiteRT-LM đóng gói. Lượng tử hoá xuống int4 **sẽ** làm giảm chất lượng, chưa biết giảm bao nhiêu. **77% là trần, không phải con số sẽ thấy trên điện thoại.**

---

### 8.9 ⭐ Kết quả đo trên MÁY THẬT (Story 1.1 + 1.2)

Samsung SM-M526BR · SM7325 (Snapdragon 778G) · Adreno 642L · Android 13 · arm64-v8a.

| | **GPU** | CPU |
|---|---|---|
| Model | `-gpu.litertlm` 2.01 GB | `.litertlm` 2.59 GB |
| `initialize()` | 36.98 s | 28.86 s |
| **Token đầu tiên** | **1.09 s** | 4.46 s |
| Prefill | **264.9 tok/s** | 59.7 tok/s |
| Decode | **8.3 tok/s** | 6.0 tok/s |
| Ước 1 trang (300 token) | **36 s** | 50 s |

**R4 đã được trả lời và đạt.** Ngoại suy trước đó (60–130 s/trang) là **bi quan quá mức** — số thật là 36 s.

**Giả thuyết "Adreno 642L không chạy được GPU" là SAI.** Nút thắt thật là thiếu một dòng khai báo trong manifest (`uses-native-library`), không phải giới hạn phần cứng. Xem AD-23 và `spike/FINDINGS.md` F17.

⚠️ **`initialize()` mất 29–37 s** — Google ghi "tới 10 giây", thực tế gấp 3–4 lần. Ngân sách 8s của NFR-005 **chỉ đúng khi engine đã nóng**. AD-20 là điều kiện sống còn, không phải tối ưu.

⚠️ **Ràng buộc dung lượng máy:** M52 của tác giả còn **5.5 GB / 107 GB (dùng 95%)**. Không đẩy được cả hai biến thể model cùng lúc.

---

### 8.10 🚨 Sửa §8.9: GPU KHÔNG dùng được

§8.9 kết luận "GPU chạy được, 36 s/trang". **Kết luận đó sai.**

Nó chỉ dựa trên hàm `benchmark()` của LiteRT-LM — thứ trả về `tok/s` và `timeToFirstToken`, tức **toàn chỉ số hiệu năng, không có chỉ số đúng/sai**. Khi cho sinh văn bản thật, GPU trả về rác:

```
[0] 俺, lại nhờ với mùi folksERICK唄
[4] Heyतरह_make###
```

| | GPU | **CPU** |
|---|---|---|
| JSON hợp lệ | **0/4 trang** | **4/4 trang** |
| Nội dung | rác đa ngôn ngữ | tiếng Việt mạch lạc |
| Thời gian/trang | 4.5–20.1 s | 51.3–79.4 s |

**CPU là đường duy nhất dùng được trên Adreno 642L.** Xem AD-2, và `spike/FINDINGS.md` F18.

### Kết quả Story 1.3 — chất lượng bản int4

48 bubble, máy thật, CPU, cùng glossary và prompt như Phase 0:

| Trang | Điểm |
|---|---|
| `tubaki_010` | 58% · `tubaki_025` | 67% |
| `tubaki_100` | 67% · `tubaki_150` | 83% |
| **Tổng** | **~69%** |

Trần trên PC (bản 7.2 GB) là 77% ⇒ **int4 mất khoảng 8 điểm**.

Chỗ mất điểm chính: bản int4 **không đọc nổi glossary cho các mục khó**. `ソデにした`, `筆下ろし`, `先生` đều sai dù glossary ghi rõ — trong khi bản 7.2 GB dịch đúng.

### Bức tranh sau Story 1.3 — cả hai chỉ số đều sát mép

| | Ngưỡng | Đo được | Biên |
|---|---|---|---|
| Chất lượng | 70% | **69%** | **âm 1 điểm** |
| Tốc độ/trang | 90 s | **51–79 s** | mỏng |

Không còn dư địa cho bất kỳ thứ gì làm chậm hoặc làm giảm chất lượng thêm.

---

### 8.11 ⭐ CHỐT CỔNG AD-14 — ĐI TIẾP Epic 2, có ba điều kiện

| # | Rủi ro | Kết quả | |
|---|---|---|---|
| R1 | OCR đủ chính xác | **chưa đo CER** | ❌ |
| R2 | LLM dịch đủ tốt | **69%** trên bộ khó nhất | ✅ vượt NFR-004b 19 điểm |
| R3 | Không OOM | 0 lần / 13 phút, RSS tự giảm | ✅ |
| R4 | Đủ nhanh | tổng ước **58–87 s** | ⚠️ biên **3 s** |

**Ba điều kiện ràng buộc Epic 2:**

| # | Điều kiện |
|---|---|
| **Đ1** | **Đo R1 (CER) trước khi kết thúc Epic 2.** Rủi ro duy nhất chưa ai chạm. OCR **bịa chữ** chứ không báo lỗi (F2) |
| **Đ2** | **Mỗi story đo lại tổng thời gian trang trên M52.** Biên chỉ 3 giây |
| **Đ3** | **Cache (FR-060) làm SỚM.** Thứ duy nhất giảm được cả thời gian, RAM và nhiệt (AD-24) |

**Không còn dư địa.** GPU không dùng được nên không còn đường tăng tốc. Nếu Epic 2 làm vỡ ngưỡng, mọi lựa chọn còn lại đều là **hạ tiêu chuẩn**: nới lên 120 s, hoặc phá AD-3 (hỏng xưng hô), hoặc dùng model nhỏ hơn (1.7B chỉ đạt 17%).

**Rủi ro sản phẩm chưa có lời giải:** máy nóng **82–84°C** khi dịch liên tục. Chưa throttle trong 13 phút, nhưng người dùng thật sẽ gặp đúng nhiệt đó.

### 8.12 Bảy giả định của brief, sáu sai

| Brief giả định | Thực tế |
|---|---|
| Model to hơn dịch tốt hơn | **Sai 2 lần** — E2B thắng 4B, bằng 8B |
| Họ Qwen mạnh tiếng Nhật | **Sai** — Gemma 4 thắng rõ |
| MediaPipe là runtime | **Đã khai tử** |
| GPU sẽ tăng tốc | **Sai** — sinh rác trên Adreno 642L |
| Glossary là tinh chỉnh Phase 3 | **Sai** — đòn bẩy mạnh nhất, đã lên MVP |
| Ngưỡng 40 s/trang | **Bất khả thi**, đã nới 90 s |
| Gói 3 GB cần 8 GB RAM | Gần đúng |

Đây là lý do AD-14 tồn tại, và lý do nó phải đứng trước mọi thứ khác.

---

### 8.13 Cách chia bubble: đã đo hết, cả trang là tối ưu

| Cách | Tổng | Bubble đầu | Dịch được |
|---|---|---|---|
| Từng ô | 209.7 s | 4.3 s | — |
| Nhóm 3 | 185.5 s | 28.1 s | 45/48 |
| Nhóm 4 | 81.8 s | 6.7 s | 45/48 |
| Nhóm 6 | 67.4 s | 8.4 s | 43/48 |
| **Cả trang** | **57.7 s** | 14.4 s | **48/48** |

**Cả trang nhanh nhất VÀ đủ nhất.** Chia nhỏ làm mất 3–5 bubble, và chậm hơn vì phải prefill lại system prompt + glossary nhiều lần.

Lợi thế "bubble đầu nhanh" của nhóm 4 **không sống sót trên máy thật**: quy sang M52 cho ~83 s — đúng bằng cả trang hiện tại — mà tổng chậm hơn 42%.

### 8.14 🚨 NFR-005 (bubble đầu ≤ 8s) là mâu thuẫn kiến trúc

Đo trên M52: **bubble đầu 91 s, trong đó 83 s là prefill** (91%).

Transformer không sinh được token nào trước khi prefill xong toàn bộ prompt. Đây là cách nó hoạt động, không phải lỗi cài đặt. Prefill ~1000 token trên Snapdragon 778G ở 2 luồng mất **50–85 s**.

Muốn bubble đầu ≤ 8 s thì prompt phải dưới ~150 token — không chứa nổi 12 bubble và glossary.

**Đã thử và đo hết mọi cách chia. Không cách nào cứu được.** ⇒ phải nới ngưỡng.

⚠️ Đây là **lần thứ hai** nới ngưỡng tốc độ (40s → 90s → 190s). Ghi rõ ra để lần đọc sau không tưởng đó là con số có cơ sở từ đầu. Cả ba lần đều do ngưỡng được đặt **trước khi có phép đo**.

---

### 8.7 Còn nợ trước khi code

| # | Việc | Chặn gì |
|---|---|---|
| 1 | Tạo ground truth để đo CER có số | R1 vẫn chưa được trả lời |
| 2 | Đo precision/recall detector có số | Chốt giữa bản 11MB và 44MB |
| 3 | **Đo trên chính M52** | R3, R4 — mọi số hiện tại là ngoại suy |
| 4 | Kiểm license `gemma3:4b` (Gemma có điều khoản riêng, không phải Apache-2.0) | Phát hành |

## 9. Giấy phép & pháp lý

| Vấn đề | Xử lý |
|---|---|
| Nội dung có bản quyền | App **chỉ xử lý nội dung đang hiển thị trên máy người dùng**. Không tải, không lưu trữ, không phân phối. Không tích hợp bất kỳ nguồn truyện lậu nào |
| Giấy phép model | **Từng model có giấy phép RIÊNG, khác với repo chứa nó.** Trước khi nhúng bất cứ model nào: mở trang model đọc license, ghi lại vào `docs/licenses.md`. Không suy từ license của repo |
| Project tham khảo | `manga-image-translator`, `BallonsTranslator` dùng giấy phép họ GPL — đọc/học thoải mái, nhưng nhúng code vào sản phẩm đóng sẽ kéo theo nghĩa vụ mở mã |
| Thông báo chụp màn hình | FR-012 — vừa là yêu cầu của Google Play, vừa là điều đúng nên làm |

Vì MVP chỉ dùng riêng, các mục điều khoản sử dụng / chính sách quyền riêng tư **hoãn đến khi thực sự quyết định public**.

---

## 10. Chỉ số thành công

| Chỉ số | Ngưỡng | Đo thế nào |
|---|---|---|
| Luồng chính chạy offline | 100% | Bật chế độ máy bay, chạy hết UJ-1 |
| CER của OCR | ≤ 10% | Bộ test `tubaki`, có ground truth |
| Bubble "hiểu được mạch truyện" | ≥ 80% | Chấm tay 50 đoạn thoại |
| Thời gian một màn trên M52 | ≤ 40s | Đo trên máy thật |
| Bubble đầu tiên | ≤ 8s | Đo trên máy thật |

### Counter-metrics — cái phải KHÔNG xấu đi

| Chỉ số ngược | Ngưỡng | Vì sao theo dõi |
|---|---|---|
| **Thoại bịa** — bubble hiện thoại mà vùng đó vốn không có chữ | **0%** | Đã quan sát thực tế: manga-ocr bịa chữ ở vùng tranh (`spike/FINDINGS.md` F2). Thoại bịa đọc rất mượt nên không ai phát hiện — đây là lỗi nguy hiểm nhất của cả hệ thống |
| Tỷ lệ bubble dịch sai **gây hiểu sai cốt truyện** | ≤ 5% | "Hiểu được mạch" mà hiểu **nhầm** thì tệ hơn không dịch |
| Tỷ lệ bubble bị bỏ sót không phát hiện được | ≤ 5% | Bỏ sót thoại quan trọng làm mất mạch, khó phát hiện hơn dịch sai |
| Bubble bị chữ tràn ra ngoài hoặc đè lên tranh | ≤ 5% | Typesetting hỏng làm trang không đọc nổi dù dịch đúng |
| Nhiệt độ máy / tụt pin trong 30 phút đọc | Không nóng tới mức throttle | LLM chạy liên tục trên điện thoại là gánh nặng thật |
| Số lần app tự chết trong 30 phút | 0 | Liên quan NFR-007 |

---

## 11. Câu hỏi mở

| # | Câu hỏi | Chặn giai đoạn nào | Ai trả lời |
|---|---|---|---|
| ~~Q1~~ | ~~M52 bản mấy GB RAM?~~ — **CHỐT: 8GB** | — | ✅ |
| ~~Q2~~ | ~~Gói là 4B hay 2B?~~ — **CHỐT: `gemma3:4b`**, không phải Qwen | — | ✅ Phase 0, §8.2 |
| Q3 | Host gói mô hình ~3.8GB ở đâu miễn phí? Phương án: tải thẳng từ Hugging Face | Chặn FR-050 | Kiểm điều khoản băng thông HF |
| Q4 | Máy dưới ngưỡng RAM: cảnh báo rồi cho chạy, hay chặn hẳn? | Chặn FR-053 | TrongND, sau khi có R3 đo trên máy thật |
| Q5 | Runtime LLM trên Android: llama.cpp hay MediaPipe LLM Inference? | **Chặn architecture** | Bước architecture |
| Q6 | **Cơ chế lọc vùng không có thoại** (FR-023): dùng điểm tin cậy detector, hay đối chiếu `bubble` ∩ `text_bubble`, hay heuristic nền sáng? | **Chặn architecture** | Bước architecture |
| Q7 | **Cách kiểm tra toàn vẹn id** (FR-036): gửi lại nguyên văn JA để đối chiếu, hay đánh id không tuần tự, hay tách thành nhiều lần gọi nhỏ? | **Chặn architecture** | Bước architecture |
| Q8 | Glossary tích luỹ (FR-034): nhận diện tên riêng bằng gì khi chạy offline? | Chặn Phase 3 | Bước architecture |
| Q9 | Ngưỡng 70% và 90s có đúng với cảm nhận thật khi dùng không? | Không chặn gì | Chỉ trả lời được khi đã có app dùng thật |

---

## 12. Bước tiếp theo

1. ✅ **Phase 0 spike** — đã chạy, kết quả ở `spike/FINDINGS.md`, tổng hợp ở §8.
2. ✅ **Cập nhật PRD** theo số đo.
3. ⏳ **`bmad-architecture`** — trả lời Q5–Q8, thiết kế module, chọn runtime LLM.
4. ⏳ **`bmad-create-epics-and-stories`**.
5. ⏳ **Phase 1** — cần Android SDK: mở Android Studio chạy Setup Wizard.
6. ⏳ **Đo lại trên M52 ngay khi có app chạy được** — mọi số hiệu năng hiện tại là ngoại suy từ PC.
