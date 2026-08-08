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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.ScoreBar
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.roundToInt

@Composable
fun PicksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: PicksViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Weekly Picks",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text
                )
                Text(
                    text = state.weekLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
            if (state.refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AurumColors.gold,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = vm::refresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Recompute picks",
                        tint = AurumColors.gold
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.rows.isEmpty()) {
                item {
                    if (state.loading || state.refreshing) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AurumColors.gold)
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Scanning the market…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AurumColors.textDim
                                )
                            }
                        }
                    } else {
                        EmptyState(
                            title = "No picks this week yet",
                            message = "Tap refresh to scan the market and rank this week's ten strongest setups."
                        )
                    }
                }
            } else {
                items(state.rows, key = { it.pick.symbol }) { row ->
                    PickCard(
                        row = row,
                        onOpen = { onOpenDetail(row.pick.symbol) },
                        onAnalyze = { onOpenAnalysis(row.pick.symbol) }
                    )
                }
            }

            if (state.budgetRows.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(14.dp))
                    SectionHeader(title = "Under $25 · weekly watch")
                }
                items(state.budgetRows, key = { "u25-${it.pick.symbol}" }) { row ->
                    PickCard(
                        row = row,
                        onOpen = { onOpenDetail(row.pick.symbol) },
                        onAnalyze = { onOpenAnalysis(row.pick.symbol) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PickCard(row: PickRow, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    val pick = row.pick
    AurumCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = pick.rank.toString().padStart(2, '0'),
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.gold
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = pick.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AurumColors.text
                    )
                    if (pick.name.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pick.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBar(score = pick.score, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pick.score.roundToInt().toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = pick.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onAnalyze() }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = AurumColors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "5-day analysis",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.gold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(pick.priceAtPick),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Text(
                    text = "at pick",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
                val since = row.sincePickPct
                if (since != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    DeltaPct(value = since, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = "since pick",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
    }
}
