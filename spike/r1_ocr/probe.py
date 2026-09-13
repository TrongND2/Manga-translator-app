import json
from pathlib import Path
from collections import Counter
from PIL import Image

src = Path(r"D:\Auto translate android\Support test file\japanese page")
pages = sorted(src.glob("tubaki_*.jpg"))
dims = []
for p in pages:
    with Image.open(p) as im:
        dims.append((p.name, im.size[0], im.size[1]))

shapes = Counter()
for n, w, h in dims:
    ar = w / h
    if ar > 3:   shapes["banner ngang (AR>3)"] += 1
    elif ar > 1.2: shapes["spread 2 trang (1.2<AR<=3)"] += 1
    elif ar > 0.8: shapes["gan vuong"] += 1
    else:        shapes["trang don doc (AR<=0.8)"] += 1

out = {
    "tong": len(dims),
    "phan_loai": dict(shapes),
    "10_dau": dims[:10],
    "lon_nhat": max(dims, key=lambda d: d[1]*d[2]),
    "nho_nhat": min(dims, key=lambda d: d[1]*d[2]),
}
Path("out/probe_dims.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(out, ensure_ascii=False, indent=2))
