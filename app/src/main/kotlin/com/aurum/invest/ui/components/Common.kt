package com.aurum.invest.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.AdviceAction
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.ui.theme.AurumColors

/** Flat segmented toggle — gold fill marks the selected option. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AurumColors.surface)
            .padding(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val fill by animateColorAsState(
                targetValue = if (sel) AurumColors.gold else Color.Transparent,
                label = "segmentFill"
            )
            val textColor by animateColorAsState(
                targetValue = if (sel) AurumColors.bg else AurumColors.textDim,
                label = "segmentText"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(fill)
                    .clickable { onSelect(i) }
                    .padding(vertical = if (compact) 6.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor
                )
            }
        }
    }
}

/**
 * Extended-hours chips: "Pre-market +1.2%" and "After hours −0.3%".
 * Renders nothing when the session has neither print.
 */
@Composable
fun ExtHoursChips(ext: ExtendedHours?, modifier: Modifier = Modifier) {
    val pre = ext?.preMarketPct
    val post = ext?.postMarketPct
    if (pre == null && post == null) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pre != null) ExtHoursChip(label = "Pre-market", value = pre)
        if (post != null) ExtHoursChip(label = "After hours", value = post)
    }
}

@Composable
private fun ExtHoursChip(label: String, value: Double) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AurumColors.surfaceHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Text(
            text = " ${Fmt.signedPct(value)}",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.deltaColor(value)
        )
    }
}

/** The standard Aurum surface: flat fill, quiet radius, no chrome. */
@Composable
fun AurumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    var m = modifier
        .clip(shape)
        .background(AurumColors.surface)
    if (onClick != null) m = m.clickable { onClick() }
    Column(modifier = m.padding(contentPadding), content = content)
}

/** Quiet section label with optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim
        )
        trailing?.invoke()
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AurumColors.text
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor
        )
    }
}

@Composable
fun DeltaPct(value: Double, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    Text(
        text = Fmt.signedPct(value),
        style = style,
        color = AurumColors.deltaColor(value),
        modifier = modifier
    )
}

@Composable
fun DeltaMoney(value: Double, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    Text(
        text = Fmt.signedMoney(value),
        style = style,
        color = AurumColors.deltaColor(value),
        modifier = modifier
    )
}

private fun adviceColor(action: AdviceAction): Color = when (action) {
    AdviceAction.STRONG_BUY, AdviceAction.BUY -> AurumColors.gain
    AdviceAction.WAIT -> AurumColors.textDim
    AdviceAction.HOLD, AdviceAction.TAKE_PROFIT -> AurumColors.gold
    AdviceAction.CUT_LOSS, AdviceAction.SELL -> AurumColors.loss
}

fun adviceLabel(action: AdviceAction): String = when (action) {
    AdviceAction.STRONG_BUY -> "Strong buy"
    AdviceAction.BUY -> "Buy"
    AdviceAction.WAIT -> "Wait"
    AdviceAction.HOLD -> "Hold"
    AdviceAction.TAKE_PROFIT -> "Take profit"
    AdviceAction.CUT_LOSS -> "Cut loss"
    AdviceAction.SELL -> "Sell"
}

/** Colored pill for an advice action. */
@Composable
fun ActionBadge(action: AdviceAction, modifier: Modifier = Modifier) {
    val color = adviceColor(action)
    PillTag(text = adviceLabel(action), color = color, modifier = modifier)
}

/** Generic colored pill tag. */
@Composable
fun PillTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = color
        )
    }
}

/** 0..100 score as a thin gold bar. */
@Composable
fun ScoreBar(score: Double, modifier: Modifier = Modifier) {
    val fraction = (score / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(AurumColors.hairline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(AurumColors.gold)
        )
    }
}

/** Sentiment indicator dot: green positive, red negative, dim neutral. */
@Composable
fun SentimentDot(sentiment: Int, modifier: Modifier = Modifier) {
    val color = when {
        sentiment > 0 -> AurumColors.gain
        sentiment < 0 -> AurumColors.loss
        else -> AurumColors.textDim
    }
    val alpha = if (kotlin.math.abs(sentiment) >= 2) 1f else 0.7f
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.textDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AurumColors.gold,
                    contentColor = AurumColors.bg
                ),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Brand-accent text. Flat gold — no gradients. */
@Composable
fun GoldGradientText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = style,
        color = AurumColors.gold,
        modifier = modifier
    )
}
