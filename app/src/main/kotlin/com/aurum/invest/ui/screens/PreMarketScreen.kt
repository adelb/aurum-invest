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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
                    "Rescan closer to the open — pre-market volume builds from 4:00 AM ET.",
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
                    "reachable from the current price. It runs 9:30 AM–4:00 PM ET, " +
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
    Dates.MarketSession.POST -> "After hours · pre-market reopens 4:00 AM ET"
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
                    color = AurumColors.textDim
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = Fmt.pct(state.targetPct),
                    style = MaterialTheme.typography.displaySmall,
                    color = AurumColors.gold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (asOf > 0L) "Prices ${Fmt.timeAgo(asOf)}"
                    else "Tap to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
                if (rowsShown > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "$reliable of $rowsShown",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (reliable > 0) AurumColors.gain else AurumColors.loss
                    )
                    Text(
                        text = when (tab) {
                            DeskTab.PRE -> "reach it most days"
                            DeskTab.OPEN -> "reach it from here most days"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
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
    Dates.MarketSession.POST -> "Session closed · scan resumes 9:30 AM ET"
    Dates.MarketSession.OVERNIGHT -> "Market closed · scan runs 9:30 AM–4:00 PM ET"
    Dates.MarketSession.WEEKEND -> "Weekend · scan returns Monday 9:30 AM ET"
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
                    "4:00 AM ET — reopen or pull to refresh then for live prints.",
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

    AurumCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = pick.rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.gold
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = pick.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (pick.livePreMarket) "Pre-market " else "Last session ",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.textDim
                    )
                    DeltaPct(
                        value = pick.preMarketPct,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = " · prev ${Fmt.money(pick.prevClose)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(pick.price),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = if (pick.livePreMarket) "pre-market" else "last close",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }

        // The plan: buy here, sell there, stop below.
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Buy near",
                value = Fmt.money(pick.suggestedEntry),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Sell at ${Fmt.pct(pick.targetPct)}",
                value = Fmt.money(pick.targetPrice),
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

        // Timing, from where the low and high actually printed.
        if (pick.buyWindowEt.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Column {
                TimingLine(
                    label = "Best time to buy",
                    value = pick.buyWindowEt,
                    color = AurumColors.gold
                )
                Spacer(Modifier.height(4.dp))
                TimingLine(
                    label = "Its high usually prints",
                    value = pick.sellWindowEt,
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
                    text = "From where the low and high landed over the last " +
                        "${pick.timingSessions} sessions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }

        // The odds of the target, stated plainly.
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillTag(text = oddsLabel, color = oddsColor)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${Fmt.pct(pick.hitRatePct)} of ${pick.sessionsAnalyzed} sessions",
                style = MaterialTheme.typography.labelMedium,
                color = oddsColor
            )
        }
        Spacer(Modifier.height(8.dp))
        ScoreBar(score = pick.hitRatePct, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(10.dp))
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
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
@Composable
private fun IntradayCard(pick: IntradayPick, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    val oddsColor = when {
        pick.hitRatePct >= 55.0 -> AurumColors.gain
        pick.hitRatePct >= 40.0 -> AurumColors.gold
        else -> AurumColors.loss
    }

    AurumCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = pick.rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.gold
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = pick.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Off the open ",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.textDim
                    )
                    DeltaPct(
                        value = pick.sinceOpenPct,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = " · open ${Fmt.money(pick.openPrice)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(pick.price),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = "now",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }

        // Live confirmation: volume pace and the technique board.
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillTag(
                text = String.format(java.util.Locale.US, "%.1fx volume", pick.relativeVolume),
                color = AurumColors.info
            )
            Spacer(Modifier.width(8.dp))
            PillTag(
                text = "${pick.techBullish}/${pick.techTotal} bullish",
                color = AurumColors.gain
            )
            if (pick.momentum30Pct > 0.0) {
                Spacer(Modifier.width(8.dp))
                PillTag(
                    text = String.format(
                        java.util.Locale.US, "+%.1f%% last 30m", pick.momentum30Pct
                    ),
                    color = AurumColors.gain
                )
            }
        }

        // The plan: buy here, sell there, stop below.
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Buy near",
                value = Fmt.money(pick.price),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Sell at ${Fmt.pct(pick.targetPct)}",
                value = Fmt.money(pick.targetPrice),
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

        // The odds of the remaining move, stated plainly.
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillTag(
                text = when {
                    pick.hitRatePct >= 70.0 -> "Reaches it most days"
                    pick.hitRatePct >= 55.0 -> "Reaches it often"
                    pick.hitRatePct >= 40.0 -> "Roughly even odds"
                    else -> "Needs an unusual day"
                },
                color = oddsColor
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${Fmt.pct(pick.hitRatePct)} of ${pick.sessionsAnalyzed} sessions",
                style = MaterialTheme.typography.labelMedium,
                color = oddsColor
            )
        }
        Spacer(Modifier.height(8.dp))
        ScoreBar(score = pick.hitRatePct, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(10.dp))
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
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

@Composable
private fun TimingLine(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.labelMedium,
            color = color
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
            "Hit rate counts the sessions where the intraday high rose at least " +
                "${Fmt.pct(targetPct)} above that session's open — the direct historical " +
                "answer to buying at the open and selling at your target.",
            "Median day shows what an ordinary session actually delivers. When it sits " +
                "below your target, the target needs a better-than-usual day.",
            "ATR is the volatility budget: how much room the name normally has to move.",
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
