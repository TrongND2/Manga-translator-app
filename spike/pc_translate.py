"""Do toc do va chat luong dich tren PC, de so voi dien thoai.

Muc dich: tra loi cau hoi "dung ROG Ally lam may dich qua Wi-Fi co dang khong"
TRUOC khi viet mot dong code app nao.

Phep do phai cong bang, nen prompt duoc dung **y het** cach app dung: doc thang
SYSTEM tu file Kotlin dang chay, khong go lai bang tay (go lai la lech, ma lech
thi phep do vo nghia).

    python spike/pc_translate.py <model> [so_bong]
"""
import io
import json
import os
import re
import sys
import time
import urllib.request

KT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "android", "app", "src", "main", "kotlin", "app", "mangatrans",
    "adapters", "litertlm", "LiteRtLmTranslator.kt",
)

# Trang 13 bong da dich tren may that luc 20:28 ngay 17/9 — giu lai lam moc doi
# chieu. Chu Nhat la DAU RA CUA OCR tren may, khong phai chep tu anh goc: phai
# cho PC doc dung nhung gi dien thoai doc duoc thi moi so duoc chat luong dich.
BUBBLES = [
    u"\u80f8\u2026\u30c3",
    u"\u7f8e\u6708!\u30a2\u30ba\u306e\u30d1\u30d1\u3060\u3063\u3064-\u306e\u30c3",
    u"\u2026\u3068\u3044\u3046\u30ef\u30b1\u306a\u3093\u3060\u3051\u3069",
    u"\u5a18\u3055\u3093\u3072\u3069\u301c\u3044",
    u"\u79c1\u304c\u6170\u3081\u3066\u3042\u3052\u3088\u3063\u304b?",
    u"\u305d\u308c\u3058\u3083\u3044\u3053\u3063\u304b\u30d1\u30d1",
    u"\u4eca\u65e5\u3082\u30a2\u30ba\u304c\u4e00\u7dd2\u306b\u3044\u3066\u3042\u3052\u308b\u304b\u3089\u306d",
    u"\u4eca\u65e5\u306f\u674f\u5f7c\u6c0f\u3068\u4f1a\u3046\u3063\u3066\u8a00\u3063\u3066\u306a\u304b\u3063\u305f?",
    u"\u5f7c\u6c0f\u3088\u308a\u30d1\u30d1\u306e\u65b9\u304c\u30a2\u30ba\u3092\u672c\u6c17\u3067\u60f3\u3063\u3066\u304f\u308c\u3066\u308b\u3082\u3093",
    u"\u79c1\u3082\u3064\u3044\u3066\u3053-\u3063\u3068",
    u"\u306a\u3093\u3067\u3088",
    u"\u674f\u3063\u3066\u3070\u3042\u306e\u30d1\u30d1\u306b\u3081\u3063\u3061\u3083\u5165\u308c\u8fbc\u3093\u3067\u308b\u3058\u3083\u3093",
    u"\u5f85\u3061\u5408\u308f\u305b\u307e\u3067\u306e\u6642\u9593\u3064\u3076\u3057\u301c",
]

# Ban dich cua Gemma 4 E2B chay tren M52, cung trang, de so ben canh.
ON_PHONE = [
    u"Ng\u1ef1c...\u30c3",
    u"Mitsu! B\u1ed1 c\u1ee7a Az!",
    u"...l\u00e0 chuy\u1ec7n nh\u01b0 v\u1eady",
    u"Con g\u00e1i th\u1eadt t\u1ec7\u301c",
    u"T\u00f4i an \u1ee7i em \u0111\u01b0\u1ee3c kh\u00f4ng?",
    u"V\u1eady th\u00ec \u0111i \u0111\u00e2y b\u1ed1",
    u"H\u00f4m nay Az s\u1ebd \u1edf b\u00ean c\u1ea1nh con nh\u00e9",
    u"H\u00f4m nay c\u1eadu n\u00f3i s\u1ebd g\u1eb7p b\u1ea1n trai c\u1ee7a An ch\u1ee9?",
    u"B\u1ed1 c\u00f2n y\u00eau Az nghi\u00eam t\u00fac h\u01a1n b\u1ea1n trai",
    u"T\u00f4i c\u0169ng s\u1ebd \u0111i theo",
    u"Sao th\u1ebf?",
    u"An th\u00ec \u0111\u00e3 d\u00ednh v\u00e0o b\u1ed1 \u0111\u00f3 r\u1ed3i",
    u"D\u00f9ng th\u1eddi gian ch\u1edd \u0111\u1ee3i \u0111\u1ec3 gi\u1ebft th\u1eddi gian\u301c",
]


def system_prompt():
    t = io.open(KT, encoding="utf-8").read()
    m = re.search(r'private const val SYSTEM = """(.*?)"""', t, re.S)
    if not m:
        raise SystemExit("khong tim thay SYSTEM trong " + KT)
    return m.group(1)


def build(bubbles):
    """Dung y het `buildPrompt` cua app, glossary de trong."""
    lines = u"\n".join(u"[%d] %s" % (i, b) for i, b in enumerate(bubbles))
    n = len(bubbles)
    return (
        system_prompt()
        + u"\n\n## Nh\u00e2n v\u1eadt v\u00e0 thu\u1eadt ng\u1eef (glossary)\n- (tr\u1ed1ng)\n\n"
        + u"## Bong b\u00f3ng tho\u1ea1i\n"
        + u"\u0110\u00e3 s\u1eafp theo th\u1ee9 t\u1ef1 \u0111\u1ecdc manga (ph\u1ea3i\u2192tr\u00e1i, "
          u"tr\u00ean\u2192d\u01b0\u1edbi). Bubble li\u1ec1n nhau th\u01b0\u1eddng l\u00e0 "
          u"m\u1ed9t m\u1ea1ch h\u1ed9i tho\u1ea1i.\n\n"
        + lines
        + u"\n\n## Y\u00eau c\u1ea7u\nD\u1ecbch \u0111\u1ee7 %d bubble. " % n
        + u"Tr\u1ea3 v\u1ec1 JSON, c\u00e1c tr\u01b0\u1eddng theo \u0110\u00daNG th\u1ee9 t\u1ef1 n\u00e0y:\n"
        + u'{"bubbles":[{"id":<s\u1ed1>,"jaEcho":"<2 k\u00fd t\u1ef1 \u0111\u1ea7u c\u1ee7a nguy\u00ean '
          u'b\u1ea3n>","vi":"<b\u1ea3n d\u1ecbch>","speaker":"<t\u00ean ho\u1eb7c ?>"}]}\n\n'
        + u"`jaEcho` l\u00e0 m\u00e3 \u0111\u1ed1i chi\u1ebfu: ch\u00e9p y nguy\u00ean 2 k\u00fd t\u1ef1 "
          u"\u0110\u1ea6U TI\u00caN c\u1ee7a nguy\u00ean b\u1ea3n ti\u1ebfng Nh\u1eadt c\u1ee7a "
          u"ch\u00ednh bubble \u0111\u00f3. Kh\u00f4ng d\u1ecbch, kh\u00f4ng s\u1eeda."
    )


def run(model, prompt):
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": True,
        "options": {"temperature": 0.0},
    }
    # Qwen3 mac dinh BAT che do "suy nghi": no sinh ca ngan token nhap nhap
    # truoc khi tra loi. Voi viec dich thi do la thoi gian vut di — tat han.
    if "qwen3" in model:
        payload["think"] = False
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        "http://127.0.0.1:11434/api/generate",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    t0 = time.time()
    first = None
    out = []
    meta = {}
    with urllib.request.urlopen(req, timeout=900) as r:
        for raw in r:
            if not raw.strip():
                continue
            d = json.loads(raw.decode("utf-8"))
            if d.get("response"):
                if first is None:
                    first = time.time()
                out.append(d["response"])
            if d.get("done"):
                meta = d
                break
    return u"".join(out), (first - t0 if first else -1), time.time() - t0, meta


def report(lines):
    """Ghi ra file UTF-8. Console Windows o may nay la cp932, in thang la vo."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "pc.txt")
    try:
        os.makedirs(os.path.dirname(path))
    except OSError:
        pass
    io.open(path, "w", encoding="utf-8").write(u"\n".join(lines))
    return path


def main():
    model = sys.argv[1] if len(sys.argv) > 1 else "gemma4:e2b"
    n = int(sys.argv[2]) if len(sys.argv) > 2 else len(BUBBLES)
    bubbles = BUBBLES[:n]
    prompt = build(bubbles)

    L = []
    L.append(u"model            : %s" % model)
    L.append(u"prompt           : %d ky tu | %d bong" % (len(prompt), len(bubbles)))
    text, ttft, total, meta = run(model, prompt)
    ns = 1e9
    # Tach bach ba khoan: NAP mo hinh (o day chinh la phan doc dia / swap),
    # DOC prompt, va SINH chu. Khong tach thi khong biet cham o dau.
    L.append(u"nap mo hinh      : %.2f s" % (meta.get("load_duration", 0) / ns))
    L.append(u"doc prompt       : %.2f s  (%d token)"
             % (meta.get("prompt_eval_duration", 0) / ns, meta.get("prompt_eval_count", 0)))
    L.append(u"sinh chu         : %.2f s  (%d token)"
             % (meta.get("eval_duration", 0) / ns, meta.get("eval_count", 0)))
    ev = meta.get("eval_duration", 0) / ns
    if ev > 0:
        L.append(u"toc do sinh      : %.1f token/s" % (meta.get("eval_count", 0) / ev))
    L.append(u"chu dau tien sau : %.2f s" % ttft)
    L.append(u"xong ca trang sau: %.2f s" % total)

    m = re.search(r'\{.*\}', text, re.S)
    got = []
    if m:
        try:
            # `strict=False` de chap nhan ky tu xuong dong tho ben trong chuoi —
            # mo hinh hay sinh kieu do, va o day ta can NHIN ban dich chu khong
            # phai cham diem do dung JSON.
            got = json.loads(m.group(0), strict=False).get("bubbles", [])
        except Exception as e:
            L.append(u"JSON hong: %s" % e)
    L.append(u"so bong tra ve   : %d / %d" % (len(got), len(bubbles)))
    L.append(u"")
    by_id = {b.get("id"): b for b in got if isinstance(b, dict)}
    for i, ja in enumerate(bubbles):
        pc = (by_id.get(i) or {}).get("vi", u"(khong co)")
        L.append(u"[%d] %s" % (i, ja))
        L.append(u"    may : %s" % (ON_PHONE[i] if i < len(ON_PHONE) else u"-"))
        L.append(u"    PC  : %s" % pc)
    L.append(u"")
    L.append(u"--- DAU RA THO ---")
    L.append(text)
    L.append(u"--- het dau ra tho ---")
    if not m:
        L.append(u"")
        L.append(u"--- dau ra tho (300 ky tu dau) ---")
        L.append(text[:300])
    path = report(L)
    print("da ghi: " + path)
    for x in L[:9]:
        print(x.encode("ascii", "replace").decode("ascii"))


if __name__ == "__main__":
    main()
