"""Small numeric helpers shared by the scoring modules."""
from __future__ import annotations

import math
from typing import Iterable, Mapping, Optional, Sequence, Tuple

import numpy as np


def finite(x) -> Optional[float]:
    """Return x as a float if it is a real, finite number, else None."""
    if x is None or isinstance(x, bool):
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def scale(x, worst: float, best: float) -> Optional[float]:
    """Map x linearly onto 0..100, where `worst` scores 0 and `best` scores 100.

    Works in either direction (e.g. scale(pe, 40, 10) rewards a low P/E).
    Values beyond the end points are clipped.
    """
    v = finite(x)
    if v is None:
        return None
    if best == worst:
        return 100.0 if v >= best else 0.0
    t = (v - worst) / (best - worst)
    return float(min(1.0, max(0.0, t)) * 100.0)


def curve(x, points: Sequence[Tuple[float, float]]) -> Optional[float]:
    """Piecewise-linear score from (x, score) points sorted by x."""
    v = finite(x)
    if v is None:
        return None
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return float(np.interp(v, xs, ys))


def weighted_average(parts: Mapping[str, Optional[float]], weights: Mapping[str, float]):
    """Weighted mean of the parts that have data.

    Returns (score, coverage) where coverage is the share of the total weight that
    had data. Missing parts are skipped and the remaining weights re-normalised.
    """
    total = sum(w for w in weights.values() if w > 0)
    if total <= 0:
        return None, 0.0
    used = 0.0
    acc = 0.0
    for key, w in weights.items():
        if w <= 0:
            continue
        v = parts.get(key)
        if v is None:
            continue
        acc += v * w
        used += w
    if used == 0:
        return None, 0.0
    return acc / used, used / total


def pct(x, digits: int = 0, sign: bool = False) -> str:
    v = finite(x)
    if v is None:
        return "n/a"
    fmt = f"{{:{'+' if sign else ''}.{digits}f}}%"
    return fmt.format(v * 100)


def num(x, digits: int = 1) -> str:
    v = finite(x)
    if v is None:
        return "n/a"
    return f"{v:,.{digits}f}"


def indian_number(x: float, digits: int = 2) -> str:
    """Indian digit grouping: 1234567.5 -> 12,34,567.50 (lakh / crore style)."""
    sign = "-" if x < 0 else ""
    whole, _, frac = f"{abs(x):.{digits}f}".partition(".")
    if len(whole) > 3:
        head, tail = whole[:-3], whole[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        whole = ",".join(groups) + "," + tail
    return sign + whole + ("." + frac if frac else "")


def money(x, symbol: str = "", digits: int = 2) -> str:
    v = finite(x)
    if v is None:
        return "n/a"
    if symbol == "₹":
        return ("-" if v < 0 else "") + symbol + indian_number(abs(v), digits)
    return f"{symbol}{v:,.{digits}f}"


def big_money(x, symbol: str = "") -> str:
    """Compact market-cap style formatting: 1.2T, 340B, 5.6M (or crore for rupees)."""
    v = finite(x)
    if v is None:
        return "n/a"
    if symbol == "₹":
        return f"₹{indian_number(v / 1e7, 0)} Cr"
    for div, suffix in ((1e12, "T"), (1e9, "B"), (1e6, "M"), (1e3, "K")):
        if abs(v) >= div:
            return f"{symbol}{v / div:,.1f}{suffix}"
    return f"{symbol}{v:,.0f}"


def chunks(items: Sequence, size: int) -> Iterable[Sequence]:
    for i in range(0, len(items), size):
        yield items[i : i + size]
