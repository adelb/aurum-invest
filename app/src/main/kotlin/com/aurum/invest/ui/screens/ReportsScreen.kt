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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.PeriodReport
import com.aurum.invest.analytics.TradeLine
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun ReportsScreen(onBack: () -> Unit) {
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
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            SegmentedToggle(
                options = listOf("Daily", "Weekly", "Monthly"),
                selected = when (period) {
                    "DAY" -> 0
                    "WEEK" -> 1
                    else -> 2
                },
                onSelect = { period = listOf("DAY", "WEEK", "MONTH")[it] }
            )
        }

        val reports = when (period) {
            "DAY" -> state.daily
            "WEEK" -> state.weekly
            else -> state.monthly
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
                modifier = Modifier.weight(1f)
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
        Spacer(Modifier.height(12.dp))
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

        val best = report.bestTrade
        val worst = report.worstTrade
        if (best != null && best.realizedPl != null) {
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    text = "Best  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Text(
                    text = "${best.symbol} ${Fmt.signedMoney(best.realizedPl)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.deltaColor(best.realizedPl)
                )
                if (worst != null && worst.realizedPl != null && worst !== best) {
                    Text(
                        text = "   ·   Worst  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                    Text(
                        text = "${worst.symbol} ${Fmt.signedMoney(worst.realizedPl)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.deltaColor(worst.realizedPl)
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AurumColors.hairline, thickness = 1.dp)
                report.trades.forEach { trade ->
                    TradeRow(
                        trade = trade,
                        onEdit = { onEditTrade(trade) },
                        onDelete = { onDeleteTrade(trade) }
                    )
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
                color = AurumColors.text
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
