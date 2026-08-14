package com.aurum.invest.analytics

/**
 * Curated stock lists for the Stocks tab: liquid, US-listed names grouped the
 * way a person browses — by sector. Pure data, no Android imports. Prices,
 * 2-week performance and highlights are always computed live from market
 * data; this file only says WHO belongs to each shelf.
 */
object StockCatalog {

    data class SectorList(val name: String, val stocks: List<Pair<String, String>>)

    val SECTORS: List<SectorList> = listOf(
        SectorList(
            "Semiconductors & AI",
            listOf(
                "NVDA" to "NVIDIA",
                "AMD" to "Advanced Micro Devices",
                "AVGO" to "Broadcom",
                "TSM" to "Taiwan Semiconductor",
                "QCOM" to "Qualcomm",
                "INTC" to "Intel",
                "MU" to "Micron Technology",
                "TXN" to "Texas Instruments",
                "ASML" to "ASML Holding",
                "AMAT" to "Applied Materials",
                "LRCX" to "Lam Research",
                "KLAC" to "KLA",
                "ARM" to "Arm Holdings",
                "SMCI" to "Super Micro Computer",
                "MRVL" to "Marvell Technology",
                "ON" to "ON Semiconductor"
            )
        ),
        SectorList(
            "Technology",
            listOf(
                "AAPL" to "Apple",
                "MSFT" to "Microsoft",
                "ORCL" to "Oracle",
                "CRM" to "Salesforce",
                "ADBE" to "Adobe",
                "NOW" to "ServiceNow",
                "IBM" to "IBM",
                "CSCO" to "Cisco Systems",
                "ACN" to "Accenture",
                "INTU" to "Intuit",
                "SNOW" to "Snowflake",
                "SHOP" to "Shopify",
                "PLTR" to "Palantir Technologies",
                "UBER" to "Uber Technologies",
                "ANET" to "Arista Networks",
                "DELL" to "Dell Technologies"
            )
        ),
        SectorList(
            "Financials",
            listOf(
                "JPM" to "JPMorgan Chase",
                "BAC" to "Bank of America",
                "WFC" to "Wells Fargo",
                "C" to "Citigroup",
                "GS" to "Goldman Sachs",
                "MS" to "Morgan Stanley",
                "V" to "Visa",
                "MA" to "Mastercard",
                "AXP" to "American Express",
                "BLK" to "BlackRock",
                "SCHW" to "Charles Schwab",
                "PYPL" to "PayPal",
                "COIN" to "Coinbase",
                "HBAN" to "Huntington Bancshares"
            )
        ),
        SectorList(
            "Healthcare",
            listOf(
                "UNH" to "UnitedHealth Group",
                "JNJ" to "Johnson & Johnson",
                "LLY" to "Eli Lilly",
                "PFE" to "Pfizer",
                "MRK" to "Merck",
                "ABBV" to "AbbVie",
                "TMO" to "Thermo Fisher Scientific",
                "ABT" to "Abbott Laboratories",
                "AMGN" to "Amgen",
                "ISRG" to "Intuitive Surgical",
                "GILD" to "Gilead Sciences",
                "CVS" to "CVS Health",
                "MDT" to "Medtronic",
                "VRTX" to "Vertex Pharmaceuticals"
            )
        ),
        SectorList(
            "Energy",
            listOf(
                "XOM" to "Exxon Mobil",
                "CVX" to "Chevron",
                "COP" to "ConocoPhillips",
                "SLB" to "SLB (Schlumberger)",
                "EOG" to "EOG Resources",
                "OXY" to "Occidental Petroleum",
                "HAL" to "Halliburton",
                "BKR" to "Baker Hughes",
                "DVN" to "Devon Energy",
                "PSX" to "Phillips 66",
                "VLO" to "Valero Energy",
                "MPC" to "Marathon Petroleum"
            )
        ),
        SectorList(
            "Consumer",
            listOf(
                "WMT" to "Walmart",
                "COST" to "Costco Wholesale",
                "TGT" to "Target",
                "HD" to "Home Depot",
                "LOW" to "Lowe's",
                "MCD" to "McDonald's",
                "SBUX" to "Starbucks",
                "NKE" to "Nike",
                "KO" to "Coca-Cola",
                "PEP" to "PepsiCo",
                "PG" to "Procter & Gamble",
                "CL" to "Colgate-Palmolive",
                "MDLZ" to "Mondelez",
                "EL" to "Estee Lauder"
            )
        ),
        SectorList(
            "Media & telecom",
            listOf(
                "GOOGL" to "Alphabet",
                "META" to "Meta Platforms",
                "NFLX" to "Netflix",
                "DIS" to "Walt Disney",
                "TMUS" to "T-Mobile US",
                "T" to "AT&T",
                "VZ" to "Verizon",
                "CMCSA" to "Comcast",
                "SPOT" to "Spotify",
                "RBLX" to "Roblox",
                "WBD" to "Warner Bros. Discovery",
                "SNAP" to "Snap"
            )
        ),
        SectorList(
            "Industrials",
            listOf(
                "BA" to "Boeing",
                "CAT" to "Caterpillar",
                "DE" to "Deere",
                "GE" to "GE Aerospace",
                "HON" to "Honeywell",
                "LMT" to "Lockheed Martin",
                "RTX" to "RTX",
                "UPS" to "United Parcel Service",
                "FDX" to "FedEx",
                "MMM" to "3M",
                "UNP" to "Union Pacific",
                "ETN" to "Eaton",
                "EMR" to "Emerson Electric",
                "VRT" to "Vertiv Holdings"
            )
        ),
        SectorList(
            "Auto & EV",
            listOf(
                "TSLA" to "Tesla",
                "F" to "Ford Motor",
                "GM" to "General Motors",
                "RIVN" to "Rivian Automotive",
                "LCID" to "Lucid Group",
                "NIO" to "NIO",
                "TM" to "Toyota Motor",
                "ALB" to "Albemarle"
            )
        ),
        SectorList(
            "Materials & mining",
            listOf(
                "LIN" to "Linde",
                "APD" to "Air Products",
                "FCX" to "Freeport-McMoRan",
                "NUE" to "Nucor",
                "DOW" to "Dow",
                "DD" to "DuPont",
                "VALE" to "Vale",
                "RIO" to "Rio Tinto",
                "BHP" to "BHP Group",
                "MP" to "MP Materials",
                "CLF" to "Cleveland-Cliffs"
            )
        ),
        SectorList(
            "Gold & silver",
            listOf(
                "NEM" to "Newmont",
                "B" to "Barrick Mining",
                "AEM" to "Agnico Eagle Mines",
                "FNV" to "Franco-Nevada",
                "RGLD" to "Royal Gold",
                "WPM" to "Wheaton Precious Metals",
                "KGC" to "Kinross Gold",
                "AU" to "AngloGold Ashanti",
                "HMY" to "Harmony Gold",
                "PAAS" to "Pan American Silver",
                "AG" to "First Majestic Silver"
            )
        ),
        SectorList(
            "Utilities & REITs",
            listOf(
                "NEE" to "NextEra Energy",
                "DUK" to "Duke Energy",
                "SO" to "Southern Company",
                "D" to "Dominion Energy",
                "AEP" to "American Electric Power",
                "PLD" to "Prologis",
                "AMT" to "American Tower",
                "EQIX" to "Equinix",
                "O" to "Realty Income",
                "SPG" to "Simon Property Group"
            )
        )
    )
}
