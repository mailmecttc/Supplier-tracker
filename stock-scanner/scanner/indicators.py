"""Technical indicators implemented with pandas only (no TA-Lib needed)."""
from __future__ import annotations

import math
from typing import Optional

import numpy as np
import pandas as pd

TRADING_DAYS = 252


def sma(series: pd.Series, window: int) -> pd.Series:
    return series.rolling(window, min_periods=window).mean()


def ema(series: pd.Series, span: int) -> pd.Series:
    return series.ewm(span=span, adjust=False, min_periods=span).mean()


def rsi(close: pd.Series, period: int = 14) -> pd.Series:
    """Wilder's Relative Strength Index (0-100)."""
    delta = close.diff()
    gain = delta.clip(lower=0.0)
    loss = (-delta).clip(lower=0.0)
    avg_gain = gain.ewm(alpha=1 / period, adjust=False, min_periods=period).mean()
    avg_loss = loss.ewm(alpha=1 / period, adjust=False, min_periods=period).mean()
    with np.errstate(divide="ignore", invalid="ignore"):
        rs = avg_gain / avg_loss
        out = 100.0 - 100.0 / (1.0 + rs)
    out = out.mask((avg_loss == 0) & (avg_gain > 0), 100.0)
    out = out.mask((avg_loss == 0) & (avg_gain == 0), 50.0)
    return out


def macd(close: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9) -> pd.DataFrame:
    line = close.ewm(span=fast, adjust=False).mean() - close.ewm(span=slow, adjust=False).mean()
    sig = line.ewm(span=signal, adjust=False).mean()
    return pd.DataFrame({"macd": line, "signal": sig, "hist": line - sig})


def atr(high: pd.Series, low: pd.Series, close: pd.Series, period: int = 14) -> pd.Series:
    """Average True Range (Wilder smoothing)."""
    prev = close.shift(1)
    tr = pd.concat([high - low, (high - prev).abs(), (low - prev).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / period, adjust=False, min_periods=period).mean()


def last(series: Optional[pd.Series], offset: int = 0) -> Optional[float]:
    """Value `offset` bars before the latest one, or None if missing."""
    if series is None or len(series) <= offset:
        return None
    v = series.iloc[-1 - offset]
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def period_return(close: pd.Series, days: int) -> Optional[float]:
    if len(close) <= days:
        return None
    start = last(close, days)
    end = last(close)
    if start is None or end is None or start <= 0:
        return None
    return end / start - 1.0


def annualized_volatility(close: pd.Series, days: int = 126) -> Optional[float]:
    rets = close.pct_change().dropna().tail(days)
    if len(rets) < 20:
        return None
    return float(rets.std() * math.sqrt(TRADING_DAYS))


def max_drawdown(close: pd.Series, days: int = TRADING_DAYS) -> Optional[float]:
    window = close.tail(days)
    if len(window) < 2:
        return None
    return float((window / window.cummax() - 1.0).min())


def up_down_volume_ratio(close: pd.Series, volume: pd.Series, days: int = 50) -> Optional[float]:
    """Volume on up days divided by volume on down days (>1 suggests accumulation)."""
    chg = close.diff().tail(days)
    vol = volume.reindex(chg.index).fillna(0)
    up = float(vol[chg > 0].sum())
    down = float(vol[chg < 0].sum())
    if down <= 0:
        return None if up <= 0 else 3.0
    return up / down
