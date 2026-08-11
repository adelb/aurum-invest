package com.aurum.invest.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.PortfolioSummary
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.Block
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.DetailLine
import com.aurum.invest.ui.components.DisclosureRow
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.ExtHoursChips
import com.aurum.invest.ui.components.HeroFigure
import com.aurum.invest.ui.components.RowDivider
import com.aurum.invest.ui.components.ScreenTitle
import com.aurum.invest.ui.components.Space
import com.aurum.invest.ui.components.SplitBar
import com.aurum.invest.ui.components.Sparkline
import com.aurum.invest.ui.components.Swatch
import com.aurum.invest.ui.theme.AurumColors

/**
 * Portfolio, read top to bottom: what the book is worth, how it stands
 * against the money put in, then the holdings as a quiet list. Everything
 * else (per-position numbers, advice, removal) lives one tap deeper.
 */
@Composable
fun DashboardScreen(
    onOpenDetail: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onReports: () -> Unit
) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    confirmRemove?.let { sym ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = AurumColors.surface,
            titleContentColor = AurumColors.text,
            textContentColor = AurumColors.textDim,
            title = { Text("Remove $sym?") },
            text = {
                Text(
                    "This deletes every $sym trade from Aurum's ledger. Your other " +
                        "holdings are untouched, and nothing happens at your broker."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeHolding(sym)
                    confirmRemove = null
                }) { Text("Remove", color = AurumColors.loss) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text("Cancel", color = AurumColors.textDim)
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {
        AurumRefreshBox(
            refreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.screenH, end = Space.screenH, top = 18.dp, bottom = 120.dp
                )
            ) {
                item {
                    ScreenTitle(
                        overline = "Portfolio",
                        title = "Aurum",
                        trailing = {
                            Row {
                                IconButton(onClick = onReports) {
                                    Icon(
                                        Icons.Rounded.Description,
                                        contentDescription = "Reports",
                                        tint = AurumColors.textDim
                                    )
                                }
                                IconButton(onClick = onSettings) {
                                    Icon(
                                        Icons.Rounded.Settings,
                                        contentDescription = "Settings",
                                        tint = AurumColors.textDim
                                    )
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(28.dp))
                }

                item {
                    Hero(summary = state.summary)
                    Spacer(Modifier.height(Space.block))
                }

                if (state.holdings.size >= 2) {
                    item {
                        AllocationBlock(holdings = state.holdings, book = state.book)
                        Spacer(Modifier.height(Space.block))
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "HOLDINGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.holdings.isNotEmpty()) {
                            Text(
                                text = "${state.holdings.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.textDim
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.item))
                    if (state.holdings.isNotEmpty()) RowDivider()
                }

                if (state.loading && state.holdings.isEmpty() && state.summary == null) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = AurumColors.gold) }
                    }
                } else if (state.holdings.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No holdings yet",
                            message = "Record your first buy and Aurum tracks price, P/L and advice for it.",
                            actionLabel = "Add a trade",
                            onAction = onAdd
                        )
                    }
                } else {
                    items(state.holdings, key = { it.view.position.symbol }) { row ->
                        HoldingRowItem(
                            row = row,
                            onOpen = { onOpenDetail(row.view.position.symbol) },
                            onRemove = { confirmRemove = row.view.position.symbol }
                        )
                        RowDivider()
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            containerColor = AurumColors.gold,
            contentColor = AurumColors.bg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add trade")
        }
    }
}

/**
 * The single figure that matters, with the answer to "how am I doing?"
 * directly beneath it — measured against invested cost, not the session.
 */
@Composable
private fun Hero(summary: PortfolioSummary?) {
    val s = summary ?: PortfolioSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    HeroFigure(
        label = "Total value",
        value = Fmt.money(s.marketValue),
        support = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeltaMoney(value = s.unrealizedPl, style = MaterialTheme.typography.titleMedium)
                    if (s.investedCost > 0.0) {
                        Text(
                            text = "  ",
                            style = MaterialTheme.typography.titleMedium,
                            color = AurumColors.textDim
                        )
                        DeltaPct(
                            value = s.unrealizedPl / s.investedCost * 100.0,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = "  vs invested",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietStat(
                        label = if (Dates.usMarketOpenedToday()) "Today" else "Last session",
                        value = Fmt.signedMoney(s.dayPl),
                        color = AurumColors.deltaColor(s.dayPl)
                    )
                    Spacer(Modifier.width(24.dp))
                    QuietStat(label = "Invested", value = Fmt.money(s.investedCost))
                    Spacer(Modifier.width(24.dp))
                    QuietStat(
                        label = "Realized",
                        value = Fmt.signedMoney(s.realizedPl),
                        color = AurumColors.deltaColor(s.realizedPl)
                    )
                }
            }
        }
    )
}

@Composable
private fun QuietStat(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = AurumColors.text
) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

/** Allocation: swipe between the per-stock split and the sector split. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllocationBlock(holdings: List<HoldingRow>, book: BookContext) {
    val total = holdings.sumOf { it.view.marketValue }
    if (total <= 0.0) return
    val pager = rememberPagerState { 2 }
    Block(
        label = "Allocation",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (pager.currentPage == 0) "By stock" else "By sector",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.width(8.dp))
                repeat(2) { i ->
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                if (pager.currentPage == i) AurumColors.gold
                                else AurumColors.hairline
                            )
                    )
                }
            }
        }
    ) {
        HorizontalPager(
            state = pager,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.animateContentSize()
        ) { page ->
            if (page == 0) {
                Column {
                    SplitBar(weights = holdings.map { (it.view.marketValue / total).toFloat() })
                    Spacer(Modifier.height(12.dp))
                    holdings.take(6).forEachIndexed { i, row ->
                        LegendRow(
                            index = i,
                            label = row.view.position.symbol,
                            pct = row.view.marketValue / total * 100.0
                        )
                    }
                }
            } else if (book.isEmpty) {
                Text(
                    text = "Sector data is loading — pull to refresh if it stays empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            } else {
                Column {
                    SplitBar(weights = book.slices.map { it.weightPct.toFloat() })
                    Spacer(Modifier.height(12.dp))
                    book.slices.forEachIndexed { i, slice ->
                        LegendRow(
                            index = i,
                            label = slice.sector,
                            pct = slice.weightPct,
                            support = slice.symbols.take(3).joinToString(", ")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(index: Int, label: String, pct: Double, support: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Swatch(AurumColors.allocation[index % AurumColors.allocation.size])
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.text
        )
        if (support != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = support,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = Fmt.pct(pct),
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
    }
}

/**
 * One holding: symbol, value, price and today's move — nothing more at rest.
 * Expanding reveals the position's numbers, the advice and the remove action.
 */
@Composable
private fun HoldingRowItem(row: HoldingRow, onOpen: () -> Unit, onRemove: () -> Unit) {
    val view = row.view
    val position = view.position
    val quote = view.quote
    val price = quote?.price ?: position.avgCost

    DisclosureRow(
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = position.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${Fmt.qty(position.shares)} sh · ${Fmt.money(view.marketValue)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                if (row.spark.size >= 2) {
                    Sparkline(
                        data = row.spark,
                        modifier = Modifier.width(56.dp).height(22.dp),
                        fill = false
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = Fmt.money(price),
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.text
                    )
                    Spacer(Modifier.height(2.dp))
                    DeltaPct(
                        value = view.unrealizedPlPct,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
        }
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            DetailLine(
                label = "Unrealized",
                value = Fmt.signedMoney(view.unrealizedPl),
                valueColor = AurumColors.deltaColor(view.unrealizedPl)
            )
            DetailLine("Average cost", Fmt.money(position.avgCost))
            if (quote != null) {
                DetailLine(
                    label = if (Dates.usMarketOpenedToday()) "Today" else "Last session",
                    value = Fmt.signedPct(quote.dayChangePct),
                    valueColor = AurumColors.deltaColor(quote.dayChangePct)
                )
            }
            row.advice?.let { advice ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = advice.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            if (row.ext?.preMarketPct != null || row.ext?.postMarketPct != null) {
                Spacer(Modifier.height(8.dp))
                ExtHoursChips(ext = row.ext)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) {
                    Text("Open ${position.symbol}", color = AurumColors.gold)
                }
                TextButton(onClick = onRemove) {
                    Text("Remove", color = AurumColors.textDim)
                }
            }
        }
    }
}
