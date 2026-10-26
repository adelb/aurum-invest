package com.aurum.invest.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.invest.analytics.GoldEngine
import com.aurum.invest.core.Dates
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.Lead
import com.aurum.invest.ui.components.Sentence
import com.aurum.invest.ui.components.Tone
import com.aurum.invest.ui.components.ToneTag
import com.aurum.invest.ui.components.popIn
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Everything the user put in, and what it has made — stocks and gold
 * together, in dollars.
 *
 * With a wallet set, the stock side is the wallet's capital and its total
 * P/L (realized + open), so a closed winner still counts; without one, it is
 * the open positions' cost and their unrealized P/L. Gold is its purchase
 * price (making included) and its P/L at the Syndicate buy price, converted
 * at the peg. By construction, put in + made = the net worth above it.
 */
private class Growth(val putIn: Double, val made: Double) {
    val pct: Double get() = made / putIn * 100.0
}

private fun growthOf(state: DashboardState): Growth? {
    val wallet = state.wallet
    val summary = state.summary
    val book = state.goldBook
    val gold = book.lots.isNotEmpty()
    val goldIn = if (gold) GoldEngine.jodToUsd(book.paid) else 0.0
    val goldMade = if (gold) GoldEngine.jodToUsd(book.pl) else 0.0
    val (stockIn, stockMade) = when {
        wallet.configured -> wallet.total to wallet.totalPl
        summary != null -> summary.investedCost to summary.unrealizedPl
        else -> 0.0 to 0.0
    }
    val putIn = stockIn + goldIn
    if (putIn <= 0.0) return null
    return Growth(putIn, stockMade + goldMade)
}

/** A plain word for where the dial points. */
private fun gradeOf(pct: Double): String = when {
    pct >= 5.0 -> "Growing well"
    pct >= 0.0 -> "Growing"
    pct >= -5.0 -> "A little under"
    else -> "Under water"
}

/**
 * Home's headline, read like a Toss credit score: the net worth, then one
 * needle on a dial that says how much it has grown on what was put in, with
 * the day, the stocks and the gold underneath.
 *
 * The dial reads ±20% and widens in steps of 10 when the return goes past
 * it, so the needle never pins at an end and lies about how far out it is.
 * On the first visit ([swingIn]) the needle swings up from the far left and
 * settles with a little overshoot; after that it simply follows the price.
 */
@Composable
fun NetWorthHero(
    state: DashboardState,
    rollIn: Boolean,
    swingIn: Boolean,
    onSwung: () -> Unit
) {
    val growth = growthOf(state)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Sentence(
            if (state.wallet.configured || !state.goldBook.isEmpty) "Your net worth is"
            else "Your holdings are worth"
        )
        AnimatedMoney(
            value = state.netWorth,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp, lineHeight = 44.sp),
            rollIn = rollIn
        )
        if (growth == null) {
            if (state.loading) {
                Spacer(Modifier.height(10.dp))
                Lead("Pricing your book…")
            }
            return@Column
        }
        LaunchedEffect(Unit) { onSwung() }
        val pct = growth.pct
        val gain = pct >= 0.0
        Spacer(Modifier.height(14.dp))
        Dial(pct = pct, swingIn = swingIn)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ToneTag(
                text = gradeOf(pct),
                tone = if (gain) Tone.GAIN else Tone.LOSS,
                modifier = Modifier.popIn(delayMs = if (swingIn) 900 else 0)
            )
            Spacer(Modifier.height(8.dp))
            AnimatedMoney(
                value = pct,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold),
                baseColor = AurumColors.deltaColor(pct),
                format = { Fmt.signedPct(it) },
                rollIn = rollIn
            )
            Text(
                text = "on the ${Fmt.moneyExact(growth.putIn)} you put in · ${Fmt.signedMoneyExact(growth.made)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = AurumColors.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Tiles(state)
    }
}

/** The day, the stocks and the gold — the three numbers behind the needle. */
@Composable
private fun Tiles(state: DashboardState) {
    val summary = state.summary
    val book = state.goldBook
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (summary != null) {
            Tile(
                label = if (Dates.usMarketOpenedToday()) "Today" else "Last session",
                value = summary.dayPl,
                format = { Fmt.signedMoneyExact(it) },
                modifier = Modifier.weight(1f)
            )
            if (summary.investedCost > 0.0) {
                Tile(
                    label = "Stocks",
                    value = summary.unrealizedPl / summary.investedCost * 100.0,
                    format = { Fmt.signedPct(it) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (book.lots.isNotEmpty()) {
            Tile(
                label = "Gold",
                value = book.plPct,
                format = { Fmt.signedPct(it) },
                modifier = Modifier.weight(1f)
            )
        } else if (state.wallet.configured) {
            Tile(
                label = "Free cash",
                value = state.wallet.liquidity,
                format = { Fmt.moneyExact(it) },
                color = if (state.wallet.shortfall) AurumColors.loss else AurumColors.text,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Tile(
    label: String,
    value: Double,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    color: Color = AurumColors.deltaColor(value)
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AurumColors.surface)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = AurumColors.textDim,
            maxLines = 1
        )
        AnimatedMoney(
            value = value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
            baseColor = color,
            format = format,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * The semicircle: a grey track, an arc from 0% (straight up) to the return
 * in gain or loss, ticks at the quarters, and a needle. Everything moving is
 * read in the draw phase, so the swing costs no recompositions.
 */
@Composable
private fun Dial(pct: Double, swingIn: Boolean) {
    val span = maxOf(20.0, ceil(abs(pct) / 10.0) * 10.0).coerceAtMost(100.0)
    val target = (pct / span).toFloat().coerceIn(-1f, 1f) * 90f
    val needle = remember { Animatable(if (swingIn) -90f else target) }
    LaunchedEffect(target) {
        needle.animateTo(target, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = AurumColors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val spanLabel = span.toInt()
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .semantics {
                contentDescription = "Return gauge, ${Fmt.signedPct(pct)} on a scale of " +
                    "minus $spanLabel to plus $spanLabel percent"
            }
    ) {
        val stroke = 18.dp.toPx()
        val top = 34.dp.toPx()
        val r = min(size.width / 2f - 30.dp.toPx(), size.height - top - 30.dp.toPx())
        val c = Offset(size.width / 2f, top + r)
        val arcTopLeft = Offset(c.x - r, c.y - r)
        val arcSize = Size(r * 2, r * 2)

        drawArc(
            color = AurumColors.surfaceHigh,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val at = needle.value
        if (abs(at) > 0.5f) {
            drawArc(
                color = if (at >= 0f) AurumColors.gain else AurumColors.loss,
                startAngle = 270f,
                sweepAngle = at,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }

        // Ticks at -span, -span/2, 0, +span/2, +span.
        val tickIn = r + stroke / 2f + 4.dp.toPx()
        val tickOut = tickIn + 7.dp.toPx()
        for (deg in floatArrayOf(-90f, -45f, 0f, 45f, 90f)) {
            val a = Math.toRadians((deg - 90f).toDouble())
            val d = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(
                color = AurumColors.surfaceTop,
                start = c + d * tickIn,
                end = c + d * tickOut,
                strokeWidth = 2.dp.toPx()
            )
        }

        // Scale labels: the two ends under the arc, 0% over its crown.
        fun label(text: String, x: Float, y: Float) {
            val layout = measurer.measure(text, labelStyle)
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y))
        }
        val under = c.y + stroke / 2f + 6.dp.toPx()
        label("−$spanLabel%", c.x - r, under)
        label("+$spanLabel%", c.x + r, under)
        val crown = measurer.measure("0%", labelStyle)
        drawText(
            crown,
            topLeft = Offset(c.x - crown.size.width / 2f, c.y - tickOut - 4.dp.toPx() - crown.size.height)
        )

        // The needle and its hub.
        val a = Math.toRadians((at - 90f).toDouble())
        val tip = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r - stroke / 2f - 10.dp.toPx())
        drawLine(
            color = AurumColors.text,
            start = c,
            end = tip,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = AurumColors.text, radius = 10.dp.toPx(), center = c)
        drawCircle(color = AurumColors.bg, radius = 4.dp.toPx(), center = c)
    }
}
