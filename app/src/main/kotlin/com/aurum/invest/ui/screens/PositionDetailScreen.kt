package com.aurum.invest.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.PriceChart
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.SentimentDot
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun PositionDetailScreen(
    symbol: String,
    onBack: () -> Unit,
    onTrade: (String, String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    val vm: PositionDetailViewModel = viewModel()
    LaunchedEffect(symbol) { vm.start(symbol) }
    val state by vm.state.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf("1D") }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {

        // ------- header -------
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = AurumColors.text
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.symbol.ifEmpty { symbol.uppercase() },
                    style = MaterialTheme.typography.titleLarge,
                    color = AurumColors.text
                )
                val shortName = state.quote?.shortName.orEmpty()
                if (shortName.isNotBlank()) {
                    Text(
                        text = shortName,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
            IconButton(onClick = vm::refresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = AurumColors.textDim
                )
            }
            if (state.watched) {
                IconButton(onClick = vm::togglePin) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = if (state.pinned) "Unpin" else "Pin",
                        tint = if (state.pinned) AurumColors.gold else AurumColors.textDim
                    )
                }
            }
            IconButton(onClick = vm::toggleWatch) {
                Icon(
                    imageVector = if (state.watched) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (state.watched) "Remove from watchlist" else "Add to watchlist",
                    tint = if (state.watched) AurumColors.gold else AurumColors.textDim
                )
            }
        }

        // ------- body -------
        if (state.loading && state.quote == null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AurumColors.gold)
            }
        } else if (state.quote == null && state.dailyCloses.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = "Couldn't load ${state.symbol.ifEmpty { symbol.uppercase() }}",
                    message = "Market data was unreachable. Check your connection and try again.",
                    actionLabel = "Try again",
                    onAction = vm::refresh
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
            ) {
                // price hero
                item {
                    val quote = state.quote
                    Column {
                        Text(
                            text = Fmt.money(quote?.price ?: state.dailyCloses.lastOrNull() ?: 0.0),
                            style = MaterialTheme.typography.displayLarge,
                            color = AurumColors.text
                        )
                        if (quote != null) {
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DeltaPct(
                                    value = quote.dayChangePct,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = " today",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AurumColors.textDim
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                // chart + range chips
                item {
                    val closes = if (range == "1D") state.intradayCloses else state.dailyCloses
                    val baseline =
                        if (range == "1D") state.quote?.prevClose
                        else state.position?.avgCost
                    AurumCard(contentPadding = PaddingValues(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RangeChip(label = "1D", selected = range == "1D") { range = "1D" }
                            RangeChip(label = "3M", selected = range == "3M") { range = "3M" }
                        }
                        Spacer(Modifier.height(14.dp))
                        if (closes.size >= 2) {
                            PriceChart(
                                closes = closes,
                                baseline = baseline,
                                modifier = Modifier.fillMaxWidth().height(210.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(210.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No chart data",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AurumColors.textDim
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // five-technique analysis entry
                item {
                    AurumCard(
                        onClick = { onOpenAnalysis(state.symbol.ifEmpty { symbol.uppercase() }) },
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.QueryStats,
                                contentDescription = null,
                                tint = AurumColors.gold
                            )
                            Spacer(Modifier.padding(start = 12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "15-technique analysis",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AurumColors.text
                                )
                                Text(
                                    text = "Moving averages, RSI, MACD, Ichimoku & 11 more · 5-day outlook · $3,000 plan",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AurumColors.textDim
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = AurumColors.textDim
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }

                // position card (only when held)
                val view = state.view
                val position = state.position
                if (position != null && view != null) {
                    item {
                        SectionHeader(title = "Your position")
                        Spacer(Modifier.height(14.dp))
                        AurumCard {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                StatTile(
                                    label = "Shares",
                                    value = Fmt.qty(position.shares),
                                    modifier = Modifier.weight(1f)
                                )
                                StatTile(
                                    label = "Avg cost",
                                    value = Fmt.money(position.avgCost),
                                    modifier = Modifier.weight(1f)
                                )
                                StatTile(
                                    label = "Market value",
                                    value = Fmt.money(view.marketValue),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Unrealized P/L",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AurumColors.textDim
                                )
                                Spacer(Modifier.weight(1f))
                                DeltaMoney(
                                    value = view.unrealizedPl,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "  ·  ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AurumColors.textDim
                                )
                                DeltaPct(
                                    value = view.unrealizedPlPct,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }

                // advice card
                val advice = state.advice
                if (advice != null) {
                    item {
                        SectionHeader(title = "Advice")
                        Spacer(Modifier.height(14.dp))
                        AurumCard(modifier = Modifier.animateContentSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ActionBadge(action = advice.action)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "Score ${advice.score}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.textDim
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = advice.headline,
                                style = MaterialTheme.typography.titleMedium,
                                color = AurumColors.text
                            )
                            if (advice.reasons.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                advice.reasons.forEach { reason ->
                                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text(
                                            text = "•  ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AurumColors.gold
                                        )
                                        Text(
                                            text = reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AurumColors.textDim
                                        )
                                    }
                                }
                            }
                            val hasLevels = advice.targetPrice != null ||
                                advice.stopLoss != null || advice.suggestedBuyPrice != null
                            if (hasLevels) {
                                Spacer(Modifier.height(14.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    advice.targetPrice?.let {
                                        StatTile(
                                            label = "Target",
                                            value = Fmt.money(it),
                                            modifier = Modifier.weight(1f),
                                            valueColor = AurumColors.gain
                                        )
                                    }
                                    advice.stopLoss?.let {
                                        StatTile(
                                            label = "Stop",
                                            value = Fmt.money(it),
                                            modifier = Modifier.weight(1f),
                                            valueColor = AurumColors.loss
                                        )
                                    }
                                    advice.suggestedBuyPrice?.let {
                                        StatTile(
                                            label = "Good entry",
                                            value = Fmt.money(it),
                                            modifier = Modifier.weight(1f),
                                            valueColor = AurumColors.gold
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }

                // gold relation card
                val gold = state.gold
                if (gold != null) {
                    item {
                        SectionHeader(title = "Gold relation")
                        Spacer(Modifier.height(14.dp))
                        AurumCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when (gold.link) {
                                    GoldLink.WITH_GOLD ->
                                        PillTag(text = "Moves with gold", color = AurumColors.gold)
                                    GoldLink.INVERSE_GOLD ->
                                        PillTag(text = "Inverse to gold", color = AurumColors.loss)
                                    GoldLink.NEUTRAL ->
                                        PillTag(text = "No gold link", color = AurumColors.textDim)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "r = ${"%.2f".format(gold.correlation)} · ${gold.sampleDays}d",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.textDim
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = gold.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AurumColors.text
                            )
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }

                // news
                if (state.news.isNotEmpty()) {
                    item {
                        SectionHeader(title = "News · last 5 days")
                        Spacer(Modifier.height(14.dp))
                        AurumCard(contentPadding = PaddingValues(vertical = 6.dp, horizontal = 0.dp)) {
                            state.news.forEachIndexed { index, item ->
                                NewsRow(item = item)
                                if (index < state.news.lastIndex) {
                                    HorizontalDivider(
                                        color = AurumColors.hairline,
                                        thickness = 1.dp,
                                        modifier = Modifier.padding(horizontal = 18.dp)
                                    )
                                }
                            }
                        }
                    }
                } else if (!state.loading) {
                    item {
                        SectionHeader(title = "News · last 5 days")
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "No recent headlines for ${state.symbol}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }

            // ------- trade actions -------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onTrade(state.symbol.ifEmpty { symbol.uppercase() }, "SELL") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, AurumColors.hairline)
                ) {
                    Text(
                        text = "Sell",
                        style = MaterialTheme.typography.labelLarge,
                        color = AurumColors.loss
                    )
                }
                Button(
                    onClick = { onTrade(state.symbol.ifEmpty { symbol.uppercase() }, "BUY") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.gold,
                        contentColor = AurumColors.bg
                    )
                ) {
                    Text(text = "Buy", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) AurumColors.goldSoft else AurumColors.surfaceHigh)
            .border(
                width = 1.dp,
                color = if (selected) AurumColors.gold.copy(alpha = 0.45f) else AurumColors.hairline,
                shape = shape
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) AurumColors.gold else AurumColors.textDim
        )
    }
}

@Composable
private fun NewsRow(item: NewsItem) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                } catch (_: Exception) {
                }
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SentimentDot(sentiment = item.sentiment)
        Spacer(Modifier.padding(start = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.text
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${item.source} • ${Fmt.timeAgo(item.publishedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
        item.priceImpactPct?.let {
            Spacer(Modifier.padding(start = 10.dp))
            DeltaPct(value = it, style = MaterialTheme.typography.labelMedium)
        }
    }
}
