package com.aurum.invest.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.GoldBook
import com.aurum.invest.analytics.GoldEngine
import com.aurum.invest.analytics.GoldKind
import com.aurum.invest.analytics.GoldLot
import com.aurum.invest.analytics.GoldTrendCall
import com.aurum.invest.analytics.MonthBand
import com.aurum.invest.analytics.SyndicateList
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.Hint
import com.aurum.invest.ui.components.IconSquare
import com.aurum.invest.ui.components.IconTap
import com.aurum.invest.ui.components.Lead
import com.aurum.invest.ui.components.MoreLink
import com.aurum.invest.ui.components.SectionBand
import com.aurum.invest.ui.components.SectionTitle
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.Sentence
import com.aurum.invest.ui.components.Tone
import com.aurum.invest.ui.components.TossButton
import com.aurum.invest.ui.components.TossChip
import com.aurum.invest.ui.components.pressScale
import com.aurum.invest.ui.theme.AurumColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun GoldScreen(
    onAdd: () -> Unit,
    onOpenPrices: () -> Unit,
    onOpenLot: (Long) -> Unit
) {
    val vm: GoldViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val book = state.book

    // The live estimate's heartbeat — only while this screen is showing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            vm.tick()
        }
    }

    AurumRefreshBox(
        refreshing = state.loading,
        onRefresh = { vm.refresh(force = true) },
        modifier = Modifier.fillMaxSize().background(AurumColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gold",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = AurumColors.text,
                        modifier = Modifier.weight(1f)
                    )
                    IconTap(Icons.Rounded.Refresh, "Refresh prices", { vm.refresh(force = true) })
                }
            }

            item { GoldHero(state = state, book = book, onBasis = vm::setBasis) }

            item {
                Spacer(Modifier.height(20.dp))
                TossButton(text = "Add gold I bought", onClick = onAdd)
            }

            item {
                SectionBand()
                TodaysPrices(state = state, onOpenPrices = onOpenPrices)
            }

            val list = state.list
            if (list != null && state.worldDaily.size >= 22 && list.worldAtList != null) {
                item {
                    SectionBand()
                    GoldChartSection(state = state, book = book)
                }
            }

            if (book.lots.isNotEmpty()) {
                item {
                    SectionBand()
                    InsightsSection(state = state, book = book)
                }
            }

            state.trend?.let { trend ->
                item {
                    SectionBand()
                    TrendRead(trend = trend)
                }
            }

            val band = state.band
            if (band != null && book.value > 0.0) {
                item {
                    SectionBand()
                    MonthAhead(band = band, value = book.value)
                }
            }

            state.world?.let { world ->
                if (list != null) {
                    item {
                        SectionBand()
                        WorldTile(state = state, world = world.price, dayPct = world.dayChangePct)
                    }
                }
            }

            if (state.lots.isNotEmpty()) {
                item {
                    SectionBand()
                    SectionTitle(
                        title = "Your purchases",
                        subtitle = "${state.openLots.size} held" +
                            if (state.soldLots.isNotEmpty()) " · ${state.soldLots.size} sold" else ""
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(state.lots, key = { it.id }) { lot ->
                    GoldLotRow(lot = lot, state = state, onClick = { onOpenLot(lot.id) })
                }
                if (state.soldLots.isNotEmpty()) {
                    item {
                        Hint("Sold purchases booked ${Fmt.signedJod(state.realized)} in all, at the prices the shops paid you.")
                    }
                }
            }
        }
    }
}

@Composable
private fun GoldHero(state: GoldState, book: GoldBook, onBasis: (GoldBasis) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (book.lots.isEmpty()) {
            Sentence("Track the gold you bought, priced by Jordan’s shops")
            Spacer(Modifier.height(10.dp))
            Lead(
                "Add a purchase and Aurum values it every day at the Syndicate’s buy-back " +
                    "price — what a shop would actually pay you — after making charges."
            )
            if (state.listStatus == FeedStatus.FAILED && !state.loading) {
                Hint("Today’s Syndicate list couldn’t be reached. Pull to try again.")
            }
            return
        }
        val up = book.pl >= 0.0
        Sentence(if (up) "Your gold is up" else "Your gold is down")
        AnimatedMoney(
            value = abs(book.pl),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp, lineHeight = 44.sp),
            baseColor = if (up) AurumColors.gain else AurumColors.loss,
            format = { Fmt.jod(it) },
            epsilon = 0.0005
        )
        Text(
            text = "if you sold today.",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 36.sp),
            color = AurumColors.text
        )
        Spacer(Modifier.height(10.dp))
        Lead(
            "Your ${Fmt.qty(round1(book.grams))} g are worth ${Fmt.jod(book.value)} " +
                "against ${Fmt.jod(book.paid)} paid · ${Fmt.signedPct(book.plPct)}."
        )
        Spacer(Modifier.height(18.dp))
        SegmentedToggle(
            options = listOf("Syndicate list", "Live estimate"),
            selected = if (state.basis == GoldBasis.LIST) 0 else 1,
            onSelect = { onBasis(if (it == 0) GoldBasis.LIST else GoldBasis.LIVE) }
        )
        Hint(
            if (state.basis == GoldBasis.LIST) {
                "Valued at what a shop pays you on the Syndicate list of ${listLabel(state.list)}."
            } else if (state.list?.worldAtList == null || state.world == null) {
                "World gold isn’t available right now, so the estimate equals the list."
            } else {
                "Moved with world gold since the ${listLabel(state.list)} list — an estimate " +
                    "until the Syndicate publishes again."
            }
        )
        if (book.unpriced.isNotEmpty()) {
            Hint("${book.unpriced.size} purchase(s) couldn’t be valued — the list has no line for them.")
        }
    }
}

private fun round1(v: Double) = Math.round(v * 10.0) / 10.0

/** "Sat 24 Oct, 10:15" in Amman time. */
fun listLabel(list: SyndicateList?): String {
    if (list == null || list.listedAt <= 0L) return "the latest list"
    val f = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)
    f.timeZone = TimeZone.getTimeZone("Asia/Amman")
    return f.format(Date(list.listedAt))
}

@Composable
private fun TodaysPrices(state: GoldState, onOpenPrices: () -> Unit) {
    val list = state.list
    SectionTitle(
        title = "Today’s gold prices",
        subtitle = if (list != null) "Syndicate list, ${listLabel(list)} · JOD per gram"
        else "The Syndicate list hasn’t loaded yet",
        trailing = { MoreLink("All", onOpenPrices) }
    )
    if (list == null) return
    Spacer(Modifier.height(8.dp))
    val cheapest = GoldEngine.spreads(list).firstOrNull()?.karat
    listOf(21, 24, 18).forEach { k ->
        val line = list.karats[k] ?: return@forEach
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .pressScale(pressedScale = 0.98f, onClick = onOpenPrices)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSquare(text = "${k}K", tone = Tone.GOLD)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("$k karat", style = MaterialTheme.typography.titleMedium, color = AurumColors.text)
                Text(
                    text = "spread ${Fmt.num2(line.spreadPct)}%" + when {
                        k == 21 -> " · most traded"
                        k == cheapest -> " · cheapest round trip"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.num3(line.sell), style = MaterialTheme.typography.titleMedium, color = AurumColors.text)
                Text(
                    "buy ${Fmt.num3(line.buy)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            }
        }
    }
    if (state.listStatus == FeedStatus.STALE) {
        Hint("Couldn’t reach the list just now — showing the one saved ${Fmt.timeAgo(list.fetchedAt)}.")
    }
}

/** The 21K buy price per gram, as world gold has moved it, against your break-even. */
@Composable
private fun GoldChartSection(state: GoldState, book: GoldBook) {
    val list = state.list ?: return
    val anchor = list.worldAtList ?: return
    val buy21 = list.karats[21]?.buy ?: return
    var range by rememberSaveable { mutableStateOf("6M") }
    val sessions = when (range) {
        "1M" -> 22
        "6M" -> 126
        else -> 252
    }
    val world = state.worldDaily.takeLast(sessions).map { it.close }.toMutableList()
    state.world?.price?.let { if (world.isNotEmpty()) world[world.lastIndex] = it }
    val series = world.map { it * buy21 / anchor }
    if (series.size < 2) return
    // Your break-even on 21K terms: today's 21K buy × what you paid / what it's worth.
    val breakEven = if (book.value > 0.0) buy21 * state.activeFactor * book.paid / book.value else null
    val change = (series.last() / series.first() - 1.0) * 100.0

    SectionTitle(title = "21K buy price", subtitle = "JOD per gram, from world gold")
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = Fmt.num3(series.last()),
            style = MaterialTheme.typography.headlineMedium,
            color = AurumColors.text
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = Fmt.signedPct(change) + when (range) {
                "1M" -> " in a month"
                "6M" -> " in 6 months"
                else -> " in a year"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.deltaColor(change),
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
    Spacer(Modifier.height(12.dp))
    GoldLineChart(
        series = series,
        breakEven = breakEven,
        key = range,
        modifier = Modifier.fillMaxWidth().height(170.dp)
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("1M", "6M", "1Y").forEach { r ->
            TossChip(text = r, onClick = { range = r }, selected = range == r, fill = false)
        }
    }
    if (breakEven != null) {
        Hint("The dashed line is where your whole book breaks even: a 21K buy price of ${Fmt.num3(breakEven)}.")
    }
}

/** A line that draws itself in, with a dashed break-even and a live dot. */
@Composable
private fun GoldLineChart(
    series: List<Double>,
    breakEven: Double?,
    key: Any,
    modifier: Modifier = Modifier
) {
    val draw = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = AurumColors.gold,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )
    val up = series.last() >= series.first()
    val lineColor = if (up) AurumColors.gain else AurumColors.loss
    Canvas(modifier = modifier) {
        val values = if (breakEven != null) series + breakEven else series
        var lo = values.min()
        var hi = values.max()
        val pad = (hi - lo).coerceAtLeast(1e-6) * 0.14
        lo -= pad
        hi += pad
        fun y(v: Double) = ((1 - (v - lo) / (hi - lo)) * size.height).toFloat()
        val step = size.width / (series.size - 1)
        val path = Path()
        series.forEachIndexed { i, v ->
            val x = i * step
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        clipRect(right = size.width * draw.value) {
            drawPath(
                fill,
                brush = Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = 0.22f), Color.Transparent)
                )
            )
            drawPath(path, color = lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        if (breakEven != null) {
            val by = y(breakEven)
            drawLine(
                color = AurumColors.gold.copy(alpha = 0.8f),
                start = Offset(0f, by),
                end = Offset(size.width, by),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            val label = measurer.measure("Break even ${Fmt.num3(breakEven)}", labelStyle)
            val ly = (by - label.size.height - 4f).coerceAtLeast(0f)
            drawRoundRect(
                color = AurumColors.bg.copy(alpha = 0.85f),
                topLeft = Offset(0f, ly - 2f),
                size = androidx.compose.ui.geometry.Size(label.size.width + 12f, label.size.height + 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
            )
            drawText(label, topLeft = Offset(6f, ly))
        }
        if (draw.value >= 1f) {
            val last = Offset(size.width, y(series.last()))
            drawCircle(color = lineColor.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = last)
            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = last)
        }
    }
}

private data class Insight(val square: String, val tone: Tone, val title: String, val detail: String)

@Composable
private fun InsightsSection(state: GoldState, book: GoldBook) {
    val list = state.list ?: return
    val insights = buildList {
        val be = book.breakEvenWorld
        val worldLine = if (be != null) "That’s world gold at about ${Fmt.money(be)} an ounce." else
            "Measured at the Syndicate buy price."
        if (book.pl >= 0.0) {
            add(Insight(Fmt.num2(book.cushionPct) + "%", Tone.GAIN,
                "Gold can fall ${Fmt.num2(book.cushionPct)}% before you’re in the red", worldLine))
        } else {
            add(Insight(Fmt.num2(-book.cushionPct) + "%", Tone.LOSS,
                "Gold must rise ${Fmt.num2(-book.pl / book.value * 100.0)}% for you to break even", worldLine))
        }
        if (book.making > 0.0) {
            add(Insight(Fmt.compact(book.making), Tone.LOSS,
                "You paid ${Fmt.jod(book.making)} in making charges",
                "Shops never refund them when you sell — they are a cost, not part of the gold."))
        }
        val spreads = GoldEngine.spreads(list)
        spreads.firstOrNull()?.let { best ->
            add(Insight("${best.karat}K", Tone.BLUE,
                "${best.karat}K is the cheapest to buy and sell back",
                "Spread today: " + spreads.joinToString(", ") { "${it.karat}K ${Fmt.num2(it.spreadPct)}%" } + "."))
        }
        state.spyShadow?.let { shadow ->
            val vs = book.value - shadow
            add(Insight("SPY", if (vs >= 0) Tone.GAIN else Tone.LOSS,
                if (vs >= 0) "Your gold beat SPY by ${Fmt.jod(vs)}"
                else "SPY would have earned ${Fmt.jod(-vs)} more",
                "The same money on the same days in SPY is worth ${Fmt.jod(shadow)} now."))
        }
        val trend = state.trend
        if (trend?.fromHighPct != null && trend.high52 != null) {
            add(Insight(Fmt.num2(trend.fromHighPct).substringBefore('.') + "%",
                if (trend.fromHighPct > -5.0) Tone.GAIN else Tone.LOSS,
                if (trend.fromHighPct > -0.5) "World gold is at its 52-week high"
                else "Gold is ${Fmt.num2(-trend.fromHighPct)}% below its 52-week high",
                "${Fmt.money(trend.high52)} an ounce." +
                    (trend.momentum20Pct?.let { " 20-session momentum ${Fmt.signedPct(it)}." } ?: "")))
        }
        state.correlation?.let { r ->
            add(Insight(Fmt.num2(r).let { if (r >= 0) it else it }, Tone.NEUTRAL,
                when {
                    r >= 0.5 -> "Gold has moved closely with stocks this year"
                    r >= 0.2 -> "Gold moved with stocks this year"
                    r > -0.2 -> "Gold moved on its own this year"
                    else -> "Gold moved against stocks this year"
                },
                "Correlation r ${Fmt.num2(r)} with SPY" + when {
                    r >= 0.2 -> " — a weaker hedge than usual."
                    else -> " — it balanced your stocks."
                }))
        }
    }
    SectionTitle(title = "What Aurum sees", subtitle = "Read from your purchases and the market")
    Spacer(Modifier.height(8.dp))
    insights.forEach { ins ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
            IconSquare(text = ins.square, tone = ins.tone)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ins.title, style = MaterialTheme.typography.titleMedium, color = AurumColors.text)
                Text(
                    ins.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TrendRead(trend: com.aurum.invest.analytics.GoldTrend) {
    val (verdict, reason, tone) = when (trend.call) {
        GoldTrendCall.DOWNTREND -> Triple(
            "Hold for now — don’t add yet",
            "World gold is below its 50-day (${trend.sma50?.let { Fmt.money(it) } ?: "—"}) and 200-day " +
                "(${trend.sma200?.let { Fmt.money(it) } ?: "—"}) averages. Aurum suggests adding again only " +
                "after a close above the 50-day.",
            Tone.LOSS
        )
        GoldTrendCall.TREND_UP -> Triple(
            "The trend is with you",
            "World gold is above its 50-day (${trend.sma50?.let { Fmt.money(it) } ?: "—"}) and 200-day " +
                "(${trend.sma200?.let { Fmt.money(it) } ?: "—"}) averages. If you add, 24K keeps the round trip cheapest.",
            Tone.GAIN
        )
        GoldTrendCall.MIXED -> Triple(
            "Mixed — add only in small steps",
            "World gold is between its 50-day (${trend.sma50?.let { Fmt.money(it) } ?: "—"}) and 200-day " +
                "(${trend.sma200?.let { Fmt.money(it) } ?: "—"}) averages: neither trend has the upper hand.",
            Tone.NEUTRAL
        )
    }
    AurumCard(modifier = Modifier.fillMaxWidth()) {
        Text("Aurum’s read", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = AurumColors.textDim)
        Spacer(Modifier.height(6.dp))
        Text(
            verdict,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = when (tone) {
                Tone.GAIN -> AurumColors.gain
                Tone.LOSS -> AurumColors.loss
                else -> AurumColors.text
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = AurumColors.textSub)
    }
    Hint("A read of the trend from public prices, not financial advice.")
}

@Composable
private fun MonthAhead(band: MonthBand, value: Double) {
    fun at(pct: Double) = value * (1.0 + pct / 100.0)
    SectionTitle(title = "A month from now", subtitle = "Where gold like yours has landed before")
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Most likely near",
        style = MaterialTheme.typography.bodyLarge,
        color = AurumColors.textSub
    )
    Text(
        text = Fmt.jod(at(band.median)),
        style = MaterialTheme.typography.headlineMedium,
        color = AurumColors.text
    )
    Spacer(Modifier.height(14.dp))
    BandBar(band = band)
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(Fmt.jod(at(band.p10)), style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim, modifier = Modifier.weight(1f))
        Text(Fmt.jod(at(band.p90)), style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim)
    }
    Hint(
        "8 in 10 of ${band.windows} past ${band.horizon}-session stretches of world gold landed in this " +
            "range, and ${Math.round(band.upSharePct)}% ended higher. It doesn’t know today’s trend."
    )
}

@Composable
private fun BandBar(band: MonthBand) {
    val lo = band.p10 - 2.5
    val width = band.p90 + 2.5 - lo
    fun f(x: Double) = ((x - lo) / width).toFloat().coerceIn(0f, 1f)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900, delayMillis = 150, easing = FastOutSlowInEasing)) }
    Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
        val h = size.height
        val g = grow.value
        val cx = size.width * f(0.0)
        fun xs(x: Double) = cx + (size.width * f(x) - cx) * g
        drawRoundRect(
            color = AurumColors.surfaceHigh,
            topLeft = Offset(0f, h * 0.3f),
            size = androidx.compose.ui.geometry.Size(size.width, h * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h)
        )
        drawRoundRect(
            color = AurumColors.gold.copy(alpha = 0.35f),
            topLeft = Offset(xs(band.p10), h * 0.3f),
            size = androidx.compose.ui.geometry.Size(xs(band.p90) - xs(band.p10), h * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h)
        )
        drawRoundRect(
            color = AurumColors.gold,
            topLeft = Offset(xs(band.q1), h * 0.3f),
            size = androidx.compose.ui.geometry.Size(xs(band.q3) - xs(band.q1), h * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h)
        )
        drawLine(AurumColors.text, Offset(xs(band.median), 0f), Offset(xs(band.median), h), strokeWidth = 2.dp.toPx())
        drawCircle(AurumColors.text, radius = 3.dp.toPx(), center = Offset(cx, h / 2))
    }
}

@Composable
private fun WorldTile(state: GoldState, world: Double, dayPct: Double) {
    val list = state.list ?: return
    val buy21 = list.karats[21]?.buy
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AurumCard(modifier = Modifier.weight(1f)) {
            Text("World gold · per ounce", style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            AnimatedMoney(value = world, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
            Text(Fmt.signedPct(dayPct), style = MaterialTheme.typography.labelMedium, color = AurumColors.deltaColor(dayPct))
        }
        if (buy21 != null) {
            AurumCard(modifier = Modifier.weight(1f)) {
                Text("21K next list, if gold holds", style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                AnimatedMoney(
                    value = buy21 * state.factor,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    format = { Fmt.num3(it) },
                    epsilon = 0.0005
                )
                Text("buy, JOD per gram", style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim)
            }
        }
    }
    Hint(
        if (abs(state.driftPct) < 0.005) "World gold hasn’t moved since the ${listLabel(list)} list."
        else "World gold has moved ${Fmt.signedPct(state.driftPct)} since the ${listLabel(list)} list — " +
            "the next list usually follows it."
    )
}

/** "21K", "24K" — the square that leads a gold row. */
fun karatTag(lot: GoldLot): String = "${lot.kind.fixedKarat ?: lot.karat}K"

/** One purchase as a row: what it is, when, what it's worth and how it stands. */
@Composable
fun GoldLotRow(lot: GoldLot, state: GoldState, onClick: () -> Unit, compact: Boolean = false) {
    val list = state.list
    val value = if (list != null) GoldEngine.valueLot(lot, list, state.activeFactor, state.shop) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .pressScale(pressedScale = 0.98f, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconSquare(text = karatTag(lot), tone = if (lot.sold) Tone.NEUTRAL else Tone.GOLD)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lot.name.ifBlank { defaultLotName(lot) },
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(Fmt.qty(round1(lot.grams)))
                    append(" g · ")
                    append(Fmt.dateFull(lot.boughtAt))
                    if (!compact) {
                        append(" · paid ")
                        append(Fmt.num3(lot.paid))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (lot.sold) {
                val pl = (lot.proceeds ?: 0.0) - lot.paid
                Text(Fmt.jod(lot.proceeds ?: 0.0), style = MaterialTheme.typography.titleMedium, color = AurumColors.text)
                Text("Sold · ${Fmt.signedJod(pl).removePrefix("+").let { if (pl >= 0) "+$it" else it }}",
                    style = MaterialTheme.typography.bodyMedium, color = AurumColors.deltaColor(pl))
            } else if (value != null) {
                Text(Fmt.jod(value.value), style = MaterialTheme.typography.titleMedium, color = AurumColors.text)
                Text(
                    text = "${signed3(value.pl)} (${Fmt.signedPct(value.plPct)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.deltaColor(value.pl)
                )
            } else {
                Text("No price", style = MaterialTheme.typography.bodyMedium, color = AurumColors.textDim)
            }
        }
    }
}

fun signed3(v: Double): String = (if (v >= 0) "+" else "−") + Fmt.num3(abs(v))

fun defaultLotName(lot: GoldLot): String = when (lot.kind) {
    GoldKind.RASHADI, GoldKind.ENGLISH ->
        lot.kind.label + if (lot.qty > 1.0) " × ${Fmt.qty(lot.qty)}" else ""
    GoldKind.BAR -> "${lot.karat}K bar"
    GoldKind.JEWELRY -> "${lot.karat}K jewelry"
}
