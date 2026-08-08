package com.aurum.invest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.invest.analytics.BollingerData
import com.aurum.invest.analytics.MaTrendData
import com.aurum.invest.analytics.MacdData
import com.aurum.invest.analytics.RsiData
import com.aurum.invest.analytics.SupportResistanceData
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Technique diagrams for the analysis screen. Same visual language as Charts.kt:
 * smooth cubic paths, vertical padding, TextMeasurer labels. Flat fills only —
 * no gradients anywhere. Series that start with nulls (not enough history yet)
 * simply begin at their first non-null point.
 */

// ---------- shared geometry ----------

/** Maps series indices/values into canvas space with a fixed value range. */
private class Pane(
    private val min: Double,
    max: Double,
    width: Float,
    private val height: Float,
    private val padY: Float,
    count: Int
) {
    private val span = (max - min).takeIf { it > 1e-12 } ?: 1.0
    private val stepX = if (count > 1) width / (count - 1) else width

    fun x(i: Int): Float = i * stepX
    fun y(v: Double): Float = padY + (1f - ((v - min) / span).toFloat()) * (height - 2 * padY)
}

private fun appendSmooth(path: Path, points: List<Offset>) {
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2f
        path.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
    }
}

private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    appendSmooth(path, points)
    return path
}

/** Offsets for a nullable series; leading nulls are skipped. */
private fun seriesPoints(values: List<Double?>, pane: Pane): List<Offset> =
    values.mapIndexedNotNull { i, v -> v?.let { Offset(pane.x(i), pane.y(it)) } }

private fun pricePoints(closes: List<Double>, pane: Pane): List<Offset> =
    closes.mapIndexed { i, v -> Offset(pane.x(i), pane.y(v)) }

private fun DrawScope.strokeSeries(points: List<Offset>, color: Color, width: Float) {
    if (points.size < 2) return
    drawPath(buildSmoothPath(points), color = color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.dashedSeries(points: List<Offset>, color: Color, width: Float = 1.5f) {
    if (points.size < 2) return
    drawPath(
        buildSmoothPath(points),
        color = color,
        style = Stroke(
            width = width,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )
    )
}

private fun DrawScope.dashedLevel(y: Float, color: Color, endX: Float = size.width) {
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(endX, y),
        strokeWidth = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
    )
}

private fun chartLabelStyle(color: Color) = TextStyle(
    color = color,
    fontSize = 10.sp,
    fontWeight = FontWeight.Medium
)

private val priceLineColor = AurumColors.text.copy(alpha = 0.85f)

// ---------- legend ----------

@Composable
private fun LegendRow(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

// ---------- diagrams ----------

/** Price line with SMA 20 (gold) and SMA 50 (blue) overlays plus a legend row. */
@Composable
fun MaTrendDiagram(data: MaTrendData, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(142.dp)) {
            val closes = data.closes
            if (closes.size < 2) return@Canvas
            val vals = ArrayList<Double>(closes.size * 3)
            vals.addAll(closes)
            data.sma20.forEach { if (it != null) vals.add(it) }
            data.sma50.forEach { if (it != null) vals.add(it) }
            val pane = Pane(vals.min(), vals.max(), size.width, size.height, 8f, closes.size)
            strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
            strokeSeries(seriesPoints(data.sma20, pane), AurumColors.gold, 2.5f)
            strokeSeries(seriesPoints(data.sma50, pane), AurumColors.info, 2.5f)
        }
        LegendRow(
            entries = listOf(
                "Price" to priceLineColor,
                "SMA 20" to AurumColors.gold,
                "SMA 50" to AurumColors.info
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Fixed 0..100 pane: flat neutral band 30..70, dashed 30/70 lines, gold RSI line. */
@Composable
fun RsiDiagram(data: RsiData, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxWidth().height(170.dp)) {
        if (data.rsi.size < 2) return@Canvas
        val pane = Pane(0.0, 100.0, size.width, size.height, 10f, data.rsi.size)
        val y70 = pane.y(70.0)
        val y30 = pane.y(30.0)

        // flat tint band between the thresholds
        drawRect(
            color = AurumColors.surfaceHigh,
            topLeft = Offset(0f, y70),
            size = Size(size.width, y30 - y70)
        )
        val lineColor = AurumColors.textDim.copy(alpha = 0.55f)
        dashedLevel(y70, lineColor)
        dashedLevel(y30, lineColor)

        val dim = chartLabelStyle(AurumColors.textDim)
        val m70 = textMeasurer.measure(AnnotatedString("70"), dim)
        drawText(textMeasurer, "70", topLeft = Offset(4f, y70 - m70.size.height - 2f), style = dim)
        drawText(textMeasurer, "30", topLeft = Offset(4f, y30 + 2f), style = dim)

        strokeSeries(seriesPoints(data.rsi, pane), AurumColors.gold, 2.5f)

        // current value at the right edge
        val lastIdx = data.rsi.indexOfLast { it != null }
        if (lastIdx >= 0) {
            val v = data.rsi[lastIdx] ?: return@Canvas
            val txt = "RSI ${v.roundToInt()}"
            val goldStyle = chartLabelStyle(AurumColors.gold)
            val m = textMeasurer.measure(AnnotatedString(txt), goldStyle)
            val ty = (pane.y(v) - m.size.height - 4f)
                .coerceIn(2f, size.height - m.size.height - 2f)
            drawText(
                textMeasurer, txt,
                topLeft = Offset(size.width - m.size.width - 4f, ty),
                style = goldStyle
            )
        }
    }
}

/** Zero-centered pane: gain/loss histogram bars, gold MACD line, blue signal line, legend. */
@Composable
fun MacdDiagram(data: MacdData, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(142.dp)) {
            val n = data.macd.size
            if (n < 2) return@Canvas
            val vals = ArrayList<Double>(n * 3 + 1)
            data.macd.forEach { if (it != null) vals.add(it) }
            data.signal.forEach { if (it != null) vals.add(it) }
            data.histogram.forEach { if (it != null) vals.add(it) }
            if (vals.size < 2) return@Canvas
            vals.add(0.0)
            val pane = Pane(vals.min(), vals.max(), size.width, size.height, 8f, n)
            val y0 = pane.y(0.0)

            // histogram bars, flat gain/loss tints
            val step = size.width / (n - 1)
            val barW = (step * 0.55f).coerceAtLeast(1.5f)
            data.histogram.forEachIndexed { i, h ->
                if (h == null || h == 0.0) return@forEachIndexed
                val yv = pane.y(h)
                val barColor =
                    if (h > 0.0) AurumColors.gain.copy(alpha = 0.4f)
                    else AurumColors.loss.copy(alpha = 0.4f)
                drawRect(
                    color = barColor,
                    topLeft = Offset(
                        (pane.x(i) - barW / 2f).coerceIn(0f, size.width - barW),
                        minOf(yv, y0)
                    ),
                    size = Size(barW, abs(yv - y0))
                )
            }

            dashedLevel(y0, AurumColors.textDim.copy(alpha = 0.55f))
            strokeSeries(seriesPoints(data.macd, pane), AurumColors.gold, 2.5f)
            strokeSeries(seriesPoints(data.signal, pane), AurumColors.info, 2.5f)
        }
        LegendRow(
            entries = listOf(
                "MACD" to AurumColors.gold,
                "Signal" to AurumColors.info,
                "Histogram" to AurumColors.textDim
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Flat blue band between the outer bands, dashed middle line, price line on top. */
@Composable
fun BollingerDiagram(data: BollingerData, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(170.dp)) {
        val closes = data.closes
        if (closes.size < 2) return@Canvas
        val vals = ArrayList<Double>(closes.size * 4)
        vals.addAll(closes)
        data.upper.forEach { if (it != null) vals.add(it) }
        data.lower.forEach { if (it != null) vals.add(it) }
        data.middle.forEach { if (it != null) vals.add(it) }
        val pane = Pane(vals.min(), vals.max(), size.width, size.height, 8f, closes.size)

        val upperPts = seriesPoints(data.upper, pane)
        val lowerPts = seriesPoints(data.lower, pane)
        if (upperPts.size >= 2 && lowerPts.size >= 2) {
            // flat translucent band between upper and lower
            val band = Path()
            band.moveTo(upperPts.first().x, upperPts.first().y)
            appendSmooth(band, upperPts)
            val lowerRev = lowerPts.asReversed()
            band.lineTo(lowerRev.first().x, lowerRev.first().y)
            appendSmooth(band, lowerRev)
            band.close()
            drawPath(band, color = AurumColors.infoSoft)

            val edge = AurumColors.info.copy(alpha = 0.6f)
            strokeSeries(upperPts, edge, 1.5f)
            strokeSeries(lowerPts, edge, 1.5f)
        }
        dashedSeries(seriesPoints(data.middle, pane), AurumColors.textDim.copy(alpha = 0.7f))
        strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
    }
}

/** Price line with dashed support (gain) and resistance (loss) levels, prices at the right edge. */
@Composable
fun SupportResistanceDiagram(data: SupportResistanceData, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.fillMaxWidth().height(170.dp)) {
        val closes = data.closes
        if (closes.size < 2) return@Canvas
        val vals = ArrayList<Double>(closes.size + 6)
        vals.addAll(closes)
        vals.addAll(data.supports)
        vals.addAll(data.resistances)
        val pane = Pane(vals.min(), vals.max(), size.width, size.height, 12f, closes.size)

        val levels =
            data.supports.map { it to AurumColors.gain } +
                data.resistances.map { it to AurumColors.loss }

        // dashed level lines, shortened so labels sit clear at the right edge
        val measured = levels.map { (level, color) ->
            val style = chartLabelStyle(color)
            val m = textMeasurer.measure(AnnotatedString(Fmt.money(level)), style)
            val y = pane.y(level)
            dashedLevel(y, color.copy(alpha = 0.8f), endX = size.width - m.size.width - 8f)
            Triple(level, color, m)
        }

        strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)

        // level prices on top, vertically centered on their lines
        measured.forEach { (level, color, m) ->
            val y = (pane.y(level) - m.size.height / 2f)
                .coerceIn(0f, size.height - m.size.height)
            drawText(
                textMeasurer, Fmt.money(level),
                topLeft = Offset(size.width - m.size.width - 2f, y),
                style = chartLabelStyle(color)
            )
        }
    }
}
