"""Fundamental analysis from the company data Yahoo Finance publishes (`Ticker.info`)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Mapping, Optional

from .config import Strategy
from .util import finite, num, pct, scale, weighted_average

FINANCIAL_SECTORS = {"Financial Services", "Financial", "Financials"}


@dataclass
class FundamentalResult:
    score: Optional[float]
    parts: Dict[str, Optional[float]]
    metrics: Dict[str, Any]
    strengths: List[str] = field(default_factory=list)
    concerns: List[str] = field(default_factory=list)
    coverage: float = 0.0
    is_financial: bool = False


def _get(info: Mapping[str, Any], *keys: str) -> Optional[float]:
    for k in keys:
        v = finite(info.get(k))
        if v is not None:
            return v
    return None


def _ratio(a: Optional[float], b: Optional[float]) -> Optional[float]:
    if a is None or b is None or b == 0:
        return None
    return a / b


def _dividend_yield(info: Mapping[str, Any], price: Optional[float]) -> Optional[float]:
    rate = _get(info, "dividendRate", "trailingAnnualDividendRate")
    if rate is not None and price:
        return max(0.0, rate / price)
    trailing = _get(info, "trailingAnnualDividendYield")
    if trailing is not None:
        return max(0.0, trailing)
    dy = _get(info, "dividendYield")
    if dy is not None:  # newer yfinance versions report this one in percent
        return max(0.0, dy / 100.0)
    # Company data is present but no dividend fields at all: treat as a non-payer.
    return 0.0 if info.get("marketCap") is not None else None


def extract_metrics(info: Mapping[str, Any]) -> Dict[str, Any]:
    price = _get(info, "currentPrice", "regularMarketPrice", "previousClose")
    trailing_eps = _get(info, "trailingEps")
    forward_eps = _get(info, "forwardEps")
    fcf = _get(info, "freeCashflow")
    revenue = _get(info, "totalRevenue")
    mcap = _get(info, "marketCap")
    de = _get(info, "debtToEquity")  # Yahoo reports this as a percentage (150 = 1.5x)
    target = _get(info, "targetMeanPrice")

    fwd_eps_growth = None
    if trailing_eps is not None and trailing_eps > 0 and forward_eps is not None:
        fwd_eps_growth = forward_eps / trailing_eps - 1

    return {
        "name": info.get("longName") or info.get("shortName"),
        "sector": info.get("sector"),
        "industry": info.get("industry"),
        "currency": info.get("currency"),
        "price": price,
        "market_cap": mcap,
        "pe": _get(info, "trailingPE"),
        "forward_pe": _get(info, "forwardPE"),
        "peg": _get(info, "pegRatio", "trailingPegRatio"),
        "pb": _get(info, "priceToBook"),
        "ev_ebitda": _get(info, "enterpriseToEbitda"),
        "roe": _get(info, "returnOnEquity"),
        "roa": _get(info, "returnOnAssets"),
        "gross_margin": _get(info, "grossMargins"),
        "operating_margin": _get(info, "operatingMargins"),
        "net_margin": _get(info, "profitMargins"),
        "revenue_growth": _get(info, "revenueGrowth"),
        "earnings_growth": _get(info, "earningsGrowth", "earningsQuarterlyGrowth"),
        "forward_eps_growth": fwd_eps_growth,
        "debt_to_equity": de / 100.0 if de is not None else None,
        "current_ratio": _get(info, "currentRatio"),
        "free_cash_flow": fcf,
        "fcf_margin": _ratio(fcf, revenue),
        "fcf_yield": _ratio(fcf, mcap),
        "dividend_yield": _dividend_yield(info, price),
        "payout_ratio": _get(info, "payoutRatio"),
        "beta": _get(info, "beta"),
        "trailing_eps": trailing_eps,
        "forward_eps": forward_eps,
        "analyst_target": target,
        "analyst_upside": _ratio(target, price) - 1 if _ratio(target, price) is not None else None,
        "analyst_count": _get(info, "numberOfAnalystOpinions"),
        "recommendation": info.get("recommendationKey"),
    }


def score_parts(m: Mapping[str, Any], strategy: Strategy, financial: bool, bank: bool) -> Dict[str, Optional[float]]:
    parts: Dict[str, Optional[float]] = {}

    parts["quality"], _ = weighted_average(
        {
            "roe": scale(m["roe"], 0.05, 0.25),
            "op": scale(m["operating_margin"], 0.05, 0.30),
            "net": scale(m["net_margin"], 0.02, 0.20),
            "roa": scale(m["roa"], 0.005, 0.02) if bank else scale(m["roa"], 0.02, 0.12),
        },
        {"roe": 0.4, "op": 0.2, "net": 0.2, "roa": 0.2},
    )

    if financial:
        # Leverage, current ratio and FCF are not meaningful for banks/insurers.
        parts["strength"] = None
    else:
        parts["strength"], _ = weighted_average(
            {
                "de": scale(m["debt_to_equity"], 2.0, 0.3),
                "cr": scale(m["current_ratio"], 0.8, 2.0),
                "fcf": scale(m["fcf_margin"], -0.05, 0.15),
            },
            {"de": 0.4, "cr": 0.25, "fcf": 0.35},
        )

    parts["growth"], _ = weighted_average(
        {
            "rev": scale(m["revenue_growth"], -0.05, 0.20),
            "eps": scale(m["earnings_growth"], -0.10, 0.25),
            "fwd": scale(m["forward_eps_growth"], -0.05, 0.20),
        },
        {"rev": 0.4, "eps": 0.35, "fwd": 0.25},
    )

    loss_making = m["trailing_eps"] is not None and m["trailing_eps"] <= 0
    fpe = m["forward_pe"]
    pe = m["pe"]
    peg = m["peg"]
    ev = m["ev_ebitda"]
    parts["valuation"], _ = weighted_average(
        {
            "fpe": (0.0 if fpe <= 0 else scale(fpe, 40, 10)) if fpe is not None else None,
            "pe": 0.0 if loss_making else scale(pe, 45, 12),
            "peg": scale(peg, 3.0, 0.8) if peg is not None and peg > 0 else None,
            "pb": scale(m["pb"], 4.0, 1.0) if financial else scale(m["pb"], 8.0, 1.5),
            "fcfy": None if financial else scale(m["fcf_yield"], 0.0, 0.06),
            "ev": None if financial or ev is None or ev <= 0 else scale(ev, 25, 8),
        },
        {"fpe": 0.3, "pe": 0.2, "peg": 0.15, "pb": 0.1, "fcfy": 0.15, "ev": 0.1},
    )

    payout = m["payout_ratio"]
    parts["income"], _ = weighted_average(
        {
            "yield": scale(m["dividend_yield"], 0.0, 0.04),
            "payout": scale(payout, 1.2, 0.6) if payout is not None and payout > 0 else None,
        },
        {"yield": 0.7, "payout": 0.3},
    )
    return parts


def describe(m: Mapping[str, Any], financial: bool):
    good: List[str] = []
    bad: List[str] = []

    roe = m["roe"]
    if roe is not None:
        if roe >= 0.18:
            good.append(f"High return on equity ({pct(roe)})")
        elif roe < 0.08:
            bad.append(f"Low return on equity ({pct(roe)})")
    om = m["operating_margin"]
    if om is not None:
        if om >= 0.25:
            good.append(f"Strong operating margin ({pct(om)})")
        elif om < 0.05:
            bad.append(f"Thin operating margin ({pct(om)})")

    if not financial:
        de = m["debt_to_equity"]
        if de is not None:
            if de <= 0.5:
                good.append(f"Low debt (debt/equity {num(de, 2)}x)")
            elif de >= 1.5:
                bad.append(f"High debt (debt/equity {num(de, 2)}x)")
        fm = m["fcf_margin"]
        if fm is not None:
            if fm >= 0.10:
                good.append(f"Strong free cash flow ({pct(fm)} of sales)")
            elif fm < 0:
                bad.append("Negative free cash flow")
        cr = m["current_ratio"]
        if cr is not None and cr < 0.8:
            bad.append(f"Weak liquidity (current ratio {num(cr, 2)})")

    rg = m["revenue_growth"]
    if rg is not None:
        if rg >= 0.10:
            good.append(f"Revenue growing {pct(rg, sign=True)}")
        elif rg < 0:
            bad.append(f"Revenue shrinking {pct(rg, sign=True)}")
    eg = m["earnings_growth"]
    if eg is not None:
        if eg >= 0.15:
            good.append(f"Earnings growing {pct(eg, sign=True)}")
        elif eg < -0.10:
            bad.append(f"Earnings falling {pct(eg, sign=True)}")

    if m["trailing_eps"] is not None and m["trailing_eps"] <= 0:
        bad.append("Loss-making (negative earnings per share)")
    fpe = m["forward_pe"]
    if fpe is not None and fpe > 0:
        if fpe <= 15:
            good.append(f"Attractive valuation (forward P/E {num(fpe)})")
        elif fpe >= 40:
            bad.append(f"Expensive (forward P/E {num(fpe)})")
    peg = m["peg"]
    if peg is not None and 0 < peg <= 1.0:
        good.append(f"PEG {num(peg, 2)}: growth at a reasonable price")

    dy = m["dividend_yield"]
    if dy is not None and dy >= 0.025:
        good.append(f"Dividend yield {pct(dy, 1)}")
    payout = m["payout_ratio"]
    if payout is not None and payout > 1.0:
        bad.append(f"Paying out more than it earns (payout {pct(payout)})")
    return good, bad


def analyze_fundamental(info: Optional[Mapping[str, Any]], strategy: Strategy) -> FundamentalResult:
    if not info:
        return FundamentalResult(None, {}, {}, [], ["No company data available"], 0.0)
    m = extract_metrics(info)
    financial = (m["sector"] or "") in FINANCIAL_SECTORS
    bank = financial and "bank" in (m["industry"] or "").lower()
    parts = score_parts(m, strategy, financial, bank)
    score, coverage = weighted_average(parts, strategy.fund_weights)
    good, bad = describe(m, financial)
    return FundamentalResult(score, parts, m, good, bad, coverage, financial)
