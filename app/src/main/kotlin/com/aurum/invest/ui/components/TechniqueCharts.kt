package com.aurum.invest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
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
import com.aurum.invest.analytics.DonchianData
import com.aurum.invest.analytics.FibonacciData
import com.aurum.invest.analytics.FvgData
import com.aurum.invest.analytics.GoldenCrossData
import com.aurum.invest.analytics.IchimokuData
import com.aurum.invest.analytics.MaTrendData
import com.aurum.invest.analytics.MacdData
import com.aurum.invest.analytics.MfiData
import com.aurum.invest.analytics.ObvData
import com.aurum.invest.analytics.PsarData
import com.aurum.invest.analytics.RsiData
import com.aurum.invest.analytics.StochasticData
import com.aurum.invest.analytics.SupportResistanceData
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.Candle
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Technique diagrams for the analysis screen. Same visual language as Charts.kt:
 * smooth cubic paths, vertical padding, TextMeasurer labels. Flat fills only —
 * no gradients anywhere. Series that start with nulls (not enough history yet)
 * simply begin at their first non-null point.
 *
 * Interaction model:
 *  - pinch zooms, one-finger horizontal drag pans, double-tap steps the zoom
 *  - long-press then drag scrubs a crosshair with date + values; tap clears it
 *  - a plain tap (with no crosshair up) reports through [onTap] — the analysis
 *    screen opens the full technique write-up from it
 *
 * Price-based diagrams can render the price series as a line or as Japanese
 * candlesticks via [PriceStyle]; every pane draws a price grid and an adaptive
 * date axis so values and dates are always readable.
 */

/** How the price series is rendered on price-scale diagrams. */
enum class PriceStyle { LINE, CANDLES }

// ---------- viewport (zoom + pan + crosshair) ----------

/** Visible window over a series of [total] points. Fractions of the whole series. */
class DiagramViewport(val total: Int) {
    var startFrac by mutableFloatStateOf(0f)
    var spanFrac by mutableFloatStateOf(1f)
    var widthPx: Float = 0f

    /** Crosshair position as a fraction of the canvas width; null = hidden. */
    var scrubFrac by mutableStateOf<Float?>(null)

    /** True while a long-press scrub drag is in flight (suppresses panning). */
    var scrubbing: Boolean = false

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
        scrubFrac = null
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
 * drag pans (vertical drags still scroll the page), double-tap steps the zoom,
 * long-press + drag scrubs the crosshair, and a plain tap either clears the
 * crosshair or fires [onTap].
 */
fun Modifier.diagramGestures(viewport: DiagramViewport, onTap: (() -> Unit)? = null): Modifier = this
    .onSizeChanged { viewport.widthPx = it.width.toFloat() }
    .pointerInput(viewport, onTap) {
        detectTapGestures(
            onTap = {
                if (viewport.scrubFrac != null) viewport.scrubFrac = null
                else onTap?.invoke()
            },
            onDoubleTap = { pos ->
                val frac = if (size.width > 0) (pos.x / size.width).coerceIn(0f, 1f) else 0.5f
                viewport.doubleTapCycle(frac)
            }
        )
    }
    .pointerInput(viewport) {
        detectDragGesturesAfterLongPress(
            onDragStart = { pos ->
                viewport.scrubbing = true
                if (size.width > 0) {
                    viewport.scrubFrac = (pos.x / size.width).coerceIn(0f, 1f)
                }
            },
            onDrag = { change, _ ->
                if (size.width > 0) {
                    viewport.scrubFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                }
                change.consume()
            },
            onDragEnd = { viewport.scrubbing = false },
            onDragCancel = { viewport.scrubbing = false }
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
                } else if (pressed.size == 1 && !viewport.scrubbing) {
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

// ---------- axes ----------

/**
 * Adaptive date axis: a hairline above the strip and 3-5 evenly spaced labels
 * (count scales with the canvas width) reflecting the visible window.
 */
private fun DrawScope.drawTimeAxis(textMeasurer: TextMeasurer, ts: List<Long>) {
    if (ts.size < 2) return
    val axisTop = size.height - AXIS_H
    drawLine(
        color = AurumColors.hairline,
        start = Offset(0f, axisTop),
        end = Offset(size.width, axisTop),
        strokeWidth = 1f
    )
    val style = chartLabelStyle(AurumColors.textDim)
    val y = axisTop + 8f
    val labels = min(5, max(3, (size.width / 240f).toInt() + 1))
    for (li in 0 until labels) {
        val idx = ((ts.size - 1).toFloat() * li / (labels - 1)).roundToInt().coerceIn(0, ts.size - 1)
        val text = Fmt.dateShort(ts[idx])
        val m = textMeasurer.measure(AnnotatedString(text), style)
        val cx = size.width * li / (labels - 1)
        val tx = when (li) {
            0 -> 0f
            labels - 1 -> size.width - m.size.width
            else -> cx - m.size.width / 2f
        }
        drawText(textMeasurer, text, topLeft = Offset(tx, y), style = style)
        // tick mark
        drawLine(
            color = AurumColors.hairline,
            start = Offset(cx.coerceIn(1f, size.width - 1f), axisTop),
            end = Offset(cx.coerceIn(1f, size.width - 1f), axisTop + 4f),
            strokeWidth = 1f
        )
    }
}

/** "Nice" tick values covering [min]..[max]. */
private fun niceTicks(min: Double, max: Double, target: Int = 4): List<Double> {
    val span = max - min
    if (span <= 1e-12 || target < 1) return emptyList()
    val rawStep = span / target
    val mag = 10.0.pow(floor(log10(rawStep)))
    val norm = rawStep / mag
    val step = when {
        norm < 1.5 -> 1.0
        norm < 3.0 -> 2.0
        norm < 7.0 -> 5.0
        else -> 10.0
    } * mag
    val first = ceil(min / step) * step
    val out = ArrayList<Double>()
    var v = first
    while (v <= max + step * 1e-6) {
        out.add(v)
        v += step
    }
    return out
}

/** Faint horizontal gridlines with left-edge price labels. */
private fun DrawScope.drawPriceGrid(
    textMeasurer: TextMeasurer,
    pane: Pane,
    minV: Double,
    maxV: Double
) {
    val style = chartLabelStyle(AurumColors.textDim.copy(alpha = 0.85f))
    niceTicks(minV, maxV).forEach { level ->
        val y = pane.y(level)
        if (y < 2f || y > size.height - AXIS_H - 2f) return@forEach
        drawLine(
            color = AurumColors.hairline.copy(alpha = 0.55f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
        val m = textMeasurer.measure(AnnotatedString(Fmt.money(level)), style)
        drawText(
            textMeasurer, Fmt.money(level),
            topLeft = Offset(4f, (y - m.size.height - 2f).coerceAtLeast(0f)),
            style = style
        )
    }
}

// ---------- candles ----------

/** Japanese candlesticks: gain/loss bodies between open and close, thin wicks. */
private fun DrawScope.drawCandleSeries(candles: List<Candle>, pane: Pane) {
    val n = candles.size
    if (n < 1) return
    val stepX = if (n > 1) size.width / (n - 1) else size.width
    val bodyW = (stepX * 0.62f).coerceIn(2f, 16f)
    candles.forEachIndexed { i, c ->
        val x = pane.x(i)
        val up = c.close >= c.open
        val color = if (up) AurumColors.gain else AurumColors.loss
        drawLine(
            color = color.copy(alpha = 0.9f),
            start = Offset(x, pane.y(c.high)),
            end = Offset(x, pane.y(c.low)),
            strokeWidth = 1.5f
        )
        val yTop = pane.y(max(c.open, c.close))
        val yBot = pane.y(min(c.open, c.close))
        drawRect(
            color = color,
            topLeft = Offset((x - bodyW / 2f).coerceIn(0f, size.width - bodyW), yTop),
            size = Size(bodyW, (yBot - yTop).coerceAtLeast(1.5f))
        )
    }
}

/**
 * Renders the price series in the requested style. When [style] is CANDLES and
 * [ohlc] is present the candles are drawn; otherwise the smooth close line.
 */
private fun DrawScope.drawPriceSeries(
    closes: List<Double>,
    ohlc: List<Candle>?,
    style: PriceStyle,
    pane: Pane
) {
    if (style == PriceStyle.CANDLES && ohlc != null && ohlc.size == closes.size) {
        drawCandleSeries(ohlc, pane)
    } else {
        strokeSeries(pricePoints(closes, pane), priceLineColor, 3f)
    }
}

/** Adds the OHLC extremes to the pane range when candles will be drawn. */
private fun MutableList<Double>.includeOhlcRange(ohlc: List<Candle>?, style: PriceStyle) {
    if (style == PriceStyle.CANDLES && ohlc != null) {
        ohlc.forEach { add(it.high); add(it.low) }
    }
}

// ---------- crosshair ----------

private class Scrub(val idx: Int, val x: Float)

/** Index + x position of the crosshair inside the visible window, if shown. */
private fun scrubAt(viewport: DiagramViewport, count: Int, width: Float): Scrub? {
    val f = viewport.scrubFrac ?: return null
    if (count < 2 || width <= 0f) return null
    val i = (f * (count - 1)).roundToInt().coerceIn(0, count - 1)
    val stepX = width / (count - 1)
    return Scrub(i, i * stepX)
}

/** Vertical hairline + a flat value plate; the plate flips sides near the edge. */
private fun DrawScope.drawCrosshair(
    textMeasurer: TextMeasurer,
    x: Float,
    title: String,
    lines: List<Pair<String, Color>>
) {
    drawLine(
        color = AurumColors.textDim.copy(alpha = 0.8f),
        start = Offset(x, 0f),
        end = Offset(x, size.height - AXIS_H),
        strokeWidth = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    )
    val titleStyle = chartLabelStyle(AurumColors.text)
    val measured = buildList {
        add(textMeasurer.measure(AnnotatedString(title), titleStyle) to titleStyle.color)
        lines.forEach { (text, color) ->
            add(textMeasurer.measure(AnnotatedString(text), chartLabelStyle(color)) to color)
        }
    }
    val padX = 10f
    val padY = 8f
    val lineGap = 3f
    val boxW = (measured.maxOf { it.first.size.width }) + padX * 2
    val boxH = measured.sumOf { it.first.size.height } + lineGap * (measured.size - 1) + padY * 2
    val bx = if (x + 12f + boxW <= size.width) x + 12f else (x - 12f - boxW).coerceAtLeast(0f)
    val by = 4f
    drawRoundRect(
        color = AurumColors.surfaceHigh.copy(alpha = 0.96f),
        topLeft = Offset(bx, by),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(10f, 10f)
    )
    drawRoundRect(
        color = AurumColors.hairline,
        topLeft = Offset(bx, by),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(width = 1f)
    )
    var ty = by + padY
    measured.forEachIndexed { i, (layout, color) ->
        drawText(
            textMeasurer,
            if (i == 0) title else lines[i - 1].first,
            topLeft = Offset(bx + padX, ty),
            style = chartLabelStyle(color)
        )
        ty += layout.size.height + lineGap
    }
}

/** Crosshair value lines for a price pane: OHLC when candles, close otherwise. */
private fun priceScrubLines(
    idx: Int,
    closes: List<Double>,
    ohlc: List<Candle>?,
    style: PriceStyle
): List<Pair<String, Color>> {
    val c = ohlc?.getOrNull(idx)
    return if (style == PriceStyle.CANDLES && c != null) {
        val color = if (c.close >= c.open) AurumColors.gain else AurumColors.loss
        listOf(
            "O ${Fmt.money(c.open)}   H ${Fmt.money(c.high)}" to priceLineColor,
            "L ${Fmt.money(c.low)}   C ${Fmt.money(c.close)}" to color
        )
    } else {
        listOf("Close ${Fmt.money(closes[idx])}" to priceLineColor)
    }
}

private fun scrubTitle(ts: List<Long>, idx: Int): String =
    ts.getOrNull(idx)?.let { Fmt.dateShort(it) } ?: ""

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

/** Price (line or candles) with SMA 20 (gold) and SMA 50 (blue) overlays plus a legend row. */
@Composable
fun MaTrendDiagram(
    data: MaTrendData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(170.dp).diagramGestures(viewport, onTap)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val candlesW = ohlc?.win(viewport)
            val sma20 = data.sma20.win(viewport)
            val sma50 = data.sma50.win(viewport)
            val vals = ArrayList<Double>(closes.size * 3)
            vals.addAll(closes)
            sma20.forEach { if (it != null) vals.add(it) }
            sma50.forEach { if (it != null) vals.add(it) }
            vals.includeOhlcRange(candlesW, style)
            val minV = vals.min()
            val maxV = vals.max()
            val pane = Pane(minV, maxV, size.width, size.height - AXIS_H, 8f, closes.size)
            drawPriceGrid(textMeasurer, pane, minV, maxV)
            drawPriceSeries(closes, candlesW, style, pane)
            strokeSeries(seriesPoints(sma20, pane), AurumColors.gold, 2.5f)
            strokeSeries(seriesPoints(sma50, pane), AurumColors.info, 2.5f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
            scrubAt(viewport, closes.size, size.width)?.let { sc ->
                val lines = priceScrubLines(sc.idx, closes, candlesW, style).toMutableList()
                sma20.getOrNull(sc.idx)?.let { lines += "SMA 20 ${Fmt.money(it)}" to AurumColors.gold }
                sma50.getOrNull(sc.idx)?.let { lines += "SMA 50 ${Fmt.money(it)}" to AurumColors.info }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
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
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(186.dp).diagramGestures(viewport, onTap)
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
        scrubAt(viewport, rsi.size, size.width)?.let { sc ->
            val lines = buildList {
                rsi.getOrNull(sc.idx)?.let { add("RSI ${it.roundToInt()}" to AurumColors.gold) }
            }
            drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
        }
    }
}

/** Zero-centered pane: gain/loss histogram bars, gold MACD line, blue signal line, legend. */
@Composable
fun MacdDiagram(
    data: MacdData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport, onTap)
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
            scrubAt(viewport, n, size.width)?.let { sc ->
                val lines = buildList {
                    macd.getOrNull(sc.idx)?.let { add("MACD ${sig(it)}" to AurumColors.gold) }
                    signal.getOrNull(sc.idx)?.let { add("Signal ${sig(it)}" to AurumColors.info) }
                    histogram.getOrNull(sc.idx)?.let {
                        add("Hist ${sig(it)}" to if (it >= 0) AurumColors.gain else AurumColors.loss)
                    }
                }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
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

/** Flat blue band between the outer bands, dashed middle line, price on top. */
@Composable
fun BollingerDiagram(
    data: BollingerData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(186.dp).diagramGestures(viewport, onTap)
    ) {
        val closes = data.closes.win(viewport)
        if (closes.size < 2) return@Canvas
        val candlesW = ohlc?.win(viewport)
        val upper = data.upper.win(viewport)
        val lower = data.lower.win(viewport)
        val middle = data.middle.win(viewport)
        val vals = ArrayList<Double>(closes.size * 4)
        vals.addAll(closes)
        upper.forEach { if (it != null) vals.add(it) }
        lower.forEach { if (it != null) vals.add(it) }
        middle.forEach { if (it != null) vals.add(it) }
        vals.includeOhlcRange(candlesW, style)
        val minV = vals.min()
        val maxV = vals.max()
        val pane = Pane(minV, maxV, size.width, size.height - AXIS_H, 8f, closes.size)
        drawPriceGrid(textMeasurer, pane, minV, maxV)

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
        drawPriceSeries(closes, candlesW, style, pane)
        drawTimeAxis(textMeasurer, timestamps.win(viewport))
        scrubAt(viewport, closes.size, size.width)?.let { sc ->
            val lines = priceScrubLines(sc.idx, closes, candlesW, style).toMutableList()
            upper.getOrNull(sc.idx)?.let { lines += "Upper ${Fmt.money(it)}" to AurumColors.info }
            lower.getOrNull(sc.idx)?.let { lines += "Lower ${Fmt.money(it)}" to AurumColors.info }
            drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
        }
    }
}

/** Price with dashed support (gain) and resistance (loss) levels, prices at the right edge. */
@Composable
fun SupportResistanceDiagram(
    data: SupportResistanceData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(186.dp).diagramGestures(viewport, onTap)
    ) {
        val closes = data.closes.win(viewport)
        if (closes.size < 2) return@Canvas
        val candlesW = ohlc?.win(viewport)
        val vals = ArrayList<Double>(closes.size + 6)
        vals.addAll(closes)
        vals.addAll(data.supports)
        vals.addAll(data.resistances)
        vals.includeOhlcRange(candlesW, style)
        val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 12f, closes.size)

        val levels =
            data.supports.map { it to AurumColors.gain } +
                data.resistances.map { it to AurumColors.loss }

        val measured = levels.map { (level, color) ->
            val labelStyle = chartLabelStyle(color)
            val m = textMeasurer.measure(AnnotatedString(Fmt.money(level)), labelStyle)
            val y = pane.y(level)
            dashedLevel(y, color.copy(alpha = 0.8f), endX = size.width - m.size.width - 8f)
            Triple(level, color, m)
        }

        drawPriceSeries(closes, candlesW, style, pane)

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
        scrubAt(viewport, closes.size, size.width)?.let { sc ->
            drawCrosshair(
                textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx),
                priceScrubLines(sc.idx, closes, candlesW, style)
            )
        }
    }
}

/** Price with unfilled fair-value-gap zones as flat tinted bands (filled ones faded). */
@Composable
fun FvgDiagram(
    data: FvgData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(170.dp).diagramGestures(viewport, onTap)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val candlesW = ohlc?.win(viewport)
            val startIdx = viewport.start
            val endIdx = startIdx + closes.size
            val visibleZones = data.zones.filter { it.startIndex < endIdx }

            val vals = ArrayList<Double>(closes.size + visibleZones.size * 2)
            vals.addAll(closes)
            visibleZones.forEach { vals.add(it.low); vals.add(it.high) }
            vals.includeOhlcRange(candlesW, style)
            val minV = vals.min()
            val maxV = vals.max()
            val pane = Pane(minV, maxV, size.width, size.height - AXIS_H, 8f, closes.size)
            drawPriceGrid(textMeasurer, pane, minV, maxV)

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

            drawPriceSeries(closes, candlesW, style, pane)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
            scrubAt(viewport, closes.size, size.width)?.let { sc ->
                drawCrosshair(
                    textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx),
                    priceScrubLines(sc.idx, closes, candlesW, style)
                )
            }
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

/** Price with dashed Fibonacci retracement levels labeled at the right edge. */
@Composable
fun FibonacciDiagram(
    data: FibonacciData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(196.dp).diagramGestures(viewport, onTap)
    ) {
        val closes = data.closes.win(viewport)
        if (closes.size < 2) return@Canvas
        val candlesW = ohlc?.win(viewport)
        val vals = ArrayList<Double>(closes.size + data.levels.size)
        vals.addAll(closes)
        data.levels.forEach { vals.add(it.second) }
        vals.includeOhlcRange(candlesW, style)
        val pane = Pane(vals.min(), vals.max(), size.width, size.height - AXIS_H, 12f, closes.size)

        data.levels.forEach { (name, level) ->
            val isEdge = name == "0.0" || name == "1.0"
            val color = if (isEdge) AurumColors.textDim else AurumColors.gold
            val labelStyle = chartLabelStyle(color)
            val label = "$name  ${Fmt.money(level)}"
            val m = textMeasurer.measure(AnnotatedString(label), labelStyle)
            val y = pane.y(level)
            dashedLevel(y, color.copy(alpha = if (isEdge) 0.5f else 0.75f), endX = size.width - m.size.width - 8f)
            drawText(
                textMeasurer, label,
                topLeft = Offset(
                    size.width - m.size.width - 2f,
                    (y - m.size.height / 2f).coerceIn(0f, size.height - AXIS_H - m.size.height)
                ),
                style = labelStyle
            )
        }

        drawPriceSeries(closes, candlesW, style, pane)
        drawTimeAxis(textMeasurer, timestamps.win(viewport))
        scrubAt(viewport, closes.size, size.width)?.let { sc ->
            drawCrosshair(
                textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx),
                priceScrubLines(sc.idx, closes, candlesW, style)
            )
        }
    }
}

/** Ichimoku: flat cloud between the senkou spans, tenkan (gold), kijun (blue), price. */
@Composable
fun IchimokuDiagram(
    data: IchimokuData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(170.dp).diagramGestures(viewport, onTap)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val candlesW = ohlc?.win(viewport)
            val tenkan = data.tenkan.win(viewport)
            val kijun = data.kijun.win(viewport)
            val senkouA = data.senkouA.win(viewport)
            val senkouB = data.senkouB.win(viewport)

            val vals = ArrayList<Double>(closes.size * 5)
            vals.addAll(closes)
            listOf(tenkan, kijun, senkouA, senkouB).forEach { s ->
                s.forEach { if (it != null) vals.add(it) }
            }
            vals.includeOhlcRange(candlesW, style)
            val minV = vals.min()
            val maxV = vals.max()
            val pane = Pane(minV, maxV, size.width, size.height - AXIS_H, 8f, closes.size)
            drawPriceGrid(textMeasurer, pane, minV, maxV)

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
            drawPriceSeries(closes, candlesW, style, pane)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
            scrubAt(viewport, closes.size, size.width)?.let { sc ->
                val lines = priceScrubLines(sc.idx, closes, candlesW, style).toMutableList()
                tenkan.getOrNull(sc.idx)?.let { lines += "Tenkan ${Fmt.money(it)}" to AurumColors.gold }
                kijun.getOrNull(sc.idx)?.let { lines += "Kijun ${Fmt.money(it)}" to AurumColors.info }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
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
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport, onTap)
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
            scrubAt(viewport, k.size, size.width)?.let { sc ->
                val lines = buildList {
                    k.getOrNull(sc.idx)?.let { add("%K ${it.roundToInt()}" to AurumColors.gold) }
                    d.getOrNull(sc.idx)?.let { add("%D ${it.roundToInt()}" to AurumColors.info) }
                }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
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
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport, onTap)
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
            scrubAt(viewport, obv.size, size.width)?.let { sc ->
                val lines = buildList {
                    obv.getOrNull(sc.idx)?.let { add("OBV ${Fmt.compact(it)}" to AurumColors.gold) }
                    px.getOrNull(sc.idx)?.let { add("Close ${Fmt.money(it)}" to priceLineColor) }
                }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
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
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(158.dp).diagramGestures(viewport, onTap)
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
            scrubAt(viewport, adx.size, size.width)?.let { sc ->
                val lines = buildList {
                    adx.getOrNull(sc.idx)?.let { add("ADX ${it.roundToInt()}" to AurumColors.gold) }
                    plus.getOrNull(sc.idx)?.let { add("+DI ${it.roundToInt()}" to AurumColors.gain) }
                    minus.getOrNull(sc.idx)?.let { add("-DI ${it.roundToInt()}" to AurumColors.loss) }
                }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
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

/** Price inside the 20-day Donchian channel: flat band, dashed middle, price on top. */
@Composable
fun DonchianDiagram(
    data: DonchianData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(170.dp).diagramGestures(viewport, onTap)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val candlesW = ohlc?.win(viewport)
            val upper = data.upper.win(viewport)
            val lower = data.lower.win(viewport)
            val middle = data.middle.win(viewport)
            val vals = ArrayList<Double>(closes.size * 3)
            vals.addAll(closes)
            upper.forEach { if (it != null) vals.add(it) }
            lower.forEach { if (it != null) vals.add(it) }
            vals.includeOhlcRange(candlesW, style)
            val minV = vals.min()
            val maxV = vals.max()
            val pane = Pane(minV, maxV, size.width, size.height - AXIS_H, 8f, closes.size)
            drawPriceGrid(textMeasurer, pane, minV, maxV)

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
                drawPath(band, color = AurumColors.goldSoft)
                val edge = AurumColors.gold.copy(alpha = 0.55f)
                strokeSeries(upperPts, edge, 1.5f)
                strokeSeries(lowerPts, edge, 1.5f)
            }
            dashedSeries(seriesPoints(middle, pane), AurumColors.textDim.copy(alpha = 0.7f))
            drawPriceSeries(closes, candlesW, style, pane)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
            scrubAt(viewport, closes.size, size.width)?.let { sc ->
                val lines = priceScrubLines(sc.idx, closes, candlesW, style).toMutableList()
                upper.getOrNull(sc.idx)?.let { lines += "20d high ${Fmt.money(it)}" to AurumColors.gold }
                lower.getOrNull(sc.idx)?.let { lines += "20d low ${Fmt.money(it)}" to AurumColors.gold }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
        }
        LegendRow(
            entries = listOf(
                "Price" to priceLineColor,
                "20-day channel" to AurumColors.gold.copy(alpha = 0.7f),
                "Middle" to AurumColors.textDim
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Parabolic SAR dots (green below price in uptrends, red above in downtrends). */
@Composable
fun PsarDiagram(
    data: PsarData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(170.dp).diagramGestures(viewport, onTap)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val candlesW = ohlc?.win(viewport)
            val sar = data.sar.win(viewport)
            val bull = data.bullish.win(viewport)
            val vals = ArrayList<Double>(closes.size * 2)
            vals.addAll(closes)
            sar.forEach { if (it != null) vals.add(it) }
            vals.includeOhlcRange(candlesW, style)
            val minV = vals.min()
            val maxV = vals.max()
            val pane = Pane(minV, maxV, size.width, size.height - AXIS_H, 8f, closes.size)
            drawPriceGrid(textMeasurer, pane, minV, maxV)
            drawPriceSeries(closes, candlesW, style, pane)
            sar.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val color = if (bull.getOrNull(i) == true) AurumColors.gain else AurumColors.loss
                drawCircle(
                    color = color.copy(alpha = 0.85f),
                    radius = 3f,
                    center = Offset(pane.x(i), pane.y(v))
                )
            }
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
            scrubAt(viewport, closes.size, size.width)?.let { sc ->
                val lines = priceScrubLines(sc.idx, closes, candlesW, style).toMutableList()
                sar.getOrNull(sc.idx)?.let {
                    val up = bull.getOrNull(sc.idx) == true
                    lines += "SAR ${Fmt.money(it)}" to if (up) AurumColors.gain else AurumColors.loss
                }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
        }
        LegendRow(
            entries = listOf(
                "Price" to priceLineColor,
                "SAR below = uptrend" to AurumColors.gain,
                "SAR above = downtrend" to AurumColors.loss
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Fixed 0..100 pane with the 20/80 money-flow zones, gold MFI line. */
@Composable
fun MfiDiagram(
    data: MfiData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier.fillMaxWidth().height(186.dp).diagramGestures(viewport, onTap)
    ) {
        val mfi = data.mfi.win(viewport)
        if (mfi.size < 2) return@Canvas
        val pane = Pane(0.0, 100.0, size.width, size.height - AXIS_H, 10f, mfi.size)
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

        strokeSeries(seriesPoints(mfi, pane), AurumColors.gold, 2.5f)

        val lastIdx = mfi.indexOfLast { it != null }
        if (lastIdx >= 0) {
            val v = mfi[lastIdx]
            if (v != null) {
                val txt = "MFI ${v.roundToInt()}"
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
        scrubAt(viewport, mfi.size, size.width)?.let { sc ->
            val lines = buildList {
                mfi.getOrNull(sc.idx)?.let { add("MFI ${it.roundToInt()}" to AurumColors.gold) }
            }
            drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
        }
    }
}

/** Price with the 50-day (gold) and 200-day (blue) averages — the cross regime. */
@Composable
fun GoldenCrossDiagram(
    data: GoldenCrossData,
    timestamps: List<Long>,
    viewport: DiagramViewport,
    modifier: Modifier = Modifier,
    ohlc: List<Candle>? = null,
    style: PriceStyle = PriceStyle.LINE,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(170.dp).diagramGestures(viewport, onTap)
        ) {
            val closes = data.closes.win(viewport)
            if (closes.size < 2) return@Canvas
            val candlesW = ohlc?.win(viewport)
            val sma50 = data.sma50.win(viewport)
            val sma200 = data.sma200.win(viewport)
            val vals = ArrayList<Double>(closes.size * 3)
            vals.addAll(closes)
            sma50.forEach { if (it != null) vals.add(it) }
            sma200.forEach { if (it != null) vals.add(it) }
            vals.includeOhlcRange(candlesW, style)
            val minV = vals.min()
            val maxV = vals.max()
            val pane = Pane(minV, maxV, size.width, size.height - AXIS_H, 8f, closes.size)
            drawPriceGrid(textMeasurer, pane, minV, maxV)
            drawPriceSeries(closes, candlesW, style, pane)
            strokeSeries(seriesPoints(sma50, pane), AurumColors.gold, 2.5f)
            strokeSeries(seriesPoints(sma200, pane), AurumColors.info, 2.5f)
            drawTimeAxis(textMeasurer, timestamps.win(viewport))
            scrubAt(viewport, closes.size, size.width)?.let { sc ->
                val lines = priceScrubLines(sc.idx, closes, candlesW, style).toMutableList()
                sma50.getOrNull(sc.idx)?.let { lines += "SMA 50 ${Fmt.money(it)}" to AurumColors.gold }
                sma200.getOrNull(sc.idx)?.let { lines += "SMA 200 ${Fmt.money(it)}" to AurumColors.info }
                drawCrosshair(textMeasurer, sc.x, scrubTitle(timestamps.win(viewport), sc.idx), lines)
            }
        }
        LegendRow(
            entries = listOf(
                "Price" to priceLineColor,
                "SMA 50" to AurumColors.gold,
                "SMA 200" to AurumColors.info
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// ---------- private formatting ----------

/** Adaptive precision for small MACD magnitudes. */
private fun sig(v: Double): String =
    if (abs(v) < 0.05) String.format(java.util.Locale.US, "%.3f", v)
    else String.format(java.util.Locale.US, "%.2f", v)
