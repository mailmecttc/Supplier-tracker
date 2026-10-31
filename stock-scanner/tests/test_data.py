"""YahooProvider tested against a fake `yfinance` module (no network)."""
import sys
import types

import pandas as pd
import pytest

from scanner.data import YahooProvider


@pytest.fixture
def fake_yf(monkeypatch):
    calls = {"download": 0, "info": 0}
    idx = pd.date_range("2025-01-01", periods=300, freq="B", tz="Asia/Kolkata")

    def download(tickers, **kwargs):
        calls["download"] += 1
        frames = {}
        for i, t in enumerate(tickers):
            if t == "BAD.NS":
                continue
            base = pd.Series(range(100 + i, 400 + i), index=idx, dtype=float)
            frames[t] = pd.DataFrame({"Open": base, "High": base + 1, "Low": base - 1, "Close": base,
                                      "Volume": 1000.0})
        return pd.concat(frames, axis=1) if frames else pd.DataFrame()

    class Ticker:
        def __init__(self, symbol):
            self.symbol = symbol

        @property
        def info(self):
            calls["info"] += 1
            if self.symbol == "BAD.NS":
                raise RuntimeError("404")
            return {"longName": f"{self.symbol} Ltd", "trailingPE": 20.5, "companyOfficers": [{"x": 1}],
                    "beta": float("nan")}

    module = types.SimpleNamespace(download=download, Ticker=Ticker)
    monkeypatch.setitem(sys.modules, "yfinance", module)
    monkeypatch.setattr("scanner.data.time.sleep", lambda s: None)
    return calls


def test_history_and_cache(fake_yf, tmp_path):
    p = YahooProvider(cache_dir=tmp_path)
    out = p.history(["TCS.NS", "INFY.NS", "BAD.NS"])
    assert set(out) == {"TCS.NS", "INFY.NS"}
    assert out["INFY.NS"]["Close"].iloc[0] == 101.0
    assert out["TCS.NS"].index.tz is None
    assert fake_yf["download"] == 1

    again = YahooProvider(cache_dir=tmp_path).history(["TCS.NS", "INFY.NS"])
    assert fake_yf["download"] == 1  # served from the cache
    pd.testing.assert_series_equal(again["TCS.NS"]["Close"], out["TCS.NS"]["Close"], check_freq=False)


def test_info_cleaning_cache_and_failure(fake_yf, tmp_path):
    p = YahooProvider(cache_dir=tmp_path, workers=2)
    infos = p.infos(["TCS.NS", "BAD.NS"])
    assert infos["TCS.NS"]["trailingPE"] == 20.5
    assert "companyOfficers" not in infos["TCS.NS"]  # non-scalar fields dropped
    assert infos["TCS.NS"]["beta"] is None
    assert infos["BAD.NS"] == {}
    before = fake_yf["info"]
    assert p.info("TCS.NS")["longName"] == "TCS.NS Ltd"
    assert fake_yf["info"] == before  # cached


def test_no_cache_mode(fake_yf):
    p = YahooProvider(cache_dir=None)
    p.history(["TCS.NS"])
    p.history(["TCS.NS"])
    assert fake_yf["download"] == 2
