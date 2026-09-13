"""Do chia trang thanh NHOM bubble: 3, 4, 6, hay ca trang (12)?

Bai toan:
  ca trang (12) : tong nhanh nhat, nhung bubble dau phai doi prefill het prompt
  tung o    (1) : bubble dau nhanh, nhung tong cham 3.3 lan vi prefill lap lai

Nhom o giua co the lay duoc phan lon ca hai. Do de tim diem can bang.

Ba so can cho moi kich thuoc nhom:
  tong thoi gian    - so voi nguong NFR-005b
  bubble dau tien   - so voi nguong NFR-005
  chat luong        - nhom nho co lam mat ngu canh khong
"""
import json, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from fixtures import GLOSSARY, CONTEXT

MODEL = "gemma4:e2b"
OUT = Path("out")
SIZES = [3, 4, 6, 12]

SYSTEM = """Dịch thoại manga Nhật sang tiếng Việt tự nhiên.

XƯNG HÔ: suy ra quan hệ người nói/người nghe rồi chọn cho đúng. ĐỪNG mặc định "mày/tao".
lịch sự → tôi/anh · thân mật → tớ/cậu · suồng sã → tao/mày · bề trên → ta/ngươi

DỊCH HẾT: không để sót chữ Nhật nào. Tên riêng phiên âm La-tinh.
Tra glossary trước khi đoán thành ngữ. Ngắn gọn.

Trả về DUY NHẤT một khối JSON."""


def call(prompt):
    payload = {"model": MODEL, "system": SYSTEM, "prompt": prompt,
               "stream": False, "think": False, "format": "json",
               "options": {"temperature": 0.3, "num_ctx": 8192, "num_predict": 2048}}
    req = urllib.request.Request("http://127.0.0.1:11434/api/generate",
                                 data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=1800) as r:
        res = json.loads(r.read().decode("utf-8"))
    return res.get("response", ""), time.time() - t


def gloss():
    return "\n".join(f"- {k}: {v}" for k, v in GLOSSARY.items())


def run_chunked(scene, size):
    """Chia bubble thanh nhom lien tiep theo THU TU DOC — nhom van giu mach hoi thoai."""
    bubbles = scene["bubbles"]
    got, total, first = {}, 0.0, None
    for i in range(0, len(bubbles), size):
        grp = bubbles[i:i + size]
        bb = "\n".join(f'[{b["id"]}] {b["ja"]}' for b in grp)
        # Cac bubble TRUOC nhom nay duoc dua vao lam ngu canh, KHONG dich lai.
        prev = bubbles[max(0, i - 3):i]
        ctx = ""
        if prev:
            ctx = ("\n## Thoại ngay trước (chỉ để hiểu mạch, KHÔNG dịch lại)\n"
                   + "\n".join(f'{b["ja"]}' for b in prev) + "\n")
        p = (f"## Bối cảnh\n{CONTEXT}\n\n## Glossary\n{gloss()}\n{ctx}\n"
             f"## Bong bóng cần dịch (thứ tự đọc phải→trái)\n{bb}\n\n"
             f'## Yêu cầu\nDịch đủ {len(grp)} bubble. JSON: '
             '{"bubbles":[{"id":<số>,"vi":"<bản dịch>"}]}')
        raw, secs = call(p)
        if first is None:
            first = secs
        total += secs
        try:
            for b in json.loads(raw).get("bubbles", []):
                got[b["id"]] = b.get("vi", "?")
        except Exception:
            pass
    return got, total, first


def main():
    scenes = json.loads((OUT / "r2_scenes.json").read_text(encoding="utf-8"))
    result = {}

    for size in SIZES:
        label = "ca trang" if size >= 12 else f"nhom {size}"
        tot, firsts, filled = 0.0, [], 0
        per_page = {}
        for sc in scenes:
            got, t, f = run_chunked(sc, size)
            tot += t
            firsts.append(f)
            filled += sum(1 for b in sc["bubbles"] if got.get(b["id"], "").strip())
            per_page[sc["page"]] = got
        n = sum(len(s["bubbles"]) for s in scenes)
        result[label] = {"size": size, "totalSecs": round(tot, 1),
                         "firstSecs": round(sum(firsts) / len(firsts), 1),
                         "filled": filled, "total": n, "vi": per_page}
        print(f"{label:10s} tong {tot:6.1f}s | bubble dau {sum(firsts)/len(firsts):5.1f}s | "
              f"dich duoc {filled}/{n}", flush=True)

    (OUT / "chunks.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n[done] out/chunks.json", flush=True)


if __name__ == "__main__":
    main()
