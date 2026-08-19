package com.aurum.invest.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.fillMaxHeight
import com.aurum.invest.analytics.AllocationSlice
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.ActionKind
import com.aurum.invest.analytics.DeployLine
import com.aurum.invest.analytics.DeploymentPlan
import com.aurum.invest.analytics.HoldingEvaluation
import com.aurum.invest.analytics.HoldingMove
import com.aurum.invest.analytics.ManagementAction
import com.aurum.invest.analytics.RiskStats
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.analytics.WealthDiscipline
import com.aurum.invest.analytics.WealthReport
import com.aurum.invest.analytics.LiquidityPlanner
import com.aurum.invest.analytics.MarketPulse
import com.aurum.invest.analytics.SectorDeployment
import com.aurum.invest.analytics.GapStatus
import com.aurum.invest.analytics.MarketCall
import com.aurum.invest.analytics.MarketMover
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.PickNote
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.TomorrowPick
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.InfoDot
import com.aurum.invest.ui.components.Meanings
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.ScoreBar
import com.aurum.invest.ui.components.SentimentDot
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

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
                    text = state.report?.let { r ->
                        r.healthScore?.let { "Health $it/100 · ${r.healthBand}" } ?: r.healthBand
                    } ?: "Your wealth, evaluated and managed",
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
                        contentDescription = "Re-scan the market",
                        tint = AurumColors.gold
                    )
                }
            }
        }

        if (state.loading && state.pulse == null && state.report == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AurumColors.gold)
            }
        } else {
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
}

// ---------------------------------------------------------------- content

@Composable
private fun WealthContent(
    state: WealthState,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val report = state.report
    // Which sections the user has folded away. Survives rotation and tab
    // switches, so the tab stays arranged the way they left it.
    var collapsed by rememberSaveable { mutableStateOf(HashSet<String>()) }
    fun open(key: String) = key !in collapsed
    fun toggle(key: String) {
        collapsed = HashSet(collapsed).apply { if (!add(key)) remove(key) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1 — the whole-market context.
        item {
            WealthSectionHeader("Market pulse", open("pulse")) { toggle("pulse") }
        }
        if (open("pulse")) {
            item { MarketPulseCard(pulse = state.pulse, loading = state.pulseLoading) }
        }

        // 2 — the wealth engine's verdict on the whole position.
        item {
            WealthSectionHeader(
                title = "Wealth health",
                expanded = open("health"),
                trailing = report?.healthScore?.let { "$it/100" }
            ) { toggle("health") }
        }
        if (open("health")) {
            item {
                WealthHealthCard(report = report, loading = state.reportLoading)
            }
        }

        // 3 — every holding, evaluated: board, trend, trail, and the move.
        val evals = report?.holdings.orEmpty()
        if (evals.isNotEmpty() || state.reportLoading) {
            item {
                WealthSectionHeader(
                    title = "Your portfolio — the evaluation",
                    expanded = open("evaluation"),
                    trailing = evals.count { it.move != HoldingMove.HOLD }
                        .takeIf { it > 0 }?.let { "$it to act on" }
                        ?: if (evals.isNotEmpty()) "all clear" else null
                ) { toggle("evaluation") }
            }
            if (open("evaluation")) {
                if (evals.isEmpty()) {
                    item {
                        AurumCard {
                            Text(
                                text = "Reading every holding through the 35-technique board…",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                    }
                } else {
                    items(evals.size) { i ->
                        HoldingEvalRow(
                            eval = evals[i],
                            onOpen = { onOpenDetail(evals[i].symbol) },
                            onAnalyze = { onOpenAnalysis(evals[i].symbol) }
                        )
                    }
                }
            }
        }

        // 4 — the management plan: what to actually do, in order.
        val actions = report?.actions.orEmpty()
        if (actions.isNotEmpty()) {
            item {
                WealthSectionHeader(
                    title = "Management actions",
                    expanded = open("actions"),
                    trailing = "${actions.size}"
                ) { toggle("actions") }
            }
            if (open("actions")) {
                items(actions.size) { i ->
                    ManagementActionRow(
                        action = actions[i],
                        onOpen = { sym -> if (sym.isNotEmpty()) onOpenDetail(sym) }
                    )
                }
            }
        }

        // 5 — risk & performance of the current composition, honestly labeled.
        if (report?.risk != null) {
            item {
                WealthSectionHeader(
                    title = "Risk & performance",
                    expanded = open("risk"),
                    trailing = Fmt.signedPct(report.risk.returnPct)
                ) { toggle("risk") }
            }
            if (open("risk")) {
                item { RiskStatsCard(risk = report.risk) }
            }
        }

        // 6 — the liquidity-management answer.
        item {
            WealthSectionHeader(
                title = "Your liquidity — where to deploy it",
                expanded = open("deploy"),
                trailing = state.liquidity?.let { Fmt.money(it.coerceAtLeast(0.0)) }
            ) { toggle("deploy") }
        }
        if (open("deploy")) {
            item {
                DeploymentCard(
                    plan = state.deployPlan,
                    loading = state.deployLoading,
                    liquidity = state.liquidity,
                    onOpenAnalysis = onOpenAnalysis,
                    onOpenDetail = onOpenDetail
                )
            }
        }

        // 7 — the book by sector.
        if (!state.book.isEmpty) {
            item {
                WealthSectionHeader(
                    title = "Your book",
                    expanded = open("book"),
                    trailing = Fmt.money(state.book.totalValue)
                ) { toggle("book") }
            }
            if (open("book")) {
                item { BookCard(book = state.book) }
            }
        }

        // 8 — which trending themes the book is missing, and what fills them.
        if (state.strategy != null || state.strategyLoading) {
            item {
                WealthSectionHeader(
                    title = "Sector gaps in your portfolio",
                    expanded = open("gaps")
                ) { toggle("gaps") }
            }
            if (open("gaps")) {
                item {
                    SectorGapsCard(strategy = state.strategy, loading = state.strategyLoading)
                }
            }
            val strategy = state.strategy
            if (strategy != null && strategy.allocations.isNotEmpty()) {
                item {
                    WealthSectionHeader(
                        title = "Where to put this week's money",
                        expanded = open("weekmoney")
                    ) { toggle("weekmoney") }
                }
                if (open("weekmoney")) {
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

        // 9 — session extras from the pulse.
        val pulse = state.pulse
        if (pulse != null && pulse.call != MarketCall.DEFENSIVE) {
            if (pulse.bestYesterday.isNotEmpty()) {
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
                            note = PortfolioLens.pickNote(
                                mover.symbol, state.pulseSectors[mover.symbol], state.book
                            ),
                            onOpen = { onOpenDetail(mover.symbol) }
                        )
                    }
                }
            }
            if (pulse.nextDay.isNotEmpty()) {
                item {
                    WealthSectionHeader(
                        title = "Positioned for the next session",
                        expanded = open("tomorrow")
                    ) { toggle("tomorrow") }
                }
                if (open("tomorrow")) {
                    items(pulse.nextDay.size) { i ->
                        val pick = pulse.nextDay[i]
                        TomorrowRow(
                            pick = pick,
                            note = PortfolioLens.pickNote(
                                pick.symbol, state.pulseSectors[pick.symbol], state.book
                            ),
                            onOpen = { onOpenDetail(pick.symbol) },
                            onAnalyze = { onOpenAnalysis(pick.symbol) }
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = report?.caveat
                    ?: "Every number on this screen is measured from live market data and the " +
                        "35-technique board — decision support, not financial advice.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(Modifier.width(8.dp))
                    InfoDot(title = "Market pulse score", explanation = Meanings.PULSE_SCORE)
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

        // The VIX index — the market's own 30-day swing forecast, first-class
        // rather than buried in the reasons list.
        Spacer(Modifier.height(12.dp))
        VixBlock(vix = pulse.vix, change5d = pulse.vixChange5d)

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

/**
 * The invested book by sector — the same PortfolioLens math the dashboard
 * allocation card and the Picks tags use, so the numbers agree everywhere.
 */
@Composable
private fun BookCard(book: BookContext) {
    val notes = PortfolioLens.exposureNotes(book, null, null)
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

/**
 * This week's trending themes measured against the book: what is missing,
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
                        text = "You hold ${Fmt.pct(gap.heldPct)} · suggested ${Fmt.pct(gap.targetPct)}",
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

/** One line of the weekly deployment plan: theme, money, and the stock to use. */
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
                    text = if (slice.amount > 0.0) Fmt.money(slice.amount)
                    else Fmt.pct(slice.sharePct),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.gold
                )
                if (slice.amount > 0.0) {
                    Text(
                        text = Fmt.pct(slice.sharePct) + " of this week",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
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
                if (slice.amount > 0.0 && lead.entry > 0.0) {
                    StatTile(
                        label = "Approx. shares",
                        value = Fmt.qty(slice.amount / lead.entry),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    StatTile(
                        label = "You hold",
                        value = Fmt.pct(slice.heldPct),
                        modifier = Modifier.weight(1f)
                    )
                }
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

@Composable
private fun TomorrowRow(
    pick: TomorrowPick,
    note: PickNote? = null,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit
) {
    AurumCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pick.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
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
                DeltaPct(
                    value = pick.dayChangePct,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onAnalyze, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.QueryStats,
                    contentDescription = "Open ${pick.symbol} analysis",
                    tint = AurumColors.gold
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = pick.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Entry ${Fmt.money(pick.entry)}",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gold
            )
            Text(
                text = "  ·  next session ",
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
                text = "Score ${pick.score}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        WealthNoteTag(note)
    }
}

// ---------------------------------------------------------------- v10 additions

/**
 * The VIX index row of the market pulse: level, volatility regime, 5-session
 * drift, and the explain dot. An unreachable read says so — no level is ever
 * invented for it.
 */
@Composable
private fun VixBlock(vix: Double?, change5d: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "VIX — expected 30-day swings",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.width(6.dp))
                InfoDot(title = "The VIX index", explanation = Meanings.VIX)
            }
            Spacer(Modifier.height(2.dp))
            if (vix == null) {
                Text(
                    text = "Unavailable this run — the volatility read could not be measured.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", vix),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            vix < 20.0 -> AurumColors.gain
                            vix < 25.0 -> AurumColors.gold
                            else -> AurumColors.loss
                        }
                    )
                    if (change5d != null) {
                        Text(
                            text = String.format(
                                java.util.Locale.US, "  %s%.1f pts · 5 days",
                                if (change5d >= 0) "+" else "", change5d
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            // A RISING vix is the adverse direction — color by
                            // meaning, not by sign.
                            color = when {
                                change5d <= -0.05 -> AurumColors.gain
                                change5d >= 0.05 -> AurumColors.loss
                                else -> AurumColors.textDim
                            },
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
            }
        }
        if (vix != null) {
            PillTag(
                text = MarketPulse.vixRegime(vix),
                color = when {
                    vix < 20.0 -> AurumColors.gain
                    vix < 25.0 -> AurumColors.gold
                    else -> AurumColors.loss
                }
            )
        }
    }
}

/**
 * One holding of the portfolio evaluation: what it is worth, how it stands
 * against the money put in, and the advice engine's verdict with its numbers.
 */
@Composable
private fun DeploymentCard(
    plan: DeploymentPlan?,
    loading: Boolean,
    liquidity: Double?,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    AurumCard {
        if (liquidity == null) {
            Text(
                text = "Set your total wallet on the Dashboard and Aurum derives your real " +
                    "uninvested cash — then this card says how much to deploy, into which " +
                    "sectors, and into which stocks.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            return@AurumCard
        }
        if (plan == null) {
            Text(
                text = if (loading) "Sizing your liquidity against the week's sector scan…"
                else "The deployment plan needs market data. Pull down to retry.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            return@AurumCard
        }
        Text(
            text = plan.headline,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Updated ${Fmt.timeAgo(plan.computedAt)} · liquidity ${Fmt.money(plan.liquidity)}" +
                if (plan.marketNote.isNotBlank()) " · ${plan.marketNote}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        plan.sectors.forEach { sector ->
            Spacer(Modifier.height(12.dp))
            SectorDeployGroup(sector, onOpenAnalysis, onOpenDetail)
        }
        if (plan.reserve > 0.0) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatTile(label = "Reserve cash", value = Fmt.money(plan.reserve))
                Spacer(Modifier.width(8.dp))
                InfoDot(title = "Reserve cash", explanation = Meanings.RESERVE_CASH)
            }
            if (plan.reserveReason.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = plan.reserveReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = plan.policyNote,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

/** One sector's slice: verdict on coverage, its dollars, and its named stocks. */
@Composable
private fun SectorDeployGroup(
    sector: SectorDeployment,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sector.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${sector.lines.size} stock${if (sector.lines.size == 1) "" else "s"} · " +
                            Fmt.money(sector.amount) +
                            " · ${Fmt.pct(sector.heldPct)} held → ${Fmt.pct(sector.targetPct)} target",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.gold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoDot(title = "Sector target", explanation = Meanings.SECTOR_TARGET)
                }
            }
            when (sector.status) {
                GapStatus.MISSING -> PillTag(text = "Missing", color = AurumColors.loss)
                GapStatus.UNDER -> PillTag(text = "Light", color = AurumColors.gold)
                GapStatus.COVERED -> PillTag(text = "Covered", color = AurumColors.gain)
                GapStatus.OVER -> PillTag(text = "Heavy", color = AurumColors.textDim)
            }
        }
        sector.lines.forEach { line ->
            Spacer(Modifier.height(8.dp))
            DeployLineRow(line, onOpenAnalysis, onOpenDetail)
        }
    }
}

@Composable
private fun DeployLineRow(
    line: DeployLine,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    Column(modifier = Modifier.clickable { onOpenDetail(line.symbol) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#${line.rank}  ${line.symbol} · ${line.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "~${Fmt.qty(line.approxShares)} sh at ${Fmt.money(line.price)} · " +
                        "entry ≈ ${Fmt.money(line.entry)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(line.amount),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.gold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${line.conviction}/100 conviction",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.width(4.dp))
                    InfoDot(title = "Conviction", explanation = Meanings.CONVICTION)
                }
            }
        }
        line.rationale.take(2).forEach { r ->
            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
                Text(
                    text = r,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { onOpenAnalysis(line.symbol) }
        ) {
            Icon(
                imageVector = Icons.Rounded.QueryStats,
                contentDescription = null,
                tint = AurumColors.gold,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Full analysis",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.gold
            )
        }
    }
}

/** Offered, never forced: the 4-month goal plan is one tap away. */

// ---------------------------------------------------------------- wealth engine cards

/**
 * The engine's verdict on the whole position: the fixed-scale health score,
 * each graded discipline with its measured numbers, and the fix where one is
 * owed. A refusal (too little measured) shows no number — never a guess.
 */
@Composable
private fun WealthHealthCard(report: WealthReport?, loading: Boolean) {
    AurumCard {
        if (report == null) {
            Text(
                text = if (loading) {
                    "Measuring the book, the cash, the risk, and the record…"
                } else {
                    "The wealth engine needs the ledger and market data. Pull down to retry."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            return@AurumCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Wealth health",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.width(6.dp))
                    InfoDot(
                        title = "Wealth health score",
                        explanation = "Your whole position graded 0-100 on a fixed scale " +
                            "across six measured disciplines: diversification (Herfindahl " +
                            "concentration of positions and sectors), risk control " +
                            "(annualized volatility and drawdown against your own " +
                            "tolerance), trend health (value above its 200-day average, " +
                            "bullish technique boards), cash posture (liquidity against " +
                            "the VIX-regime band), performance (the current book replayed " +
                            "against SPY, with Sharpe), and the trade record (your closed " +
                            "sells' win rate at its Wilson 95% lower bound).\\n\\n" +
                            "A discipline that could not be measured contributes nothing " +
                            "and narrows the claim; below 60% measured weight no score is " +
                            "shown at all."
                    )
                }
                Text(
                    text = report.healthBand,
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.gold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = report.healthScore?.toString() ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        report.healthScore == null -> AurumColors.textDim
                        report.healthScore >= 60 -> AurumColors.gain
                        report.healthScore >= 40 -> AurumColors.gold
                        else -> AurumColors.loss
                    }
                )
                if (report.healthScore != null) {
                    Text(
                        text = "of 100",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        report.healthScore?.let { s ->
            Spacer(Modifier.height(10.dp))
            ScoreBar(score = s.toDouble(), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        report.disciplines.forEachIndexed { i, d ->
            if (i > 0) {
                HorizontalDivider(
                    color = AurumColors.hairline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            DisciplineRow(d)
        }
        if (report.notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            report.notes.forEach { note ->
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
}

@Composable
private fun DisciplineRow(d: WealthDiscipline) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = d.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = d.score?.let { "$it/${d.maxScore}" } ?: "unmeasured",
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    d.score == null -> AurumColors.textDim
                    d.score >= d.maxScore * 0.6 -> AurumColors.gain
                    d.score >= d.maxScore * 0.35 -> AurumColors.gold
                    else -> AurumColors.loss
                }
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = d.detail,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        if (d.fix.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    text = "→  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
                Text(
                    text = d.fix,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
            }
        }
    }
}

/**
 * One holding through the engine: value and weight, the board, the trend,
 * the ratcheting trail, and the move the evidence demands.
 */
@Composable
private fun HoldingEvalRow(
    eval: HoldingEvaluation,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit
) {
    AurumCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onOpen() }
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eval.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = "${Fmt.pct(eval.weightPct)} of the book",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(eval.marketValue),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeltaMoney(value = eval.unrealizedPl, style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "  ·  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                    DeltaPct(value = eval.unrealizedPlPct, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (eval.move) {
                HoldingMove.HOLD -> PillTag(text = "Hold", color = AurumColors.textDim)
                HoldingMove.ADD -> PillTag(text = "Add", color = AurumColors.gain)
                HoldingMove.TRIM -> PillTag(text = "Trim", color = AurumColors.gold)
                HoldingMove.EXIT -> PillTag(text = "Exit", color = AurumColors.loss)
            }
            Spacer(Modifier.width(8.dp))
            if (eval.boardTotal > 0) {
                Text(
                    text = "${eval.boardBullish}/${eval.boardTotal} board bullish",
                    style = MaterialTheme.typography.labelSmall,
                    color = when (eval.boardVerdict) {
                        TechniqueVerdict.BULLISH -> AurumColors.gain
                        TechniqueVerdict.BEARISH -> AurumColors.loss
                        TechniqueVerdict.NEUTRAL -> AurumColors.textDim
                    }
                )
                Spacer(Modifier.width(4.dp))
                InfoDot(title = "Board agreement", explanation = Meanings.TECH_AGREEMENT)
            }
            Spacer(Modifier.weight(1f))
            if (eval.conviction > 0) {
                Text(
                    text = "${eval.conviction}/100",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        eval.reasons.take(3).forEach { r ->
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
                Text(
                    text = r,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        eval.trailStop?.let { stop ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Trail stop ${Fmt.money(stop)}" +
                    (eval.stopDistancePct?.let {
                        " · ${Fmt.pct(it)} below — raise it as the highs rise, never lower it"
                    } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.gold
            )
        }
        Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Full 35-technique analysis",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gold
            )
        }
    }
}

/** One ordered management action: do this, because of these numbers. */
@Composable
private fun ManagementActionRow(action: ManagementAction, onOpen: (String) -> Unit) {
    AurumCard(onClick = { onOpen(action.symbol) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${action.priority}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AurumColors.gold
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AurumColors.text
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = action.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                when (action.kind) {
                    ActionKind.EXIT -> PillTag(text = "Exit", color = AurumColors.loss)
                    ActionKind.TRIM -> PillTag(text = "Trim", color = AurumColors.gold)
                    ActionKind.SET_STOP -> PillTag(text = "Stops", color = AurumColors.gold)
                    ActionKind.ADD -> PillTag(text = "Add", color = AurumColors.gain)
                    ActionKind.DEPLOY -> PillTag(text = "Deploy", color = AurumColors.gain)
                    ActionKind.RAISE_CASH -> PillTag(text = "Cash", color = AurumColors.gold)
                    ActionKind.DIVERSIFY -> PillTag(text = "Spread", color = AurumColors.gold)
                }
                action.amount?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = Fmt.money(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
            }
        }
    }
}

/**
 * Risk & performance of the CURRENT composition, replayed over the window —
 * labeled for exactly what it is: today's weights, not the trade history.
 */
@Composable
private fun RiskStatsCard(risk: RiskStats) {
    AurumCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Book (${risk.windowDays}d)",
                value = Fmt.signedPct(risk.returnPct),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.deltaColor(risk.returnPct)
            )
            StatTile(
                label = "SPY same days",
                value = risk.benchmarkReturnPct?.let { Fmt.signedPct(it) } ?: "—",
                modifier = Modifier.weight(1f),
                valueColor = risk.benchmarkReturnPct?.let { AurumColors.deltaColor(it) }
                    ?: AurumColors.textDim
            )
            StatTile(
                label = "Max drawdown",
                value = Fmt.pct(risk.maxDrawdownPct),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.loss,
                info = "Max drawdown" to (
                    "The deepest peak-to-trough fall of the replayed book value in the " +
                        "window — the worst day to have started watching. A book you " +
                        "couldn't sit through at this drawdown is a book you don't have."
                    )
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Volatility (ann.)",
                value = Fmt.pct(risk.volatilityPct),
                modifier = Modifier.weight(1f),
                info = "Annualized volatility" to (
                    "The standard deviation of the book's daily value-weighted returns, " +
                        "scaled by √252. Roughly: the book's typical yearly swing size."
                    )
            )
            StatTile(
                label = "Beta vs SPY",
                value = risk.beta?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—",
                modifier = Modifier.weight(1f),
                info = "Beta" to (
                    "cov(book, SPY) / var(SPY) over the window: how hard the book moves " +
                        "when the index moves 1%. 1.3 means a 2% market dip has typically " +
                        "dragged the book down about 2.6%."
                    )
            )
            StatTile(
                label = "Sharpe (rf 0)",
                value = risk.sharpe?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—",
                modifier = Modifier.weight(1f),
                info = "Sharpe ratio" to (
                    "Mean daily return × 252 divided by annualized volatility, " +
                        "risk-free rate taken as 0 and said so. Above 1 is good; negative " +
                        "means the risk wasn't paid for."
                    )
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Replays TODAY's composition over the last ${risk.windowDays} sessions " +
                "(${Fmt.pct(risk.measuredValuePct)} of value measured) — what these weights " +
                "would have done, not your trade history.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}
