"""Dieu kien D1 — do CER cua manga-ocr ban ONNX int8 so voi ban PyTorch fp32.

Khong tu convert: onnx-community/manga-ocr-base-ONNX da co san ban int8,
Apache-2.0, khong khoa. Da kiem bang HF API.

Pham vi ket luan:
  DO duoc     : int8 mat bao nhieu so voi fp32
  KHONG do    : manga-ocr doc dung bao nhieu so voi chu THAT tren trang
                (can nguoi go tay — ghi ro la CHUA lam)
"""
import json, sys, time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
from huggingface_hub import hf_hub_download
from transformers import AutoTokenizer, ViTImageProcessor

sys.path.insert(0, str(Path(__file__).parent))
from detect import BubbleDetector, reading_order

REPO = "onnx-community/manga-ocr-base-ONNX"
BASE = "kha-white/manga-ocr-base"
SRC = Path(r"D:\Auto translate android\Support test file\japanese page")
OUT = Path("out")
MODELS = Path("models")
MAX_LEN = 300
MIN_CONTAINED = 0.9


def fetch(variant: str):
    """variant: '' cho fp32, '_int8', '_quantized'..."""
    enc = hf_hub_download(REPO, f"onnx/encoder_model{variant}.onnx", local_dir=str(MODELS))
    dec = hf_hub_download(REPO, f"onnx/decoder_model{variant}.onnx", local_dir=str(MODELS))
    return enc, dec


class OnnxMangaOcr:
    """Chay tay vong lap sinh token — encoder mot lan, decoder lap tung buoc."""

    def __init__(self, enc_path, dec_path, tok, proc):
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.enc = ort.InferenceSession(enc_path, so, providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(dec_path, so, providers=["CPUExecutionProvider"])
        self.tok, self.proc = tok, proc
        self.dec_inputs = {i.name for i in self.dec.get_inputs()}

    def __call__(self, img: Image.Image) -> str:
        px = self.proc(img, return_tensors="np").pixel_values.astype(np.float32)
        hidden = self.enc.run(None, {"pixel_values": px})[0]

        ids = [self.tok.cls_token_id or self.tok.bos_token_id or 2]
        eos = self.tok.sep_token_id or self.tok.eos_token_id
        for _ in range(MAX_LEN):
            feed = {"input_ids": np.array([ids], dtype=np.int64),
                    "encoder_hidden_states": hidden}
            if "encoder_attention_mask" in self.dec_inputs:
                feed["encoder_attention_mask"] = np.ones(hidden.shape[:2], dtype=np.int64)
            logits = self.dec.run(None, feed)[0]
            nxt = int(np.argmax(logits[0, -1]))
            if nxt == eos:
                break
            ids.append(nxt)
        return self.tok.decode(ids, skip_special_tokens=True).replace(" ", "")


def cer(ref: str, hyp: str) -> float:
    if not ref:
        return 0.0 if not hyp else 1.0
    prev = list(range(len(hyp) + 1))
    for i, rc in enumerate(ref, 1):
        cur = [i]
        for j, hc in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rc != hc)))
        prev = cur
    return prev[-1] / len(ref)


def load_crops(ref_json):
    det_pages = {}
    out = []
    for r in ref_json:
        p = SRC / r["page"]
        if r["page"] not in det_pages:
            det_pages[r["page"]] = Image.open(p).convert("RGB")
        x1, y1, x2, y2 = r["box"]
        out.append(det_pages[r["page"]].crop((x1, y1, x2, y2)))
    return out


def main():
    ref_json = json.loads((OUT / "r1_reference.json").read_text(encoding="utf-8"))
    print(f"[ref] {len(ref_json)} vung tham chieu (manga-ocr PyTorch fp32)", flush=True)
    crops = load_crops(ref_json)
    refs = [r["text"] for r in ref_json]

    # tokenizer_type BAT BUOC: transformers moi nhan nham lop tokenizer cho
    # config VisionEncoderDecoder roi roi vao backend fast-only khong tuong thich.
    # Chinh manga_ocr cung phai va cho nay.
    tok = AutoTokenizer.from_pretrained(BASE, tokenizer_type="bert-japanese")
    proc = ViTImageProcessor.from_pretrained(BASE)

    results = {}
    for variant, label in [("", "onnx fp32"), ("_int8", "onnx int8")]:
        print(f"\n[{label}] tai model...", flush=True)
        enc, dec = fetch(variant)
        size_mb = (Path(enc).stat().st_size + Path(dec).stat().st_size) / 1e6
        m = OnnxMangaOcr(enc, dec, tok, proc)

        print(f"[{label}] chay {len(crops)} vung ({size_mb:.0f} MB)...", flush=True)
        hyps, t0 = [], time.time()
        for i, c in enumerate(crops):
            hyps.append(m(c))
            if (i + 1) % 25 == 0:
                print(f"    {i+1}/{len(crops)}", flush=True)
        dt = time.time() - t0

        cers = [cer(r, h) for r, h in zip(refs, hyps)]
        exact = sum(1 for r, h in zip(refs, hyps) if r == h)
        results[label] = {
            "sizeMb": round(size_mb, 1),
            "secPerCrop": round(dt / len(crops), 3),
            "cerMean": round(float(np.mean(cers)), 4),
            "cerMedian": round(float(np.median(cers)), 4),
            "exactMatch": exact,
            "total": len(crops),
            "hyps": hyps,
        }
        print(f"[{label}] CER trung binh {np.mean(cers):.1%} | "
              f"khop tuyet doi {exact}/{len(crops)} | {dt/len(crops):.3f}s/vung", flush=True)

    (OUT / "r1_cer.json").write_text(
        json.dumps({"refs": refs, "results": results}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    print("\n[done] out/r1_cer.json", flush=True)


if __name__ == "__main__":
    main()
