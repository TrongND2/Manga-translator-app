"""So sanh hai bien the detector tren cac trang that, ve overlay de nhin bang mat.

Muc dich: quyet dinh dung detector-v4-s_int8 (11MB) hay detector_int8 (44MB).
Khong ket luan bang so luong box - phai nhin anh overlay.
"""
import json, sys, time
from pathlib import Path
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from detect import BubbleDetector, reading_order

SRC = Path(r"D:\Auto translate android\Support test file\japanese page")
OUT = Path("out/detect"); OUT.mkdir(parents=True, exist_ok=True)
COLOR = {"bubble": (0, 160, 255), "text_bubble": (0, 200, 0), "text_free": (255, 90, 0)}

PAGES = ["tubaki_010.jpg", "tubaki_025.jpg", "tubaki_050.jpg",
         "tubaki_100.jpg", "tubaki_150.jpg", "tubaki_190.jpg"]

MODELS = {
    "v4s_11mb": "models/detector-v4-s_int8.onnx",
    "full_44mb": "models/detector_int8.onnx",
}

report = {}
for tag, path in MODELS.items():
    det = BubbleDetector(path)
    per_model, times = [], []
    for name in PAGES:
        img = Image.open(SRC / name).convert("RGB")
        t = time.time()
        dets = det(img, conf=0.5)
        dt = time.time() - t
        times.append(dt)
        dets = reading_order(dets, img.size[0])

        vis = img.copy()
        d = ImageDraw.Draw(vis)
        for x in dets:
            x1, y1, x2, y2 = x["box"]
            c = COLOR[x["label"]]
            d.rectangle([x1, y1, x2, y2], outline=c, width=3)
            d.text((x1 + 4, y1 + 2), f'{x["id"]}:{x["label"][:4]} {x["score"]}', fill=c)
        vis.save(OUT / f"{tag}__{name}.png")

        counts = {}
        for x in dets:
            counts[x["label"]] = counts.get(x["label"], 0) + 1
        per_model.append({"page": name, "sec": round(dt, 3), "counts": counts,
                          "dets": dets})
    report[tag] = {"sec_tb": round(sum(times) / len(times), 3), "pages": per_model}

Path("out/detect_report.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

for tag, r in report.items():
    print(f"\n{tag}  ({r['sec_tb']}s/trang)")
    for p in r["pages"]:
        print(f"   {p['page']:18s} {p['sec']:.2f}s  {p['counts']}")
