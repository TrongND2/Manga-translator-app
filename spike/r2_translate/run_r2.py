"""R2 + R4: do chat luong dich va toc do that.

Chay tron pipeline tren N trang, voi tung model, ghi ket qua ra file
de cham tay. KHONG tu cham diem chat luong - viec do phai do nguoi lam.
"""
import json, sys, time
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r1_ocr"))

from detect import BubbleDetector, reading_order
from pipeline import ocr_page, translate
from manga_ocr import MangaOcr

SRC = Path(r"D:\Auto translate android\Support test file\japanese page")
OUT = Path("out"); OUT.mkdir(exist_ok=True)

PAGES = ["tubaki_010.jpg", "tubaki_025.jpg", "tubaki_100.jpg", "tubaki_150.jpg"]
MODELS = ["qwen3:4b", "qwen3:1.7b"]

print("[init] nap detector + OCR...", flush=True)
det = BubbleDetector("models/detector-v4-s_int8.onnx")
mocr = MangaOcr()

# --- Buoc 1+2: detect + OCR (lam mot lan, dung chung cho moi model) ---
scenes = []
for name in PAGES:
    img = Image.open(SRC / name).convert("RGB")
    t = time.time(); dets = det(img, conf=0.5); t_det = time.time() - t
    dets = reading_order(dets, img.size[0])
    t = time.time(); bubbles = ocr_page(img, dets, mocr); t_ocr = time.time() - t
    scenes.append({
        "page": name,
        "context": "Truyen co trang Nhat Ban, boi canh khu lau xanh thoi Edo.",
        "glossary": {},
        "bubbles": bubbles,
        "t_detect": round(t_det, 2),
        "t_ocr": round(t_ocr, 2),
    })
    print(f"[ocr] {name}: {len(bubbles)} bubble | detect {t_det:.2f}s | ocr {t_ocr:.2f}s", flush=True)

(OUT / "r2_scenes.json").write_text(
    json.dumps(scenes, ensure_ascii=False, indent=2), encoding="utf-8")

# --- Buoc 3: dich, MOT lan goi moi trang ---
results = {}
for model in MODELS:
    print(f"\n[llm] === {model} ===", flush=True)
    per_page = []
    for sc in scenes:
        if not sc["bubbles"]:
            continue
        try:
            r = translate(sc, model)
        except Exception as e:
            print(f"  {sc['page']}: LOI {type(e).__name__}: {e}", flush=True)
            per_page.append({"page": sc["page"], "error": str(e)})
            continue

        try:
            parsed = json.loads(r["raw"])
            n_got = len(parsed.get("bubbles", []))
        except Exception:
            parsed, n_got = None, -1

        rec = {
            "page": sc["page"],
            "n_bubble_vao": len(sc["bubbles"]),
            "n_bubble_ra": n_got,
            "json_hop_le": parsed is not None,
            "wall_sec": r["wall_sec"],
            "tok_per_sec": r["tok_per_sec"],
            "out_tokens": r["out_tokens"],
            "prompt_tokens": r["prompt_tokens"],
            "prompt_sec": r["prompt_sec"],
            "t_detect": sc["t_detect"],
            "t_ocr": sc["t_ocr"],
            "tong_pipeline_sec": round(sc["t_detect"] + sc["t_ocr"] + r["wall_sec"], 2),
            "ket_qua": parsed,
            "raw_neu_hong": None if parsed else r["raw"][:2000],
        }
        per_page.append(rec)
        print(f"  {sc['page']}: {r['wall_sec']}s | {r['tok_per_sec']} tok/s | "
              f"{len(sc['bubbles'])}->{n_got} bubble | json={parsed is not None}", flush=True)
    results[model] = per_page

(OUT / "r2_results.json").write_text(
    json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
print("\n[done] out/r2_results.json", flush=True)
