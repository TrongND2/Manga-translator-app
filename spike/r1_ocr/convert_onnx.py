"""Dieu kien D1 — tra loi R1: manga-ocr chuyen ONNX int8 con giu duoc bao nhieu?

R1 la rui ro DUY NHAT chua ai cham toi sau ca Phase 0 va Epic 1.

Cach lam, khong can nguoi go ground truth:
  1. Chay manga-ocr ban PyTorch fp32 (ban goc, chinh xac nhat co the) tren
     cac vung bubble that do detector cat ra -> lay lam THAM CHIEU.
  2. Chuyen sang ONNX, roi ONNX int8.
  3. Do CER giua ban ONNX va ban tham chieu.

Luu y ve pham vi ket luan: cach nay do "int8 mat bao nhieu so voi fp32",
KHONG do "manga-ocr doc dung bao nhieu so voi chu that tren trang". Cau hoi
thu hai can nguoi go tay, va phai ghi ro la CHUA lam.
"""
import json, sys, time
from pathlib import Path

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from detect import BubbleDetector, reading_order

SRC = Path(r"D:\Auto translate android\Support test file\japanese page")
OUT = Path("out"); OUT.mkdir(exist_ok=True)
MODELS = Path("models"); MODELS.mkdir(exist_ok=True)
ONNX_FP32 = MODELS / "manga-ocr-fp32.onnx"
ONNX_INT8 = MODELS / "manga-ocr-int8.onnx"

N_PAGES = 15          # 15 trang x ~12 bubble = ~180 vung, du de do CER
MIN_CONTAINED = 0.9   # AD-5


def crops_from_pages(n_pages):
    """Cat vung text_bubble THAT bang detector + cong AD-5 — giong het app."""
    det = BubbleDetector("models/detector-v4-s_int8.onnx")
    pages = sorted(SRC.glob("tubaki_*.jpg"))
    # Bo qua bia/banner: chi lay trang don 974x1400 (F3).
    good = []
    for p in pages:
        with Image.open(p) as im:
            if im.size == (974, 1400):
                good.append(p)
        if len(good) >= n_pages:
            break

    out = []
    for p in good:
        img = Image.open(p).convert("RGB")
        dets = reading_order(det(img, conf=0.5), img.size[0])
        shells = [d["box"] for d in dets if d["label"] == "bubble"]
        for d in dets:
            if d["label"] != "text_bubble":
                continue
            x1, y1, x2, y2 = d["box"]
            area = max(1, (x2 - x1) * (y2 - y1))
            best = 0.0
            for s in shells:
                ix1, iy1 = max(x1, s[0]), max(y1, s[1])
                ix2, iy2 = min(x2, s[2]), min(y2, s[3])
                if ix2 > ix1 and iy2 > iy1:
                    best = max(best, (ix2 - ix1) * (iy2 - iy1) / area)
            if best < MIN_CONTAINED:
                continue
            out.append({"page": p.name, "box": d["box"],
                        "crop": img.crop((x1, y1, x2, y2))})
    return out


def cer(ref: str, hyp: str) -> float:
    """Character Error Rate = khoang cach Levenshtein / do dai tham chieu."""
    if not ref:
        return 0.0 if not hyp else 1.0
    prev = list(range(len(hyp) + 1))
    for i, rc in enumerate(ref, 1):
        cur = [i]
        for j, hc in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rc != hc)))
        prev = cur
    return prev[-1] / len(ref)


def main():
    print("[1] cat vung bubble that tu trang manga...", flush=True)
    crops = crops_from_pages(N_PAGES)
    print(f"    {len(crops)} vung tu {N_PAGES} trang", flush=True)
    if not crops:
        print("KHONG cat duoc vung nao — dung lai."); return

    print("[2] chay manga-ocr fp32 lam THAM CHIEU...", flush=True)
    from manga_ocr import MangaOcr
    mocr = MangaOcr()
    ref = []
    t0 = time.time()
    for i, c in enumerate(crops):
        ref.append(mocr(c["crop"]))
        if (i + 1) % 25 == 0:
            print(f"    {i+1}/{len(crops)}", flush=True)
    t_fp32 = time.time() - t0
    print(f"    xong {t_fp32:.1f}s ({t_fp32/len(crops):.3f}s/vung)", flush=True)

    Path(OUT / "r1_reference.json").write_text(
        json.dumps([{"page": c["page"], "box": c["box"], "text": r}
                    for c, r in zip(crops, ref)], ensure_ascii=False, indent=1),
        encoding="utf-8")
    print(f"    ghi out/r1_reference.json", flush=True)

    # Thong ke so bo ve chinh ban tham chieu.
    empty = sum(1 for r in ref if not r.strip())
    lens = [len(r) for r in ref]
    print(f"    vung rong: {empty}/{len(ref)}  |  do dai trung binh: {np.mean(lens):.1f} ky tu", flush=True)


if __name__ == "__main__":
    main()
