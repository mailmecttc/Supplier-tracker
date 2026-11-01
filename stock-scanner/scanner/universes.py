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

# Added by --universe extended. Mostly AMFI large and mid caps outside the core list.
# The size category shown in reports is worked out from each company's live market cap,
# so it does not matter if a company has moved between categories since this list was made.
INDIA_MORE_LARGE_AND_MID = tuple(
    f"{s}.NS"
    for s in (
        # Banks
        "CANBK", "PNB", "UNIONBANK", "BANKINDIA", "INDIANB", "IDFCFIRSTB", "FEDERALBNK", "AUBANK",
        "YESBANK", "BANDHANBNK",
        # NBFCs, housing and government finance
        "CHOLAFIN", "MUTHOOTFIN", "MANAPPURAM", "SBICARD", "LICHSGFIN", "PFC", "RECLTD", "IRFC", "HUDCO",
        "IREDA", "SUNDARMFIN", "M&MFIN", "LTF", "ABCAPITAL", "POONAWALLA", "BAJAJHLDNG",
        # Insurance, AMCs, exchanges, fintech
        "LICI", "ICICIGI", "ICICIPRULI", "MFSL", "POLICYBZR", "PAYTM", "HDFCAMC", "NAM-INDIA", "BSE",
        "CDSL", "MCX", "ANGELONE",
        # IT and internet
        "PERSISTENT", "COFORGE", "MPHASIS", "LTTS", "OFSS", "KPITTECH", "TATAELXSI", "TATATECH", "NAUKRI",
        "SWIGGY", "NYKAA",
        # Capital goods, electricals, defence, railways
        "CUMMINSIND", "POLYCAB", "KEI", "BHARATFORG", "SUPREMEIND", "ASTRAL", "APLAPOLLO", "THERMAX",
        "BHEL", "CGPOWER", "SCHAEFFLER", "TIMKEN", "AIAENG", "GMRAIRPORT", "CONCOR", "BDL", "MAZDOCK",
        "COCHINSHIP", "SOLARINDS", "SUZLON", "WAAREEENER", "DIXON", "VOLTAS", "BLUESTARCO", "KAYNES",
        "CROMPTON", "HONAUT", "IRCTC", "RVNL", "NBCC",
        # Consumer
        "COLPAL", "MARICO", "EMAMILTD", "JUBLFOOD", "PAGEIND", "VBL", "UNITDSPR", "RADICO", "BATAINDIA",
        "KALYANKJIL", "PATANJALI", "BERGEPAIN", "INDHOTEL",
        # Autos and auto parts
        "TVSMOTOR", "HYUNDAI", "ASHOKLEY", "MOTHERSON", "BOSCHLTD", "MRF", "BALKRISIND", "APOLLOTYRE",
        "EXIDEIND", "SONACOMS", "UNOMINDA", "ESCORTS", "TIINDIA",
        # Pharma and hospitals
        "LUPIN", "AUROPHARMA", "ALKEM", "TORNTPHARM", "ZYDUSLIFE", "MANKIND", "GLENMARK", "BIOCON",
        "IPCALAB", "ABBOTINDIA", "LAURUSLABS", "FORTIS", "SYNGENE",
        # Chemicals, fertilisers, cement, metals
        "SRF", "PIIND", "UPL", "DEEPAKNTR", "COROMANDEL", "TATACHEM", "NAVINFLUOR", "ACC", "AMBUJACEM",
        "SHREECEM", "DALBHARAT", "JKCEMENT", "JINDALSTEL", "SAIL", "NMDC", "NATIONALUM", "HINDZINC",
        "VEDL", "JSL",
        # Power, oil and gas
        "TATAPOWER", "ADANIPOWER", "ADANIGREEN", "ADANIENSOL", "NTPCGREEN", "NHPC", "TORNTPOWER",
        "JSWENERGY", "GAIL", "PETRONET", "HINDPETRO", "OIL", "IGL", "ATGL",
        # Real estate
        "DLF", "GODREJPROP", "OBEROIRLTY", "PRESTIGE", "PHOENIXLTD", "LODHA",
        # Telecom
        "IDEA", "INDUSTOWER", "TATACOMM", "BHARTIHEXA",
    )
)

US_MID_CAPS = (
    "DKS", "WSM", "OC", "LII", "EME", "FIX", "TOL", "PHM", "ALLY", "EWBC", "WAL", "JEF", "RGA", "UNM",
    "EXEL", "NBIX", "MEDP", "UTHR", "DECK", "CROX", "BURL", "CASY", "USFD", "SAIA", "XPO", "JBHT",
    "AYI", "ATR", "CSL", "GGG", "ITT", "TTC", "CHE", "EHC", "MANH", "PSTG", "NTNX", "DT", "AKAM",
    "FFIV", "GDDY", "DUOL", "ELF", "CAG", "SJM", "CPB", "HRL", "TPR", "RL", "LKQ", "GPC", "ALV",
    "LEA", "SWKS", "TER", "ENTG", "CIEN", "COHR",
)
