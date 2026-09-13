"""Q7: kiem chung co che phat hien LECH ID (loi F10).

Y tuong: bat model tra ve 'ja4' = 4 ky tu dau cua nguyen ban JA kem moi ban dich.
App doi chieu ja4 voi input. Lech mot o => ja4 khong khop => bat duoc.

Do tren tubaki_025 - dung trang da dinh loi F10.
Cung do CHI PHI: them bao nhieu token, cham them bao nhieu giay.
"""
import json, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from fixtures import GLOSSARY, CONTEXT

MODEL = "gemma3:4b"
PAGE = "tubaki_025.jpg"

scenes = {s["page"]: s for s in json.loads(
    Path("out/r2_scenes.json").read_text(encoding="utf-8"))}
scene = scenes[PAGE]

SYSTEM_BASE = """Bạn là người dịch truyện tranh Nhật sang tiếng Việt.

- Dịch đúng nghĩa, không né tránh. Truyện có thể thô tục.
- Thành ngữ và tiếng lóng: tra glossary trước, đừng đoán từ mặt chữ.
- Tiếng Nhật lược chủ ngữ. Suy ra người nói/người nghe từ ngữ cảnh cả trang rồi mới chọn xưng hô tiếng Việt.
- Giọng thoại tự nhiên, ngắn, vừa bong bóng.

Trả về DUY NHẤT một khối JSON."""

SYS_ECHO = SYSTEM_BASE + """

QUAN TRỌNG: với mỗi bubble bạn PHẢI chép lại `ja4` = **4 ký tự đầu tiên của nguyên bản tiếng Nhật** của đúng bubble đó. Đây là mã đối chiếu để phát hiện gán nhầm. Chép y nguyên, không sửa, không dịch."""

USER_T = """## Bối cảnh
{context}

## Glossary
{gl}

## Bong bóng (thứ tự đọc phải→trái, trên→dưới)
{bubbles}

## Yêu cầu
Dịch đủ {n} bubble. JSON dạng:
{fmt}"""

FMT_PLAIN = '{"bubbles":[{"id":<số>,"vi":"<bản dịch>"}]}'
FMT_ECHO = '{"bubbles":[{"id":<số>,"ja4":"<4 ký tự đầu nguyên bản>","vi":"<bản dịch>"}]}'


def build(scene, fmt):
    gl = "\n".join(f"- {k}: {v}" for k, v in GLOSSARY.items())
    bubbles = "\n".join(f'[{b["id"]}] {b["ja"]}' for b in scene["bubbles"])
    return USER_T.format(context=CONTEXT, gl=gl, bubbles=bubbles,
                         n=len(scene["bubbles"]), fmt=fmt)


def call(system, user):
    payload = {"model": MODEL, "system": system, "prompt": user,
               "stream": False, "think": False, "format": "json",
               "options": {"temperature": 0.3, "num_ctx": 8192, "num_predict": 2048}}
    req = urllib.request.Request(
        "http://127.0.0.1:11434/api/generate",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=3600) as r:
        res = json.loads(r.read().decode("utf-8"))
    return {"wall": round(time.time() - t, 1),
            "out_tok": res.get("eval_count") or 0,
            "raw": res.get("response", "")}


truth = {b["id"]: b["ja"] for b in scene["bubbles"]}
out = {}

for tag, system, fmt in [("khong_echo", SYSTEM_BASE, FMT_PLAIN),
                         ("co_echo", SYS_ECHO, FMT_ECHO)]:
    r = call(system, build(scene, fmt))
    try:
        r["parsed"] = json.loads(r["raw"])
    except Exception:
        r["parsed"] = None

    if r["parsed"] and tag == "co_echo":
        khop, lech = 0, []
        for b in r["parsed"]["bubbles"]:
            i = b.get("id")
            got = (b.get("ja4") or "")[:4]
            want = (truth.get(i) or "")[:4]
            if got == want:
                khop += 1
            else:
                lech.append({"id": i, "ja4_model_tra": got, "ja4_that": want,
                             "vi": b.get("vi")})
        r["kiem_tra"] = {"khop": khop, "tong": len(r["parsed"]["bubbles"]),
                         "lech": lech}
    out[tag] = r
    print(f"[{tag}] {r['wall']}s | {r['out_tok']} tok | json={r['parsed'] is not None}",
          flush=True)
    if "kiem_tra" in r:
        k = r["kiem_tra"]
        print(f"   ja4 khop {k['khop']}/{k['tong']} | phat hien {len(k['lech'])} bubble lech",
              flush=True)

a, b = out["khong_echo"], out["co_echo"]
print(f"\nCHI PHI: +{b['out_tok']-a['out_tok']} token, "
      f"+{round(b['wall']-a['wall'],1)}s "
      f"({round((b['wall']/a['wall']-1)*100)}%)", flush=True)

Path("out/q7_results.json").write_text(
    json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
