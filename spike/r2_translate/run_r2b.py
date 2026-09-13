"""R2 vong 2: loai tru loi cua chinh phep test truoc khi ket luan R2 that bai.

Vong 1 co 2 diem co the tu lam hong ket qua:
  - think=False  -> tat che do suy luan cua Qwen3 (model hybrid reasoning)
  - glossary rong -> ten nhan vat (瑠璃丸) bi dich thanh cau vo nghia

Vong 2 bat think va dua glossary + boi canh cu the, chay lai DUNG trang do.
"""
import json, sys, time, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
from prompt import build

PAGE = "tubaki_010.jpg"
MODEL = "qwen3:4b"

scenes = {s["page"]: s for s in json.loads(
    Path("out/r2_scenes.json").read_text(encoding="utf-8"))}
base = scenes[PAGE]

GLOSSARY = {
    "瑠璃丸 (Rurimaru)": "ten nhan vat nam, giu nguyen 'Rurimaru'. Nguoi khac goi than mat",
    "先生 (sensei)": "dich la 'tien sinh' - cach goi trong truyen co trang, khong phai 'thay giao'",
    "女郎 (joro)": "ky nu / gai lau xanh thoi Edo",
}
CONTEXT = (
    "Truyen tranh co trang Nhat Ban, boi canh khu lau xanh (yukaku) thoi Edo. "
    "Canh nay: mot nhom dan ong dang treu choc mot nguoi dan ong khac (Rurimaru) "
    "vi anh ta tu choi ky nu hang sang va dinh bo di. Thoai tho tuc, khau ngu manh. "
    "Noi dung nguoi lon - dich dung nghia, khong lam nhe di, khong ne tranh."
)


def call(model, scene, think):
    system, user = build(scene)
    payload = {
        "model": model, "system": system, "prompt": user,
        "stream": False, "think": think, "format": "json",
        "options": {"temperature": 0.3, "num_ctx": 8192},
    }
    req = urllib.request.Request(
        "http://127.0.0.1:11434/api/generate",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=3600) as r:
        res = json.loads(r.read().decode("utf-8"))
    n = res.get("eval_count") or 0
    return {
        "wall": round(time.time() - t, 1),
        "tok_s": round(n / ((res.get("eval_duration") or 1) / 1e9), 1),
        "out_tok": n,
        "think_len": len(res.get("thinking") or ""),
        "raw": res.get("response", ""),
    }


runs = {
    "A_goc_khong_think_khong_glossary": (dict(base), False),
    "B_them_glossary_khong_think": (dict(base, glossary=GLOSSARY, context=CONTEXT), False),
    "C_them_glossary_CO_think": (dict(base, glossary=GLOSSARY, context=CONTEXT), True),
}

out = {}
for tag, (scene, think) in runs.items():
    print(f"[run] {tag} ...", flush=True)
    r = call(MODEL, scene, think)
    try:
        r["parsed"] = json.loads(r["raw"])
    except Exception:
        r["parsed"] = None
    out[tag] = r
    print(f"   {r['wall']}s | {r['tok_s']} tok/s | out {r['out_tok']} tok | "
          f"think {r['think_len']} ky tu | json={r['parsed'] is not None}", flush=True)

Path("out/r2b_results.json").write_text(
    json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print("[done] out/r2b_results.json", flush=True)
