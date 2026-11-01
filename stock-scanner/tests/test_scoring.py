import numpy as np
import pandas as pd
import pytest

from scanner.config import STRATEGIES
from scanner.engine import apply_filters, rate
from scanner.fundamental import analyze_fundamental, extract_metrics
from scanner.plan import MAX_STOP_PCT, MIN_STOP_PCT, make_plan
from scanner.regime import Regime, detect_regime
from scanner.technical import analyze_technical
from scanner.util import curve, scale, weighted_average

DEF = STRATEGIES["defensive"]

STRONG = dict(
    sector="Technology", returnOnEquity=0.30, operatingMargins=0.30, profitMargins=0.22, returnOnAssets=0.14,
    debtToEquity=30.0, currentRatio=2.0, freeCashflow=3e9, totalRevenue=1.5e10, marketCap=6e10,
    revenueGrowth=0.18, earningsGrowth=0.22, trailingEps=5.0, forwardEps=6.0, trailingPE=20.0,
    forwardPE=16.0, pegRatio=0.9, priceToBook=4.0, enterpriseToEbitda=12.0, currentPrice=100.0,
    dividendRate=2.0, payoutRatio=0.3, beta=0.9,
)
WEAK = dict(
    sector="Consumer Cyclical", returnOnEquity=0.02, operatingMargins=0.02, profitMargins=-0.05,
    returnOnAssets=0.0, debtToEquity=250.0, currentRatio=0.6, freeCashflow=-5e8, totalRevenue=5e9,
    marketCap=2e9, revenueGrowth=-0.10, earningsGrowth=-0.5, trailingEps=-1.0, forwardEps=-0.5,
    priceToBook=9.0, currentPrice=20.0, beta=1.8,
)


def prices_from(close: np.ndarray) -> pd.DataFrame:
    idx = pd.bdate_range(end="2026-10-30", periods=len(close))
    close = pd.Series(close, index=idx)
    return pd.DataFrame({"Open": close, "High": close * 1.01, "Low": close * 0.99, "Close": close,
                         "Volume": 1e6}, index=idx)


def trend(n=400, daily=0.001, start=100.0, noise=0.0, seed=0):
    rng = np.random.default_rng(seed)
    return start * np.exp(np.cumsum(np.full(n, daily) + rng.normal(0, noise, n)))


# ------------------------------------------------------------------ helpers
def test_scale_curve_weighted():
    assert scale(0.25, 0.05, 0.25) == 100
    assert scale(0.15, 0.05, 0.25) == pytest.approx(50)
    assert scale(10, 40, 10) == 100  # reversed direction: lower is better
    assert scale(None, 0, 1) is None
    assert curve(55, [(0, 0), (50, 100), (100, 0)]) == pytest.approx(90)
    score, cov = weighted_average({"a": 100, "b": None, "c": 50}, {"a": 1, "b": 1, "c": 1})
    assert score == pytest.approx(75) and cov == pytest.approx(2 / 3)
    assert weighted_average({"a": None}, {"a": 1}) == (None, 0.0)


# ------------------------------------------------------------------ fundamentals
def test_strong_company_beats_weak_company():
    strong = analyze_fundamental(STRONG, DEF)
    weak = analyze_fundamental(WEAK, DEF)
    assert strong.score > 75
    assert weak.score < 25
    assert any("return on equity" in s for s in strong.strengths)
    assert any("Loss-making" in s for s in weak.concerns)
    assert weak.parts["valuation"] < 30


def test_missing_info_is_handled():
    r = analyze_fundamental({}, DEF)
    assert r.score is None and r.coverage == 0


def test_financials_skip_leverage_tests():
    bank = dict(STRONG, sector="Financial Services", industry="Banks - Diversified", debtToEquity=900.0,
                returnOnAssets=0.015)
    r = analyze_fundamental(bank, DEF)
    assert r.is_financial
    assert r.parts["strength"] is None
    assert r.parts["quality"] > 70  # 1.5% ROA is good for a bank


def test_debt_to_equity_and_dividend_units():
    m = extract_metrics({"debtToEquity": 150.0, "dividendYield": 2.5, "marketCap": 1e9})
    assert m["debt_to_equity"] == pytest.approx(1.5)
    assert m["dividend_yield"] == pytest.approx(0.025)  # yfinance now reports percent
    m = extract_metrics({"dividendRate": 3.0, "currentPrice": 100.0})
    assert m["dividend_yield"] == pytest.approx(0.03)


# ------------------------------------------------------------------ technicals
def test_uptrend_scores_higher_than_downtrend():
    bench = pd.Series(trend(daily=0.0), index=pd.bdate_range(end="2026-10-30", periods=400))
    up = analyze_technical(prices_from(trend(daily=0.0015, noise=0.01, seed=1)), DEF, bench)
    down = analyze_technical(prices_from(trend(daily=-0.0015, noise=0.01, seed=2)), DEF, bench)
    assert up.score > 65 > 35 > down.score
    assert up.parts["trend"] > 80 and down.parts["trend"] < 20
    assert up.metrics["rs_6m"] > 0 > down.metrics["rs_6m"]
    assert any("Downtrend" in c for c in down.concerns)


def test_short_history_is_not_scored():
    r = analyze_technical(prices_from(trend(n=30)), DEF)
    assert r.score is None


# ------------------------------------------------------------------ regime
def test_regime_detection():
    idx = pd.bdate_range(end="2026-10-30", periods=400)
    bull = detect_regime(pd.Series(trend(daily=0.001), index=idx), "Index")
    bear_path = np.r_[trend(n=250, daily=0.001), trend(n=150, daily=-0.003, start=trend(n=250, daily=0.001)[-1])]
    bear = detect_regime(pd.Series(bear_path, index=idx), "Index")
    unknown = detect_regime(pd.Series(trend(n=100), index=idx[:100]), "Index")
    assert bull.label == "Bull" and bull.threshold_bump == 0
    assert bear.label == "Bear" and bear.max_position_pct < bull.max_position_pct
    assert unknown.label == "Unknown"


# ------------------------------------------------------------------ plan
def test_trade_plan_bounds_and_sizing():
    regime = Regime(label="Bear", headline="", max_position_pct=0.05, tranches=3)
    plan = make_plan(price=100, atr=2, ema20=98, rsi=55, regime=regime, capital=100_000, risk_pct=0.01)
    assert plan.stop == pytest.approx(96.0)  # 2 x ATR
    assert plan.target == pytest.approx(108.0)  # 2R
    assert plan.shares == 50  # capped by 5% max position, not by the 250 the risk allows
    assert plan.risk_amount <= 100_000 * 0.01

    wide = make_plan(price=100, atr=20, ema20=90, rsi=50, regime=regime)
    assert wide.stop_pct == pytest.approx(-MAX_STOP_PCT)
    tight = make_plan(price=100, atr=0.1, ema20=99.9, rsi=50, regime=regime)
    assert tight.stop_pct == pytest.approx(-MIN_STOP_PCT)

    hot = make_plan(price=100, atr=3, ema20=95, rsi=78, regime=regime)
    assert hot.entry_high < 100 and "pullback" in hot.note


# ------------------------------------------------------------------ filters and rating
def test_filters_block_risky_stocks():
    down = analyze_technical(prices_from(trend(daily=-0.0015)), DEF)
    weak = analyze_fundamental(WEAK, DEF)
    reasons = apply_filters(DEF, weak, down)
    text = " ".join(reasons)
    assert "Loss-making" in text and "Debt/equity" in text and "Beta" in text and "downtrend" in text

    up = analyze_technical(prices_from(trend(daily=0.0015)), DEF)
    assert apply_filters(DEF, analyze_fundamental(STRONG, DEF), up) == []
    # value strategy allows stocks below their 200-day average
    assert not any("downtrend" in r for r in apply_filters(STRATEGIES["value"], analyze_fundamental(STRONG, DEF), down))


def test_rating_needs_both_halves_and_bear_raises_the_bar():
    neutral = Regime(label="Neutral", headline="", threshold_bump=0)
    bear = Regime(label="Bear", headline="", threshold_bump=5)
    assert rate(80, 80, 80, neutral)[0] == "TOP PICK"
    assert rate(70, 70, 70, bear)[0] == "BUY"   # would be 72 -> TOP PICK only without the bump
    rating, notes = rate(75, 95, 40, neutral)
    assert rating == "WATCH" and "technicals" in notes[0]
    assert rate(40, 40, 40, neutral)[0] == "AVOID"
