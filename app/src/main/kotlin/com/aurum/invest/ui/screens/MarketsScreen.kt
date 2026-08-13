package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.R
import com.aurum.invest.analytics.MarketIdea
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun MarketsScreen(
    onOpenDetail: (String) -> Unit = {}
) {
    val vm: MarketsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    AurumRefreshBox(
        refreshing = state.refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.markets_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = AurumColors.text,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Text(
                    text = stringResource(R.string.markets_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }

            marketSection(
                key = "indices",
                headerRes = R.string.markets_indices,
                rows = state.indices,
                state = state,
                onToggle = vm::toggleExpanded,
                onOpenDetail = onOpenDetail
            )
            marketSection(
                key = "metals",
                headerRes = R.string.markets_metals,
                rows = state.metals,
                state = state,
                onToggle = vm::toggleExpanded,
                onOpenDetail = onOpenDetail
            )
            marketSection(
                key = "fx",
                headerRes = R.string.markets_fx,
                rows = state.fx,
                state = state,
                onToggle = vm::toggleExpanded,
                onOpenDetail = onOpenDetail
            )

            item(key = "footer") {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.markets_footer_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.marketSection(
    key: String,
    headerRes: Int,
    rows: List<MarketRow>,
    state: MarketsState,
    onToggle: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    item(key = "hdr-$key") {
        Spacer(Modifier.height(20.dp))
        SectionHeader(title = androidx.compose.ui.res.stringResource(headerRes))
        Spacer(Modifier.height(10.dp))
    }
    items(rows, key = { "$key-" + it.symbol }) { row ->
        val expanded = state.expandedSymbol == row.symbol
        MarketRowCard(
            row = row,
            expanded = expanded,
            detail = if (expanded) state.detail else null,
            onToggle = { onToggle(row.symbol) },
            onOpenDetail = { onOpenDetail(row.symbol) }
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MarketRowCard(
    row: MarketRow,
    expanded: Boolean,
    detail: MarketDetail?,
    onToggle: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val quote = row.quote
    val currency = quote?.currency?.takeIf { it.isNotBlank() } ?: ""
    AurumCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.symbol + if (currency.isNotEmpty()) "  ·  $currency" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = quote?.price?.let { formatPrice(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.text,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                if (quote != null) {
                    DeltaPct(value = quote.dayChangePct)
                } else {
                    Text(
                        text = stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
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

                if (detail == null || detail.loading) {
                    Text(
                        text = stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                } else {
                    if (detail.ideas.isNotEmpty()) {
                        SectionSubHeader(text = stringResource(R.string.markets_ideas_title))
                        Spacer(Modifier.height(6.dp))
                        detail.ideas.forEach { idea ->
                            IdeaCard(idea = idea, quoteCurrency = currency)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SectionSubHeader(text = stringResource(R.string.markets_news_title))
                    Spacer(Modifier.height(6.dp))
                    if (detail.news.isEmpty()) {
                        Text(
                            text = stringResource(R.string.markets_news_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    } else {
                        detail.news.forEach { item ->
                            NewsRowMini(item = item)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.markets_open_full),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.gold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        androidx.compose.material3.TextButton(onClick = onOpenDetail) {
                            Text(
                                text = stringResource(R.string.markets_open_full_action),
                                style = MaterialTheme.typography.labelLarge,
                                color = AurumColors.gold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionSubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = AurumColors.textDim
    )
}

@Composable
private fun IdeaCard(idea: MarketIdea, quoteCurrency: String) {
    val horizonLabel = when (idea.horizon) {
        MarketIdea.Horizon.SCALP -> stringResource(R.string.markets_horizon_scalp)
        MarketIdea.Horizon.SHORT -> stringResource(R.string.markets_horizon_short)
        MarketIdea.Horizon.LONG -> stringResource(R.string.markets_horizon_long)
    }
    val dirLabel = when (idea.direction) {
        MarketIdea.Direction.LONG -> stringResource(R.string.markets_dir_long)
        MarketIdea.Direction.SHORT -> stringResource(R.string.markets_dir_short)
        MarketIdea.Direction.NEUTRAL -> stringResource(R.string.markets_dir_neutral)
    }
    val dirColor = when (idea.direction) {
        MarketIdea.Direction.LONG -> AurumColors.gain
        MarketIdea.Direction.SHORT -> AurumColors.loss
        MarketIdea.Direction.NEUTRAL -> AurumColors.textDim
    }
    Surface(
        color = AurumColors.surfaceHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = horizonLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = AurumColors.text
                )
                Text(
                    text = dirLabel + "  ·  R:R " + String.format(java.util.Locale.US, "%.2f", idea.rr1),
                    style = MaterialTheme.typography.labelSmall,
                    color = dirColor
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LevelChip(label = "ENT", value = idea.entry, color = AurumColors.text, currency = quoteCurrency)
                LevelChip(label = "SL", value = idea.sl, color = AurumColors.loss, currency = quoteCurrency)
                LevelChip(label = "TP1", value = idea.tp1, color = AurumColors.gain, currency = quoteCurrency)
                LevelChip(label = "TP2", value = idea.tp2, color = AurumColors.gain, currency = quoteCurrency)
                LevelChip(label = "TP3", value = idea.tp3, color = AurumColors.gain, currency = quoteCurrency)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = idea.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LevelChip(
    label: String,
    value: Double,
    color: Color,
    currency: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Text(
            text = formatPrice(value),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NewsRowMini(item: NewsItem) {
    val dotColor = when {
        item.sentiment > 0 -> AurumColors.gain
        item.sentiment < 0 -> AurumColors.loss
        else -> AurumColors.textDim
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.text,
                maxLines = 2
            )
            Text(
                text = item.source + "  ·  " + Fmt.timeAgo(item.publishedAt),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
    }
}

private fun formatPrice(v: Double): String {
    val abs = if (v < 0) -v else v
    return when {
        abs >= 1000 -> Fmt.money(v, symbol = "")
        abs >= 1 -> String.format(java.util.Locale.US, "%.4f", v)
        else -> String.format(java.util.Locale.US, "%.5f", v)
    }
}
