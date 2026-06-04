"""OCR numeric correction for medicine strengths and pack sizes.

The correction is intentionally conservative: medicine names are preserved, and
only tokens that look like dosage/quantity values are rewritten.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import re


WHITELIST_STRENGTHS = frozenset(
    Decimal(str(value))
    for value in [
        1, 2, 2.5, 3, 4, 5, 6, 8, 10,
        12.5, 15, 20, 25, 30, 40, 50,
        60, 75, 80, 90, 100, 110, 120,
        125, 150, 180, 200, 220, 228,
        250, 300, 333, 375, 400, 457,
        500, 550, 600, 625, 650, 667,
        750, 800, 850, 1000, 1200,
        1500, 2000, 3000,
    ]
)

WHITELIST_UNITS = frozenset({"MG", "MCG", "G", "GM", "KG", "ML", "L", "IU", "%"})

TOKEN_RE = re.compile(r"[A-Za-z0-9.]+%?|[^A-Za-z0-9.]+")
WORD_RE = re.compile(r"^[A-Za-z0-9.]+%?$")
NUMBER_RE = re.compile(r"^\d+(?:\.\d+)?$")

CONFUSABLE_DIGITS = {
    "G": "6",
    "g": "9",
    "S": "5",
    "s": "5",
    "O": "0",
    "o": "0",
    "I": "1",
    "l": "1",
    "L": "1",
    "B": "8",
    "Z": "2",
}


@dataclass(frozen=True)
class TokenParts:
    prefix: str
    numeric: str
    unit: str
    suffix: str


def correct_numeric_ocr(text: str) -> str:
    """Correct OCR-confused numeric medicine strengths in text.

    Examples:
        "DOLO GSO MG" -> "DOLO 650 MG"
        "PARACETAMOL SOOMG" -> "PARACETAMOL 500MG"
        "SYRUP IOOML" -> "SYRUP 100ML"

    Non-numeric medicine-name tokens are left unchanged.
    """

    tokens = TOKEN_RE.findall(text)
    corrected: list[str] = []

    for index, token in enumerate(tokens):
        if not WORD_RE.match(token):
            corrected.append(token)
            continue

        previous_word = _nearest_word(tokens, index, step=-1)
        next_word = _nearest_word(tokens, index, step=1)
        corrected.append(_correct_token(token, previous_word, next_word))

    return "".join(corrected)


def _correct_token(token: str, previous_word: str | None, next_word: str | None) -> str:
    parts = _split_token(token)
    if parts is None:
        return token

    has_unit_context = bool(parts.unit) or _is_unit(previous_word) or _is_unit(next_word)
    looks_numeric = _looks_like_numeric_ocr(parts.numeric)
    if not looks_numeric:
        return token

    corrected_number = _correct_number(parts.numeric)
    if corrected_number is None:
        return token

    if not has_unit_context and not _is_standalone_strength_candidate(parts.numeric, corrected_number):
        return token

    return f"{parts.prefix}{corrected_number}{parts.unit}{parts.suffix}"


def _split_token(token: str) -> TokenParts | None:
    suffix = ""
    core = token
    if core.endswith("%"):
        core = core[:-1]
        suffix = "%"

    unit = ""
    upper_core = core.upper()
    for candidate in sorted(WHITELIST_UNITS - {"%"}, key=len, reverse=True):
        if upper_core.endswith(candidate) and len(core) > len(candidate):
            unit = core[-len(candidate):].upper()
            core = core[:-len(candidate)]
            break

    if not core:
        return None

    return TokenParts(prefix="", numeric=core, unit=unit, suffix=suffix)


def _correct_number(value: str) -> str | None:
    candidate = "".join(CONFUSABLE_DIGITS.get(ch, ch) for ch in value)
    if not NUMBER_RE.match(candidate):
        return None

    try:
        decimal_value = Decimal(candidate)
    except InvalidOperation:
        return None

    if decimal_value not in WHITELIST_STRENGTHS:
        return None

    return _format_decimal(decimal_value)


def _looks_like_numeric_ocr(value: str) -> bool:
    if NUMBER_RE.match(value):
        return True
    usable = [ch for ch in value if ch != "."]
    if not usable:
        return False
    return all(ch.isdigit() or ch in CONFUSABLE_DIGITS for ch in usable)


def _is_standalone_strength_candidate(original: str, corrected: str) -> bool:
    if len(original) > 4:
        return False
    if original == corrected:
        return True
    changed = sum(1 for before, after in zip(original, corrected) if before != after)
    return changed > 0


def _nearest_word(tokens: list[str], index: int, step: int) -> str | None:
    cursor = index + step
    while 0 <= cursor < len(tokens):
        token = tokens[cursor]
        if WORD_RE.match(token):
            return token
        cursor += step
    return None


def _is_unit(token: str | None) -> bool:
    return bool(token) and token.upper().rstrip(".") in WHITELIST_UNITS


def _format_decimal(value: Decimal) -> str:
    if value == value.to_integral_value():
        return str(int(value))
    return format(value.normalize(), "f")


if __name__ == "__main__":
    samples = [
        "DOLO GSO MG",
        "PARACETAMOL SOOMG",
        "SYRUP IOOML",
        "ABC",
    ]
    for sample in samples:
        print(f"{sample} -> {correct_numeric_ocr(sample)}")
