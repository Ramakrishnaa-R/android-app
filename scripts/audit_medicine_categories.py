#!/usr/bin/env python3
"""Audit how medicines.json names map to ProductCategory (mirrors Kotlin rules)."""
import json
import re
from collections import Counter
from pathlib import Path

MED_PATH = Path(__file__).resolve().parents[1] / "app/src/main/assets/medicines.json"

pill = {"TAB", "TABS", "TABLET", "TABLETS", "DT", "MD", "ODT", "CAP", "CAPS", "CAPSULE", "CAPSULES", "SOFTGEL", "SOFTGELS", "SOFTLET", "BOLUS", "DUO"}
inj = {"INJ", "INJECT", "INJECTION", "VIAL", "AMP", "PFS", "INFU", "INFUSION", "IV"}
powder = {"POWDER", "PDR", "PWDR", "PDRS", "GRANULES", "SACHET", "CHOORNAM", "DUSTING"}
tonic = {"SYP", "SYRUP", "TONIC", "LIQ", "LIQUID", "ELIXIR", "SOLUTION", "SOL"}
susp = {"SUSP", "SUSPENSION", "SUS"}
drops = {"DROPS", "DROP"}
cream = {"CREAM", "CREM", "CRM"}
lotion = {"LOTION", "LOT"}
gel = {"GEL", "EMULGEL"}
oint = {"OINT", "OINTMENT", "ONT", "OINMENT"}
inh = {"INHALER", "ROTAHALER", "DPI", "MDI", "ROTACAPS", "RESPICAPS"}
spray = {"SPRAY", "SPRY"}
shampoo = {"SHAMPOO"}
soap = {"SOAP", "BAR"}
device = {"APPARATUS", "MONITOR", "METER", "GLUCOMETER", "THERMOMETER", "NEBULIZER", "OXIMETER"}
consumable = {"MASK", "SYRINGE", "NEEDLE", "STRIP", "LANCET", "BANDAGE", "GAUZE"}


def tokenize(s: str) -> list[str]:
    s = re.sub(r"[^A-Z0-9 ]", " ", s.upper())
    return [t for t in s.split() if t]


def infer_pill_pack(normalized: str, tokens: set[str]) -> bool:
    if not re.search(r"\b\d{1,3}\s*'?S\b", normalized):
        return False
    has_strength = bool(tokens & {"MG", "MCG", "GM", "G"}) or " MG" in normalized
    liquid = {"SYP", "SYRUP", "SUSP", "DROPS", "LOTION", "LIQUID", "SOL", "SOLUTION"}
    return has_strength and not (tokens & liquid)


def classify_kotlin_like(name: str, database: bool = False) -> str:
    t = set(tokenize(name))
    n = name.upper()
    if database and infer_pill_pack(n, t):
        return "PILL"
    if "NASAL SPRAY" in n or "MIST SPRAY" in n or t & spray:
        return "SPRAY"
    if "EYE OINT" in n or t & oint:
        return "OINTMENT"
    if any(p in n for p in ("EYE DROPS", "EAR DROPS", "NASAL DROPS")) or t & drops:
        return "DROPS"
    if t & inj:
        return "INJECTION"
    if t & inh:
        return "INHALER"
    if t & susp:
        return "SUSPENSION"
    if t & tonic or "ORAL SOLUTION" in n or "ORAL SOL" in n or "DRY SYP" in n:
        return "TONIC"
    if t & lotion:
        return "LOTION"
    if t & cream:
        return "CREAM"
    if t & gel:
        return "GEL"
    if t & powder:
        return "POWDER"
    if t & shampoo:
        return "SHAMPOO"
    if t & soap:
        return "SOAP"
    if t & pill or "SOFT GEL" in n:
        return "PILL"
    if t & device:
        return "DEVICE"
    if t & consumable:
        return "CONSUMABLE"
    return "OTHER"


def main():
    meds = json.loads(MED_PATH.read_text(encoding="utf-8"))
    counts_ocr = Counter(classify_kotlin_like(m, database=False) for m in meds)
    counts_db = Counter(classify_kotlin_like(m, database=True) for m in meds)
    print(f"Total: {len(meds)}\n")
    print("OCR rules (old index):")
    for k, v in counts_ocr.most_common():
        print(f"  {k}: {v} ({100 * v / len(meds):.1f}%)")
    print("\nDATABASE rules (new index):")
    for k, v in counts_db.most_common():
        print(f"  {k}: {v} ({100 * v / len(meds):.1f}%)")

    other = [m for m in meds if classify_kotlin_like(m, database=True) == "OTHER"]
    print(f"\nOTHER count: {len(other)}")
    print("Sample OTHER:")
    for m in other[:25]:
        print(f"  {m}")

    # Likely pills missing TAB token
    maybe_pill = []
    for m in other:
        u = m.upper()
        if re.search(r"\b\d+\s*'?S\b", u) or " MG " in u or "MCG" in u:
            if not any(x in u for x in ("CREAM", "GEL", "LOTION", "SHAMPOO", "SOAP", "DEVICE")):
                maybe_pill.append(m)
    print(f"\nOTHER but looks like pill (N'S or MG, no form word): {len(maybe_pill)}")
    for m in maybe_pill[:15]:
        print(f"  {m}")


if __name__ == "__main__":
    main()
