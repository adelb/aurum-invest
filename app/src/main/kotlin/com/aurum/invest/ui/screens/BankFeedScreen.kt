package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.bank.BankNotificationListener
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.BankEvent
import com.aurum.invest.data.model.TradeSide
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.theme.AurumColors
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun BankFeedScreen() {
    val vm: BankFeedViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var historyExpanded by remember { mutableStateOf(false) }

    // Re-check listener access every time we come back from system settings.
    LifecycleResumeEffect(Unit) {
        vm.refreshListenerState()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp)
        ) {
            item(key = "header") {
                Text(
                    text = "Bank Feed",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Trade alerts captured from your bank's notifications",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }

            item(key = "listener-banner") {
                AnimatedVisibility(
                    visible = !state.listenerEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(Modifier.height(20.dp))
                        AurumCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.NotificationsActive,
                                    contentDescription = null,
                                    tint = AurumColors.gold,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Notification access is off",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AurumColors.gold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Enable notification access so Aurum can auto-capture your Bank al Etihad trade alerts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AurumColors.textDim
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { BankNotificationListener.openSettings(context) },
                                shape = RoundedCornerShape(3.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AurumColors.gold,
                                    contentColor = AurumColors.bg
                                )
                            ) {
                                Text(
                                    text = "Enable access",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            item(key = "pending-header") {
                Spacer(Modifier.height(28.dp))
                SectionHeader(
                    title = "Pending",
                    trailing = {
                        if (state.pending.isNotEmpty()) {
                            PillTag(text = "${state.pending.size}", color = AurumColors.gold)
                        }
                    }
                )
                Spacer(Modifier.height(14.dp))
            }

            if (state.pending.isEmpty()) {
                item(key = "pending-empty") {
                    EmptyState(
                        title = "No pending alerts",
                        message = "New trade notifications from your bank will land here, parsed and ready to import."
                    )
                }
            } else {
                items(
                    count = state.pending.size,
                    key = { i -> "pending-${state.pending[i].id}" }
                ) { i ->
                    val event = state.pending[i]
                    PendingEventCard(
                        event = event,
                        onImport = { symbol, side, shares, price ->
                            vm.importEvent(event.id, symbol, side, shares, price)
                        },
                        onDismiss = { vm.dismissEvent(event.id) }
                    )
                    if (i < state.pending.lastIndex) Spacer(Modifier.height(14.dp))
                }
            }

            if (state.history.isNotEmpty()) {
                item(key = "history-header") {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader(
                        title = "History",
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .clickable { historyExpanded = !historyExpanded }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${state.history.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.textDim
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    imageVector = if (historyExpanded) Icons.Rounded.ExpandLess
                                    else Icons.Rounded.ExpandMore,
                                    contentDescription = if (historyExpanded) "Collapse history" else "Expand history",
                                    tint = AurumColors.textDim,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                }
                if (historyExpanded) {
                    items(
                        count = state.history.size,
                        key = { i -> "history-${state.history[i].id}" }
                    ) { i ->
                        HistoryRow(event = state.history[i])
                        if (i < state.history.lastIndex) Spacer(Modifier.height(10.dp))
                    }
                } else {
                    item(key = "history-collapsed") {
                        Text(
                            text = "Tap to show imported and dismissed alerts",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
        }
    }
}

/** One pending bank notification; tap to expand into an editable import form. */
@Composable
private fun PendingEventCard(
    event: BankEvent,
    onImport: (symbol: String, side: TradeSide, shares: Double, price: Double) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    var symbol by remember(event.id) { mutableStateOf(event.parsed?.symbol.orEmpty()) }
    var sharesText by remember(event.id) { mutableStateOf(editNum(event.parsed?.shares)) }
    var priceText by remember(event.id) { mutableStateOf(editNum(event.parsed?.price)) }
    var side by remember(event.id) { mutableStateOf(event.parsed?.side ?: TradeSide.BUY) }

    val sharesVal = sharesText.trim().replace(",", "").toDoubleOrNull()
    val priceVal = priceText.trim().replace(",", "").toDoubleOrNull()
    val valid = symbol.isNotBlank() && symbol.all { it.isLetter() } &&
        sharesVal != null && sharesVal > 0.0 &&
        priceVal != null && priceVal > 0.0

    AurumCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = event.pkg,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = Fmt.timeAgo(event.postedAt),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = event.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = event.text,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )

        val parsed = event.parsed
        if (parsed != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillTag(
                    text = parsed.side.name,
                    color = if (parsed.side == TradeSide.BUY) AurumColors.gain else AurumColors.loss
                )
                parsed.symbol?.let { PillTag(text = it, color = AurumColors.gold) }
                parsed.shares?.let { PillTag(text = "${Fmt.qty(it)} sh", color = AurumColors.textDim) }
                parsed.price?.let { PillTag(text = Fmt.money(it), color = AurumColors.textDim) }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = AurumColors.hairline)
                Spacer(Modifier.height(14.dp))

                SideToggle(side = side, onChange = { side = it })
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = symbol,
                    onValueChange = { input ->
                        symbol = input.uppercase().filter { it.isLetter() }.take(5)
                    },
                    label = { Text("Symbol") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    colors = feedFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = sharesText,
                        onValueChange = { sharesText = it },
                        label = { Text("Shares") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feedFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Price $") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = feedFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (sharesVal != null && priceVal != null) {
                                onImport(symbol, side, sharesVal, priceVal)
                            }
                        },
                        enabled = valid,
                        shape = RoundedCornerShape(3.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.gold,
                            contentColor = AurumColors.bg,
                            disabledContainerColor = AurumColors.surfaceHigh,
                            disabledContentColor = AurumColors.textDim
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "Import", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Dismiss",
                            style = MaterialTheme.typography.labelLarge,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
        }

        if (!expanded) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Tap to review & import",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.gold
            )
        }
    }
}

/** BUY / SELL segmented pill toggle. */
@Composable
private fun SideToggle(side: TradeSide, onChange: (TradeSide) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(AurumColors.surfaceHigh)
            .padding(4.dp)
    ) {
        TradeSide.entries.forEach { s ->
            val selected = s == side
            val accent = if (s == TradeSide.BUY) AurumColors.gain else AurumColors.loss
            val bgColor = when {
                !selected -> Color.Transparent
                s == TradeSide.BUY -> AurumColors.gainSoft
                else -> AurumColors.lossSoft
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bgColor)
                    .clickable { onChange(s) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (s == TradeSide.BUY) "Buy" else "Sell",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) accent else AurumColors.textDim
                )
            }
        }
    }
}

/** Compact history row with a status pill. */
@Composable
private fun HistoryRow(event: BankEvent) {
    AurumCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${event.pkg} • ${Fmt.timeAgo(event.postedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            PillTag(
                text = event.status,
                color = if (event.status == BankEvent.STATUS_IMPORTED) AurumColors.gain
                else AurumColors.textDim
            )
        }
    }
}

@Composable
private fun feedFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    focusedBorderColor = AurumColors.gold,
    unfocusedBorderColor = AurumColors.hairline,
    cursorColor = AurumColors.gold,
    focusedLabelColor = AurumColors.gold,
    unfocusedLabelColor = AurumColors.textDim,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent
)

private val editFmt = DecimalFormat("0.####", DecimalFormatSymbols(Locale.US))

/** Plain (grouping-free) number text for editable fields. */
private fun editNum(v: Double?): String = if (v == null) "" else editFmt.format(v)
