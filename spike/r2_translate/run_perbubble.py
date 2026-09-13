"""Do truc tiep: dich CA TRANG mot lan goi vs dich TUNG BUBBLE rieng le.

Day la gia dinh nen tang cua AD-3, lay tu brief va CHUA BAO GIO duoc do.
Brief da sai 6/7 lan nen phai kiem.

Cau hoi co hai mat, va ca hai deu quan trong:
  chat luong : dich tung o co that su te hon khong, te o dau
  toc do     : dich tung o co cho bubble dau ve nhanh hon khong

Neu tung-o gan bang ca-trang ve chat luong thi NFR-005 (bubble dau <= 8s)
giai quyet duoc ma khong mat gi — vi moi o chi can prefill mot prompt ngan.
"""
import json, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from fixtures import GLOSSARY, CONTEXT

MODEL = "gemma4:e2b"
OUT = Path("out")

SYSTEM = """Dịch thoại manga Nhật sang tiếng Việt tự nhiên.

XƯNG HÔ — quan trọng nhất:
Suy ra quan hệ giữa người nói và người nghe rồi chọn xưng hô cho đúng. ĐỪNG mặc định "mày/tao".
- lịch sự, xa lạ → tôi/anh · tôi/ông
- thân mật → tớ/cậu
- suồng sã → tao/mày
- bề trên với bề dưới → ta/ngươi

DỊCH HẾT: không để sót chữ Nhật nào. Tên riêng phiên âm La-tinh.
Tra glossary trước khi đoán nghĩa thành ngữ. Ngắn gọn.

Trả về DUY NHẤT một khối JSON."""


def call(prompt, timeout=1800):
    payload = {"model": MODEL, "system": SYSTEM, "prompt": prompt,
               "stream": False, "think": False, "format": "json",
               "options": {"temperature": 0.3, "num_ctx": 8192, "num_predict": 2048}}
    req = urllib.request.Request("http://127.0.0.1:11434/api/generate",
                                 data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        res = json.loads(r.read().decode("utf-8"))
    return res.get("response", ""), time.time() - t, res


def gloss_text():
    return "\n".join(f"- {k}: {v}" for k, v in GLOSSARY.items())


def whole_page(scene):
    bb = "\n".join(f'[{b["id"]}] {b["ja"]}' for b in scene["bubbles"])
    p = (f"## Bối cảnh\n{CONTEXT}\n\n## Glossary\n{gloss_text()}\n\n"
         f"## Bong bóng (thứ tự đọc phải→trái)\n{bb}\n\n"
         f'## Yêu cầu\nDịch đủ {len(scene["bubbles"])} bubble. JSON: '
         '{"bubbles":[{"id":<số>,"vi":"<bản dịch>"}]}')
    raw, secs, meta = call(p)
    return raw, secs, meta


def per_bubble(scene):
    """Moi bubble mot lan goi RIENG — khong biet gi ve cac bubble khac."""
    outs, total, first = [], 0.0, None
    for b in scene["bubbles"]:
        p = (f"## Bối cảnh\n{CONTEXT}\n\n## Glossary\n{gloss_text()}\n\n"
             f'## Bong bóng\n[{b["id"]}] {b["ja"]}\n\n'
             '## Yêu cầu\nDịch bubble này. JSON: {"bubbles":[{"id":<số>,"vi":"<bản dịch>"}]}')
        raw, secs, _ = call(p)
        if first is None:
            first = secs
        total += secs
        try:
            j = json.loads(raw)
            outs.append(j["bubbles"][0])
        except Exception:
            outs.append({"id": b["id"], "vi": "(hỏng)"})
    return outs, total, first


def parse(raw):
    try:
        return {b["id"]: b.get("vi", "?") for b in json.loads(raw).get("bubbles", [])}
    except Exception:
        return {}


def main():
    scenes = json.loads((OUT / "r2_scenes.json").read_text(encoding="utf-8"))
    result = {}

    for sc in scenes:
        page = sc["page"]
        print(f"\n=== {page} ({len(sc['bubbles'])} bubble) ===", flush=True)

        raw, secs, meta = whole_page(sc)
        w = parse(raw)
        print(f"  ca trang : {secs:5.1f}s | {len(w)} bubble", flush=True)

        outs, total, first = per_bubble(sc)
        p = {b["id"]: b.get("vi", "?") for b in outs}
        print(f"  tung o   : {total:5.1f}s tong | bubble dau {first:.1f}s", flush=True)

        result[page] = {
            "ja": {b["id"]: b["ja"] for b in sc["bubbles"]},
            "whole": {"secs": round(secs, 1), "vi": w},
            "per": {"secsTotal": round(total, 1), "secsFirst": round(first, 1), "vi": p},
        }

    (OUT / "perbubble_vs_page.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n[done] out/perbubble_vs_page.json", flush=True)


if __name__ == "__main__":
    main()
