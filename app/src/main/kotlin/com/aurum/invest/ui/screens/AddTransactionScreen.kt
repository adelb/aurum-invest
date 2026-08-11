package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun AddTransactionScreen(prefillSymbol: String?, prefillSide: String?, onDone: () -> Unit) {
    val vm: AddTransactionViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(prefillSymbol, prefillSide) {
        vm.applyPrefill(prefillSymbol, prefillSide)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = AurumColors.text
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Add trade",
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.text
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        SideToggle(side = state.side, onChange = vm::setSide)
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = state.symbol,
            onValueChange = vm::onSymbolChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = addFieldColors(),
            label = { Text("Symbol") },
            placeholder = { Text("AAPL") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
        )
        AnimatedVisibility(visible = state.held != null) {
            val held = state.held
            if (held != null) {
                Text(
                    text = "You hold ${Fmt.qty(held.shares)} shares · avg ${Fmt.money(held.avgCost)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
        }
        AnimatedVisibility(visible = state.suggestions.isNotEmpty()) {
            AurumCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                state.suggestions.forEach { (symbol, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.pickSuggestion(symbol) }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.text
                        )
                        if (name.isNotBlank()) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.shares,
                onValueChange = vm::onSharesChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !state.sellAll,
                shape = RoundedCornerShape(16.dp),
                colors = addFieldColors(),
                label = { Text("Shares") },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = state.price,
                onValueChange = vm::onPriceChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = addFieldColors(),
                label = { Text("Price (per share $)") },
                placeholder = { Text("0.00") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = state.amount,
            onValueChange = vm::onAmountChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.sellAll,
            shape = RoundedCornerShape(16.dp),
            colors = addFieldColors(),
            label = {
                Text(
                    if (state.side == TradeSide.BUY) "Amount paid ($, fees included)"
                    else "Amount received ($, after fees)"
                )
            },
            placeholder = { Text("0.00") },
            supportingText = {
                val sh = state.sharesVal
                if (state.amount.isNotBlank() && sh != null && sh > 0.0) {
                    Text(
                        text = "= ${Fmt.qty(sh)} shares",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )

        AnimatedVisibility(visible = state.side == TradeSide.SELL && state.held != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sell all shares",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = state.sellAll,
                    onCheckedChange = vm::setSellAll,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AurumColors.gold,
                        checkedTrackColor = AurumColors.goldSoft,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = AurumColors.textDim,
                        uncheckedTrackColor = AurumColors.surfaceHigh,
                        uncheckedBorderColor = AurumColors.hairline
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = state.fees,
            onValueChange = vm::onFeesChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = addFieldColors(),
            label = { Text("Fees (optional)") },
            placeholder = { Text("0.00") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.side == TradeSide.BUY) "Total cost" else "Net proceeds",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.textDim
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = Fmt.money(state.total),
                style = MaterialTheme.typography.titleMedium,
                color = if (state.total > 0.0) AurumColors.gold else AurumColors.textDim
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { vm.save(onDone) },
            enabled = state.valid && !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AurumColors.gold,
                contentColor = AurumColors.bg,
                disabledContainerColor = AurumColors.surfaceHigh,
                disabledContentColor = AurumColors.textDim
            )
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.width(22.dp).height(22.dp),
                    color = AurumColors.bg,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = if (state.side == TradeSide.BUY) "Save buy" else "Save sell",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

/** Custom two-option pill toggle — flat gold fill when selected, per M7/P5 contract. */
@Composable
private fun SideToggle(side: TradeSide, onChange: (TradeSide) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AurumColors.surface)
            .border(1.dp, AurumColors.hairline, shape)
            .padding(4.dp)
    ) {
        SideOption(
            label = "Buy",
            selected = side == TradeSide.BUY,
            modifier = Modifier.weight(1f)
        ) { onChange(TradeSide.BUY) }
        SideOption(
            label = "Sell",
            selected = side == TradeSide.SELL,
            modifier = Modifier.weight(1f)
        ) { onChange(TradeSide.SELL) }
    }
}

@Composable
private fun SideOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    val m = modifier
        .clip(shape)
        .clickable { onClick() }
        .background(if (selected) AurumColors.gold else Color.Transparent)
    Box(
        modifier = m.padding(vertical = 10.dp),
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
private fun addFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    disabledTextColor = AurumColors.textDim,
    focusedContainerColor = AurumColors.surface,
    unfocusedContainerColor = AurumColors.surface,
    disabledContainerColor = AurumColors.surface,
    cursorColor = AurumColors.gold,
    focusedBorderColor = AurumColors.gold,
    unfocusedBorderColor = AurumColors.hairline,
    disabledBorderColor = AurumColors.hairline,
    focusedLabelColor = AurumColors.gold,
    unfocusedLabelColor = AurumColors.textDim,
    disabledLabelColor = AurumColors.textDim,
    focusedPlaceholderColor = AurumColors.textDim,
    unfocusedPlaceholderColor = AurumColors.textDim,
    focusedSupportingTextColor = AurumColors.textDim,
    unfocusedSupportingTextColor = AurumColors.textDim,
    disabledSupportingTextColor = AurumColors.textDim
)
