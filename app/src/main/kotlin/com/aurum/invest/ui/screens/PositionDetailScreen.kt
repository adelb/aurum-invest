package com.aurum.invest.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.GoldLink
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.data.model.Quote
import com.aurum.invest.ui.components.ActionBadge
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.DeltaPct
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.ExtHoursChips
import com.aurum.invest.ui.components.LiveStatTile
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.SectionHeader
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.SentimentDot
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.components.ZoomablePriceChart
import com.aurum.invest.ui.components.TossChip
import com.aurum.invest.ui.components.CompanyLogo
import com.aurum.invest.ui.components.LogoFlight
import com.aurum.invest.ui.components.flyFrom
import com.aurum.invest.ui.components.popIn
import com.aurum.invest.ui.components.rememberEntrance
import com.aurum.invest.ui.components.riseIn
import com.aurum.invest.ui.theme.AurumColors
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlin.math.ceil
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The chart ranges, in the order the selector shows them. */
private val RANGES = listOf("1D", "1W", "1M", "3M", "1Y", "All")

@Composable
fun PositionDetailScreen(
    symbol: String,
    onBack: () -> Unit,
    onTrade: (String, String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onEditPosition: (String) -> Unit = {}
) {
    val vm: PositionDetailViewModel = viewModel()
    LaunchedEffect(symbol) { vm.start(symbol) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        val sym = state.symbol.ifEmpty { symbol.uppercase() }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = AurumColors.surface,
            shape = RoundedCornerShape(24.dp),
            titleContentColor = AurumColors.text,
            textContentColor = AurumColors.textSub,
            title = { Text("Remove $sym?") },
            text = {
                Text(
                    "This deletes every $sym trade record from Aurum’s ledger — handy for " +
                        "clearing a test position. Your other holdings are untouched, and " +
                        "nothing happens at your real broker."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.removeHolding(sym)
                    onBack()
                }) { Text("Remove", color = AurumColors.loss) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text("Cancel", color = AurumColors.textDim)
                }
            }
        )
    }
    var range by rememberSaveable { mutableStateOf("1D") }
    // The header and the action bar make their entrance the moment the screen
    // opens; the body has its own, started when its content first shows.
    val chrome = rememberEntrance(symbol)
    val scope = rememberCoroutineScope()

    // The refresh arrow turns while a load runs and settles on a full turn.
    val turn = remember { Animatable(0f) }
    LaunchedEffect(state.loading) {
        if (state.loading) {
            while (isActive) turn.animateTo(turn.value + 360f, tween(800, easing = LinearEasing))
        } else {
            turn.animateTo(ceil(turn.value / 360f) * 360f, spring(dampingRatio = 0.6f))
        }
    }
    // Star and pin pop under the finger.
    val starPop = remember { Animatable(1f) }
    val pinPop = remember { Animatable(1f) }
    fun pop(target: Animatable<Float, AnimationVector1D>) {
        scope.launch {
            target.snapTo(0.55f)
            target.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        }
    }

    // The Apple Stocks hand-off: the logo flies in from the row that was
    // tapped, and on the way back flies home to it while the body fades.
    val launchPoint = remember(symbol) { LogoFlight.take(symbol) }
    val flight = remember { Animatable(if (launchPoint != null) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (launchPoint != null) {
            flight.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val bodyAlpha = remember { Animatable(1f) }
    var leaving by remember { mutableStateOf(false) }
    val goBack: () -> Unit = {
        if (!leaving) {
            leaving = true
            if (launchPoint == null) {
                onBack()
            } else {
                scope.launch {
                    launch { bodyAlpha.animateTo(0f, tween(180)) }
                    flight.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                    onBack()
                }
            }
        }
    }
    BackHandler(enabled = launchPoint != null && !leaving, onBack = goBack)
    val fading = Modifier.graphicsLayer { alpha = bodyAlpha.value }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {

        // ------- header ------- (drawn above the body, so the flying logo passes over it)
        Row(
            modifier = Modifier.fillMaxWidth().zIndex(1f).padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = goBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = AurumColors.text
                )
            }
            CompanyLogo(
                symbol = symbol.uppercase(),
                size = 36.dp,
                launchPad = false,
                modifier = if (launchPoint != null) Modifier.flyFrom(launchPoint, flight) else Modifier.popIn(delayMs = 60)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).riseIn(chrome, "title", order = 1, distance = 8.dp)) {
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
                    tint = AurumColors.textDim,
                    modifier = Modifier.graphicsLayer { rotationZ = turn.value }
                )
            }
            if (state.watched) {
                IconButton(onClick = {
                    vm.togglePin()
                    pop(pinPop)
                }) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = if (state.pinned) "Unpin" else "Pin",
                        tint = if (state.pinned) AurumColors.gold else AurumColors.textDim,
                        modifier = Modifier.graphicsLayer {
                            scaleX = pinPop.value
                            scaleY = pinPop.value
                        }
                    )
                }
            }
            IconButton(onClick = {
                vm.toggleWatch()
                pop(starPop)
            }) {
                Icon(
                    imageVector = if (state.watched) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (state.watched) "Remove from watchlist" else "Add to watchlist",
                    tint = if (state.watched) AurumColors.gold else AurumColors.textDim,
                    modifier = Modifier.graphicsLayer {
                        scaleX = starPop.value
                        scaleY = starPop.value
                        rotationZ = (1f - starPop.value) * 120f
                    }
                )
            }
        }

        // ------- body -------
        if (state.loading && state.quote == null) {
            DetailSkeleton(modifier = Modifier.fillMaxWidth().weight(1f).then(fading))
        } else if (state.quote == null && state.chart3M.closes.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f).then(fading)) {
                EmptyState(
                    title = "Couldn't load ${state.symbol.ifEmpty { symbol.uppercase() }}",
                    message = "Market data was unreachable. Check your connection and try again.",
                    actionLabel = "Try again",
                    onAction = vm::refresh
                )
            }
        } else {
            // Created here, not at the top: the stagger starts when the
            // content does, not while the skeleton was still showing.
            val entrance = rememberEntrance(symbol)
            AurumRefreshBox(
                refreshing = state.loading,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxWidth().weight(1f).then(fading)
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
            ) {
                // price hero: the price counts in, wheel by wheel
                item {
                    val quote = state.quote
                    Column(modifier = Modifier.riseIn(entrance, "price", order = 0)) {
                        AnimatedMoney(
                            value = quote?.price ?: state.chart3M.closes.lastOrNull() ?: 0.0,
                            style = MaterialTheme.typography.displayLarge,
                            format = { Fmt.money(it) },
                            rollIn = true
                        )
                        if (quote != null) {
                            Spacer(Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.riseIn(entrance, "dayMove", order = 2, distance = 8.dp)
                            ) {
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

                // chart + range selector
                item {
                    LaunchedEffect(range) { vm.ensureRange(range) }
                    val series = when (range) {
                        "1D" -> state.chart1D
                        "1W" -> state.chart1W
                        "1M" -> state.chart1M
                        "3M" -> state.chart3M
                        "1Y" -> state.chart1Y
                        else -> state.chartMax
                    }
                    val baseline =
                        if (range == "1D") state.quote?.prevClose
                        else state.position?.avgCost
                    AurumCard(
                        contentPadding = PaddingValues(14.dp),
                        modifier = Modifier.riseIn(entrance, "chart", order = 1)
                    ) {
                        SegmentedToggle(
                            options = RANGES,
                            selected = RANGES.indexOf(range).coerceAtLeast(0),
                            onSelect = { range = RANGES[it] },
                            compact = true
                        )
                        Spacer(Modifier.height(14.dp))
                        if (series.closes.size >= 2) {
                            // key(range): a new range resets the zoom viewport
                            // and draws its line in afresh.
                            androidx.compose.runtime.key(range) {
                                ZoomablePriceChart(
                                    closes = series.closes,
                                    timestamps = series.timestamps,
                                    baseline = baseline,
                                    modifier = Modifier.fillMaxWidth().height(230.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
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
                                    // The long ranges load on first visit; a
                                    // moment of "loading" must not read as a
                                    // verdict of missing history.
                                    text = if (range == "1Y" || range == "All") "Loading chart…"
                                    else "No chart data",
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
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.riseIn(entrance, "analysis", order = 2)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.QueryStats,
                                contentDescription = null,
                                tint = AurumColors.accent
                            )
                            Spacer(Modifier.padding(start = 12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "35-technique analysis",
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

                // key stats (only when the quote carries them)
                val statsQuote = state.quote
                if (statsQuote != null && (
                        (statsQuote.dayLow != null && statsQuote.dayHigh != null) ||
                            (statsQuote.fiftyTwoWeekLow != null && statsQuote.fiftyTwoWeekHigh != null) ||
                            statsQuote.volume != null
                        )
                ) {
                    item {
                        Column(modifier = Modifier.riseIn(entrance, "stats", order = 3)) {
                            SectionHeader(title = "Key stats")
                            Spacer(Modifier.height(14.dp))
                            KeyStatsCard(quote = statsQuote)
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }

                // position card (only when held)
                val view = state.view
                val position = state.position
                if (position != null && view != null) {
                    item {
                        Column(modifier = Modifier.riseIn(entrance, "position", order = 4)) {
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
                                    LiveStatTile(
                                        label = "Market value",
                                        value = view.marketValue,
                                        modifier = Modifier.weight(1f),
                                        format = { Fmt.money(it) },
                                        rollIn = true
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
                                    AnimatedMoney(
                                        value = view.unrealizedPl,
                                        style = MaterialTheme.typography.titleMedium,
                                        baseColor = AurumColors.deltaColor(view.unrealizedPl),
                                        format = { Fmt.signedMoney(it) },
                                        rollIn = true
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
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }

                // advice card
                val advice = state.advice
                if (advice != null) {
                    item {
                        Column(modifier = Modifier.riseIn(entrance, "advice", order = 5)) {
                            SectionHeader(title = "Advice")
                            Spacer(Modifier.height(14.dp))
                            AurumCard(modifier = Modifier.animateContentSize()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ActionBadge(action = advice.action, modifier = Modifier.popIn(delayMs = 420))
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
                                                color = AurumColors.accent
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
                                                valueColor = AurumColors.accent
                                            )
                                        }
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
                        Column(modifier = Modifier.riseIn(entrance, "gold", order = 6)) {
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
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }

                // news: the headlines rise in one after another
                if (state.news.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "News · last 5 days",
                            modifier = Modifier.riseIn(entrance, "newsTitle", order = 7)
                        )
                        Spacer(Modifier.height(14.dp))
                        AurumCard(contentPadding = PaddingValues(vertical = 6.dp, horizontal = 0.dp)) {
                            state.news.forEachIndexed { index, item ->
                                NewsRow(
                                    item = item,
                                    modifier = Modifier.riseIn(entrance, "news$index", order = 8 + index)
                                )
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
                        Column(modifier = Modifier.riseIn(entrance, "newsTitle", order = 7)) {
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
            }
            }
        }

        // ------- ledger actions: moved here from the Home cards -------
        if (state.isHeld) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp)
                    .riseIn(chrome, "ledger", order = 3, distance = 32.dp)
                    .then(fading),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TossChip(
                    text = "Edit trades",
                    onClick = { onEditPosition(state.symbol.ifEmpty { symbol.uppercase() }) },
                    icon = Icons.Rounded.Edit
                )
                TossChip(
                    text = "Remove from Aurum",
                    onClick = { confirmRemove = true },
                    icon = Icons.Rounded.DeleteOutline
                )
            }
        }

        // ------- trade actions: slide up from the bottom edge -------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)
                .riseIn(chrome, "trade", order = 4, distance = 40.dp)
                .then(fading),
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
                    containerColor = AurumColors.accent,
                    contentColor = AurumColors.bg
                )
            ) {
                Text(text = "Buy", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The shape of the screen to come — price, chart, cards — pulsing gently
 * while the first quote loads, so the content lands where the eye already is.
 */
@Composable
private fun DetailSkeleton(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val glow by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(760, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeletonGlow"
    )
    Column(
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, top = 8.dp)
            .graphicsLayer { alpha = glow }
    ) {
        Bone(Modifier.width(190.dp).height(46.dp))
        Spacer(Modifier.height(10.dp))
        Bone(Modifier.width(120.dp).height(20.dp))
        Spacer(Modifier.height(20.dp))
        Bone(Modifier.fillMaxWidth().height(300.dp), radius = 20.dp)
        Spacer(Modifier.height(14.dp))
        Bone(Modifier.fillMaxWidth().height(76.dp), radius = 20.dp)
        Spacer(Modifier.height(28.dp))
        Bone(Modifier.width(140.dp).height(24.dp))
        Spacer(Modifier.height(14.dp))
        Bone(Modifier.fillMaxWidth().height(120.dp), radius = 20.dp)
    }
}

@Composable
private fun Bone(modifier: Modifier, radius: Dp = 10.dp) {
    Box(modifier = modifier.clip(RoundedCornerShape(radius)).background(AurumColors.surface))
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

/** A low—high track with the current price marked on it; the marker slides out to its place. */
@Composable
private fun RangeMeter(label: String, low: Double, high: Double, value: Double) {
    if (high <= low) return
    val frac = ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    val at = remember { Animatable(0f) }
    LaunchedEffect(frac) {
        at.animateTo(frac, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessVeryLow))
    }
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
            val x = at.value.coerceIn(0f, 1f) * size.width
            drawLine(
                color = AurumColors.surfaceHigh,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = AurumColors.accent.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(x, y),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = AurumColors.accent,
                radius = 6f,
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
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
