"""Technical analysis: trend, relative strength, momentum, risk and volume."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

import pandas as pd

from . import indicators as ind
from .config import Strategy
from .util import curve, finite, pct, scale, weighted_average

MIN_ROWS = 60  # fewer bars than this and the technical picture is meaningless


@dataclass
class TechnicalResult:
    score: Optional[float]
    parts: Dict[str, Optional[float]]
    metrics: Dict[str, Optional[float]]
    strengths: List[str] = field(default_factory=list)
    concerns: List[str] = field(default_factory=list)
    coverage: float = 0.0


def _bench_return(bench: Optional[pd.Series], index: pd.DatetimeIndex, days: int) -> Optional[float]:
    """Benchmark return over the same dates as the stock's last `days` bars."""
    if bench is None or bench.empty or len(index) <= days:
        return None
    start = finite(bench.asof(index[-1 - days]))
    end = finite(bench.asof(index[-1]))
    if start is None or end is None or start <= 0:
        return None
    return end / start - 1.0


def _excess(stock: Optional[float], bench: Optional[float]) -> Optional[float]:
    if stock is None or bench is None:
        return None
    return (1 + stock) / (1 + bench) - 1


def compute_metrics(prices: pd.DataFrame, benchmark: Optional[pd.Series] = None) -> Dict[str, Optional[float]]:
    close = prices["Close"].astype(float)
    high = prices["High"].astype(float) if "High" in prices else close
    low = prices["Low"].astype(float) if "Low" in prices else close
    volume = prices["Volume"].astype(float) if "Volume" in prices else pd.Series(0.0, index=close.index)

    m: Dict[str, Optional[float]] = {}
    price = ind.last(close)
    m["price"] = price
    ema20 = ind.ema(close, 20)
    sma50 = ind.sma(close, 50)
    sma200 = ind.sma(close, 200)
    m["ema20"] = ind.last(ema20)
    m["sma50"] = ind.last(sma50)
    m["sma200"] = ind.last(sma200)

    def slope(series: pd.Series, bars: int) -> Optional[float]:
        now, before = ind.last(series), ind.last(series, bars)
        if now is None or before is None or before <= 0:
            return None
        return now / before - 1

    m["sma50_slope"] = slope(sma50, 10)
    m["sma200_slope"] = slope(sma200, 20)

    m["rsi"] = ind.last(ind.rsi(close))
    md = ind.macd(close)
    m["macd"] = ind.last(md["macd"])
    m["macd_signal"] = ind.last(md["signal"])
    m["macd_hist"] = ind.last(md["hist"])
    m["macd_hist_prev"] = ind.last(md["hist"], 3)

    atr = ind.last(ind.atr(high, low, close))
    m["atr"] = atr
    m["atr_pct"] = atr / price if atr is not None and price else None

    for label, days in (("1m", 21), ("3m", 63), ("6m", 126), ("12m", 252)):
        m[f"ret_{label}"] = ind.period_return(close, days)
    m["bench_ret_3m"] = _bench_return(benchmark, close.index, 63)
    m["bench_ret_6m"] = _bench_return(benchmark, close.index, 126)
    m["rs_3m"] = _excess(m["ret_3m"], m["bench_ret_3m"])
    m["rs_6m"] = _excess(m["ret_6m"], m["bench_ret_6m"])

    year = close.tail(ind.TRADING_DAYS)
    m["high_52w"] = finite(year.max())
    m["low_52w"] = finite(year.min())
    m["pct_from_high"] = price / m["high_52w"] - 1 if price and m["high_52w"] else None
    m["volatility"] = ind.annualized_volatility(close)
    m["max_drawdown"] = ind.max_drawdown(close)
    m["updown_volume"] = ind.up_down_volume_ratio(close, volume)
    m["avg_traded_value"] = finite((close * volume).tail(20).mean())
    m["bars"] = float(len(close))
    return m


def _trend_score(m: Dict[str, Optional[float]]) -> Optional[float]:
    price, ema20, sma50, sma200 = m["price"], m["ema20"], m["sma50"], m["sma200"]

    def gt(a, b):
        return None if a is None or b is None else a > b

    checks = [
        (gt(price, sma200), 25),
        (gt(price, sma50), 20),
        (gt(sma50, sma200), 20),
        (None if m["sma200_slope"] is None else m["sma200_slope"] > 0, 15),
        (None if m["sma50_slope"] is None else m["sma50_slope"] > 0, 10),
        (gt(price, ema20), 10),
    ]
    known = [(ok, w) for ok, w in checks if ok is not None]
    if not known:
        return None
    return 100.0 * sum(w for ok, w in known if ok) / sum(w for _, w in known)


def score_parts(m: Dict[str, Optional[float]], strategy: Strategy, beta: Optional[float]) -> Dict[str, Optional[float]]:
    parts: Dict[str, Optional[float]] = {}
    parts["trend"] = _trend_score(m)

    if m["rs_3m"] is not None or m["rs_6m"] is not None:
        parts["relative_strength"], _ = weighted_average(
            {"3m": scale(m["rs_3m"], -0.15, 0.15), "6m": scale(m["rs_6m"], -0.20, 0.25)},
            {"3m": 0.4, "6m": 0.6},
        )
    else:  # no benchmark: fall back to absolute returns
        parts["relative_strength"], _ = weighted_average(
            {"3m": scale(m["ret_3m"], -0.10, 0.15), "6m": scale(m["ret_6m"], -0.15, 0.25)},
            {"3m": 0.4, "6m": 0.6},
        )

    parts["rsi"] = curve(m["rsi"], strategy.rsi_curve)

    if m["macd"] is not None and m["macd_signal"] is not None:
        s = 45.0 if m["macd"] > m["macd_signal"] else 0.0
        if m["macd_hist"] is not None and m["macd_hist_prev"] is not None and m["macd_hist"] > m["macd_hist_prev"]:
            s += 25.0
        if m["macd"] > 0:
            s += 30.0
        parts["macd"] = s
    else:
        parts["macd"] = None

    parts["risk"], _ = weighted_average(
        {
            "vol": scale(m["volatility"], 0.60, 0.15),
            "dd": scale(m["max_drawdown"], -0.50, -0.08),
            "beta": scale(beta, 1.8, 0.6),
        },
        {"vol": 0.4, "dd": 0.35, "beta": 0.25},
    )
    parts["near_high"] = curve(
        m["pct_from_high"], [(-0.6, 0), (-0.4, 15), (-0.25, 45), (-0.15, 70), (-0.05, 100), (0, 100)]
    )
    parts["volume"] = scale(m["updown_volume"], 0.7, 1.5)
    return parts


def describe(m: Dict[str, Optional[float]], beta: Optional[float]):
    good: List[str] = []
    bad: List[str] = []
    price, sma50, sma200 = m["price"], m["sma50"], m["sma200"]

    if price and sma50 and sma200:
        if price > sma50 > sma200:
            good.append("Uptrend: price above rising 50- and 200-day averages"
                        if (m["sma200_slope"] or 0) > 0 else "Price above 50- and 200-day averages")
        elif price > sma200 and sma50 > sma200:
            good.append("Long-term uptrend intact (pullback to/below 50-day average)")
        elif price < sma200 and sma50 < sma200:
            bad.append("Downtrend: price and 50-day average below 200-day average")
        elif price < sma200:
            bad.append("Price below its 200-day average")
        elif sma50 < sma200:
            bad.append("50-day average still below 200-day average (trend not confirmed)")
    elif sma200 is None:
        bad.append("Less than 200 days of price history")

    rs = m["rs_6m"] if m["rs_6m"] is not None else m["rs_3m"]
    horizon = "6 months" if m["rs_6m"] is not None else "3 months"
    if rs is not None:
        if rs >= 0.05:
            good.append(f"Beating the index by {pct(rs, sign=True)} over {horizon}")
        elif rs <= -0.10:
            bad.append(f"Lagging the index by {pct(rs, sign=True)} over {horizon}")

    r = m["rsi"]
    if r is not None:
        if r >= 75:
            bad.append(f"RSI {r:.0f}: overbought, wait for a pullback")
        elif r >= 70:
            bad.append(f"RSI {r:.0f}: getting stretched")
        elif r <= 30:
            bad.append(f"RSI {r:.0f}: oversold, momentum still weak")
        elif 45 <= r <= 65:
            good.append(f"RSI {r:.0f}: healthy momentum")

    if m["macd"] is not None and m["macd_signal"] is not None:
        if m["macd"] > m["macd_signal"] and m["macd"] > 0:
            good.append("MACD bullish (above signal line and zero)")
        elif m["macd"] < m["macd_signal"] and m["macd"] < 0:
            bad.append("MACD bearish (below signal line and zero)")

    v = m["volatility"]
    if v is not None:
        if v <= 0.22:
            good.append(f"Low volatility ({pct(v)} a year)")
        elif v >= 0.50:
            bad.append(f"High volatility ({pct(v)} a year)")
    if beta is not None:
        if beta <= 0.8:
            good.append(f"Low beta {beta:.2f}: moves less than the market")
        elif beta >= 1.5:
            bad.append(f"High beta {beta:.2f}: swings more than the market")

    h = m["pct_from_high"]
    if h is not None:
        if h >= -0.05:
            good.append("Trading near its 52-week high (market leader)")
        elif h <= -0.35:
            bad.append(f"{pct(-h)} below its 52-week high")

    u = m["updown_volume"]
    if u is not None:
        if u >= 1.3:
            good.append(f"Accumulation: {u:.1f}x more volume on up days")
        elif u <= 0.75:
            bad.append("Distribution: heavier volume on down days")
    return good, bad


def analyze_technical(
    prices: pd.DataFrame,
    strategy: Strategy,
    benchmark: Optional[pd.Series] = None,
    beta: Optional[float] = None,
) -> TechnicalResult:
    if prices is None or len(prices.dropna(subset=["Close"])) < MIN_ROWS:
        return TechnicalResult(None, {}, {}, [], ["Not enough price history"], 0.0)
    prices = prices.dropna(subset=["Close"])
    m = compute_metrics(prices, benchmark)
    parts = score_parts(m, strategy, beta)
    score, coverage = weighted_average(parts, strategy.tech_weights)
    good, bad = describe(m, beta)
    return TechnicalResult(score, parts, m, good, bad, coverage)
