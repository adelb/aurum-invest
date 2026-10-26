package com.aurum.invest.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.min
import kotlinx.coroutines.delay

// ----------------------------------------------------------------- motion

/** The Toss press: the control gives under the finger and springs back. */
fun Modifier.pressScale(
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/** The springy overshoot the Toss reveals settle with. */
private val RevealEasing = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)

/**
 * One screen's entrance. Blocks that compose with the screen rise in one
 * after another; a block first scrolled into view later rises at once; and a
 * block that has made its entrance never replays when it scrolls back. Create
 * it where the content first appears (after a loading state), so the stagger
 * starts when there is something to see.
 */
class Entrance internal constructor() {
    internal val openedAt: Long = SystemClock.uptimeMillis()
    internal val entered = HashSet<String>()
}

@Composable
fun rememberEntrance(vararg keys: Any?): Entrance = remember(*keys) { Entrance() }

/**
 * Rises [distance] and fades in, [order] steps into the screen's [entrance].
 * Read in the draw phase, so the stagger costs no recompositions.
 */
fun Modifier.riseIn(
    entrance: Entrance,
    id: String,
    order: Int = 0,
    distance: Dp = 18.dp
): Modifier = composed {
    val progress = remember(entrance, id) {
        Animatable(if (entrance.entered.add(id)) 0f else 1f)
    }
    LaunchedEffect(entrance, id) {
        if (progress.value < 1f) {
            val since = (SystemClock.uptimeMillis() - entrance.openedAt).toInt()
            val delayMs = (order * ENTRANCE_STAGGER_MS - since).coerceAtLeast(0)
            progress.animateTo(1f, tween(ENTRANCE_MS, delayMillis = delayMs, easing = RevealEasing))
        }
    }
    val rise = with(LocalDensity.current) { distance.toPx() }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * rise
    }
}

/** Pops in from half size with a quarter turn and a bouncy settle — for a logo or a badge. */
fun Modifier.popIn(delayMs: Int = 0): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer {
        val p = progress.value
        scaleX = 0.5f + 0.5f * p
        scaleY = 0.5f + 0.5f * p
        alpha = p.coerceIn(0f, 1f)
        rotationZ = (1f - p) * -24f
    }
}

private const val ENTRANCE_MS = 560
private const val ENTRANCE_STAGGER_MS = 70

/**
 * A screen's headline, said as a sentence, revealed word by word: each word
 * rises 10dp and fades in 50ms after the one before. The progress is one
 * animation read in the draw phase, so a long sentence costs no extra
 * recompositions while it plays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sentence(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 26.sp,
        lineHeight = 36.sp
    ),
    color: Color = AurumColors.text,
    startDelayMs: Int = 0
) {
    val words = remember(text) { text.split(' ').filter { it.isNotEmpty() } }
    val total = words.size * WORD_STAGGER_MS + WORD_MS
    val clock = remember(text) { Animatable(0f) }
    LaunchedEffect(text) {
        clock.animateTo(
            total.toFloat(),
            tween(durationMillis = total, delayMillis = startDelayMs, easing = LinearEasing)
        )
    }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    FlowRow(modifier = modifier.semantics { contentDescription = text }) {
        words.forEachIndexed { i, word ->
            Text(
                text = if (i == words.lastIndex) word else "$word ",
                style = style,
                color = color,
                modifier = Modifier.graphicsLayer {
                    val local = ((clock.value - i * WORD_STAGGER_MS) / WORD_MS).coerceIn(0f, 1f)
                    val eased = RevealEasing.transform(local)
                    alpha = local
                    translationY = (1f - eased) * rise
                }
            )
        }
    }
}

private const val WORD_STAGGER_MS = 50
private const val WORD_MS = 520

// --------------------------------------------------------------- structure

/**
 * The 16dp band that separates one section of a screen from the next. It
 * bleeds past the screen's 20dp gutter to both edges, the way Toss splits a
 * long page into chapters without drawing a single card border.
 */
@Composable
fun SectionBand(modifier: Modifier = Modifier, gutter: Dp = 20.dp) {
    // Drawn past its own bounds rather than measured wider: the band keeps
    // the item's width for layout and only its paint reaches the edges.
    Box(
        modifier = modifier
            .padding(vertical = 26.dp)
            .fillMaxWidth()
            .height(16.dp)
            .drawBehind {
                val bleed = gutter.toPx()
                drawRect(
                    color = AurumColors.separator,
                    topLeft = Offset(-bleed, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width + bleed * 2, size.height)
                )
            }
    )
}

/** A section title (20sp, extra bold) with an optional quiet line under it. */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = AurumColors.text
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing?.invoke()
    }
}

/** "Edit", "See all" — the quiet text action at the end of a section title. */
@Composable
fun MoreLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = AurumColors.textDim,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .pressScale(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    )
}

/** Supporting sentence under a headline. */
@Composable
fun Lead(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        color = AurumColors.textDim,
        modifier = modifier
    )
}

/** Footnote: how a figure was arrived at. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
        color = AurumColors.textFaint,
        modifier = modifier.padding(top = 10.dp)
    )
}

/** A detail screen's top bar: back, a title, and up to three actions. */
@Composable
fun TossTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (onBack != null) 8.dp else 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconTap(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, tint = AurumColors.text)
        }
        Text(
            text = title.orEmpty(),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
            color = AurumColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 4.dp else 0.dp)
        )
        actions()
    }
}

/** A 40dp icon target — never smaller, so it is easy to hit with a thumb. */
@Composable
fun IconTap(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AurumColors.textSub,
    badge: Int = 0
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .pressScale(pressedScale = 0.9f, onClick = onClick)
            .semantics {
                contentDescription =
                    if (badge > 0) "$description, $badge waiting" else description
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AurumColors.loss)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badge > 9) "9+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = AurumColors.text
                )
            }
        }
    }
}

// ----------------------------------------------------------------- buttons

enum class ButtonTone { PRIMARY, SOFT, DANGER }

/** The Toss button: 56dp tall, 16dp radius, one clear verb. */
@Composable
fun TossButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.PRIMARY,
    enabled: Boolean = true,
    small: Boolean = false
) {
    val (bg, fg) = when (tone) {
        ButtonTone.PRIMARY -> AurumColors.accent to AurumColors.text
        ButtonTone.SOFT -> AurumColors.surfaceHigh to AurumColors.text
        ButtonTone.DANGER -> AurumColors.loss to AurumColors.text
    }
    Box(
        modifier = modifier
            .then(if (small) Modifier else Modifier.fillMaxWidth())
            .height(if (small) 44.dp else 56.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(RoundedCornerShape(if (small) 14.dp else 16.dp))
            .pressScale(enabled = enabled, onClick = onClick)
            .background(bg)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = if (small) 15.sp else 17.sp
            ),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A chip. Filled chips are shortcuts ("Add trade", "Reports"); a selected
 * filled chip turns blue. Unfilled chips are a choice in a row (1M · 6M · 1Y)
 * and gain a layer-2 ground when chosen.
 */
@Composable
fun TossChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fill: Boolean = true,
    icon: ImageVector? = null
) {
    val bg by animateColorAsState(
        targetValue = when {
            fill && selected -> AurumColors.accent
            fill -> AurumColors.surfaceHigh
            selected -> AurumColors.surfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(220),
        label = "chipBg"
    )
    val fg by animateColorAsState(
        targetValue = when {
            selected -> AurumColors.text
            fill -> AurumColors.textSub
            else -> AurumColors.textDim
        },
        animationSpec = tween(220),
        label = "chipFg"
    )
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .pressScale(pressedScale = 0.93f, onClick = onClick)
            .background(bg)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = fg,
            maxLines = 1
        )
    }
}

/** A row of choice chips that scrolls sideways when it outgrows the screen. */
@Composable
fun ScrollTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp)
) {
    val scroll = rememberScrollState()
    // Bring the chosen tab into view, so a choice at the far end never sits clipped.
    LaunchedEffect(selected, scroll.maxValue) {
        if (options.size > 1) {
            scroll.animateScrollTo(scroll.maxValue * selected / (options.size - 1))
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            TossChip(text = label, onClick = { onSelect(i) }, selected = i == selected, fill = false)
        }
    }
}

// ------------------------------------------------------------------ figures

/** One cell of a [KvGrid]. A [live] value rolls per digit as it re-prices. */
data class Kv(
    val label: String,
    val value: String = "",
    val note: String? = null,
    val valueColor: Color = AurumColors.text,
    val live: Double? = null,
    val liveSigned: Boolean = false,
    val liveFormat: ((Double) -> String)? = null
)

/** Label-over-figure pairs in an even grid — the Toss way to show a ledger. */
@Composable
fun KvGrid(items: List<Kv>, modifier: Modifier = Modifier, columns: Int = 2) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { kv -> KvCell(kv, Modifier.weight(1f)) }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KvCell(kv: Kv, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            text = kv.label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = AurumColors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val valueStyle = MaterialTheme.typography.titleMedium.copy(
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
        )
        if (kv.live != null) {
            AnimatedMoney(
                value = kv.live,
                style = valueStyle,
                baseColor = kv.valueColor,
                signed = kv.liveSigned,
                format = kv.liveFormat,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            Text(
                text = kv.value,
                style = valueStyle,
                color = kv.valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (kv.note != null) {
            Text(
                text = kv.note,
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textFaint,
                maxLines = 2
            )
        }
    }
}

enum class Tone { NEUTRAL, BLUE, GOLD, GAIN, LOSS }

private fun toneColors(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.NEUTRAL -> AurumColors.surfaceHigh to AurumColors.textSub
    Tone.BLUE -> AurumColors.accentSoft to AurumColors.info
    Tone.GOLD -> AurumColors.goldSoft to AurumColors.gold
    Tone.GAIN -> AurumColors.gainSoft to AurumColors.gain
    Tone.LOSS -> AurumColors.lossSoft to AurumColors.loss
}

/** The 44dp rounded square that leads a row: "21K", "SPY", "4.7%". */
@Composable
fun IconSquare(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    size: Dp = 44.dp,
    icon: ImageVector? = null
) {
    val (bg, fg) = toneColors(tone)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = if (text.length > 3) 12.sp else 13.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = fg,
                maxLines = 1
            )
        }
    }
}

/** A tag in one of the five tones. */
@Composable
fun ToneTag(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A progress meter that grows into place the first time it is shown. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = AurumColors.accent) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        grow.animateTo(
            fraction.coerceIn(0f, 1f),
            tween(900, delayMillis = 200, easing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f))
        )
    }
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AurumColors.surfaceHigh)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(grow.value.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}

// -------------------------------------------------------------------- logos

/** Where the company logos come from — one PNG per ticker, no key needed. */
fun logoUrl(symbol: String): String =
    "https://financialmodelingprep.com/image-stock/${symbol.trim().uppercase()}.png"

/**
 * The Apple Stocks hand-off: when a row opens a stock, its logo flies from
 * the row into the detail screen's header. Every on-screen [CompanyLogo]
 * leaves its position here; opening a stock [arm]s the one for its symbol,
 * and the detail screen [take]s it before its own header has laid out, so
 * the flight starts exactly where the user tapped.
 */
object LogoFlight {
    internal class Pad(val symbol: String) {
        var bounds: Rect? = null
    }

    private val placed = HashMap<String, Pad>()
    private var armed: Pair<String, Rect>? = null

    internal fun place(pad: Pad, bounds: Rect) {
        pad.bounds = bounds
        placed[pad.symbol] = pad
    }

    /** A logo leaving the screen stops being a launch point. */
    internal fun release(pad: Pad) {
        if (placed[pad.symbol] === pad) placed.remove(pad.symbol)
    }

    /** Called as a stock is opened: remembers where its logo sits right now. */
    fun arm(symbol: String) {
        val key = symbol.trim().uppercase()
        armed = placed[key]?.bounds?.let { key to it }
    }

    /** The launch point for [symbol], handed out once; null when it was opened from elsewhere. */
    fun take(symbol: String): Rect? {
        val launch = armed ?: return null
        armed = null
        return if (launch.first == symbol.trim().uppercase()) launch.second else null
    }
}

/**
 * Flies this element in from [from] (root coordinates) to wherever it lays
 * out, as [progress] runs 0 → 1, scaling from the launch size to its own.
 * Running [progress] back to 0 flies it home again.
 */
fun Modifier.flyFrom(from: Rect, progress: Animatable<Float, AnimationVector1D>): Modifier = composed {
    val landing = remember { arrayOfNulls<Rect>(1) }
    this
        .onGloballyPositioned { landing[0] = it.boundsInRoot() }
        .graphicsLayer {
            val end = landing[0]
            if (end == null || end.width <= 0f) {
                // Not laid out yet: one invisible frame beats a jump.
                alpha = 0f
                return@graphicsLayer
            }
            val away = 1f - progress.value
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = (from.left - end.left) * away
            translationY = (from.top - end.top) * away
            val scale = 1f + (from.width / end.width - 1f) * away
            scaleX = scale
            scaleY = scale
        }
}

/**
 * The company's logo on a round badge. While it loads — or when a ticker has
 * no logo — the badge shows the first letter, so a row never jumps. As a
 * [launchPad] (the default) it is where [LogoFlight] takes off from when its
 * row opens the stock.
 */
@Composable
fun CompanyLogo(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    launchPad: Boolean = true
) {
    val pad = remember(symbol) { LogoFlight.Pad(symbol.trim().uppercase()) }
    if (launchPad) {
        DisposableEffect(pad) { onDispose { LogoFlight.release(pad) } }
    }
    val context = LocalContext.current
    val request = remember(symbol) {
        ImageRequest.Builder(context)
            .data(logoUrl(symbol))
            // The badge transformation reads pixels; hardware bitmaps can't be read.
            .allowHardware(false)
            .transformations(LogoBadge)
            .crossfade(220)
            .build()
    }
    Box(
        modifier = modifier
            .then(
                if (launchPad) Modifier.onGloballyPositioned { LogoFlight.place(pad, it.boundsInRoot()) }
                else Modifier
            )
            .size(size)
            .clip(CircleShape)
            .background(AurumColors.surfaceHigh),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = AurumColors.textSub
        )
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size)
        )
    }
}

/** A ranked pick: the company's logo with its place in the list on a small tab. */
@Composable
fun RankedLogo(symbol: String, rank: Int, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(modifier = modifier.size(width = size + 6.dp, height = size + 4.dp)) {
        CompanyLogo(symbol = symbol, size = size)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(7.dp))
                .background(AurumColors.bg)
                .padding(1.5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (rank <= 3) AurumColors.accent else AurumColors.surfaceTop)
                .padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = AurumColors.text
            )
        }
    }
}

/**
 * Bakes a logo onto a round badge that it can be seen on.
 *
 * Logos arrive as transparent PNGs in every colour, and no single badge suits
 * them all: a white wordmark (Amazon, Arista) disappears on white, a black one
 * (Palantir, Rocket Lab) disappears on the dark ground. So the badge is chosen
 * by the logo's own ink: when its visible pixels are nearly all light, it
 * goes on a dark badge, otherwise on white. A logo with no transparency is
 * already its own badge and simply fills the circle.
 */
private object LogoBadge : Transformation {
    override val cacheKey: String = "aurum.logoBadge.v1"

    private const val DARK_BADGE = 0xFF232F3E.toInt()
    private const val WHITE_BADGE = 0xFFFFFFFF.toInt()

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        var ink = 0
        var light = 0.0
        var clear = 0
        for (p in pixels) {
            val a = p ushr 24
            if (a < 40) {
                clear++
                continue
            }
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            light += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            ink++
        }
        val transparent = clear > pixels.size * 0.05
        val side = min(256, maxOf(w, h)).coerceAtLeast(64)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (!transparent) {
            // Already a full-bleed tile: clip it to the circle.
            canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(input, null, RectF(0f, 0f, side.toFloat(), side.toFloat()), paint)
            return out
        }
        val mostlyLight = ink > 0 && light / ink > 0.82
        paint.color = if (mostlyLight) DARK_BADGE else WHITE_BADGE
        canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint)
        // Inset so a wide wordmark clears the circle's edge.
        val inset = side * 0.18f
        val box = side - inset * 2
        val scale = box / maxOf(w, h)
        val dw = w * scale
        val dh = h * scale
        val left = (side - dw) / 2f
        val top = (side - dh) / 2f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawBitmap(input, null, RectF(left, top, left + dw, top + dh), paint)
        return out
    }
}

/**
 * "NVIDIA Corporation" → "NVIDIA". The legal suffix says nothing in a list
 * and pushes the figures off the row.
 */
fun companyName(shortName: String?, symbol: String): String {
    val raw = shortName?.trim().orEmpty()
    if (raw.isEmpty()) return symbol
    var name = raw
    for (suffix in NAME_SUFFIXES) {
        if (name.endsWith(suffix, ignoreCase = true)) {
            name = name.dropLast(suffix.length).trimEnd(' ', ',', '.')
        }
    }
    return name.ifBlank { symbol }
}

private val NAME_SUFFIXES = listOf(
    " Common Stock", " Class A", " Class B", " Corporation", " Incorporated",
    " Holdings", " Holding", " Corp.", " Corp", ", Inc.", " Inc.", " Inc",
    " Co.", " plc", " PLC", " Ltd.", " Ltd", " N.V.", " S.A.", " Limited",
    " Platforms", " Technologies"
)

// ------------------------------------------------------------------- keypad

/**
 * The Toss number pad: big keys, no system keyboard covering the figures the
 * user is typing against. [onKey] receives "0".."9", "." or "del".
 */
@Composable
fun NumberKeypad(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    Column(modifier = modifier.fillMaxWidth()) {
        keys.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .pressScale(pressedScale = 0.9f) { onKey(key) }
                            .semantics { contentDescription = if (key == "del") "Delete" else key },
                        contentAlignment = Alignment.Center
                    ) {
                        if (key == "del") {
                            Icon(
                                Icons.AutoMirrored.Rounded.Backspace,
                                contentDescription = null,
                                tint = AurumColors.textSub,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = AurumColors.text
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Applies one keypad press to a typed number: at most [maxDecimals] places
 * and [maxLength] characters, one decimal point, no leading zeros.
 */
fun applyKey(current: String, key: String, maxDecimals: Int = 3, maxLength: Int = 9): String =
    when (key) {
        "del" -> current.dropLast(1)
        "." -> if (current.contains('.')) current else (current.ifEmpty { "0" }) + "."
        else -> {
            val decimals = current.substringAfter('.', "").length
            when {
                current.length >= maxLength -> current
                current.contains('.') && decimals >= maxDecimals -> current
                current == "0" -> key
                else -> current + key
            }
        }
    }

/** A small spacer helper for rows of figures. */
@Composable
fun Gap(width: Dp) = Spacer(Modifier.width(width))
