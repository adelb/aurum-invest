package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.PeriodReport
import com.aurum.invest.analytics.TradeGrouping
import com.aurum.invest.analytics.TradeGroup
import com.aurum.invest.analytics.TradeLine
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.LiveStatTile
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun ReportsScreen(onBack: () -> Unit, onOpenAdviceHistory: () -> Unit = {}) {
    val vm: ReportsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf("WEEK") }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<TransactionEntity?>(null) }

    editing?.let { tx ->
        EditTradeDialog(
            tx = tx,
            onDismiss = { editing = null },
            onSave = { side, shares, price, fees, ts, plOverride ->
                vm.updateTrade(tx, side, shares, price, fees, ts, plOverride)
                editing = null
            }
        )
    }

    state.ledgerError?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.clearLedgerError() },
            containerColor = AurumColors.surface,
            titleContentColor = AurumColors.text,
            textContentColor = AurumColors.textDim,
            title = { Text("Ledger protected") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.clearLedgerError() }) {
                    Text("OK", color = AurumColors.gold)
                }
            }
        )
    }

    confirmDelete?.let { tx ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = AurumColors.surface,
            titleContentColor = AurumColors.text,
            textContentColor = AurumColors.textDim,
            title = { Text("Delete this trade?") },
            text = {
                Text(
                    "${tx.side.lowercase().replaceFirstChar { it.uppercase() }} " +
                        "${Fmt.qty(tx.shares)} ${tx.symbol} at ${Fmt.money(tx.price)} " +
                        "on ${Fmt.dateShort(tx.ts)} will be removed from the ledger. " +
                        "Your position, P/L, and every report recompute without it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTrade(tx)
                    confirmDelete = null
                }) { Text("Delete", color = AurumColors.loss) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel", color = AurumColors.textDim)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = AurumColors.text
                )
            }
            Text(
                text = "Reports",
                style = MaterialTheme.typography.titleLarge,
                color = AurumColors.text
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenAdviceHistory) {
                Text(
                    text = "Advice history",
                    style = MaterialTheme.typography.labelLarge,
                    color = AurumColors.gold
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            WalletSummaryCard(state = state)
            Spacer(Modifier.height(14.dp))
            SegmentedToggle(
                options = listOf("Daily", "Weekly", "Monthly", "Yearly"),
                selected = when (period) {
                    "DAY" -> 0
                    "WEEK" -> 1
                    "MONTH" -> 2
                    else -> 3
                },
                onSelect = { period = listOf("DAY", "WEEK", "MONTH", "YEAR")[it] }
            )
        }

        val reports = when (period) {
            "DAY" -> state.daily
            "WEEK" -> state.weekly
            "MONTH" -> state.monthly
            else -> state.yearly
        }

        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AurumColors.gold)
            }
        } else if (reports.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = "No trades yet",
                    message = "Reports build themselves as you buy and sell."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(reports, key = { it.periodKey }) { report ->
                    ReportCard(
                        report = report,
                        onEditTrade = { line ->
                            vm.transaction(line.txId)?.let { editing = it }
                        },
                        onDeleteTrade = { line ->
                            vm.transaction(line.txId)?.let { confirmDelete = it }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletSummaryCard(state: ReportsState) {
    AurumCard(modifier = Modifier.fillMaxWidth()) {
        val w = state.wallet
        if (w.configured) {
            // Two per row, not four across: at exact cents a quarter-width
            // column truncates the value, and a truncated balance is worse
            // than a taller card.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiveStatTile(
                    label = "Wallet",
                    value = w.total,
                    modifier = Modifier.weight(1f)
                )
                LiveStatTile(
                    label = "Invested",
                    value = w.invested,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiveStatTile(
                    label = "Liquidity",
                    value = w.liquidity,
                    modifier = Modifier.weight(1f),
                    baseColor = if (w.shortfall) AurumColors.loss else AurumColors.text
                )
                LiveStatTile(
                    label = "Net worth",
                    value = w.netWorth,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            // Where both figures come from. A sell returns the shares' cost
            // basis AND the P/L booked on them, so liquidity moves by the full
            // proceeds — this line names the realized part of it.
            Text(
                text = "Liquidity = wallet − invested " +
                    (if (w.realizedPl < 0) "− " else "+ ") +
                    Fmt.moneyExact(kotlin.math.abs(w.realizedPl)) +
                    " realized P/L booked by your sells · " +
                    "net worth = liquidity + holdings at market.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            if (state.unpricedCount > 0) {
                Text(
                    text = "${state.unpricedCount} holding" +
                        (if (state.unpricedCount == 1) " has" else "s have") +
                        " no live price — counted at cost, so net worth is approximate.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
            }
            if (w.shortfall) {
                Text(
                    text = "Your ledger has more deployed than the stated wallet covers — " +
                        "raise the total from the Portfolio tab if you've added money.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
            }
        } else {
            Text(
                text = "Set your total wallet from the Portfolio tab to see invested vs. " +
                    "liquidity here.",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun ReportCard(
    report: PeriodReport,
    onEditTrade: (TradeLine) -> Unit,
    onDeleteTrade: (TradeLine) -> Unit
) {
    var expanded by rememberSaveable(report.periodKey) { mutableStateOf(false) }

    AurumCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        onClick = { expanded = !expanded }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = report.label,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            DeltaMoney(
                value = report.realizedPl,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = "Realized P/L",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim,
            modifier = Modifier.align(Alignment.End)
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Accumulated P/L",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                modifier = Modifier.weight(1f)
            )
            DeltaMoney(
                value = report.accumulatedPl,
                style = MaterialTheme.typography.titleSmall
            )
        }
        Spacer(Modifier.height(12.dp))

        val best = report.bestTrade
        val worst = report.worstTrade

        AnimatedVisibility(visible = expanded) {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        label = "Buys",
                        value = "${report.buysCount} · ${Fmt.money(report.buysTotal)}",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Sells",
                        value = "${report.sellsCount} · ${Fmt.money(report.sellsTotal)}",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Trades",
                        value = "${report.trades.size}",
                        modifier = Modifier.weight(0.6f)
                    )
                }
                if (best != null && best.realizedPl != null) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BestWorstLine(label = "Best", trade = best)
                        if (worst != null && worst.realizedPl != null && worst !== best) {
                            BestWorstLine(label = "Worst", trade = worst)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AurumColors.hairline, thickness = 1.dp)
                if (report.grouping == TradeGrouping.NONE) {
                    report.trades.forEach { trade ->
                        TradeRow(
                            trade = trade,
                            onEdit = { onEditTrade(trade) },
                            onDelete = { onDeleteTrade(trade) }
                        )
                    }
                } else {
                    report.groups.forEach { group ->
                        TradeGroupRow(
                            group = group,
                            onEditTrade = onEditTrade,
                            onDeleteTrade = onDeleteTrade
                        )
                    }
                }
                Text(
                    text = "Tap a trade to correct it — position, P/L, and every report " +
                        "recompute from the fixed ledger.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (expanded) "Hide trades" else "Show ${report.trades.size} trades",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.gold
        )
    }
}

/** One finer bucket (a day inside a week, a week inside a month, a month inside a year). */
@Composable
private fun TradeGroupRow(
    group: TradeGroup,
    onEditTrade: (TradeLine) -> Unit,
    onDeleteTrade: (TradeLine) -> Unit
) {
    var open by rememberSaveable(group.key) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${group.trades.size} trade${if (group.trades.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            DeltaMoney(value = group.realizedPl, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (open) "︿" else "﹀",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        AnimatedVisibility(visible = open) {
            Column(modifier = Modifier.padding(start = 8.dp)) {
                group.trades.forEach { trade ->
                    TradeRow(
                        trade = trade,
                        onEdit = { onEditTrade(trade) },
                        onDelete = { onDeleteTrade(trade) }
                    )
                }
            }
        }
        HorizontalDivider(color = AurumColors.hairline, thickness = 1.dp)
    }
}


@Composable
private fun TradeRow(trade: TradeLine, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onEdit() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PillTag(
            text = if (trade.side == TradeSide.BUY.name) "Buy" else "Sell",
            color = if (trade.side == TradeSide.BUY.name) AurumColors.gain else AurumColors.loss
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${trade.symbol}  ·  ${Fmt.qty(trade.shares)} @ ${Fmt.money(trade.price)}",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = Fmt.dateShort(trade.ts),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        trade.realizedPl?.let {
            Column(horizontalAlignment = Alignment.End) {
                DeltaMoney(value = it, style = MaterialTheme.typography.labelMedium)
                if (trade.plOverridden) {
                    Text(
                        text = "pinned",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.gold
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = "Edit ${trade.symbol} trade",
                tint = AurumColors.textDim,
                modifier = Modifier.size(15.dp)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Delete ${trade.symbol} trade",
                tint = AurumColors.textDim,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun BestWorstLine(label: String, trade: TradeLine) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label  ",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Text(
            text = "${trade.symbol} ${Fmt.signedMoney(trade.realizedPl ?: 0.0)}",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.deltaColor(trade.realizedPl ?: 0.0),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
