package com.aurum.invest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.db.TransactionEntity
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.ui.theme.AurumColors
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Correct one ledger trade: side, shares, price, fees, the trade date, and —
 * on a sell — the realized outcome itself. Shared by the Edit-position
 * screen and the Reports screen — both edit the SAME ledger rows, so a fix
 * in either place fixes positions, P/L, and reports everywhere.
 *
 * [onSave] receives (side, shares, price, fees, ts, plOverride). The
 * timestamp keeps the trade's original wall-clock time; only the calendar
 * day moves when the user picks a new date, so same-day ordering is
 * preserved. plOverride is the pinned +/- outcome in dollars for a sell —
 * null means "compute it from the ledger as usual".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTradeDialog(
    tx: TransactionEntity,
    onDismiss: () -> Unit,
    onSave: (TradeSide, Double, Double, Double, Long, Double?) -> Unit
) {
    var side by remember {
        mutableStateOf(
            if (tx.side.equals("SELL", ignoreCase = true)) TradeSide.SELL else TradeSide.BUY
        )
    }
    var sharesText by remember { mutableStateOf(Fmt.qty(tx.shares)) }
    var priceText by remember { mutableStateOf(trimZeros(tx.price)) }
    var feesText by remember { mutableStateOf(if (tx.fees > 0.0) trimZeros(tx.fees) else "") }
    var ts by remember { mutableLongStateOf(tx.ts) }
    var showDatePicker by remember { mutableStateOf(false) }
    // The pinned +/- outcome: a sign toggle plus an absolute amount, so the
    // keyboard never needs a minus key. Empty amount = automatic.
    var outcomeLoss by remember { mutableStateOf((tx.plOverride ?: 0.0) < 0.0) }
    var outcomeText by remember {
        mutableStateOf(tx.plOverride?.let { trimZeros(kotlin.math.abs(it)) } ?: "")
    }

    val shares = sharesText.replace(",", "").toDoubleOrNull()
    val price = priceText.replace(",", "").toDoubleOrNull()
    val fees = feesText.replace(",", "").toDoubleOrNull() ?: 0.0
    val outcomeAbs = outcomeText.replace(",", "").trim().toDoubleOrNull()
    val plOverride: Double? =
        if (side == TradeSide.SELL && outcomeAbs != null) {
            if (outcomeLoss) -outcomeAbs else outcomeAbs
        } else null
    val valid = shares != null && shares > 0.0 && price != null && price > 0.0 &&
        (outcomeText.isBlank() || outcomeAbs != null)

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = ts)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            colors = DatePickerDefaults.colors(containerColor = AurumColors.surface),
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { selected ->
                        // The picker returns UTC midnight of the chosen day;
                        // keep the trade's original local time-of-day so
                        // same-day ordering in the ledger is untouched.
                        val zone = ZoneId.systemDefault()
                        val newDate = Instant.ofEpochMilli(selected)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        val oldTime = Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
                        ts = newDate.atTime(oldTime).atZone(zone).toInstant().toEpochMilli()
                    }
                    showDatePicker = false
                }) { Text("Set date", color = AurumColors.gold) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = AurumColors.textDim)
                }
            }
        ) {
            DatePicker(
                state = pickerState,
                colors = DatePickerDefaults.colors(containerColor = AurumColors.surface)
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        containerColor = AurumColors.surface,
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Edit ${tx.symbol} trade") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TradeSideOption("Buy", side == TradeSide.BUY, Modifier.weight(1f)) {
                        side = TradeSide.BUY
                    }
                    TradeSideOption("Sell", side == TradeSide.SELL, Modifier.weight(1f)) {
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
                    colors = tradeFieldColors(),
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
                    colors = tradeFieldColors(),
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
                    colors = tradeFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AurumColors.surfaceHigh)
                        .clickable { showDatePicker = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Trade date",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = Fmt.dateShort(ts),
                        style = MaterialTheme.typography.labelLarge,
                        color = AurumColors.gold
                    )
                }
                if (ts != tx.ts) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Moving this trade to ${Fmt.dateShort(ts)} re-files it in that " +
                            "day's, week's, and month's reports.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                if (side == TradeSide.SELL) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Outcome (+/-)",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TradeSideOption("Profit", !outcomeLoss, Modifier.weight(1f)) {
                            outcomeLoss = false
                        }
                        TradeSideOption("Loss", outcomeLoss, Modifier.weight(1f)) {
                            outcomeLoss = true
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = outcomeText,
                        onValueChange = { outcomeText = it },
                        label = { Text("Realized P/L ($, empty = automatic)") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = tradeFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (plOverride != null) {
                            "This sell's outcome is pinned to " +
                                "${Fmt.signedMoney(plOverride)} — the position, summary, " +
                                "and reports all use it instead of the computed number."
                        } else {
                            "Leave empty and the outcome is computed from your average " +
                                "cost, as usual. Enter the broker's real number to pin it."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
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
                onClick = { if (valid) onSave(side, shares!!, price!!, fees, ts, plOverride) },
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
private fun TradeSideOption(
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
private fun tradeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    focusedBorderColor = AurumColors.gold,
    unfocusedBorderColor = AurumColors.hairline,
    focusedLabelColor = AurumColors.gold,
    unfocusedLabelColor = AurumColors.textDim,
    cursorColor = AurumColors.gold
)

/** "150.25" not "150.2500" — keeps the edit fields clean. */
private fun trimZeros(v: Double): String =
    java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
