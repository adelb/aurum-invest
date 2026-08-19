package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.BookContext
import com.aurum.invest.analytics.PortfolioLens
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.PortfolioSummary
import com.aurum.invest.data.repo.TargetsRepository
import com.aurum.invest.data.repo.WalletState
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.ExtHoursChips
import com.aurum.invest.ui.components.GoldGradientText
import com.aurum.invest.ui.components.InfoDot
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.Sparkline
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun DashboardScreen(
    onOpenDetail: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onReports: () -> Unit,
    onEditPosition: (String) -> Unit
) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var targetFor by remember { mutableStateOf<HoldingRow?>(null) }
    var showWalletDialog by remember { mutableStateOf(false) }

    if (showWalletDialog) {
        WalletSetupDialog(
            current = state.wallet.total,
            onDismiss = { showWalletDialog = false },
            onSave = { amount ->
                vm.setWalletTotal(amount)
                showWalletDialog = false
            }
        )
    }

    targetFor?.let { row ->
        SellTargetDialog(
            row = row,
            onDismiss = { targetFor = null },
            onSave = { pct ->
                vm.setTarget(row.view.position.symbol, pct)
                targetFor = null
            }
        )
    }

    confirmRemove?.let { sym ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = AurumColors.surface,
            titleContentColor = AurumColors.text,
            textContentColor = AurumColors.textDim,
            title = { Text("Remove $sym?") },
            text = {
                Text(
                    "This deletes every $sym trade record from Aurum's ledger — handy for " +
                        "clearing a test position. Your other holdings are untouched, and " +
                        "nothing happens at your real broker."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeHolding(sym)
                    confirmRemove = null
                }) {
                    Text("Remove", color = AurumColors.loss)
                }
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
                    start = 20.dp, end = 20.dp, top = 12.dp, bottom = 110.dp
                )
            ) {
                item {
                    HeaderRow(
                        loading = state.loading,
                        onRefresh = vm::refresh,
                        onSettings = onSettings,
                        onReports = onReports
                    )
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    HeroSummary(summary = state.summary, wallet = state.wallet)
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    SummaryTiles(summary = state.summary)
                    TextButton(onClick = { showWalletDialog = true }) {
                        Text(
                            text = if (state.wallet.configured) {
                                "Edit total wallet"
                            } else {
                                "Set your total wallet"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.gold
                        )
                    }
                }

                if (state.holdings.size >= 2) {
                    item {
                        Spacer(Modifier.height(14.dp))
                        AllocationCard(holdings = state.holdings, book = state.book)
                    }
                }

                item {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader(
                        title = "Holdings",
                        trailing = {
                            if (state.holdings.isNotEmpty()) {
                                Text(
                                    text = "${state.holdings.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.textDim
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                }

                if (state.loading && state.holdings.isEmpty() && state.summary == null) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AurumColors.gold)
                        }
                    }
                } else if (state.holdings.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No holdings yet",
                            message = "Record your first buy and Aurum starts tracking price, P/L and advice for it.",
                            actionLabel = "Add a trade",
                            onAction = onAdd
                        )
                    }
                } else {
                    items(state.holdings, key = { it.view.position.symbol }) { row ->
                        HoldingCard(
                            row = row,
                            onClick = { onOpenDetail(row.view.position.symbol) },
                            onEdit = { onEditPosition(row.view.position.symbol) },
                            onSetTarget = { targetFor = row },
                            onRemove = { confirmRemove = row.view.position.symbol }
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            containerColor = AurumColors.gold,
            contentColor = AurumColors.bg,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add trade")
        }
    }
}

/**
 * Where the money sits. Swipe between two reads of the SAME live values:
 * page one is per stock, page two compounds the stocks into their sectors
 * (via the shared [PortfolioLens], so Picks and Wealth agree with it).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AllocationCard(holdings: List<HoldingRow>, book: BookContext) {
    val total = holdings.sumOf { it.view.marketValue }
    if (total <= 0.0) return
    val pagerState = rememberPagerState { 2 }
    AurumCard(modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Allocation",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (pagerState.currentPage == 0) "By stock · swipe for sectors"
                else "By sector",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { i ->
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == i) AurumColors.gold
                                else AurumColors.hairline
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalPager(state = pagerState, verticalAlignment = Alignment.Top) { page ->
            if (page == 0) {
                StockAllocation(holdings = holdings, total = total)
            } else {
                SectorAllocation(book = book)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockAllocation(holdings: List<HoldingRow>, total: Double) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            holdings.forEachIndexed { i, row ->
                val weight = (row.view.marketValue / total).toFloat()
                if (weight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                AurumColors.allocation[i % AurumColors.allocation.size]
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            holdings.forEachIndexed { i, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                AurumColors.allocation[i % AurumColors.allocation.size]
                            )
                    )
                    Text(
                        text = "  ${row.view.position.symbol} " +
                            Fmt.pct(row.view.marketValue / total * 100.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}

@Composable
private fun SectorAllocation(book: BookContext) {
    if (book.isEmpty) {
        Text(
            text = "Sector data is still loading — swipe back, or pull to refresh.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            book.slices.forEachIndexed { i, slice ->
                val weight = (slice.weightPct / 100.0).toFloat()
                if (weight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                AurumColors.allocation[i % AurumColors.allocation.size]
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        book.slices.forEachIndexed { i, slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            AurumColors.allocation[i % AurumColors.allocation.size]
                        )
                )
                Text(
                    text = "  ${slice.sector}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.text
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = slice.symbols.take(4).joinToString(", ") +
                        (if (slice.symbols.size > 4) " +${slice.symbols.size - 4}" else "") +
                        "  ·  " + Fmt.pct(slice.weightPct),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onReports: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Aurum",
            style = MaterialTheme.typography.titleLarge,
            color = AurumColors.gold
        )
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = loading, enter = fadeIn(), exit = fadeOut()) {
            CircularProgressIndicator(
                color = AurumColors.gold,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "Refresh",
                tint = AurumColors.textDim
            )
        }
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

@Composable
private fun HeroSummary(summary: PortfolioSummary?, wallet: WalletState = WalletState.UNSET) {
    val s = summary ?: PortfolioSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Text(
            // "Holdings value", not "Total value": uninvested cash is its own
            // line below — the sum of open positions must never masquerade as
            // account equity.
            text = "Holdings value",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(4.dp))
        // Rolls per digit and flashes by direction as the prices re-price the
        // book — only the places that changed move.
        AnimatedMoney(
            value = s.marketValue,
            style = MaterialTheme.typography.displayLarge,
            baseColor = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        // The headline delta: how the holdings stand AGAINST THE MONEY PUT IN
        // (market value vs remaining cost basis), not just today's move.
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeltaMoney(value = s.unrealizedPl, style = MaterialTheme.typography.titleMedium)
            if (s.investedCost > 0.0) {
                Text(
                    text = "  ·  ",
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.textDim
                )
                DeltaPct(
                    value = s.unrealizedPl / s.investedCost * 100.0,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = " vs invested",
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(2.dp))
        // The session move, demoted to a secondary line.
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeltaMoney(value = s.dayPl, style = MaterialTheme.typography.bodySmall)
            Text(
                // Before today's US open, the delta belongs to the last session.
                text = if (Dates.usMarketOpenedToday()) " today" else " last session",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        if (wallet.configured) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                WalletStat(label = "Wallet", value = wallet.total, modifier = Modifier.weight(1f))
                WalletStat(label = "Invested", value = wallet.invested, modifier = Modifier.weight(1f))
                WalletStat(
                    label = "Liquidity",
                    value = wallet.liquidity,
                    modifier = Modifier.weight(1f),
                    valueColor = if (wallet.shortfall) AurumColors.loss else AurumColors.text
                )
            }
            Spacer(Modifier.height(6.dp))
            // The second row closes the loop: what the sells booked, the
            // accumulated P/L, and what the wallet is actually worth today.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                WalletStat(
                    label = "Realized",
                    value = wallet.realizedPl,
                    modifier = Modifier.weight(1f),
                    signed = true
                )
                WalletStat(
                    label = "Total P/L",
                    value = wallet.totalPl,
                    modifier = Modifier.weight(1f),
                    signed = true
                )
                WalletStat(label = "Net worth", value = wallet.netWorth, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            // Says out loud how the three cash figures relate, so a sell that
            // moves liquidity is never mistaken for a number drifting on its own.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Liquidity = wallet − invested + realized · " +
                        "net worth = liquidity + holdings",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.width(6.dp))
                InfoDot(
                    title = "The wallet identity",
                    explanation = "You state one number — the total cash you committed to " +
                        "investing. Aurum reads what is deployed (invested cost) and what " +
                        "your sells have booked (realized P/L) from the ledger, and derives " +
                        "liquidity = wallet − invested + realized. A sell returns its cost " +
                        "basis AND its profit to liquidity. Net worth is liquidity plus " +
                        "what the holdings are worth right now. Change the wallet total " +
                        "only when you actually add or withdraw money."
                )
            }
            if (wallet.shortfall) {
                Text(
                    text = "Your ledger has ${Fmt.moneyExact(-wallet.liquidity)} more deployed than " +
                        "the stated wallet covers — raise the total if you've added money.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
            }
        }
    }
}

@Composable
private fun WalletStat(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = AurumColors.text
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim,
            maxLines = 1
        )
        // Exact cents (rounding $25,477.30 to "$25,477" hides money the user
        // has), and animated so a figure moved by the live prices announces
        // itself instead of silently differing from a second ago.
        AnimatedMoney(
            value = value,
            style = MaterialTheme.typography.bodyMedium,
            baseColor = if (signed) AurumColors.deltaColor(value) else valueColor,
            signed = signed
        )
    }
}

@Composable
private fun SummaryTiles(summary: PortfolioSummary?) {
    val s = summary ?: PortfolioSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    AurumCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Invested",
                value = Fmt.money(s.investedCost),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Unrealized P/L",
                value = Fmt.signedMoney(s.unrealizedPl),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.deltaColor(s.unrealizedPl)
            )
            StatTile(
                label = "Realized P/L",
                value = Fmt.signedMoney(s.realizedPl),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.deltaColor(s.realizedPl)
            )
        }
    }
}

@Composable
private fun HoldingCard(
    row: HoldingRow,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onSetTarget: () -> Unit,
    onRemove: () -> Unit
) {
    val view = row.view
    val position = view.position
    val quote = view.quote
    val price = quote?.price ?: position.avgCost

    AurumCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = position.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                }
                Text(
                    text = "${Fmt.qty(position.shares)} shares · ${Fmt.money(view.marketValue)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            if (row.spark.size >= 2) {
                Sparkline(
                    data = row.spark,
                    modifier = Modifier.width(90.dp).height(36.dp)
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(price),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text
                )
                if (quote != null) {
                    DeltaPct(
                        value = quote.dayChangePct,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Unrealized  ",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            DeltaMoney(value = view.unrealizedPl, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "  ·  ",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            DeltaPct(value = view.unrealizedPlPct, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            row.advice?.let { ActionBadge(action = it.action) }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "Edit ${position.symbol} trades",
                    tint = AurumColors.textDim,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove ${position.symbol} from portfolio",
                    tint = AurumColors.textDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (row.ext?.preMarketPct != null || row.ext?.postMarketPct != null) {
            Spacer(Modifier.height(8.dp))
            ExtHoursChips(ext = row.ext)
        }
        Spacer(Modifier.height(10.dp))
        SellTargetFlag(row = row, onClick = onSetTarget)
    }
}

/**
 * The sell flag: the exact price this position must reach for the profit
 * percentage the user asked for, plus how far away it still is. Tap to set
 * or change the percentage; it turns green the moment the price gets there.
 */
@Composable
private fun SellTargetFlag(row: HoldingRow, onClick: () -> Unit) {
    val targetPrice = row.targetPrice
    val shape = RoundedCornerShape(10.dp)
    if (targetPrice == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AurumColors.surfaceHigh)
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Flag,
                contentDescription = null,
                tint = AurumColors.textDim,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Set a profit target — Aurum shows the sell price",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        return
    }

    val reached = row.targetReached
    val accent = if (reached) AurumColors.gain else AurumColors.gold
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.10f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Flag,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (reached) "Target reached — sell at ${Fmt.money(targetPrice)}"
                else "Sell at ${Fmt.money(targetPrice)}",
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
            Spacer(Modifier.height(2.dp))
            val distance = row.distanceToTargetPct
            Text(
                text = buildString {
                    append("for ")
                    append(Fmt.pct(row.targetPct ?: 0.0))
                    append(" on ")
                    append(Fmt.money(row.view.position.avgCost))
                    append(" avg cost")
                    if (distance != null && !reached) {
                        append(" · ")
                        append(Fmt.pct(distance))
                        append(" to go")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        val gain = row.targetPct?.let { row.view.position.shares * row.view.position.avgCost * it / 100.0 }
        if (gain != null) {
            Text(
                text = Fmt.signedMoney(gain),
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
        }
    }
}

/** Enter any profit percentage; the sell price is computed live as you type. */
@Composable
private fun SellTargetDialog(
    row: HoldingRow,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit
) {
    var text by remember {
        mutableStateOf(row.targetPct?.let { Fmt.trimNumber(it) } ?: "")
    }
    val pct = text.replace(",", "").trim().toDoubleOrNull()
    val valid = pct != null && pct > 0.0
    val avgCost = row.view.position.avgCost
    val shares = row.view.position.shares
    val price = row.view.quote?.price

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Profit target for ${row.view.position.symbol}") },
        text = {
            Column {
                Text(
                    text = "Enter the profit you want. Aurum computes the price to sell at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Target profit (%)") },
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
                if (valid) {
                    val target = TargetsRepository.sellPriceFor(avgCost, pct!!)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Sell at ${Fmt.money(target)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.gold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append("on ")
                            append(Fmt.money(avgCost))
                            append(" average cost · ")
                            append(Fmt.signedMoney(shares * avgCost * pct / 100.0))
                            append(" on ")
                            append(Fmt.qty(shares))
                            append(" shares")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                    if (price != null && price > 0.0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (price >= target) {
                                "The price is already there — ${Fmt.money(price)} today."
                            } else {
                                "${Fmt.pct((target - price) / price * 100.0)} above today's " +
                                    "${Fmt.money(price)}."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (valid) onSave(pct) },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.gold,
                    contentColor = AurumColors.bg,
                    disabledContainerColor = AurumColors.surfaceHigh,
                    disabledContentColor = AurumColors.textDim
                )
            ) { Text("Set flag") }
        },
        dismissButton = {
            Row {
                if (row.targetPct != null) {
                    TextButton(onClick = { onSave(null) }) {
                        Text("Clear", color = AurumColors.loss)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AurumColors.textDim)
                }
            }
        }
    )
}

@Composable
private fun WalletSetupDialog(
    current: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var amountText by remember {
        mutableStateOf(if (current > 0.0) Fmt.money(current).filter { it.isDigit() || it == '.' } else "")
    }
    val amount = amountText.replace(",", "").trim().toDoubleOrNull()
    val valid = amount != null && amount > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Set your total wallet") },
        text = {
            Column {
                Text(
                    text = "Enter the money you put in for investing — the capital itself, " +
                        "before any profit. Aurum reads what's invested from your ledger, " +
                        "and every sell returns its cost plus the P/L it booked to your " +
                        "liquidity. Only change this when you actually add or withdraw cash.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Total wallet ($)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (amount != null) onSave(amount) },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.gold,
                    contentColor = AurumColors.bg,
                    disabledContainerColor = AurumColors.surfaceHigh,
                    disabledContentColor = AurumColors.textDim
                )
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AurumColors.textDim) }
        }
    )
}
