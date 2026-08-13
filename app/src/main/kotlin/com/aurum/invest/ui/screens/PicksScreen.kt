package com.aurum.invest.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.heightIn
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
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.DailyPick
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.PickNote
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.ExtHoursChips
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.ScoreBar
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.SentimentDot
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.roundToInt

private enum class PicksTab(val key: String) {
    DAILY(PicksViewModel.TAB_DAILY),
    ENTRIES(PicksViewModel.TAB_ENTRIES),
    POWER(PicksViewModel.TAB_POWER),
    WEEKLY(PicksViewModel.TAB_WEEKLY)
}

@Composable
fun PicksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: PicksViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PicksTab.DAILY) }

    // Each tab's market-wide scan starts only when that tab is first shown,
    // instead of all four firing at once when the screen opens.
    LaunchedEffect(tab) { vm.ensureTab(tab.key) }

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
                    text = when (tab) {
                        PicksTab.DAILY -> "Daily Picks"
                        PicksTab.ENTRIES -> "Best Entries"
                        PicksTab.POWER -> "Power Hour"
                        PicksTab.WEEKLY -> "Weekly Picks"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text
                )
                Text(
                    text = when (tab) {
                        PicksTab.DAILY -> state.dailyLabel
                        PicksTab.ENTRIES -> "Market-wide entry-price scan"
                        PicksTab.POWER -> "Buy 2:30–4:00 PM ET · sell into tomorrow"
                        PicksTab.WEEKLY -> state.weekLabel
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
            val busy = when (tab) {
                PicksTab.DAILY -> state.dailyRefreshing
                PicksTab.ENTRIES -> state.entryRefreshing
                PicksTab.POWER -> state.powerRefreshing
                PicksTab.WEEKLY -> state.refreshing
            }
            // Fixed 48dp slot so the header doesn't shift when the
            // refresh button swaps to the busy spinner and back.
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = AurumColors.gold,
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(
                        onClick = {
                            when (tab) {
                                PicksTab.DAILY -> vm.refreshDaily()
                                PicksTab.ENTRIES -> vm.refreshEntries()
                                PicksTab.POWER -> vm.refreshPower()
                                PicksTab.WEEKLY -> vm.refresh()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Recompute picks",
                            tint = AurumColors.gold
                        )
                    }
                }
            }
        }

        SegmentedToggle(
            options = listOf("Today", "Entries", "Power", "Weekly"),
            selected = when (tab) {
                PicksTab.DAILY -> 0
                PicksTab.ENTRIES -> 1
                PicksTab.POWER -> 2
                PicksTab.WEEKLY -> 3
            },
            onSelect = {
                tab = when (it) {
                    0 -> PicksTab.DAILY
                    1 -> PicksTab.ENTRIES
                    2 -> PicksTab.POWER
                    else -> PicksTab.WEEKLY
                }
            },
            compact = true,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
        )

        AurumRefreshBox(
            refreshing = when (tab) {
                PicksTab.DAILY -> state.dailyRefreshing
                PicksTab.ENTRIES -> state.entryRefreshing
                PicksTab.POWER -> state.powerRefreshing
                PicksTab.WEEKLY -> state.refreshing
            },
            onRefresh = {
                when (tab) {
                    PicksTab.DAILY -> vm.refreshDaily()
                    PicksTab.ENTRIES -> vm.refreshEntries()
                    PicksTab.POWER -> vm.refreshPower()
                    PicksTab.WEEKLY -> vm.refresh()
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (tab) {
                PicksTab.DAILY -> dailyItems(state, onOpenDetail, onOpenAnalysis, vm::refreshDaily)
                PicksTab.ENTRIES -> entryItems(state, onOpenDetail, onOpenAnalysis, vm::refreshEntries)
                PicksTab.POWER -> powerItems(state, onOpenDetail, onOpenAnalysis, vm::refreshPower)
                PicksTab.WEEKLY -> weeklyItems(state, onOpenDetail, onOpenAnalysis, vm::refresh)
            }
        }
        }
    }
}

// ---------------------------------------------------------------- daily tab

private fun androidx.compose.foundation.lazy.LazyListScope.dailyItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
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
                message = "Scan the market for stocks set up for a 3-10%+ move today.",
                actionLabel = "Scan now",
                onAction = onRefresh
            )
        }
        else -> {
            item {
                Text(
                    text = "The stocks best positioned to move today — whole-market screens plus " +
                        "the fixed universe, read through momentum, volume, the 15 techniques, " +
                        "pre/post-market prints, and news. Each range is that stock's own " +
                        "measured volatility, not a promise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            items(state.dailyRows, key = { "d-${it.symbol}" }) { pick ->
                DailyPickCard(
                    pick = pick,
                    onOpen = { onOpenDetail(pick.symbol) },
                    onAnalyze = { onOpenAnalysis(pick.symbol) },
                    note = noteFor(state, pick.symbol),
                    ext = state.extHours[pick.symbol]
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

// ---------------------------------------------------------------- power tab

private fun androidx.compose.foundation.lazy.LazyListScope.powerItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    item { PowerWindowBanner() }
    when {
        state.powerRows.isEmpty() && (state.powerLoading || state.powerRefreshing) -> item {
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
                        text = "Sweeping the market for closing strength…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.powerRows.isEmpty() -> item {
            EmptyState(
                title = "No power-hour setups found",
                message = "No stock shows the 4-day strength this play needs right now. " +
                    "Rescan during the buy window for the freshest read.",
                actionLabel = "Scan now",
                onAction = onRefresh
            )
        }
        else -> {
            item {
                Text(
                    text = "The 10 strongest candidates to buy in the last 90 minutes and hold " +
                        "into tomorrow: the whole market screened for names finishing near " +
                        "their daily high on hot volume after 4 strong trading days, confirmed " +
                        "by the 15-technique board. Refresh inside the window for the live read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            items(state.powerRows, key = { "p-${it.symbol}" }) { pick ->
                PowerPickCard(
                    pick = pick,
                    onOpen = { onOpenDetail(pick.symbol) },
                    onAnalyze = { onOpenAnalysis(pick.symbol) },
                    note = noteFor(state, pick.symbol),
                    ext = state.extHours[pick.symbol]
                )
            }
            item {
                Text(
                    text = "Overnight positions carry gap risk — next-day potential comes from " +
                        "volatility (ATR) plus the momentum evidence, never a promise. Sell into " +
                        "morning strength and honor every stop. Decision support, not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun PowerWindowBanner() {
    val (window, startMs, endMs) = Dates.powerWindowNow()
    val fmt = java.text.SimpleDateFormat("h:mm a", Locale.US)
    val (text, color) = when (window) {
        Dates.PowerWindow.OPEN ->
            "Buy window OPEN — closes ${fmt.format(java.util.Date(endMs))} your time " +
                "(4:00 PM ET)." to AurumColors.gain
        Dates.PowerWindow.BEFORE ->
            "Buy window opens ${fmt.format(java.util.Date(startMs))} your time " +
                "(2:30 PM ET)." to AurumColors.gold
        Dates.PowerWindow.CLOSED ->
            "Today's buy window has closed — these picks are for the next session." to
                AurumColors.textDim
        Dates.PowerWindow.WEEKEND ->
            "Market closed — the buy window returns Monday 2:30–4:00 PM ET." to
                AurumColors.textDim
    }
    AurumCard {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerPickCard(
    pick: PowerPick,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    note: PickNote? = null,
    ext: ExtendedHours? = null
) {
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
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillTag(
                        text = String.format(
                            Locale.US, "+%.1f–%.1f%% ATR-based range",
                            pick.expectedLowPct, pick.expectedHighPct
                        ),
                        color = AurumColors.gold
                    )
                    if (pick.techDirection == "BULLISH" && pick.techTotal > 0) {
                        PillTag(
                            text = "${pick.techBullish}/${pick.techTotal} bullish",
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
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        label = "Buy at",
                        value = Fmt.money(pick.price),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Morning target",
                        value = Fmt.money(pick.target),
                        modifier = Modifier.weight(1f),
                        valueColor = AurumColors.gain
                    )
                    StatTile(
                        label = "Stop",
                        value = Fmt.money(pick.stop),
                        modifier = Modifier.weight(1f),
                        valueColor = AurumColors.loss
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sell into tomorrow morning's strength — at the target, or in the " +
                        "first hour if it stalls; exit immediately if the stop breaks.",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.text
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = pick.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                if (pick.headline.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SentimentDot(sentiment = pick.newsScore)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${pick.headline} — ${pick.headlineSource}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (ext?.preMarketPct != null || ext?.postMarketPct != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ExtHoursChips(ext = ext)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 40.dp).clickable { onAnalyze() }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = AurumColors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Full analysis & buy plan",
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
        PortfolioNoteTag(note)
    }
}

// ---------------------------------------------------------------- entries tab

private fun androidx.compose.foundation.lazy.LazyListScope.entryItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    when {
        state.entryRows.isEmpty() && (state.entryLoading || state.entryRefreshing) -> item {
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
                        text = "Sweeping the whole market for entry setups…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.entryRows.isEmpty() -> item {
            EmptyState(
                title = "No entry setups found",
                message = "The market-wide scan found no stock at a compelling entry right now.",
                actionLabel = "Sweep again",
                onAction = onRefresh
            )
        }
        else -> {
            item {
                Text(
                    text = "The best entry setups across 8 market-wide screens: kept only when " +
                        "the long trend is intact, the price has pulled back toward support, " +
                        "the 15-technique board does not read the dip as a falling knife, and " +
                        "the week's news does not explain the dip away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            items(state.entryRows, key = { "e-${it.symbol}" }) { pick ->
                EntryPickCard(
                    pick = pick,
                    onOpen = { onOpenDetail(pick.symbol) },
                    onAnalyze = { onOpenAnalysis(pick.symbol) },
                    note = noteFor(state, pick.symbol),
                    ext = state.extHours[pick.symbol]
                )
            }
            item {
                Text(
                    text = "Refreshed automatically every 30 minutes and on demand. Upside, risk, " +
                        "and R:R are measured from the limit price shown — the price you would " +
                        "actually pay. Entry, target, and stop derive from support/resistance " +
                        "and ATR — decision support, not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryPickCard(
    pick: EntryPick,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    note: PickNote? = null,
    ext: ExtendedHours? = null
) {
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
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillTag(
                        text = String.format(Locale.US, "RR %.1f", pick.rewardRisk),
                        color = AurumColors.gold
                    )
                    if (pick.techDirection == "BULLISH" && pick.techTotal > 0) {
                        PillTag(
                            text = "${pick.techBullish}/${pick.techTotal} bullish",
                            color = AurumColors.gain
                        )
                    }
                    val rating = pick.analystRating
                    if (rating != null && rating <= 2.5) {
                        PillTag(text = "Buy-rated", color = AurumColors.info)
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
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        label = if (pick.entryLimit >= pick.price) "Enter at" else "Limit at",
                        value = Fmt.money(if (pick.entryLimit >= pick.price) pick.price else pick.entryLimit),
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Target",
                        value = Fmt.money(pick.target),
                        modifier = Modifier.weight(1f),
                        valueColor = AurumColors.gain
                    )
                    StatTile(
                        label = "Stop",
                        value = Fmt.money(pick.stop),
                        modifier = Modifier.weight(1f),
                        valueColor = AurumColors.loss
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "+%.1f%% to target · %.1f%% risk to the stop — from the limit price",
                        pick.upsidePct, pick.riskPct
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.gain
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = pick.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                if (ext?.preMarketPct != null || ext?.postMarketPct != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ExtHoursChips(ext = ext)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 40.dp).clickable { onAnalyze() }
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
                    text = "last session",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        PortfolioNoteTag(note)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyPickCard(
    pick: DailyPick,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    note: PickNote? = null,
    ext: ExtendedHours? = null
) {
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
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillTag(
                        text = String.format(
                            Locale.US, "+%.0f–%.0f%% potential",
                            pick.expectedLowPct, pick.expectedHighPct
                        ),
                        color = AurumColors.gold
                    )
                    if (pick.techDirection == "BULLISH" && pick.techTotal > 0) {
                        PillTag(
                            text = "${pick.techBullish}/${pick.techTotal} bullish",
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
                ExtendedHoursRow(pick, ext)
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
                    modifier = Modifier.heightIn(min = 40.dp).clickable { onAnalyze() }
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
                    // Before the open, price and change still belong to the
                    // previous regular session — say so instead of "today".
                    text = when (pick.marketState) {
                        "PRE", "PREPRE", "CLOSED", "POSTPOST" -> "last session"
                        else -> "today"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        PortfolioNoteTag(note)
    }
}

/**
 * "Pre-market +2.1% · After hours -0.3%" line; hidden when neither print
 * exists. The LIVE extended-hours read wins over the values stored with the
 * pick, which age as the session moves on.
 */
@Composable
private fun ExtendedHoursRow(pick: DailyPick, ext: ExtendedHours? = null) {
    val pre = ext?.preMarketPct ?: pick.preMarketPct
    val post = ext?.postMarketPct ?: pick.postMarketPct
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
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    item {
        Text(
            text = "The week's strongest sustained setups — momentum, volume, and the " +
                "15-technique board over the fixed universe plus the market-wide screens. " +
                "Every pick now carries its stop and first target in the reason line: a " +
                "pick without an exit is not a plan.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
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
                    message = "Scan the market to rank this week's ten strongest setups.",
                    actionLabel = "Scan now",
                    onAction = onRefresh
                )
            }
        }
    } else {
        items(state.rows, key = { it.pick.symbol }) { row ->
            PickCard(
                row = row,
                onOpen = { onOpenDetail(row.pick.symbol) },
                onAnalyze = { onOpenAnalysis(row.pick.symbol) },
                note = noteFor(state, row.pick.symbol),
                ext = state.extHours[row.pick.symbol]
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
                onAnalyze = { onOpenAnalysis(row.pick.symbol) },
                note = noteFor(state, row.pick.symbol),
                ext = state.extHours[row.pick.symbol]
            )
        }
    }
    item {
        Text(
            text = "Computed at the week's open and held for the week; \"since pick\" tracks " +
                "each name live. Scores sit on a fixed scale — a quiet week reads low, and " +
                "that is the honest answer. Decision support, not financial advice.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun PickCard(
    row: PickRow,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    note: PickNote? = null,
    ext: ExtendedHours? = null
) {
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
                if (ext?.preMarketPct != null || ext?.postMarketPct != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ExtHoursChips(ext = ext)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 40.dp).clickable { onAnalyze() }
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
        PortfolioNoteTag(note)
    }
}

// ------------------------------------------------------- portfolio context

/** The pick's note against the user's live book — null when nothing factual applies. */
private fun noteFor(state: PicksState, symbol: String): PickNote? =
    PortfolioLens.pickNote(symbol, state.pickSectors[symbol], state.book)

/** Renders a pick's portfolio note as a stamped tag; held gold, risk red, fresh blue. */
@Composable
private fun PortfolioNoteTag(note: PickNote?) {
    if (note == null) return
    Spacer(modifier = Modifier.height(10.dp))
    PillTag(
        text = note.text,
        color = when (note.kind) {
            NoteKind.HELD -> AurumColors.gold
            NoteKind.CONCENTRATION -> AurumColors.loss
            NoteKind.DIVERSIFIES -> AurumColors.info
        }
    )
}
