"""R2 vong 3: chat luong co tang theo co model khong?

Cung mot trang, cung glossary, cung prompt. Chi doi model.
qwen3:8b khong nhet vua dien thoai - no o day de tra loi cau hoi CHAN DOAN:
neu 8b cung truot thi van de nam o du lieu/nhiem vu, khong phai o co model.
"""
import json, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from prompt import build
from fixtures import GLOSSARY, CONTEXT, PAGE

MODELS = ["qwen3:1.7b", "qwen3:4b", "gemma3:4b", "qwen3:8b"]

scenes = {s["page"]: s for s in json.loads(
    Path("out/r2_scenes.json").read_text(encoding="utf-8"))}
scene = dict(scenes[PAGE], glossary=GLOSSARY, context=CONTEXT)


def call(model, scene):
    system, user = build(scene)
    payload = {"model": model, "system": system, "prompt": user,
               "stream": False, "think": False, "format": "json",
               "options": {"temperature": 0.3, "num_ctx": 8192}}
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
            "raw": res.get("response", "")}


out = {}
for m in MODELS:
    print(f"[run] {m} ...", flush=True)
    try:
        r = call(m, scene)
    except Exception as e:
        print(f"   LOI {type(e).__name__}: {e}", flush=True)
        out[m] = {"error": str(e)}
        continue
    try:
        r["parsed"] = json.loads(r["raw"])
        n = len(r["parsed"].get("bubbles", []))
    except Exception:
        r["parsed"], n = None, -1
    out[m] = r
    print(f"   {r['wall']}s | {r['tok_s']} tok/s | {n} bubble | json={r['parsed'] is not None}",
          flush=True)

Path("out/r2c_results.json").write_text(
    json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print("[done] out/r2c_results.json", flush=True)
