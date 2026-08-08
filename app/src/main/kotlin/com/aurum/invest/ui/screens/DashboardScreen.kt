package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.PortfolioSummary
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.GoldGradientText
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.Sparkline
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun DashboardScreen(
    onOpenDetail: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onReports: () -> Unit
) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 12.dp, bottom = 110.dp
                )
            ) {
                item {
                    HeaderRow(
                        loading = state.loading,
                        onRefresh = vm::refresh,
                        onSettings = onSettings,
                        onReports = onReports
                    )
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    HeroSummary(summary = state.summary)
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    SummaryTiles(summary = state.summary)
                }

                item {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader(
                        title = "Holdings",
                        trailing = {
                            if (state.holdings.isNotEmpty()) {
                                Text(
                                    text = "${state.holdings.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.textDim
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                }

                if (state.loading && state.holdings.isEmpty() && state.summary == null) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AurumColors.gold)
                        }
                    }
                } else if (state.holdings.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No holdings yet",
                            message = "Tap + to record your first trade."
                        )
                    }
                } else {
                    items(state.holdings, key = { it.view.position.symbol }) { row ->
                        HoldingCard(row = row, onClick = { onOpenDetail(row.view.position.symbol) })
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            containerColor = AurumColors.gold,
            contentColor = AurumColors.bg,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add trade")
        }
    }
}

@Composable
private fun HeaderRow(
    loading: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onReports: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Aurum",
            style = MaterialTheme.typography.titleLarge,
            color = AurumColors.gold
        )
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = loading, enter = fadeIn(), exit = fadeOut()) {
            CircularProgressIndicator(
                color = AurumColors.gold,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "Refresh",
                tint = AurumColors.textDim
            )
        }
        IconButton(onClick = onReports) {
            Icon(
                Icons.Rounded.Description,
                contentDescription = "Reports",
                tint = AurumColors.textDim
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun HeroSummary(summary: PortfolioSummary?) {
    val s = summary ?: PortfolioSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Text(
            text = "Total value",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = Fmt.money(s.marketValue),
            style = MaterialTheme.typography.displayLarge,
            color = AurumColors.text
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeltaMoney(value = s.dayPl, style = MaterialTheme.typography.titleMedium)
            Text(
                text = " today",
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun SummaryTiles(summary: PortfolioSummary?) {
    val s = summary ?: PortfolioSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    AurumCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Invested",
                value = Fmt.money(s.investedCost),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Unrealized P/L",
                value = Fmt.signedMoney(s.unrealizedPl),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.deltaColor(s.unrealizedPl)
            )
            StatTile(
                label = "Realized P/L",
                value = Fmt.signedMoney(s.realizedPl),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.deltaColor(s.realizedPl)
            )
        }
    }
}

@Composable
private fun HoldingCard(row: HoldingRow, onClick: () -> Unit) {
    val view = row.view
    val position = view.position
    val quote = view.quote
    val price = quote?.price ?: position.avgCost

    AurumCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = position.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text
                )
                Text(
                    text = "${Fmt.qty(position.shares)} shares",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            if (row.spark.size >= 2) {
                Sparkline(
                    data = row.spark,
                    modifier = Modifier.width(90.dp).height(36.dp)
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.money(price),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text
                )
                if (quote != null) {
                    DeltaPct(
                        value = quote.dayChangePct,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Unrealized  ",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            DeltaMoney(value = view.unrealizedPl, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "  ·  ",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            DeltaPct(value = view.unrealizedPlPct, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            row.advice?.let { ActionBadge(action = it.action) }
        }
    }
}
