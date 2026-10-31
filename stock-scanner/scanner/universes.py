"""Built-in stock lists (Yahoo Finance symbols).

These are liquid large caps chosen to cover every sector. They are a starting
point, not an official index membership list (index members change). Pass your
own list with --tickers or --tickers-file to scan anything else.
A ticker that has been renamed or delisted is skipped with a warning.
"""

US_LARGE_CAPS = (
    # Technology
    "AAPL", "MSFT", "NVDA", "GOOGL", "META", "AVGO", "ORCL", "CRM", "ADBE", "AMD", "INTC", "QCOM",
    "TXN", "CSCO", "IBM", "ACN", "NOW", "INTU", "AMAT", "MU", "LRCX", "KLAC", "ADI", "PANW", "ANET",
    # Consumer
    "AMZN", "TSLA", "HD", "LOW", "MCD", "SBUX", "NKE", "TJX", "BKNG", "COST", "WMT", "PG", "KO",
    "PEP", "PM", "MO", "CL", "KMB", "MDLZ", "GIS",
    # Health care
    "UNH", "LLY", "JNJ", "MRK", "ABBV", "PFE", "TMO", "ABT", "DHR", "BMY", "AMGN", "GILD", "ISRG",
    "MDT", "VRTX", "REGN", "CVS", "CI", "ELV", "SYK",
    # Financials
    "BRK-B", "JPM", "V", "MA", "BAC", "WFC", "GS", "MS", "C", "SCHW", "AXP", "BLK", "SPGI", "CB",
    "PGR", "MMC", "ICE", "CME",
    # Industrials
    "GE", "CAT", "DE", "HON", "RTX", "LMT", "UNP", "UPS", "BA", "ETN", "WM", "ADP",
    # Energy
    "XOM", "CVX", "COP", "EOG", "SLB",
    # Utilities
    "NEE", "DUK", "SO", "AEP",
    # Communication
    "T", "VZ", "TMUS", "CMCSA", "DIS", "NFLX",
    # Materials and real estate
    "LIN", "SHW", "APD", "PLD", "AMT", "O",
)

INDIA_LARGE_CAPS = tuple(
    f"{s}.NS"
    for s in (
        # Banks and financials
        "HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "BANKBARODA", "BAJFINANCE",
        "BAJAJFINSV", "SHRIRAMFIN", "JIOFIN", "SBILIFE", "HDFCLIFE",
        # IT
        "TCS", "INFY", "HCLTECH", "WIPRO", "TECHM", "LTIM",
        # Energy, utilities, metals
        "RELIANCE", "ONGC", "IOC", "BPCL", "NTPC", "POWERGRID", "COALINDIA", "JSWSTEEL", "TATASTEEL",
        "HINDALCO",
        # Consumer
        "HINDUNILVR", "ITC", "NESTLEIND", "BRITANNIA", "TATACONSUM", "DABUR", "GODREJCP", "TITAN",
        "ASIANPAINT", "PIDILITIND", "TRENT", "DMART", "ETERNAL",
        # Autos (TMPV = Tata Motors passenger vehicles after the 2025 demerger)
        "MARUTI", "M&M", "BAJAJ-AUTO", "EICHERMOT", "HEROMOTOCO", "TMPV",
        # Health care
        "SUNPHARMA", "DRREDDY", "CIPLA", "DIVISLAB", "APOLLOHOSP", "MAXHEALTH",
        # Industrials, infra, telecom, cement
        "LT", "BEL", "HAL", "SIEMENS", "ABB", "HAVELLS", "ADANIENT", "ADANIPORTS", "INDIGO",
        "BHARTIARTL", "ULTRACEMCO", "GRASIM",
    )
)
