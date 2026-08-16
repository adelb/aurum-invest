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
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.PickNote
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.RelatedMove
import com.aurum.invest.analytics.RelationGroup
import com.aurum.invest.analytics.UPick
import com.aurum.invest.analytics.UState
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.AlertPermissionCard
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
    U(PicksViewModel.TAB_U),
    ENTRIES(PicksViewModel.TAB_ENTRIES),
    POWER(PicksViewModel.TAB_POWER),
    WEEKLY(PicksViewModel.TAB_WEEKLY),
    RELATION(PicksViewModel.TAB_RELATION)
}

@Composable
fun PicksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: PicksViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PicksTab.U) }

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
                        PicksTab.U -> "U-Pattern Day"
                        PicksTab.ENTRIES -> "Best Entries"
                        PicksTab.POWER -> "Power Hour"
                        PicksTab.WEEKLY -> "Weekly Picks"
                        PicksTab.RELATION -> "Relations"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text
                )
                Text(
                    text = when (tab) {
                        PicksTab.U -> state.uLabel + " · dip first, rise into the close"
                        PicksTab.ENTRIES -> "Entry-price scan · 8-screen universe"
                        PicksTab.POWER -> "Buy 2:30–4:00 PM ET · sell into tomorrow"
                        PicksTab.WEEKLY -> state.weekLabel
                        PicksTab.RELATION -> "Who moves when the giants move"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
            val busy = when (tab) {
                PicksTab.U -> state.uRefreshing
                PicksTab.ENTRIES -> state.entryRefreshing
                PicksTab.POWER -> state.powerRefreshing
                PicksTab.WEEKLY -> state.refreshing
                PicksTab.RELATION -> state.relationRefreshing
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
                                // The header button is the FULL market rescan.
                                PicksTab.U -> vm.rescanU()
                                PicksTab.ENTRIES -> vm.refreshEntries()
                                PicksTab.POWER -> vm.refreshPower()
                                PicksTab.WEEKLY -> vm.refresh()
                                PicksTab.RELATION -> vm.refreshRelations()
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
            options = listOf("U-Day", "Entries", "Power", "Weekly", "Relation"),
            selected = when (tab) {
                PicksTab.U -> 0
                PicksTab.ENTRIES -> 1
                PicksTab.POWER -> 2
                PicksTab.WEEKLY -> 3
                PicksTab.RELATION -> 4
            },
            onSelect = {
                tab = when (it) {
                    0 -> PicksTab.U
                    1 -> PicksTab.ENTRIES
                    2 -> PicksTab.POWER
                    3 -> PicksTab.WEEKLY
                    else -> PicksTab.RELATION
                }
            },
            compact = true,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
        )

        AurumRefreshBox(
            refreshing = when (tab) {
                PicksTab.U -> state.uRefreshing
                PicksTab.ENTRIES -> state.entryRefreshing
                PicksTab.POWER -> state.powerRefreshing
                PicksTab.WEEKLY -> state.refreshing
                PicksTab.RELATION -> state.relationRefreshing
            },
            onRefresh = {
                when (tab) {
                    // Pull = quick live re-read; the header button rescans.
                    PicksTab.U -> vm.refreshU()
                    PicksTab.ENTRIES -> vm.refreshEntries()
                    PicksTab.POWER -> vm.refreshPower()
                    PicksTab.WEEKLY -> vm.refresh()
                    PicksTab.RELATION -> vm.refreshRelations()
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
                PicksTab.U -> uItems(state, onOpenDetail, onOpenAnalysis, vm::rescanU)
                PicksTab.ENTRIES -> entryItems(state, onOpenDetail, onOpenAnalysis, vm::refreshEntries)
                PicksTab.POWER -> powerItems(state, onOpenDetail, onOpenAnalysis, vm::refreshPower)
                PicksTab.WEEKLY -> weeklyItems(state, onOpenDetail, onOpenAnalysis, vm::refresh)
                PicksTab.RELATION -> relationItems(state, onOpenDetail, onOpenAnalysis, vm::refreshRelations)
            }
        }
        }
    }
}

// -------------------------------------------------------------- U-pattern tab

private fun androidx.compose.foundation.lazy.LazyListScope.uItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRescan: () -> Unit
) {
    item { UAlertsCard() }
    when {
        state.uRows.isEmpty() && (state.uLoading || state.uRefreshing) -> item {
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
                        text = "Fingerprinting the market's intraday history — the first scan " +
                            "of the day replays ~21 sessions of 5-minute bars per name, so it " +
                            "takes a minute or two. Later refreshes are quick.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.uRows.isEmpty() -> item {
            // Honest empty state: "no setup" may only be claimed when the
            // screener universe was actually reachable.
            val cov = state.coverage
            if (cov != null && !cov.reachable) {
                EmptyState(
                    title = "Scan failed — data source unreachable",
                    message = "None of the ${cov.screensRequested} Yahoo screens behind this " +
                        "scan could be fetched, so an empty list here means the scan FAILED — " +
                        "it says nothing about whether the edge exists today.",
                    actionLabel = "Retry the scan",
                    onAction = onRescan
                )
            } else {
                EmptyState(
                    title = "No stock passes the U bar today",
                    message = "A name is listed only when its measured record clears every gate: " +
                        "a real U habit (40%+ of recent sessions), a 1%+ median rebound, and a " +
                        "buy rule that actually paid on replay." +
                        (cov?.let {
                            if (it.screensMissing > 0) {
                                " Note: only ${it.screensLive + it.screensStale} of " +
                                    "${it.screensRequested} screens were reachable — this was a " +
                                    "PARTIAL scan."
                            } else {
                                " The scan covered ${it.rowsSeen} screener rows and found no " +
                                    "qualifying setup."
                            }
                        } ?: ""),
                    actionLabel = "Rescan the market",
                    onAction = onRescan
                )
            }
        }
        else -> {
            item {
                Text(
                    text = "Stocks that habitually dip after the open and climb back through " +
                        "the close — found by scanning Yahoo's 8 predefined market screens " +
                        "(a broad liquid-name sample, not every US stock), proven on ~21 " +
                        "sessions of 5-minute bars each (U-day rate, parabola curvature, " +
                        "VWAP-reclaim rule replay), confirmed by the 35-technique board, and " +
                        "tracked live so each card says whether NOW is the time to buy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                ScanCoverageNote(state.coverage)
            }
            items(state.uRows, key = { "u-${it.symbol}" }) { pick ->
                UPatternCard(
                    pick = pick,
                    onOpen = { onOpenDetail(pick.symbol) },
                    onAnalyze = { onOpenAnalysis(pick.symbol) },
                    note = noteFor(state, pick.symbol)
                )
            }
            item {
                Text(
                    text = "The buy signal is mechanical: after a real dip, a 5-minute bar " +
                        "closing back above VWAP without a new session low. Each name's card " +
                        "shows how often that exact rule paid on ITS OWN last month — a " +
                        "tendency with a measured record, not a promise. Alerts are checked " +
                        "every 15 minutes during the session. Decision support, not " +
                        "financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

/** Notification opt-in for buy-zone alerts; a quiet confirmation once granted. */
@Composable
private fun UAlertsCard() {
    AlertPermissionCard(
        enabledText = "Buy-zone alerts are on — checked every 15 minutes during the session.",
        title = "Get pinged at the turn",
        message = "Allow notifications and Aurum will alert you the moment a tracked " +
            "name confirms its intraday turn and enters the buy zone."
    )
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
            val cov = state.coverage
            if (cov != null && !cov.reachable) {
                EmptyState(
                    title = "Scan failed — data source unreachable",
                    message = "None of the ${cov.screensRequested} Yahoo screens could be " +
                        "fetched — an empty list here means the scan FAILED, not that no " +
                        "setup exists.",
                    actionLabel = "Retry",
                    onAction = onRefresh
                )
            } else {
                EmptyState(
                    title = "No power-hour setups found",
                    message = "No scanned stock shows the 4-day strength this play needs right " +
                        "now. Rescan during the buy window for the freshest read." +
                        (cov?.takeIf { it.screensMissing > 0 }?.let {
                            " Note: only ${it.screensLive + it.screensStale} of " +
                                "${it.screensRequested} screens were reachable — a PARTIAL scan."
                        } ?: ""),
                    actionLabel = "Scan now",
                    onAction = onRefresh
                )
            }
        }
        else -> {
            item {
                Text(
                    text = "The 10 strongest candidates to buy in the last 90 minutes and hold " +
                        "into tomorrow: Yahoo's 8 market screens (a broad liquid-name sample, " +
                        "not every US stock) swept for names finishing near their daily high " +
                        "on hot volume after 4 strong trading days, confirmed by the " +
                        "35-technique board. Refresh inside the window for the live read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                ScanCoverageNote(state.coverage)
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
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
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
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                        text = "Sweeping the screener universe for entry setups…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.entryRows.isEmpty() -> item {
            val cov = state.coverage
            if (cov != null && !cov.reachable) {
                EmptyState(
                    title = "Scan failed — data source unreachable",
                    message = "None of the ${cov.screensRequested} Yahoo screens could be " +
                        "fetched — an empty list here means the scan FAILED, not that no " +
                        "entry exists.",
                    actionLabel = "Retry",
                    onAction = onRefresh
                )
            } else {
                EmptyState(
                    title = "No entry setups found",
                    message = "The scan found no stock at a compelling entry right now." +
                        (cov?.takeIf { it.screensMissing > 0 }?.let {
                            " Note: only ${it.screensLive + it.screensStale} of " +
                                "${it.screensRequested} screens were reachable — a PARTIAL scan."
                        } ?: ""),
                    actionLabel = "Sweep again",
                    onAction = onRefresh
                )
            }
        }
        else -> {
            item {
                Text(
                    text = "The best entry setups across Yahoo's 8 predefined screens (a broad " +
                        "liquid-name sample, not every US stock): kept only when the long trend " +
                        "is intact, the price has pulled back toward support, the 35-technique " +
                        "board does not read the dip as a falling knife, and the week's news " +
                        "does not explain the dip away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                ScanCoverageNote(state.coverage)
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
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
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
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
/** Pill color + label for one U-pattern live state. */
@Composable
private fun UStatePill(state: String) {
    val (label, color) = when (state) {
        UState.BUY_ZONE.name -> "BUY ZONE — turn confirmed" to AurumColors.gain
        UState.IN_DIP.name -> "In the dip — wait" to AurumColors.gold
        UState.NO_DIP_YET.name -> "No dip yet" to AurumColors.textDim
        UState.TOO_LATE.name -> "Too late — already ran" to AurumColors.loss
        UState.NO_TURN.name -> "No turn today" to AurumColors.loss
        UState.AFTER_CLOSE.name -> "Session done" to AurumColors.info
        else -> "Market closed" to AurumColors.textDim
    }
    PillTag(text = label, color = color)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UPatternCard(
    pick: UPick,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    note: PickNote? = null
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
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UStatePill(pick.state)
                    PillTag(
                        text = "U on ${pick.uDays}/${pick.sessions} days",
                        color = AurumColors.gold
                    )
                    if (pick.techTotal > 0) {
                        PillTag(
                            text = "${pick.techBullish}/${pick.techTotal} bullish",
                            color = AurumColors.gain
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBar(score = pick.score.toDouble(), modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pick.score.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // The live verdict — this is the "when to buy" answer.
                Text(
                    text = pick.stateNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (pick.state) {
                        UState.BUY_ZONE.name -> AurumColors.gain
                        UState.IN_DIP.name -> AurumColors.gold
                        else -> AurumColors.textDim
                    }
                )
                val entry = pick.entry
                val stop = pick.stop
                val target = pick.target
                if (entry != null && stop != null && target != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Entry",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.textDim
                            )
                            Text(
                                text = Fmt.money(entry),
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.gain
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Stop",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.textDim
                            )
                            Text(
                                text = Fmt.money(stop),
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.loss
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Target",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.textDim
                            )
                            Text(
                                text = Fmt.money(target),
                                style = MaterialTheme.typography.titleSmall,
                                color = AurumColors.gold
                            )
                        }
                        val rr = pick.rewardRisk
                        if (rr != null && rr > 0.0) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "R:R",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AurumColors.textDim
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f", rr),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AurumColors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = pick.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Dip window ${pick.buyWindow} · sell window ${pick.sellWindow} " +
                        "(your time)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
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
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                DeltaPct(value = pick.dayChangePct, style = MaterialTheme.typography.labelMedium)
                val dip = pick.dipTodayPct
                if (dip != null && dip > 0.0) {
                    Text(
                        text = String.format(Locale.US, "dip −%.1f%%", dip),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        PortfolioNoteTag(note)
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
                "35-technique board over the fixed universe plus Yahoo's predefined screens " +
                "(a broad liquid-name sample, not every US stock). Every pick now carries " +
                "its stop and first target in the reason line: a pick without an exit is " +
                "not a plan.",
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
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
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
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

// ------------------------------------------------------------ relation tab

private fun androidx.compose.foundation.lazy.LazyListScope.relationItems(
    state: PicksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onRefresh: () -> Unit
) {
    when {
        state.relationRows.isEmpty() && (state.relationLoading || state.relationRefreshing) -> item {
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
                        text = "Measuring who moves with the giants…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.relationRows.isEmpty() -> item {
            EmptyState(
                title = "No relation data yet",
                message = "Load the first-party relation map: each giant, its suppliers and " +
                    "partners, and how tightly they actually move together.",
                actionLabel = "Load now",
                onAction = onRefresh
            )
        }
        else -> {
            item {
                Text(
                    text = "First-party relations: each giant with the stocks tied to it by a " +
                        "real business link — its manufacturer, suppliers, and the companies " +
                        "that build around it. When AMD rises this week, these lists answer " +
                        "who rises with it. The link is curated fact; every number is " +
                        "measured live — this week's moves and the actual 3-month " +
                        "correlation of daily returns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            items(state.relationRows, key = { "rel-${it.symbol}" }) { group ->
                RelationGroupCard(
                    group = group,
                    onOpenDetail = onOpenDetail,
                    onOpenAnalysis = onOpenAnalysis
                )
            }
            item {
                Text(
                    text = "Groups are ordered by the size of the giant's move this week. " +
                        "Correlation is measured from ~3 months of shared trading days — a " +
                        "curated link that measures weak is shown weak. Decision support, " +
                        "not financial advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun RelationGroupCard(
    group: RelationGroup,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    AurumCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onOpenDetail(group.symbol) }
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = group.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (group.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val week = group.weekChangePct
                    if (week != null) {
                        DeltaPct(value = week, style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "this week",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    DeltaPct(value = group.dayChangePct, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "today",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(group.price),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { onOpenAnalysis(group.symbol) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = "Analyze ${group.symbol}",
                        tint = AurumColors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Moves with ${group.symbol}",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        group.related.forEach { rel ->
            Spacer(modifier = Modifier.height(10.dp))
            RelatedRow(rel = rel, onOpen = { onOpenDetail(rel.symbol) })
        }
    }
}

@Composable
private fun RelatedRow(rel: RelatedMove, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rel.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
                val corr = rel.correlation
                if (corr != null) {
                    PillTag(
                        text = String.format(Locale.US, "r %.2f", corr),
                        color = when {
                            corr >= 0.6 -> AurumColors.gold
                            corr >= 0.3 -> AurumColors.info
                            else -> AurumColors.textDim
                        }
                    )
                } else {
                    PillTag(text = "r —", color = AurumColors.textDim)
                }
            }
            Text(
                text = rel.note.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            val week = rel.weekChangePct
            if (week != null) {
                DeltaPct(value = week, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "this week",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            } else {
                DeltaPct(value = rel.dayChangePct, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "today",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
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

/**
 * One-line universe provenance under a scan header (H4): exactly which
 * screens were served, how many rows the universe held, and when — so
 * "market scan" can never be read as "every US stock, right now".
 */
@Composable
private fun ScanCoverageNote(coverage: com.aurum.invest.data.model.ScanCoverage?) {
    if (coverage == null) return
    Text(
        text = "Universe: ${coverage.summary()}" +
            (if (coverage.oldestAsOf > 0L) " · as of ${Fmt.timeShort(coverage.oldestAsOf)}" else ""),
        style = MaterialTheme.typography.labelSmall,
        color = if (coverage.healthy) AurumColors.textDim else AurumColors.gold,
        modifier = Modifier.padding(top = 6.dp)
    )
}
