"""Market regime: is the overall market in an uptrend, a choppy range or a downtrend?

The regime decides how aggressive the scanner is: which strategy "auto" uses, how
high the bar for a BUY rating is, and how big each position may be.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

import pandas as pd

from . import indicators as ind
from .util import pct


@dataclass
class Regime:
    label: str                 # Bull | Neutral | Bear | Unknown
    headline: str
    checks: List[str] = field(default_factory=list)   # "✓ ..." / "✗ ..." lines
    guidance: List[str] = field(default_factory=list)
    metrics: Dict[str, Optional[float]] = field(default_factory=dict)
    max_position_pct: float = 0.07   # cap on any one stock, as a share of capital
    cash_reserve_pct: float = 0.30   # suggested cash kept aside
    threshold_bump: float = 0.0      # extra score needed for BUY / TOP PICK
    tranches: int = 2                # split each buy into this many parts


def detect_regime(bench_close: Optional[pd.Series], bench_name: str = "the index") -> Regime:
    if bench_close is None or len(bench_close.dropna()) < 210:
        return Regime(
            label="Unknown",
            headline=f"Could not load enough {bench_name} history to judge the market trend.",
            guidance=["Treat the market as mixed: be selective and buy in 2 parts."],
        )
    close = bench_close.dropna().astype(float)
    price = ind.last(close)
    sma50 = ind.last(ind.sma(close, 50))
    sma200 = ind.last(ind.sma(close, 200))
    ret_3m = ind.period_return(close, 63)
    high = float(close.tail(ind.TRADING_DAYS).max())
    from_high = price / high - 1
    rsi = ind.last(ind.rsi(close))

    tests = [
        (price > sma200, f"{bench_name} above its 200-day average", f"{bench_name} below its 200-day average"),
        (sma50 > sma200, "50-day average above 200-day average", "50-day average below 200-day average"),
        ((ret_3m or 0) > 0, f"3-month return {pct(ret_3m, 1, True)}", f"3-month return {pct(ret_3m, 1, True)}"),
        (from_high > -0.10, f"Only {pct(-from_high, 1)} below 52-week high",
         f"{pct(-from_high, 1)} below 52-week high"),
    ]
    passed = sum(1 for ok, _, _ in tests if ok)
    checks = [("✓ " + yes) if ok else ("✗ " + no) for ok, yes, no in tests]
    metrics = {"price": price, "sma50": sma50, "sma200": sma200, "ret_3m": ret_3m,
               "from_high": from_high, "rsi": rsi}

    if passed == 4:
        return Regime(
            label="Bull",
            headline=f"{bench_name} is in an uptrend.",
            checks=checks, metrics=metrics,
            guidance=[
                "Normal position sizes are fine; favour leaders breaking to new highs.",
                "Keep ~10% cash and still use stop-losses.",
            ],
            max_position_pct=0.10, cash_reserve_pct=0.10, threshold_bump=0.0, tranches=1,
        )
    if passed >= 2:
        return Regime(
            label="Neutral",
            headline=f"{bench_name} is mixed / range-bound.",
            checks=checks, metrics=metrics,
            guidance=[
                "Be selective: only buy stocks rated BUY or TOP PICK.",
                "Buy each position in 2 parts, a week or two apart, and keep ~30% cash.",
                "Prefer stocks that are beating the index; they usually lead when the market turns up.",
            ],
            max_position_pct=0.07, cash_reserve_pct=0.30, threshold_bump=2.0, tranches=2,
        )
    return Regime(
        label="Bear",
        headline=f"{bench_name} is in a downtrend. Protect capital first.",
        checks=checks, metrics=metrics,
        guidance=[
            "Keep 40-60% in cash or short-term deposits until the index retakes its 200-day average.",
            "Buy only TOP PICK / BUY names, and split each buy into 3 parts over several weeks.",
            "Keep positions small (max ~5% of capital each) and always place the stop-loss.",
            "Stocks that hold up (near highs, beating the index) in a falling market often lead the next rally.",
            "Re-run the scan weekly; switch to 'balanced' or 'growth' when the market turns up.",
        ],
        max_position_pct=0.05, cash_reserve_pct=0.50, threshold_bump=5.0, tranches=3,
    )
