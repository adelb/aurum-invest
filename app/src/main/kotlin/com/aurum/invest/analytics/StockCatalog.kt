package com.aurum.invest.analytics

/**
 * Curated stock lists for the Stocks tab: liquid, US-listed names grouped the
 * way a person browses — by sector. Pure data, no Android imports. Prices,
 * 2-week performance and highlights are always computed live from market
 * data; this file only says WHO belongs to each shelf.
 *
 * Each shelf carries a [SectorList.themeKey] pointing into
 * [SectorTrends.SECTORS], so the browse can show the shelf's live trend
 * (ETF momentum, volume, news tone) without a second market sweep.
 */
object StockCatalog {

    data class SectorList(
        val name: String,
        val themeKey: String,
        val stocks: List<Pair<String, String>>
    )

    val SECTORS: List<SectorList> = listOf(
        SectorList(
            "Semiconductors & AI",
            "semis",
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
                "ON" to "ON Semiconductor",
                "NXPI" to "NXP Semiconductors",
                "ADI" to "Analog Devices",
                "MCHP" to "Microchip Technology",
                "MPWR" to "Monolithic Power Systems",
                "TER" to "Teradyne",
                "SWKS" to "Skyworks Solutions",
                "ALAB" to "Astera Labs",
                "CRDO" to "Credo Technology"
            )
        ),
        SectorList(
            "Technology",
            "software",
            listOf(
                "AAPL" to "Apple",
                "MSFT" to "Microsoft",
                "AMZN" to "Amazon",
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
                "DELL" to "Dell Technologies",
                "PANW" to "Palo Alto Networks",
                "CRWD" to "CrowdStrike",
                "FTNT" to "Fortinet",
                "NET" to "Cloudflare",
                "DDOG" to "Datadog",
                "WDAY" to "Workday",
                "ABNB" to "Airbnb"
            )
        ),
        SectorList(
            // Drones first, then the robotics and automation names they sit
            // beside: the shelf is browsed by people following the drone
            // build-out, and a list that opens on industrial automation buries
            // exactly what they came for.
            "Drones & robotics",
            "drones",
            listOf(
                "AVAV" to "AeroVironment",
                "KTOS" to "Kratos Defense & Security",
                "RCAT" to "Red Cat Holdings",
                "UMAC" to "Unusual Machines",
                "ONDS" to "Ondas Holdings",
                "AIRO" to "AIRO Group Holdings",
                "UAVS" to "AgEagle Aerial Systems",
                "DPRO" to "Draganfly",
                "EH" to "EHang Holdings",
                "JOBY" to "Joby Aviation",
                "ACHR" to "Archer Aviation",
                "RDW" to "Redwire",
                "ISRG" to "Intuitive Surgical",
                "SYM" to "Symbotic",
                "TER" to "Teradyne",
                "ROK" to "Rockwell Automation",
                "CGNX" to "Cognex",
                "ZBRA" to "Zebra Technologies",
                "SERV" to "Serve Robotics",
                "RR" to "Richtech Robotics",
                "PDYN" to "Palladyne AI",
                "IRBT" to "iRobot"
            )
        ),
        SectorList(
            "Defense & aerospace",
            "defense",
            listOf(
                "LMT" to "Lockheed Martin",
                "RTX" to "RTX",
                "NOC" to "Northrop Grumman",
                "GD" to "General Dynamics",
                "LHX" to "L3Harris Technologies",
                "BA" to "Boeing",
                "TDG" to "TransDigm",
                "HWM" to "Howmet Aerospace",
                "HEI" to "HEICO",
                "TXT" to "Textron",
                "LDOS" to "Leidos",
                "BAH" to "Booz Allen Hamilton",
                "CACI" to "CACI International",
                "SAIC" to "SAIC",
                "MRCY" to "Mercury Systems",
                "KTOS" to "Kratos Defense & Security",
                "AVAV" to "AeroVironment",
                "RKLB" to "Rocket Lab",
                "LUNR" to "Intuitive Machines"
            )
        ),
        SectorList(
            "Quantum computing",
            "quantum",
            listOf(
                "IONQ" to "IonQ",
                "RGTI" to "Rigetti Computing",
                "QBTS" to "D-Wave Quantum",
                "QUBT" to "Quantum Computing Inc",
                "ARQQ" to "Arqit Quantum",
                "LAES" to "SEALSQ",
                "IBM" to "IBM",
                "GOOGL" to "Alphabet",
                "MSFT" to "Microsoft",
                "HON" to "Honeywell",
                "NVDA" to "NVIDIA"
            )
        ),
        SectorList(
            "Biotech",
            "biotech",
            listOf(
                "VRTX" to "Vertex Pharmaceuticals",
                "REGN" to "Regeneron Pharmaceuticals",
                "GILD" to "Gilead Sciences",
                "AMGN" to "Amgen",
                "BIIB" to "Biogen",
                "MRNA" to "Moderna",
                "ALNY" to "Alnylam Pharmaceuticals",
                "BMRN" to "BioMarin Pharmaceutical",
                "INCY" to "Incyte",
                "NBIX" to "Neurocrine Biosciences",
                "SRPT" to "Sarepta Therapeutics",
                "EXEL" to "Exelixis",
                "UTHR" to "United Therapeutics",
                "RARE" to "Ultragenyx Pharmaceutical",
                "IONS" to "Ionis Pharmaceuticals",
                "CRSP" to "CRISPR Therapeutics",
                "NTLA" to "Intellia Therapeutics",
                "BEAM" to "Beam Therapeutics",
                "VKTX" to "Viking Therapeutics",
                "ARWR" to "Arrowhead Pharmaceuticals",
                "HALO" to "Halozyme Therapeutics",
                "AXSM" to "Axsome Therapeutics"
            )
        ),
        SectorList(
            "Nuclear & uranium",
            "nuclear",
            listOf(
                "CCJ" to "Cameco",
                "LEU" to "Centrus Energy",
                "UEC" to "Uranium Energy",
                "UUUU" to "Energy Fuels",
                "DNN" to "Denison Mines",
                "NXE" to "NexGen Energy",
                "URG" to "Ur-Energy",
                "SMR" to "NuScale Power",
                "OKLO" to "Oklo",
                "NNE" to "Nano Nuclear Energy",
                "LTBR" to "Lightbridge",
                "ASPI" to "ASP Isotopes",
                "BWXT" to "BWX Technologies",
                "CEG" to "Constellation Energy",
                "VST" to "Vistra",
                "TLN" to "Talen Energy"
            )
        ),
        SectorList(
            "Solar & clean energy",
            "solar",
            listOf(
                "FSLR" to "First Solar",
                "ENPH" to "Enphase Energy",
                "SEDG" to "SolarEdge Technologies",
                "RUN" to "Sunrun",
                "NXT" to "Nextracker",
                "ARRY" to "Array Technologies",
                "SHLS" to "Shoals Technologies",
                "CSIQ" to "Canadian Solar",
                "JKS" to "JinkoSolar",
                "PLUG" to "Plug Power",
                "BE" to "Bloom Energy",
                "BLDP" to "Ballard Power Systems",
                "FCEL" to "FuelCell Energy",
                "ORA" to "Ormat Technologies",
                "BEP" to "Brookfield Renewable",
                "AMRC" to "Ameresco"
                // GE Vernova stays on the Industrials shelf: it is power
                // equipment broadly, and listing it here would take its theme
                // attribution off Industrials for a clean-energy claim only
                // part of its business supports.
            )
        ),
        SectorList(
            "Financials",
            "banks",
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
                "HBAN" to "Huntington Bancshares",
                "USB" to "U.S. Bancorp",
                "PNC" to "PNC Financial",
                "COF" to "Capital One",
                "BX" to "Blackstone",
                "KKR" to "KKR",
                "PGR" to "Progressive",
                "IBKR" to "Interactive Brokers",
                "SOFI" to "SoFi Technologies",
                "HOOD" to "Robinhood Markets"
            )
        ),
        SectorList(
            "Healthcare",
            "health",
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
                "VRTX" to "Vertex Pharmaceuticals",
                "NVO" to "Novo Nordisk",
                "AZN" to "AstraZeneca",
                "BMY" to "Bristol Myers Squibb",
                "DHR" to "Danaher",
                "SYK" to "Stryker",
                "BSX" to "Boston Scientific",
                "REGN" to "Regeneron Pharmaceuticals",
                "MRNA" to "Moderna",
                "ZTS" to "Zoetis",
                "HCA" to "HCA Healthcare"
            )
        ),
        SectorList(
            "Energy",
            "oil",
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
                "MPC" to "Marathon Petroleum",
                "FANG" to "Diamondback Energy",
                "KMI" to "Kinder Morgan",
                "WMB" to "Williams Companies",
                "OKE" to "ONEOK",
                "TRGP" to "Targa Resources",
                "ET" to "Energy Transfer"
            )
        ),
        SectorList(
            "Consumer",
            "consumer",
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
                "EL" to "Estee Lauder",
                "TJX" to "TJX Companies",
                "ROST" to "Ross Stores",
                "LULU" to "Lululemon Athletica",
                "CMG" to "Chipotle Mexican Grill",
                "YUM" to "Yum! Brands",
                "DPZ" to "Domino's Pizza",
                "MNST" to "Monster Beverage",
                "CELH" to "Celsius Holdings",
                "DG" to "Dollar General",
                "HSY" to "Hershey"
            )
        ),
        SectorList(
            "Media & telecom",
            "media",
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
                "SNAP" to "Snap",
                "TTD" to "Trade Desk",
                "RDDT" to "Reddit",
                "PINS" to "Pinterest",
                "EA" to "Electronic Arts",
                "TTWO" to "Take-Two Interactive",
                "CHTR" to "Charter Communications",
                "LYV" to "Live Nation"
            )
        ),
        SectorList(
            "Industrials",
            "industrials",
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
                "VRT" to "Vertiv Holdings",
                "NOC" to "Northrop Grumman",
                "GD" to "General Dynamics",
                "LHX" to "L3Harris Technologies",
                "TDG" to "TransDigm",
                "PH" to "Parker Hannifin",
                "ITW" to "Illinois Tool Works",
                "CSX" to "CSX",
                "WM" to "Waste Management",
                "PWR" to "Quanta Services",
                "GEV" to "GE Vernova"
            )
        ),
        SectorList(
            "Auto & EV",
            "autos",
            listOf(
                "TSLA" to "Tesla",
                "F" to "Ford Motor",
                "GM" to "General Motors",
                "RIVN" to "Rivian Automotive",
                "LCID" to "Lucid Group",
                "NIO" to "NIO",
                "TM" to "Toyota Motor",
                "ALB" to "Albemarle",
                "HMC" to "Honda Motor",
                "STLA" to "Stellantis",
                "XPEV" to "XPeng",
                "LI" to "Li Auto",
                "APTV" to "Aptiv",
                "MBLY" to "Mobileye"
            )
        ),
        SectorList(
            "Materials & mining",
            "materials",
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
                "CLF" to "Cleveland-Cliffs",
                "SCCO" to "Southern Copper",
                "AA" to "Alcoa",
                "STLD" to "Steel Dynamics",
                "SHW" to "Sherwin-Williams",
                "ECL" to "Ecolab",
                "CTVA" to "Corteva"
            )
        ),
        SectorList(
            "Gold & silver",
            "goldminers",
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
                "AG" to "First Majestic Silver",
                "GFI" to "Gold Fields",
                "HL" to "Hecla Mining",
                "CDE" to "Coeur Mining",
                "EGO" to "Eldorado Gold",
                "MAG" to "MAG Silver"
            )
        ),
        SectorList(
            "Utilities & REITs",
            "utilities",
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
                "SPG" to "Simon Property Group",
                "CEG" to "Constellation Energy",
                "VST" to "Vistra",
                "NRG" to "NRG Energy",
                "EXC" to "Exelon",
                "SRE" to "Sempra",
                "XEL" to "Xcel Energy",
                "DLR" to "Digital Realty Trust",
                "WELL" to "Welltower",
                "AVB" to "AvalonBay Communities"
            )
        ),
        SectorList(
            "Cybersecurity",
            "cyber",
            listOf(
                "CRWD" to "CrowdStrike",
                "PANW" to "Palo Alto Networks",
                "ZS" to "Zscaler",
                "FTNT" to "Fortinet",
                "NET" to "Cloudflare",
                "S" to "SentinelOne",
                "OKTA" to "Okta",
                "CYBR" to "CyberArk",
                "CHKP" to "Check Point",
                "GEN" to "Gen Digital",
                "QLYS" to "Qualys",
                "TENB" to "Tenable"
            )
        ),
        SectorList(
            "Crypto & fintech",
            "crypto",
            listOf(
                "COIN" to "Coinbase",
                "MSTR" to "Strategy",
                "HOOD" to "Robinhood",
                "XYZ" to "Block",
                "PYPL" to "PayPal",
                "SOFI" to "SoFi Technologies",
                "AFRM" to "Affirm",
                "MARA" to "MARA Holdings",
                "RIOT" to "Riot Platforms",
                "CLSK" to "CleanSpark",
                "V" to "Visa",
                "MA" to "Mastercard"
            )
        ),
        SectorList(
            "Space & satellites",
            "space",
            listOf(
                "RKLB" to "Rocket Lab",
                "ASTS" to "AST SpaceMobile",
                "LUNR" to "Intuitive Machines",
                "PL" to "Planet Labs",
                "RDW" to "Redwire",
                "BKSY" to "BlackSky",
                "IRDM" to "Iridium",
                "VSAT" to "Viasat",
                "SATS" to "EchoStar"
            )
        ),
        SectorList(
            "Real estate & REITs",
            "reits",
            listOf(
                "PLD" to "Prologis",
                "AMT" to "American Tower",
                "EQIX" to "Equinix",
                "O" to "Realty Income",
                "SPG" to "Simon Property Group",
                "PSA" to "Public Storage",
                "WELL" to "Welltower",
                "DLR" to "Digital Realty Trust",
                "CCI" to "Crown Castle",
                "VICI" to "VICI Properties",
                "AVB" to "AvalonBay Communities",
                "EXR" to "Extra Space Storage"
            )
        ),
        SectorList(
            "Transports & logistics",
            "transport",
            listOf(
                "UNP" to "Union Pacific",
                "CSX" to "CSX",
                "NSC" to "Norfolk Southern",
                "FDX" to "FedEx",
                "ODFL" to "Old Dominion",
                "DAL" to "Delta Air Lines",
                "UAL" to "United Airlines",
                "LUV" to "Southwest Airlines",
                "JBHT" to "J.B. Hunt",
                "CHRW" to "C.H. Robinson",
                "XPO" to "XPO",
                "SAIA" to "Saia"
            )
        ),
        SectorList(
            "Consumer staples",
            "staples",
            listOf(
                "PG" to "Procter & Gamble",
                "KO" to "Coca-Cola",
                "PEP" to "PepsiCo",
                "PM" to "Philip Morris",
                "MO" to "Altria",
                "CL" to "Colgate-Palmolive",
                "KMB" to "Kimberly-Clark",
                "GIS" to "General Mills",
                "MDLZ" to "Mondelez",
                "KHC" to "Kraft Heinz",
                "HSY" to "Hershey",
                "STZ" to "Constellation Brands"
            )
        )
    )

    /** Every catalog symbol's tracked theme and shelf label, first shelf wins. */
    val SYMBOL_THEME: Map<String, Pair<String, String>> by lazy {
        buildMap {
            SECTORS.forEach { shelf ->
                shelf.stocks.forEach { (symbol, _) ->
                    putIfAbsent(symbol, shelf.themeKey to shelf.name)
                }
            }
        }
    }
}
