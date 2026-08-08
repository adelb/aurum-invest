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
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.Sparkline
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun WatchlistScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: WatchlistViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Watchlist",
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.text
            )
            Spacer(modifier = Modifier.height(16.dp))
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
                item {
                    SectionHeader(title = "Watching")
                }
                items(state.rows, key = { it.symbol }) { row ->
                    WatchRowCard(
                        row = row,
                        onOpen = { onOpenDetail(row.symbol) },
                        onPin = { vm.setPinned(row.symbol, !row.pinned) },
                        onRemove = { vm.remove(row.symbol) },
                        onAnalyze = { onOpenAnalysis(row.symbol) }
                    )
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

@Composable
private fun WatchRowCard(
    row: WatchRow,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
    onAnalyze: () -> Unit
) {
    AurumCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (row.pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (row.pinned) "Unpin ${row.symbol}" else "Pin ${row.symbol}",
                    tint = if (row.pinned) AurumColors.gold else AurumColors.textDim
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text
                )
                if (row.name.isNotBlank()) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Sparkline(
                data = row.spark,
                modifier = Modifier
                    .width(90.dp)
                    .height(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                val quote = row.quote
                if (quote != null) {
                    Text(
                        text = Fmt.money(quote.price),
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    DeltaPct(
                        value = quote.dayChangePct,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val advice = row.advice
            if (advice != null) {
                ActionBadge(action = advice.action)
                val entry = advice.suggestedBuyPrice
                if (entry != null) {
                    Text(
                        text = "Entry ≈ " + Fmt.money(entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
            } else {
                Text(
                    text = "Analyzing…",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (row.pinned) {
                when (row.goldLink) {
                    GoldLink.WITH_GOLD -> PillTag(text = "Moves with gold", color = AurumColors.gold)
                    GoldLink.INVERSE_GOLD -> PillTag(text = "Inverse to gold", color = AurumColors.loss)
                    GoldLink.NEUTRAL -> PillTag(text = "—", color = AurumColors.textDim)
                    null -> Unit
                }
            }
            IconButton(onClick = onAnalyze, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Rounded.QueryStats,
                    contentDescription = "Analyze ${row.symbol}",
                    tint = AurumColors.gold,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${row.symbol}",
                    tint = AurumColors.textDim,
                    modifier = Modifier.size(16.dp)
                )
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
