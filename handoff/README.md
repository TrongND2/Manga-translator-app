# Chat Handoffs

Mỗi chat session / dự án quan trọng có **một file "doc sống"** `.md` ở đây, để khi cần chỉ việc bảo Claude *"đọc lại handoff <tên>"* là nắm lại bối cảnh — không cần mở lại nguyên phiên.

> **Git:** CHỈ `README.md` này (quy ước chung) được đẩy lên git. Các file handoff (`Common.md`, `TREC.md`, `archive/…`) **giữ LOCAL, KHÔNG đẩy** — chúng là ghi chú làm việc riêng máy. (Hệ quả: nội dung handoff không sync sang máy khác; chỉ quy ước được chia sẻ.)

## Nguyên tắc cốt lõi: DOC SỐNG, không phải nhật ký cộng dồn
Handoff là **ảnh chụp trạng thái hiện tại**, không phải log kể lại mọi bước. → File luôn nhỏ-gọn-cập-nhật.

Bố cục mỗi file:
- **🔵 Trạng thái hiện tại** — *ghi đè* mỗi lần, phản ánh "đang ở đâu". KHÔNG cộng dồn.
- **📌 Quyết định / quy ước còn hiệu lực** — chỉ giữ cái còn đúng.
- **🟡 Việc đang mở** — todo còn dang dở.
- **🗒️ Nhật ký gọn** — mỗi mốc lớn 1 dòng, mới nhất trên cùng.

Đầu file ghi **session id** (tên `.jsonl`) để cần thì resume bản gốc.

## Chống phình: archive
- Tri thức **bền** (quy tắc, quy ước dùng lâu) → đẩy sang **CLAUDE.md** hoặc **auto-memory**, KHÔNG để trong handoff.
- Khi "Nhật ký gọn" vượt ~30 dòng hoặc qua một kỳ (vd nửa năm) → cắt phần cũ sang `chat-handoffs/archive/<Label>-<kỳ>.md` (vd `Common-2026-H1.md`), file chính chỉ giữ phần gần đây.
- Tuổi thọ session ≠ độ dài handoff: cứ mở session mới theo đợt việc, handoff vẫn là doc sống của dự án/chủ đề.

## Quy ước tên
`chat-handoffs/<Label>.md` (vd `Common.md`, `TREC.md`). Archive: `chat-handoffs/archive/<Label>-<kỳ>.md`.

## Cách dùng
- **Cập nhật:** prompt *"cập nhật handoff cho phiên này"* → Claude ghi đè Trạng thái + thêm 1 dòng nhật ký.
- **Đọc lại (phiên mới):** prompt *"đọc lại handoff TREC"*.
- **Tạo từ phiên khác:** prompt *"đọc transcript phiên `<session-id>` rồi viết handoff"* (Claude đọc file `.jsonl` trong thư mục project).

## Khác gì với memory / transcript
- **auto-memory** (`~/.claude/.../memory/`): tri thức bền, tự nạp mỗi phiên — không cần bảo đọc.
- **handoff (folder này):** trạng thái theo phiên/dự án, người đọc được, đọc khi cần.
- **transcript `.jsonl`** (trong `~/.claude/projects/...`): bản nguyên văn để `--resume` đúng mạch cũ.
