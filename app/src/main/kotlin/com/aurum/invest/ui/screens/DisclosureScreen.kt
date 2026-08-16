package com.aurum.invest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurum.invest.BuildConfig
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.theme.AurumColors

/**
 * The canonical methodology / data / risk disclosure center (M4). One
 * versioned place that says where every number comes from, how fresh it is,
 * what the scores mean, what was measured vs. assumed, and what this app is
 * NOT — replacing scattered one-line disclaimers as the source of truth.
 */
@Composable
fun DisclosureScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = AurumColors.text
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "Methodology & disclosures",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AurumColors.text
                        )
                        Text(
                            text = "Disclosure version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }

            item { DisclosureSection(title = "What this app is — and is not", body = listOf(
                "Aurum is a personal decision-support tool. It is NOT financial advice, not a " +
                    "brokerage, and not a registered investment adviser. Every output is a " +
                    "computed signal for you to judge, not an instruction.",
                "Stocks can and do lose money. No output here is a promise, a probability " +
                    "of profit, or a guarantee. You alone are responsible for your trades."
            )) }

            item { DisclosureSection(title = "Data sources & freshness", body = listOf(
                "Prices, candles, screeners, search, and company data come from Yahoo Finance's " +
                    "public endpoints. Quotes may be delayed by up to 15 minutes depending on " +
                    "the exchange; the app shows an \"as of\" time when data is not fresh.",
                "News comes from Google News RSS headlines. It is HEADLINE text only — the app " +
                    "does not read the articles. A failed fetch is always labeled as failed, " +
                    "never presented as \"no news\".",
                "When the network fails, the app serves the last cached copy and labels it " +
                    "stale, or says the fetch failed. It never silently substitutes old or " +
                    "neutral data for a live answer.",
                "\"Market-wide\" scans read Yahoo's predefined screener lists (up to ~100 rows " +
                    "each) — a broad sample of liquid names, NOT every listed US stock. Each " +
                    "scan shows exactly how many screens and rows it covered."
            )) }

            item { DisclosureSection(title = "What the scores mean", body = listOf(
                "\"% agree\" / indicator agreement is the share of the 35-technique board " +
                    "voting one way. The techniques are CORRELATED (many read the same price " +
                    "series), so 80% agreement is NOT an 80% probability of anything.",
                "Per-technique hit rates come from replaying the last 252 sessions of the " +
                    "stock and grading each call against the real 5-day move that followed. " +
                    "Trust requires non-overlapping samples, a 60%+ hit rate, and an edge over " +
                    "the stock's own base drift. This is an in-sample historical replay — " +
                    "useful context, not a validated forecasting model.",
                "\"Expected range\" numbers are ATR (volatility) projections — a formula, not " +
                    "an empirically calibrated prediction interval.",
                "Engine scores (0–100) are fixed-scale heuristics: the same inputs always give " +
                    "the same score, and no score is ever inflated to fill a screen. Missing " +
                    "inputs lower coverage and are disclosed, never silently replaced."
            )) }

            item { DisclosureSection(title = "Backtest limitations", body = listOf(
                "Replays use daily closes and ignore commissions, spreads, slippage, and " +
                    "overnight gaps through stops. Real results would differ.",
                "One stock's one-year record is a small sample from one market regime. A rule " +
                    "that worked in a rising year can fail in the next regime.",
                "No walk-forward, out-of-sample validation has been performed on the technique " +
                    "board. Treat every historical hit rate as descriptive, not predictive."
            )) }

            item { DisclosureSection(title = "Suitability & sizing", body = listOf(
                "Recommendations are shaped by your investor profile (Settings): horizon, risk " +
                    "tolerance, risk-per-trade, and concentration caps. Until you set it, the " +
                    "app uses balanced defaults and labels them as defaults.",
                "Buy plans size from your recorded holdings value (plus tracked cash) when " +
                    "available. When the app cannot see your account, plans fall back to a " +
                    "fixed order budget and say so explicitly.",
                "The app supports US-listed equities and ETFs, in USD. Non-USD bank alerts " +
                    "require an explicit FX conversion before entering the ledger."
            )) }

            item { DisclosureSection(title = "Portfolio numbers", body = listOf(
                "\"Holdings value\" is the sum of your open positions at the latest fetched " +
                    "prices — it is not account equity unless you also track cash. Holdings " +
                    "with no live quote are carried at cost and flagged.",
                "Positions, P/L, and reports all replay the same transaction ledger with " +
                    "weighted-average cost. Oversells are rejected at entry; dividends, fees, " +
                    "deposits, and splits are recorded explicitly.",
                "Sell targets are computed before selling fees and taxes unless you set a " +
                    "selling-cost estimate in Settings."
            )) }

            item { DisclosureSection(title = "Privacy", body = listOf(
                "Everything lives in a local database on this device: no account, no backend, " +
                    "no analytics. Network calls go only to Yahoo Finance and Google News to " +
                    "fetch market data.",
                "With notification access enabled, bank/broker trade alerts are captured and " +
                    "stored locally so you can import them. Raw captures are deleted after " +
                    "your configured retention window, and you can delete them all at any time.",
                "Android app backups may include the local database. Use Settings → Export " +
                    "for an explicit, portable copy of your ledger."
            )) }

            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Questions this page can't answer are gaps — report them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, body: List<String>) {
    Spacer(Modifier.height(20.dp))
    SectionHeader(title = title)
    Spacer(Modifier.height(12.dp))
    AurumCard(modifier = Modifier.fillMaxWidth()) {
        body.forEachIndexed { i, paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.text
            )
            if (i < body.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}
