"""Smoke test R1: tai model manga-ocr va doc thu mot vung anh.

Chua co detector nen chua cat dung bubble - buoc nay chi xac nhan
model tai duoc, chay duoc tren CPU, va do thoi gian moi lan goi.
"""
import time, sys
from pathlib import Path
from PIL import Image

t0 = time.time()
from manga_ocr import MangaOcr
mocr = MangaOcr()
print(f"[load] {time.time()-t0:.1f}s", flush=True)

src = Path(r"D:\Auto translate android\Support test file\japanese page")
pages = sorted(src.glob("tubaki_*.jpg"))
print(f"[data] {len(pages)} trang", flush=True)

img = Image.open(pages[1])
print(f"[size] {pages[1].name} = {img.size}", flush=True)

# Cat thu goc tren-phai: vung bubble hay nam o manga (doc phai->trai)
w, h = img.size
crop = img.crop((int(w*0.55), int(h*0.05), int(w*0.95), int(h*0.30)))
crop.save("out/smoke_crop.png")

t1 = time.time()
text = mocr(crop)
print(f"[ocr] {time.time()-t1:.2f}s -> {text!r}", flush=True)
print("SMOKE OK", flush=True)
