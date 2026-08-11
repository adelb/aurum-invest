# Aurum

A native Android investment-wallet tracker for the US market. Kotlin + Jetpack Compose, dark premium design, no accounts and no backend — everything runs on the device.

## Features

- **Portfolio** — record buys and sells (by share count or by invested dollar amount); Aurum computes share counts, weighted average cost, realized and unrealized P/L, and day P/L from live prices. Each holding also shows its pre-market / after-hours move, and the allocation card swipes between two reads of the same live values: per stock, and compounded into sectors (Yahoo's own classification, cached; unknowns stay honestly "Unclassified" rather than guessed). Each card carries a **sell flag**: type the profit percentage you want and Aurum shows the exact price to sell at, how far away it is, and the money it represents — turning green the moment the price gets there. An edit button opens the position's trades — correct or delete any buy/sell and every derived number (position, P/L, reports) recomputes from the fixed ledger. New trades can be sized as a **percentage** — of your invested money on a buy, of the position itself on a sell. Any position (e.g. a test entry) can be removed from the ledger without touching the others.
- **Portfolio-aware suggestions** — one shared engine slices the book by sector and every screen reads from it, so the numbers always agree. Picks (all four tabs) and the Wealth market-pulse lists tag each suggestion against your actual holdings: "In portfolio · 12% of book", "Adds to Technology — already 45% of book", or "New sector for your book". The Wealth tab adds a "Your book" card: sector split, and number-backed notes — concentration warnings, whether this week's trending theme is already covered, and how much of the book lacks sector data (left out of sector reads rather than guessed).
- **Live market data** — quotes with day range, 52-week range and volume; 1-day, 1-week, 1-month and 3-month price charts with pinch-zoom, pan, double-tap zoom steps and a hold-for-crosshair price/time readout; powered by Yahoo Finance public endpoints (no API key). Every screen refreshes with a pull-down gesture.
- **Buy/sell advice** — RSI, moving averages, ATR-based targets and stops, plus news tone; every holding gets a hold/take-profit/cut-loss read, every watched stock gets a suggested entry price.
- **15-technique analysis** — moving averages, RSI, MACD, Bollinger Bands, support/resistance, fair value gaps, direction-aware Fibonacci, Ichimoku, stochastic, OBV, ADX, Donchian channel (the Turtle system), Parabolic SAR, Money Flow Index, and the golden cross 50/200, each with its own diagram and verdict, combined into a plain-English 5-day outlook. Charts render as Japanese candlesticks or a line (toggle), with price gridlines, a date axis, pinch-zoom/pan, and a hold-and-drag crosshair; tapping a chart opens the full written analysis of that technique — what's drawn, the current reading, levels to watch, and a playbook.
- **$3,000 five-day buy plan** — a toggle on the analysis screen switches from the techniques to a staged plan for deploying $3,000 into the stock over five trading days, built from the documented playbooks of Livermore (probe then pyramid), O'Neil (50/30/20 scaling), Paul Tudor Jones (200-day MA gate, 5:1 reward-to-risk), Elder (2% risk rule), and Buffett/Munger (limit orders at value): tranches with trigger prices, an ATR-padded structural stop, targets, risk math, and a day-by-day schedule.
- **Gold relation** — 125-day correlation of any stock against GLD: moves with gold, inverse, or unrelated.
- **News** — last 5 days of headlines per stock with sentiment dots and the price move on each news day.
- **Market pulse** — the Wealth tab opens with a whole-market rating (0-100) built from measured data: S&P 500 / Nasdaq 100 / Russell 2000 trend vs their 50-day averages, breadth across hundreds of scanned liquid names, the share that advanced in the last session, and the VIX. It answers plainly whether this week is worth new money (invest / selective / defensive, with the numbers behind the call), lists the last session's best performers (liquidity-gated), and scans for names positioned for the next session — each confirmed by the 15-technique board, with a suggested entry and an honest ATR-based next-session range.
- **Pre-market** — a standalone tab ranking the 10 strongest pre-market movers **on live pre-market prints** (prices read fresh each visit, never more than a minute stale, with the list recomputed when it ages past two minutes) against *your* daily profit target (default 2.06%, editable — the whole list re-ranks against whatever you set). Each name is measured, not guessed: how often its intraday high actually rose that far above the open across ~60 sessions, what a median day really delivers, its ATR budget, and how often its gap-ups held into the close. Ranking is by that evidence rather than by gap size. Each card gives the buy price, the sell price that hits your target, an ATR stop, and the clock windows where its low and high have actually printed over recent sessions — including a warning when the high tends to land *before* the low, meaning waiting for a dip usually misses the move.
- **Weekly sector strategy** — the Wealth tab answers one question a week: where is the portfolio thin, and what should be bought. It measures every trending theme against what is actually held (real sector weights where the mapping is unambiguous, exact holdings membership for cross-sector themes like AI or quantum), marks each **Missing / Light / Covered / Heavy**, picks the best stock per theme (technique-board approved, with a pullback entry when the name is extended), and splits the week's money across themes — weighted by trend strength scaled by how much room the book still has, so new money flows toward the gaps.
- **Wealth** — a standalone 4-month investment plan: enter a base amount and a profit target, and every week the app re-reads the market — this week's trending sector (AI, semiconductors, quantum, oil, materials, gold, and more via ETF momentum plus news tone), the 15-technique board on every candidate, news sentiment, and insider/institutional headlines — then answers with a concrete allocation: which stocks, how many dollars each, entry, 4-month target, stop, expected profit, and when to buy and sell. Each trending sector lists 4 representative stocks to look at — tap a ticker to open its full 15-technique analysis. It also tells you honestly whether the target's required monthly return is realistic.
- **Daily picks** — a Today toggle on the Picks tab ranks the 5 stocks most capable of a 3-10%+ up-move in the current session, scored from short-term momentum, latest-session volume surge, ATR capacity, the 15-technique outlook, pre/post-market prints, and news sentiment; each card shows the expected-move range, the pre-market/after-hours performance, and the newest related headline. Refreshable any time, recomputed each day except Saturday.
- **Best entries** — an Entries toggle on the Picks tab sweeps the whole US market (hundreds of names from Yahoo's market-wide screens: most actives, gainers, losers, undervalued large caps and growth, small caps) and ranks the 10 stocks sitting at the best entry price right now: long trend intact above the 200-day average, pulled back toward the 50-day and clustered support, RSI reset, honest reward/risk to the nearest resistance, and a 15-technique board that does not read the dip as a falling knife. Each card shows the entry (or a patient limit), target, stop, upside and risk percentages, and the numbers behind the read.
- **Power hour** — a Power toggle on the Picks tab for the overnight-momentum play: buy in the last 90 minutes of the session (2:30–4:00 PM ET — the app shows the window in your local time), sell into tomorrow morning's strength. The whole market is screened for names finishing near their daily high on hot volume after 4 strong trading days (total move, up-day consistency, acceleration into today), each confirmed by the 15-technique board, with an honest ATR-based next-day potential range, a morning exit target, and a hard stop under the day's low.
- **Weekly picks** — every Monday (and on every refresh) the app ranks the 10 strongest setups from an 85-name universe widened with live market-wide movers from Yahoo's screens (most actives, gainers, growth), plus a separate under-$25 watch list of 5, each with a one-line data-backed reason and full analysis.
- **Bank sync** — a notification listener captures Bank al Etihad trade alerts (English and Arabic), parses side/symbol/quantity/price, and imports them into the portfolio after your review.
- **Reports** — daily, weekly, and monthly summaries of trades done: realized P/L, buys/sells totals, best and worst trade.

## Install

Scan with your phone camera to download the latest APK directly:

<img src="install-qr.png" alt="Scan to install Aurum" width="260" />

Or download `aurum.apk` from the [latest release](../../releases/latest). Open it on your Android phone (allow "Install unknown apps"), then:

1. Open **Settings → Bank sync → Enable** to grant Notification Access (needed for bank-alert capture).
2. Add your first trade with the gold **+** button, or let a bank notification arrive and import it from the **Feed** tab.

Requires Android 8.0+ and an internet connection for market data.

## Build from source

```
gradlew.bat :app:assembleDebug
```

JDK 17+, Android SDK 34. See `CONTRACTS.md` for the module architecture.

## Disclaimer

All suggestions are computed from public historical market data and news headlines. They are decision support, not financial advice.
