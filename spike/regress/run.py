"""Chay bo trang chuan va ghi lai MOC (snapshot) ban dich cua tung bong.

Van de no giai quyet, ghi ro trong CLAUDE.md:

    Mo hinh TAT DINH, nhung ca trang dinh chum. Chay hai lan cung prompt:
    15/15 bong giong het tung chu. Nhung doi MOT cum chu trong prompt thi
    15/15 bong deu doi -- vi AD-3 dua ca trang trong MOT lan goi.
    => tinh prompt la tro dap chuot: moi lan va mot bong co the lam hong
       bong khac ma khong ai thay.

Co moc thi sau moi lan sua prompt / tu dien chi viec chay lai va hoi
`diff.py`: "lan nay doi bao nhieu bong, o nhung trang nao". Doi 1 bong hay
doi 87 bong la hai chuyen hoan toan khac nhau, va truoc day khong ai phan
biet duoc.

    python spike/regress/run.py [ten_moc]

Mac dinh ghi ra `spike/out/regress/<ten_moc>.json`, ten_moc mac dinh la
`baseline`. Chay lai voi ten khac de so:

    python spike/regress/run.py baseline      # lan dau
    ... sua prompt ...
    python spike/regress/run.py thu-nghiem
    python spike/regress/diff.py baseline thu-nghiem

⚠️ PHAM VI -- doc truoc khi tin so lieu:
  - Chay tren PC bang ollama `gemma4:e2b`, KHONG phai LiteRT-LM tren may.
    Dung de do DO LECH giua hai lan chay, khong dung de ket luan chat luong
    tuyet doi tren dien thoai.
  - Goi MOT lan cho ca trang, khong tai hien co che chia dot cua app.
  - Prompt doc THANG tu file Kotlin dang chay (`prompt.build`), nen sua
    prompt trong app la o day thay doi theo -- do la chu dich.
"""
import io
import json
import os
import sys
import time
from pathlib import Path

from PIL import Image

# ⚠️ Console Windows o day la cp932: in mot dau `—` hay bat ky chu Nhat nao ra
# stdout la nem UnicodeEncodeError va **mat sach tien do dang chay**. Da mac
# dung loi do o lan chay dau. Ep UTF-8 mot lan, thay vi di san tung dau gach.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "spike" / "r1_ocr"))
sys.path.insert(0, str(ROOT / "spike" / "r2_translate"))

from detect import BubbleDetector, reading_order          # noqa: E402
from pipeline import ocr_page, translate                  # noqa: E402

PAGES_DIR = ROOT / "Support test file" / "japanese page"
MODEL_ONNX = ROOT / "spike" / "models" / "detector-v4-s_int8.onnx"
OUT_DIR = ROOT / "spike" / "out" / "regress"
LIST = Path(__file__).resolve().parent / "pages.txt"

# Cung nguong app dang dung (`PipelineConfig.detectMinScore`). Doi o app thi
# doi ca o day, khong thi hai ben do hai thu khac nhau.
DETECT_MIN_SCORE = 0.30
OLLAMA_MODEL = "gemma4:e2b"


def page_list():
    out = []
    for line in io.open(LIST, encoding="utf-8"):
        line = line.split("#")[0].strip()
        if line:
            out.append(line)
    return out


def parse_vi(raw: str):
    """Doc JSON mo hinh tra ve -> {id: vi}. Hong thi tra ve rong, khong nem."""
    try:
        d = json.loads(raw)
    except Exception:
        return {}
    items = d.get("bubbles") if isinstance(d, dict) else d
    if not isinstance(items, list):
        return {}
    out = {}
    for it in items:
        if isinstance(it, dict) and "id" in it:
            out[int(it["id"])] = str(it.get("vi", ""))
    return out


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else "baseline"
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    dest = OUT_DIR / ("%s.json" % name)

    # Resume duoc: 10 trang OCR + LLM mat hang chuc phut, dut giua chung ma
    # phai lam lai tu dau thi khong ai chay lan hai.
    snap = {}
    if dest.exists():
        snap = json.loads(io.open(dest, encoding="utf-8").read())
        print("tiep tuc moc cu: da co %d trang" % len(snap))

    from manga_ocr import MangaOcr
    det = BubbleDetector(str(MODEL_ONNX))
    mocr = MangaOcr()

    pages = page_list()
    for i, fname in enumerate(pages, 1):
        if fname in snap:
            print("[%d/%d] %s — da co, bo qua" % (i, len(pages), fname))
            continue
        path = PAGES_DIR / fname
        if not path.exists():
            print("[%d/%d] %s — KHONG THAY FILE, bo qua" % (i, len(pages), fname))
            continue

        t0 = time.time()
        img = Image.open(path)
        # `reading_order` can chieu rong trang de tinh nguong gom hang theo ty
        # le, khong phu thuoc do phan giai -- xem detect.py.
        dets = reading_order(det(img, conf=DETECT_MIN_SCORE), img.size[0])
        bubbles = ocr_page(img, dets, mocr)
        if not bubbles:
            print("[%d/%d] %s — 0 bong doc duoc, bo qua" % (i, len(pages), fname))
            continue

        # temperature=0: BAT BUOC voi bo do hoi quy. Do duoc o mac dinh 0.3:
        # chay hai lan y het van lech 78/187 bong (41,7%). Muc nhieu do nuot
        # sach tin hieu -- "prompt moi lam doi 30 bong" khong con phan biet
        # duoc voi "khong sua gi".
        res = translate({"bubbles": bubbles}, OLLAMA_MODEL, temperature=0.0)
        vi = parse_vi(res["raw"])

        snap[fname] = {
            "bubbles": [
                {"id": b["id"], "ja": b["ja"], "vi": vi.get(b["id"], "")}
                for b in bubbles
            ],
            "n_bubbles": len(bubbles),
            "n_dich_duoc": sum(1 for b in bubbles if vi.get(b["id"], "").strip()),
            "wall_sec": res["wall_sec"],
        }
        # Ghi sau TUNG trang, khong doi chay het: dut giua chung van giu duoc
        # phan da lam.
        io.open(dest, "w", encoding="utf-8").write(
            json.dumps(snap, ensure_ascii=False, indent=1)
        )
        print("[%d/%d] %s — %d bong, dich duoc %d, %.0fs (tong %.0fs)" % (
            i, len(pages), fname, len(bubbles),
            snap[fname]["n_dich_duoc"], res["wall_sec"], time.time() - t0,
        ))

    tong = sum(v["n_bubbles"] for v in snap.values())
    duoc = sum(v["n_dich_duoc"] for v in snap.values())
    print("\nmoc '%s': %d trang, %d bong, dich duoc %d (%.0f%%)"
          % (name, len(snap), tong, duoc, 100.0 * duoc / max(1, tong)))
    print("ghi o: %s" % dest)


if __name__ == "__main__":
    main()
