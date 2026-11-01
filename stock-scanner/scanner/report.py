"""Console, CSV and HTML output."""
from __future__ import annotations

import csv
import html
from pathlib import Path
from typing import List, Optional

from .config import MIN_PILLAR_FOR_BUY, RATING_THRESHOLDS
from .engine import ScanResult, StockResult, sector_summary
from .plan import ATR_MULTIPLE, MAX_STOP_PCT, MIN_STOP_PCT, REWARD_MULTIPLE
from .util import big_money, finite, money, num, pct

DISCLAIMER = (
    "This scanner is an educational screening tool, not financial advice. Scores come from "
    "public data that can be delayed, incomplete or wrong, and past prices do not predict future "
    "returns. Check each company yourself (latest results, news, management) before investing, "
    "use stop-losses, never invest money you cannot afford to lose, and consider speaking to a "
    "registered financial adviser."
)


def _fmt_score(x: Optional[float]) -> str:
    return "  -" if x is None else f"{x:3.0f}"


def _short(text: str, width: int) -> str:
    return text if len(text) <= width else text[: width - 1] + "…"


def strengths(s: StockResult, n: int = 3) -> List[str]:
    """Top reasons, balanced between the business and the chart."""
    return s.fundamental.strengths[:n] + s.technical.strengths[:n]


def concerns(s: StockResult, n: int = 2) -> List[str]:
    return s.notes + s.fundamental.concerns[:n] + s.technical.concerns[:n]


def _tick(ticker: str, suffix: str) -> str:
    """TCS.NS -> TCS for compact lists."""
    return ticker[: -len(suffix)] if suffix and ticker.endswith(suffix) else ticker


def _pe(s: StockResult) -> str:
    if s.pe is not None:
        return num(s.pe)
    eps = (s.fundamental.metrics or {}).get("trailing_eps")
    return "loss" if eps is not None and eps <= 0 else "n/a"


def _screen_line(scan: ScanResult) -> str:
    sc = scan.screen
    if not sc.active:
        return ""
    return (f"Your filters: {sc.describe(scan.market)}. {len(scan.ranked)} stocks pass, "
            f"{len(scan.screened)} left out.")


def print_sectors(scan: ScanResult) -> None:
    rows = sector_summary(scan)
    if not rows:
        return
    limit = scan.screen.max_pe
    sizes = " + ".join(scan.screen.caps) + " caps" if scan.screen.caps else "stocks"
    print(f"\nSECTOR VALUATIONS (median P/E of the {sizes} scanned, cheapest first)")
    lim = f"P/E≤{limit:g}" if limit is not None else "With P/E"
    header = f"   {'Sector':<24}{'Stocks':>6}  {'Median P/E':>10}  {lim:>8}   Best value stocks (rating, score)"
    print(header)
    print("   " + "-" * (len(header) - 3))
    for r in rows:
        mark = "✓" if limit is not None and r["median_pe"] is not None and r["median_pe"] <= limit else " "
        best = ", ".join(f"{_tick(b.ticker, scan.market.suffix)} ({b.rating} {b.score:.0f})" for b in r["best"])
        print(f" {mark} {_short(r['sector'], 23):<24}{r['stocks']:>6}  {num(r['median_pe']):>10}  "
              f"{r['under_limit']:>4}/{r['stocks']:<3}   {best or '-'}")
    if limit is not None:
        print(f"   ✓ = sector's median P/E is at or below {limit:g}")


def _plan_line(s: StockResult, sym: str) -> str:
    p = s.plan
    if not p:
        return ""
    line = (f"Entry {money(p.entry_low, sym)}-{money(p.entry_high, sym)}, "
            f"stop {money(p.stop, sym)} ({pct(p.stop_pct, 1, True)}), "
            f"target {money(p.target, sym)} ({pct(p.target_pct, 1, True)})")
    if p.shares is not None:
        line += f"; {p.shares:,} shares ≈ {money(p.position_value, sym, 0)} (risk {money(p.risk_amount, sym, 0)})"
    return line


# ---------------------------------------------------------------- console
def print_console(scan: ScanResult, top: int = 10, paths: Optional[List[Path]] = None) -> None:
    mk, rg, st = scan.market, scan.regime, scan.strategy
    sym = mk.symbol
    bar = "=" * 100
    print(bar)
    title = f" STOCK SCANNER | {mk.name} | {scan.generated_at:%Y-%m-%d %H:%M}"
    if scan.demo:
        title += " | DEMO DATA (not real companies)"
    print(title)
    print(bar)
    print(f"MARKET: {rg.label.upper()} - {rg.headline}")
    for c in rg.checks:
        print(f"   {c}")
    print("What to do:")
    for g in rg.guidance:
        print(f"   * {g}")
    auto = " (auto-selected for this market)" if scan.requested_strategy == "auto" else ""
    print(f"\nStrategy: {st.title}{auto}")
    print(f"   {st.description}")
    if scan.screen.active:
        print(_screen_line(scan))

    picks = scan.picks(top)
    print()
    if picks:
        print(f"BEST STOCKS RIGHT NOW ({len(picks)} rated BUY or better, best first)")
        header = (f"{'#':>2}  {'Ticker':<14}{'Name':<24}{'Score':>5} {'Fund':>4} {'Tech':>4} {'P/E':>6} "
                  f"{'Cap':<6}{'Rating':<9}{'Price':>12}  {'Action'}")
        print(header)
        print("-" * len(header))
        for i, s in enumerate(picks, 1):
            print(f"{i:>2}  {s.ticker:<14}{_short(s.name, 23):<24}{_fmt_score(s.score):>5} "
                  f"{_fmt_score(s.fundamental.score):>4} {_fmt_score(s.technical.score):>4} {_pe(s):>6} "
                  f"{(s.cap or '-'):<6}{s.rating:<9}{money(s.price, sym):>12}  {s.action}")
        print("\nWHY THESE STOCKS")
        for i, s in enumerate(picks, 1):
            print(f"\n{i}. {s.ticker} - {s.name} ({s.sector})   score {s.score:.0f}/100")
            for g in strengths(s):
                print(f"   + {g}")
            for b in concerns(s):
                print(f"   - {b}")
            if s.plan:
                print(f"   Plan: {_plan_line(s, sym)}")
    else:
        print("NO STOCK PASSES THE BAR RIGHT NOW.")
        print("   In a weak market, holding cash is a valid position. Re-run the scan next week.")

    watch = [s for s in scan.ranked if s.rating == "WATCH"][:8]
    if watch:
        print("\nWATCHLIST (good but not ready yet): "
              + ", ".join(f"{s.ticker} ({s.score:.0f})" for s in watch))
    avoided = [s for s in scan.ranked if s.rating == "AVOID"]
    if avoided:
        print(f"Avoided: {len(avoided)} stock{'s' if len(avoided) != 1 else ''} (reasons are in the CSV/HTML report)")
    if scan.screened:
        print(f"Left out by your filters: {len(scan.screened)} stock{'s' if len(scan.screened) != 1 else ''} (listed in the CSV)")
    print_sectors(scan)
    if scan.failed:
        print(f"Skipped (no data): {', '.join(sorted(scan.failed))}")
    if paths:
        print("\nReports saved:")
        for p in paths:
            print(f"   {p}")
    print("\n" + DISCLAIMER)


# ---------------------------------------------------------------- CSV
CSV_METRICS_F = ["pe", "forward_pe", "peg", "pb", "ev_ebitda", "roe", "roa", "operating_margin", "net_margin",
                 "revenue_growth", "earnings_growth", "debt_to_equity", "current_ratio", "fcf_margin",
                 "fcf_yield", "dividend_yield", "beta", "market_cap", "analyst_target", "analyst_upside",
                 "recommendation"]
CSV_METRICS_T = ["rsi", "sma50", "sma200", "atr", "ret_1m", "ret_3m", "ret_6m", "ret_12m", "rs_3m", "rs_6m",
                 "pct_from_high", "volatility", "max_drawdown", "updown_volume", "avg_traded_value"]


def write_csv(scan: ScanResult, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    head = ["rank", "ticker", "name", "sector", "cap", "pe", "left_out_because", "price", "score", "fundamental_score", "technical_score",
            "rating", "action", "entry_low", "entry_high", "stop", "target", "shares",
            "strengths", "concerns", "excluded_because"]
    head += [f"f_{k}" for k in CSV_METRICS_F] + [f"t_{k}" for k in CSV_METRICS_T]
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(head)
        for i, s in enumerate(scan.stocks, 1):
            p = s.plan
            fm, tm = s.fundamental.metrics or {}, s.technical.metrics or {}

            def r(x, d=4):
                v = finite(x)
                return "" if v is None else round(v, d)

            row = [i, s.ticker, s.name, s.sector, s.cap or "", r(s.pe, 2), " | ".join(s.screened_out),
                   r(s.price, 2), r(s.score, 1), r(s.fundamental.score, 1),
                   r(s.technical.score, 1), s.rating, s.action,
                   r(p.entry_low, 2) if p else "", r(p.entry_high, 2) if p else "",
                   r(p.stop, 2) if p else "", r(p.target, 2) if p else "",
                   p.shares if p and p.shares is not None else "",
                   " | ".join(s.fundamental.strengths + s.technical.strengths),
                   " | ".join(s.fundamental.concerns + s.technical.concerns + s.notes),
                   " | ".join(s.excluded)]
            row += [fm.get(k) if isinstance(fm.get(k), str) else r(fm.get(k)) for k in CSV_METRICS_F]
            row += [r(tm.get(k)) for k in CSV_METRICS_T]
            w.writerow(row)
    return path


# ---------------------------------------------------------------- HTML
def _e(x) -> str:
    return html.escape(str(x))


def _bar(score: Optional[float]) -> str:
    if score is None:
        return '<span class="muted">-</span>'
    tone = "good" if score >= 65 else "mid" if score >= 50 else "bad"
    return (f'<span class="score {tone}"><span class="track"><span class="fill" '
            f'style="width:{max(0, min(100, score)):.0f}%"></span></span><b>{score:.0f}</b></span>')


def _badge(rating: str) -> str:
    cls = {"TOP PICK": "top", "BUY": "buy", "WATCH": "watch"}.get(rating, "avoid")
    return f'<span class="badge {cls}">{_e(rating)}</span>'


def _card(i: int, s: StockResult, sym: str) -> str:
    fm, tm = s.fundamental.metrics or {}, s.technical.metrics or {}
    pros = "".join(f"<li>{_e(x)}</li>" for x in strengths(s, 4))
    cons = "".join(f"<li>{_e(x)}</li>" for x in concerns(s, 3))
    stats = [
        ("Size", f"{s.cap} cap" if s.cap else "n/a"),
        ("P/E", _pe(s)), ("Fwd P/E", num(fm.get("forward_pe"))), ("ROE", pct(fm.get("roe"))),
        ("Op. margin", pct(fm.get("operating_margin"))), ("Revenue growth", pct(fm.get("revenue_growth"), sign=True)),
        ("Debt/Equity", num(fm.get("debt_to_equity"), 2)), ("Div. yield", pct(fm.get("dividend_yield"), 1)),
        ("Market cap", big_money(fm.get("market_cap"), sym)), ("RSI", num(tm.get("rsi"), 0)),
        ("6m vs index", pct(tm.get("rs_6m"), sign=True)), ("From 52w high", pct(tm.get("pct_from_high"), sign=True)),
        ("Volatility", pct(tm.get("volatility"))),
    ]
    up = fm.get("analyst_upside")
    if up is not None:
        stats.append(("Analyst target", f"{money(fm.get('analyst_target'), sym)} ({pct(up, sign=True)})"))
    stat_html = "".join(f"<div><dt>{_e(k)}</dt><dd>{_e(v)}</dd></div>" for k, v in stats)
    plan = ""
    if s.plan:
        p = s.plan
        size = ""
        if p.shares is not None:
            size = (f"<div><dt>Position</dt><dd>{p.shares:,} shares ≈ {_e(money(p.position_value, sym, 0))}"
                    f" (risk {_e(money(p.risk_amount, sym, 0))})</dd></div>")
        plan = (
            '<div class="plan"><h4>Trade plan</h4><dl>'
            f"<div><dt>Entry zone</dt><dd>{_e(money(p.entry_low, sym))} - {_e(money(p.entry_high, sym))}</dd></div>"
            f"<div><dt>Stop-loss</dt><dd>{_e(money(p.stop, sym))} ({_e(pct(p.stop_pct, 1, True))})</dd></div>"
            f"<div><dt>Target</dt><dd>{_e(money(p.target, sym))} ({_e(pct(p.target_pct, 1, True))})</dd></div>"
            f"{size}</dl><p class=\"muted\">{_e(p.note)}</p></div>"
        )
    return f"""
<article class="card">
  <header>
    <div><span class="rank">#{i}</span> <strong>{_e(s.ticker)}</strong> <span class="muted">{_e(s.name)} · {_e(s.sector)}</span></div>
    <div>{_badge(s.rating)} <span class="action">{_e(s.action)}</span></div>
  </header>
  <div class="scores">
    <div><span class="muted">Overall</span>{_bar(s.score)}</div>
    <div><span class="muted">Fundamental</span>{_bar(s.fundamental.score)}</div>
    <div><span class="muted">Technical</span>{_bar(s.technical.score)}</div>
    <div><span class="muted">Price</span><strong>{_e(money(s.price, sym))}</strong></div>
  </div>
  <div class="cols">
    <div><h4>Strengths</h4><ul class="pros">{pros or '<li class="muted">-</li>'}</ul></div>
    <div><h4>Watch out for</h4><ul class="cons">{cons or '<li class="muted">Nothing major flagged</li>'}</ul></div>
  </div>
  <dl class="stats">{stat_html}</dl>
  {plan}
</article>"""


CSS = """
:root{--bg:#f6f7f9;--panel:#fff;--text:#1b1f24;--muted:#5d6670;--line:#e3e6ea;--good:#1a7f4b;--good-bg:#e3f4ea;
--mid:#9a6700;--mid-bg:#fff4d6;--bad:#b42318;--bad-bg:#fde8e6;--accent:#2952cc;--accent-bg:#e7edff}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#0f1216;--panel:#171b21;--text:#e7eaee;
--muted:#97a1ab;--line:#2a3038;--good:#4cc38a;--good-bg:#12291e;--mid:#e3b341;--mid-bg:#2b2410;--bad:#f47067;
--bad-bg:#2d1615;--accent:#7b9cff;--accent-bg:#18213b}}
:root[data-theme="dark"]{--bg:#0f1216;--panel:#171b21;--text:#e7eaee;--muted:#97a1ab;--line:#2a3038;--good:#4cc38a;
--good-bg:#12291e;--mid:#e3b341;--mid-bg:#2b2410;--bad:#f47067;--bad-bg:#2d1615;--accent:#7b9cff;--accent-bg:#18213b}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);
font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:1180px;margin:0 auto;padding:24px 16px 48px}h1{font-size:24px;margin:0 0 4px}
h2{font-size:18px;margin:32px 0 12px}h4{margin:0 0 6px;font-size:13px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
.muted{color:var(--muted)}.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px 18px}
.regime{border-left:6px solid var(--mid)}.regime.Bull{border-left-color:var(--good)}.regime.Bear{border-left-color:var(--bad)}
.regime h2{margin:0 0 8px}.regime ul{margin:6px 0 0;padding-left:20px}.grid2{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.demo{background:var(--mid-bg);color:var(--mid);padding:8px 12px;border-radius:8px;margin:12px 0;font-weight:600}
.tablewrap{overflow-x:auto;background:var(--panel);border:1px solid var(--line);border-radius:12px}
table{border-collapse:collapse;width:100%;font-size:14px}th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}
th{cursor:pointer;user-select:none;color:var(--muted);font-weight:600;position:sticky;top:0;background:var(--panel)}
td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}tr:last-child td{border-bottom:0}
.score{display:inline-flex;align-items:center;gap:6px;vertical-align:middle}
.score .track{width:56px;height:8px;border-radius:4px;background:var(--line);overflow:hidden}
.score .fill{display:block;height:100%}.score.good .fill{background:var(--good)}.score.mid .fill{background:var(--mid)}
.score.bad .fill{background:var(--bad)}.score b{min-width:2ch;text-align:right;font-variant-numeric:tabular-nums}
.badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;font-weight:700}
.badge.top{background:var(--good);color:#fff}.badge.buy{background:var(--good-bg);color:var(--good)}
.badge.watch{background:var(--mid-bg);color:var(--mid)}.badge.avoid{background:var(--bad-bg);color:var(--bad)}
.cards{display:grid;gap:16px}.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px 18px}
.card header{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:12px}
.rank{color:var(--muted);font-weight:700}.action{font-size:13px;color:var(--muted);margin-left:6px}
.scores{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:12px}
.scores>div{display:flex;flex-direction:column;gap:4px;font-size:13px}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:16px}.cols ul{margin:0;padding-left:18px}
.pros li::marker{content:"+  ";color:var(--good);font-weight:700}.cons li::marker{content:"−  ";color:var(--bad);font-weight:700}
dl{margin:0}.stats{display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:8px 16px;margin-top:14px;
padding-top:12px;border-top:1px solid var(--line)}dt{font-size:12px;color:var(--muted)}dd{margin:0;font-weight:600;font-variant-numeric:tabular-nums}
.plan{margin-top:14px;padding:12px;border-radius:10px;background:var(--accent-bg)}.plan dl{display:grid;
grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:8px 16px}.plan p{margin:8px 0 0;font-size:13px}
.small{font-size:13px}footer{margin-top:32px;font-size:13px}
@media (max-width:720px){.grid2,.cols{grid-template-columns:1fr}.scores{grid-template-columns:1fr 1fr}}
"""

SORT_JS = """
document.querySelectorAll('table.sortable th').forEach(function(th, col){
  th.addEventListener('click', function(){
    var tbody = th.closest('table').tBodies[0];
    var rows = Array.prototype.slice.call(tbody.rows);
    var asc = th.dataset.dir !== 'asc'; th.dataset.dir = asc ? 'asc' : 'desc';
    rows.sort(function(a, b){
      var x = a.cells[col].dataset.v || a.cells[col].textContent, y = b.cells[col].dataset.v || b.cells[col].textContent;
      var nx = parseFloat(x), ny = parseFloat(y);
      var c = (!isNaN(nx) && !isNaN(ny)) ? nx - ny : x.localeCompare(y);
      return asc ? c : -c;
    });
    rows.forEach(function(r){ tbody.appendChild(r); });
  });
});
"""


CAP_RANK = {"Large": 0, "Mid": 1, "Small": 2}


def _sector_html(scan: ScanResult) -> str:
    rows = sector_summary(scan)
    if not rows:
        return '<p class="muted">No sector data.</p>'
    limit = scan.screen.max_pe
    sizes = " + ".join(scan.screen.caps).lower() + " caps" if scan.screen.caps else "stocks"
    lim = f"P/E ≤ {limit:g}" if limit is not None else "With a P/E"
    out = []
    for r in rows:
        cheap = limit is not None and r["median_pe"] is not None and r["median_pe"] <= limit
        tag = ' <span class="badge buy">below limit</span>' if cheap else ""
        best = ", ".join(f"{_tick(b.ticker, scan.market.suffix)} ({b.rating.lower()} {b.score:.0f})"
                         for b in r["best"])
        out.append(
            "<tr>"
            f"<td>{_e(r['sector'])}{tag}</td>"
            f'<td class="num">{r["stocks"]}</td>'
            f'<td class="num" data-v="{r["median_pe"] if r["median_pe"] is not None else 9999}">'
            f"{_e(num(r['median_pe']))}</td>"
            f'<td class="num">{r["under_limit"]} of {r["stocks"]}</td>'
            f"<td>{_e(best or '-')}</td>"
            "</tr>"
        )
    return (f'<p class="small muted">Median trailing P/E of the {_e(sizes)} in this scan, cheapest sector first. '
            "A low P/E alone is not a buy signal: banks, metals and oil companies usually trade cheaper than "
            "consumer or tech companies.</p>"
            '<div class="tablewrap"><table class="sortable"><thead><tr><th>Sector</th><th class="num">Stocks</th>'
            f'<th class="num">Median P/E</th><th class="num">{_e(lim)}</th><th>Best value stocks (rating, score)</th>'
            f"</tr></thead><tbody>{''.join(out)}</tbody></table></div>")


def write_html(scan: ScanResult, path: Path, top: int = 10) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    mk, rg, st = scan.market, scan.regime, scan.strategy
    sym = mk.symbol
    picks = scan.picks(top)

    checks = "".join(f"<li>{_e(c)}</li>" for c in rg.checks)
    guidance = "".join(f"<li>{_e(g)}</li>" for g in rg.guidance)
    auto = " <span class=\"muted\">(auto-selected for this market)</span>" if scan.requested_strategy == "auto" else ""

    if picks:
        cards = "".join(_card(i, s, sym) for i, s in enumerate(picks, 1))
    else:
        cards = ('<div class="panel">No stock passes the bar right now. In a weak market, holding cash is a '
                 'valid position. Check the watchlist below and re-run the scan next week.</div>')

    rows = []
    for i, s in enumerate(scan.ranked, 1):
        why = "; ".join(s.excluded) if s.excluded else "; ".join(concerns(s, 1)[:2])
        rows.append(
            "<tr>"
            f'<td class="num">{i}</td><td><strong>{_e(s.ticker)}</strong></td><td>{_e(s.name)}</td>'
            f"<td>{_e(s.sector)}</td>"
            f'<td class="num" data-v="{s.price or 0}">{_e(money(s.price, sym))}</td>'
            f'<td class="num" data-v="{s.pe if s.pe is not None else 9999}">{_e(_pe(s))}</td>'
            f'<td data-v="{CAP_RANK.get(s.cap, 9)}">{_e(s.cap or "-")}</td>'
            f'<td data-v="{s.score or 0}">{_bar(s.score)}</td>'
            f'<td data-v="{s.fundamental.score or 0}">{_bar(s.fundamental.score)}</td>'
            f'<td data-v="{s.technical.score or 0}">{_bar(s.technical.score)}</td>'
            f'<td data-v="{["TOP PICK", "BUY", "WATCH", "AVOID"].index(s.rating)}">{_badge(s.rating)}</td>'
            f"<td>{_e(s.action)}</td>"
            f'<td class="num" data-v="{(s.technical.metrics or {}).get("rsi") or 0}">'
            f"{_e(num((s.technical.metrics or {}).get('rsi'), 0))}</td>"
            f'<td class="small muted">{_e(why)}</td>'
            "</tr>"
        )
    screened = ""
    if scan.screened:
        pe_out = sum(1 for x in scan.screened if any("P/E" in r or "Loss" in r for r in x.screened_out))
        cap_out = sum(1 for x in scan.screened if not x.cap_ok)
        screened = (f'<p class="small muted">{len(scan.screened)} stocks left out by your filters '
                    f"({pe_out} on P/E, {cap_out} on company size). They are listed in the CSV.</p>")
    sectors = _sector_html(scan)
    filters = f'<p class="small"><strong>{_e(_screen_line(scan))}</strong></p>' if scan.screen.active else ""
    failed = ""
    if scan.failed:
        failed = ('<p class="small muted">Skipped (no data): '
                  + _e(", ".join(f"{k} ({v})" for k, v in sorted(scan.failed.items()))) + "</p>")
    capital = ""
    if scan.capital:
        capital = (f"<p class=\"small\">Position sizes assume capital of {_e(money(scan.capital, sym, 0))}, "
                   f"risking {_e(pct(scan.risk_pct, 1))} per trade, max {_e(pct(rg.max_position_pct))} per stock.</p>")

    fw = ", ".join(f"{k} {v:.0%}" for k, v in st.fund_weights.items() if v)
    tw = ", ".join(f"{k.replace('_', ' ')} {v:.0%}" for k, v in st.tech_weights.items() if v)
    demo = '<div class="demo">DEMO DATA: these are made-up companies to show the report layout.</div>' if scan.demo else ""

    doc = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stock Scan {_e(mk.name)} {scan.generated_at:%Y-%m-%d}</title><style>{CSS}</style></head>
<body><main>
<h1>Stock scan: {_e(mk.name)}</h1>
<div class="muted">{scan.generated_at:%d %b %Y, %H:%M} · {len(scan.ranked)} stocks analysed · benchmark {_e(mk.benchmark_name)}</div>
{demo}
<h2>1. Market condition</h2>
<div class="grid2">
  <section class="panel regime {_e(rg.label)}"><h2>{_e(rg.label)} market</h2><p>{_e(rg.headline)}</p><ul>{checks}</ul></section>
  <section class="panel"><h4>What to do</h4><ul>{guidance}</ul></section>
</div>
<h2>2. Strategy</h2>
<div class="panel"><strong>{_e(st.title)}</strong>{auto}<p>{_e(st.description)}</p>
<p class="small muted">Final score = {st.composite[0]:.0%} fundamental + {st.composite[1]:.0%} technical.
Fundamental pillars: {_e(fw)}. Technical pillars: {_e(tw)}.</p>{filters}{capital}</div>
<h2>3. Best stocks right now ({len(picks)})</h2>
<div class="cards">{cards}</div>
<h2>4. Sector valuations</h2>
{sectors}
<h2>5. All stocks ranked</h2>
<p class="small muted">Click a column heading to sort.</p>
<div class="tablewrap"><table class="sortable"><thead><tr>
<th class="num">#</th><th>Ticker</th><th>Name</th><th>Sector</th><th class="num">Price</th><th class="num">P/E</th>
<th>Cap</th><th>Score</th><th>Fund.</th>
<th>Tech.</th><th>Rating</th><th>Action</th><th class="num">RSI</th><th>Main concern / why avoided</th>
</tr></thead><tbody>{''.join(rows)}</tbody></table></div>
{screened}{failed}
<h2>6. How the scores work</h2>
<div class="panel small">
<p><strong>Fundamental score</strong> (0-100): quality (ROE, margins, ROA), financial strength (debt/equity,
current ratio, free-cash-flow margin), growth (revenue, earnings, forward EPS), valuation (P/E, forward P/E, PEG,
P/B, FCF yield, EV/EBITDA) and income (dividend yield, payout ratio). Banks and insurers skip the leverage and
cash-flow tests, which do not apply to them.</p>
<p><strong>Technical score</strong> (0-100): trend (price vs 20/50/200-day averages and their slope),
relative strength vs the index over 3 and 6 months, RSI, MACD, risk (volatility, 1-year drawdown, beta),
distance from the 52-week high, and up/down volume.</p>
<p><strong>Ratings</strong>: TOP PICK ≥ {RATING_THRESHOLDS["TOP PICK"] + rg.threshold_bump:.0f},
BUY ≥ {RATING_THRESHOLDS["BUY"] + rg.threshold_bump:.0f}, WATCH ≥ {RATING_THRESHOLDS["WATCH"] + rg.threshold_bump:.0f}
(the bar is higher in weak markets). A BUY also needs both the fundamental and technical score to be at least
{MIN_PILLAR_FOR_BUY:.0f}, and the stock must pass the strategy's hard rules.</p>
<p><strong>Trade plan</strong>: stop-loss = {ATR_MULTIPLE:g} × ATR below the entry (between {MIN_STOP_PCT:.0%} and
{MAX_STOP_PCT:.0%}), first target = {REWARD_MULTIPLE:g} × the risk. Position size = the amount you are willing to lose ÷ the distance to the stop, capped per stock.</p>
</div>
<footer class="panel muted">{_e(DISCLAIMER)}</footer>
</main><script>{SORT_JS}</script></body></html>"""
    path.write_text(doc, encoding="utf-8")
    return path
