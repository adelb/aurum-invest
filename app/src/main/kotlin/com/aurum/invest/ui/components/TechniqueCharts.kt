package com.aurum.invest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.invest.analytics.AdxData
import com.aurum.invest.analytics.BollingerData
import com.aurum.invest.analytics.FibonacciData
import com.aurum.invest.analytics.FvgData
import com.aurum.invest.analytics.IchimokuData
import com.aurum.invest.analytics.MaTrendData
import com.aurum.invest.analytics.MacdData
import com.aurum.invest.analytics.ObvData
import com.aurum.invest.analytics.RsiData
import com.aurum.invest.analytics.StochasticData
import com.aurum.invest.analytics.SupportResistanceData
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Technique diagrams for the analysis screen. Same visual language as Charts.kt:
 * smooth cubic paths, vertical padding, TextMeasurer labels. Flat fills only —
 * no gradients anywhere. Series that start with nulls (not enough history yet)
 * simply begin at their first non-null point.
 *
 * Every diagram is windowed through a DiagramViewport: pinch to zoom, drag
 * horizontally to move through time, double-tap to step 1x -> 2x -> 4x -> 1x.
 * A three-label date axis along the bottom always reflects the visible window.
 */

// ---------- viewport (zoom + pan) ----------

/** Visible window over a series of [total] points. Fractions of the whole series. */
class DiagramViewport(val total: Int) {
    var startFrac by mutableFloatStateOf(0f)
    var spanFrac by mutableFloatStateOf(1f)
    var widthPx: Float = 0f

    private val minSpan: Float =
        if (total <= MIN_POINTS) 1f else MIN_POINTS.toFloat() / total

    val start: Int
        get() = (startFrac * total).roundToInt().coerceIn(0, max(0, total - 2))
    val count: Int
        get() = (spanFrac * total).roundToInt().coerceIn(2, total - start)

    fun zoomBy(factor: Float, centerFrac: Float) {
        if (total <= MIN_POINTS || factor <= 0f) return
        val oldSpan = spanFrac
        val newSpan = (oldSpan / factor).coerceIn(minSpan, 1f)
        val anchor = startFrac + centerFrac * oldSpan
        startFrac = (anchor - centerFrac * newSpan).coerceIn(0f, 1f - newSpan)
        spanFrac = newSpan
    }

    fun panByPx(dx: Float) {
        if (widthPx <= 0f || spanFrac >= 0.999f) return
        startFrac = (startFrac - dx / widthPx * spanFrac).coerceIn(0f, 1f - spanFrac)
    }

    /** Double-tap steps: full view -> 2x -> 4x -> back to full, centered on the tap. */
    fun doubleTapCycle(centerFrac: Float) {
        when {
            spanFrac > 0.75f -> zoomBy(2f, centerFrac)
            spanFrac > 0.35f -> zoomBy(2f, centerFrac)
            else -> reset()
        }
    }

    fun reset() {
        startFrac = 0f
        spanFrac = 1f
    }

    companion object {
        const val MIN_POINTS = 15
    }
}

@Composable
fun rememberDiagramViewport(total: Int): DiagramViewport =
    remember(total) { DiagramViewport(total) }

/**
 * Gesture handling for a diagram: two-finger pinch zooms, one-finger horizontal
 * drag pans (vertical drags still scroll the page), double-tap steps the zoom.
 */
fun Modifier.diagramGestures(viewport: DiagramViewport): Modifier = this
    .onSizeChanged { viewport.widthPx = it.width.toFloat() }
    .pointerInput(viewport) {
        detectTapGestures(
            onDoubleTap = { pos ->
                val frac = if (size.width > 0) (pos.x / size.width).coerceIn(0f, 1f) else 0.5f
                viewport.doubleTapCycle(frac)
            }
        )
    }
    .pointerInput(viewport) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid()
                    if (zoom != 1f && size.width > 0) {
                        viewport.zoomBy(zoom, (centroid.x / size.width).coerceIn(0f, 1f))
                    }
                    if (pan.x != 0f) viewport.panByPx(pan.x)
                    event.changes.forEach { it.consume() }
                } else if (pressed.size == 1) {
                    val ch = pressed[0]
                    val dx = ch.position.x - ch.previousPosition.x
                    val dy = ch.position.y - ch.previousPosition.y
                    if (abs(dx) > abs(dy) && viewport.spanFrac < 0.999f) {
                        viewport.panByPx(dx)
                        ch.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** Slice a series to the viewport window. */
private fun <T> List<T>.win(v: DiagramViewport): List<T> {
    if (isEmpty()) return this
    val s = v.start.coerceIn(0, size - 1)
    val e = (s + v.count).coerceIn(s + 1, size)
    return subList(s, e)
}

// ---------- shared geometry ----------

private const val AXIS_H = 26f

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

/** Three date labels (first / middle / last visible) along the bottom strip. */
private fun DrawScope.drawTimeAxis(textMeasurer: TextMeasurer, ts: List<Long>) {
    if (ts.size < 2) return
    val style = chartLabelStyle(AurumColors.textDim)
    val y = size.height - AXIS_H + 8f
    val first = Fmt.dateShort(ts.first())
    val mid = Fmt.dateShort(ts[ts.size / 2])
    val last = Fmt.dateShort(ts.last())
    drawText(textMeasurer, first, topLeft = Offset(0f, y), style = style)
    val mMid = textMeasurer.measure(AnnotatedString(mid), style)
    drawText(
        textMeasurer, mid,
        topLeft = Offset(size.width / 2f - mMid.size.width / 2f, y),
        style = style
    )
    val mLast = textMeasurer.measure(AnnotatedString(last), style)
    drawText(
        textMeasurer, last,
        topLeft = Offset(size.width - mLast.size.width, y),
        style = style
    )
}

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
fun MaTrendDiagram(
    data: MaTrendData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val sma20 = data.sma20.win(viewport)
            val sma50 = data.sma50.win(viewport)
            val vals = ArrayList<Double>(closes.size * 3)
            vals.addAll(closes)
            sma20.forEach { if (it != null) vals.add(it) }
            sma50.forEach { if (it != null) vals.add(it) }
            val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 8f, closes.size)
            strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
            strokeSeries(seriesPoints(sma20, pane), AurumColors.gold, 2.5f)
            strokeSeries(seriesPoints(sma50, pane), AurumColors.info, 2.5f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
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
fun RsiDiagram(
    data: RsiData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(186.dp).diagramGestures(viewport)
    ) {
        val rsi = data.rsi.win(viewport)
        if (rsi.size < 2) return@Canvas
        val pane = Pane(0.0, 100.0, size.width, size.height - AXIS_H, 10f, rsi.size)
        val y70 = pane.y(70.0)
        val y30 = pane.y(30.0)

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

        strokeSeries(seriesPoints(rsi, pane), AurumColors.gold, 2.5f)

        val lastIdx = rsi.indexOfLast { it != null }
        if (lastIdx >= 0) {
            val v = rsi[lastIdx]
            if (v != null) {
                val txt = "RSI ${v.roundToInt()}"
                val goldStyle = chartLabelStyle(AurumColors.gold)
                val m = textMeasurer.measure(AnnotatedString(txt), goldStyle)
                val ty = (pane.y(v) - m.size.height - 4f)
                    .coerceIn(2f, size.height - AXIS_H - m.size.height - 2f)
                drawText(
                    textMeasurer, txt,
                    topLeft = Offset(size.width - m.size.width - 4f, ty),
                    style = goldStyle
                )
            }
        }
        drawTimeAxis(textMeasurer, timestamps.win(viewport))
    }
}

/** Zero-centered pane: gain/loss histogram bars, gold MACD line, blue signal line, legend. */
@Composable
fun MacdDiagram(
    data: MacdData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport)
        ) {
            val macd = data.macd.win(viewport)
            val signal = data.signal.win(viewport)
            val histogram = data.histogram.win(viewport)
            val n = macd.size
            if (n < 2) return@Canvas
            val vals = ArrayList<Double>(n * 3 + 1)
            macd.forEach { if (it != null) vals.add(it) }
            signal.forEach { if (it != null) vals.add(it) }
            histogram.forEach { if (it != null) vals.add(it) }
            if (vals.size < 2) return@Canvas
            vals.add(0.0)
            val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 8f, n)
            val y0 = pane.y(0.0)

            val step = size.width / (n - 1)
            val barW = (step * 0.55f).coerceAtLeast(1.5f)
            histogram.forEachIndexed { i, h ->
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
            strokeSeries(seriesPoints(macd, pane), AurumColors.gold, 2.5f)
            strokeSeries(seriesPoints(signal, pane), AurumColors.info, 2.5f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
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
fun BollingerDiagram(
    data: BollingerData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(186.dp).diagramGestures(viewport)
    ) {
        val closes = data.closes.win(viewport)
        if (closes.size < 2) return@Canvas
        val upper = data.upper.win(viewport)
        val lower = data.lower.win(viewport)
        val middle = data.middle.win(viewport)
        val vals = ArrayList<Double>(closes.size * 4)
        vals.addAll(closes)
        upper.forEach { if (it != null) vals.add(it) }
        lower.forEach { if (it != null) vals.add(it) }
        middle.forEach { if (it != null) vals.add(it) }
        val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 8f, closes.size)

        val upperPts = seriesPoints(upper, pane)
        val lowerPts = seriesPoints(lower, pane)
        if (upperPts.size >= 2 && lowerPts.size >= 2) {
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
        dashedSeries(seriesPoints(middle, pane), AurumColors.textDim.copy(alpha = 0.7f))
        strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
        drawTimeAxis(textMeasurer, timestamps.win(viewport))
    }
}

/** Price line with dashed support (gain) and resistance (loss) levels, prices at the right edge. */
@Composable
fun SupportResistanceDiagram(
    data: SupportResistanceData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(186.dp).diagramGestures(viewport)
    ) {
        val closes = data.closes.win(viewport)
        if (closes.size < 2) return@Canvas
        val vals = ArrayList<Double>(closes.size + 6)
        vals.addAll(closes)
        vals.addAll(data.supports)
        vals.addAll(data.resistances)
        val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 12f, closes.size)

        val levels =
            data.supports.map { it to AurumColors.gain } +
                data.resistances.map { it to AurumColors.loss }

        val measured = levels.map { (level, color) ->
            val style = chartLabelStyle(color)
            val m = textMeasurer.measure(AnnotatedString(Fmt.money(level)), style)
            val y = pane.y(level)
            dashedLevel(y, color.copy(alpha = 0.8f), endX = size.width - m.size.width - 8f)
            Triple(level, color, m)
        }

        strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)

        measured.forEach { (level, color, m) ->
            val y = (pane.y(level) - m.size.height / 2f)
                .coerceIn(0f, size.height - AXIS_H - m.size.height)
            drawText(
                textMeasurer, Fmt.money(level),
                topLeft = Offset(size.width - m.size.width - 2f, y),
                style = chartLabelStyle(color)
            )
        }
        drawTimeAxis(textMeasurer, timestamps.win(viewport))
    }
}

/** Price line with unfilled fair-value-gap zones as flat tinted bands (filled ones faded). */
@Composable
fun FvgDiagram(
    data: FvgData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val startIdx = viewport.start
            val endIdx = startIdx + closes.size
            val visibleZones = data.zones.filter { it.startIndex < endIdx }

            val vals = ArrayList<Double>(closes.size + visibleZones.size * 2)
            vals.addAll(closes)
            visibleZones.forEach { vals.add(it.low); vals.add(it.high) }
            val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 8f, closes.size)

            visibleZones.forEach { zone ->
                val x0 = pane.x((zone.startIndex - startIdx).coerceAtLeast(0))
                val yTop = pane.y(zone.high)
                val yBot = pane.y(zone.low)
                val base = if (zone.bullish) AurumColors.gain else AurumColors.loss
                val alpha = if (zone.filled) 0.08f else 0.22f
                drawRect(
                    color = base.copy(alpha = alpha),
                    topLeft = Offset(x0, yTop),
                    size = Size((size.width - x0).coerceAtLeast(0f), (yBot - yTop).coerceAtLeast(1f))
                )
            }

            strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
        }
        LegendRow(
            entries = listOf(
                "Bullish gap" to AurumColors.gain.copy(alpha = 0.5f),
                "Bearish gap" to AurumColors.loss.copy(alpha = 0.5f),
                "Faded = filled" to AurumColors.textDim
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Price line with dashed Fibonacci retracement levels labeled at the right edge. */
@Composable
fun FibonacciDiagram(
    data: FibonacciData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(196.dp).diagramGestures(viewport)
    ) {
        val closes = data.closes.win(viewport)
        if (closes.size < 2) return@Canvas
        val vals = ArrayList<Double>(closes.size + data.levels.size)
        vals.addAll(closes)
        data.levels.forEach { vals.add(it.second) }
        val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 12f, closes.size)

        data.levels.forEach { (name, level) ->
            val isEdge = name == "0.0" || name == "1.0"
            val color = if (isEdge) AurumColors.textDim else AurumColors.gold
            val style = chartLabelStyle(color)
            val label = "$name  ${Fmt.money(level)}"
            val m = textMeasurer.measure(AnnotatedString(label), style)
            val y = pane.y(level)
            dashedLevel(y, color.copy(alpha = if (isEdge) 0.5f else 0.75f), endX = size.width - m.size.width - 8f)
            drawText(
                textMeasurer, label,
                topLeft = Offset(
                    size.width - m.size.width - 2f,
                    (y - m.size.height / 2f).coerceIn(0f, size.height - AXIS_H - m.size.height)
                ),
                style = style
            )
        }

        strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
        drawTimeAxis(textMeasurer, timestamps.win(viewport))
    }
}

/** Ichimoku: flat cloud between the senkou spans, tenkan (gold), kijun (blue), price. */
@Composable
fun IchimokuDiagram(
    data: IchimokuData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val tenkan = data.tenkan.win(viewport)
            val kijun = data.kijun.win(viewport)
            val senkouA = data.senkouA.win(viewport)
            val senkouB = data.senkouB.win(viewport)

            val vals = ArrayList<Double>(closes.size * 5)
            vals.addAll(closes)
            listOf(tenkan, kijun, senkouA, senkouB).forEach { s ->
                s.forEach { if (it != null) vals.add(it) }
            }
            val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 8f, closes.size)

            // cloud where both spans exist
            val aPts = ArrayList<Offset>()
            val bPts = ArrayList<Offset>()
            for (i in closes.indices) {
                val a = senkouA.getOrNull(i)
                val b = senkouB.getOrNull(i)
                if (a != null && b != null) {
                    aPts.add(Offset(pane.x(i), pane.y(a)))
                    bPts.add(Offset(pane.x(i), pane.y(b)))
                }
            }
            if (aPts.size >= 2) {
                val cloud = Path()
                cloud.moveTo(aPts.first().x, aPts.first().y)
                appendSmooth(cloud, aPts)
                val bRev = bPts.asReversed()
                cloud.lineTo(bRev.first().x, bRev.first().y)
                appendSmooth(cloud, bRev)
                cloud.close()
                drawPath(cloud, color = AurumColors.infoSoft)
            }

            strokeSeries(seriesPoints(tenkan, pane), AurumColors.gold, 2f)
            strokeSeries(seriesPoints(kijun, pane), AurumColors.info, 2f)
            strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
        }
        LegendRow(
            entries = listOf(
                "Price" to priceLineColor,
                "Tenkan 9" to AurumColors.gold,
                "Kijun 26" to AurumColors.info,
                "Cloud" to AurumColors.info.copy(alpha = 0.4f)
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Fixed 0..100 pane with 20/80 zones, %K (gold) and %D (blue) lines, legend. */
@Composable
fun StochasticDiagram(
    data: StochasticData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport)
        ) {
            val k = data.k.win(viewport)
            val d = data.d.win(viewport)
            if (k.size < 2) return@Canvas
            val pane = Pane(0.0, 100.0, size.width, size.height - AXIS_H, 10f, k.size)
            val y80 = pane.y(80.0)
            val y20 = pane.y(20.0)

            drawRect(
                color = AurumColors.surfaceHigh,
                topLeft = Offset(0f, y80),
                size = Size(size.width, y20 - y80)
            )
            val lineColor = AurumColors.textDim.copy(alpha = 0.55f)
            dashedLevel(y80, lineColor)
            dashedLevel(y20, lineColor)

            val dim = chartLabelStyle(AurumColors.textDim)
            val m80 = textMeasurer.measure(AnnotatedString("80"), dim)
            drawText(textMeasurer, "80", topLeft = Offset(4f, y80 - m80.size.height - 2f), style = dim)
            drawText(textMeasurer, "20", topLeft = Offset(4f, y20 + 2f), style = dim)

            strokeSeries(seriesPoints(k, pane), AurumColors.gold, 2.5f)
            strokeSeries(seriesPoints(d, pane), AurumColors.info, 2f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
        }
        LegendRow(
            entries = listOf(
                "%K" to AurumColors.gold,
                "%D" to AurumColors.info
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** OBV and price, each normalized to its own range, overlaid for divergence reads. */
@Composable
fun ObvDiagram(
    data: ObvData,
    closes: List<Double>,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport)
        ) {
            val obv = data.obv.win(viewport)
            val px = closes.win(viewport)
            if (obv.size < 2 || px.size < 2) return@Canvas

            // normalize each to 0..1 in its own range, share one pane
            fun norm(series: List<Double>): List<Double> {
                val mn = series.min()
                val mx = series.max()
                val span = (mx - mn).takeIf { it > 1e-12 } ?: 1.0
                return series.map { (it - mn) / span }
            }

            val pane = Pane(0.0, 1.0, size.width, size.height - AXIS_H, 10f, obv.size)
            strokeSeries(pricePoints(norm(px), pane), priceLineColor.copy(alpha = 0.55f), 2f)
            strokeSeries(pricePoints(norm(obv), pane), AurumColors.gold, 2.5f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
        }
        LegendRow(
            entries = listOf(
                "OBV" to AurumColors.gold,
                "Price (scaled)" to priceLineColor.copy(alpha = 0.55f)
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** ADX (gold) with +DI (green) and -DI (red); dashed 25 line marks trend strength. */
@Composable
fun AdxDiagram(
    data: AdxData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport)
        ) {
            val adx = data.adx.win(viewport)
            val plus = data.plusDi.win(viewport)
            val minus = data.minusDi.win(viewport)
            if (adx.size < 2) return@Canvas

            var maxV = 60.0
            listOf(adx, plus, minus).forEach { s ->
                s.forEach { if (it != null && it > maxV) maxV = it }
            }
            val pane = Pane(0.0, maxV, size.width, size.height - AXIS_H, 10f, adx.size)

            val y25 = pane.y(25.0)
            dashedLevel(y25, AurumColors.textDim.copy(alpha = 0.55f))
            val dim = chartLabelStyle(AurumColors.textDim)
            drawText(textMeasurer, "25", topLeft = Offset(4f, y25 + 2f), style = dim)

            strokeSeries(seriesPoints(plus, pane), AurumColors.gain, 2f)
            strokeSeries(seriesPoints(minus, pane), AurumColors.loss, 2f)
            strokeSeries(seriesPoints(adx, pane), AurumColors.gold, 2.5f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
        }
        LegendRow(
            entries = listOf(
                "ADX" to AurumColors.gold,
                "+DI" to AurumColors.gain,
                "-DI" to AurumColors.loss
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
