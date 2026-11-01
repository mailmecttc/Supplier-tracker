"""Strategy presets and market settings.

Every number that drives a score lives here so the strategy is easy to read and tune.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from .universes import INDIA_LARGE_CAPS, INDIA_MORE_LARGE_AND_MID, US_LARGE_CAPS, US_MID_CAPS

# RSI -> score curves. The default rewards healthy momentum (RSI 50-60) and
# penalises overbought (>70) and collapsing (<30) stocks.
RSI_DEFAULT: List[Tuple[float, float]] = [
    (0, 10), (25, 25), (30, 35), (40, 65), (50, 100), (60, 100), (65, 85), (70, 60), (80, 20), (100, 5),
]
# Value / buy-the-dip: prefers a cooled-off stock (RSI 35-55).
RSI_PULLBACK: List[Tuple[float, float]] = [
    (0, 10), (20, 25), (30, 60), (35, 85), (40, 100), (55, 100), (65, 70), (70, 45), (80, 15), (100, 5),
]
# Momentum: prefers strong but not euphoric (RSI 60-70).
RSI_MOMENTUM: List[Tuple[float, float]] = [
    (0, 0), (30, 10), (40, 35), (50, 70), (55, 90), (60, 100), (70, 100), (75, 75), (80, 45), (90, 15), (100, 5),
]


@dataclass(frozen=True)
class Filters:
    """Hard rules. A stock that breaks one is rated AVOID whatever its score."""

    require_profit: bool = False           # trailing EPS must be > 0
    max_debt_to_equity: Optional[float] = None  # ignored for banks/financials
    max_beta: Optional[float] = None
    min_revenue_growth: Optional[float] = None
    # None | "not_downtrend" (exclude price AND 50-DMA below 200-DMA) | "above200"
    trend: Optional[str] = None


@dataclass(frozen=True)
class Strategy:
    name: str
    title: str
    description: str
    # Share of the final score from (fundamentals, technicals); must sum to 1.
    composite: Tuple[float, float]
    # Fundamental pillars: quality, strength, growth, valuation, income
    fund_weights: Dict[str, float]
    # Technical pillars: trend, relative_strength, rsi, macd, risk, near_high, volume
    tech_weights: Dict[str, float]
    rsi_curve: List[Tuple[float, float]] = field(default_factory=lambda: RSI_DEFAULT)
    filters: Filters = Filters()


STRATEGIES: Dict[str, Strategy] = {
    "defensive": Strategy(
        name="defensive",
        title="Defensive quality (weak/bear market)",
        description=(
            "Profitable, low-debt, cash-generating companies that are holding up better than the "
            "index, with lower volatility. Avoids loss-makers, high debt and stocks in confirmed "
            "downtrends. Use this when the market is falling."
        ),
        composite=(0.60, 0.40),
        fund_weights={"quality": 0.35, "strength": 0.25, "growth": 0.15, "valuation": 0.15, "income": 0.10},
        tech_weights={"trend": 0.30, "relative_strength": 0.20, "rsi": 0.10, "macd": 0.05,
                      "risk": 0.20, "near_high": 0.10, "volume": 0.05},
        filters=Filters(require_profit=True, max_debt_to_equity=2.0, max_beta=1.6, trend="not_downtrend"),
    ),
    "balanced": Strategy(
        name="balanced",
        title="Balanced (quality + growth + trend)",
        description="Equal weight to business quality and price trend. A good all-weather default.",
        composite=(0.50, 0.50),
        fund_weights={"quality": 0.30, "strength": 0.20, "growth": 0.25, "valuation": 0.20, "income": 0.05},
        tech_weights={"trend": 0.30, "relative_strength": 0.20, "rsi": 0.10, "macd": 0.10,
                      "risk": 0.10, "near_high": 0.10, "volume": 0.10},
        filters=Filters(require_profit=True, max_debt_to_equity=3.0, trend="not_downtrend"),
    ),
    "growth": Strategy(
        name="growth",
        title="Growth leaders",
        description="Fast revenue and earnings growth with strong relative strength. Best in rising markets.",
        composite=(0.55, 0.45),
        fund_weights={"quality": 0.25, "strength": 0.10, "growth": 0.50, "valuation": 0.15, "income": 0.0},
        tech_weights={"trend": 0.25, "relative_strength": 0.25, "rsi": 0.10, "macd": 0.10,
                      "risk": 0.05, "near_high": 0.15, "volume": 0.10},
        rsi_curve=RSI_MOMENTUM,
        filters=Filters(min_revenue_growth=0.05, trend="not_downtrend"),
    ),
    "value": Strategy(
        name="value",
        title="Value / buy quality on dips",
        description=(
            "Profitable companies trading at low valuations after a pullback. Allows stocks below "
            "their 200-day average, so size positions small and respect the stop-loss."
        ),
        composite=(0.65, 0.35),
        fund_weights={"quality": 0.25, "strength": 0.20, "growth": 0.10, "valuation": 0.40, "income": 0.05},
        tech_weights={"trend": 0.20, "relative_strength": 0.10, "rsi": 0.25, "macd": 0.10,
                      "risk": 0.15, "near_high": 0.05, "volume": 0.15},
        rsi_curve=RSI_PULLBACK,
        filters=Filters(require_profit=True, max_debt_to_equity=2.5),
    ),
    "momentum": Strategy(
        name="momentum",
        title="Momentum (trend following)",
        description="Stocks in strong uptrends that beat the index, backed by acceptable fundamentals.",
        composite=(0.35, 0.65),
        fund_weights={"quality": 0.30, "strength": 0.20, "growth": 0.35, "valuation": 0.15, "income": 0.0},
        tech_weights={"trend": 0.30, "relative_strength": 0.30, "rsi": 0.05, "macd": 0.10,
                      "risk": 0.0, "near_high": 0.15, "volume": 0.10},
        rsi_curve=RSI_MOMENTUM,
        filters=Filters(trend="above200"),
    ),
}

# What "auto" picks for each market regime.
AUTO_STRATEGY = {"Bull": "growth", "Neutral": "balanced", "Bear": "defensive", "Unknown": "balanced"}

# Score needed for each rating (before the regime adjustment).
RATING_THRESHOLDS = {"TOP PICK": 72.0, "BUY": 62.0, "WATCH": 50.0}
# Both halves must be at least this for a BUY / TOP PICK (fundamentals and technicals must agree).
MIN_PILLAR_FOR_BUY = 45.0


@dataclass(frozen=True)
class Market:
    key: str
    name: str
    benchmark: str
    benchmark_name: str
    currency: str
    symbol: str
    suffix: str                 # appended to bare tickers, e.g. ".NS"
    min_avg_traded_value: float  # 20-day average daily traded value, in local currency
    universe: Tuple[str, ...]           # "core": the biggest, most liquid companies
    extra_universe: Tuple[str, ...] = ()  # added by --universe extended (more large + mid caps)
    # Market-cap bands in local currency: (large cap from, mid cap from). Below = small cap.
    cap_bands: Tuple[float, float] = (1e10, 2e9)
    cap_note: str = ""

    def tickers(self, universe: str = "core") -> List[str]:
        if universe == "extended":
            return list(dict.fromkeys(self.universe + self.extra_universe))
        return list(self.universe)


MARKETS: Dict[str, Market] = {
    "us": Market(
        key="us", name="United States", benchmark="^GSPC", benchmark_name="S&P 500",
        currency="USD", symbol="$", suffix="", min_avg_traded_value=5e6, universe=US_LARGE_CAPS,
        extra_universe=US_MID_CAPS, cap_bands=(1e10, 2e9),
        cap_note="Large ≥ $10B, Mid $2-10B, Small < $2B",
    ),
    "india": Market(
        key="india", name="India (NSE)", benchmark="^NSEI", benchmark_name="NIFTY 50",
        currency="INR", symbol="₹", suffix=".NS", min_avg_traded_value=5e7, universe=INDIA_LARGE_CAPS,
        extra_universe=INDIA_MORE_LARGE_AND_MID,
        # Close to AMFI's 2026 cut-offs (top 100 = large, next 150 = mid); AMFI resets them twice a year.
        cap_bands=(1.05e12, 3.4e11),
        cap_note="Large ≥ ₹1,05,000 Cr, Mid ₹34,000-1,05,000 Cr, Small < ₹34,000 Cr (≈ AMFI 2026)",
    ),
}
