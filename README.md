# Aurum

A native Android investment-wallet tracker for the US market. Kotlin + Jetpack Compose, dark premium design, no accounts and no backend — everything runs on the device.

**v9.0** rebuilds the app on the v3.8 ledger base per `ARCHITECTURE_v9.md`: the honest-numbers discipline, the wallet cash identity, and the 35-technique board are load-bearing; the request-hungry engines that earned the rate limit are gone; and one superset-ranged data store means the same candles are never fetched twice under different names.

## Features

### Money truth
- **Portfolio ledger** — record buys and sells (by share count, invested dollars, or percentage); weighted-average cost, realized and unrealized P/L, and day P/L from live prices. Sells that would oversell are rejected; edits and deletes are replayed against the whole ledger and refused if they would break it — with a difference-based guard, so one broken symbol never locks every other edit. Broker-reference uniqueness stops a re-imported bank alert from swallowing a genuine rebuy. Splits live as ledger rows; foreign currency converts at the recorded rate.
- **Wallet & liquidity** — state your total investing cash once and every screen derives the rest from one identity: **liquidity = wallet − invested + realized P/L**, and net worth = liquidity + holdings. A sell returns its cost basis *and* its profit to liquidity — computed in exactly one place, printed to the cent.
- **Cash ledger** — deposits, withdrawals, dividends, and fees, folded into the same equity math the performance engine replays.
- **Reports** — daily, weekly, monthly, and yearly. Each period nests one level finer: a week's card holds its days, a month's its weeks, a year's its months — with running accumulated P/L, best/worst trade, and every listed trade editable in place. A sell's realized outcome can be pinned to the broker's real number, labeled so it never reads as computed.
- **Live figures roll per digit** — the numbers the live ticker re-prices (wallet, net worth, holdings, watch-row and detail-screen prices) turn like odometer wheels: only the digits that changed move, flashing green or red by direction.

### The 35-technique board & trust
- **35 techniques** — moving averages, RSI, MACD, Bollinger, support/resistance, fair-value gaps, Fibonacci, Ichimoku, stochastic, OBV, ADX, Donchian, PSAR, MFI, golden cross, Williams %R, CCI, Keltner, CMF, Aroon, StochRSI, ROC, TRIX, Ultimate Oscillator, Vortex, Force Index, CMO, DPO, KST, Hull MA, Supertrend, Chandelier, VWAP, A/D, and pivot points — each with its own diagram, verdict, and written playbook.
- **Trusted techniques** — every technique's calls are replayed against a year of this stock's actual candles and scored on *independent* (non-overlapping) samples with Wilson confidence intervals. A technique earns the gold **Trusted** border only with 10+ independent samples, a 60%+ hit rate, and a 5-point edge over the stock's own base rate — accuracy is measured, never assumed, and the measured weights feed every picker.

### Wealth — portfolio-aware, explained
- **Your portfolio — the verdicts** — every holding read through the technique board with a hold / take-profit / cut-loss verdict; winners are ridden behind a ratcheting trailing stop that only moves up, never sold on a bare percentage. Unpriceable holdings are named, not dropped.
- **Portfolio grade** — the book scored 0–100 against the elite-desk rulebook (sizing, concentration, winner/loser handling, stop discipline, cash posture), each weak discipline paired with its concrete fix.
- **Your liquidity — where to put it to work** — the uninvested wallet cash evaluated and deployed **sector by sector**: each sector shows its money-flow verdict, its current → target share of the account, the dollars it receives, and the named stocks — counted — that carry them, with per-stock tickets, approximate shares, and conviction built only from measured evidence. Candidates come from the day's entry boards *and* the trendy sectors' own shelves, so a theme the day's scan missed can still receive a ticket. What stays uncommitted is reported as reserve cash with the reason; when nothing clears the bar, holding cash *is* the recommendation.
- **Where to add, by theme** — trending themes measured against what the book actually holds (Missing / Light / Covered / Heavy), each sized in real wallet dollars with the theme's strongest board-passed stock and alternates.
- **Market pulse with the VIX** — a fixed 0–100 market health score from benchmark trend, breadth, participation, and volatility. The **VIX index** is a first-class figure: its level, its 5-session drift, and a regime label (very calm → fear) on the same bands the score uses. Too few measured inputs and the pulse refuses to show a number at all.
- **Every number explains itself** — the ⓘ dot beside each figure opens what it measures, the scale it sits on, and how to act on it: pulse score, VIX, money-flow score, conviction, sector targets, reserve cash, TWR, Sharpe, drawdown, beta, the grade. One glossary feeds them all, so two cards can never explain the same number differently.
- **Where the money is moving** — sector money flow from real candles: Chaikin Money Flow, MFI, OBV slope, up-day dollar share, relative strength, member breadth — on a fixed scale, with INFLOW/OUTFLOW verdicts requiring 3-of-4 signal agreement.
- **Performance & risk** — time-weighted return vs SPY over the same days, volatility, beta, Sharpe, max drawdown, and correlated pairs ("diversification that isn't"), reconstructed from the actual ledger.
- **Positioned for the next session** — measured picks with analog-history ranges and extreme-setup alerts.

### Picks & scans
- **U-Pattern day** — the dip-then-rise engine: names that fell early and are rising into the close, fingerprinted per day with buy-zone alerts.
- **Best entries** — the market-wide entry-price scan across Yahoo's screens, with honest screener-coverage reporting.
- **Power hour** — buy the last 90 minutes' strength, sell into tomorrow morning, ATR-based potential ranges.
- **The 2% desk** — pre-market and open-session scans against one editable daily target, on live prints.
- **Weekly picks** — the week's 10 strongest setups plus an under-$25 list.
- **Stocks** — a watchlist with live advice, plus a sector/theme browse whose best 2-week performers wear the gold border.

### Discipline
- **Feed discipline** — one pacing gate for every request, escalating backoff that honors `Retry-After`, no retry into a refusal, and a per-hour request/refusal counter shown in Settings. The superset candle store keeps one canonical daily series per symbol at the deepest range anyone asked for — a shallow caller gets a slice, not a second fetch.
- **Honest numbers** — fixed scales everywhere, no fabricated floors, feed provenance (fresh / stale / failed) on every card, coverage reporting on every scan, and refusal over guessing when inputs can't be measured.
- **Bank sync** — a notification listener captures Bank al Etihad trade alerts (English and Arabic) for review-then-import.
- **Alerts** — price alerts, next-session extremes, and U-pattern buy zones, each deduplicated per day.

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

JDK 17+, Android SDK 34. See `CONTRACTS.md` for the module architecture and `ARCHITECTURE_v9.md` for the v9 rebuild's design.

## Disclaimer

All suggestions are computed from public historical market data and news headlines. They are decision support, not financial advice.
