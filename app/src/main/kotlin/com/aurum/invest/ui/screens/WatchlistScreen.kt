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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.DetailLine
import com.aurum.invest.ui.components.DisclosureRow
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.RowDivider
import com.aurum.invest.ui.components.ScreenTitle
import com.aurum.invest.ui.components.Space
import com.aurum.invest.ui.components.Sparkline
import com.aurum.invest.ui.components.adviceLabel
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun WatchlistScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: WatchlistViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        Column(modifier = Modifier.padding(horizontal = Space.screenH)) {
            Spacer(modifier = Modifier.height(18.dp))
            ScreenTitle(overline = "Tracking", title = "Watchlist")
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = watchFieldColors(),
                placeholder = {
                    Text(
                        text = "Add ticker — try AAPL",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // The keyboard's action key adds the best match directly.
                    state.suggestions.firstOrNull()?.let { (symbol, name) ->
                        vm.addSymbol(symbol, name)
                        keyboard?.hide()
                    }
                }),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = AurumColors.textDim
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = AurumColors.textDim
                            )
                        }
                    }
                }
            )
            AnimatedVisibility(visible = state.suggestions.isNotEmpty()) {
                AurumCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    state.suggestions.forEach { (symbol, name) ->
                        SuggestionRow(
                            symbol = symbol,
                            name = name,
                            onAdd = { vm.addSymbol(symbol, name) }
                        )
                    }
                }
            }
        }

        AurumRefreshBox(
            refreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.loading && state.rows.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AurumColors.gold)
                    }
                }
            } else if (state.rows.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing watched yet",
                        message = "Search a ticker above to track its price, a good entry point and its relation to gold."
                    )
                }
            } else {
                item { RowDivider() }
                items(state.rows, key = { it.symbol }) { row ->
                    WatchRowCard(
                        row = row,
                        onOpen = { onOpenDetail(row.symbol) },
                        onPin = { vm.setPinned(row.symbol, !row.pinned) },
                        onRemove = { vm.remove(row.symbol) },
                        onAnalyze = { onOpenAnalysis(row.symbol) }
                    )
                    RowDivider()
                }
            }
        }
        }
    }
}

@Composable
private fun SuggestionRow(symbol: String, name: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAdd() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
            if (name.isNotBlank()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Add $symbol to watchlist",
            tint = AurumColors.gold
        )
    }
}

/**
 * One watched name: ticker, price and today's move at rest. The buy read,
 * gold relation and the pin/analyse/remove actions open on tap.
 */
@Composable
private fun WatchRowCard(
    row: WatchRow,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
    onAnalyze: () -> Unit
) {
    DisclosureRow(
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp)
            ) {
                if (row.pinned) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = "Pinned",
                        tint = AurumColors.gold,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (row.name.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (row.spark.size >= 2) {
                    Sparkline(
                        data = row.spark,
                        modifier = Modifier.width(56.dp).height(22.dp),
                        fill = false
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    val quote = row.quote
                    if (quote != null) {
                        Text(
                            text = Fmt.money(quote.price),
                            style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.text
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        DeltaPct(
                            value = quote.dayChangePct,
                            style = MaterialTheme.typography.labelMedium
                        )
                    } else {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.textDim
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
        }
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            val advice = row.advice
            if (advice != null) {
                Text(
                    text = advice.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(modifier = Modifier.height(8.dp))
                advice.suggestedBuyPrice?.let {
                    DetailLine("Good entry", Fmt.money(it), valueColor = AurumColors.gold)
                }
                DetailLine("Read", adviceLabel(advice.action))
            } else {
                Text(
                    text = "Analyzing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            if (row.pinned && row.goldLink != null) {
                DetailLine(
                    label = "Gold relation",
                    value = when (row.goldLink) {
                        GoldLink.WITH_GOLD -> "Moves with gold"
                        GoldLink.INVERSE_GOLD -> "Inverse to gold"
                        GoldLink.NEUTRAL -> "No link"
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text(row.symbol, color = AurumColors.gold) }
                TextButton(onClick = onAnalyze) { Text("Analysis", color = AurumColors.gold) }
                TextButton(onClick = onPin) {
                    Text(if (row.pinned) "Unpin" else "Pin", color = AurumColors.textDim)
                }
                TextButton(onClick = onRemove) { Text("Remove", color = AurumColors.textDim) }
            }
        }
    }
}

@Composable
private fun watchFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    focusedContainerColor = AurumColors.surface,
    unfocusedContainerColor = AurumColors.surface,
    cursorColor = AurumColors.gold,
    focusedBorderColor = AurumColors.gold,
    unfocusedBorderColor = AurumColors.hairline,
    focusedLabelColor = AurumColors.gold,
    unfocusedLabelColor = AurumColors.textDim,
    focusedPlaceholderColor = AurumColors.textDim,
    unfocusedPlaceholderColor = AurumColors.textDim
)
