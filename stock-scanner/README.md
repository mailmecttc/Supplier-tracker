# Stock Scanner: fundamental + technical analysis

Scans a list of stocks, checks each **business** (fundamentals) and **chart**
(technicals), reads the **overall market trend**, and ranks the best candidates
with a ready-made trade plan (entry zone, stop-loss, target, position size).

It is built for a weak market. When the index is falling, it automatically
switches to a defensive strategy, raises the bar for a BUY, and suggests smaller
positions and buying in parts.

> **Not financial advice.** This is a screening tool that shortlists stocks.
> Data can be delayed or wrong. Always check the company yourself before buying,
> and use stop-losses.

## Quick start

Needs Python 3.9+ and internet access (data comes free from Yahoo Finance).

```bash
cd stock-scanner
pip install -r requirements.txt

python -m scanner                         # India (default): ~60 NSE large caps, benchmark NIFTY 50
python -m scanner --market us             # ~120 US large caps, benchmark S&P 500
python -m scanner --demo                  # offline sample with made-up data
```

A full scan takes 1-3 minutes the first time. Data is cached for 12 hours, so re-runs are fast.
Each run prints a summary and saves `reports/scan_<market>_<date>.html` (open it in a browser)
and a `.csv` with every number.

### First time on Windows, step by step

1. Install Python from <https://www.python.org/downloads/>. On the first installer screen,
   tick **"Add python.exe to PATH"**.
2. On GitHub, open this repo, switch to the branch that has `stock-scanner/`, click
   **Code → Download ZIP**, and unzip it.
3. Open the unzipped `stock-scanner` folder, click the address bar, type `cmd` and press Enter.
4. In the black window, run once: `pip install -r requirements.txt`
5. Then run: `python -m scanner --capital 500000` (use your own amount).
6. Open the newest `.html` file in the `reports` folder.

On a Mac, use the Terminal app and `python3` / `pip3` instead of `python` / `pip`.

### More examples

```bash
# Your own list (India symbols get ".NS" added automatically)
python -m scanner --tickers TCS INFY HDFCBANK PERSISTENT POLYCAB

# From a file (one per line, or comma separated; # comments allowed)
python -m scanner --tickers-file my_watchlist.txt

# Position sizes for ₹5,00,000, risking 1% per trade
python -m scanner --capital 500000 --risk 1

# Force a strategy instead of auto
python -m scanner --market us --strategy value --top 15
python -m scanner --list-strategies
```

### Run it on GitHub instead (no install)

Open the repo's **Actions** tab, choose **Stock scan**, then **Run workflow**. Pick the
market and strategy. When it finishes, the summary is on the run page and the HTML/CSV
report is under **Artifacts**. (The workflow appears in the Actions tab once this folder
is on the default branch.)

## How it decides

### 1. Market condition (benchmark index)

| Check | Bull needs |
|---|---|
| Index above its 200-day average | ✓ |
| 50-day average above 200-day average | ✓ |
| 3-month return positive | ✓ |
| Within 10% of its 52-week high | ✓ |

4/4 = **Bull**, 2-3 = **Neutral**, 0-1 = **Bear**. The regime sets:

| | Bull | Neutral | Bear |
|---|---|---|---|
| `auto` strategy | growth | balanced | defensive |
| Extra score needed for BUY | +0 | +2 | +5 |
| Max per stock | 10% | 7% | 5% |
| Suggested cash | ~10% | ~30% | 40-60% |
| Buy in | 1 part | 2 parts | 3 parts |

### 2. Fundamental score (0-100)

| Pillar | What is measured | Good looks like |
|---|---|---|
| Quality | ROE, operating margin, net margin, ROA | ROE ≥ 20-25%, op. margin ≥ 25-30% |
| Strength | Debt/equity, current ratio, free-cash-flow margin | D/E ≤ 0.3-0.5x, FCF margin ≥ 15% |
| Growth | Revenue growth, earnings growth, forward EPS growth | ≥ 20% |
| Valuation | P/E, forward P/E, PEG, P/B, FCF yield, EV/EBITDA | Fwd P/E ≤ 10-15, PEG ≤ 1 |
| Income | Dividend yield, payout ratio | Yield ≥ 4%, payout ≤ 60% |

Banks and insurers skip the debt, current-ratio and cash-flow tests (they don't apply), and use
bank-appropriate ROA and P/B ranges.

### 3. Technical score (0-100)

| Pillar | What is measured |
|---|---|
| Trend | Price vs 20-EMA / 50-DMA / 200-DMA, golden/death cross, slope of the averages |
| Relative strength | 3- and 6-month return vs the index (stocks that hold up in a falling market often lead the rebound) |
| RSI (14) | Rewards healthy momentum, penalises overbought (>70) and collapsing (<30) |
| MACD | Above signal line, histogram rising, above zero |
| Risk | Volatility, 1-year max drawdown, beta |
| Near high | Distance from the 52-week high |
| Volume | Volume on up days vs down days (accumulation) |

### 4. Strategy presets

| Strategy | Fund./Tech. weight | Hard rules (fail = AVOID) |
|---|---|---|
| **defensive** | 60/40 | Profitable, debt/equity ≤ 2, beta ≤ 1.6, not in a confirmed downtrend |
| **balanced** | 50/50 | Profitable, debt/equity ≤ 3, not in a confirmed downtrend |
| **growth** | 55/45 | Revenue growth ≥ 5%, not in a confirmed downtrend |
| **value** | 65/35 | Profitable, debt/equity ≤ 2.5 (may buy below the 200-DMA) |
| **momentum** | 35/65 | Price above the 200-DMA |

All presets also skip illiquid stocks.

### 5. Rating and trade plan

* **TOP PICK** ≥ 72, **BUY** ≥ 62, **WATCH** ≥ 50, else **AVOID** (plus the regime bump above).
  A BUY also needs *both* the fundamental and the technical score ≥ 45, so a great company
  in a collapsing chart (or a hot chart on a weak business) waits on the watchlist.
* **Stop-loss** = 2 × ATR(14) below the entry, kept between 3% and 12%.
* **Target** = 2 × the risk (2:1 reward/risk). Analyst target price is shown alongside.
* **Position size** = (capital × risk %) ÷ (entry − stop), capped by the regime's max per stock.
* If RSI ≥ 70 the action becomes **Buy on pullback** and the entry zone moves lower.

Every threshold is in `scanner/config.py`, `scanner/fundamental.py` and `scanner/technical.py`.

## How to use the results in a falling market

1. Look at the **Market condition** first. In a Bear market, keep a large cash reserve.
2. From the **Best stocks** list, read each stock's strengths and concerns, then check its
   latest quarterly results and news.
3. Buy in parts inside the entry zone, and place the stop-loss order at the same time.
4. Re-run weekly. Stocks that move from WATCH to BUY are often the ones to add next.
   When the index regains its 200-day average, `auto` moves to a more aggressive strategy.

## Project layout

```
scanner/
  cli.py          command line
  engine.py       runs the scan, filters, ratings
  fundamental.py  business scoring
  technical.py    chart scoring
  indicators.py   SMA/EMA/RSI/MACD/ATR etc. (pandas only)
  regime.py       market trend + guidance
  plan.py         entry, stop, target, position size
  config.py       strategies and markets (tune here)
  universes.py    built-in stock lists
  data.py         Yahoo Finance download + cache
  report.py       terminal, CSV and HTML output
  demo.py         made-up data for --demo and tests
tests/            run with: python -m pytest
```

## Limitations

* Yahoo Finance data is free but unofficial. Some fields (PEG, growth, debt) are missing for
  some companies; scores then use whatever is available, and stocks with too little company data
  are scored mostly on price and penalised.
* Valuation uses fixed ranges, not sector averages. Tech will look "expensive" next to banks.
* The built-in lists are large caps and are not official index membership lists. Renamed or
  delisted symbols are skipped with a message; pass your own list for mid/small caps.
* Scores rank stocks against each other today. They don't predict returns.
