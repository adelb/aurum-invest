package com.aurum.invest.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

/**
 * Edit a holding by correcting the trades behind it. Every position number in
 * the app is derived from this ledger, so a fix here flows everywhere.
 */
@Composable
fun EditPositionScreen(
    symbol: String,
    onBack: () -> Unit,
    onAddTrade: (String) -> Unit
) {
    val vm: EditPositionViewModel = viewModel()
    LaunchedEffect(symbol) { vm.start(symbol) }
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<TransactionEntity?>(null) }

    // A SPLIT row (from a v6–v9 migrated ledger) is a ratio, not a trade —
    // the buy/sell edit dialog would silently rewrite it into a buy.
    editing?.takeIf { !it.side.equals("SPLIT", ignoreCase = true) }?.let { tx ->
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
                        "Your position and P/L recompute without it."
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Edit ${state.symbol}",
                    style = MaterialTheme.typography.titleLarge,
                    color = AurumColors.text
                )
                Text(
                    text = "${state.trades.size} trade${if (state.trades.size == 1) "" else "s"} on record",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            IconButton(onClick = { onAddTrade(state.symbol) }) {
                Icon(Icons.Rounded.Add, contentDescription = "Add a trade", tint = AurumColors.gold)
            }
        }

        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AurumColors.gold)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            state.position?.let { position ->
                item {
                    AurumCard {
                        Text(
                            text = "Position as recorded",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.textDim
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatTile(
                                label = "Shares",
                                value = Fmt.qty(position.shares),
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Avg cost",
                                value = Fmt.money(position.avgCost),
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Invested",
                                value = Fmt.money(position.investedCost),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "These are computed from the trades below — correct a trade " +
                                "and every number in the app follows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }

            if (state.trades.isEmpty()) {
                item {
                    EmptyState(
                        title = "No trades for ${state.symbol}",
                        message = "Add a trade to start tracking this position again.",
                        actionLabel = "Add a trade",
                        onAction = { onAddTrade(state.symbol) }
                    )
                }
            } else {
                item { SectionHeader(title = "Trades") }
                items(state.trades, key = { it.id }) { tx ->
                    TradeRow(
                        tx = tx,
                        onEdit = { editing = tx },
                        onDelete = { confirmDelete = tx }
                    )
                }
            }
        }
    }
}

@Composable
private fun TradeRow(tx: TransactionEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val isBuy = tx.side.equals("BUY", ignoreCase = true)
    AurumCard(onClick = onEdit, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillTag(
                text = if (isBuy) "Buy" else "Sell",
                color = if (isBuy) AurumColors.gain else AurumColors.loss
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${Fmt.qty(tx.shares)} @ ${Fmt.money(tx.price)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = Fmt.dateShort(tx.ts) +
                        (if (tx.fees > 0.0) " · fees ${Fmt.money(tx.fees)}" else "") +
                        (if (tx.source == "BANK") " · from bank" else "") +
                        (tx.plOverride?.let {
                            " · outcome pinned ${Fmt.signedMoney(it)}"
                        } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            Text(
                text = Fmt.money(tx.shares * tx.price + if (isBuy) tx.fees else -tx.fees),
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete trade",
                    tint = AurumColors.textDim,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

// The trade-edit dialog itself is shared with the Reports screen — see
// TradeEditDialog.kt in this package.
