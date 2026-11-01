import math

import numpy as np
import pandas as pd
import pytest

from scanner import indicators as ind


def wilder_rsi_reference(values, period=14):
    """Plain-loop Wilder RSI used to cross-check the vectorised version."""
    deltas = np.diff(values)
    gains = np.clip(deltas, 0, None)
    losses = np.clip(-deltas, 0, None)
    # pandas ewm(alpha=1/n, adjust=False) seeds with the first value, then smooths
    ag, al = gains[0], losses[0]
    for g, l in zip(gains[1:], losses[1:]):
        ag = ag + (g - ag) / period
        al = al + (l - al) / period
    return 100 - 100 / (1 + ag / al)


def test_sma_and_ema_basics():
    s = pd.Series([1.0, 2, 3, 4, 5])
    assert ind.sma(s, 3).tolist()[2:] == [2.0, 3.0, 4.0]
    assert math.isnan(ind.sma(s, 3).iloc[1])
    e = ind.ema(pd.Series([10.0] * 30), 20)
    assert e.iloc[-1] == pytest.approx(10.0)


def test_rsi_extremes_and_reference():
    up = pd.Series(np.arange(1, 60, dtype=float))
    down = up[::-1].reset_index(drop=True)
    flat = pd.Series([5.0] * 40)
    assert ind.last(ind.rsi(up)) == pytest.approx(100.0)
    assert ind.last(ind.rsi(down)) == pytest.approx(0.0)
    assert ind.last(ind.rsi(flat)) == pytest.approx(50.0)

    rng = np.random.default_rng(1)
    values = 100 + np.cumsum(rng.normal(0, 1, 200))
    assert ind.last(ind.rsi(pd.Series(values))) == pytest.approx(wilder_rsi_reference(values), rel=1e-9)


def test_atr_constant_range():
    n = 50
    close = pd.Series([100.0] * n)
    high = close + 2
    low = close - 2
    assert ind.last(ind.atr(high, low, close)) == pytest.approx(4.0)


def test_returns_drawdown_volatility():
    close = pd.Series([100.0, 120, 90, 108])
    assert ind.period_return(close, 3) == pytest.approx(0.08)
    assert ind.period_return(close, 10) is None
    assert ind.max_drawdown(close) == pytest.approx(-0.25)
    assert ind.annualized_volatility(close) is None  # too short
    steady = pd.Series(100 * 1.001 ** np.arange(100))
    assert ind.annualized_volatility(steady) == pytest.approx(0.0, abs=1e-9)


def test_macd_positive_in_uptrend():
    close = pd.Series(100 * 1.003 ** np.arange(200))
    m = ind.macd(close)
    assert m["macd"].iloc[-1] > 0


def test_up_down_volume_ratio():
    close = pd.Series([1.0, 2, 1, 2, 1, 2])
    volume = pd.Series([0.0, 300, 100, 300, 100, 300])
    assert ind.up_down_volume_ratio(close, volume) == pytest.approx(900 / 200)
