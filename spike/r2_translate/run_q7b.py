"""Q7b: cong kiem tra phai bat duoc LECH THAT ma khong bao dong gia.

Vong q7 cho thay so khop chinh xac tung ky tu de bao dong gia:
  何を当て vs 何が当て   (tro tu を/が)
  どうせ無 vs ―どうせ    (dau gach dau cau)

Vong nay:
  1. Chuan hoa truoc khi so (bo dau cau, bo khoang trang).
  2. Cho phep khoang cach sua <= 1 ky tu.
  3. Tiem loi lech co chu dich de do TY LE BAT DUOC THAT.

Khong can goi LLM - thuan xu ly chuoi tren du lieu da co.
"""
import json, unicodedata
from pathlib import Path

PUNCT = "―ー-—…‥。、．，！？!?「」『』（）()〝〟“”\"'  　\n\t．"


def norm(s: str, n: int = 4) -> str:
    s = unicodedata.normalize("NFKC", s or "")
    s = "".join(c for c in s if c not in PUNCT)
    return s[:n]


def edit_le1(a: str, b: str) -> bool:
    """True neu khoang cach sua <= 1. Du cho muc dich nay, khong can Levenshtein day du."""
    if a == b:
        return True
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) <= 1
    lo, hi = (a, b) if len(a) < len(b) else (b, a)
    for i in range(len(hi)):
        if hi[:i] + hi[i + 1:] == lo:
            return True
    return False


def check(truth: dict, answers: list, n=4, fuzzy=True):
    """answers: [{'id':int,'ja4':str}] -> (so khop, danh sach lech)"""
    ok, bad = 0, []
    for a in answers:
        i = a.get("id")
        got, want = norm(a.get("ja4", ""), n), norm(truth.get(i, ""), n)
        match = edit_le1(got, want) if fuzzy else got == want
        if match:
            ok += 1
        else:
            bad.append({"id": i, "got": got, "want": want})
    return ok, bad


q7 = json.loads(Path("out/q7_results.json").read_text(encoding="utf-8"))
scenes = {s["page"]: s for s in json.loads(
    Path("out/r2_scenes.json").read_text(encoding="utf-8"))}
truth = {b["id"]: b["ja"] for b in scenes["tubaki_025.jpg"]["bubbles"]}
answers = q7["co_echo"]["parsed"]["bubbles"]

print("=== 1. Dap an THAT cua model (khong lech) ===")
for tag, fz in [("so khop chinh xac", False), ("chuan hoa + sai <=1", True)]:
    ok, bad = check(truth, answers, fuzzy=fz)
    print(f"  {tag:22s}: khop {ok}/{len(answers)} | bao dong {len(bad)}"
          + ("  <- BAO DONG GIA" if bad and fz is False else ""))
    for b in bad:
        print(f"      id={b['id']} got={b['got']!r} want={b['want']!r}")

print("\n=== 2. Tiem loi LECH MOT O co chu dich (mo phong F10) ===")
shifted = []
for a in answers:
    i = a["id"]
    src = next((x for x in answers if x["id"] == i + 1), None)
    shifted.append({"id": i, "ja4": (src or a)["ja4"]})
for tag, fz in [("so khop chinh xac", False), ("chuan hoa + sai <=1", True)]:
    ok, bad = check(truth, shifted, fuzzy=fz)
    print(f"  {tag:22s}: khop {ok}/{len(shifted)} | BAT DUOC {len(bad)}/{len(shifted)-1} o lech")

print("\n=== 3. Do dai ma doi chieu can bao nhieu ky tu? ===")
for n in (2, 3, 4, 6):
    ok_good, bad_good = check(truth, answers, n=n)
    ok_bad, bad_bad = check(truth, shifted, n=n)
    print(f"  n={n}: bao dong gia {len(bad_good)}/12 | bat duoc lech {len(bad_bad)}/11")
