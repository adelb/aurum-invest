package com.aurum.invest.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.data.model.Quote
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaMoney
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.ExtHoursChips
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.SentimentDot
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.components.ZoomablePriceChart
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
    var showAlertDialog by remember { mutableStateOf(false) }

    if (showAlertDialog) {
        AddAlertDialog(
            symbol = state.symbol.ifEmpty { symbol.uppercase() },
            currentPrice = state.quote?.price,
            suggestedTarget = state.advice?.targetPrice,
            suggestedStop = state.advice?.stopLoss,
            onDismiss = { showAlertDialog = false },
            onSave = { direction, threshold, note ->
                vm.addAlert(direction, threshold, note)
                showAlertDialog = false
            }
        )
    }

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
                        color = AurumColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
        } else if (state.quote == null && state.chart3M.closes.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EmptyState(
                    title = "Couldn't load ${state.symbol.ifEmpty { symbol.uppercase() }}",
                    message = "Market data was unreachable. Check your connection and try again.",
                    actionLabel = "Try again",
                    onAction = vm::refresh
                )
            }
        } else {
            AurumRefreshBox(
                refreshing = state.loading,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
            ) {
                // price hero
                item {
                    val quote = state.quote
                    Column {
                        Text(
                            text = Fmt.money(quote?.price ?: state.chart3M.closes.lastOrNull() ?: 0.0),
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
                        val ext = state.ext
                        if (ext?.preMarketPct != null || ext?.postMarketPct != null) {
                            Spacer(Modifier.height(8.dp))
                            ExtHoursChips(ext = ext)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                // chart + range chips
                item {
                    LaunchedEffect(range) { vm.ensureRange(range) }
                    val series = when (range) {
                        "1D" -> state.chart1D
                        "1W" -> state.chart1W
                        "1M" -> state.chart1M
                        "3M" -> state.chart3M
                        "1Y" -> state.chart1Y
                        "5Y" -> state.chart5Y
                        else -> state.chartMax
                    }
                    val baseline =
                        if (range == "1D") state.quote?.prevClose
                        else state.position?.avgCost
                    AurumCard(contentPadding = PaddingValues(14.dp)) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RangeChip(label = "1D", selected = range == "1D") { range = "1D" }
                            RangeChip(label = "1W", selected = range == "1W") { range = "1W" }
                            RangeChip(label = "1M", selected = range == "1M") { range = "1M" }
                            RangeChip(label = "3M", selected = range == "3M") { range = "3M" }
                            RangeChip(label = "1Y", selected = range == "1Y") { range = "1Y" }
                            RangeChip(label = "5Y", selected = range == "5Y") { range = "5Y" }
                            RangeChip(label = "Max", selected = range == "MAX") { range = "MAX" }
                        }
                        Spacer(Modifier.height(14.dp))
                        if (series.closes.size >= 2) {
                            // key(range) so switching ranges resets the zoom viewport
                            androidx.compose.runtime.key(range) {
                                ZoomablePriceChart(
                                    closes = series.closes,
                                    timestamps = series.timestamps,
                                    baseline = baseline,
                                    modifier = Modifier.fillMaxWidth().height(250.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Pinch to zoom · drag to pan · hold for crosshair",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.textDim
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(230.dp),
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
                                    text = "35-technique analysis",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AurumColors.text
                                )
                                Text(
                                    text = "All 35 techniques — moving averages, RSI, MACD, " +
                                        "Ichimoku & more · 5-day outlook · sized buy plan",
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

                // key stats (only when the quote carries them)
                val statsQuote = state.quote
                if (statsQuote != null && (
                        (statsQuote.dayLow != null && statsQuote.dayHigh != null) ||
                            (statsQuote.fiftyTwoWeekLow != null && statsQuote.fiftyTwoWeekHigh != null) ||
                            statsQuote.volume != null
                        )
                ) {
                    item {
                        SectionHeader(title = "Key stats")
                        Spacer(Modifier.height(14.dp))
                        KeyStatsCard(quote = statsQuote)
                        Spacer(Modifier.height(28.dp))
                    }
                }

                // price alerts — watch any level, notified within ~15 minutes
                item {
                    AlertsSection(
                        alerts = state.alerts,
                        onAdd = { showAlertDialog = true },
                        onDelete = { vm.deleteAlert(it) }
                    )
                    Spacer(Modifier.height(28.dp))
                }

                // company research: profile, financial health, valuation,
                // analyst consensus, upcoming catalysts (C6 + H1)
                item {
                    FundamentalsSections(
                        feed = state.fundamentals,
                        price = state.quote?.price
                    )
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
                                    text = gold.correlation
                                        ?.let { "r = ${"%.2f".format(it)} · ${gold.sampleDays}d" }
                                        ?: "r — · ${gold.sampleDays}d",
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

                // news — "no headlines" is only claimed when the feed was
                // actually reached; a failed fetch says so instead.
                if (state.news.isNotEmpty()) {
                    item {
                        SectionHeader(title = "News · last 5 days")
                        if (state.newsStatus == FeedStatus.STALE) {
                            Text(
                                text = "Feed unreachable — showing headlines from " +
                                    Fmt.timeAgo(state.newsAsOf) + ".",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.gold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
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
                            text = when (state.newsStatus) {
                                FeedStatus.FRESH ->
                                    "No headlines for ${state.symbol} in the last 5 days " +
                                        "(feed checked)."
                                FeedStatus.STALE ->
                                    "News feed unreachable; the last successful check " +
                                        "(${Fmt.timeAgo(state.newsAsOf)}) had no headlines."
                                FeedStatus.FAILED ->
                                    "Couldn't load news — the feed was unreachable. This is " +
                                        "NOT a verified \"no news\"."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.newsStatus == FeedStatus.FRESH) AurumColors.textDim
                            else AurumColors.gold
                        )
                    }
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
private fun KeyStatsCard(quote: Quote) {
    AurumCard {
        var first = true
        if (quote.dayLow != null && quote.dayHigh != null) {
            RangeMeter(
                label = "Day range",
                low = quote.dayLow,
                high = quote.dayHigh,
                value = quote.price
            )
            first = false
        }
        if (quote.fiftyTwoWeekLow != null && quote.fiftyTwoWeekHigh != null) {
            if (!first) Spacer(Modifier.height(14.dp))
            RangeMeter(
                label = "52-week range",
                low = quote.fiftyTwoWeekLow,
                high = quote.fiftyTwoWeekHigh,
                value = quote.price
            )
            first = false
        }
        if (!first) Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            quote.volume?.let {
                StatTile(
                    label = "Volume",
                    value = Fmt.compact(it.toDouble()),
                    modifier = Modifier.weight(1f)
                )
            }
            StatTile(
                label = "Prev close",
                value = Fmt.money(quote.prevClose),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Day move",
                value = Fmt.signedPct(quote.dayChangePct),
                modifier = Modifier.weight(1f),
                valueColor = AurumColors.deltaColor(quote.dayChangePct)
            )
        }
    }
}

/** A low—high track with the current price marked on it. */
@Composable
private fun RangeMeter(label: String, low: Double, high: Double, value: Double) {
    if (high <= low) return
    val frac = ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${Fmt.money(low)} – ${Fmt.money(high)}",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.text
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2f
            drawLine(
                color = AurumColors.surfaceHigh,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = AurumColors.gold.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(frac * size.width, y),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = AurumColors.gold,
                radius = 6f,
                center = Offset(frac * size.width, y)
            )
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
                color = AurumColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${item.source} • ${Fmt.timeAgo(item.publishedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.priceImpactPct?.let {
            Spacer(Modifier.padding(start = 10.dp))
            DeltaPct(value = it, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The user's price alerts on this symbol: active levels first, fired ones as history. */
@Composable
private fun AlertsSection(
    alerts: List<com.aurum.invest.data.db.PriceAlertEntity>,
    onAdd: () -> Unit,
    onDelete: (com.aurum.invest.data.db.PriceAlertEntity) -> Unit
) {
    SectionHeader(
        title = "Price alerts",
        trailing = {
            Text(
                text = "Add",
                style = MaterialTheme.typography.labelLarge,
                color = AurumColors.gold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onAdd() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    )
    Spacer(Modifier.height(10.dp))
    if (alerts.isEmpty()) {
        Text(
            text = "Watch any level — Aurum checks every ~15 minutes and notifies you when " +
                "it is crossed.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    } else {
        AurumCard(contentPadding = PaddingValues(vertical = 4.dp, horizontal = 16.dp)) {
            alerts.forEach { alert ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (if (alert.direction == "ABOVE") "Above " else "Below ") +
                                Fmt.money(alert.threshold) +
                                (if (alert.note.isNotBlank()) " · ${alert.note}" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurumColors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (alert.active) {
                                "Watching · set ${Fmt.timeAgo(alert.createdAt)}"
                            } else {
                                "Fired ${alert.triggeredAt?.let { Fmt.timeAgo(it) } ?: ""} at " +
                                    (alert.priceAtTrigger?.let { Fmt.money(it) } ?: "—")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (alert.active) AurumColors.gold else AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onDelete(alert) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** Create a price alert; the advice's own target/stop are one tap away. */
@Composable
private fun AddAlertDialog(
    symbol: String,
    currentPrice: Double?,
    suggestedTarget: Double?,
    suggestedStop: Double?,
    onDismiss: () -> Unit,
    onSave: (direction: String, threshold: Double, note: String) -> Unit
) {
    var direction by remember { mutableStateOf("ABOVE") }
    var priceText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val priceVal = priceText.replace(",", "").trim().toDoubleOrNull()
    val valid = priceVal != null && priceVal > 0.0

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurumColors.surface,
        titleContentColor = AurumColors.text,
        textContentColor = AurumColors.textDim,
        title = { Text("Alert on $symbol") },
        text = {
            Column {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("ABOVE" to "Rises above", "BELOW" to "Falls below").forEach { (key, label) ->
                        val selected = key == direction
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) AurumColors.bg else AurumColors.textDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) AurumColors.gold else AurumColors.surfaceHigh)
                                .clickable { direction = key }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Price ($)") },
                    supportingText = {
                        currentPrice?.let {
                            Text(
                                text = "Now ${Fmt.money(it)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (suggestedTarget != null || suggestedStop != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestedTarget?.let { t ->
                            Text(
                                text = "Target ${Fmt.money(t)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.gain,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(AurumColors.surfaceHigh)
                                    .clickable {
                                        direction = "ABOVE"
                                        priceText = Fmt.trimNumber(t)
                                        note = "target"
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        suggestedStop?.let { s ->
                            Text(
                                text = "Stop ${Fmt.money(s)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.loss,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(AurumColors.surfaceHigh)
                                    .clickable {
                                        direction = "BELOW"
                                        priceText = Fmt.trimNumber(s)
                                        note = "stop"
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(40) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { if (priceVal != null) onSave(direction, priceVal, note) },
                enabled = valid,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = AurumColors.gold,
                    contentColor = AurumColors.bg,
                    disabledContainerColor = AurumColors.surfaceHigh,
                    disabledContentColor = AurumColors.textDim
                )
            ) { Text("Set alert") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = AurumColors.textDim)
            }
        }
    )
}

// ---------------------------------------------------------------- fundamentals

/** "—  not available" instead of a silent zero: every absent field says so. */
private fun fmtOrDash(v: Double?, format: (Double) -> String): String =
    v?.let(format) ?: "—"

/**
 * Company research (C6 + H1): profile, financial health, valuation with
 * bull/base/bear scenarios, analyst consensus with count and dispersion, and
 * the upcoming catalysts. Sourced and time-stamped; unavailable fields render
 * as "—" rather than fabricated values.
 */
@Composable
private fun FundamentalsSections(
    feed: com.aurum.invest.data.model.FundamentalsFeed?,
    price: Double?
) {
    SectionHeader(title = "Company research")
    Spacer(Modifier.height(10.dp))
    val f = feed?.data
    if (feed == null || f == null) {
        Text(
            text = if (feed?.status == FeedStatus.FAILED) {
                "Couldn't load company data — the fundamentals source was unreachable. " +
                    "This is a fetch failure, not \"no data exists\"."
            } else {
                "Loading company data…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(28.dp))
        return
    }

    if (feed.status == FeedStatus.STALE) {
        Text(
            text = "Source unreachable — showing data fetched ${Fmt.timeAgo(feed.asOf)}.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.gold
        )
        Spacer(Modifier.height(10.dp))
    }

    // Profile
    if (f.sector != null || f.description != null) {
        AurumCard {
            Text(
                text = listOfNotNull(f.sector, f.industry).joinToString(" · ")
                    .ifBlank { "Sector unknown" },
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text
            )
            f.employees?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "%,d employees".format(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            f.description?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    // Financial health
    AurumCard {
        Text(
            text = "Financial health",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Revenue (ttm)",
                value = fmtOrDash(f.totalRevenue) { Fmt.compact(it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Rev growth",
                value = fmtOrDash(f.revenueGrowthPct) { Fmt.signedPct(it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Net margin",
                value = fmtOrDash(f.profitMarginPct) { Fmt.pct(it) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Cash",
                value = fmtOrDash(f.totalCash) { Fmt.compact(it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Debt",
                value = fmtOrDash(f.totalDebt) { Fmt.compact(it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Free cash flow",
                value = fmtOrDash(f.freeCashflow) { Fmt.compact(it) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "ROE",
                value = fmtOrDash(f.returnOnEquityPct) { Fmt.pct(it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Debt/equity",
                value = fmtOrDash(f.debtToEquity) { String.format(java.util.Locale.US, "%.1f", it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Current ratio",
                value = fmtOrDash(f.currentRatio) { String.format(java.util.Locale.US, "%.2f", it) },
                modifier = Modifier.weight(1f)
            )
        }
    }
    Spacer(Modifier.height(14.dp))

    // Valuation + scenarios
    AurumCard {
        Text(
            text = "Valuation",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Market cap",
                value = fmtOrDash(f.marketCap) { Fmt.compact(it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "P/E (ttm)",
                value = fmtOrDash(f.trailingPE) { String.format(java.util.Locale.US, "%.1f", it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Fwd P/E",
                value = fmtOrDash(f.forwardPE) { String.format(java.util.Locale.US, "%.1f", it) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "EPS fwd",
                value = fmtOrDash(f.epsForward) { Fmt.money(it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "P/B",
                value = fmtOrDash(f.priceToBook) { String.format(java.util.Locale.US, "%.1f", it) },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Div yield",
                value = fmtOrDash(f.dividendYieldPct) { Fmt.pct(it) },
                modifier = Modifier.weight(1f)
            )
        }

        // Mechanical EPS x multiple scenarios: shown ONLY when both inputs
        // exist, with the formula disclosed — never a single "fair value".
        val eps = f.epsForward ?: f.epsTrailing
        val pe = f.forwardPE ?: f.trailingPE
        if (eps != null && eps > 0.0 && pe != null && pe > 0.0) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = AurumColors.hairline)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Scenarios (EPS × multiple)",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Bear (×${String.format(java.util.Locale.US, "%.0f", pe * 0.8)})",
                    value = Fmt.money(eps * pe * 0.8),
                    modifier = Modifier.weight(1f),
                    valueColor = AurumColors.loss
                )
                StatTile(
                    label = "Base (×${String.format(java.util.Locale.US, "%.0f", pe)})",
                    value = Fmt.money(eps * pe),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Bull (×${String.format(java.util.Locale.US, "%.0f", pe * 1.2)})",
                    value = Fmt.money(eps * pe * 1.2),
                    modifier = Modifier.weight(1f),
                    valueColor = AurumColors.gain
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Mechanical bands: ${Fmt.money(eps)} EPS × the current multiple ±20%. " +
                    "A sensitivity frame, not a fair-value claim — earnings and multiples " +
                    "both move.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Valuation scenarios unavailable: EPS or multiple missing for this name.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
    }
    Spacer(Modifier.height(14.dp))

    // Analyst consensus — with count and dispersion, never a bare average.
    AurumCard {
        Text(
            text = "Analyst view",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        if (f.targetMean != null || f.recommendationMean != null) {
            // The low–high range is two prices plus a dash: in a third-width
            // column it wrapped onto a second line and broke the tiles' shared
            // baseline. It gets its own full-width row, where it cannot wrap
            // however long the prices are.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatTile(
                    label = "Target (mean)",
                    value = fmtOrDash(f.targetMean) { Fmt.money(it) },
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                StatTile(
                    label = "Analysts",
                    value = f.analystCount?.toString() ?: "—",
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(12.dp))
            StatTile(
                label = "Target range (low – high)",
                value = if (f.targetLow != null && f.targetHigh != null) {
                    "${Fmt.money(f.targetLow)} – ${Fmt.money(f.targetHigh)}"
                } else "—",
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            val rec = f.recommendationKey?.replace('_', ' ')
            Text(
                text = buildString {
                    if (rec != null && f.recommendationMean != null) {
                        append(
                            "Consensus: $rec (${
                                String.format(java.util.Locale.US, "%.1f", f.recommendationMean)
                            } on a 1=strong buy … 5=sell scale)"
                        )
                    }
                    price?.let { p ->
                        f.targetMean?.let { t ->
                            if (p > 0) {
                                append(
                                    " · mean target ${
                                        Fmt.signedPct((t - p) / p * 100.0)
                                    } vs the current price"
                                )
                            }
                        }
                    }
                    append(". Analyst targets are opinions with wide error bars, not forecasts.")
                },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        } else {
            Text(
                text = "No analyst coverage data available for this name.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
    }
    Spacer(Modifier.height(14.dp))

    // Catalysts (H1): the dated events that can move the stock.
    AurumCard {
        Text(
            text = "Catalysts",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        val todayStart = Dates.todayStartMs()
        // Second guard, on top of the parser's: a cached row can be read after
        // its date has passed, and a date that is behind us is never "next".
        val earnings = f.nextEarningsTs?.takeIf { it >= todayStart }
        if (earnings != null) {
            val days = ((earnings - todayStart) / 86_400_000L).toInt()
            val soon = days <= 7
            val window = f.nextEarningsEndTs
                ?.takeIf { it > earnings }
                ?.let { " – ${Fmt.dateShort(it)}" }
                .orEmpty()
            Text(
                text = "Next earnings: ${Fmt.dateShort(earnings)}$window" +
                    (if (f.earningsDateEstimated || window.isNotEmpty()) " (estimated)" else " (confirmed)") +
                    (if (soon) "  ⚠ within a week — event risk on any new position" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (soon) AurumColors.gold else AurumColors.text
            )
        } else if (f.lastEarningsTs != null) {
            // Yahoo keeps serving the last report's date until the next one is
            // scheduled. Say which it is rather than dressing it up as "next".
            Text(
                text = "Next earnings date not scheduled yet · last reported " +
                    Fmt.dateShort(f.lastEarningsTs),
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        } else {
            Text(
                text = "Next earnings date: not available.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        f.exDividendTs?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Ex-dividend: ${Fmt.dateShort(it)}" +
                    (f.dividendRate?.let { r -> " · ${Fmt.money(r)}/share annually" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.text
            )
        }
        f.dividendDateTs?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Dividend payment: ${Fmt.dateShort(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.text
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Source: ${f.source} · fetched ${Fmt.timeAgo(f.fetchedAt)}. Fields marked — " +
            "were not available, not zero.",
        style = MaterialTheme.typography.labelSmall,
        color = AurumColors.textDim
    )
    Spacer(Modifier.height(28.dp))
}
