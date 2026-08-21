package com.aurum.invest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.invest.analytics.BeatSpyEngine
import com.aurum.invest.analytics.BeatSpyHorizon
import com.aurum.invest.analytics.BeatSpyReport
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale

/**
 * The Beat-the-SPY tab: before the buy, the same dollars are raced against
 * their default home — the index. Every card here is a percentile of paired
 * measured windows; the analog count and tier print beside the numbers.
 */
fun LazyListScope.beatSpyItems(state: AnalysisState) {
    val report = state.beatSpy
    when {
        state.beatSpyLoading && report == null -> item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AurumColors.gold)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Racing ${state.symbol} against SPY — five years of paired windows",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.symbol == "SPY" -> item {
            EmptyState(
                title = "SPY is the benchmark",
                message = "The race measures a stock against the index the same dollars " +
                    "would otherwise buy — SPY can't race itself."
            )
        }
        report == null -> item {
            EmptyState(
                title = "The race couldn't run",
                message = "SPY's history or this listing's couldn't be loaded this run. " +
                    "Pull to retry once the connection is back."
            )
        }
        else -> {
            item {
                VerdictCard(report)
                Spacer(Modifier.height(14.dp))
            }
            val buy = report.horizons.firstOrNull {
                it.horizonSessions == BeatSpyEngine.BUY_HORIZON_SESSIONS
            } ?: report.horizons.firstOrNull()
            if (buy != null) {
                item {
                    EntryRangeCard(buy, report.price)
                    Spacer(Modifier.height(14.dp))
                }
                item {
                    ThousandCard(buy)
                    Spacer(Modifier.height(14.dp))
                }
            }
            if (report.horizons.isNotEmpty()) {
                item {
                    HorizonsCard(report.horizons)
                    Spacer(Modifier.height(14.dp))
                }
            }
            item {
                HeadToHeadCard(report)
                Spacer(Modifier.height(14.dp))
            }
            if (report.notes.isNotEmpty()) {
                item {
                    for (note in report.notes) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            item {
                Text(
                    text = report.caveat,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

// -------------------------------------------------------------- the verdict

@Composable
private fun VerdictCard(report: BeatSpyReport) {
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "The ticket",
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.textDim,
                modifier = Modifier.weight(1f)
            )
            when (report.verdict) {
                TechniqueVerdict.BULLISH -> PillTag("${report.symbol} wins", AurumColors.gain)
                TechniqueVerdict.BEARISH -> PillTag("SPY wins", AurumColors.loss)
                TechniqueVerdict.NEUTRAL -> PillTag("Coin flip", AurumColors.textDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = report.verdictLine,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.text
        )
        val buy = report.horizons.firstOrNull {
            it.horizonSessions == BeatSpyEngine.BUY_HORIZON_SESSIONS
        } ?: report.horizons.firstOrNull()
        if (buy != null) {
            Spacer(Modifier.height(14.dp))
            BeatShareBar(buy.beatSharePct)
            Spacer(Modifier.height(6.dp))
            Text(
                text = String.format(
                    Locale.US,
                    "Beat share over the %s: %.0f%% of %d %s.",
                    buy.label, buy.beatSharePct, buy.analogCount, buy.basis
                ),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
    }
}

/** 0..100 beat-share bar with the 50% coin-flip tick. */
@Composable
private fun BeatShareBar(sharePct: Double) {
    val fill = when {
        sharePct >= BeatSpyEngine.BEAT_BAR_PCT -> AurumColors.gain
        sharePct <= BeatSpyEngine.LOSE_BAR_PCT -> AurumColors.loss
        else -> AurumColors.gold
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(color = AurumColors.hairline, cornerRadius = r)
        val w = (sharePct / 100.0).coerceIn(0.0, 1.0).toFloat() * size.width
        if (w > 0f) {
            drawRoundRect(color = fill, size = Size(w, size.height), cornerRadius = r)
        }
        // The 50% line: everything left of it is SPY's side of the coin.
        drawLine(
            color = AurumColors.bg,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = 2.dp.toPx()
        )
    }
}

// ------------------------------------------------------- the entry price zones

@Composable
private fun EntryRangeCard(h: BeatSpyHorizon, price: Double) {
    AurumCard {
        Text(
            text = "Where the buy beats the index",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(12.dp))
        EntryZoneBar(h, price)
        Spacer(Modifier.height(12.dp))
        Row {
            StatTile(
                label = "Soft-quartile edge",
                value = "≤ " + Fmt.money(h.edgeEntry),
                valueColor = AurumColors.gain,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            StatTile(
                label = "Median edge",
                value = "≤ " + Fmt.money(h.breakevenEntry),
                valueColor = AurumColors.gold,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            StatTile(
                label = "Price now",
                value = Fmt.money(price),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }
        Spacer(Modifier.height(12.dp))
        val stance = when {
            price <= h.edgeEntry ->
                "At today's price even the stock's soft (Q1) measured ${h.label} beats " +
                    "SPY's median — the entry carries the edge with room to be wrong."
            price <= h.breakevenEntry ->
                "At today's price the stock's MEDIAN measured ${h.label} beats SPY's " +
                    "median — the edge holds, but a soft draw hands the ${h.label} to the index."
            else ->
                "Above " + Fmt.money(h.breakevenEntry) + " the median measured ${h.label} " +
                    "goes to SPY. A limit at or under that level is what earns the ticket."
        }
        Text(
            text = stance,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Levels anchor the stock's measured ${h.label} destinations from today " +
                "against SPY's median over the same ${h.analogCount} paired windows.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

/**
 * The three entry zones on one bar — soft-quartile edge, median edge, SPY's —
 * with today's price as the marker.
 */
@Composable
private fun EntryZoneBar(h: BeatSpyHorizon, price: Double) {
    val points = listOf(h.edgeEntry, h.breakevenEntry, price).filter { it > 0.0 }
    if (points.isEmpty()) return
    val lo = points.min() * 0.96
    val hi = points.max() * 1.04
    val span = (hi - lo).takeIf { it > 1e-9 } ?: return
    Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
        fun x(v: Double): Float = ((v - lo) / span).toFloat() * size.width
        val barTop = size.height / 2f - 4.dp.toPx() / 2f
        val barH = 4.dp.toPx()
        val xEdge = x(h.edgeEntry).coerceIn(0f, size.width)
        val xBreak = x(h.breakevenEntry).coerceIn(0f, size.width)
        // Below the soft-quartile level: the stock's side even on a soft draw.
        drawRoundRect(
            color = AurumColors.gain,
            topLeft = Offset(0f, barTop),
            size = Size(xEdge, barH),
            cornerRadius = CornerRadius(barH / 2f)
        )
        // Between the two: the median's side.
        if (xBreak > xEdge) {
            drawRect(
                color = AurumColors.gold,
                topLeft = Offset(xEdge, barTop),
                size = Size(xBreak - xEdge, barH)
            )
        }
        // Above breakeven: SPY's side of the race.
        if (size.width > xBreak) {
            drawRect(
                color = AurumColors.loss,
                topLeft = Offset(xBreak, barTop),
                size = Size(size.width - xBreak, barH)
            )
        }
        // Today's price marker.
        val xPrice = x(price).coerceIn(0f, size.width)
        drawLine(
            color = AurumColors.text,
            start = Offset(xPrice, 0f),
            end = Offset(xPrice, size.height),
            strokeWidth = 2.dp.toPx()
        )
    }
}

// ------------------------------------------------------------ the $1,000 race

@Composable
private fun ThousandCard(h: BeatSpyHorizon) {
    AurumCard {
        Text(
            text = "The same $1,000, both ways",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Median measured ${h.label}, with the middle-half band beside it.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(12.dp))
        ThousandRow("In the stock", h.stockMedianPct, h.stockQ1Pct, h.stockQ3Pct)
        Spacer(Modifier.height(10.dp))
        ThousandRow("In SPY", h.spyMedianPct, h.spyQ1Pct, h.spyQ3Pct)
    }
}

@Composable
private fun ThousandRow(label: String, medianPct: Double, q1Pct: Double, q3Pct: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.text,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = Fmt.money(1000.0 * (1.0 + medianPct / 100.0)),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AurumColors.deltaColor(medianPct)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = Fmt.money(1000.0 * (1.0 + q1Pct / 100.0)) + " – " +
                Fmt.money(1000.0 * (1.0 + q3Pct / 100.0)),
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
}

// ------------------------------------------------------------ every horizon

@Composable
private fun HorizonsCard(horizons: List<BeatSpyHorizon>) {
    AurumCard {
        Text(
            text = "Every horizon raced",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(6.dp))
        horizons.forEachIndexed { i, h ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = h.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text,
                    modifier = Modifier.width(76.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = String.format(Locale.US, "%.0f%% beat share", h.beatSharePct),
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.text
                    )
                    Text(
                        text = String.format(
                            Locale.US, "median edge %+.1f pts · %d windows",
                            h.medianEdgePct, h.analogCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                when (h.verdict) {
                    TechniqueVerdict.BULLISH -> PillTag("Stock", AurumColors.gain)
                    TechniqueVerdict.BEARISH -> PillTag("SPY", AurumColors.loss)
                    TechniqueVerdict.NEUTRAL -> PillTag("Flip", AurumColors.textDim)
                }
            }
            if (i < horizons.lastIndex) {
                HorizontalDivider(color = AurumColors.hairline, thickness = 1.dp)
            }
        }
    }
}

// ------------------------------------------------------------ head to head

@Composable
private fun HeadToHeadCard(report: BeatSpyReport) {
    AurumCard {
        Text(
            text = "Head to head, same clock",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        for (span in report.spans) {
            val stock = span.stockPct
            val spy = span.spyPct
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = span.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    modifier = Modifier.width(40.dp)
                )
                if (stock != null && spy != null) {
                    Text(
                        text = Fmt.signedPct(stock),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.deltaColor(stock),
                        modifier = Modifier.width(92.dp)
                    )
                    Text(
                        text = "vs SPY " + Fmt.signedPct(spy),
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim,
                        modifier = Modifier.weight(1f)
                    )
                    val edge = stock - spy
                    Text(
                        text = String.format(Locale.US, "%+.1f pts", edge),
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.deltaColor(edge)
                    )
                } else {
                    Text(
                        text = "not enough shared history",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            StatTile(
                label = "Beta",
                value = report.beta?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                modifier = Modifier.weight(1f),
                info = "Beta vs SPY" to "How hard this stock moves per 1% SPY move, " +
                    "measured over the last shared year of daily returns."
            )
            StatTile(
                label = "Correlation",
                value = report.correlation?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                modifier = Modifier.weight(1f),
                info = "Correlation" to "How closely the two move together day to day " +
                    "(1 = lockstep, 0 = unrelated), last shared year."
            )
        }
        Spacer(Modifier.height(10.dp))
        Row {
            StatTile(
                label = "Up capture",
                value = report.upCapturePct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
                valueColor = AurumColors.gain,
                modifier = Modifier.weight(1f),
                info = "Up capture" to "The stock's average move on SPY's up days, as a " +
                    "share of SPY's own move. Over 100% = it outruns rallies."
            )
            StatTile(
                label = "Down capture",
                value = report.downCapturePct?.let { String.format(Locale.US, "%.0f%%", it) }
                    ?: "—",
                valueColor = AurumColors.loss,
                modifier = Modifier.weight(1f),
                info = "Down capture" to "The stock's average move on SPY's down days, as a " +
                    "share of SPY's fall. Over 100% = it falls harder than the index."
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${report.alignedSessions} sessions where both printed a close — every " +
                "figure above shares that one clock.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}
