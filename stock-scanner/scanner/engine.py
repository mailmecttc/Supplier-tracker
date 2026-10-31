"""Runs the scan: loads data, scores every stock, applies filters and ranks them."""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Dict, List, Optional

from .config import (
    AUTO_STRATEGY, MIN_PILLAR_FOR_BUY, RATING_THRESHOLDS, STRATEGIES, Market, Strategy,
)
from .fundamental import FundamentalResult, analyze_fundamental
from .plan import TradePlan, make_plan
from .regime import Regime, detect_regime
from .technical import TechnicalResult, analyze_technical
from .util import num, pct

RATING_ORDER = {"TOP PICK": 0, "BUY": 1, "WATCH": 2, "AVOID": 3}


@dataclass
class StockResult:
    ticker: str
    name: str
    sector: str
    price: Optional[float]
    score: Optional[float]
    fundamental: FundamentalResult
    technical: TechnicalResult
    rating: str = "AVOID"
    action: str = ""
    excluded: List[str] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)
    plan: Optional[TradePlan] = None


@dataclass
class ScanResult:
    market: Market
    strategy: Strategy
    requested_strategy: str
    regime: Regime
    stocks: List[StockResult]
    failed: Dict[str, str]
    generated_at: datetime
    capital: Optional[float] = None
    risk_pct: float = 0.01
    demo: bool = False

    @property
    def ranked(self) -> List[StockResult]:
        return [s for s in self.stocks if s.score is not None]

    def picks(self, top: int) -> List[StockResult]:
        return [s for s in self.ranked if s.rating in ("TOP PICK", "BUY")][:top]


def _log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def apply_filters(strategy: Strategy, fund: FundamentalResult, tech: TechnicalResult,
                  min_traded_value: float = 0.0) -> List[str]:
    f = strategy.filters
    fm, tm = fund.metrics, tech.metrics
    reasons: List[str] = []

    eps = fm.get("trailing_eps")
    if f.require_profit and eps is not None and eps <= 0:
        reasons.append("Loss-making (negative trailing EPS)")
    de = fm.get("debt_to_equity")
    if f.max_debt_to_equity is not None and not fund.is_financial and de is not None and de > f.max_debt_to_equity:
        reasons.append(f"Debt/equity {num(de, 2)}x is above the {num(f.max_debt_to_equity, 1)}x limit")
    beta = fm.get("beta")
    if f.max_beta is not None and beta is not None and beta > f.max_beta:
        reasons.append(f"Beta {num(beta, 2)} is above the {num(f.max_beta, 1)} limit")
    rg = fm.get("revenue_growth")
    if f.min_revenue_growth is not None and rg is not None and rg < f.min_revenue_growth:
        reasons.append(f"Revenue growth {pct(rg, sign=True)} is below {pct(f.min_revenue_growth)}")

    price, sma50, sma200 = tm.get("price"), tm.get("sma50"), tm.get("sma200")
    if price and sma200:
        if f.trend == "not_downtrend" and sma50 and price < sma200 and sma50 < sma200:
            reasons.append("Confirmed downtrend (don't catch a falling knife)")
        elif f.trend == "above200" and price < sma200:
            reasons.append("Price is below its 200-day average")

    traded = tm.get("avg_traded_value")
    if min_traded_value and traded is not None and traded < min_traded_value:
        reasons.append("Low trading volume (hard to buy/sell)")
    return reasons


def rate(score: float, fund_score: Optional[float], tech_score: Optional[float], regime: Regime):
    """Return (rating, notes)."""
    bump = regime.threshold_bump
    notes: List[str] = []
    if score >= RATING_THRESHOLDS["TOP PICK"] + bump:
        rating = "TOP PICK"
    elif score >= RATING_THRESHOLDS["BUY"] + bump:
        rating = "BUY"
    elif score >= RATING_THRESHOLDS["WATCH"] + bump:
        rating = "WATCH"
    else:
        rating = "AVOID"

    if rating in ("TOP PICK", "BUY"):
        weak = []
        if fund_score is not None and fund_score < MIN_PILLAR_FOR_BUY:
            weak.append("fundamentals")
        if tech_score is not None and tech_score < MIN_PILLAR_FOR_BUY:
            weak.append("technicals")
        if weak:
            notes.append(f"Downgraded to WATCH: weak {' and '.join(weak)}")
            rating = "WATCH"
    return rating, notes


def action_for(rating: str, rsi: Optional[float], regime: Regime) -> str:
    if rating in ("TOP PICK", "BUY"):
        if rsi is not None and rsi >= 70:
            return "Buy on pullback"
        if regime.label == "Bear":
            return "Accumulate slowly (3 parts)"
        if regime.label in ("Neutral", "Unknown"):
            return "Buy in 2 parts"
        return "Buy"
    if rating == "WATCH":
        return "Watchlist: wait for a better setup"
    return "Avoid for now"


def score_stock(ticker: str, prices, info: dict, benchmark, strategy: Strategy, regime: Regime,
                market: Market, capital: Optional[float], risk_pct: float) -> StockResult:
    fund = analyze_fundamental(info, strategy)
    beta = fund.metrics.get("beta") if fund.metrics else None
    tech = analyze_technical(prices, strategy, benchmark, beta)
    fm = fund.metrics or {}
    result = StockResult(
        ticker=ticker,
        name=fm.get("name") or ticker,
        sector=fm.get("sector") or "Unknown",
        price=tech.metrics.get("price") if tech.metrics else None,
        score=None,
        fundamental=fund,
        technical=tech,
    )
    if tech.score is None:
        result.excluded = ["Not enough price history"]
        return result

    w_fund, w_tech = strategy.composite
    if fund.score is None or fund.coverage < 0.35:
        # Without company data we cannot judge the business: score on price only, with a penalty.
        score = tech.score * 0.85
        result.notes.append("Limited company data: score is mostly technical")
    else:
        score = w_fund * fund.score + w_tech * tech.score
    result.score = round(score, 1)

    result.excluded = apply_filters(strategy, fund, tech, market.min_avg_traded_value)
    if result.excluded:
        result.rating = "AVOID"
    else:
        result.rating, notes = rate(score, fund.score, tech.score, regime)
        result.notes.extend(notes)
    result.action = action_for(result.rating, tech.metrics.get("rsi"), regime)

    tm = tech.metrics
    if result.rating in ("TOP PICK", "BUY", "WATCH"):
        result.plan = make_plan(tm.get("price"), tm.get("atr"), tm.get("ema20"), tm.get("rsi"),
                                regime, capital, risk_pct)
    return result


def run_scan(
    tickers: List[str],
    market: Market,
    strategy_name: str,
    provider,
    capital: Optional[float] = None,
    risk_pct: float = 0.01,
    benchmark_symbol: Optional[str] = None,
    demo: bool = False,
    log: Callable[[str], None] = _log,
) -> ScanResult:
    tickers = list(dict.fromkeys(tickers))  # de-duplicate, keep order
    bench_symbol = benchmark_symbol or market.benchmark

    log(f"Loading {market.benchmark_name} ({bench_symbol}) to read the market trend...")
    bench_hist = provider.history([bench_symbol]).get(bench_symbol)
    bench_close = bench_hist["Close"] if bench_hist is not None else None
    regime = detect_regime(bench_close, market.benchmark_name)
    log(f"Market regime: {regime.label} - {regime.headline}")

    resolved = AUTO_STRATEGY[regime.label] if strategy_name == "auto" else strategy_name
    strategy = STRATEGIES[resolved]
    log(f"Strategy: {strategy.title}")

    log(f"Downloading 2 years of prices for {len(tickers)} stocks...")
    histories = provider.history(tickers)

    def progress(done: int, total: int) -> None:
        if done == total or done % 10 == 0:
            log(f"  company data {done}/{total}")

    log("Downloading company fundamentals...")
    infos = provider.infos([t for t in tickers if t in histories], progress=progress)

    stocks: List[StockResult] = []
    failed: Dict[str, str] = {}
    for t in tickers:
        if t not in histories:
            failed[t] = "no price data (wrong symbol or delisted?)"
            continue
        res = score_stock(t, histories[t], infos.get(t) or {}, bench_close, strategy, regime,
                          market, capital, risk_pct)
        if res.score is None:
            failed[t] = "; ".join(res.excluded) or "could not be scored"
        stocks.append(res)

    stocks.sort(key=lambda s: (s.score is None, RATING_ORDER.get(s.rating, 9), -(s.score or 0)))
    return ScanResult(
        market=market, strategy=strategy, requested_strategy=strategy_name, regime=regime,
        stocks=stocks, failed=failed, generated_at=datetime.now(), capital=capital,
        risk_pct=risk_pct, demo=demo,
    )
