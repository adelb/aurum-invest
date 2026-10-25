package com.aurum.invest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.GoldEngine
import com.aurum.invest.analytics.GoldKind
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.ButtonTone
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.Hint
import com.aurum.invest.ui.components.IconSquare
import com.aurum.invest.ui.components.Kv
import com.aurum.invest.ui.components.KvGrid
import com.aurum.invest.ui.components.Lead
import com.aurum.invest.ui.components.SectionBand
import com.aurum.invest.ui.components.SectionTitle
import com.aurum.invest.ui.components.Sentence
import com.aurum.invest.ui.components.Tone
import com.aurum.invest.ui.components.TossButton
import com.aurum.invest.ui.components.TossTopBar
import com.aurum.invest.ui.components.pressScale
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs

@Composable
fun GoldLotScreen(lotId: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val vm: GoldViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val lot = state.lots.firstOrNull { it.id == lotId }
    var selling by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {
        TossTopBar(title = lot?.let { it.name.ifBlank { defaultLotName(it) } }, onBack = onBack)
        if (lot == null) {
            EmptyState(
                title = if (state.lots.isEmpty() && state.loading) "Loading…" else "This purchase is gone",
                message = "It may have been deleted."
            )
            return@Column
        }
        val list = state.list
        val value = list?.let { GoldEngine.valueLot(lot, it, state.activeFactor, state.shop) }
        val unit = if (lot.kind.perPiece) "piece" else "gram"
        val what = when (lot.kind) {
            GoldKind.RASHADI, GoldKind.ENGLISH -> "${karatTag(lot)} · ${Fmt.qty(lot.qty)} piece(s), ${Fmt.qty(lot.grams)} g"
            GoldKind.BAR -> "${karatTag(lot)} bar · ${Fmt.qty(lot.grams)} g"
            GoldKind.JEWELRY -> "${karatTag(lot)} jewelry · ${Fmt.qty(lot.grams)} g"
        }

        if (selling) {
            SellDialog(
                unit = unit,
                suggested = value?.unitBuy,
                onDismiss = { selling = false },
                onSave = {
                    vm.markSold(lot.id, it)
                    selling = false
                }
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = AurumColors.surface,
                shape = RoundedCornerShape(24.dp),
                titleContentColor = AurumColors.text,
                textContentColor = AurumColors.textSub,
                title = { Text("Delete this purchase?") },
                text = { Text("It leaves your gold book entirely. To record that you sold it, use “I sold it” instead — that keeps the profit on the record.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        vm.delete(lot.id)
                        onBack()
                    }) { Text("Delete", color = AurumColors.loss) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = AurumColors.textDim) }
                }
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                "$what · bought ${Fmt.dateFull(lot.boughtAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(8.dp))
            val noun = lot.name.ifBlank { "gold" }.lowercase()
            if (lot.sold) {
                val pl = (lot.proceeds ?: 0.0) - lot.paid
                Sentence(if (pl >= 0) "You sold your $noun for a profit of" else "You sold your $noun at a loss of")
                Text(
                    Fmt.jod(abs(pl)),
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp),
                    color = AurumColors.deltaColor(pl)
                )
                Spacer(Modifier.height(16.dp))
                KvGrid(
                    listOf(
                        Kv("You paid", Fmt.jod(lot.paid), note = "${Fmt.num3(lot.pricePerUnit)} gold + ${Fmt.num3(lot.makingPerUnit)} making per $unit"),
                        Kv("The shop paid you", Fmt.jod(lot.proceeds ?: 0.0), note = "${Fmt.num3(lot.soldPricePerUnit ?: 0.0)} per $unit"),
                        Kv("Return", Fmt.signedPct(if (lot.paid > 0) pl / lot.paid * 100 else 0.0), valueColor = AurumColors.deltaColor(pl)),
                        Kv("Sold on", Fmt.dateFull(lot.soldAt ?: 0L))
                    )
                )
                Spacer(Modifier.height(20.dp))
                TossButton("Undo the sale", onClick = { vm.reopen(lot.id) }, tone = ButtonTone.SOFT)
                Spacer(Modifier.height(8.dp))
                TossButton("Delete this purchase", onClick = { confirmDelete = true }, tone = ButtonTone.SOFT)
                Spacer(Modifier.height(32.dp))
                return@Column
            }

            if (value == null) {
                Lead("Today’s Syndicate list hasn’t loaded, so this purchase can’t be valued yet.")
            } else {
                val up = value.pl >= 0
                Sentence("Your $noun is ${if (up) "up" else "down"}")
                AnimatedMoney(
                    value = abs(value.pl),
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp, lineHeight = 44.sp),
                    baseColor = if (up) AurumColors.gain else AurumColors.loss,
                    format = { Fmt.jod(it) },
                    epsilon = 0.0005
                )
                Text("if you sold today", style = MaterialTheme.typography.headlineSmall, color = AurumColors.text)
                Spacer(Modifier.height(18.dp))
                KvGrid(
                    listOf(
                        Kv("You paid", Fmt.jod(lot.paid), note = "${Fmt.num3(lot.pricePerUnit)} gold + ${Fmt.num3(lot.makingPerUnit)} making per $unit"),
                        Kv("Worth today", Fmt.jod(value.value), note = "at the ${Fmt.num3(value.unitBuy)} buy price"),
                        Kv("Return", Fmt.signedPct(value.plPct), valueColor = AurumColors.deltaColor(value.pl)),
                        Kv("Making charges", Fmt.jod(lot.making), note = "never refunded")
                    )
                )

                SectionBand()
                SectionTitle(title = "What it takes to break even")
                Spacer(Modifier.height(12.dp))
                val rise = GoldEngine.riseToBreakEvenPct(lot, value.unitBuy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(karatTag(lot), tone = Tone.GOLD)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Buy price of ${Fmt.num3(lot.breakEvenUnit)} per $unit", style = MaterialTheme.typography.titleMedium, color = AurumColors.text)
                        Text(
                            if (rise > 0) "${Fmt.pct(rise)} above today’s" else "Already below today’s — you’re ahead",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (rise > 0) AurumColors.textDim else AurumColors.gain
                        )
                    }
                }
                val world = list?.let { GoldEngine.worldForUnitBuy(lot.breakEvenUnit, it, lot.kind, lot.karat) }
                Hint(
                    buildString {
                        if (world != null) append("That’s world gold near ${Fmt.money(world)} an ounce. ")
                        append("You’ve recovered ${Fmt.pct(value.value / lot.paid * 100)} of what you paid.")
                    }
                )

                val dayOne = list?.let { GoldEngine.dayOne(lot, it) }
                if (dayOne != null && lot.paid > 0) {
                    SectionBand()
                    SectionTitle(title = "The day you bought it")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You started about ${Fmt.pct(abs(dayOne.startPct))} under water: roughly " +
                            "${Fmt.jod(dayOne.spreadCost)} of sell-to-buy spread and ${Fmt.jod(dayOne.makingCost)} " +
                            "of making charges." +
                            if (lot.kind == GoldKind.JEWELRY) " Jewelry carries both; a 24K bar carries far less." else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AurumColors.textSub
                    )
                    Hint("The spread is measured at today’s percentage for the same line.")
                }

                val shadow = GoldEngine.shadowValue(listOf(lot), state.spyDaily)
                if (shadow != null) {
                    SectionBand()
                    val vs = value.value - shadow
                    Row(verticalAlignment = Alignment.Top) {
                        IconSquare("SPY", tone = if (vs >= 0) Tone.GAIN else Tone.LOSS)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                if (vs >= 0) "Gold is ahead of SPY by ${Fmt.jod(vs)}" else "SPY would be ahead by ${Fmt.jod(-vs)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = AurumColors.text
                            )
                            Text(
                                "The same ${Fmt.jod(lot.paid)} in SPY on ${Fmt.dateFull(lot.boughtAt)} is worth ${Fmt.jod(shadow)} now.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AurumColors.textDim
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TossButton("Edit purchase", onClick = { onEdit(lot.id) }, tone = ButtonTone.SOFT, modifier = Modifier.weight(1f))
                TossButton("I sold it", onClick = { selling = true }, modifier = Modifier.weight(1f))
            }
            Hint("Recording a sale books the real profit at the price the shop paid you — the Syndicate buy, or your own.")
            Spacer(Modifier.height(12.dp))
            Text(
                "Delete this purchase",
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.loss,
                modifier = Modifier
                    .pressScale { confirmDelete = true }
                    .padding(vertical = 12.dp)
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SellDialog(
    unit: String,
    suggested: Double?,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var text by remember { mutableStateOf(suggested?.let { Fmt.num3(it).replace(",", "") } ?: "") }
    val value = text.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        shape = RoundedCornerShape(24.dp),
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textSub,
        title = { Text("What did the shop pay you?") },
        text = {
            Column {
                Text("Per $unit, in dinars. Filled with today’s buy price — change it to what you were actually paid.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AurumColors.accent,
                        unfocusedBorderColor = AurumColors.surfaceTop,
                        focusedTextColor = AurumColors.text,
                        unfocusedTextColor = AurumColors.text,
                        cursorColor = AurumColors.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null) {
                Text("Record sale", color = if (value != null) AurumColors.accent else AurumColors.textFaint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AurumColors.textDim) }
        }
    )
}
