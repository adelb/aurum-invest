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
import com.aurum.invest.analytics.GapStatus
import com.aurum.invest.analytics.MarketCall
import com.aurum.invest.analytics.MarketMover
import com.aurum.invest.analytics.MarketRating
import com.aurum.invest.analytics.NextWeekPlan
import com.aurum.invest.analytics.NextWeekSector
import com.aurum.invest.analytics.NextWeekStock
import com.aurum.invest.analytics.NoteKind
import com.aurum.invest.analytics.PickNote
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.analytics.SectorTrend
import com.aurum.invest.analytics.SectorTrends
import com.aurum.invest.analytics.TomorrowPick
import com.aurum.invest.analytics.WealthAllocation
import com.aurum.invest.analytics.WealthPlan
import com.aurum.invest.analytics.WeeklyStrategy
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import java.util.Locale
import com.aurum.invest.ui.components.AurumCard
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
                    text = when {
                        state.editing -> "Your 4-month investment plan"
                        state.plan != null -> "Weekly plan · updated ${Fmt.timeAgo(state.plan!!.updatedAt)}"
                        else -> "Your 4-month investment plan"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
            if (state.computing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AurumColors.gold,
                    strokeWidth = 2.dp
                )
            } else if (!state.editing && state.plan != null) {
                IconButton(onClick = vm::startEditing) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Edit amounts",
                        tint = AurumColors.textDim
                    )
                }
                IconButton(onClick = vm::refresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Re-scan the market",
                        tint = AurumColors.gold
                    )
                }
            }
        }

        when {
            state.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AurumColors.gold)
                }
            }
            state.editing -> {
                SetupForm(
                    initialBase = state.baseAmount,
                    initialTarget = state.targetProfit,
                    canCancel = state.plan != null,
                    onCancel = vm::cancelEditing,
                    onSave = vm::save
                )
            }
            state.computing && state.plan == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AurumColors.gold)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Scanning sectors, techniques, news, and flow headlines…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
            state.plan == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
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
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = vm::refresh,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurumColors.gold,
                                contentColor = AurumColors.bg
                            )
                        ) { Text("Retry") }
                    }
                }
            }
            else -> {
                AurumRefreshBox(
                    refreshing = state.computing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    PlanContent(
                        plan = state.plan!!,
                        pulse = state.pulse,
                        pulseLoading = state.pulseLoading,
                        book = state.book,
                        pulseSectors = state.pulseSectors,
                        strategy = state.strategy,
                        strategyLoading = state.strategyLoading,
                        preview = state.preview,
                        previewLoading = state.previewLoading,
                        previewWindowActive = state.previewWindowActive,
                        onOpenAnalysis = onOpenAnalysis,
                        onOpenDetail = onOpenDetail
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- setup form

@Composable
private fun SetupForm(
    initialBase: Double?,
    initialTarget: Double?,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onSave: (Double, Double) -> Unit
) {
    var baseText by rememberSaveable {
        mutableStateOf(initialBase?.let { Fmt.qty(it) } ?: "")
    }
    var targetText by rememberSaveable {
        mutableStateOf(initialTarget?.let { Fmt.qty(it) } ?: "")
    }
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp)
    ) {
        item {
            AurumCard {
                Text(
                    text = "Set the goal",
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tell Aurum how much you want to put to work and what profit you " +
                        "aim for over the next 4 months. Every week the plan re-reads the " +
                        "market — trending sectors, the 20-technique board on every candidate, " +
                        "news, and flow headlines — and tells you what to buy, for how much, " +
                        "when to sell, and exactly what you lose if every stop hits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = baseText,
                    onValueChange = { baseText = it },
                    label = { Text("Base amount to invest ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("Expected profit in 4 months ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                if (base != null && base > 0.0 && target != null && target > 0.0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "That is ${Fmt.pct(target / base * 100.0)} over 4 months " +
                            "(${Fmt.pct(target / base * 100.0 / 4.0)} per month). The plan " +
                            "will tell you honestly whether that pace is realistic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { if (valid) onSave(base!!, target!!) },
                    enabled = valid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.gold,
                        contentColor = AurumColors.bg,
                        disabledContainerColor = AurumColors.surfaceHigh,
                        disabledContentColor = AurumColors.textDim
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Build my weekly plan")
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
}

// ---------------------------------------------------------------- plan view

@Composable
private fun PlanContent(
    plan: WealthPlan,
    pulse: MarketRating?,
    pulseLoading: Boolean,
    book: BookContext,
    pulseSectors: Map<String, String>,
    strategy: WeeklyStrategy?,
    strategyLoading: Boolean,
    preview: NextWeekPlan?,
    previewLoading: Boolean,
    previewWindowActive: Boolean,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    // Which sections the user has folded away. Survives rotation and tab
    // switches. The screen OPENS on the goal and the plan — everything else
    // starts folded so a first look reads in seconds, not scrolls.
    var collapsed by rememberSaveable {
        mutableStateOf(
            HashSet(
                setOf("pulse", "trend", "book", "gaps", "weekmoney", "movers", "tomorrow", "insider")
            )
        )
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
        // 1 — the user's goal. Their money, their target, the honest verdict.
        item {
            WealthSectionHeader(
                title = "The goal",
                expanded = open("goal"),
                trailing = plan.feasibility.lowercase(Locale.US)
                    .replaceFirstChar { it.uppercase(Locale.US) }
            ) { toggle("goal") }
        }
        if (open("goal")) {
            item { GoalCard(plan) }
        }

        // 2 — the ONE money section: this week's plan.
        item {
            WealthSectionHeader(
                title = "This week's plan",
                expanded = open("alloc"),
                trailing = if (plan.allocations.isEmpty()) "holding cash"
                else "${plan.allocations.size} buys · ${Fmt.money(plan.cashReserve)} cash"
            ) { toggle("alloc") }
        }
        if (open("alloc")) {
            if (plan.planNote.isNotEmpty()) {
                item { PlanNoteCard(plan.planNote) }
            }
            items(plan.allocations.size) { i ->
                AllocationCard(
                    allocation = plan.allocations[i],
                    onOpenAnalysis = onOpenAnalysis,
                    onOpenDetail = onOpenDetail
                )
            }
            item { TotalsCard(plan) }
            item { ActionsCard(plan) }
        }

        // 3 — the next-week preview, live Thursday through Monday.
        item {
            WealthSectionHeader(
                title = "Next week — stocks & sectors to watch",
                expanded = open("nextweek"),
                trailing = preview?.let { Dates.weekStartLabel(it.weekStart) }
                    ?: if (previewWindowActive) "building…" else "returns Thursday"
            ) { toggle("nextweek") }
        }
        if (open("nextweek")) {
            when {
                preview != null -> {
                    item { NextWeekHeadlineCard(preview) }
                    item { NextWeekSectorsCard(preview.sectors, onOpenAnalysis) }
                    items(preview.stocks.size) { i ->
                        NextWeekStockCard(
                            stock = preview.stocks[i],
                            onOpenAnalysis = onOpenAnalysis,
                            onOpenDetail = onOpenDetail
                        )
                    }
                    item { NextWeekFooterCard(preview) }
                }
                previewLoading -> {
                    item { NextWeekLoadingCard() }
                }
                else -> {
                    item { NextWeekDormantCard() }
                }
            }
        }

        // 4 — market context, folded by default.
        item {
            WealthSectionHeader(
                title = "Market pulse",
                expanded = open("pulse"),
                trailing = pulse?.let { "${it.score}/100" }
            ) { toggle("pulse") }
        }
        if (open("pulse")) {
            item { MarketPulseCard(pulse = pulse, loading = pulseLoading) }
        }
        item {
            WealthSectionHeader(
                title = "This week's market trend",
                expanded = open("trend"),
                trailing = plan.topSectors.firstOrNull()?.label
            ) { toggle("trend") }
        }
        if (open("trend")) {
            item { SectorCard(plan, onOpenAnalysis) }
        }

        // 5 — the user's book and how the themes cut it, folded by default.
        if (!book.isEmpty) {
            item {
                WealthSectionHeader(
                    title = "Your book",
                    expanded = open("book"),
                    trailing = Fmt.money(book.totalValue)
                ) { toggle("book") }
            }
            if (open("book")) {
                item { BookCard(book = book, plan = plan) }
            }
        }
        if (strategy != null || strategyLoading) {
            item {
                WealthSectionHeader(
                    title = "Sector gaps in your portfolio",
                    expanded = open("gaps")
                ) { toggle("gaps") }
            }
            if (open("gaps")) {
                item {
                    SectorGapsCard(strategy = strategy, loading = strategyLoading)
                }
            }
            if (strategy != null && strategy.allocations.isNotEmpty()) {
                item {
                    WealthSectionHeader(
                        title = "Sector lens on the same money",
                        expanded = open("weekmoney")
                    ) { toggle("weekmoney") }
                }
                if (open("weekmoney")) {
                    item {
                        Text(
                            text = "A second cut of the SAME base, split by theme instead of " +
                                "by stock — context for the plan above, not extra buying. " +
                                "Follow one, not both.",
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

        // 6 — session extras, folded by default.
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
                            note = PortfolioLens.pickNote(mover.symbol, pulseSectors[mover.symbol], book),
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
                            note = PortfolioLens.pickNote(pick.symbol, pulseSectors[pick.symbol], book),
                            onOpen = { onOpenDetail(pick.symbol) },
                            onAnalyze = { onOpenAnalysis(pick.symbol) }
                        )
                    }
                }
            }
        }

        // 7 — flow headlines, folded by default, honestly labeled.
        if (plan.marketNotes.isNotEmpty()) {
            item {
                WealthSectionHeader("Insider & big-money flow", open("insider")) { toggle("insider") }
            }
            if (open("insider")) {
                item { NewsCard(plan, onOpen = { url ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        // no browser — ignore
                    }
                }) }
            }
        }
        item {
            Text(
                text = plan.caveat,
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

// ---------------------------------------------------------------- next week

/** One-line note card for a plan that is deliberately holding back. */
@Composable
private fun PlanNoteCard(note: String) {
    AurumCard {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.text
        )
    }
}

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
                "re-deploys the same base, never extra money",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun NextWeekSectorsCard(sectors: List<NextWeekSector>, onOpenAnalysis: (String) -> Unit) {
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
                    text = Fmt.money(stock.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.gold
                )
                Text(
                    text = String.format(Locale.US, "%.0f%% of the base", stock.allocationPct),
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

@Composable
private fun NextWeekLoadingCard() {
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AurumColors.gold,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Reading the whole market, sectors, news, and the latest " +
                    "pre/post-market prints…",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun NextWeekDormantCard() {
    AurumCard {
        Text(
            text = "The next-week preview builds Thursday through Monday, once the week " +
                "has shown its hand. Come back Thursday.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
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

/**
 * The invested book by sector — the same PortfolioLens math the dashboard
 * allocation card and the Picks tags use, so the numbers agree everywhere.
 */
@Composable
private fun BookCard(book: BookContext, plan: WealthPlan) {
    val trending = plan.topSectors.firstOrNull()
    val notes = PortfolioLens.exposureNotes(book, trending?.key, trending?.label)
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

@Composable
private fun GoalCard(plan: WealthPlan) {
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            FeasibilityPill(plan.feasibility)
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Base",
                value = Fmt.money(plan.baseAmount),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Target profit",
                value = Fmt.money(plan.targetProfit),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.gold
            )
            StatTile(
                label = "Needed / month",
                value = Fmt.pct(plan.requiredMonthlyPct),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = plan.feasibilityNote,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Horizon ends ${plan.horizonEndIso} · plan re-scans every Monday.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun FeasibilityPill(feasibility: String) {
    when (feasibility) {
        "REALISTIC" -> PillTag(text = "Realistic", color = AurumColors.gain)
        "AGGRESSIVE" -> PillTag(text = "Aggressive", color = AurumColors.gold)
        else -> PillTag(text = "Very stretched", color = AurumColors.loss)
    }
}

@Composable
private fun SectorCard(plan: WealthPlan, onOpenAnalysis: (String) -> Unit) {
    AurumCard {
        Text(
            text = plan.sectorHeadline,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(10.dp))
        plan.topSectors.forEach { s -> SectorRow(s, onOpenAnalysis) }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tap a ticker for its 20-technique analysis.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun SectorRow(s: SectorTrend, onOpenStock: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text
                )
                Text(
                    text = s.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                DeltaPct(value = s.r5Pct, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "5 days",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        val watch = SectorTrends.WATCH[s.key].orEmpty()
        if (watch.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                watch.forEach { (symbol, _) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AurumColors.surfaceHigh)
                            .clickable { onOpenStock(symbol) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = AurumColors.text
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllocationCard(
    allocation: WealthAllocation,
    onOpenAnalysis: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    AurumCard(onClick = { onOpenDetail(allocation.symbol) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = allocation.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (allocation.name.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = allocation.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (allocation.sectorLabel.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    PillTag(text = allocation.sectorLabel, color = AurumColors.info)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(allocation.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.gold
                )
                Text(
                    text = "≈ ${Fmt.qty(allocation.shares)} shares",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Entry",
                value = Fmt.money(allocation.entry),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Target",
                value = Fmt.money(allocation.target),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.gain
            )
            StatTile(
                label = "Stop",
                value = Fmt.money(allocation.stop),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.loss
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Expected: ${Fmt.signedMoney(allocation.expectedProfit)} " +
                    "(${Fmt.signedPct(allocation.expectedPct)})",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.deltaColor(allocation.expectedProfit),
                modifier = Modifier.weight(1f)
            )
            PillTag(
                text = "${allocation.techBullish}/${allocation.techTotal} bullish",
                color = AurumColors.gain
            )
        }
        // Defense first: the risk sits beside the promise, never hidden.
        if (allocation.riskDollars > 0.0) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "If the stop hits: −${Fmt.money(allocation.riskDollars)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.loss,
                    modifier = Modifier.weight(1f)
                )
                PillTag(
                    text = String.format(Locale.US, "R:R %.1f", allocation.rewardRisk),
                    color = if (allocation.rewardRisk >= 1.5) AurumColors.gain
                    else AurumColors.textDim
                )
            }
        }
        if (allocation.heldNote.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = allocation.heldNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.gold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = allocation.reason,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        AdviceLine(label = "Buy", text = allocation.buyAdvice)
        Spacer(Modifier.height(4.dp))
        AdviceLine(label = "Sell", text = allocation.sellAdvice)
        if (allocation.insiderNote.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                // A news headline, not a filing — the pill says what it is.
                PillTag(text = "News flow", color = AurumColors.info)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = allocation.insiderNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clickable { onOpenAnalysis(allocation.symbol) }
        ) {
            Icon(
                imageVector = Icons.Rounded.QueryStats,
                contentDescription = null,
                tint = AurumColors.gold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "20-technique analysis",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gold
            )
        }
    }
}

@Composable
private fun AdviceLine(label: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.gold,
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.text
        )
    }
}

@Composable
private fun TotalsCard(plan: WealthPlan) {
    AurumCard {
        Text(
            text = "If the plan plays out",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Deployed",
                value = Fmt.money(plan.baseAmount - plan.cashReserve),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Cash reserve",
                value = Fmt.money(plan.cashReserve),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Expected profit",
                value = Fmt.signedMoney(plan.expectedProfitTotal),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.deltaColor(plan.expectedProfitTotal)
            )
        }
        if (plan.riskIfAllStopsHit > 0.0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = String.format(
                    Locale.US,
                    "If every stop hits: −%s (%.1f%% of the base). That is the plan's real " +
                        "worst case — know it before placing the first buy.",
                    Fmt.money(plan.riskIfAllStopsHit),
                    if (plan.baseAmount > 0.0) plan.riskIfAllStopsHit / plan.baseAmount * 100.0 else 0.0
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.loss
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = plan.gapNote,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun ActionsCard(plan: WealthPlan) {
    AurumCard {
        Text(
            text = "This week",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(6.dp))
        plan.weeklyActions.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.gold
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text
                )
            }
        }
    }
}

@Composable
private fun NewsCard(plan: WealthPlan, onOpen: (String) -> Unit) {
    val planSymbols = plan.allocations.map { it.symbol }.toSet()
    AurumCard {
        plan.marketNotes.forEach { n ->
            // Pin each headline to a ticker where one is honestly known:
            // per-stock items carry their symbol; global sweeps are matched
            // against the plan's own names in the title, never guessed.
            val symbol = when {
                n.symbol.length in 1..5 && n.symbol.all { it.isUpperCase() || it.isDigit() } &&
                    n.symbol != "FLOW" -> n.symbol
                else -> planSymbols.firstOrNull { sym ->
                    Regex("\\b$sym\\b").containsMatchIn(n.title.uppercase(Locale.US))
                }
            }
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(n.url) }
                    .padding(vertical = 6.dp)
            ) {
                SentimentDot(sentiment = n.sentiment, modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (symbol != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PillTag(text = symbol, color = AurumColors.gold)
                            Spacer(Modifier.width(6.dp))
                            // "Clear to buy" only when the plan itself backs the
                            // name with entry, stop, and size — a headline alone
                            // never clears anything.
                            if (symbol in planSymbols) {
                                PillTag(text = "In this week's plan — clear to buy", color = AurumColors.gain)
                            } else {
                                PillTag(text = "Watch only", color = AurumColors.textDim)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = n.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(n.source, Fmt.timeAgo(n.publishedAt))
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = AurumColors.hairline)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "These are public news headlines, not SEC filings — Aurum has no Form 4 " +
                "or 13F feed. A name is clear to buy only when it appears in the plan above " +
                "with an entry, a stop, and a size.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}
