package com.aurum.invest.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.PickNote
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.DailyPick
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.DetailLine
import com.aurum.invest.ui.components.DisclosureRow
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.Footnote
import com.aurum.invest.ui.components.RowDivider
import com.aurum.invest.ui.components.ScreenTitle
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.Space
import com.aurum.invest.ui.components.StatusTag
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale

private enum class PicksTab(val key: String, val label: String) {
    DAILY(PicksViewModel.TAB_DAILY, "Today"),
    ENTRIES(PicksViewModel.TAB_ENTRIES, "Entries"),
    POWER(PicksViewModel.TAB_POWER, "Power"),
    WEEKLY(PicksViewModel.TAB_WEEKLY, "Weekly")
}

/**
 * Picks: one list, one job per tab. Each row states the stock and the single
 * number that matters for that list; the supporting evidence opens on tap so
 * the page stays scannable.
 */
@Composable
fun PicksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: PicksViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PicksTab.DAILY) }

    // Each tab's market-wide scan starts when that tab is first shown.
    LaunchedEffect(tab) { vm.ensureTab(tab.key) }

    val busy = when (tab) {
        PicksTab.DAILY -> state.dailyRefreshing
        PicksTab.ENTRIES -> state.entryRefreshing
        PicksTab.POWER -> state.powerRefreshing
        PicksTab.WEEKLY -> state.refreshing
    }
    val onRefresh: () -> Unit = {
        when (tab) {
            PicksTab.DAILY -> vm.refreshDaily()
            PicksTab.ENTRIES -> vm.refreshEntries()
            PicksTab.POWER -> vm.refreshPower()
            PicksTab.WEEKLY -> vm.refresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {
        ScreenTitle(
            overline = when (tab) {
                PicksTab.DAILY -> state.dailyLabel
                PicksTab.ENTRIES -> "Market-wide scan"
                PicksTab.POWER -> "2:30–4:00 PM ET"
                PicksTab.WEEKLY -> state.weekLabel
            },
            title = when (tab) {
                PicksTab.DAILY -> "Today"
                PicksTab.ENTRIES -> "Best entries"
                PicksTab.POWER -> "Power hour"
                PicksTab.WEEKLY -> "This week"
            },
            modifier = Modifier.padding(start = Space.screenH, end = Space.screenH, top = 18.dp),
            trailing = {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AurumColors.gold,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Rescan",
                                tint = AurumColors.gold
                            )
                        }
                    }
                }
            }
        )

        SegmentedToggle(
            options = PicksTab.entries.map { it.label },
            selected = tab.ordinal,
            onSelect = { tab = PicksTab.entries[it] },
            compact = true,
            modifier = Modifier.padding(start = Space.screenH, end = Space.screenH, top = 16.dp)
        )

        AurumRefreshBox(
            refreshing = busy,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.screenH, end = Space.screenH, top = 20.dp, bottom = 32.dp
                )
            ) {
                when (tab) {
                    PicksTab.DAILY -> dailyItems(state, onOpenDetail, onOpenAnalysis, onRefresh)
                    PicksTab.ENTRIES -> entryItems(state, onOpenDetail, onOpenAnalysis, onRefresh)
                    PicksTab.POWER -> powerItems(state, onOpenDetail, onOpenAnalysis, onRefresh)
                    PicksTab.WEEKLY -> weeklyItems(state, onOpenDetail, onOpenAnalysis, onRefresh)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ scaffolding

private fun LazyListScope.loadingItem(message: String) {
    item {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AurumColors.gold)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

private fun LazyListScope.footnoteItem(text: String) {
    item {
        Spacer(Modifier.height(18.dp))
        Footnote(text)
    }
}

/**
 * The shared shape of every pick row: rank, name, the headline number, an
 * optional portfolio tag — and the evidence one tap away.
 */
@Composable
private fun PickRowItem(
    rank: Int,
    symbol: String,
    name: String,
    headline: String,
    headlineColor: androidx.compose.ui.graphics.Color,
    support: String,
    note: PickNote?,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    detail: @Composable () -> Unit
) {
    DisclosureRow(
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp)
            ) {
                Text(
                    text = rank.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold,
                    modifier = Modifier.width(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.titleMedium,
                            color = AurumColors.text
                        )
                        if (note != null) {
                            Spacer(Modifier.width(8.dp))
                            StatusTag(
                                text = when (note.kind) {
                                    NoteKind.HELD -> "held"
                                    NoteKind.CONCENTRATION -> "concentrates"
                                    NoteKind.DIVERSIFIES -> "new sector"
                                },
                                color = when (note.kind) {
                                    NoteKind.HELD -> AurumColors.gold
                                    NoteKind.CONCENTRATION -> AurumColors.loss
                                    NoteKind.DIVERSIFIES -> AurumColors.info
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = name.ifBlank { support },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = headlineColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = support,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
        }
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            detail()
            note?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (it.kind) {
                        NoteKind.HELD -> AurumColors.gold
                        NoteKind.CONCENTRATION -> AurumColors.loss
                        NoteKind.DIVERSIFIES -> AurumColors.info
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text(symbol, color = AurumColors.gold) }
                TextButton(onClick = onAnalyze) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.QueryStats,
                            contentDescription = null,
                            tint = AurumColors.gold,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Analysis", color = AurumColors.gold)
                    }
                }
            }
        }
    }
    RowDivider()
}

private fun noteFor(state: PicksState, symbol: String): PickNote? =
    PortfolioLens.pickNote(symbol, state.pickSectors[symbol], state.book)

// ------------------------------------------------------------------ today

private fun LazyListScope.dailyItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    when {
        state.saturday -> item {
            EmptyState(
                title = "Closed on Saturday",
                message = "Fresh same-day candidates return tomorrow."
            )
        }
        state.dailyRows.isEmpty() && (state.dailyLoading || state.dailyRefreshing) ->
            loadingItem("Scanning today's movers…")
        state.dailyRows.isEmpty() -> item {
            EmptyState(
                title = "No picks yet",
                message = "Scan for stocks set up to move 3–10% today.",
                actionLabel = "Scan now",
                onAction = onRefresh
            )
        }
        else -> {
            item {
                Footnote("Ranked on momentum, volume, the 15 techniques, extended hours and news.")
                Spacer(Modifier.height(12.dp))
                RowDivider()
            }
            items(state.dailyRows, key = { "d-${it.symbol}" }) { pick ->
                DailyRow(pick, noteFor(state, pick.symbol), onOpenDetail, onOpenAnalysis)
            }
            footnoteItem(
                "Ranges come from volatility (ATR) plus live catalysts — decision support, " +
                    "not financial advice."
            )
        }
    }
}

@Composable
private fun DailyRow(
    pick: DailyPick,
    note: PickNote?,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    PickRowItem(
        rank = pick.rank,
        symbol = pick.symbol,
        name = pick.name,
        headline = String.format(
            Locale.US, "+%.0f–%.0f%%", pick.expectedLowPct, pick.expectedHighPct
        ),
        headlineColor = AurumColors.gain,
        support = "potential today",
        note = note,
        onOpen = { onOpenDetail(pick.symbol) },
        onAnalyze = { onOpenAnalysis(pick.symbol) }
    ) {
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        DetailLine("Price", Fmt.money(pick.price))
        DetailLine(
            label = when (pick.marketState) {
                "PRE", "PREPRE", "CLOSED", "POSTPOST" -> "Last session"
                else -> "Today"
            },
            value = Fmt.signedPct(pick.dayChangePct),
            valueColor = AurumColors.deltaColor(pick.dayChangePct)
        )
        pick.preMarketPct?.let {
            DetailLine("Pre-market", Fmt.signedPct(it), valueColor = AurumColors.deltaColor(it))
        }
        pick.postMarketPct?.let {
            DetailLine("After hours", Fmt.signedPct(it), valueColor = AurumColors.deltaColor(it))
        }
        DetailLine("Volume", String.format(Locale.US, "%.1fx average", pick.volumeRatio))
        if (pick.techTotal > 0) {
            DetailLine("Techniques", "${pick.techBullish}/${pick.techTotal} bullish")
        }
        if (pick.headline.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "“${pick.headline}” — ${pick.headlineSource}",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

// ------------------------------------------------------------------ entries

private fun LazyListScope.entryItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    when {
        state.entryRows.isEmpty() && (state.entryLoading || state.entryRefreshing) ->
            loadingItem("Sweeping the market for entry setups…")
        state.entryRows.isEmpty() -> item {
            EmptyState(
                title = "No entry setups",
                message = "No stock sits at a compelling entry right now.",
                actionLabel = "Sweep again",
                onAction = onRefresh
            )
        }
        else -> {
            item {
                Footnote("Long trend intact, pulled back toward support, technique board still constructive.")
                Spacer(Modifier.height(12.dp))
                RowDivider()
            }
            items(state.entryRows, key = { "e-${it.symbol}" }) { pick ->
                EntryRow(pick, noteFor(state, pick.symbol), onOpenDetail, onOpenAnalysis)
            }
            footnoteItem(
                "Entry, target and stop derive from support/resistance and volatility (ATR) — " +
                    "decision support, not financial advice."
            )
        }
    }
}

@Composable
private fun EntryRow(
    pick: EntryPick,
    note: PickNote?,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    PickRowItem(
        rank = pick.rank,
        symbol = pick.symbol,
        name = pick.name,
        headline = Fmt.money(pick.entryLimit),
        headlineColor = AurumColors.gold,
        support = "entry",
        note = note,
        onOpen = { onOpenDetail(pick.symbol) },
        onAnalyze = { onOpenAnalysis(pick.symbol) }
    ) {
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        DetailLine("Last price", Fmt.money(pick.price))
        DetailLine("Target", Fmt.money(pick.target), valueColor = AurumColors.gain)
        DetailLine("Stop", Fmt.money(pick.stop), valueColor = AurumColors.loss)
        DetailLine(
            label = "Upside vs risk",
            value = String.format(
                Locale.US, "%+.1f%% / -%.1f%% · %.1f:1",
                pick.upsidePct, pick.riskPct, pick.rewardRisk
            )
        )
        DetailLine("RSI", String.format(Locale.US, "%.0f", pick.rsi))
        DetailLine("Off the 20-day high", String.format(Locale.US, "%.1f%%", pick.dipPct))
        if (pick.techTotal > 0) {
            DetailLine("Techniques", "${pick.techBullish}/${pick.techTotal} bullish")
        }
    }
}

// ------------------------------------------------------------------ power

private fun LazyListScope.powerItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    item { PowerWindowBanner() }
    when {
        state.powerRows.isEmpty() && (state.powerLoading || state.powerRefreshing) ->
            loadingItem("Sweeping for closing strength…")
        state.powerRows.isEmpty() -> item {
            EmptyState(
                title = "No power-hour setups",
                message = "No stock shows the 4-day strength this play needs.",
                actionLabel = "Scan now",
                onAction = onRefresh
            )
        }
        else -> {
            item {
                Footnote("Buy in the last 90 minutes, sell into tomorrow morning's strength.")
                Spacer(Modifier.height(12.dp))
                RowDivider()
            }
            items(state.powerRows, key = { "p-${it.symbol}" }) { pick ->
                PowerRow(pick, noteFor(state, pick.symbol), onOpenDetail, onOpenAnalysis)
            }
            footnoteItem(
                "Overnight positions carry gap risk. Ranges come from volatility plus momentum " +
                    "evidence, never a promise — honor every stop."
            )
        }
    }
}

@Composable
private fun PowerWindowBanner() {
    val (window, startMs, endMs) = Dates.powerWindowNow()
    val fmt = java.text.SimpleDateFormat("h:mm a", Locale.US)
    val open = window == Dates.PowerWindow.OPEN
    val label = when (window) {
        Dates.PowerWindow.OPEN -> "Buy window open — until ${fmt.format(java.util.Date(endMs))}"
        Dates.PowerWindow.BEFORE -> "Buy window opens ${fmt.format(java.util.Date(startMs))}"
        Dates.PowerWindow.WEEKEND -> "Market closed for the weekend"
        Dates.PowerWindow.CLOSED -> "Buy window closed — next session ${fmt.format(java.util.Date(startMs))}"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
    ) {
        StatusTag(
            text = if (open) "live" else "waiting",
            color = if (open) AurumColors.gain else AurumColors.textDim
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun PowerRow(
    pick: PowerPick,
    note: PickNote?,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    PickRowItem(
        rank = pick.rank,
        symbol = pick.symbol,
        name = pick.name,
        headline = String.format(
            Locale.US, "+%.0f–%.0f%%", pick.expectedLowPct, pick.expectedHighPct
        ),
        headlineColor = AurumColors.gain,
        support = "by tomorrow",
        note = note,
        onOpen = { onOpenDetail(pick.symbol) },
        onAnalyze = { onOpenAnalysis(pick.symbol) }
    ) {
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        DetailLine("Price", Fmt.money(pick.price))
        DetailLine("Morning target", Fmt.money(pick.target), valueColor = AurumColors.gain)
        DetailLine("Stop", Fmt.money(pick.stop), valueColor = AurumColors.loss)
        DetailLine(
            label = "Last 4 days",
            value = String.format(Locale.US, "%+.1f%% · %d of 4 up", pick.r4Pct, pick.upDays)
        )
        DetailLine(
            label = "Closing position",
            value = String.format(Locale.US, "%.0f%% of the day's range", pick.closePosPct)
        )
        DetailLine("Volume", String.format(Locale.US, "%.1fx average", pick.volumeRatio))
        if (pick.techTotal > 0) {
            DetailLine("Techniques", "${pick.techBullish}/${pick.techTotal} bullish")
        }
    }
}

// ------------------------------------------------------------------ weekly

private fun LazyListScope.weeklyItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    if (state.rows.isEmpty()) {
        if (state.loading || state.refreshing) {
            loadingItem("Scanning the market…")
        } else {
            item {
                EmptyState(
                    title = "No picks this week",
                    message = "Scan to rank this week's strongest setups.",
                    actionLabel = "Scan now",
                    onAction = onRefresh
                )
            }
        }
    } else {
        item {
            Footnote("The strongest setups from the tracked universe plus this week's live movers.")
            Spacer(Modifier.height(12.dp))
            RowDivider()
        }
        items(state.rows, key = { it.pick.symbol }) { row ->
            WeeklyRow(row, noteFor(state, row.pick.symbol), onOpenDetail, onOpenAnalysis)
        }
    }

    if (state.budgetRows.isNotEmpty()) {
        item {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "UNDER $25",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(12.dp))
            RowDivider()
        }
        items(state.budgetRows, key = { "u25-${it.pick.symbol}" }) { row ->
            WeeklyRow(row, noteFor(state, row.pick.symbol), onOpenDetail, onOpenAnalysis)
        }
    }
}

@Composable
private fun WeeklyRow(
    row: PickRow,
    note: PickNote?,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    val pick = row.pick
    val since = row.sincePickPct
    PickRowItem(
        rank = pick.rank,
        symbol = pick.symbol,
        name = pick.name,
        headline = Fmt.money(row.quote?.price ?: pick.priceAtPick),
        headlineColor = AurumColors.text,
        support = if (since != null) "${Fmt.signedPct(since)} since pick" else "at pick",
        note = note,
        onOpen = { onOpenDetail(pick.symbol) },
        onAnalyze = { onOpenAnalysis(pick.symbol) }
    ) {
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        DetailLine("Price at pick", Fmt.money(pick.priceAtPick))
        row.quote?.let { DetailLine("Now", Fmt.money(it.price)) }
        since?.let {
            DetailLine(
                label = "Since pick",
                value = Fmt.signedPct(it),
                valueColor = AurumColors.deltaColor(it)
            )
        }
        DetailLine("Score", String.format(Locale.US, "%.0f / 100", pick.score))
    }
}
