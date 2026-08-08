package com.aurum.invest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
