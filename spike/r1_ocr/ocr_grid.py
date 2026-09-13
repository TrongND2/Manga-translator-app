"""Doc thu nhieu vung tren mot trang that, ghi ket qua ra UTF-8.

Chua co detector nen cat theo luoi - muc dich chi de xac nhan
(a) manga-ocr tra ve tieng Nhat that chu khong phai rac,
(b) thoi gian moi lan goi tren CPU.
"""
import json, time
from pathlib import Path
from PIL import Image
from manga_ocr import MangaOcr

mocr = MangaOcr()
src = Path(r"D:\Auto translate android\Support test file\japanese page")
page = src / "tubaki_010.jpg"
img = Image.open(page).convert("RGB")
w, h = img.size

results, times = [], []
# Luoi 3 cot x 4 hang, quet phai->trai tren->duoi (thu tu doc manga)
for row in range(4):
    for col in range(2, -1, -1):
        box = (int(w*col/3), int(h*row/4), int(w*(col+1)/3), int(h*(row+1)/4))
        crop = img.crop(box)
        t = time.time()
        txt = mocr(crop)
        dt = time.time() - t
        times.append(dt)
        results.append({"box": box, "sec": round(dt, 3), "text": txt})

out = {
    "page": page.name,
    "size": [w, h],
    "so_lan_goi": len(times),
    "sec_trung_binh": round(sum(times)/len(times), 3),
    "sec_tong": round(sum(times), 2),
    "ket_qua": results,
}
Path("out/ocr_grid.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"{len(times)} calls | tb {out['sec_trung_binh']}s | tong {out['sec_tong']}s")
