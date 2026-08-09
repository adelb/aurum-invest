# Aurum

A native Android investment-wallet tracker for the US market. Kotlin + Jetpack Compose, dark premium design, no accounts and no backend — everything runs on the device.

## Features

- **Portfolio** — record buys and sells (by share count or by invested dollar amount); Aurum computes share counts, weighted average cost, realized and unrealized P/L, and day P/L from live prices. Any position (e.g. a test entry) can be removed from the ledger without touching the others.
- **Live market data** — quotes, intraday and 3-month charts, powered by Yahoo Finance public endpoints (no API key).
- **Buy/sell advice** — RSI, moving averages, ATR-based targets and stops, plus news tone; every holding gets a hold/take-profit/cut-loss read, every watched stock gets a suggested entry price.
- **11-technique analysis** — moving averages, RSI, MACD, Bollinger Bands, support/resistance, fair value gaps, Fibonacci, Ichimoku, stochastic, OBV, and ADX, each with its own diagram and verdict, combined into a plain-English 5-day outlook. Charts render as Japanese candlesticks or a line (toggle), with price gridlines, a date axis, pinch-zoom/pan, and a hold-and-drag crosshair; tapping a chart opens the full written analysis of that technique — what's drawn, the current reading, levels to watch, and a playbook.
- **$3,000 five-day buy plan** — a toggle on the analysis screen switches from the techniques to a staged plan for deploying $3,000 into the stock over five trading days, built from the documented playbooks of Livermore (probe then pyramid), O'Neil (50/30/20 scaling), Paul Tudor Jones (200-day MA gate, 5:1 reward-to-risk), Elder (2% risk rule), and Buffett/Munger (limit orders at value): tranches with trigger prices, an ATR-padded structural stop, targets, risk math, and a day-by-day schedule.
- **Gold relation** — 125-day correlation of any stock against GLD: moves with gold, inverse, or unrelated.
- **News** — last 5 days of headlines per stock with sentiment dots and the price move on each news day.
- **Weekly picks** — every Monday the app ranks the 10 strongest setups from an 85-name universe, plus a separate under-$25 watch list of 5, each with a one-line data-backed reason and full analysis.
- **Bank sync** — a notification listener captures Bank al Etihad trade alerts (English and Arabic), parses side/symbol/quantity/price, and imports them into the portfolio after your review.
- **Reports** — weekly and monthly summaries of trades done: realized P/L, buys/sells totals, best and worst trade.

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
