"""Tai Gemma 4 E2B bang huggingface_hub (tai song song nhieu luong qua Xet).
Nhanh hon Invoke-WebRequest don luong dang ke, va TU RESUME neu dut giua chung."""
import time
from pathlib import Path
from huggingface_hub import hf_hub_download

REPO = "litert-community/gemma-4-E2B-it-litert-lm"
DST = Path("models"); DST.mkdir(exist_ok=True)

for f in ["gemma-4-E2B-it-gpu.litertlm", "gemma-4-E2B-it.litertlm"]:
    out = DST / f
    if out.exists():
        print(f"da co: {f}", flush=True); continue
    print(f"tai {f} ...", flush=True)
    t = time.time()
    p = hf_hub_download(repo_id=REPO, filename=f, local_dir=str(DST))
    dt = time.time() - t
    gb = Path(p).stat().st_size / 1e9
    print(f"xong {f}: {gb:.2f} GB trong {dt/60:.1f} phut ({gb*1000/dt:.1f} MB/s)", flush=True)

print("=== tat ca ===", flush=True)
for p in sorted(DST.glob("*.litertlm")):
    print(f"{p.name:45s} {p.stat().st_size/1e9:6.2f} GB", flush=True)
