package com.aurum.invest.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.AllocationSlice
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.GapStatus
import com.aurum.invest.analytics.MarketCall
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.SectorGap
import com.aurum.invest.analytics.SectorPick
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.analytics.WealthPlan
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.Block
import com.aurum.invest.ui.components.Bullet
import com.aurum.invest.ui.components.DetailLine
import com.aurum.invest.ui.components.DisclosureRow
import com.aurum.invest.ui.components.Footnote
import com.aurum.invest.ui.components.HeroFigure
import com.aurum.invest.ui.components.RowDivider
import com.aurum.invest.ui.components.ScreenTitle
import com.aurum.invest.ui.components.Space
import com.aurum.invest.ui.components.SplitBar
import com.aurum.invest.ui.components.StatusTag
import com.aurum.invest.ui.components.Swatch
import com.aurum.invest.ui.theme.AurumColors

/**
 * Wealth, organised as one weekly decision rather than a wall of research:
 *
 *   1. Is this a week to invest?      — the verdict, one line
 *   2. Where is my book thin?         — trending themes vs what you hold
 *   3. What do I buy this week?       — money split across themes, one stock each
 *   4. Everything else                — collapsed until asked for
 */
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
                .padding(start = Space.screenH, end = Space.screenH, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScreenTitle(
                overline = if (state.plan != null) "This week" else "Plan",
                title = "Wealth",
                modifier = Modifier.weight(1f)
            )
            if (state.computing || state.strategyLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AurumColors.gold,
                    strokeWidth = 2.dp
                )
            } else if (!state.editing && state.plan != null) {
                IconButton(onClick = vm::startEditing) {
                    Icon(Icons.Rounded.Edit, "Edit amounts", tint = AurumColors.textDim)
                }
                IconButton(onClick = vm::refresh) {
                    Icon(Icons.Rounded.Refresh, "Re-scan the market", tint = AurumColors.gold)
                }
            }
        }

        when {
            state.loading -> Centered { CircularProgressIndicator(color = AurumColors.gold) }

            state.editing -> SetupForm(
                initialBase = state.baseAmount,
                initialTarget = state.targetProfit,
                canCancel = state.plan != null,
                onCancel = vm::cancelEditing,
                onSave = vm::save
            )

            state.computing && state.plan == null -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AurumColors.gold)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Reading sectors, techniques and news…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
            }

            state.plan == null -> Centered {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = "Could not build the plan",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Market data was unreachable. Check the connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = vm::refresh,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.gold,
                            contentColor = AurumColors.bg
                        )
                    ) { Text("Retry") }
                }
            }

            else -> AurumRefreshBox(
                refreshing = state.computing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                WealthContent(
                    plan = state.plan!!,
                    pulse = state.pulse,
                    strategy = state.strategy,
                    strategyLoading = state.strategyLoading,
                    book = state.book,
                    onOpenAnalysis = onOpenAnalysis,
                    onOpenDetail = onOpenDetail
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// ------------------------------------------------------------------ content

@Composable
private fun WealthContent(
    plan: WealthPlan,
    pulse: MarketRating?,
    strategy: WeeklyStrategy?,
    strategyLoading: Boolean,
    book: BookContext,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.screenH, end = Space.screenH, top = 18.dp, bottom = 32.dp
        )
    ) {
        // 1 — the verdict, in one line.
        item {
            VerdictBlock(pulse)
            Spacer(Modifier.height(Space.block))
        }

        // 2 — where the book is thin.
        item {
            GapsBlock(strategy = strategy, loading = strategyLoading, book = book)
            Spacer(Modifier.height(Space.block))
        }

        // 3 — what to buy this week.
        item {
            AllocationBlock(
                strategy = strategy,
                loading = strategyLoading,
                onOpenDetail = onOpenDetail,
                onOpenAnalysis = onOpenAnalysis
            )
            Spacer(Modifier.height(Space.block))
        }

        // 4 — the deeper material, collapsed.
        item {
            Block(label = "More") {
                Column {
                    RowDivider()
                    DisclosureRow(
                        header = { DisclosureTitle("Your 4-month goal", plan.feasibility.lowercase()) }
                    ) {
                        GoalDetail(plan)
                    }
                    RowDivider()
                    DisclosureRow(
                        header = {
                            DisclosureTitle(
                                "Named positions",
                                "${plan.allocations.size} stocks"
                            )
                        }
                    ) {
                        plan.allocations.forEach { a ->
                            PositionLine(
                                symbol = a.symbol,
                                name = a.name,
                                amount = a.amount,
                                entry = a.entry,
                                target = a.target,
                                stop = a.stop,
                                reason = a.reason,
                                onOpen = { onOpenDetail(a.symbol) },
                                onAnalyze = { onOpenAnalysis(a.symbol) }
                            )
                        }
                        if (plan.cashReserve > 0.0) {
                            DetailLine("Cash reserve", Fmt.money(plan.cashReserve))
                        }
                    }
                    RowDivider()
                    if (pulse != null) {
                        DisclosureRow(
                            header = { DisclosureTitle("Why this verdict", "${pulse.score}/100") }
                        ) {
                            pulse.reasons.forEach { Bullet(it) }
                            Spacer(Modifier.height(6.dp))
                            pulse.indexes.forEach { ix ->
                                DetailLine(
                                    label = ix.name,
                                    value = "${Fmt.signedPct(ix.r5Pct)} · 5d",
                                    valueColor = AurumColors.deltaColor(ix.r5Pct)
                                )
                            }
                        }
                        RowDivider()
                    }
                    if (strategy != null && strategy.notes.isNotEmpty()) {
                        DisclosureRow(
                            header = { DisclosureTitle("How this plan was built", "method") }
                        ) {
                            strategy.notes.forEach { Bullet(it) }
                        }
                        RowDivider()
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Footnote(
                "Computed from live market data, sector ETF momentum and your own holdings. " +
                    "Decision support, not financial advice."
            )
        }
    }
}

// ---------------------------------------------------------------- 1: verdict

@Composable
private fun VerdictBlock(pulse: MarketRating?) {
    if (pulse == null) {
        Block(label = "Market", caption = "Reading the market…") {}
        return
    }
    val color = when (pulse.call) {
        MarketCall.INVEST -> AurumColors.gain
        MarketCall.SELECTIVE -> AurumColors.gold
        MarketCall.DEFENSIVE -> AurumColors.loss
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "MARKET · ${pulse.score}/100",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (pulse.call) {
                MarketCall.INVEST -> "A good week to invest."
                MarketCall.SELECTIVE -> "Invest selectively this week."
                MarketCall.DEFENSIVE -> "Hold back this week."
            },
            style = MaterialTheme.typography.headlineMedium,
            color = color
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = pulse.advice,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(12.dp))
        SplitBar(
            weights = listOf(pulse.score.toFloat(), (100 - pulse.score).toFloat()),
            colors = listOf(color, AurumColors.hairline),
            height = 4.dp
        )
    }
}

// ------------------------------------------------------------------- 2: gaps

@Composable
private fun GapsBlock(strategy: WeeklyStrategy?, loading: Boolean, book: BookContext) {
    Block(
        label = "Your sector gaps",
        caption = strategy?.headline
            ?: if (loading) "Matching this week's themes to your holdings…"
            else "Sector data unavailable right now."
    ) {
        if (strategy == null) return@Block
        Column {
            RowDivider()
            strategy.gaps.forEach { gap ->
                DisclosureRow(header = { GapHeader(gap) }) { GapDetail(gap) }
                RowDivider()
            }
            if (!book.isEmpty) {
                Spacer(Modifier.height(10.dp))
                BookStrip(book)
            }
        }
    }
}

@Composable
private fun GapHeader(gap: SectorGap) {
    val (label, color) = when (gap.status) {
        GapStatus.MISSING -> "Missing" to AurumColors.loss
        GapStatus.UNDER -> "Light" to AurumColors.gold
        GapStatus.COVERED -> "Covered" to AurumColors.gain
        GapStatus.OVER -> "Heavy" to AurumColors.info
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
        Text(
            text = Fmt.signedPct(gap.r5Pct),
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.deltaColor(gap.r5Pct)
        )
        Spacer(Modifier.width(10.dp))
        StatusTag(label, color)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun GapDetail(gap: SectorGap) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        DetailLine("Theme momentum", "${Fmt.signedPct(gap.r5Pct)} 5d · ${Fmt.signedPct(gap.r20Pct)} 20d")
        DetailLine("Tracked by", gap.etf)
        if (gap.coverageFromHoldings) {
            Spacer(Modifier.height(4.dp))
            Footnote(
                "This theme spans several sectors, so coverage counts only stocks " +
                    "you hold from its list — not a sector match."
            )
        }
        if (gap.picks.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Footnote("No name in this theme passes the technique board right now.")
        } else {
            Spacer(Modifier.height(8.dp))
            gap.picks.forEach { pick ->
                Text(
                    text = "${pick.symbol} · ${pick.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun BookStrip(book: BookContext) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "YOUR BOOK · ${Fmt.money(book.totalValue)}",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        SplitBar(weights = book.slices.map { it.weightPct.toFloat() })
        Spacer(Modifier.height(8.dp))
        book.slices.take(4).forEachIndexed { i, slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Swatch(AurumColors.allocation[i % AurumColors.allocation.size])
                Text(
                    text = "  ${slice.sector}",
                    style = MaterialTheme.typography.bodySmall,
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
    }
}

// ------------------------------------------------------------- 3: allocation

@Composable
private fun AllocationBlock(
    strategy: WeeklyStrategy?,
    loading: Boolean,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    val allocations = strategy?.allocations.orEmpty()
    Block(
        label = "Buy this week",
        caption = when {
            allocations.isNotEmpty() && strategy!!.investable > 0.0 ->
                "How to split ${Fmt.money(strategy.investable)} across this week's themes."
            allocations.isNotEmpty() ->
                "How to split new money across this week's themes. Set an amount to see dollars."
            loading -> "Working out the split…"
            else -> "No theme currently has a name that passes the technique board."
        }
    ) {
        if (allocations.isEmpty()) return@Block
        Column {
            SplitBar(weights = allocations.map { it.sharePct.toFloat() })
            Spacer(Modifier.height(14.dp))
            RowDivider()
            allocations.forEachIndexed { i, slice ->
                DisclosureRow(header = { AllocationHeader(slice, i) }) {
                    AllocationDetail(slice, onOpenDetail, onOpenAnalysis)
                }
                RowDivider()
            }
        }
    }
}

@Composable
private fun AllocationHeader(slice: AllocationSlice, index: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatch(AurumColors.allocation[index % AurumColors.allocation.size])
        Spacer(Modifier.width(10.dp))
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
                text = slice.lead?.let { "via ${it.symbol}" } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (slice.amount > 0.0) Fmt.money(slice.amount) else Fmt.pct(slice.sharePct),
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
            if (slice.amount > 0.0) {
                Text(
                    text = Fmt.pct(slice.sharePct),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun AllocationDetail(
    slice: AllocationSlice,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    val lead = slice.lead ?: return
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = lead.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        DetailLine("Entry", Fmt.money(lead.entry), valueColor = AurumColors.gold)
        DetailLine("Last price", Fmt.money(lead.price))
        if (slice.amount > 0.0 && lead.entry > 0.0) {
            DetailLine("Approx. shares", Fmt.qty(slice.amount / lead.entry))
        }
        DetailLine("You already hold", Fmt.pct(slice.heldPct) + " of book in this theme")
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { onOpenDetail(lead.symbol) }) {
                Text(lead.symbol, color = AurumColors.gold)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { onOpenAnalysis(lead.symbol) }) {
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

// ------------------------------------------------------------------- 4: more

@Composable
private fun DisclosureTitle(title: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun GoalDetail(plan: WealthPlan) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        DetailLine("Base amount", Fmt.money(plan.baseAmount))
        DetailLine("Profit target", Fmt.money(plan.targetProfit))
        DetailLine("Required return", Fmt.pct(plan.requiredTotalPct))
        DetailLine("Per month", Fmt.pct(plan.requiredMonthlyPct))
        DetailLine(
            label = "Expected from this plan",
            value = Fmt.signedMoney(plan.expectedProfitTotal),
            valueColor = AurumColors.deltaColor(plan.expectedProfitTotal)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = plan.feasibilityNote,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (plan.gapNote.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = plan.gapNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun PositionLine(
    symbol: String,
    name: String,
    amount: Double,
    entry: Double,
    target: Double,
    stop: Double,
    reason: String,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = Fmt.money(amount),
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(6.dp))
        Row {
            LevelChip("Entry", entry, AurumColors.gold)
            Spacer(Modifier.width(8.dp))
            LevelChip("Target", target, AurumColors.gain)
            Spacer(Modifier.width(8.dp))
            LevelChip("Stop", stop, AurumColors.loss)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.QueryStats,
                contentDescription = "Analysis",
                tint = AurumColors.gold,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAnalyze() }
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun LevelChip(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = Fmt.money(value),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

// ------------------------------------------------------------------- setup

@Composable
private fun SetupForm(
    initialBase: Double?,
    initialTarget: Double?,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onSave: (Double, Double) -> Unit
) {
    var baseText by rememberSaveable { mutableStateOf(initialBase?.let { Fmt.qty(it) } ?: "") }
    var targetText by rememberSaveable { mutableStateOf(initialTarget?.let { Fmt.qty(it) } ?: "") }
    val base = baseText.replace(",", "").toDoubleOrNull()
    val target = targetText.replace(",", "").toDoubleOrNull()
    val valid = base != null && base > 0.0 && target != null && target > 0.0

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = AurumColors.text,
        unfocusedTextColor = AurumColors.text,
        focusedBorderColor = AurumColors.gold,
        unfocusedBorderColor = AurumColors.hairline,
        focusedLabelColor = AurumColors.gold,
        unfocusedLabelColor = AurumColors.textDim,
        cursorColor = AurumColors.gold
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.screenH, end = Space.screenH, top = 24.dp, bottom = 28.dp
        )
    ) {
        item {
            Text(
                text = "How much are you putting to work, and what do you want back?",
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.text
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Aurum re-reads the market every week — trending sectors, the technique " +
                    "board on every candidate, news — then tells you what to buy, how much, " +
                    "and which sectors your portfolio is missing.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = baseText,
                onValueChange = { baseText = it },
                label = { Text("Amount to invest ($)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it },
                label = { Text("Profit target in 4 months ($)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            if (valid) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "That is ${Fmt.pct(target!! / base!! * 100.0)} over 4 months " +
                        "(${Fmt.pct(target / base * 100.0 / 4.0)} per month). The plan will " +
                        "tell you honestly whether that pace is realistic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (valid) onSave(base!!, target!!) },
                enabled = valid,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.gold,
                    contentColor = AurumColors.bg,
                    disabledContainerColor = AurumColors.surfaceHigh,
                    disabledContentColor = AurumColors.textDim
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Build my plan", style = MaterialTheme.typography.labelLarge)
            }
            if (canCancel) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep the current plan", color = AurumColors.textDim)
                }
            }
        }
    }
}
