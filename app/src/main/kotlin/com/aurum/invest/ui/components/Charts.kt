package com.aurum.invest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.roundToInt

private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2f
        path.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
    }
    return path
}

private fun normalize(
    data: List<Double>,
    width: Float,
    height: Float,
    padY: Float
): List<Offset> {
    val min = data.min()
    val max = data.max()
    val span = (max - min).takeIf { it > 1e-12 } ?: 1.0
    val stepX = if (data.size > 1) width / (data.size - 1) else width
    return data.mapIndexed { i, v ->
        val yNorm = ((v - min) / span).toFloat()
        Offset(i * stepX, padY + (1f - yNorm) * (height - 2 * padY))
    }
}

private fun DrawScope.drawLineWithFill(
    points: List<Offset>,
    color: Color,
    strokeWidthPx: Float,
    fill: Boolean
) {
    if (points.size < 2) return
    val path = buildSmoothPath(points)
    if (fill) {
        val fillPath = Path().apply {
            addPath(path)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)),
                startY = 0f,
                endY = size.height
            )
        )
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round))
}

/** Tiny trend line. Auto-colors by direction when [color] is null. */
@Composable
fun Sparkline(
    data: List<Double>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fill: Boolean = true,
    strokeWidth: Dp = 2.dp
) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val c = color ?: if (data.last() >= data.first()) AurumColors.gain else AurumColors.loss
        val points = normalize(data, size.width, size.height, padY = strokeWidth.toPx())
        drawLineWithFill(points, c, strokeWidth.toPx(), fill)
    }
}

/**
 * Full price chart: smooth gradient line, optional dashed [baseline]
 * (e.g. previous close or avg cost), min/max labels, glowing last-price dot.
 */
@Composable
fun PriceChart(
    closes: List<Double>,
    modifier: Modifier = Modifier,
    baseline: Double? = null,
    color: Color? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = AurumColors.textDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
    Canvas(modifier = modifier) {
        if (closes.size < 2) return@Canvas
        val c = color ?: if (closes.last() >= closes.first()) AurumColors.gain else AurumColors.loss
        val padY = 26f
        val points = normalize(closes, size.width, size.height, padY)
        drawLineWithFill(points, c, 4f, fill = true)

        // dashed baseline, only when it falls inside the visible range
        val min = closes.min()
        val max = closes.max()
        if (baseline != null && baseline in min..max && max - min > 1e-12) {
            val yNorm = ((baseline - min) / (max - min)).toFloat()
            val y = padY + (1f - yNorm) * (size.height - 2 * padY)
            drawLine(
                color = AurumColors.textDim.copy(alpha = 0.55f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )
        }

        // last-price dot with a soft glow
        val last = points.last()
        drawCircle(color = c.copy(alpha = 0.25f), radius = 14f, center = last)
        drawCircle(color = c, radius = 6f, center = last)

        // min / max labels
        val maxText = AnnotatedString(Fmt.money(max))
        val minText = AnnotatedString(Fmt.money(min))
        val maxSize = textMeasurer.measure(maxText, labelStyle).size
        drawText(
            textMeasurer, maxText.text,
            topLeft = Offset(size.width - maxSize.width - 4f, 2f),
            style = labelStyle
        )
        val minSize = textMeasurer.measure(minText, labelStyle).size
        drawText(
            textMeasurer, minText.text,
            topLeft = Offset(size.width - minSize.width - 4f, size.height - minSize.height - 2f),
            style = labelStyle
        )
    }
}

/**
 * Interactive price chart. Pinch zooms, one-finger horizontal drag pans,
 * double-tap steps the zoom (1x → 2x → 4x → reset), long-press then drag
 * scrubs a crosshair with the exact price and time of the bar under the
 * finger. Renders the visible window with price gridlines, a date axis,
 * an optional dashed [baseline], and the glowing last-price dot.
 *
 * [timestamps] must be index-aligned with [closes]; when sizes differ the
 * date axis and crosshair time are simply omitted.
 */
@Composable
fun ZoomablePriceChart(
    closes: List<Double>,
    timestamps: List<Long>,
    modifier: Modifier = Modifier,
    baseline: Double? = null,
    color: Color? = null
) {
    val viewport = rememberDiagramViewport(closes.size)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = AurumColors.textDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
    val bubbleStyle = TextStyle(
        color = AurumColors.text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
    Canvas(modifier = modifier.diagramGestures(viewport)) {
        if (closes.size < 2) return@Canvas
        val start = viewport.start.coerceIn(0, closes.size - 2)
        val count = viewport.count.coerceIn(2, closes.size - start)
        val win = closes.subList(start, start + count)
        val ts =
            if (timestamps.size == closes.size) timestamps.subList(start, start + count)
            else emptyList()

        val axisH = if (ts.isEmpty()) 0f else 28f
        val chartH = size.height - axisH
        val padY = 26f
        val c = color ?: if (win.last() >= win.first()) AurumColors.gain else AurumColors.loss
        val min = win.min()
        val max = win.max()
        val span = (max - min).takeIf { it > 1e-12 } ?: 1.0
        val stepX = if (win.size > 1) size.width / (win.size - 1) else size.width
        fun yOf(v: Double): Float =
            padY + (1f - ((v - min) / span).toFloat()) * (chartH - 2 * padY)
        val points = win.mapIndexed { i, v -> Offset(i * stepX, yOf(v)) }

        // Price gridlines at the quartiles of the visible range.
        for (frac in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val y = padY + frac * (chartH - 2 * padY)
            drawLine(
                color = AurumColors.hairline.copy(alpha = 0.6f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }

        // Flat translucent fill under the line, then the line itself.
        val linePath = buildSmoothPath(points)
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, chartH)
            lineTo(points.first().x, chartH)
            close()
        }
        drawPath(fillPath, color = c.copy(alpha = 0.10f))
        drawPath(linePath, color = c, style = Stroke(width = 4f, cap = StrokeCap.Round))

        // Dashed baseline (previous close / avg cost) when inside the window.
        if (baseline != null && baseline in min..max) {
            val y = yOf(baseline)
            drawLine(
                color = AurumColors.textDim.copy(alpha = 0.55f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )
        }

        // Last-price dot only when the window reaches the newest bar.
        if (start + count == closes.size) {
            val last = points.last()
            drawCircle(color = c.copy(alpha = 0.25f), radius = 14f, center = last)
            drawCircle(color = c, radius = 6f, center = last)
        }

        // Visible min / max labels, right-aligned.
        val maxText = AnnotatedString(Fmt.money(max))
        val minText = AnnotatedString(Fmt.money(min))
        val maxSize = textMeasurer.measure(maxText, labelStyle).size
        drawText(
            textMeasurer, maxText.text,
            topLeft = Offset(size.width - maxSize.width - 4f, 2f),
            style = labelStyle
        )
        val minSize = textMeasurer.measure(minText, labelStyle).size
        drawText(
            textMeasurer, minText.text,
            topLeft = Offset(size.width - minSize.width - 4f, chartH - minSize.height - 2f),
            style = labelStyle
        )

        // Date axis: first / middle / last visible bar. Intraday windows get
        // times; multi-day windows get dates; windows spanning a year or more
        // (1Y, 5Y, Max) must show the year — otherwise labels like "Jan" /
        // "Jun" / "Jan" repeat with no way to tell which year is which.
        if (ts.isNotEmpty()) {
            val visibleSpanMs = ts.last() - ts.first()
            val intraday = visibleSpanMs <= 48L * 3_600_000L
            val yearsApart = visibleSpanMs >= 300L * 24 * 3_600_000L
            fun axisLabel(t: Long): String = when {
                intraday -> Fmt.timeShort(t)
                yearsApart -> Fmt.dateWithYear(t)
                else -> Fmt.dateShort(t)
            }
            val slots = listOf(0, win.size / 2, win.size - 1).distinct()
            for (i in slots) {
                val text = AnnotatedString(axisLabel(ts[i]))
                val w = textMeasurer.measure(text, labelStyle).size.width
                val x = (i * stepX - w / 2f).coerceIn(0f, size.width - w)
                drawText(
                    textMeasurer, text.text,
                    topLeft = Offset(x, chartH + 6f),
                    style = labelStyle
                )
            }
        }

        // Crosshair: vertical hairline + marker + price/time bubble.
        viewport.scrubFrac?.let { frac ->
            val i = (frac * (win.size - 1)).roundToInt().coerceIn(0, win.size - 1)
            val p = points[i]
            drawLine(
                color = AurumColors.textDim.copy(alpha = 0.7f),
                start = Offset(p.x, 0f),
                end = Offset(p.x, chartH),
                strokeWidth = 1.5f
            )
            drawCircle(color = AurumColors.bg, radius = 8f, center = p)
            drawCircle(color = c, radius = 5f, center = p)

            val whenText =
                if (i < ts.size) {
                    // Sub-daily bars deserve a time, daily bars just a date.
                    val barSpan = (ts.last() - ts.first()) / (ts.size - 1).coerceAtLeast(1)
                    val subDaily = barSpan < 20L * 3_600_000L
                    "  ·  " + if (subDaily) Fmt.dateTime(ts[i]) else Fmt.dateShort(ts[i])
                } else ""
            val bubble = AnnotatedString(Fmt.money(win[i]) + whenText)
            val bSize = textMeasurer.measure(bubble, bubbleStyle).size
            val bx = (p.x - bSize.width / 2f).coerceIn(4f, size.width - bSize.width - 4f)
            drawRoundRect(
                color = AurumColors.surfaceHigh,
                topLeft = Offset(bx - 8f, 0f),
                size = Size(bSize.width + 16f, bSize.height + 10f),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = AurumColors.hairline,
                topLeft = Offset(bx - 8f, 0f),
                size = Size(bSize.width + 16f, bSize.height + 10f),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 1f)
            )
            drawText(
                textMeasurer, bubble.text,
                topLeft = Offset(bx, 5f),
                style = bubbleStyle
            )
        }
    }
}
