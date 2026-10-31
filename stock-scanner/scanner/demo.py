"""Synthetic market data for --demo runs and tests. Nothing here is a real company."""
from __future__ import annotations

from typing import Dict, List

import numpy as np
import pandas as pd

DEMO_BENCHMARK = "DEMO-INDEX"

# ticker: (name, sector, annual drift, annual vol, late drift (last 60 bars), info overrides)
PROFILES = {
    "QUALITY": ("Demo Quality Staples", "Consumer Defensive", 0.18, 0.16, 0.10, dict(
        returnOnEquity=0.28, operatingMargins=0.27, profitMargins=0.18, returnOnAssets=0.13,
        debtToEquity=35.0, currentRatio=1.8, freeCashflow=2.0e9, totalRevenue=1.2e10,
        revenueGrowth=0.09, earningsGrowth=0.12, trailingEps=5.0, forwardEps=5.6,
        trailingPE=22.0, forwardPE=19.0, pegRatio=1.4, priceToBook=6.0, enterpriseToEbitda=14.0,
        dividendRate=2.4, payoutRatio=0.45, beta=0.6)),
    "LEADER": ("Demo Growth Leader", "Technology", 0.35, 0.28, 0.25, dict(
        returnOnEquity=0.35, operatingMargins=0.32, profitMargins=0.25, returnOnAssets=0.15,
        debtToEquity=40.0, currentRatio=2.2, freeCashflow=6.0e9, totalRevenue=2.5e10,
        revenueGrowth=0.24, earningsGrowth=0.30, trailingEps=8.0, forwardEps=10.0,
        trailingPE=34.0, forwardPE=27.0, pegRatio=1.1, priceToBook=10.0, enterpriseToEbitda=24.0,
        dividendRate=0.0, beta=1.15)),
    "BANK": ("Demo Steady Bank", "Financial Services", 0.10, 0.20, 0.05, dict(
        industry="Banks - Regional", returnOnEquity=0.16, operatingMargins=0.40, profitMargins=0.28,
        returnOnAssets=0.016, revenueGrowth=0.11, earningsGrowth=0.14, trailingEps=4.0, forwardEps=4.4,
        trailingPE=11.0, forwardPE=10.0, priceToBook=1.6, dividendRate=1.5, payoutRatio=0.30, beta=0.95)),
    "CHEAP": ("Demo Value Industrial", "Industrials", 0.02, 0.24, -0.30, dict(
        returnOnEquity=0.17, operatingMargins=0.15, profitMargins=0.10, returnOnAssets=0.07,
        debtToEquity=60.0, currentRatio=1.5, freeCashflow=1.0e9, totalRevenue=9.0e9,
        revenueGrowth=0.05, earningsGrowth=0.06, trailingEps=6.0, forwardEps=6.4,
        trailingPE=11.0, forwardPE=10.0, pegRatio=0.9, priceToBook=1.8, enterpriseToEbitda=7.0,
        dividendRate=3.0, payoutRatio=0.40, beta=1.0)),
    "HOTSTOCK": ("Demo Overheated Momentum", "Technology", 0.45, 0.40, 1.60, dict(
        returnOnEquity=0.12, operatingMargins=0.10, profitMargins=0.06, returnOnAssets=0.05,
        debtToEquity=80.0, currentRatio=1.3, freeCashflow=2.0e8, totalRevenue=4.0e9,
        revenueGrowth=0.30, earningsGrowth=0.20, trailingEps=1.0, forwardEps=1.3,
        trailingPE=90.0, forwardPE=65.0, priceToBook=15.0, enterpriseToEbitda=55.0, beta=1.9)),
    "FALLING": ("Demo Falling Knife", "Consumer Cyclical", -0.35, 0.38, -0.40, dict(
        returnOnEquity=0.04, operatingMargins=0.03, profitMargins=0.01, returnOnAssets=0.01,
        debtToEquity=180.0, currentRatio=0.9, freeCashflow=-3.0e8, totalRevenue=7.0e9,
        revenueGrowth=-0.08, earningsGrowth=-0.40, trailingEps=0.4, forwardEps=0.3,
        trailingPE=45.0, forwardPE=60.0, priceToBook=1.2, enterpriseToEbitda=18.0, beta=1.4)),
    "LOSSMAKER": ("Demo Cash-Burning Startup", "Healthcare", 0.05, 0.55, 0.0, dict(
        returnOnEquity=-0.30, operatingMargins=-0.25, profitMargins=-0.30, returnOnAssets=-0.12,
        debtToEquity=20.0, currentRatio=3.0, freeCashflow=-5.0e8, totalRevenue=1.0e9,
        revenueGrowth=0.40, trailingEps=-2.0, forwardEps=-1.2, priceToBook=5.0, beta=1.7)),
    "INDEBTED": ("Demo Leveraged Utility", "Utilities", 0.06, 0.15, 0.04, dict(
        returnOnEquity=0.09, operatingMargins=0.22, profitMargins=0.11, returnOnAssets=0.03,
        debtToEquity=260.0, currentRatio=0.7, freeCashflow=-1.0e8, totalRevenue=1.5e10,
        revenueGrowth=0.03, earningsGrowth=0.04, trailingEps=3.0, forwardEps=3.2,
        trailingPE=18.0, forwardPE=17.0, priceToBook=2.0, enterpriseToEbitda=12.0,
        dividendRate=3.5, payoutRatio=0.85, beta=0.5)),
}

DEMO_TICKERS: List[str] = list(PROFILES)


def _series(seed: int, n: int, drift: float, vol: float, late_drift: float, start: float) -> np.ndarray:
    rng = np.random.default_rng(seed)
    daily = np.full(n, drift / 252)
    daily[-60:] = late_drift / 252
    rets = daily + rng.normal(0.0, vol / np.sqrt(252), n)
    return start * np.exp(np.cumsum(rets))


def _frame(close: np.ndarray, index: pd.DatetimeIndex, seed: int, vol: float) -> pd.DataFrame:
    rng = np.random.default_rng(seed + 1000)
    spread = np.abs(rng.normal(0, vol / np.sqrt(252), len(close))) * close
    up = np.r_[False, np.diff(close) > 0]
    volume = rng.integers(800_000, 1_200_000, len(close)) * np.where(up, 1.15, 0.9)
    return pd.DataFrame(
        {"Open": close, "High": close + spread, "Low": close - spread, "Close": close, "Volume": volume},
        index=index,
    )


class DemoProvider:
    """Same interface as YahooProvider, but returns deterministic made-up data."""

    def __init__(self, bars: int = 504, end: str = "2026-10-30"):
        self.index = pd.bdate_range(end=end, periods=bars)

    def history(self, tickers: List[str], period: str = "2y") -> Dict[str, pd.DataFrame]:
        out: Dict[str, pd.DataFrame] = {}
        n = len(self.index)
        for t in tickers:
            if t == DEMO_BENCHMARK:
                # A market that rose for a year and then rolled over: a bear phase.
                close = _series(7, n, 0.15, 0.15, -0.55, 4000.0)
                out[t] = _frame(close, self.index, 7, 0.15)
            elif t in PROFILES:
                _, _, drift, vol, late, _ = PROFILES[t]
                seed = 11 + DEMO_TICKERS.index(t)
                out[t] = _frame(_series(seed, n, drift, vol, late, 100.0), self.index, seed, vol)
        return out

    def infos(self, tickers: List[str], progress=None) -> Dict[str, dict]:
        prices = self.history(tickers)
        out: Dict[str, dict] = {}
        for i, t in enumerate(tickers, 1):
            if t in PROFILES:
                name, sector, *_ignored, overrides = PROFILES[t]
                price = float(prices[t]["Close"].iloc[-1])
                info = {"longName": name, "sector": sector, "currency": "USD", "currentPrice": price,
                        "marketCap": 5e10}
                info.update(overrides)
                if info.get("forwardEps"):
                    info["targetMeanPrice"] = round(price * 1.12, 2)
                out[t] = info
            if progress:
                progress(i, len(tickers))
        return out
