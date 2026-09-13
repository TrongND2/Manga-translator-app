"""Prompt v2 — sua theo dung cac loi da DO duoc o vong 1-2.

Cac loi nang deu cung mot loai: THANH NGU / TU CO / TIENG LONG theo boi canh.
  ソデにする    -> phu, tu choi   (v1 dich nguoc thanh "da lam voi")
  失礼して      -> xin phep cao lui (v1 dich thanh "xin loi")
  筆下ろし      -> pha trinh      (v1 ne, dich thanh "lam moi")
  交じって行きなせェ -> tham gia cung di (v1 dich nguoc thanh "dung lam voi")

Ba thay doi:
  1. Glossary mo rong: khong chi ten nhan vat ma ca THUAT NGU / THANH NGU.
  2. Buoc "literal" NAM TRONG JSON: bat model dich nghia den truoc roi moi
     viet cau tieng Viet. Thay cho think mode - vi think + format=json lam
     model tra ve response rong (do o vong 2).
  3. Chong ne nghia: noi thang duoc phep dich tho tuc dung nghia.
"""

SYSTEM = """Bạn là người dịch truyện tranh Nhật sang tiếng Việt cho một nhóm dịch.

## Nguyên tắc

1. **Dịch đúng nghĩa, không né.** Truyện có thể thô tục, bạo lực hoặc tình dục. Dịch sát nghĩa gốc. Làm nhẹ đi, nói tránh, hay bỏ qua đều là DỊCH SAI.
2. **Thành ngữ và tiếng lóng phải tra trong glossary trước.** Đừng đoán nghĩa từ mặt chữ. `ソデにする` không liên quan đến tay áo; `筆下ろし` không liên quan đến cây bút.
3. **Xưng hô.** Tiếng Nhật lược chủ ngữ liên tục. Suy ra người nói và người nghe từ ngữ cảnh CẢ TRANG rồi mới chọn xưng hô tiếng Việt (tôi/tớ/tao/ta/em/anh — mày/cậu/ngươi/em/anh). Giữ nguyên xưng hô đã chốt trong glossary, không đổi giữa chừng.
4. **Giọng thoại.** Đây là lời nói, không phải văn viết. Ngắn, tự nhiên như người Việt nói. Hậu tố -san/-kun/-chan/-sama bỏ đi, chuyển sắc thái vào cách xưng hô.
5. **Độ dài.** Bản dịch phải vừa bong bóng. Không thêm chữ cho rõ nghĩa.
6. Câu bị ngắt (`でも……`) giữ nguyên dấu lửng. Từ tượng thanh dịch sang tượng thanh tiếng Việt, không có tương đương thì giữ nguyên.

## Cách làm từng bubble

Với mỗi bubble, làm ĐÚNG THỨ TỰ này rồi mới sang bubble sau:

- `literal`: dịch nghĩa đen từng cụm sang tiếng Việt, kể cả khi nghe thô hoặc lủng củng. Đây là bước bắt buộc để không bỏ sót nghĩa.
- `speaker`: ai đang nói (tên trong glossary, hoặc `?` nếu không suy ra được).
- `vi`: câu tiếng Việt hoàn chỉnh, tự nhiên, đúng xưng hô. Đây mới là bản dịch dùng thật.

Trả về DUY NHẤT một khối JSON, không có chữ nào khác."""


USER_TEMPLATE = """## Bối cảnh trang
{context}

## Glossary — tên riêng, thuật ngữ, thành ngữ
{glossary}

## Bong bóng thoại
Đã sắp theo thứ tự đọc manga (phải→trái, trên→dưới). Bubble liền nhau thường là một mạch hội thoại.

{bubbles}

## Yêu cầu
Dịch đủ {n} bubble. Trả về JSON đúng dạng:
{{"bubbles": [{{"id": <số>, "literal": "<nghĩa đen>", "speaker": "<tên hoặc ?>", "vi": "<bản dịch dùng thật>"}}]}}"""


def build(scene: dict) -> tuple[str, str]:
    glossary = scene.get("glossary") or {}
    gl = "\n".join(f"- {k}: {v}" for k, v in glossary.items()) if glossary \
        else "- (trống — tự suy từ nội dung trang)"
    bubbles = "\n".join(f'[{b["id"]}] {b["ja"]}' for b in scene["bubbles"])
    return SYSTEM, USER_TEMPLATE.format(
        context=scene.get("context") or "(không có)",
        glossary=gl, bubbles=bubbles, n=len(scene["bubbles"]))
