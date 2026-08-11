package com.aurum.invest.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aurum.invest.ui.theme.AurumColors

/**
 * Layout primitives for the calmer, decision-first screens.
 *
 * The guiding rule: one answer per block. A block states its conclusion in
 * plain type, and the numbers that back it up live behind a disclosure or in
 * a quiet caption — so a screen reads top-to-bottom instead of demanding to
 * be decoded all at once.
 */

/** Vertical rhythm shared by every screen. */
object Space {
    val screenH = 20.dp      // horizontal screen padding
    val block = 32.dp        // between major blocks
    val item = 12.dp         // between rows inside a block
    val tight = 6.dp
}

/**
 * A screen's opening statement: small overline, then the answer in large type.
 * Nothing else competes with it.
 */
@Composable
fun ScreenTitle(
    overline: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = overline.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = AurumColors.text
            )
        }
        trailing?.invoke()
    }
}

/**
 * A titled block. The label is quiet and small; the content carries the
 * weight. Optional [caption] explains the block in one line.
 */
@Composable
fun Block(
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(Space.item))
        content()
    }
}

/**
 * A tappable row that expands to reveal its detail. This is how dense
 * material stays available without being in the way.
 */
@Composable
fun DisclosureRow(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    header: @Composable (expanded: Boolean) -> Unit,
    detail: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) { header(expanded) }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = AurumColors.textDim,
                modifier = Modifier.size(20.dp).rotate(rotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp), content = detail)
        }
    }
}

/** A hairline between list rows — lighter than boxing every row in a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AurumColors.hairline.copy(alpha = 0.7f))
    )
}

/**
 * The one number that matters on a screen, with its label above and an
 * optional supporting line below.
 */
@Composable
fun HeroFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AurumColors.text,
    support: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.displayLarge,
            color = valueColor
        )
        if (support != null) {
            Spacer(Modifier.height(6.dp))
            support()
        }
    }
}

/**
 * A horizontal split bar — the shared way this app shows composition
 * (allocation by stock, by sector, this week's deployment).
 */
@Composable
fun SplitBar(
    weights: List<Float>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 6.dp,
    colors: List<Color> = AurumColors.allocation
) {
    val total = weights.sum().takeIf { it > 0f } ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(3.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        weights.forEachIndexed { i, w ->
            if (w > 0f) {
                Box(
                    modifier = Modifier
                        .weight(w / total)
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors[i % colors.size])
                )
            }
        }
    }
}

/** A small square swatch used by every legend in the app. */
@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

/** A label/value line — the workhorse of the detail disclosures. */
@Composable
fun DetailLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AurumColors.text
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

/** Quiet footnote type for provenance and caveats. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AurumColors.textDim.copy(alpha = 0.8f),
        modifier = modifier.fillMaxWidth()
    )
}

/** A single bullet observation. */
@Composable
fun Bullet(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp, end = 10.dp)
                .size(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AurumColors.gold)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
}

/** Compact status tag — flat, no fill, for statuses like MISSING / COVERED. */
@Composable
fun StatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}
