"""Buoc 1 cua pipeline: phat hien bubble / vung chu.

Model: ogkalu/comic-text-and-bubble-detector (RT-DETR-v2, Apache-2.0).
3 lop: 0=bubble (vo bong thoai) · 1=text_bubble (chu TRONG bong) · 2=text_free (chu ngoai bong).

Lop text_bubble chinh la thu tra loi cho F2: chi cat vung duoc nhan
text_bubble moi dua sang OCR, thay vi doan mo mam tren ca trang.
"""
import numpy as np
import onnxruntime as ort
from PIL import Image

LABELS = {0: "bubble", 1: "text_bubble", 2: "text_free"}


class BubbleDetector:
    def __init__(self, model_path: str, size: int = 640):
        self.size = size
        self.sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])

    def __call__(self, img: Image.Image, conf: float = 0.5):
        w, h = img.size
        # preprocessor_config.json: do_resize 640x640, do_rescale 1/255, do_normalize FALSE
        resized = img.convert("RGB").resize((self.size, self.size), Image.BILINEAR)
        x = np.asarray(resized, dtype=np.float32) / 255.0
        x = x.transpose(2, 0, 1)[None]                      # NCHW
        sizes = np.array([[w, h]], dtype=np.int64)          # orig_target_sizes

        labels, boxes, scores = self.sess.run(
            None, {"images": x, "orig_target_sizes": sizes}
        )
        labels, boxes, scores = labels[0], boxes[0], scores[0]

        out = []
        for lab, box, sc in zip(labels, boxes, scores):
            if sc < conf:
                continue
            x1, y1, x2, y2 = [float(v) for v in box]
            x1, x2 = sorted((max(0.0, x1), min(float(w), x2)))
            y1, y2 = sorted((max(0.0, y1), min(float(h), y2)))
            if x2 - x1 < 4 or y2 - y1 < 4:
                continue
            out.append({
                "label": LABELS.get(int(lab), str(lab)),
                "score": round(float(sc), 3),
                "box": [round(x1), round(y1), round(x2), round(y2)],
            })
        return out


def reading_order(dets, page_w: int, row_tol_ratio: float = 0.06):
    """Sap xep theo thu tu doc manga: PHAI -> TRAI, TREN -> DUOI.

    Gom cac box co tam y gan nhau thanh mot 'hang', trong hang sap
    theo x giam dan. row_tol tinh theo chieu rong trang de khong phu
    thuoc do phan giai.
    """
    if not dets:
        return []
    tol = page_w * row_tol_ratio
    items = sorted(dets, key=lambda d: (d["box"][1] + d["box"][3]) / 2)

    rows, cur = [], [items[0]]
    cur_y = (items[0]["box"][1] + items[0]["box"][3]) / 2
    for d in items[1:]:
        cy = (d["box"][1] + d["box"][3]) / 2
        if abs(cy - cur_y) <= tol:
            cur.append(d)
        else:
            rows.append(cur)
            cur, cur_y = [d], cy
    rows.append(cur)

    ordered = []
    for row in rows:
        ordered.extend(sorted(row, key=lambda d: -(d["box"][0] + d["box"][2]) / 2))
    for i, d in enumerate(ordered):
        d["id"] = i
    return ordered
