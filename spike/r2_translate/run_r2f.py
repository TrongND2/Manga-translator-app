"""R2 vong 5 - phep do ket luan.

Vong 1-4 deu chi do 1 trang / 12 bubble. Qua it de ket luan.
Vong nay: cau hinh tot nhat tim duoc, chay tren CA 4 trang (~48 bubble),
de con so R2 co y nghia.

Cau hinh tot nhat theo do luong:
  model    = gemma3:4b   (thang qwen3:4b va bang qwen3:8b, F8)
  prompt   = v1          (v2 lam qwen3 te di va lam gemma3 bi cat cut)
  glossary = mo rong co thanh ngu/tieng long (F8)

Cung chay lai qwen3:4b lam moc doi chieu tren cung 4 trang.
num_predict nang len de khong bi cat cut JSON.
"""
import json, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from prompt import build
from fixtures import GLOSSARY, CONTEXT

MODELS = ["gemma4:e2b", "gemma3:4b"]

scenes = json.loads(Path("out/r2_scenes.json").read_text(encoding="utf-8"))


def call(model, scene):
    system, user = build(scene)
    payload = {"model": model, "system": system, "prompt": user,
               "stream": False, "think": False, "format": "json",
               "options": {"temperature": 0.3, "num_ctx": 8192,
                           "num_predict": 2048}}
    req = urllib.request.Request(
        "http://127.0.0.1:11434/api/generate",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=3600) as r:
        res = json.loads(r.read().decode("utf-8"))
    n = res.get("eval_count") or 0
    return {"wall": round(time.time() - t, 1),
            "tok_s": round(n / ((res.get("eval_duration") or 1) / 1e9), 1),
            "out_tok": n, "done_reason": res.get("done_reason"),
            "raw": res.get("response", "")}


out = {}
for model in MODELS:
    print(f"\n[{model}]", flush=True)
    per_page = []
    for s in scenes:
        sc = dict(s, glossary=GLOSSARY, context=CONTEXT)
        try:
            r = call(model, sc)
        except Exception as e:
            print(f"  {s['page']}: LOI {e}", flush=True)
            continue
        try:
            r["parsed"] = json.loads(r["raw"]); n = len(r["parsed"]["bubbles"])
        except Exception:
            r["parsed"], n = None, -1
        r["page"] = s["page"]
        r["n_vao"] = len(s["bubbles"])
        r["ja"] = {b["id"]: b["ja"] for b in s["bubbles"]}
        r["tong_pipeline"] = round(s["t_detect"] + s["t_ocr"] + r["wall"], 1)
        per_page.append(r)
        print(f"  {s['page']}: {r['wall']}s (pipeline {r['tong_pipeline']}s) | "
              f"{r['tok_s']} tok/s | {r['n_vao']}->{n} | json={r['parsed'] is not None} "
              f"| stop={r['done_reason']}", flush=True)
    out[model] = per_page

Path("out/r2f_results.json").write_text(
    json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print("\n[done] out/r2f_results.json", flush=True)
