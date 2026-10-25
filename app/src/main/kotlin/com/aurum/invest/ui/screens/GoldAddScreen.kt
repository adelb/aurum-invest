package com.aurum.invest.ui.screens

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.AurumApp
import com.aurum.invest.analytics.GoldEngine
import com.aurum.invest.analytics.GoldKind
import com.aurum.invest.analytics.GoldLot
import com.aurum.invest.analytics.SyndicateList
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.Hint
import com.aurum.invest.ui.components.NumberKeypad
import com.aurum.invest.ui.components.Sentence
import com.aurum.invest.ui.components.TossButton
import com.aurum.invest.ui.components.TossChip
import com.aurum.invest.ui.components.TossTopBar
import com.aurum.invest.ui.components.applyKey
import com.aurum.invest.ui.components.pressScale
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GoldField { QTY, PRICE, MAKING }

data class GoldForm(
    val id: Long = 0L,
    val loaded: Boolean = false,
    val kind: GoldKind = GoldKind.JEWELRY,
    val karat: Int = 21,
    val qty: String = "",
    val price: String = "",
    val making: String = "",
    val name: String = "",
    val boughtAt: Long = System.currentTimeMillis(),
    val focus: GoldField = GoldField.QTY,
    val list: SyndicateList? = null,
    val saving: Boolean = false
) {
    val effectiveKarat: Int get() = kind.fixedKarat ?: karat

    /** Today's sell price for this line — what an empty price field means. */
    val listSell: Double?
        get() = list?.let { GoldEngine.lineFor(it, kind, effectiveKarat)?.sell }

    val qtyValue: Double? get() = qty.toDoubleOrNull()?.takeIf { it > 0.0 }
    val priceValue: Double? get() = price.toDoubleOrNull()?.takeIf { it > 0.0 } ?: listSell
    val makingValue: Double get() = making.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    val valid: Boolean get() = qtyValue != null && priceValue != null
}

class GoldAddViewModel(app: Application) : AndroidViewModel(app) {
    private val gold = (app as AurumApp).container.gold
    private val _form = MutableStateFlow(GoldForm())
    val form: StateFlow<GoldForm> = _form.asStateFlow()

    fun start(id: Long) {
        if (_form.value.loaded) return
        _form.update { it.copy(loaded = true) }
        viewModelScope.launch {
            val list = gold.list().list
            _form.update { it.copy(list = list) }
            if (id > 0L) {
                val lot = gold.lot(id) ?: return@launch
                _form.update {
                    it.copy(
                        id = lot.id,
                        kind = lot.kind,
                        karat = lot.karat,
                        qty = Fmt.trimNumber(lot.qty),
                        price = Fmt.trimNumber(lot.pricePerUnit),
                        making = if (lot.makingPerUnit > 0.0) Fmt.trimNumber(lot.makingPerUnit) else "",
                        name = lot.name,
                        boughtAt = lot.boughtAt
                    )
                }
            }
        }
    }

    fun setKind(kind: GoldKind) = _form.update { it.copy(kind = kind, price = "", making = "") }
    fun setKarat(karat: Int) = _form.update { it.copy(karat = karat, price = "") }
    fun setName(name: String) = _form.update { it.copy(name = name.take(40)) }
    fun setDate(ts: Long) = _form.update { it.copy(boughtAt = ts) }
    fun focus(field: GoldField) = _form.update { it.copy(focus = field) }

    fun press(key: String) = _form.update {
        when (it.focus) {
            GoldField.QTY -> it.copy(qty = applyKey(it.qty, key, maxDecimals = if (it.kind.perPiece) 0 else 3))
            GoldField.PRICE -> it.copy(price = applyKey(it.price, key))
            GoldField.MAKING -> it.copy(making = applyKey(it.making, key))
        }
    }

    fun save(onDone: () -> Unit) {
        val f = _form.value
        val qty = f.qtyValue ?: return
        val price = f.priceValue ?: return
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val existing = if (f.id > 0L) gold.lot(f.id) else null
            gold.save(
                GoldLot(
                    id = f.id,
                    kind = f.kind,
                    karat = f.effectiveKarat,
                    qty = qty,
                    pricePerUnit = price,
                    makingPerUnit = f.makingValue,
                    boughtAt = f.boughtAt,
                    name = f.name.trim(),
                    note = existing?.note.orEmpty(),
                    soldAt = existing?.soldAt,
                    soldPricePerUnit = existing?.soldPricePerUnit
                )
            )
            onDone()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GoldAddScreen(lotId: Long, onDone: () -> Unit) {
    val vm: GoldAddViewModel = viewModel()
    LaunchedEffect(lotId) { vm.start(lotId) }
    val f by vm.form.collectAsStateWithLifecycle()
    var pickDate by remember { mutableStateOf(false) }

    if (pickDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = f.boughtAt)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { vm.setDate(it + 12 * 3_600_000L) }
                    pickDate = false
                }) { Text("Done", color = AurumColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pickDate = false }) { Text("Cancel", color = AurumColors.textDim) }
            },
            colors = DatePickerDefaults.colors(containerColor = AurumColors.surface)
        ) {
            DatePicker(
                state = picker,
                colors = DatePickerDefaults.colors(
                    containerColor = AurumColors.surface,
                    selectedDayContainerColor = AurumColors.accent,
                    todayDateBorderColor = AurumColors.accent,
                    todayContentColor = AurumColors.accent
                )
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {
        TossTopBar(title = if (f.id > 0L) "Edit purchase" else null, onBack = onDone)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Sentence("What did you buy?")
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GoldKind.entries.forEach { k ->
                    TossChip(text = k.label, onClick = { vm.setKind(k) }, selected = f.kind == k)
                }
            }
            if (!f.kind.perPiece) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoldEngine.KARATS.forEach { k ->
                        TossChip(text = "${k}K", onClick = { vm.setKarat(k) }, selected = f.karat == k)
                    }
                }
            } else {
                Hint("${f.kind.fixedKarat}K · ${Fmt.qty(f.kind.gramsEach)} g each · priced per piece")
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = f.name,
                onValueChange = vm::setName,
                placeholder = { Text("Name it — bracelet, ring, 50 g bar…", color = AurumColors.textFaint) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AurumColors.surfaceHigh,
                    unfocusedContainerColor = AurumColors.surfaceHigh,
                    focusedBorderColor = AurumColors.accent,
                    unfocusedBorderColor = AurumColors.surfaceHigh,
                    focusedTextColor = AurumColors.text,
                    unfocusedTextColor = AurumColors.text,
                    cursorColor = AurumColors.accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .pressScale(pressedScale = 0.98f) { pickDate = true }
                    .background(AurumColors.surfaceHigh)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bought on", style = MaterialTheme.typography.bodyLarge, color = AurumColors.textDim, modifier = Modifier.weight(1f))
                Text(Fmt.dateFull(f.boughtAt), style = MaterialTheme.typography.titleMedium, color = AurumColors.text)
            }
            Spacer(Modifier.height(10.dp))
            val unit = if (f.kind.perPiece) "piece" else "gram"
            FormField(
                label = if (f.kind.perPiece) "Pieces" else "Grams",
                value = f.qty,
                placeholder = "0",
                focused = f.focus == GoldField.QTY,
                onFocus = { vm.focus(GoldField.QTY) }
            )
            FormField(
                label = "Price per $unit",
                value = f.price,
                placeholder = f.listSell?.let { Fmt.num3(it) } ?: "0.000",
                focused = f.focus == GoldField.PRICE,
                onFocus = { vm.focus(GoldField.PRICE) }
            )
            FormField(
                label = "Making per $unit",
                value = f.making,
                placeholder = "0.000",
                focused = f.focus == GoldField.MAKING,
                onFocus = { vm.focus(GoldField.MAKING) }
            )
            if (f.price.isEmpty() && f.listSell != null) {
                Hint("Left empty, the price is today’s Syndicate sell price for this line.")
            }
            Preview(f)
            Spacer(Modifier.height(12.dp))
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            NumberKeypad(onKey = vm::press)
        }
        TossButton(
            text = if (f.id > 0L) "Save changes" else "Save",
            onClick = { vm.save(onDone) },
            enabled = f.valid && !f.saving,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    placeholder: String,
    focused: Boolean,
    onFocus: () -> Unit
) {
    val border by animateColorAsState(
        targetValue = if (focused) AurumColors.accent else AurumColors.surfaceHigh,
        animationSpec = tween(200),
        label = "fieldBorder"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .pressScale(pressedScale = 0.98f, onClick = onFocus)
            .background(AurumColors.surfaceHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = AurumColors.textDim, modifier = Modifier.weight(1f))
        Text(
            text = value.ifEmpty { placeholder },
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 20.sp),
            color = if (value.isEmpty()) AurumColors.textFaint else AurumColors.text
        )
    }
}

@Composable
private fun Preview(f: GoldForm) {
    val qty = f.qtyValue
    val price = f.priceValue
    val p = if (qty != null && price != null) {
        GoldEngine.preview(f.kind, f.effectiveKarat, qty, price, f.makingValue, f.list)
    } else null
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AurumColors.surface)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("You start at", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = AurumColors.textDim)
            Text(
                text = p?.let { (if (it.gap < 0) "−" else "+") + Fmt.num3(abs(it.gap)) } ?: "—",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = when {
                    p == null -> AurumColors.textDim
                    p.gap >= 0 -> AurumColors.gain
                    else -> AurumColors.loss
                }
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Rise needed", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = AurumColors.textDim)
            Text(
                text = p?.let { Fmt.pct(it.riseNeededPct) } ?: "—",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = AurumColors.text
            )
        }
    }
    if (p != null) {
        Hint(
            "You pay ${Fmt.jod(p.pay)}; a shop would buy it back today for ${Fmt.jod(p.backToday)}. " +
                "The gap is the Syndicate spread plus the making charge."
        )
    } else if (f.list == null) {
        Hint("Today’s list hasn’t loaded, so the starting gap can’t be shown yet. You can still save.")
    }
}
