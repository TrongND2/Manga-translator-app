from huggingface_hub import hf_hub_download
import shutil, json
from pathlib import Path

REPO = "ogkalu/comic-text-and-bubble-detector"
dst = Path("models"); dst.mkdir(exist_ok=True)
for f in ["config.json", "preprocessor_config.json",
          "detector-v4-s_int8.onnx", "detector_int8.onnx"]:
    p = hf_hub_download(repo_id=REPO, filename=f)
    shutil.copy(p, dst / f)
    print(f"{f}  {(dst/f).stat().st_size/1e6:.1f} MB", flush=True)

cfg = json.loads((dst/"config.json").read_text(encoding="utf-8"))
print("id2label:", cfg.get("id2label"))
print("preproc:", (dst/"preprocessor_config.json").read_text(encoding="utf-8")[:600])
