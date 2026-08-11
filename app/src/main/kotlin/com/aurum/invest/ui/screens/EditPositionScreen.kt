package com.aurum.invest.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.TradeSide
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

    editing?.let { tx ->
        EditTradeDialog(
            tx = tx,
            onDismiss = { editing = null },
            onSave = { side, shares, price, fees ->
                vm.updateTrade(tx, side, shares, price, fees, tx.ts)
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
                        (if (tx.source == "BANK") " · from bank" else ""),
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

/** Correct one trade: side, shares, price and fees. */
@Composable
private fun EditTradeDialog(
    tx: TransactionEntity,
    onDismiss: () -> Unit,
    onSave: (TradeSide, Double, Double, Double) -> Unit
) {
    var side by remember {
        mutableStateOf(
            if (tx.side.equals("SELL", ignoreCase = true)) TradeSide.SELL else TradeSide.BUY
        )
    }
    var sharesText by remember { mutableStateOf(Fmt.qty(tx.shares)) }
    var priceText by remember { mutableStateOf(trimZeros(tx.price)) }
    var feesText by remember { mutableStateOf(if (tx.fees > 0.0) trimZeros(tx.fees) else "") }

    val shares = sharesText.replace(",", "").toDoubleOrNull()
    val price = priceText.replace(",", "").toDoubleOrNull()
    val fees = feesText.replace(",", "").toDoubleOrNull() ?: 0.0
    val valid = shares != null && shares > 0.0 && price != null && price > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Edit ${tx.symbol} trade") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SideOption("Buy", side == TradeSide.BUY, Modifier.weight(1f)) {
                        side = TradeSide.BUY
                    }
                    SideOption("Sell", side == TradeSide.SELL, Modifier.weight(1f)) {
                        side = TradeSide.SELL
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = sharesText,
                    onValueChange = { sharesText = it },
                    label = { Text("Shares") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = editFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Price per share ($)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = editFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = feesText,
                    onValueChange = { feesText = it },
                    label = { Text("Fees ($, optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = editFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (valid) {
                    Spacer(Modifier.height(10.dp))
                    val total = shares!! * price!! + if (side == TradeSide.BUY) fees else -fees
                    Text(
                        text = if (side == TradeSide.BUY) "Total cost ${Fmt.money(total)}"
                        else "Net proceeds ${Fmt.money(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (valid) onSave(side, shares!!, price!!, fees) },
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

@Composable
private fun SideOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AurumColors.gold else AurumColors.surfaceHigh)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AurumColors.bg else AurumColors.textDim
        )
    }
}

@Composable
private fun editFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    focusedBorderColor = AurumColors.gold,
    unfocusedBorderColor = AurumColors.hairline,
    focusedLabelColor = AurumColors.gold,
    unfocusedLabelColor = AurumColors.textDim,
    cursorColor = AurumColors.gold
)

/** "150.25" not "150.2500" — keeps the edit fields clean. */
private fun trimZeros(v: Double): String {
    val s = java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    return s
}
