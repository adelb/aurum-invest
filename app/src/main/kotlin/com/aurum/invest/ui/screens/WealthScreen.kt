package com.aurum.invest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.AllocationLine
import com.aurum.invest.analytics.AllocationSlice
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.FlowVerdict
import com.aurum.invest.analytics.GapStatus
import com.aurum.invest.analytics.HoldingAction
import com.aurum.invest.analytics.HoldingVerdict
import com.aurum.invest.analytics.MarketCall
import com.aurum.invest.analytics.MarketMover
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.MoneyFlowReport
import com.aurum.invest.analytics.NextSessionPick
import com.aurum.invest.analytics.NextSessionReport
import com.aurum.invest.analytics.NextWeekPlan
import com.aurum.invest.analytics.NextWeekSector
import com.aurum.invest.analytics.NextWeekStock
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.PickNote
import com.aurum.invest.analytics.GradeAction
import com.aurum.invest.analytics.GradeActionKind
import com.aurum.invest.analytics.PortfolioGrade
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.PortfolioReview
import com.aurum.invest.analytics.RebalanceMove
import com.aurum.invest.analytics.SectorFlow
import com.aurum.invest.analytics.UnverifiedHolding
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.AlertPermissionCard
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.ScoreBar
import com.aurum.invest.ui.components.SentimentDot
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale

@Composable
fun WealthScreen(onOpenAnalysis: (String) -> Unit, onOpenDetail: (String) -> Unit) {
    val vm: WealthViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

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
                    text = "Wealth",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text
                )
                Text(
                    text = state.review?.let { "Portfolio review · updated ${Fmt.timeAgo(it.computedAt)}" }
                        ?: "Portfolio intelligence",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
            if (state.refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AurumColors.gold,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = vm::refresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Re-run every engine",
                        tint = AurumColors.gold
                    )
                }
            }
        }

        AurumRefreshBox(
            refreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            WealthContent(
                state = state,
                onOpenAnalysis = onOpenAnalysis,
                onOpenDetail = onOpenDetail
            )
        }
    }
}

// ---------------------------------------------------------------- content

@Composable
private fun WealthContent(
    state: WealthState,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    // Which sections the user has folded away. The review and the next-session
    // engine open by default — the rest starts folded so a first look reads in
    // seconds, not scrolls.
    var collapsed by rememberSaveable {
        mutableStateOf(HashSet(setOf("pulse", "flow", "book", "gaps", "weekmoney", "movers")))
    }
    fun open(key: String) = key !in collapsed
    fun toggle(key: String) {
        collapsed = HashSet(collapsed).apply { if (!add(key)) remove(key) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1 — the portfolio review: hold / sell / cut, allocation, rebalance.
        item {
            WealthSectionHeader(
                title = "Your portfolio — the verdicts",
                expanded = open("review"),
                trailing = state.review?.let { r ->
                    val actions = r.verdicts.count { it.action != HoldingAction.HOLD }
                    if (actions == 0) "all clear" else "$actions to act on"
                }
            ) { toggle("review") }
        }
        if (open("review")) {
            when {
                state.bookLoaded && !state.hasPositions -> {
                    item { ReviewEmptyCard() }
                }
                state.review == null && state.reviewLoading -> {
                    item { LoadingCard("Reading every holding through the 35-technique board…") }
                }
                state.review == null -> {
                    item {
                        AurumCard {
                            Text(
                                text = "The engine could not verify a single holding from live " +
                                    "market data — most likely a network problem. Pull down to " +
                                    "retry; nothing is guessed in the meantime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                    }
                }
                else -> {
                    val review = state.review
                    item { ReviewSummaryCard(review) }
                    review.grade?.let { grade ->
                        item { PortfolioGradeCard(grade, onOpenDetail) }
                    }
                    items(review.verdicts.size) { i ->
                        HoldingCard(
                            verdict = review.verdicts[i],
                            onOpenAnalysis = onOpenAnalysis,
                            onOpenDetail = onOpenDetail
                        )
                    }
                    if (review.unverified.isNotEmpty()) {
                        item { UnverifiedHoldingsCard(review.unverified, onOpenDetail) }
                    }
                    item { AllocationPlanCard(review) }
                    if (review.rebalance.isNotEmpty()) {
                        item { RebalanceCard(review.rebalance, onOpenAnalysis, onOpenDetail) }
                    }
                }
            }
        }

        // 2 — the next-session engine.
        item {
            WealthSectionHeader(
                title = "Positioned for the next session",
                expanded = open("nextsession"),
                trailing = state.nextSession?.let { ns ->
                    if (ns.alerts.isNotEmpty()) "${ns.alerts.size} extreme" else "${ns.picks.size} picks"
                }
            ) { toggle("nextsession") }
        }
        if (open("nextsession")) {
            item {
                AlertPermissionCard(
                    enabledText = "Extreme next-session alerts are on — checked after the close and pre-open.",
                    title = "Get the extreme setup alert",
                    message = "Allow notifications and Aurum will alert you when a stock clears " +
                        "every score, analog-history, and 35-technique confidence gate."
                )
            }
            val ns = state.nextSession
            when {
                ns == null && state.nextSessionLoading -> {
                    item { LoadingCard("Scanning the whole market and replaying every analog day…") }
                }
                ns == null -> {
                    item {
                        AurumCard {
                            Text(
                                text = "The next-session scan needs market data. Pull down to retry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                    }
                }
                else -> {
                    item { NextSessionHeaderCard(ns) }
                    items(ns.picks.size) { i ->
                        NextSessionCard(
                            rank = i + 1,
                            pick = ns.picks[i],
                            onOpenAnalysis = onOpenAnalysis,
                            onOpenDetail = onOpenDetail
                        )
                    }
                    item { NextSessionFooterCard(ns) }
                }
            }
        }

        // 3 — the next-week preview, live Thursday through Monday.
        item {
            WealthSectionHeader(
                title = "Next week — stocks & sectors to watch",
                expanded = open("nextweek"),
                trailing = state.preview?.let { Dates.weekStartLabel(it.weekStart) }
                    ?: if (state.previewWindowActive) "building…" else "returns Thursday"
            ) { toggle("nextweek") }
        }
        if (open("nextweek")) {
            val preview = state.preview
            when {
                preview != null -> {
                    item { NextWeekHeadlineCard(preview) }
                    item { NextWeekSectorsCard(preview.sectors) }
                    items(preview.stocks.size) { i ->
                        NextWeekStockCard(
                            stock = preview.stocks[i],
                            onOpenAnalysis = onOpenAnalysis,
                            onOpenDetail = onOpenDetail
                        )
                    }
                    item { NextWeekFooterCard(preview) }
                }
                state.previewLoading -> {
                    item { LoadingCard("Reading the whole market, flows, news, and the latest prints…") }
                }
                else -> {
                    item {
                        AurumCard {
                            Text(
                                text = "The next-week preview builds Thursday through Monday, once " +
                                    "the week has shown its hand. Come back Thursday.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                    }
                }
            }
        }

        // 4 — market context, folded by default.
        item {
            WealthSectionHeader(
                title = "Market pulse",
                expanded = open("pulse"),
                trailing = state.pulse?.let { "${it.score}/100" }
            ) { toggle("pulse") }
        }
        if (open("pulse")) {
            item { MarketPulseCard(pulse = state.pulse, loading = state.pulseLoading) }
        }

        // 5 — this week's money flow, the standalone engine.
        item {
            WealthSectionHeader(
                title = "Where the money is moving",
                expanded = open("flow"),
                trailing = state.flow?.inflows?.firstOrNull()?.label
            ) { toggle("flow") }
        }
        if (open("flow")) {
            item { MoneyFlowCard(flow = state.flow, loading = state.flowLoading) }
        }

        // 6 — the user's book, folded by default.
        if (!state.book.isEmpty) {
            item {
                WealthSectionHeader(
                    title = "Your book",
                    expanded = open("book"),
                    trailing = Fmt.money(state.book.totalValue)
                ) { toggle("book") }
            }
            if (open("book")) {
                item { BookCard(book = state.book, flow = state.flow) }
            }
        }

        // 7 — sector gaps + the theme lens.
        if (state.strategy != null || state.strategyLoading) {
            item {
                WealthSectionHeader(
                    title = "Sector gaps in your portfolio",
                    expanded = open("gaps")
                ) { toggle("gaps") }
            }
            if (open("gaps")) {
                item { SectorGapsCard(strategy = state.strategy, loading = state.strategyLoading) }
            }
            val strategy = state.strategy
            if (strategy != null && strategy.allocations.isNotEmpty()) {
                item {
                    WealthSectionHeader(
                        title = "Where to add, by theme",
                        expanded = open("weekmoney")
                    ) { toggle("weekmoney") }
                }
                if (open("weekmoney")) {
                    item {
                        Text(
                            text = "How new money would split across the themes the flow engine " +
                                "backs, as percentages — you decide the dollars.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    items(strategy.allocations.size) { i ->
                        AllocationRow(
                            slice = strategy.allocations[i],
                            index = i,
                            onOpen = { sym -> onOpenDetail(sym) },
                            onAnalyze = { sym -> onOpenAnalysis(sym) }
                        )
                    }
                    if (strategy.notes.isNotEmpty()) {
                        item { StrategyNotesCard(strategy) }
                    }
                }
            }
        }

        // 8 — session extras, folded by default.
        val pulse = state.pulse
        if (pulse != null && pulse.call != MarketCall.DEFENSIVE && pulse.bestYesterday.isNotEmpty()) {
            item {
                WealthSectionHeader(
                    title = "Last session's best performers",
                    expanded = open("movers")
                ) { toggle("movers") }
            }
            if (open("movers")) {
                items(pulse.bestYesterday.size) { i ->
                    val mover = pulse.bestYesterday[i]
                    MoverRow(
                        rank = i + 1,
                        mover = mover,
                        note = PortfolioLens.pickNote(mover.symbol, null, state.book),
                        onOpen = { onOpenDetail(mover.symbol) }
                    )
                }
            }
        }

        item {
            Text(
                text = "Every number on this screen is computed from live market data, the " +
                    "35-technique board, and measured money flows — decision support, not " +
                    "financial advice.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Section header with a fold toggle — tapping it tucks the whole section
 * away, so the Wealth tab shows only the parts the user wants open.
 */
@Composable
private fun WealthSectionHeader(
    title: String,
    expanded: Boolean,
    trailing: String? = null,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = AurumColors.textDim,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LoadingCard(message: String) {
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AurumColors.gold,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

// ---------------------------------------------------------------- review

@Composable
private fun ReviewEmptyCard() {
    AurumCard {
        Text(
            text = "No positions yet",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Add your first trade with the gold + button and the engine will read " +
                "every holding through the 35-technique board: what to hold and why, what " +
                "to sell and when, where to cut, and how the money should be allocated.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun ReviewSummaryCard(review: PortfolioReview) {
    AurumCard {
        Text(
            text = review.headline,
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text
        )
        if (review.marketNote.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = review.marketNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Book value",
                value = Fmt.money(review.totalValue),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = if (review.unverified.isEmpty()) "Holdings" else "Verified",
                value = if (review.unverified.isEmpty()) "${review.verdicts.size}"
                else "${review.verdicts.size} of ${review.verdicts.size + review.unverified.size}",
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Plan frees up",
                value = Fmt.pct(review.suggestedCashPct),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.gold
            )
        }
        if (review.sectorNotes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            review.sectorNotes.forEach { note ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "•  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.gold
                    )
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioGradeCard(grade: PortfolioGrade, onOpenDetail: (String) -> Unit) {
    // Which discipline's action dropdown is open; one at a time.
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Portfolio grade",
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = "Your book against the published rules of elite investors — " +
                        "Buffett, O'Neil, Livermore, Weinstein, Minervini.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${grade.score}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.gold
                )
                Text(
                    text = if (grade.maxScore == 100) "of 100" else "of ${grade.maxScore} measured",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (grade.maxScore > 0) {
            ScoreBar(score = grade.score * 100.0 / grade.maxScore)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = grade.band,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.gold
        )
        Spacer(Modifier.height(10.dp))
        grade.components.forEach { c ->
            val expandable = c.actions.isNotEmpty() || c.principle.isNotEmpty()
            val expanded = expandedKey == c.key
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = expandable) {
                        expandedKey = if (expanded) null else c.key
                    }
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = c.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurumColors.text
                        )
                        if (c.actions.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            PillTag(
                                text = "${c.actions.size} fix${if (c.actions.size > 1) "es" else ""}",
                                color = AurumColors.gold
                            )
                        }
                    }
                    Text(
                        text = c.evidence,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (c.measured) {
                    Text(
                        text = "${c.points}/${c.maxPoints}",
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            c.green -> AurumColors.gain
                            c.points * 100 >= c.maxPoints * 50 -> AurumColors.text
                            else -> AurumColors.loss
                        }
                    )
                } else {
                    PillTag(text = "not measured", color = AurumColors.textDim)
                }
                if (expandable) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess
                        else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Show how to improve",
                        tint = AurumColors.textDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (expanded) {
                Column(modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                    if (c.principle.isNotEmpty()) {
                        Text(
                            text = c.principle,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim
                        )
                    }
                    if (c.green && c.actions.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "In the green — nothing to fix here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.gain
                        )
                    }
                    c.actions.forEach { action ->
                        Spacer(Modifier.height(8.dp))
                        GradeActionRow(action, onOpenDetail)
                    }
                }
            }
        }
        HorizontalDivider(
            color = AurumColors.hairline,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Text(
            text = "Do next: ${grade.suggestion}",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.gold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "A comparison against these investors' published rules — their live holdings " +
                "are not public in real time, and Aurum does not pretend otherwise. " +
                "Points-after figures are today's arithmetic, not predictions.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun GradeActionRow(action: GradeAction, onOpenDetail: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                onOpenDetail(action.buySymbol.ifEmpty { action.symbol })
            }
            .padding(vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (action.kind) {
                GradeActionKind.SELL -> PillTag(text = "Sell", color = AurumColors.loss)
                GradeActionKind.TRIM -> PillTag(text = "Trim", color = AurumColors.info)
                GradeActionKind.ROTATE -> PillTag(text = "Rotate", color = AurumColors.gold)
                GradeActionKind.BUY -> PillTag(text = "Buy", color = AurumColors.gain)
                GradeActionKind.REVIEW -> PillTag(text = "Review", color = AurumColors.textDim)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = action.title,
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${action.pointsNow} → ${action.pointsAfter}/${action.maxPoints}",
                style = MaterialTheme.typography.titleSmall,
                color = if (action.pointsAfter > action.pointsNow) AurumColors.gain
                else AurumColors.textDim
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = action.detail,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (action.buySymbol.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillTag(text = "Buy ${action.buySymbol}", color = AurumColors.gain)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = action.buyName +
                        if (action.buyEntry > 0.0) " · entry ≈ ${Fmt.money(action.buyEntry)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun ActionPill(action: HoldingAction) {
    when (action) {
        HoldingAction.HOLD -> PillTag(text = "Hold", color = AurumColors.gain)
        HoldingAction.TAKE_PROFIT -> PillTag(text = "Take profit", color = AurumColors.gold)
        HoldingAction.TRIM -> PillTag(text = "Trim", color = AurumColors.info)
        HoldingAction.SELL -> PillTag(text = "Sell", color = AurumColors.loss)
        HoldingAction.CUT_LOSS -> PillTag(text = "Cut loss", color = AurumColors.loss)
    }
}

@Composable
private fun HoldingCard(
    verdict: HoldingVerdict,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val urgent = verdict.action == HoldingAction.CUT_LOSS || verdict.action == HoldingAction.SELL
    val cardModifier =
        if (urgent) Modifier.border(1.5.dp, AurumColors.loss, RoundedCornerShape(16.dp))
        else Modifier
    AurumCard(modifier = cardModifier, onClick = { onOpenDetail(verdict.symbol) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = verdict.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = String.format(Locale.US, "%.0f%% of book", verdict.weightPct),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                if (verdict.sector != PortfolioLens.UNCLASSIFIED) {
                    Text(
                        text = verdict.sector,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                DeltaPct(
                    value = verdict.unrealizedPlPct,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = Fmt.signedMoney(verdict.unrealizedPl),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.deltaColor(verdict.unrealizedPl)
                )
                verdict.sessionMovePct?.let { move ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeltaPct(
                            value = move,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = " session",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            ActionPill(verdict.action)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = verdict.headline,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "When: ${verdict.whenText}",
            style = MaterialTheme.typography.bodySmall,
            color = if (urgent) AurumColors.loss else AurumColors.gold
        )
        Spacer(Modifier.height(8.dp))
        verdict.whyPoints.forEach { why ->
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold
                )
                Text(
                    text = why,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Price",
                value = Fmt.money(verdict.price),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Take profit",
                value = Fmt.money(verdict.target),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.gain
            )
            StatTile(
                label = "Exit below",
                value = Fmt.money(verdict.stop),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.loss
            )
        }
        if (verdict.newsNote.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                SentimentDot(sentiment = verdict.newsScore)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = verdict.newsNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clickable { onOpenAnalysis(verdict.symbol) }
        ) {
            Icon(
                imageVector = Icons.Rounded.QueryStats,
                contentDescription = null,
                tint = AurumColors.gold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Full 35-technique analysis",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gold
            )
        }
    }
}

@Composable
private fun UnverifiedHoldingsCard(
    unverified: List<UnverifiedHolding>,
    onOpenDetail: (String) -> Unit
) {
    AurumCard {
        Text(
            text = "Not verified this run",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "These holdings could not be measured from live market data, so they are " +
                "excluded from the weights and the allocation plan — nothing about them is " +
                "guessed. Pull down to retry.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        unverified.forEach { u ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenDetail(u.symbol) }
            ) {
                Text(
                    text = u.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    modifier = Modifier.width(64.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = String.format(Locale.US, "%s shares", Fmt.qty(u.shares)),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                    Text(
                        text = u.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

@Composable
private fun AllocationPlanCard(review: PortfolioReview) {
    AurumCard {
        Text(
            text = "Allocation plan",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Where each position sits against where the engine would size it.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        review.allocation.forEach { line ->
            AllocationPlanRow(line)
        }
        HorizontalDivider(
            color = AurumColors.hairline,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Freed to cash",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = Fmt.pct(review.suggestedCashPct),
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.gold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Cash is a position too — it buys the next setup the engines flag.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun AllocationPlanRow(line: AllocationLine) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(
            text = line.symbol,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text,
            modifier = Modifier.width(64.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${Fmt.pct(line.currentPct)} → ${Fmt.pct(line.suggestedPct)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (line.suggestedPct < line.currentPct) AurumColors.loss
                else AurumColors.text
            )
            Text(
                text = line.note,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun RebalanceCard(
    moves: List<RebalanceMove>,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    AurumCard {
        Text(
            text = "Sector rebalance",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Your book is over its concentration line — these moves bring it back.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        moves.forEachIndexed { i, move ->
            if (i > 0) {
                HorizontalDivider(
                    color = AurumColors.hairline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillTag(text = "Sell", color = AurumColors.loss)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = move.sellSymbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenDetail(move.sellSymbol) }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "≈ ${Fmt.money(move.sellAmount)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.gold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = move.sellReason,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(8.dp))
            if (move.buySymbol.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillTag(text = "Buy", color = AurumColors.gain)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${move.buySymbol} · ${move.buyName}",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.text,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenDetail(move.buySymbol) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { onOpenAnalysis(move.buySymbol) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Rounded.QueryStats,
                            contentDescription = "Open ${move.buySymbol} analysis",
                            tint = AurumColors.gold,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (move.buySector.isNotEmpty()) {
                    PillTag(text = move.buySector, color = AurumColors.info)
                    Spacer(Modifier.height(4.dp))
                }
            }
            Text(
                text = move.buyReason,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

// ---------------------------------------------------------------- next session

@Composable
private fun NextSessionHeaderCard(ns: NextSessionReport) {
    AurumCard {
        Text(
            text = ns.headline,
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${ns.sessionNote} Updated ${Fmt.timeAgo(ns.computedAt)}.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun NextSessionCard(
    rank: Int,
    pick: NextSessionPick,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val cardModifier =
        if (pick.alert) Modifier.border(1.5.dp, AurumColors.gold, RoundedCornerShape(16.dp))
        else Modifier
    AurumCard(modifier = cardModifier, onClick = { onOpenDetail(pick.symbol) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%02d".format(rank),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AurumColors.gold
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pick.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (pick.alert) {
                        Spacer(Modifier.width(8.dp))
                        PillTag(text = "Every gate cleared", color = AurumColors.gold)
                    }
                }
                Text(
                    text = pick.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(pick.price),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                DeltaPct(value = pick.dayChangePct, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreBar(score = pick.score.toDouble(), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${pick.score}/100",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Entry",
                value = Fmt.money(pick.entry),
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
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pick.probUpPct >= 0) {
                PillTag(
                    text = "${pick.probUpPct}% follow-through · ${pick.analogDays} analogs",
                    color = if (pick.probUpPct >= 65) AurumColors.gain else AurumColors.textDim
                )
            } else {
                PillTag(text = "Follow-through not measurable", color = AurumColors.textDim)
            }
            Spacer(Modifier.width(8.dp))
            if (pick.techTotal > 0) {
                PillTag(
                    text = "${pick.techBullish}/${pick.techTotal} bullish",
                    color = AurumColors.gain
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Next session ",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Text(
                text = Fmt.signedPct(pick.expectedLowPct),
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.loss
            )
            Text(
                text = " … ",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Text(
                text = Fmt.signedPct(pick.expectedHighPct),
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gain
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = String.format(Locale.US, "RSI %.0f", pick.rsi),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (pick.extNote.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = pick.extNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.info
            )
        }
        if (pick.heldNote.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = pick.heldNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.gold
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clickable { onOpenAnalysis(pick.symbol) }
        ) {
            Icon(
                imageVector = Icons.Rounded.QueryStats,
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

@Composable
private fun NextSessionFooterCard(ns: NextSessionReport) {
    AurumCard {
        ns.notes.forEach { note ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "When a name clears every gate, Aurum pushes a notification — once per " +
                "name per day, after the close or pre-open.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.gold
        )
    }
}

// ---------------------------------------------------------------- money flow

@Composable
private fun MoneyFlowCard(flow: MoneyFlowReport?, loading: Boolean) {
    AurumCard {
        if (flow == null) {
            Text(
                text = if (loading) "Measuring dollar flows across every sector…"
                else "Flow data unavailable. Pull down to retry.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.textDim
            )
            return@AurumCard
        }
        Text(
            text = flow.headline,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Updated ${Fmt.timeAgo(flow.computedAt)} · S&P 500 ${Fmt.signedPct(flow.spyR20Pct)} over 20 days",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        flow.sectors.take(8).forEachIndexed { i, s ->
            if (i > 0) {
                HorizontalDivider(
                    color = AurumColors.hairline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            FlowRow(s)
        }
        Spacer(Modifier.height(10.dp))
        flow.notes.forEach { note ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun FlowRow(s: SectorFlow) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AurumColors.text
                )
                Text(
                    text = "Flow ${s.flowScore}/100 · ${s.etf} ${Fmt.signedPct(s.r5Pct)} in 5 days",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            when (s.verdict) {
                FlowVerdict.INFLOW -> PillTag(text = "Money in", color = AurumColors.gain)
                FlowVerdict.OUTFLOW -> PillTag(text = "Money out", color = AurumColors.loss)
                FlowVerdict.NEUTRAL -> PillTag(text = "Balanced", color = AurumColors.textDim)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = s.reason,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------- book

/**
 * The invested book by sector — the same PortfolioLens math the dashboard
 * allocation card and the Picks tags use, so the numbers agree everywhere.
 */
@Composable
private fun BookCard(book: BookContext, flow: MoneyFlowReport?) {
    val lead = flow?.sectors?.firstOrNull()
    val notes = PortfolioLens.exposureNotes(book, lead?.key, lead?.label)
    AurumCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            book.slices.forEachIndexed { i, slice ->
                val weight = (slice.weightPct / 100.0).toFloat()
                if (weight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                AurumColors.allocation[i % AurumColors.allocation.size]
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        book.slices.take(4).forEachIndexed { i, slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AurumColors.allocation[i % AurumColors.allocation.size])
                )
                Text(
                    text = "  ${slice.sector}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.text
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = Fmt.pct(slice.weightPct),
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim
                )
            }
        }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            notes.forEach { note ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "•  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.gold
                    )
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- market pulse

@Composable
private fun MarketPulseCard(pulse: MarketRating?, loading: Boolean) {
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    pulse != null -> "Updated ${Fmt.timeAgo(pulse.computedAt)}"
                    loading -> "Reading the market…"
                    else -> "Market data unreachable"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                modifier = Modifier.weight(1f)
            )
            if (loading && pulse == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AurumColors.gold,
                    strokeWidth = 2.dp
                )
            } else if (pulse != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${pulse.score}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (pulse.call) {
                            MarketCall.INVEST -> AurumColors.gain
                            MarketCall.SELECTIVE -> AurumColors.gold
                            MarketCall.DEFENSIVE -> AurumColors.loss
                        }
                    )
                    Text(
                        text = " /100",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        if (pulse == null) {
            if (!loading) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pull down to try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            return@AurumCard
        }

        Spacer(Modifier.height(10.dp))
        ScoreBar(score = pulse.score.toDouble(), modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (pulse.call) {
                MarketCall.INVEST -> PillTag(text = "Worth investing this week", color = AurumColors.gain)
                MarketCall.SELECTIVE -> PillTag(text = "Selective entries only", color = AurumColors.gold)
                MarketCall.DEFENSIVE -> PillTag(text = "Not this week", color = AurumColors.loss)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = pulse.headline,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = pulse.advice,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.textDim
        )

        if (pulse.reasons.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            pulse.reasons.forEach { reason ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "•  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.gold
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }

        if (pulse.indexes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                pulse.indexes.forEach { ix ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ix.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim
                        )
                        Spacer(Modifier.height(2.dp))
                        DeltaPct(
                            value = ix.r5Pct,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "5 days · ${Fmt.signedPct(ix.vs50Pct)} vs 50d",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Live index, breadth and screener data · decision support, not financial advice",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

// ---------------------------------------------------------------- sector gaps

/**
 * This week's flowing themes measured against the book: what is missing,
 * what is already covered, and the stock that would fill each gap.
 */
@Composable
private fun SectorGapsCard(strategy: WeeklyStrategy?, loading: Boolean) {
    AurumCard {
        if (strategy == null) {
            Text(
                text = if (loading) "Matching this week's themes to your holdings…"
                else "Sector data is unavailable right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.textDim
            )
            return@AurumCard
        }
        Text(
            text = strategy.headline,
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text
        )
        Spacer(Modifier.height(12.dp))
        strategy.gaps.forEachIndexed { index, gap ->
            if (index > 0) {
                HorizontalDivider(
                    color = AurumColors.hairline,
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = gap.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("You hold ${Fmt.pct(gap.heldPct)} · suggested ${Fmt.pct(gap.targetPct)}")
                            if (gap.flowScore >= 0) append(" · flow ${gap.flowScore}/100")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                DeltaPct(value = gap.r5Pct, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(10.dp))
                when (gap.status) {
                    GapStatus.MISSING -> PillTag("Missing", AurumColors.loss)
                    GapStatus.UNDER -> PillTag("Light", AurumColors.gold)
                    GapStatus.COVERED -> PillTag("Covered", AurumColors.gain)
                    GapStatus.OVER -> PillTag("Heavy", AurumColors.info)
                }
            }
            val lead = gap.picks.firstOrNull()
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (lead != null) "${lead.symbol} — ${lead.reason}"
                else "No name in this theme passes the technique board right now.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            if (gap.picks.size > 1) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Also passing: " +
                        gap.picks.drop(1).joinToString(", ") { it.symbol },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            if (gap.coverageFromHoldings) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Spans several sectors — coverage counts only stocks you hold " +
                        "from this theme's list.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

/** One line of the theme lens: theme, share of new money, and the stock to use. */
@Composable
private fun AllocationRow(
    slice: AllocationSlice,
    index: Int,
    onOpen: (String) -> Unit,
    onAnalyze: (String) -> Unit
) {
    val lead = slice.lead
    AurumCard(onClick = { lead?.let { onOpen(it.symbol) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AurumColors.allocation[index % AurumColors.allocation.size])
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = lead?.let { "via ${it.symbol} · ${it.name}" } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.pct(slice.sharePct),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.gold
                )
                Text(
                    text = "of new money",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        if (lead != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = lead.reason,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            if (lead.newsNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    PillTag(text = "Report", color = AurumColors.gold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = lead.newsNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Entry",
                    value = Fmt.money(lead.entry),
                    modifier = Modifier.weight(1f),
                    valueColor = AurumColors.gold
                )
                StatTile(
                    label = "Last price",
                    value = Fmt.money(lead.price),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "You hold",
                    value = Fmt.pct(slice.heldPct),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clickable { onAnalyze(lead.symbol) }
            ) {
                Icon(
                    imageVector = Icons.Rounded.QueryStats,
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
        if (slice.alternates.isNotEmpty()) {
            HorizontalDivider(
                color = AurumColors.hairline,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                text = "Also strong in this theme",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            slice.alternates.forEach { alt ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpen(alt.symbol) }
                        .padding(vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = alt.symbol,
                            style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.text
                        )
                        Text(
                            text = alt.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Entry ${Fmt.money(alt.entry)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                    IconButton(
                        onClick = { onAnalyze(alt.symbol) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Rounded.QueryStats,
                            contentDescription = "Open ${alt.symbol} analysis",
                            tint = AurumColors.textDim,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** How the weekly split was arrived at, and what it deliberately does not claim. */
@Composable
private fun StrategyNotesCard(strategy: WeeklyStrategy) {
    AurumCard {
        Text(
            text = "How this split was built",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(8.dp))
        strategy.notes.forEach { note ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

// ---------------------------------------------------------------- next week

@Composable
private fun NextWeekHeadlineCard(preview: NextWeekPlan) {
    AurumCard {
        Text(
            text = preview.headline,
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text
        )
        if (preview.marketNote.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = preview.marketNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        if (preview.portfolioNote.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = preview.portfolioNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.gold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Built ${preview.builtOn} · previews ${Dates.weekStartLabel(preview.weekStart)} · " +
                "re-ranks until Monday's open",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun NextWeekSectorsCard(sectors: List<NextWeekSector>) {
    if (sectors.isEmpty()) return
    AurumCard {
        Text(
            text = "Sectors to look at",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(8.dp))
        sectors.forEachIndexed { i, s ->
            if (i > 0) {
                HorizontalDivider(color = AurumColors.hairline)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AurumColors.text,
                    modifier = Modifier.weight(1f)
                )
                DeltaPct(value = s.r5Pct, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = s.note,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NextWeekStockCard(
    stock: NextWeekStock,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    AurumCard(onClick = { onOpenDetail(stock.symbol) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stock.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.width(8.dp))
                    if (stock.sectorLabel.isNotEmpty()) {
                        PillTag(text = stock.sectorLabel, color = AurumColors.info)
                    }
                }
                Text(
                    text = stock.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (stock.amount > 0.0) Fmt.money(stock.amount)
                    else Fmt.pct(stock.allocationPct),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.gold
                )
                Text(
                    text = if (stock.amount > 0.0) {
                        String.format(Locale.US, "%.0f%% of the buying power", stock.allocationPct)
                    } else "of next week's money",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(label = "Entry", value = Fmt.money(stock.entry), modifier = Modifier.weight(1f))
            StatTile(
                label = "Target",
                value = Fmt.money(stock.target),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.gain
            )
            StatTile(
                label = "Stop",
                value = Fmt.money(stock.stop),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.loss
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stock.techTotal > 0) {
                PillTag(
                    text = "${stock.techBullish}/${stock.techTotal} bullish",
                    color = AurumColors.gain
                )
                Spacer(Modifier.width(8.dp))
            }
            PillTag(
                text = String.format(Locale.US, "R:R %.1f", stock.rewardRisk),
                color = if (stock.rewardRisk >= 1.5) AurumColors.gain else AurumColors.textDim
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Score ${stock.score}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stock.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (stock.extNote.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stock.extNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.info
            )
        }
        if (stock.newsNote.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SentimentDot(sentiment = stock.newsScore)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stock.newsNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (stock.heldNote.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stock.heldNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.gold
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onOpenAnalysis(stock.symbol) }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
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

@Composable
private fun NextWeekFooterCard(preview: NextWeekPlan) {
    AurumCard {
        if (preview.investable > 0.0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "To deploy",
                    value = Fmt.money(preview.investable - preview.cashLeft),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Stays cash",
                    value = Fmt.money(preview.cashLeft),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        preview.actions.forEach { action ->
            Row {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold
                )
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.text
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = preview.caveat,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

// ---------------------------------------------------------------- movers

/** A pick's portfolio note as a stamped tag; held gold, risk red, fresh blue. */
@Composable
private fun WealthNoteTag(note: PickNote?) {
    if (note == null) return
    Spacer(Modifier.height(8.dp))
    PillTag(
        text = note.text,
        color = when (note.kind) {
            NoteKind.HELD -> AurumColors.gold
            NoteKind.CONCENTRATION -> AurumColors.loss
            NoteKind.DIVERSIFIES -> AurumColors.info
        }
    )
}

@Composable
private fun MoverRow(
    rank: Int,
    mover: MarketMover,
    note: PickNote? = null,
    onOpen: () -> Unit
) {
    AurumCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%02d".format(rank),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AurumColors.gold
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mover.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = mover.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                DeltaPct(
                    value = mover.dayChangePct,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = buildString {
                        append(Fmt.money(mover.price))
                        if (mover.volumeRatio > 0.0) append(" · ${mover.volumeRatio}x vol")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        WealthNoteTag(note)
    }
}
