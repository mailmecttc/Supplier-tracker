import csv

import pandas as pd

from scanner.cli import main, normalize_tickers
from scanner.config import MARKETS
from scanner.data import _extract, normalize_prices
from scanner.demo import DEMO_BENCHMARK, DEMO_TICKERS, DemoProvider
from scanner.engine import run_scan
from scanner.report import write_csv, write_html
from dataclasses import replace


def demo_scan(strategy="auto", capital=None):
    market = replace(MARKETS["us"], benchmark=DEMO_BENCHMARK, benchmark_name="Demo index", min_avg_traded_value=0)
    return run_scan(DEMO_TICKERS, market, strategy, DemoProvider(), capital=capital, demo=True, log=lambda m: None)


def test_demo_scan_end_to_end(tmp_path):
    scan = demo_scan(capital=100_000)
    assert scan.regime.label == "Bear"
    assert scan.strategy.name == "defensive"  # auto picks defensive in a bear market
    by = {s.ticker: s for s in scan.stocks}

    # the obviously bad stocks are filtered out, each with a reason
    assert by["LOSSMAKER"].rating == "AVOID" and any("Loss-making" in r for r in by["LOSSMAKER"].excluded)
    assert by["INDEBTED"].rating == "AVOID" and any("Debt/equity" in r for r in by["INDEBTED"].excluded)
    assert by["FALLING"].rating == "AVOID"
    assert by["HOTSTOCK"].rating == "AVOID"  # beta too high for a defensive strategy

    picks = scan.picks(10)
    assert {"QUALITY", "LEADER"} <= {p.ticker for p in picks}
    for p in picks:
        assert p.plan is not None and p.plan.stop < p.plan.entry_high < p.plan.target
        assert p.plan.position_value <= 100_000 * scan.regime.max_position_pct + 1e-6

    # ranking: picks first, best score first
    ranked = scan.ranked
    assert ranked[0].rating in ("TOP PICK", "BUY")
    assert [s.score for s in picks] == sorted((s.score for s in picks), reverse=True)

    html_path = write_html(scan, tmp_path / "r.html")
    csv_path = write_csv(scan, tmp_path / "r.csv")
    text = html_path.read_text()
    assert "QUALITY" in text and "Bear market" in text and "DEMO DATA" in text
    with csv_path.open() as fh:
        rows = list(csv.DictReader(fh))
    assert len(rows) == len(DEMO_TICKERS)
    assert rows[0]["rating"] in ("TOP PICK", "BUY")


def test_other_strategies_run():
    for name in ("balanced", "growth", "value", "momentum"):
        scan = demo_scan(strategy=name)
        assert scan.strategy.name == name
        assert len(scan.ranked) == len(DEMO_TICKERS)


def test_missing_tickers_are_reported():
    market = replace(MARKETS["us"], benchmark=DEMO_BENCHMARK)
    scan = run_scan(["QUALITY", "NOPE"], market, "balanced", DemoProvider(), log=lambda m: None)
    assert "NOPE" in scan.failed
    assert [s.ticker for s in scan.ranked] == ["QUALITY"]


def test_normalize_tickers():
    india = MARKETS["india"]
    assert normalize_tickers(["tcs", "M&M", "INFY.NS", "^NSEI", "RELIANCE.BO"], india) == [
        "TCS.NS", "M&M.NS", "INFY.NS", "^NSEI", "RELIANCE.BO"]
    assert normalize_tickers(["aapl", "brk-b"], MARKETS["us"]) == ["AAPL", "BRK-B"]


def test_extract_handles_yfinance_layouts():
    idx = pd.date_range("2026-01-01", periods=3, tz="America/New_York")
    cols = ["Open", "High", "Low", "Close", "Volume"]
    single = pd.DataFrame(1.0, index=idx, columns=cols)
    by_ticker = pd.concat({"AAA": single, "BBB": single * 2}, axis=1)            # (ticker, field)
    by_field = by_ticker.swaplevel(axis=1)                                        # (field, ticker)
    assert _extract(by_ticker, "BBB")["Close"].iloc[-1] == 2.0
    assert _extract(by_field, "BBB")["Close"].iloc[-1] == 2.0
    assert _extract(by_ticker, "CCC") is None
    out = _extract(single, "AAA")
    assert out.index.tz is None and list(out.columns) == cols
    assert normalize_prices(pd.DataFrame({"Close": [float("nan")]})) is None


def test_cli_demo(tmp_path, capsys):
    assert main(["--demo", "--out", str(tmp_path), "--capital", "50000"]) == 0
    out = capsys.readouterr().out
    assert "BEST STOCKS RIGHT NOW" in out and "not financial advice" in out
    assert len(list(tmp_path.glob("*.html"))) == 1 and len(list(tmp_path.glob("*.csv"))) == 1
    assert main(["--list-strategies"]) == 0


def test_india_is_default_and_rupees_use_indian_grouping():
    from scanner.cli import build_parser
    from scanner.util import big_money, money

    assert build_parser().parse_args([]).market == "india"
    assert money(1234567.5, "₹") == "₹12,34,567.50"
    assert money(999.0, "₹", 0) == "₹999"
    assert money(-150000, "₹", 0) == "-₹1,50,000"
    assert big_money(1.85e13, "₹") == "₹18,50,000 Cr"   # ₹18.5 lakh crore
    assert money(1234567.5, "$") == "$1,234,567.50"
