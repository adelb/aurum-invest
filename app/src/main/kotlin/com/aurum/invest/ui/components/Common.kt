package com.aurum.invest.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.AdviceAction
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlin.math.floor

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
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Extended-hours chips: "Pre-market +1.2%" and "After hours −0.3%".
 * Renders nothing when the session has neither print.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtHoursChips(ext: ExtendedHours?, modifier: Modifier = Modifier) {
    val pre = ext?.preMarketPct
    val post = ext?.postMarketPct
    if (pre == null && post == null) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * Label over value. [maxLines] caps both lines so a row of tiles keeps its
 * baselines aligned when one label is longer than its neighbours.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AurumColors.text,
    maxLines: Int = Int.MAX_VALUE
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A signed percentage. Always one line — a number reads as broken when wrapped. */
@Composable
fun DeltaPct(value: Double, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    Text(
        text = Fmt.signedPct(value),
        style = style,
        color = AurumColors.deltaColor(value),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** A signed money amount. Always one line, for the same reason as [DeltaPct]. */
@Composable
fun DeltaMoney(value: Double, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    Text(
        text = Fmt.signedMoney(value),
        style = style,
        color = AurumColors.deltaColor(value),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * A money figure that shows its own movement.
 *
 * When [value] changes, only the places that actually changed move: each one
 * turns vertically onto its new digit the way an odometer wheel rolls, while
 * every digit that stayed the same holds still. The figure also flashes green
 * when it rose and red when it fell before settling back to [baseColor]. This
 * is for the figures the live ticker re-prices (holdings value, net worth,
 * total P/L, liquidity …): without it a digit simply differs from the one
 * that was there a moment ago, and the user cannot tell which number moved
 * or which way.
 *
 * The whole figure used to flip on its X axis instead. That said "this number
 * changed" but never WHICH part of it — one cent moving turned the entire
 * balance edge-on, and the reader had to hunt for the digit that differed.
 * Turning per place points straight at the movement.
 *
 * Always exact cents — these are balances, and a flashing rounded number
 * would appear to sit still while the cents underneath it moved.
 */
@Composable
fun AnimatedMoney(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    baseColor: Color = AurumColors.text,
    signed: Boolean = false,
    textAlign: TextAlign? = null,
    /** Movements below this are rounding noise and must not fire the flash. */
    epsilon: Double = 0.005
) {
    val text = if (signed) Fmt.signedMoneyExact(value) else Fmt.moneyExact(value)
    val move = remember { MoveTracker(value) }
    // Read while composing, deliberately: the wheels below need the direction
    // in the SAME frame they are handed their new digit. Direction published
    // through snapshot state from a LaunchedEffect would reach them a frame
    // late, so the first wheel of every move would turn the wrong way.
    val direction = move.observe(value, epsilon)

    // 1f = fully tinted with the move colour, 0f = back to baseColor.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(move.moves) {
        if (move.moves == 0) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(FLASH_MS, easing = LinearEasing))
    }
    val color = lerp(
        baseColor,
        if (direction >= 0) AurumColors.gain else AurumColors.loss,
        flash.value
    )

    // The ten glyphs are measured once and shared by every wheel in the figure.
    val measurer = rememberTextMeasurer()
    val reel = remember(style, measurer) { DigitReel.of(measurer, style) }

    Row(
        // A row of single characters reads as ten separate labels to a screen
        // reader; the figure is one number and must be announced as one.
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
        horizontalArrangement = when (textAlign) {
            TextAlign.End, TextAlign.Right -> Arrangement.End
            TextAlign.Center -> Arrangement.Center
            else -> Arrangement.Start
        },
        verticalAlignment = Alignment.Bottom
    ) {
        val lastIndex = text.lastIndex
        text.forEachIndexed { i, ch ->
            // Keyed from the RIGHT so a place keeps its wheel when the figure
            // gains or loses a digit: "$999.99" -> "$1,000.00" turns the places
            // that moved instead of restarting every wheel one column across.
            val fromRight = lastIndex - i
            key(fromRight) {
                if (ch.isDigit()) {
                    DigitWheel(
                        digit = ch - '0',
                        up = direction >= 0,
                        delayMs = (fromRight * ROLL_STAGGER_MS)
                            .coerceAtMost(ROLL_STAGGER_CAP_MS),
                        reel = reel,
                        color = color
                    )
                } else {
                    // Currency symbol, sign, separators — nothing to turn.
                    Text(
                        text = ch.toString(),
                        style = style,
                        color = color,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * One place of a live figure, drawn as a wheel.
 *
 * [digit] is the only thing that starts a turn, so a wheel whose digit did not
 * change never moves — that is precisely what makes the figure point at what
 * changed. The turn itself runs in the draw phase: the position is read while
 * drawing, so each tick costs two glyph draws and neither a recomposition nor
 * a re-layout of the screen around it.
 */
@Composable
private fun DigitWheel(
    digit: Int,
    up: Boolean,
    delayMs: Int,
    reel: DigitReel,
    color: Color
) {
    val position = remember { Animatable(digit.toFloat()) }
    // The turn direction that applies is the one at the moment the wheel is
    // handed a new digit, not whatever it becomes while the wheel is turning.
    val turnUp = rememberUpdatedState(up)

    LaunchedEffect(digit) {
        val here = position.value.mod(10f)
        // Travel the way the whole figure is moving, so a rising balance turns
        // every wheel up and a falling one turns them all down — mixed
        // directions inside one number look like a glitch, not a movement.
        val distance =
            if (turnUp.value) (digit - here).mod(10f) else -((here - digit).mod(10f))
        if (abs(distance) < 1e-4f) return@LaunchedEffect
        position.snapTo(here)
        position.animateTo(
            targetValue = here + distance,
            animationSpec = tween(ROLL_MS, delayMillis = delayMs, easing = FastOutSlowInEasing)
        )
        // Back into 0..9 once at rest. Invisible — the wheel shows this digit
        // either way — and it keeps the next turn's arithmetic small.
        position.snapTo(digit.toFloat())
    }

    val density = LocalDensity.current
    Spacer(
        Modifier
            .size(
                width = with(density) { reel.width.toDp() },
                height = with(density) { reel.height.toDp() }
            )
            .clipToBounds()
            .drawBehind {
                // Measured against the window the wheel actually got, not the
                // px it was measured at: the dp round-trip above can land a
                // fraction off, and half a pixel of drift would leave a resting
                // digit sitting slightly high.
                val slotHeight = size.height
                // Only the two glyphs straddling the window are ever drawn,
                // however far the wheel has to travel.
                val at = position.value
                val below = floor(at)
                val slide = (at - below) * slotHeight
                val leaving = reel.glyphAt(below.toInt())
                val arriving = reel.glyphAt(below.toInt() + 1)
                drawText(
                    textLayoutResult = leaving,
                    color = color,
                    topLeft = Offset((size.width - leaving.size.width) / 2f, -slide)
                )
                drawText(
                    textLayoutResult = arriving,
                    color = color,
                    topLeft = Offset(
                        (size.width - arriving.size.width) / 2f,
                        slotHeight - slide
                    )
                )
            }
    )
}

/**
 * The ten digit glyphs of one text style, pre-measured.
 *
 * [width] is the widest of them and every wheel takes it, so a 1 turning into
 * a 4 cannot shove the rest of the figure sideways mid-turn.
 */
private class DigitReel(
    private val glyphs: List<TextLayoutResult>,
    val width: Int,
    val height: Int
) {
    /** The glyph for any wheel position, wrapping past either end of 0-9. */
    fun glyphAt(index: Int): TextLayoutResult = glyphs[((index % 10) + 10) % 10]

    companion object {
        fun of(measurer: TextMeasurer, style: TextStyle): DigitReel {
            val glyphs = List(10) {
                measurer.measure(it.toString(), style = style, maxLines = 1, softWrap = false)
            }
            return DigitReel(
                glyphs = glyphs,
                width = glyphs.maxOf { it.size.width },
                height = glyphs.maxOf { it.size.height }
            )
        }
    }
}

/**
 * Remembers which way a live figure last moved, and counts the moves that
 * cleared the noise floor.
 *
 * Deliberately NOT snapshot state: the direction has to be readable while the
 * wheels compose, and writing snapshot state during composition invalidates
 * the very composition reading it. [moves] changes only on a real move, which
 * is what makes it a stable key for restarting the flash.
 */
private class MoveTracker(private var last: Double) {

    /** 1 after a rise, -1 after a fall, 0 before the first real move. */
    var direction: Int = 0
        private set

    /** How many real moves have happened — the flash's restart key. */
    var moves: Int = 0
        private set

    fun observe(current: Double, epsilon: Double): Int {
        val delta = current - last
        last = current
        if (abs(delta) > epsilon) {
            direction = if (delta > 0) 1 else -1
            moves++
        }
        return direction
    }
}

/** Label over an [AnimatedMoney] value — the live counterpart of [StatTile]. */
@Composable
fun LiveStatTile(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    baseColor: Color = AurumColors.text,
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = labelStyle,
            color = AurumColors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        AnimatedMoney(
            value = value,
            style = valueStyle,
            baseColor = baseColor,
            signed = signed
        )
    }
}

/** How long the green/red tint takes to fade back to the resting colour. */
private const val FLASH_MS = 850

/** How long one wheel takes to turn onto its new digit. */
private const val ROLL_MS = 420

/**
 * How much later each more-significant place starts turning. On a real
 * odometer a wheel is dragged by the one to its right rather than moving with
 * it, and that lag is most of what reads as "rolling" instead of "flickering".
 */
private const val ROLL_STAGGER_MS = 22

/**
 * Ceiling on that lag. A long figure must still come to rest well before the
 * tick that hands it the next number, or the wheels never settle.
 */
private const val ROLL_STAGGER_CAP_MS = 130

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

/**
 * Generic colored pill tag. A pill is a chip, so its label stays on one line
 * with an ellipsis by default: without that, a tight row squeezes the box to
 * near-zero width and the text stacks into a crushed, unreadable blob.
 */
@Composable
fun PillTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
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
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
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
