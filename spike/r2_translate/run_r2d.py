"""R2 vong 4: prompt v2 + glossary thanh ngu.

Doi DUNG MOT bien so voi vong 3: cach dat bai toan.
Cung model, cung trang, cung trang thai OCR.

v1 = prompt goc, glossary chi ten rieng
v2 = prompt co buoc 'literal' trong JSON + glossary co thanh ngu/tieng long
"""
import json, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import prompt as p_v1
import prompt_v2 as p_v2
from fixtures import GLOSSARY, CONTEXT, PAGE

MODELS = ["qwen3:4b", "gemma3:4b"]

scenes = {s["page"]: s for s in json.loads(
    Path("out/r2_scenes.json").read_text(encoding="utf-8"))}
scene = dict(scenes[PAGE], glossary=GLOSSARY, context=CONTEXT)


def call(model, builder, scene):
    system, user = builder(scene)
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
            "out_tok": n, "raw": res.get("response", "")}


out = {}
for model in MODELS:
    for tag, builder in [("v1", p_v1.build), ("v2", p_v2.build)]:
        key = f"{model}__{tag}"
        print(f"[run] {key} ...", flush=True)
        try:
            r = call(model, builder, scene)
        except Exception as e:
            print(f"   LOI {type(e).__name__}: {e}", flush=True)
            out[key] = {"error": str(e)}
            continue
        try:
            r["parsed"] = json.loads(r["raw"])
            n = len(r["parsed"].get("bubbles", []))
        except Exception:
            r["parsed"], n = None, -1
        out[key] = r
        print(f"   {r['wall']}s | {r['tok_s']} tok/s | {r['out_tok']} tok ra | "
              f"{n} bubble | json={r['parsed'] is not None}", flush=True)

Path("out/r2d_results.json").write_text(
    json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print("[done] out/r2d_results.json", flush=True)
