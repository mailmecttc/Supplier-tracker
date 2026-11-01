"""Market data from Yahoo Finance (via the `yfinance` package), with a local disk cache."""
from __future__ import annotations

import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Callable, Dict, List, Optional

import pandas as pd

from .util import chunks

COLUMNS = ["Open", "High", "Low", "Close", "Volume"]
DEFAULT_CACHE = Path(__file__).resolve().parent.parent / ".cache"


def normalize_prices(df: Optional[pd.DataFrame]) -> Optional[pd.DataFrame]:
    """Keep OHLCV columns, drop empty rows, use a tz-naive sorted daily index."""
    if df is None or df.empty or "Close" not in df.columns:
        return None
    df = df.copy()
    df.index = pd.to_datetime(df.index)
    if getattr(df.index, "tz", None) is not None:
        df.index = df.index.tz_localize(None)
    df.index = df.index.normalize()
    for col in COLUMNS:
        if col not in df.columns:
            df[col] = df["Close"] if col != "Volume" else 0.0
    df = df[COLUMNS].apply(pd.to_numeric, errors="coerce")
    df = df.dropna(subset=["Close"])
    df = df[~df.index.duplicated(keep="last")].sort_index()
    return df if not df.empty else None


def _extract(data: pd.DataFrame, ticker: str) -> Optional[pd.DataFrame]:
    if data is None or data.empty:
        return None
    if isinstance(data.columns, pd.MultiIndex):
        if ticker in data.columns.get_level_values(0):
            df = data[ticker]
        elif ticker in data.columns.get_level_values(1):
            df = data.xs(ticker, axis=1, level=1)
        else:
            return None
    else:
        df = data
    return normalize_prices(df)


def _clean_info(info: dict) -> dict:
    out = {}
    for k, v in (info or {}).items():
        if isinstance(v, (str, int, float, bool)) or v is None:
            if isinstance(v, float) and v != v:  # NaN
                v = None
            out[k] = v
    return out


def _log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


class YahooProvider:
    """Downloads daily prices and company data. Results are cached for `ttl_hours`."""

    def __init__(self, cache_dir: Optional[Path] = DEFAULT_CACHE, ttl_hours: float = 12.0,
                 workers: int = 4, retries: int = 3):
        try:
            import yfinance  # noqa: F401
        except ImportError as exc:  # pragma: no cover - depends on the environment
            raise SystemExit("yfinance is not installed. Run: pip install -r requirements.txt") from exc
        self.cache_dir = Path(cache_dir) if cache_dir else None
        self.ttl = ttl_hours * 3600
        self.workers = max(1, workers)
        self.retries = max(1, retries)

    # ---- cache helpers -------------------------------------------------
    def _path(self, kind: str, ticker: str, ext: str) -> Optional[Path]:
        if not self.cache_dir:
            return None
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", ticker)
        return self.cache_dir / kind / f"{safe}.{ext}"

    def _fresh(self, path: Optional[Path]) -> bool:
        return bool(path and path.exists() and time.time() - path.stat().st_mtime < self.ttl)

    def _save(self, path: Optional[Path], write: Callable[[Path], None]) -> None:
        if not path:
            return
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            write(path)
        except OSError:
            pass

    # ---- prices ----------------------------------------------------------
    def history(self, tickers: List[str], period: str = "2y") -> Dict[str, pd.DataFrame]:
        out: Dict[str, pd.DataFrame] = {}
        missing: List[str] = []
        for t in tickers:
            path = self._path("prices", t, "csv")
            if self._fresh(path):
                try:
                    df = normalize_prices(pd.read_csv(path, index_col=0, parse_dates=True))
                    if df is not None:
                        out[t] = df
                        continue
                except (OSError, ValueError):
                    pass
            missing.append(t)

        for batch in chunks(missing, 40):
            data = self._download(list(batch), period)
            for t in batch:
                df = _extract(data, t) if data is not None else None
                if df is None:
                    continue
                out[t] = df
                self._save(self._path("prices", t, "csv"), lambda p, d=df: d.to_csv(p))
        return out

    def _download(self, tickers: List[str], period: str) -> Optional[pd.DataFrame]:
        import yfinance as yf

        for attempt in range(self.retries):
            try:
                return yf.download(
                    tickers, period=period, interval="1d", auto_adjust=True,
                    group_by="ticker", threads=True, progress=False,
                )
            except Exception as exc:  # network / rate-limit errors
                wait = 2 ** (attempt + 1)
                _log(f"  price download failed ({exc}); retrying in {wait}s")
                time.sleep(wait)
        return None

    # ---- company data ----------------------------------------------------
    def info(self, ticker: str) -> dict:
        path = self._path("info", ticker, "json")
        if self._fresh(path):
            try:
                return json.loads(path.read_text())
            except (OSError, ValueError):
                pass
        import yfinance as yf

        for attempt in range(self.retries):
            try:
                info = _clean_info(yf.Ticker(ticker).info)
                if info:
                    self._save(path, lambda p: p.write_text(json.dumps(info)))
                return info
            except Exception as exc:
                if attempt == self.retries - 1:
                    _log(f"  {ticker}: company data unavailable ({exc})")
                    return {}
                time.sleep(2 ** (attempt + 1))
        return {}

    def infos(self, tickers: List[str], progress: Optional[Callable[[int, int], None]] = None) -> Dict[str, dict]:
        out: Dict[str, dict] = {}
        with ThreadPoolExecutor(max_workers=self.workers) as pool:
            futures = {pool.submit(self.info, t): t for t in tickers}
            for i, fut in enumerate(as_completed(futures), 1):
                out[futures[fut]] = fut.result()
                if progress:
                    progress(i, len(tickers))
        return out
