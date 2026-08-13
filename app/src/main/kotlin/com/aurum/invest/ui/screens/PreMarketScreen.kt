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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.IntradayPick
import com.aurum.invest.analytics.PreMarketPick
import com.aurum.invest.analytics.TargetOdds
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.ScoreBar
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

/** The two halves of the 2% desk. */
private enum class DeskTab { PRE, OPEN }

/**
 * The 2% desk: one editable daily profit goal, answered two ways. Before the
 * bell, the strongest pre-market movers measured against the goal; once the
 * market is trading, a live scan for names still positioned to add the goal
 * from their current price — volume-backed and technique-confirmed.
 */
@Composable
fun PreMarketScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: PreMarketViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var editingTarget by remember { mutableStateOf(false) }
    // Land on the half that matches the live session: the open-market scan
    // while the market trades, the pre-market desk otherwise.
    var tab by rememberSaveable {
        mutableStateOf(
            if (Dates.marketSessionNow() == Dates.MarketSession.REGULAR) DeskTab.OPEN
            else DeskTab.PRE
        )
    }

    // Re-read on every visit: opening the app mid-session must show that
    // moment's prints, not whatever was computed hours earlier.
    LifecycleResumeEffect(tab) {
        when (tab) {
            DeskTab.PRE -> vm.onShown()
            DeskTab.OPEN -> vm.ensureIntraday()
        }
        onPauseOrDispose { }
    }

    if (editingTarget) {
        TargetDialog(
            current = state.targetPct,
            onDismiss = { editingTarget = false },
            onSave = {
                vm.setTarget(it)
                editingTarget = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "2%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text
                )
                Text(
                    text = when (tab) {
                        DeskTab.PRE -> sessionLine(state)
                        DeskTab.OPEN -> intradaySessionLine(state)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        tab == DeskTab.PRE && state.session == Dates.MarketSession.PRE ->
                            AurumColors.gold
                        tab == DeskTab.OPEN && state.session == Dates.MarketSession.REGULAR ->
                            AurumColors.gold
                        else -> AurumColors.textDim
                    }
                )
            }
            val busy = when (tab) {
                DeskTab.PRE -> state.refreshing
                DeskTab.OPEN -> state.intradayRefreshing
            }
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
                                DeskTab.PRE -> vm.refresh()
                                DeskTab.OPEN -> vm.refreshIntraday()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Rescan",
                            tint = AurumColors.gold
                        )
                    }
                }
            }
        }

        SegmentedToggle(
            options = listOf("Pre-market", "Market open"),
            selected = if (tab == DeskTab.PRE) 0 else 1,
            onSelect = { tab = if (it == 0) DeskTab.PRE else DeskTab.OPEN },
            compact = true,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)
        )

        AurumRefreshBox(
            refreshing = when (tab) {
                DeskTab.PRE -> state.refreshing
                DeskTab.OPEN -> state.intradayRefreshing
            },
            onRefresh = {
                when (tab) {
                    DeskTab.PRE -> vm.refresh()
                    DeskTab.OPEN -> vm.refreshIntraday()
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { TargetCard(state = state, tab = tab, onEdit = { editingTarget = true }) }
                when (tab) {
                    DeskTab.PRE -> preMarketItems(state, vm, onOpenDetail, onOpenAnalysis)
                    DeskTab.OPEN -> intradayItems(state, vm, onOpenDetail, onOpenAnalysis)
                }
            }
        }
    }
}

// ------------------------------------------------------------ pre-market tab

private fun androidx.compose.foundation.lazy.LazyListScope.preMarketItems(
    state: PreMarketState,
    vm: PreMarketViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    if (state.rows.isNotEmpty() && !state.livePreMarket) {
        item { LastSessionNotice() }
    }

    when {
        state.rows.isEmpty() && state.loading -> item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AurumColors.gold)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Reading pre-market prints and session history…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.rows.isEmpty() -> item {
            EmptyState(
                title = "Nothing moving pre-market",
                message = "No liquid name shows a pre-market gain right now. " +
                    "Rescan closer to the open — pre-market volume builds from " +
                    "${Dates.etAsAmman(4)} Amman time.",
                actionLabel = "Rescan",
                onAction = vm::refresh
            )
        }
        else -> {
            items(state.rows, key = { "pm-${it.symbol}" }) { pick ->
                PreMarketCard(
                    pick = pick,
                    onOpen = { onOpenDetail(pick.symbol) },
                    onAnalyze = { onOpenAnalysis(pick.symbol) }
                )
            }
            item { MethodCard(targetPct = state.targetPct) }
        }
    }
}

// ------------------------------------------------------------ open-market tab

private fun androidx.compose.foundation.lazy.LazyListScope.intradayItems(
    state: PreMarketState,
    vm: PreMarketViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    val marketOpen = state.session == Dates.MarketSession.REGULAR
    if (!marketOpen && state.intradayRows.isNotEmpty()) {
        item { StaleScanNotice(asOf = state.intradayAsOf) }
    }

    when {
        state.intradayRows.isEmpty() && !marketOpen -> item {
            EmptyState(
                title = "The market is not open",
                message = "This scan reads the live session: stocks trading above their open " +
                    "on real volume, with the technique board bullish and your target still " +
                    "reachable from the current price. It runs " +
                    "${Dates.etAsAmman(9, 30)}–${Dates.etAsAmman(16)} Amman time, " +
                    "Monday to Friday."
            )
        }
        state.intradayRows.isEmpty() && (state.intradayLoading || state.intradayRefreshing) -> item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AurumColors.gold)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Scanning the open session…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        state.intradayRows.isEmpty() -> item {
            EmptyState(
                title = "No qualified names right now",
                message = "Nothing currently clears all four gates: trading above its open, " +
                    "volume at or above its normal pace, a bullish technique board, and your " +
                    "target still historically reachable from the current price. Rescan in a " +
                    "little while — the picture changes through the session.",
                actionLabel = "Rescan",
                onAction = vm::refreshIntraday
            )
        }
        else -> {
            items(state.intradayRows, key = { "id-${it.symbol}" }) { pick ->
                IntradayCard(
                    pick = pick,
                    onOpen = { onOpenDetail(pick.symbol) },
                    onAnalyze = { onOpenAnalysis(pick.symbol) }
                )
            }
            item { IntradayMethodCard(targetPct = state.targetPct) }
        }
    }
}

/**
 * States plainly which prints are on screen. During the pre-market window it
 * counts down to the open; outside it, it says the figures describe the last
 * session rather than letting them read as live.
 */
private fun sessionLine(state: PreMarketState): String = when (state.session) {
    Dates.MarketSession.PRE -> {
        val mins = state.minutesToOpen
        val countdown = when {
            mins <= 0L -> "opening now"
            mins < 60L -> "opens in ${mins}m"
            else -> "opens in ${mins / 60}h ${mins % 60}m"
        }
        if (state.livePreMarket) "Live pre-market · $countdown"
        else "Pre-market open · no prints yet · $countdown"
    }
    Dates.MarketSession.REGULAR ->
        if (state.livePreMarket) "Market open · pre-market prints from this morning"
        else "Market open · showing today's session"
    Dates.MarketSession.POST ->
        "After hours · pre-market reopens ${Dates.etAsAmman(4)} Amman time"
    Dates.MarketSession.OVERNIGHT -> "Market closed · showing the last session"
    Dates.MarketSession.WEEKEND -> "Weekend · showing Friday's session"
}

/** The goal itself: editable, and the headline count of names that clear it. */
@Composable
private fun TargetCard(state: PreMarketState, tab: DeskTab, onEdit: () -> Unit) {
    val rowsShown = when (tab) {
        DeskTab.PRE -> state.rows.size
        DeskTab.OPEN -> state.intradayRows.size
    }
    val reliable = when (tab) {
        DeskTab.PRE -> state.rows.count {
            it.odds == TargetOdds.RELIABLE || it.odds == TargetOdds.FREQUENT
        }
        DeskTab.OPEN -> state.intradayRows.count { it.hitRatePct >= 55.0 }
    }
    val asOf = when (tab) {
        DeskTab.PRE -> state.asOf
        DeskTab.OPEN -> state.intradayAsOf
    }
    AurumCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Daily profit target",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = Fmt.pct(state.targetPct),
                    style = MaterialTheme.typography.displaySmall,
                    color = AurumColors.gold,
                    maxLines = 1
                )
            }
            // Capped so the count can never squeeze the goal beside it.
            if (rowsShown > 0) {
                Spacer(Modifier.width(12.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.widthIn(max = 132.dp)
                ) {
                    Text(
                        text = "$reliable of $rowsShown",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (reliable > 0) AurumColors.gain else AurumColors.loss,
                        maxLines = 1
                    )
                    Text(
                        text = when (tab) {
                            DeskTab.PRE -> "reach it most days"
                            DeskTab.OPEN -> "reach it from here most days"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
        // Freshness and the edit hint get their own line rather than competing
        // for width with the goal.
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (asOf > 0L) "Prices ${Fmt.timeAgo(asOf)} · tap to change the target"
            else "Tap to change the target",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

/**
 * The open-market subtitle: live during the regular session, honest about
 * staleness outside it.
 */
private fun intradaySessionLine(state: PreMarketState): String = when (state.session) {
    Dates.MarketSession.REGULAR -> "Live session scan · what can still add your target today"
    Dates.MarketSession.PRE -> {
        val mins = state.minutesToOpen
        val countdown = when {
            mins <= 0L -> "opening now"
            mins < 60L -> "scan starts in ${mins}m"
            else -> "scan starts in ${mins / 60}h ${mins % 60}m"
        }
        "Runs once the market opens · $countdown"
    }
    Dates.MarketSession.POST ->
        "Session closed · scan resumes ${Dates.etAsAmman(9, 30)} Amman time"
    Dates.MarketSession.OVERNIGHT ->
        "Market closed · scan runs ${Dates.etAsAmman(9, 30)}–${Dates.etAsAmman(16)} Amman time"
    Dates.MarketSession.WEEKEND ->
        "Weekend · scan returns Monday ${Dates.etAsAmman(9, 30)} Amman time"
}

/** Shown when a stored scan is displayed after the session it read has closed. */
@Composable
private fun StaleScanNotice(asOf: Long) {
    AurumCard {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "!  ",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.gold
            )
            Text(
                text = "The market is closed — this is the last scan of the open session" +
                    (if (asOf > 0L) ", read ${Fmt.timeAgo(asOf)}" else "") +
                    ". Prices have moved on; treat it as a record, not a live signal.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

/** Shown when the pre-market session has no prints, so nothing reads as live. */
@Composable
private fun LastSessionNotice() {
    AurumCard {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "!  ",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.gold
            )
            Text(
                text = "The pre-market session has no trades right now, so these are ranked " +
                    "on the last regular session. Pre-market volume starts building from " +
                    "${Dates.etAsAmman(4)} Amman time — reopen or pull to refresh then " +
                    "for live prints.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

/** One mover, with the evidence for and against today's goal. */
@Composable
private fun PreMarketCard(pick: PreMarketPick, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    val (oddsLabel, oddsColor) = when (pick.odds) {
        TargetOdds.RELIABLE -> "Reaches it most days" to AurumColors.gain
        TargetOdds.FREQUENT -> "Reaches it often" to AurumColors.gain
        TargetOdds.COINFLIP -> "Roughly even odds" to AurumColors.gold
        TargetOdds.RARE -> "Rarely reaches it" to AurumColors.loss
    }
    // Captions must match the live session: during the regular session the
    // price on the card is the live regular print, not a pre-market one.
    val session = Dates.marketSessionNow()
    val (pctCaption, priceCaption) = when {
        !pick.livePreMarket -> "Last session" to "last close"
        session == Dates.MarketSession.PRE -> "Pre-market" to "pre-market"
        session == Dates.MarketSession.REGULAR -> "Live" to "live"
        else -> "Last session" to "last close"
    }

    AurumCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        PickHeader(
            rank = pick.rank,
            symbol = pick.symbol,
            name = pick.name,
            moveCaption = pctCaption,
            movePct = pick.preMarketPct,
            price = pick.price,
            priceCaption = priceCaption,
            referenceLabel = "prev",
            referencePrice = pick.prevClose
        )

        // The plan: buy here, sell there, stop below.
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile(
                label = "Buy near",
                value = Fmt.money(pick.suggestedEntry),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            StatTile(
                label = "Sell at ${Fmt.pct(pick.targetPct)}",
                value = Fmt.money(pick.targetPrice),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.gain,
                maxLines = 1
            )
            StatTile(
                label = "Stop",
                value = Fmt.money(pick.stop),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.loss,
                maxLines = 1
            )
        }
        // A buyer here needs the day to stretch further than the target alone.
        if (pick.neededPct > pick.targetPct + 0.05) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = String.format(
                    java.util.Locale.US,
                    "Needs +%.1f%% from the open to pay +%.1f%% from here.",
                    pick.neededPct, pick.targetPct
                ),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }

        // Timing, from where the low and high actually printed — Amman time.
        if (pick.buyWindow.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Column {
                TimingLine(
                    label = "Best time to buy",
                    value = pick.buyWindow,
                    color = AurumColors.gold
                )
                Spacer(Modifier.height(4.dp))
                TimingLine(
                    label = "High usually prints",
                    value = pick.sellWindow,
                    color = AurumColors.gain
                )
                if (pick.timingNote.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = pick.timingNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.text
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Amman time, from where the low and high landed over the " +
                        "last ${pick.timingSessions} sessions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }

        // The odds of the target, stated plainly.
        Spacer(Modifier.height(14.dp))
        OddsBlock(
            oddsLabel = oddsLabel,
            oddsColor = oddsColor,
            sectorLabel = if (pick.sectorNote.isNotBlank()) pick.sectorLabel else "",
            sectorLeading = pick.sectorNote.contains("#1"),
            hitRatePct = pick.hitRatePct
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (pick.sectorNote.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${pick.sectorNote}.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        if (pick.headline.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            NewsLine(
                headline = pick.headline,
                source = pick.headlineSource,
                sentiment = pick.headlineSentiment
            )
        }
        if (pick.caution.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    text = "!  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.loss
                )
                Text(
                    text = pick.caution,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.loss
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 40.dp).clickable { onAnalyze() }
        ) {
            Icon(
                Icons.Rounded.QueryStats,
                contentDescription = null,
                tint = AurumColors.gold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Full analysis",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gold
            )
        }
    }
}

/** One open-session candidate, with the evidence for and against the target. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntradayCard(pick: IntradayPick, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    val oddsColor = when {
        pick.hitRatePct >= 55.0 -> AurumColors.gain
        pick.hitRatePct >= 40.0 -> AurumColors.gold
        else -> AurumColors.loss
    }

    AurumCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        PickHeader(
            rank = pick.rank,
            symbol = pick.symbol,
            name = pick.name,
            moveCaption = "Off the open",
            movePct = pick.sinceOpenPct,
            price = pick.price,
            priceCaption = "now",
            referenceLabel = "open",
            referencePrice = pick.openPrice
        )

        // Live confirmation: volume pace, the technique board, and either the
        // trending theme or the last half hour. Wraps rather than crushing a
        // pill when the labels run long.
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillTag(
                text = String.format(java.util.Locale.US, "%.1fx volume", pick.relativeVolume),
                color = AurumColors.info,
                maxLines = 1
            )
            PillTag(
                text = "${pick.techBullish}/${pick.techTotal} bullish",
                color = AurumColors.gain,
                maxLines = 1
            )
            if (pick.sectorNote.isNotBlank()) {
                PillTag(
                    text = pick.sectorLabel,
                    color = if (pick.sectorNote.contains("#1")) AurumColors.gold
                    else AurumColors.info,
                    maxLines = 1
                )
            } else if (pick.momentum30Pct > 0.0) {
                PillTag(
                    text = String.format(
                        java.util.Locale.US, "+%.1f%% last 30m", pick.momentum30Pct
                    ),
                    color = AurumColors.gain,
                    maxLines = 1
                )
            }
        }

        // The plan: buy here, sell there, stop below.
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile(
                label = "Buy near",
                value = Fmt.money(pick.price),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            StatTile(
                label = "Sell at ${Fmt.pct(pick.targetPct)}",
                value = Fmt.money(pick.targetPrice),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.gain,
                maxLines = 1
            )
            StatTile(
                label = "Stop",
                value = Fmt.money(pick.stop),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.loss,
                maxLines = 1
            )
        }

        // The odds of the remaining move, stated plainly.
        Spacer(Modifier.height(14.dp))
        OddsBlock(
            oddsLabel = when {
                pick.hitRatePct >= 70.0 -> "Reaches it most days"
                pick.hitRatePct >= 55.0 -> "Reaches it often"
                pick.hitRatePct >= 40.0 -> "Roughly even odds"
                else -> "Needs an unusual day"
            },
            oddsColor = oddsColor,
            sectorLabel = "",
            sectorLeading = false,
            hitRatePct = pick.hitRatePct
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (pick.sectorNote.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${pick.sectorNote}.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        if (pick.headline.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            NewsLine(
                headline = pick.headline,
                source = pick.headlineSource,
                sentiment = pick.headlineSentiment
            )
        }
        if (pick.caution.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    text = "!  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.loss
                )
                Text(
                    text = pick.caution,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.loss
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 40.dp).clickable { onAnalyze() }
        ) {
            Icon(
                Icons.Rounded.QueryStats,
                contentDescription = null,
                tint = AurumColors.gold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Full analysis",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gold
            )
        }
    }
}

/** What the open-session numbers mean, and the gates every name had to pass. */
@Composable
private fun IntradayMethodCard(targetPct: Double) {
    AurumCard {
        Text(
            text = "How this scan qualifies a stock",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "Trading above its open — a stock falling on the session is never proposed " +
                "for a further gain.",
            "Volume at or above its normal pace: today's traded volume, scaled by how much " +
                "of the session has elapsed, against the 3-month average.",
            "The 15-technique board must read BULLISH — a hot screener line with a bearish " +
                "board is dropped.",
            "The +${Fmt.pct(targetPct)} from the current price is tested against history: " +
                "how often the day's high ultimately stretched far enough above the open to " +
                "cover it. That hit rate leads the ranking.",
            "News tone over the last five days feeds the score; a negative tone is flagged " +
                "on the card.",
            "Names in this week's top three trending themes get a small ranking boost.",
            "No stock can be guaranteed to deliver a set profit on a given day. These are " +
                "measured frequencies from past sessions; size positions and honor stops " +
                "accordingly."
        ).forEach { line ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

/**
 * Shared card header: rank, name, the session move, and the price block.
 * Every text is bounded and the middle column is the only elastic one, so a
 * long company name or a four-digit price can never push a neighbour out of
 * the card or land on top of it.
 */
@Composable
private fun PickHeader(
    rank: Int,
    symbol: String,
    name: String,
    moveCaption: String,
    movePct: Double,
    price: Double,
    priceCaption: String,
    referenceLabel: String,
    referencePrice: Double
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = rank.toString().padStart(2, '0'),
            style = MaterialTheme.typography.headlineMedium,
            color = AurumColors.gold,
            maxLines = 1
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text,
                    maxLines = 1
                )
                if (name.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The figure keeps its natural width; the caption is the side
                // that gives way, so the percentage can never be squeezed into
                // one character per line on a narrow screen or a large font.
                Text(
                    text = moveCaption,
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(5.dp))
                DeltaPct(value = movePct, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 116.dp)
        ) {
            Text(
                text = Fmt.money(price),
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = priceCaption,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$referenceLabel ${Fmt.money(referencePrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The odds verdict: wrapping pills, then the measured hit rate on its own
 * line beside the bar. The pills wrap instead of competing with the figure
 * for the same row, which is what used to crush them on narrow screens. The
 * caption stays short because the sample size is already spelled out in the
 * reason line below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OddsBlock(
    oddsLabel: String,
    oddsColor: androidx.compose.ui.graphics.Color,
    sectorLabel: String,
    sectorLeading: Boolean,
    hitRatePct: Double
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillTag(text = oddsLabel, color = oddsColor, maxLines = 1)
            if (sectorLabel.isNotBlank()) {
                PillTag(
                    text = sectorLabel,
                    color = if (sectorLeading) AurumColors.gold else AurumColors.info,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreBar(score = hitRatePct, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(
                text = String.format(java.util.Locale.US, "%.0f%% hit rate", hitRatePct),
                style = MaterialTheme.typography.labelMedium,
                color = oddsColor,
                maxLines = 1
            )
        }
    }
}

/**
 * A recent headline, given a full-width line to read on instead of being
 * squeezed into the right half of a label row and truncated mid-word.
 */
@Composable
private fun NewsLine(headline: String, source: String, sentiment: Int) {
    val tone = when {
        sentiment > 0 -> AurumColors.gain
        sentiment < 0 -> AurumColors.loss
        else -> AurumColors.textDim
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "News (5d)",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                maxLines = 1
            )
            if (source.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "· $source",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Label on the left, value right-aligned in whatever space is left. The value
 * is the elastic side so a long window description wraps inside the card
 * rather than running under the label.
 */
@Composable
private fun TimingLine(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim,
            maxLines = 1
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** What the numbers mean, and what they deliberately do not promise. */
@Composable
private fun MethodCard(targetPct: Double) {
    AurumCard {
        Text(
            text = "How the odds are measured",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "Hit rate is entry-adjusted: it counts the sessions whose open-to-high reach " +
                "covered the move a buyer at the suggested entry needs for " +
                "${Fmt.pct(targetPct)} — not just the target from the open.",
            "On gap-up mornings the odds are measured on gap-up days only — the sessions " +
                "that look like today.",
            "The scan covers the 44 most liquid names from the market-wide screens.",
            "Median day shows what an ordinary session actually delivers. When it sits " +
                "below your target, the target needs a better-than-usual day.",
            "ATR is the volatility budget: how much room the name normally has to move.",
            "News tone over the last five days feeds the score; a negative tone is flagged " +
                "on the card.",
            "Names in this week's top three trending themes get a small ranking boost.",
            "Buy and sell windows come from the clock times where the low and high really " +
                "printed over recent sessions — a tendency, not a schedule.",
            "No stock can be guaranteed to deliver a set profit on a given day. These are " +
                "measured frequencies from past sessions; size positions and honor stops " +
                "accordingly."
        ).forEach { line ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

/** Enter any daily profit goal; the whole list re-ranks against it. */
@Composable
private fun TargetDialog(current: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(Fmt.trimNumber(current)) }
    val pct = text.replace(",", "").trim().toDoubleOrNull()
    val valid = pct != null && pct > 0.0 && pct <= 50.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Daily profit target") },
        text = {
            Column {
                Text(
                    text = "Every stock below is measured against this number: how often it " +
                        "has actually delivered it, and the price that reaches it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Target per day (%)") },
                    placeholder = { Text("2.06") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AurumColors.text,
                        unfocusedTextColor = AurumColors.text,
                        focusedBorderColor = AurumColors.gold,
                        unfocusedBorderColor = AurumColors.hairline,
                        focusedLabelColor = AurumColors.gold,
                        unfocusedLabelColor = AurumColors.textDim,
                        cursorColor = AurumColors.gold
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (pct != null && pct > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "On $1,000 that is ${Fmt.money(1000.0 * pct / 100.0)} a day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (valid) onSave(pct!!) },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.gold,
                    contentColor = AurumColors.bg,
                    disabledContainerColor = AurumColors.surfaceHigh,
                    disabledContentColor = AurumColors.textDim
                )
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AurumColors.textDim) }
        }
    )
}
