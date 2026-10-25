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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.GoldKind
import com.aurum.invest.analytics.KaratPrice
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.remote.JordanGoldClient
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.Hint
import com.aurum.invest.ui.components.Kv
import com.aurum.invest.ui.components.KvGrid
import com.aurum.invest.ui.components.Lead
import com.aurum.invest.ui.components.ScrollTabs
import com.aurum.invest.ui.components.SectionBand
import com.aurum.invest.ui.components.SectionTitle
import com.aurum.invest.ui.components.Sentence
import com.aurum.invest.ui.components.TossButton
import com.aurum.invest.ui.components.ButtonTone
import com.aurum.invest.ui.components.TossTopBar
import com.aurum.invest.ui.theme.AurumColors

/** One choosable line of the list. */
private data class PriceLine(val label: String, val kind: GoldKind, val karat: Int, val unit: String)

private val LINES = listOf(
    PriceLine("21K", GoldKind.JEWELRY, 21, "gram"),
    PriceLine("24K", GoldKind.BAR, 24, "gram"),
    PriceLine("22K", GoldKind.JEWELRY, 22, "gram"),
    PriceLine("18K", GoldKind.JEWELRY, 18, "gram"),
    PriceLine("Rashadi", GoldKind.RASHADI, 21, "piece"),
    PriceLine("English", GoldKind.ENGLISH, 22, "piece")
)

@Composable
fun GoldPricesScreen(onBack: () -> Unit) {
    val vm: GoldViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var pick by rememberSaveable { mutableStateOf(0) }
    var editingShop by remember { mutableStateOf(false) }
    val line = LINES[pick]
    val list = state.list

    if (editingShop && !line.kind.perPiece) {
        ShopQuoteDialog(
            karat = line.karat,
            current = state.shop?.takeIf { it.karat == line.karat && it.forListAt == list?.listedAt }?.buyPerGram,
            onDismiss = { editingShop = false },
            onSave = {
                vm.setShopQuote(line.karat, it)
                editingShop = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {
        TossTopBar(title = "Jordan gold prices", onBack = onBack)
        ScrollTabs(options = LINES.map { it.label }, selected = pick, onSelect = { pick = it })
        if (list == null) {
            EmptyState(
                title = if (state.loading) "Reading today’s list…" else "The list couldn’t be reached",
                message = "Aurum reads the Jewellers’ Syndicate list as it is republished each day. " +
                    "Nothing is shown until it arrives — no price is ever guessed.",
                actionLabel = if (state.loading) null else "Try again",
                onAction = { vm.refresh(force = true) }
            )
            return@Column
        }
        val price: KaratPrice? = when (line.kind) {
            GoldKind.RASHADI -> list.rashadi
            GoldKind.ENGLISH -> list.english
            else -> list.karats[line.karat]
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            if (price == null) {
                Lead("The list of ${listLabel(list)} has no ${line.label} line.")
                return@Column
            }
            val name = if (line.kind.perPiece) "A ${line.kind.label.lowercase()}" else "${line.label} gold"
            Sentence("$name sells at ${Fmt.jod(price.sell)} a ${line.unit} today", startDelayMs = 0)
            Lead(
                if (line.kind.perPiece) "${line.kind.fixedKarat}K, ${Fmt.qty(line.kind.gramsEach)} g each · " +
                    "priced per piece, before making."
                else "Per gram, before making charges.",
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            KvGrid(
                items = listOf(
                    Kv("Shop sells you", Fmt.num3(price.sell)),
                    Kv("Shop buys back", Fmt.num3(price.buy)),
                    Kv("Spread", Fmt.pct(price.spreadPct), note = "lost the moment you buy"),
                    Kv(
                        "Live estimate (buy)",
                        live = price.buy * state.factor,
                        liveFormat = { Fmt.num3(it) },
                        note = if (kotlin.math.abs(state.driftPct) < 0.005) "world gold unchanged"
                        else "world gold ${Fmt.signedPct(state.driftPct)} since the list"
                    )
                )
            )
            Hint(
                "Syndicate list of ${listLabel(list)} Amman. The estimate moves with world gold" +
                    (state.world?.let { " (${Fmt.money(it.price)}/oz)" } ?: "") + " until the next list."
            )
            if (state.listStatus == FeedStatus.STALE) {
                Hint("Couldn’t reach the source just now — this is the list saved ${Fmt.timeAgo(list.fetchedAt)}.")
            }

            SectionBand()
            SectionTitle(title = "The whole list", subtitle = "JOD, before making charges")
            Spacer(Modifier.height(10.dp))
            AurumCard(modifier = Modifier.fillMaxWidth()) {
                TableRow("Karat", "Sell", "Buy", "Spread", header = true)
                val rows = listOf(24, 22, 21, 18).mapNotNull { k -> list.karats[k]?.let { "${k}K / g" to it } } +
                    listOfNotNull(
                        list.rashadi?.let { "Rashadi" to it },
                        list.english?.let { "English" to it }
                    )
                rows.forEach { (label, p) ->
                    HorizontalDivider(color = AurumColors.hairline)
                    TableRow(label, Fmt.num3(p.sell), Fmt.num3(p.buy), Fmt.num2(p.spreadPct) + "%")
                }
            }

            if (!line.kind.perPiece) {
                SectionBand()
                val shop = state.shop?.takeIf { it.karat == line.karat && it.forListAt == list.listedAt }
                SectionTitle(
                    title = "Your shop quoted a different price?",
                    subtitle = if (shop != null) "Using your shop’s ${line.label} buy price of ${Fmt.num3(shop.buyPerGram)} until the next list"
                    else "Aurum uses yours for your ${line.label} purchases until the next list"
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TossButton(
                        text = if (shop != null) "Change" else "Use my shop’s price",
                        onClick = { editingShop = true },
                        tone = ButtonTone.SOFT,
                        small = true
                    )
                    if (shop != null) {
                        TossButton(
                            text = "Back to the Syndicate",
                            onClick = { vm.setShopQuote(line.karat, null) },
                            tone = ButtonTone.SOFT,
                            small = true
                        )
                    }
                }
            }

            SectionBand()
            SectionTitle(title = "How these prices are set")
            Spacer(Modifier.height(8.dp))
            Text(
                "The Jewellers’ Syndicate builds the list from world gold in dollars, the 0.709 dinar peg " +
                    "and each karat’s purity, plus a margin. Shops add making charges on top of the sell " +
                    "price — and never refund them when you sell back.",
                style = MaterialTheme.typography.bodyLarge,
                color = AurumColors.textSub
            )
            Hint("Source: the Syndicate list as republished on ${JordanGoldClient.SOURCE_LABEL} · world gold from COMEX (GC=F).")
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TableRow(a: String, b: String, c: String, d: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    else MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val color = if (header) AurumColors.textDim else AurumColors.text
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(a, style = style, color = color, modifier = Modifier.weight(1.2f))
        Text(b, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(c, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(d, style = style, color = if (header) color else AurumColors.textDim, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
    }
}

@Composable
private fun ShopQuoteDialog(
    karat: Int,
    current: Double?,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit
) {
    var text by remember { mutableStateOf(current?.let { Fmt.trimNumber(it) } ?: "") }
    val value = text.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        shape = RoundedCornerShape(24.dp),
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textSub,
        title = { Text("Your shop’s ${karat}K buy price") },
        text = {
            Column {
                Text("What your shop says it would pay per gram today. It replaces the Syndicate’s buy price for your ${karat}K purchases until the next list.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("JOD per gram") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(14.dp),
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
            TextButton(onClick = { onSave(value) }, enabled = value != null) {
                Text("Use it", color = if (value != null) AurumColors.accent else AurumColors.textFaint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AurumColors.textDim) }
        }
    )
}
