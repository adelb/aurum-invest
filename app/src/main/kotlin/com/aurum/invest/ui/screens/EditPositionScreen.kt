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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.db.TxSide
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
    var showSplitDialog by remember { mutableStateOf(false) }

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

    if (showSplitDialog) {
        SplitDialog(
            symbol = state.symbol,
            onDismiss = { showSplitDialog = false },
            onSave = { ratio, ts ->
                vm.recordSplit(ratio, ts)
                showSplitDialog = false
            }
        )
    }

    state.editError?.let { error ->
        AlertDialog(
            onDismissRequest = { vm.clearEditError() },
            containerColor = AurumColors.surface,
            titleContentColor = AurumColors.text,
            textContentColor = AurumColors.textDim,
            title = { Text("Edit rejected") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { vm.clearEditError() }) {
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
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${state.trades.size} trade${if (state.trades.size == 1) "" else "s"} on record",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
            if (state.ledgerGap > 1e-6) {
                item {
                    AurumCard {
                        Text(
                            text = "Incomplete history",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.loss
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${Fmt.qty(state.ledgerGap)} ${state.symbol} " +
                                "${if (state.ledgerGap == 1.0) "share is" else "shares are"} " +
                                "sold below with no buy behind them, so the position and its " +
                                "realized P/L leave those shares out. Add the missing buy — or " +
                                "reduce the sell to what you really sold — and the gap closes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }

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
                        onEdit = { if (tx.side != TxSide.SPLIT) editing = tx },
                        onDelete = { confirmDelete = tx }
                    )
                }
                item {
                    TextButton(onClick = { showSplitDialog = true }) {
                        Text(
                            text = "Record a stock split…",
                            style = MaterialTheme.typography.labelLarge,
                            color = AurumColors.gold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeRow(tx: TransactionEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val isBuy = tx.side.equals("BUY", ignoreCase = true)
    val isSplit = tx.side == TxSide.SPLIT
    AurumCard(onClick = onEdit, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillTag(
                text = if (isSplit) "Split" else if (isBuy) "Buy" else "Sell",
                color = if (isSplit) AurumColors.gold else if (isBuy) AurumColors.gain else AurumColors.loss
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSplit) "${Fmt.qty(tx.shares)}-for-1 split"
                    else "${Fmt.qty(tx.shares)} @ ${Fmt.money(tx.price)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = Fmt.dateShort(tx.ts) +
                        (if (tx.fees > 0.0) " · fees ${Fmt.money(tx.fees)}" else "") +
                        (if (tx.source == "BANK") " · from bank" else "") +
                        (if (tx.currency != "USD") " · ${tx.currency} @ ${Fmt.qty(tx.fxRate)}" else "") +
                        (if (tx.note.isNotBlank()) " · ${tx.note}" else "") +
                        (tx.plOverride?.let {
                            " · outcome pinned ${Fmt.signedMoney(it)}"
                        } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!isSplit) {
                Text(
                    text = Fmt.money(tx.shares * tx.price + if (isBuy) tx.fees else -tx.fees),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

/** Records a stock split as a ledger row — the position engine scales shares and cost basis. */
@Composable
private fun SplitDialog(
    symbol: String,
    onDismiss: () -> Unit,
    onSave: (ratio: Double, ts: Long) -> Unit
) {
    var ratioText by remember { mutableStateOf("") }
    val ratio = ratioText.trim().toDoubleOrNull()
    val valid = ratio != null && ratio > 0.0 && ratio != 1.0
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        containerColor = AurumColors.surface,
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Record $symbol split") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Enter the new-shares-per-old-share ratio: 4 for a 4-for-1 split, " +
                        "0.25 for a 1-for-4 reverse split. Shares multiply and average cost " +
                        "divides by this ratio from today onward."
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = ratioText,
                    onValueChange = { ratioText = it },
                    label = { Text("Ratio") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (ratio != null) onSave(ratio, System.currentTimeMillis()) },
                enabled = valid
            ) { Text("Record split", color = if (valid) AurumColors.gold else AurumColors.textDim) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AurumColors.textDim) }
        }
    )
}

// The trade-edit dialog itself is shared with the Reports screen — see
// TradeEditDialog.kt in this package.
