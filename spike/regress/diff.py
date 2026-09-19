"""So hai moc: sua prompt / tu dien xong thi DOI BAO NHIEU BONG?

    python spike/regress/diff.py baseline thu-nghiem

In ra man hinh CHI SO DEM. Chi tiet tung bong ghi ra file rieng
(`spike/out/regress/diff-<a>-vs-<b>.txt`) -- vi hai ly do: noi dung bong la
chu cua truyen, va doc 200 dong tren terminal thi khong ai doc.

Vi sao con so nay quan trong: mo hinh TAT DINH nhung ca trang dinh chum. Doi
mot cum chu trong prompt co the lam doi CA trang. Nen "doi 1 bong" va "doi 87
bong" la hai chuyen khac han, ma truoc khi co cong cu nay thi khong ai phan
biet duoc -- chi nhin duoc mot trang bang mat.

⚠️ Cot `ja doi` la cot phai xem TRUOC. `ja` doi nghia la detect/OCR ra khac,
tuc hai lan chay khong con so duoc voi nhau o nhung bong do; thay doi o `vi`
cua chung KHONG phai do prompt. Sua prompt thi cot nay phai bang 0.
"""
import io
import json
import sys
from pathlib import Path

# Xem ghi chu cung loai trong `run.py`: console cp932 nem loi khi gap ky tu
# ngoai bang ma. `diff.py` con in ca chu Nhat neu ai do bo bot phan ghi file,
# nen cang phai ep.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

OUT_DIR = Path(__file__).resolve().parent.parent.parent / "spike" / "out" / "regress"


def load(name):
    p = OUT_DIR / ("%s.json" % name)
    if not p.exists():
        sys.exit("khong thay moc '%s' o %s" % (name, p))
    return json.loads(io.open(p, encoding="utf-8").read())


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    a_name, b_name = sys.argv[1], sys.argv[2]
    a, b = load(a_name), load(b_name)

    chung = [p for p in a if p in b]
    chi_a = [p for p in a if p not in b]
    chi_b = [p for p in b if p not in a]

    tong_bong = 0
    doi_vi = 0
    doi_ja = 0
    mat_ban_dich = 0
    them_ban_dich = 0
    per_page = []
    lines = ["so moc '%s' -> '%s'" % (a_name, b_name), ""]

    for page in chung:
        ba = {x["id"]: x for x in a[page]["bubbles"]}
        bb = {x["id"]: x for x in b[page]["bubbles"]}
        ids = sorted(set(ba) | set(bb))
        n_doi = n_ja = 0
        lines.append("=== %s ===" % page)
        for i in ids:
            x, y = ba.get(i), bb.get(i)
            if x is None or y is None:
                lines.append("  [%s] CHI CO O MOT BEN" % i)
                continue
            tong_bong += 1
            if x["ja"] != y["ja"]:
                n_ja += 1
                doi_ja += 1
                lines.append("  [%s] JA DOI -- khong so duoc" % i)
                continue
            if x["vi"] != y["vi"]:
                n_doi += 1
                doi_vi += 1
                if x["vi"].strip() and not y["vi"].strip():
                    mat_ban_dich += 1
                elif not x["vi"].strip() and y["vi"].strip():
                    them_ban_dich += 1
                lines.append("  [%s] JA: %s" % (i, x["ja"]))
                lines.append("       %s: %s" % (a_name, x["vi"]))
                lines.append("       %s: %s" % (b_name, y["vi"]))
        per_page.append((page, len(ids), n_doi, n_ja))
        lines.append("")

    print("trang so duoc : %d" % len(chung))
    if chi_a:
        print("chi co o '%s': %s" % (a_name, ", ".join(chi_a)))
    if chi_b:
        print("chi co o '%s': %s" % (b_name, ", ".join(chi_b)))
    print("")
    print("%-22s %6s %8s %8s" % ("trang", "bong", "vi doi", "ja doi"))
    for page, n, d, j in per_page:
        print("%-22s %6d %8d %8d" % (page, n, d, j))
    print("%-22s %6d %8d %8d" % ("TONG", tong_bong, doi_vi, doi_ja))
    print("")
    if tong_bong:
        print("ty le bong doi: %.1f%%" % (100.0 * doi_vi / tong_bong))
    if mat_ban_dich:
        print("!! %d bong CO ban dich o '%s' nhung MAT o '%s'"
              % (mat_ban_dich, a_name, b_name))
    if them_ban_dich:
        print("   %d bong truoc trong, nay co ban dich" % them_ban_dich)
    if doi_ja:
        print("!! %d bong co JA khac nhau -- detect/OCR da doi, khong quy duoc"
              % doi_ja)
        print("   cho prompt. Xem lai truoc khi ket luan gi ve prompt.")

    dest = OUT_DIR / ("diff-%s-vs-%s.txt" % (a_name, b_name))
    io.open(dest, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\nchi tiet tung bong: %s" % dest)


if __name__ == "__main__":
    main()
