package com.aurum.invest.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.DailyPick
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.ScoreBar
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.SentimentDot
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.roundToInt

private enum class PicksTab { DAILY, WEEKLY }

@Composable
fun PicksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: PicksViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(PicksTab.DAILY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (tab == PicksTab.DAILY) "Daily Picks" else "Weekly Picks",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text
                )
                Text(
                    text = if (tab == PicksTab.DAILY) state.dailyLabel else state.weekLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
            val busy = if (tab == PicksTab.DAILY) state.dailyRefreshing else state.refreshing
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AurumColors.gold,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = { if (tab == PicksTab.DAILY) vm.refreshDaily() else vm.refresh() }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Recompute picks",
                        tint = AurumColors.gold
                    )
                }
            }
        }

        SegmentedToggle(
            options = listOf("Today", "Weekly"),
            selected = if (tab == PicksTab.DAILY) 0 else 1,
            onSelect = { tab = if (it == 0) PicksTab.DAILY else PicksTab.WEEKLY },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (tab == PicksTab.DAILY) {
                dailyItems(state, onOpenDetail, onOpenAnalysis)
            } else {
                weeklyItems(state, onOpenDetail, onOpenAnalysis)
            }
        }
    }
}

// ---------------------------------------------------------------- daily tab

private fun androidx.compose.foundation.lazy.LazyListScope.dailyItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    when {
        state.saturday -> item {
            EmptyState(
                title = "Daily picks are off on Saturday",
                message = "The US market is closed. Fresh same-day candidates return tomorrow, " +
                    "built from the latest pre-market moves, volume, techniques, and news."
            )
        }
        state.dailyRows.isEmpty() && (state.dailyLoading || state.dailyRefreshing) -> item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AurumColors.gold)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Scanning for today's movers…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.dailyRows.isEmpty() -> item {
            EmptyState(
                title = "No daily picks yet",
                message = "Tap refresh to scan the market for stocks set up for a 3-10%+ move today."
            )
        }
        else -> {
            item {
                Text(
                    text = "Stocks the engine reads as capable of a 3-10%+ up-move today, " +
                        "from momentum, volume, the 11 techniques, pre/post-market prints, and news.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            items(state.dailyRows, key = { "d-${it.symbol}" }) { pick ->
                DailyPickCard(
                    pick = pick,
                    onOpen = { onOpenDetail(pick.symbol) },
                    onAnalyze = { onOpenAnalysis(pick.symbol) }
                )
            }
            item {
                Text(
                    text = "Recomputed each day except Saturday. Potential ranges come from " +
                        "volatility (ATR) plus live catalysts — decision support, not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun DailyPickCard(pick: DailyPick, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    AurumCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = pick.rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.gold
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = pick.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillTag(
                        text = String.format(
                            Locale.US, "+%.0f–%.0f%% potential",
                            pick.expectedLowPct, pick.expectedHighPct
                        ),
                        color = AurumColors.gold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (pick.techDirection == "BULLISH") {
                        PillTag(
                            text = "${pick.techBullish}/11 bullish",
                            color = AurumColors.gain
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBar(score = pick.score, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pick.score.roundToInt().toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = pick.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                ExtendedHoursRow(pick)
                if (pick.headline.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SentimentDot(sentiment = pick.headlineSentiment)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (pick.headlineSource.isBlank()) pick.headline
                            else "${pick.headline} — ${pick.headlineSource}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onAnalyze() }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = AurumColors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Full analysis & $3,000 plan",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(pick.price),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                DeltaPct(value = pick.dayChangePct, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "today",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

/** "Pre-market +2.1% · After hours -0.3%" line; hidden when neither print exists. */
@Composable
private fun ExtendedHoursRow(pick: DailyPick) {
    val pre = pick.preMarketPct
    val post = pick.postMarketPct
    if (pre == null && post == null) return
    Spacer(modifier = Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (pre != null) {
            Text(
                text = "Pre-market ",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            Text(
                text = Fmt.signedPct(pre),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.deltaColor(pre)
            )
        }
        if (pre != null && post != null) {
            Text(
                text = "  ·  ",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        if (post != null) {
            Text(
                text = "After hours ",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            Text(
                text = Fmt.signedPct(post),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.deltaColor(post)
            )
        }
    }
}

// ---------------------------------------------------------------- weekly tab

private fun androidx.compose.foundation.lazy.LazyListScope.weeklyItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    if (state.rows.isEmpty()) {
        item {
            if (state.loading || state.refreshing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AurumColors.gold)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Scanning the market…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurumColors.textDim
                        )
                    }
                }
            } else {
                EmptyState(
                    title = "No picks this week yet",
                    message = "Tap refresh to scan the market and rank this week's ten strongest setups."
                )
            }
        }
    } else {
        items(state.rows, key = { it.pick.symbol }) { row ->
            PickCard(
                row = row,
                onOpen = { onOpenDetail(row.pick.symbol) },
                onAnalyze = { onOpenAnalysis(row.pick.symbol) }
            )
        }
    }

    if (state.budgetRows.isNotEmpty()) {
        item {
            Spacer(modifier = Modifier.height(14.dp))
            SectionHeader(title = "Under $25 · weekly watch")
        }
        items(state.budgetRows, key = { "u25-${it.pick.symbol}" }) { row ->
            PickCard(
                row = row,
                onOpen = { onOpenDetail(row.pick.symbol) },
                onAnalyze = { onOpenAnalysis(row.pick.symbol) }
            )
        }
    }
}

@Composable
private fun PickCard(row: PickRow, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    val pick = row.pick
    AurumCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = pick.rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.gold
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = pick.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBar(score = pick.score, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pick.score.roundToInt().toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = pick.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onAnalyze() }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = AurumColors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "5-day analysis",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(pick.priceAtPick),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = "at pick",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
                val since = row.sincePickPct
                if (since != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    DeltaPct(value = since, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = "since pick",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}
