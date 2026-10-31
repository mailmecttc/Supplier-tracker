"""Trade plan: entry zone, ATR-based stop-loss, target and position size."""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional

from .regime import Regime

ATR_MULTIPLE = 2.0      # stop = price - 2 x ATR ...
MAX_STOP_PCT = 0.12     # ... but never more than 12% below the price
MIN_STOP_PCT = 0.03     # ... and never closer than 3% (avoid noise stop-outs)
REWARD_MULTIPLE = 2.0   # first target = 2 x the risk taken


@dataclass
class TradePlan:
    entry_low: float
    entry_high: float
    stop: float
    stop_pct: float
    target: float
    target_pct: float
    note: str
    shares: Optional[int] = None
    position_value: Optional[float] = None
    risk_amount: Optional[float] = None


def make_plan(
    price: Optional[float],
    atr: Optional[float],
    ema20: Optional[float],
    rsi: Optional[float],
    regime: Regime,
    capital: Optional[float] = None,
    risk_pct: float = 0.01,
) -> Optional[TradePlan]:
    if not price or price <= 0:
        return None
    if atr is None or atr <= 0:
        atr = price * 0.02

    risk = min(ATR_MULTIPLE * atr, price * MAX_STOP_PCT)
    risk = max(risk, price * MIN_STOP_PCT)

    if rsi is not None and rsi >= 70:
        # Stretched: wait for a pullback towards the 20-day average.
        anchor = ema20 if ema20 and ema20 < price else price - atr
        entry_high = max(anchor, price - atr)
        entry_low = entry_high - atr
        note = "Overbought: wait for a pullback into the entry zone"
    else:
        entry_high = price
        entry_low = max(price - atr, ema20) if ema20 and ema20 < price else price - atr
        note = "Can buy in the entry zone"
    if regime.tranches > 1:
        note += f"; split into {regime.tranches} buys"

    # Stop and target are measured from the top of the entry zone.
    stop = entry_high - risk
    target = entry_high + REWARD_MULTIPLE * risk
    plan = TradePlan(
        entry_low=round(entry_low, 2),
        entry_high=round(entry_high, 2),
        stop=round(stop, 2),
        stop_pct=-(risk / entry_high),
        target=round(target, 2),
        target_pct=REWARD_MULTIPLE * risk / entry_high,
        note=note,
    )

    if capital and capital > 0:
        risk_amount = capital * risk_pct
        by_risk = math.floor(risk_amount / risk)
        by_cap = math.floor(capital * regime.max_position_pct / entry_high)
        shares = max(0, min(by_risk, by_cap))
        plan.shares = shares
        plan.position_value = round(shares * entry_high, 2)
        plan.risk_amount = round(shares * risk, 2)
    return plan
