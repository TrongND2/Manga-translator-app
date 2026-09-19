"""Pipeline day du cho Phase 0: detect -> crop text_bubble -> OCR -> dich 1 lan goi.

Day la ban chay tren PC cua dung kien truc se lam tren Android.
Diem mau chot: TOAN BO bubble cua mot trang di vao MOT lan goi LLM.
"""
import json, sys, time, os, urllib.request
from pathlib import Path
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent.parent / "r1_ocr"))
from detect import BubbleDetector, reading_order
from prompt import build

OLLAMA = os.path.expandvars(r"%LOCALAPPDATA%\Programs\Ollama\ollama.exe")


def ocr_page(img: Image.Image, dets, mocr, pad: int = 2):
    """Chi OCR vung duoc gan nhan text_bubble / text_free - khong quet ca trang."""
    bubbles = []
    for d in dets:
        if d["label"] == "bubble":       # vo bong rong, khong phai vung chu
            continue
        x1, y1, x2, y2 = d["box"]
        crop = img.crop((max(0, x1 - pad), max(0, y1 - pad),
                         min(img.size[0], x2 + pad), min(img.size[1], y2 + pad)))
        txt = mocr(crop).strip()
        if not txt:
            continue
        bubbles.append({"id": len(bubbles), "ja": txt,
                        "box": d["box"], "kind": d["label"], "score": d["score"]})
    return bubbles


def translate(scene: dict, model: str, timeout: int = 1800, temperature: float = 0.3):
    """Goi LLM local qua HTTP API cua ollama. MOT lan goi cho CA trang.

    `temperature` mac dinh GIU NGUYEN 0.3 de moi so lieu Phase 0 da do van so
    duoc voi nhau. Bo do hoi quy (`spike/regress`) truyen 0, vi ly do do duoc:
    chay HAI lan y het o 0.3 thi **78/187 bong tu doi (41,7%)**. Sàn nhieu cao
    the thi khong the doc duoc tin hieu "sua prompt lam doi bao nhieu bong".
    """
    system, user = build(scene)
    payload = {
        "model": model,
        "system": system,
        "prompt": user,
        "stream": False,
        "think": False,
        "format": "json",
        "options": {"temperature": temperature, "num_ctx": 8192},
    }
    req = urllib.request.Request(
        "http://127.0.0.1:11434/api/generate",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    t = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        res = json.loads(r.read().decode("utf-8"))
    wall = time.time() - t

    n_out = res.get("eval_count") or 0
    ns = res.get("eval_duration") or 1
    return {
        "raw": res.get("response", ""),
        "wall_sec": round(wall, 2),
        "out_tokens": n_out,
        "tok_per_sec": round(n_out / (ns / 1e9), 2) if n_out else None,
        "prompt_tokens": res.get("prompt_eval_count"),
        "prompt_sec": round((res.get("prompt_eval_duration") or 0) / 1e9, 2),
    }
