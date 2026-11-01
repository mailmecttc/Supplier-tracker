"""Command line entry point:  python -m scanner --market india"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import List, Optional

from .config import MARKETS, STRATEGIES, Market


def _read_tickers_file(path: str) -> List[str]:
    out: List[str] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0]
        for tok in line.replace(",", " ").split():
            out.append(tok.strip())
    return [t for t in out if t]


def normalize_tickers(tickers: List[str], market: Market) -> List[str]:
    """Upper-case and add the exchange suffix (e.g. .NS) to bare symbols."""
    out = []
    for t in tickers:
        t = t.strip().upper()
        if not t:
            continue
        if market.suffix and "." not in t and not t.startswith("^"):
            t += market.suffix
        out.append(t)
    return out


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="python -m scanner",
        description="Scan stocks with fundamental + technical analysis and rank the best ones to buy.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "examples:\n"
            "  python -m scanner                          (India, NSE large caps)\n"
            "  python -m scanner --capital 500000         (also size positions for 5 lakh)\n"
            "  python -m scanner --tickers TCS INFY HDFCBANK POLYCAB\n"
            "  python -m scanner --universe extended --cap large mid --max-pe 40 --strategy value\n"
            "  python -m scanner --market us --strategy value --top 15\n"
            "  python -m scanner --demo           (offline sample with made-up data)\n"
        ),
    )
    p.add_argument("--market", choices=sorted(MARKETS), default="india",
                   help="which stock exchange to scan (default: india)")
    p.add_argument("--strategy", choices=["auto"] + sorted(STRATEGIES), default="auto",
                   help="auto picks one from the market trend (default: auto)")
    p.add_argument("--universe", choices=["core", "extended"], default="core",
                   help="core = the ~60 biggest companies; extended = ~220 large and mid caps (default: core)")
    p.add_argument("--max-pe", type=float, metavar="N",
                   help="only keep stocks with a trailing P/E at or below N (loss-makers are left out)")
    p.add_argument("--cap", nargs="+", choices=["large", "mid", "small"], metavar="SIZE",
                   help="only keep these company sizes: large, mid and/or small")
    p.add_argument("--tickers", nargs="+", metavar="SYMBOL",
                   help="scan these symbols instead of the built-in list")
    p.add_argument("--tickers-file", metavar="PATH",
                   help="text/CSV file of symbols (comma, space or one per line; # comments)")
    p.add_argument("--benchmark", metavar="SYMBOL", help="override the index used for the market trend")
    p.add_argument("--top", type=int, default=10, help="how many best stocks to show (default: 10)")
    p.add_argument("--capital", type=float, help="your investable capital, to size positions")
    p.add_argument("--risk", type=float, default=1.0,
                   help="percent of capital you accept losing per trade if the stop is hit (default: 1)")
    p.add_argument("--out", default="reports", help="folder for the HTML/CSV reports (default: reports)")
    p.add_argument("--no-report", action="store_true", help="only print to the terminal")
    p.add_argument("--no-cache", action="store_true", help="always download fresh data")
    p.add_argument("--workers", type=int, default=4, help="parallel downloads for company data (default: 4)")
    p.add_argument("--demo", action="store_true", help="run on built-in made-up data (no internet needed)")
    p.add_argument("--list-strategies", action="store_true", help="describe each strategy and exit")
    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    if args.list_strategies:
        for key, st in STRATEGIES.items():
            print(f"{key:<10} {st.title}\n           {st.description}\n")
        print("auto       Bull market -> growth, mixed -> balanced, bear -> defensive")
        return 0
    if args.risk <= 0 or args.risk > 5:
        print("--risk should be between 0 and 5 (percent).", file=sys.stderr)
        return 2
    if args.max_pe is not None and args.max_pe <= 0:
        print("--max-pe should be a positive number, e.g. 40.", file=sys.stderr)
        return 2

    from .engine import Screen, run_scan
    from .report import print_console, write_csv, write_html

    market = MARKETS[args.market]
    benchmark = args.benchmark
    if args.demo:
        from dataclasses import replace

        from .demo import DEMO_BENCHMARK, DEMO_TICKERS, DemoProvider

        provider = DemoProvider()
        # Made-up companies are priced in dollars, so use the US currency and size bands.
        market = replace(MARKETS["us"], key="demo", name="Demo market", benchmark=DEMO_BENCHMARK,
                         benchmark_name="Demo index", min_avg_traded_value=0.0)
        tickers = list(DEMO_TICKERS)
    else:
        from .data import YahooProvider

        provider = YahooProvider(cache_dir=None if args.no_cache else Path(__file__).resolve().parent.parent / ".cache",
                                 workers=args.workers)
        tickers: List[str] = []
        if args.tickers:
            tickers += args.tickers
        if args.tickers_file:
            tickers += _read_tickers_file(args.tickers_file)
        tickers = normalize_tickers(tickers, market) if tickers else market.tickers(args.universe)

    screen = Screen(max_pe=args.max_pe,
                    caps=tuple(c.capitalize() for c in dict.fromkeys(args.cap or [])))
    scan = run_scan(tickers, market, args.strategy, provider, capital=args.capital,
                    risk_pct=args.risk / 100.0, benchmark_symbol=benchmark, demo=args.demo, screen=screen)
    if not scan.scored:
        print("No stock could be analysed. Check your internet connection and the ticker symbols.",
              file=sys.stderr)
        return 1

    paths = []
    if not args.no_report:
        stamp = f"{scan.generated_at:%Y-%m-%d_%H%M}"
        base = Path(args.out) / f"scan_{'demo' if args.demo else market.key}_{stamp}"
        paths = [write_html(scan, base.with_suffix(".html"), args.top),
                 write_csv(scan, base.with_suffix(".csv"))]
    print_console(scan, args.top, paths)
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
