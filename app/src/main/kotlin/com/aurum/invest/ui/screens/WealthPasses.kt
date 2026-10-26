package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aurum.invest.analytics.ActionKind
import com.aurum.invest.analytics.CheckState
import com.aurum.invest.analytics.CurvePoint
import com.aurum.invest.analytics.GapStatus
import com.aurum.invest.analytics.HoldingEvaluation
import com.aurum.invest.analytics.HoldingMove
import com.aurum.invest.analytics.ManagementAction
import com.aurum.invest.analytics.MarketCall
import com.aurum.invest.analytics.MustBuyEngine
import com.aurum.invest.analytics.MustBuyRow
import com.aurum.invest.analytics.NextSessionPick
import com.aurum.invest.analytics.PerformanceReport
import com.aurum.invest.analytics.SectorGap
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.CompanyLogo
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The seven passes, back to front as the wallet first deals them. */
private enum class WealthPass { RACE, MARKET, MUST_BUY, NEXT_SESSION, GAPS, MOVES, SCORE }

/** One pass's paint: its ground, its ink, and the gain/loss it can carry. */
private class PassTone(
    val ground: Color,
    val ink: Color,
    val dim: Color,
    val track: Color,
    val rule: Color,
    val gain: Color,
    val loss: Color,
    /** The dot after the "Aurum" wordmark. */
    val dot: Color
)

private val WhitePass = PassTone(
    ground = AurumColors.cardWhite, ink = AurumColors.inkDark, dim = AurumColors.inkDarkDim,
    track = Color(0x1F17171C), rule = Color(0xFFD9DEE3),
    gain = AurumColors.gainOnLight, loss = AurumColors.lossOnLight, dot = AurumColors.accent
)

private fun darkPass(ground: Color) = PassTone(
    ground = ground, ink = AurumColors.text, dim = AurumColors.textDim,
    track = AurumColors.inkDarkDim, rule = AurumColors.surfaceTop,
    gain = AurumColors.gain, loss = AurumColors.loss, dot = AurumColors.gold
)

/** A bright pass carries its message in its colour, so gain and loss read in its own ink. */
private fun brightPass(ground: Color, darkInk: Boolean): PassTone {
    val ink = if (darkInk) AurumColors.inkDark else AurumColors.text
    return PassTone(
        ground = ground, ink = ink, dim = ink.copy(alpha = if (darkInk) 0.68f else 0.8f),
        track = Color(0x3817171C), rule = ink.copy(alpha = if (darkInk) 0.18f else 0.25f),
        gain = ink, loss = ink, dot = if (darkInk) AurumColors.text else AurumColors.inkDark
    )
}

/** What a pass shows: its header, its face, and — when there is one — its back. */
private class PassContent(
    val tone: PassTone,
    val title: String,
    val headLabel: String,
    val headValue: String,
    val headColor: Color? = null,
    /** An event ticket, with the notch at the top. */
    val ticket: Boolean = false,
    val face: @Composable () -> Unit,
    val back: (@Composable () -> Unit)? = null
)

private data class PassField(val label: String, val value: String, val color: Color? = null)

private data class BackRow(val label: String, val value: String? = null, val line: String? = null, val color: Color? = null)

/** One slice of a split bar: its label, what it reads on the right, its share. */
private data class SplitSlice(val label: String, val right: String, val sharePct: Double, val color: Color)

private val PassShape = RoundedCornerShape(22.dp)
private val HEADER_H = 54.dp
/** How much of each pass shows above the one in front of it. */
private val PEEK = 54.dp
private val PASS_MIN_H = 392.dp
private val DropEasing = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)

/**
 * The Wealth tab as the user picked it on 26 Oct: one Apple Wallet holding the
 * seven passes that manage the portfolio — the score, what to do next, the
 * sector gaps, the next session, the must-buys, the market and the race
 * against SPY. Each pass peeks out from under the next; tapping one brings it
 * to the front, where every row's ⓘ opens its full detail and the pass's own
 * ⓘ turns it over for the reasons behind it.
 *
 * Only the front pass composes its face; the passes behind are just their
 * label strips, so the wallet opens fast. On the first visit ([intro]) the
 * passes drop in one after another and the figures count in.
 */
@Composable
fun WealthWallet(
    state: WealthState,
    intro: Boolean,
    onIntroDone: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    var orderNames by rememberSaveable { mutableStateOf(WealthPass.entries.joinToString(",") { it.name }) }
    val order = remember(orderNames) { orderNames.split(",").map { WealthPass.valueOf(it) } }
    var flippedName by rememberSaveable { mutableStateOf<String?>(null) }
    val front = order.last()
    LaunchedEffect(Unit) {
        if (intro) {
            delay(1800)
            onIntroDone()
        }
    }
    val heights = remember { mutableStateMapOf<WealthPass, Dp>() }
    val stackHeight by animateDpAsState(
        targetValue = PEEK * (order.size - 1) + maxOf(heights[front] ?: PASS_MIN_H, PASS_MIN_H),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "wealthStackHeight"
    )

    Box(modifier = Modifier.fillMaxWidth().height(stackHeight)) {
        WealthPass.entries.forEachIndexed { index, pass ->
            val place = order.indexOf(pass)
            val live = pass == front
            val content = when (pass) {
                WealthPass.SCORE -> scorePass(state, intro)
                WealthPass.MOVES -> movesPass(state, live, onOpenDetail)
                WealthPass.GAPS -> gapsPass(state, live, onOpenDetail)
                WealthPass.NEXT_SESSION -> nextSessionPass(state, live, onOpenDetail)
                WealthPass.MUST_BUY -> mustBuyPass(state, live, onOpenAnalysis)
                WealthPass.MARKET -> marketPass(state, intro)
                WealthPass.RACE -> racePass(state, live)
            }
            PassFrame(
                content = content,
                y = PEEK * place,
                z = place.toFloat(),
                index = index,
                intro = intro,
                front = live,
                flipped = live && flippedName == pass.name,
                onBring = {
                    if (!live) {
                        orderNames = (order.filter { it != pass } + pass).joinToString(",") { it.name }
                        flippedName = null
                    }
                },
                onFlip = { flippedName = if (flippedName == pass.name) null else pass.name },
                onMeasured = { heights[pass] = it }
            )
        }
    }
}

@Composable
private fun PassFrame(
    content: PassContent,
    y: Dp,
    z: Float,
    index: Int,
    intro: Boolean,
    front: Boolean,
    flipped: Boolean,
    onBring: () -> Unit,
    onFlip: () -> Unit,
    onMeasured: (Dp) -> Unit
) {
    val density = LocalDensity.current
    val tone = content.tone
    val shift by animateDpAsState(
        targetValue = y,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "passY"
    )
    // The face's own height; the back is never shorter, so turning over doesn't jump.
    var faceHeight by remember { mutableStateOf(PASS_MIN_H) }
    var shownHeight by remember { mutableStateOf(PASS_MIN_H) }
    // A pass behind only needs its label strip; the one in front shows all of itself.
    val tall by animateDpAsState(
        targetValue = if (front) maxOf(shownHeight, PASS_MIN_H) else PEEK + 30.dp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "passHeight"
    )
    // The first-visit drop: from well above, into place, one after another.
    val drop = remember { Animatable(if (intro) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (drop.value < 1f) {
            drop.animateTo(1f, tween(800, delayMillis = 80 + index * 110, easing = DropEasing))
        }
    }
    val turn by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "passTurn"
    )
    val lift = with(density) { 160.dp.toPx() }

    Box(
        modifier = Modifier
            .zIndex(z)
            .offset { IntOffset(0, shift.roundToPx()) }
            .fillMaxWidth()
            .height(tall)
            .graphicsLayer {
                val p = drop.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * -lift
                rotationY = turn
                cameraDistance = 18f * this.density
            }
            .shadow(elevation = 14.dp, shape = PassShape, clip = false)
            .clip(PassShape)
            .background(tone.ground)
    ) {
        if (turn <= 90f) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .onSizeChanged {
                        val h = with(density) { it.height.toDp() }
                        faceHeight = h
                        shownHeight = h
                        onMeasured(h)
                    }
            ) {
                PassHeader(content)
                // Behind, a pass is only its label: the face composes once it comes forward.
                if (front) {
                    content.face()
                    // Room for ⓘ in the corner.
                    Spacer(Modifier.height(64.dp))
                }
            }
            if (content.ticket) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(width = 28.dp, height = 14.dp)
                        .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                        .background(AurumColors.bg.copy(alpha = 0.6f))
                )
            }
            if (front && content.back != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, tone.dim, CircleShape)
                        .clickable(onClick = onFlip)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Turn ${content.title} over"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "i",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                        color = tone.ink
                    )
                }
            }
        } else {
            // The back, turned the right way round again.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .heightIn(min = faceHeight)
                    .graphicsLayer { rotationY = 180f }
                    .onSizeChanged {
                        val h = with(density) { it.height.toDp() }
                        shownHeight = h
                        onMeasured(h)
                    }
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 84.dp)
            ) {
                content.back?.invoke()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { rotationY = 180f }
                    .padding(20.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tone.ink)
                    .clickable(onClick = onFlip),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Done",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                    color = tone.ground
                )
            }
        }
        if (!front) {
            // A pass behind is all label: a tap anywhere on it brings it forward.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tall)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBring
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "Bring ${content.title} forward"
                    }
            )
        }
    }
}

@Composable
private fun PassHeader(content: PassContent) {
    val tone = content.tone
    Row(
        modifier = Modifier.fillMaxWidth().height(HEADER_H).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                append("Aurum")
                withStyle(SpanStyle(color = tone.dot)) { append(".") }
            },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
            color = tone.ink
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = content.title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = tone.ink.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = content.headLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                color = tone.dim,
                maxLines = 1
            )
            Text(
                text = content.headValue,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
                color = content.headColor ?: tone.ink,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------- the passes

private fun scorePass(state: WealthState, intro: Boolean): PassContent {
    val report = state.report
    val score = report?.healthScore
    val tone = when {
        score == null -> darkPass(AurumColors.surfaceHigh)
        score >= 60 -> brightPass(AurumColors.gain, darkInk = true)
        else -> brightPass(AurumColors.loss, darkInk = false)
    }
    val perf = state.performance
    val parts = report?.disciplines.orEmpty()
    val graded = parts.filter { it.score != null }
    return PassContent(
        tone = tone,
        title = "Wealth score",
        headLabel = if (perf != null) "vs SPY" else "Grade",
        headValue = if (perf != null) signedPts(perf.edgePct) else report?.healthBand ?: "—",
        face = {
            when {
                report == null -> PassMessage(
                    if (state.reportLoading) "Reading every holding through the 35-technique board…"
                    else "Record a trade and Aurum grades the whole book.",
                    tone
                )
                score == null -> {
                    PassMessage(report.healthBand, tone)
                    PassNote("Only ${pct1(report.coveragePct)} of the book could be measured this run.", tone)
                }
                else -> {
                    var open by remember { mutableStateOf<String?>(null) }
                    PassFigure(score.toDouble(), { it.roundToInt().toString() }, "/ 100", 96.sp, tone, intro)
                    Text(
                        text = report.healthBand,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                        color = tone.ink,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    PassBars(graded.map { it.score!!.toFloat() / it.maxScore }, tone)
                    Spacer(Modifier.height(16.dp))
                    parts.forEach { d ->
                        DetailRow(
                            title = d.label,
                            tone = tone,
                            live = false,
                            open = open == d.key,
                            onToggle = { open = if (open == d.key) null else d.key },
                            right = d.score?.let { "$it / ${d.maxScore}" } ?: "Not graded",
                            rightSub = d.score?.let { if (it.toFloat() / d.maxScore >= 0.6f) "holds it up" else "pulls it down" }
                        ) {
                            DetailText(d.detail, tone)
                            if (d.fix.isNotBlank()) DetailText(d.fix, tone, strong = true)
                        }
                    }
                }
            }
        },
        back = if (score == null) null else (@Composable {
            val got = graded.sumOf { it.score!! }
            val max = graded.sumOf { it.maxScore }
            BackSheet(
                title = "How the score is made",
                sub = "$score is $got of the $max points that could be measured, scaled to 100. " +
                    "Parts without enough data are left out, never guessed.",
                rows = report?.notes.orEmpty().map { BackRow(label = "", line = it) },
                tone = tone
            )
        })
    )
}

private fun movesPass(state: WealthState, live: Boolean, onOpenDetail: (String) -> Unit): PassContent {
    val report = state.report
    val actions = report?.actions.orEmpty().sortedBy { it.priority }
    val evals = report?.holdings.orEmpty().associateBy { it.symbol }
    val steady = report?.holdings.orEmpty().filter { e -> e.move == HoldingMove.HOLD && actions.none { it.symbol == e.symbol } }
    val tone = brightPass(AurumColors.accent, darkInk = false)
    return PassContent(
        tone = tone,
        title = "Do next",
        headLabel = "Moves",
        headValue = if (report == null) "—" else actions.size.toString(),
        ticket = true,
        face = {
            when {
                report == null -> PassMessage(
                    if (state.reportLoading) "Working out what to do with each holding…"
                    else "Record a trade and the moves to make appear here.",
                    tone
                )
                actions.isEmpty() && steady.isEmpty() -> PassMessage("Nothing to do. Every holding is on its plan.", tone)
                else -> {
                    var open by remember { mutableStateOf<String?>(null) }
                    actions.firstOrNull()?.let { first ->
                        LeadRow(
                            symbol = first.symbol.ifEmpty { null },
                            icon = moveIcon(first.kind),
                            label = "First",
                            title = moveTitle(first),
                            amount = first.amount?.let { Fmt.moneyExact(it) }.orEmpty(),
                            tone = tone,
                            live = live,
                            open = open == "move0",
                            onToggle = { open = if (open == "move0") null else "move0" }
                        ) { MoveDetail(first, evals, tone, onOpenDetail) }
                        Spacer(Modifier.height(6.dp))
                    }
                    actions.drop(1).forEachIndexed { i, a ->
                        val key = "move${i + 1}"
                        DetailRow(
                            title = moveTitle(a),
                            tone = tone,
                            live = live,
                            open = open == key,
                            onToggle = { open = if (open == key) null else key },
                            symbol = a.symbol.ifEmpty { null },
                            icon = moveIcon(a.kind),
                            sub = a.symbol.takeIf { it.isNotEmpty() }?.let { s -> evals[s]?.let { holdingLine(it) } },
                            right = a.amount?.let { Fmt.moneyExact(it) }
                        ) { MoveDetail(a, evals, tone, onOpenDetail) }
                    }
                    if (steady.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        PassCaption("Holding steady", tone)
                        steady.forEach { e ->
                            val key = "hold${e.symbol}"
                            DetailRow(
                                title = "Hold ${e.symbol}",
                                tone = tone,
                                live = live,
                                open = open == key,
                                onToggle = { open = if (open == key) null else key },
                                symbol = e.symbol,
                                sub = holdingLine(e),
                                right = Fmt.moneyExact(e.marketValue)
                            ) {
                                HoldingFacts(e, tone)
                                DetailLink("Open ${e.symbol}", tone) { onOpenDetail(e.symbol) }
                            }
                        }
                    }
                }
            }
        },
        back = if (actions.isEmpty()) null else (@Composable {
            BackSheet(
                title = "Why each move",
                sub = null,
                rows = actions.map { BackRow(moveTitle(it), line = it.detail) },
                tone = tone,
                inline = true
            )
        })
    )
}

private fun gapsPass(state: WealthState, live: Boolean, onOpenDetail: (String) -> Unit): PassContent {
    val strategy = state.strategy
    val book = state.book
    val tone = WhitePass
    val gaps = strategy?.gaps.orEmpty()
    val open = gaps.filter { it.status == GapStatus.MISSING || it.status == GapStatus.UNDER }
    val sliceColors = listOf(AurumColors.accent, AurumColors.gain, AurumColors.inkDarkDim, AurumColors.textSub, AurumColors.loss)
    return PassContent(
        tone = tone,
        title = "Sector gaps",
        headLabel = "Missing",
        headValue = when {
            strategy == null -> "—"
            open.isEmpty() -> "None"
            open.size == 1 -> "1 theme"
            else -> "${open.size} themes"
        },
        headColor = if (open.isEmpty()) tone.gain else tone.loss,
        face = {
            if (strategy == null) {
                PassMessage(
                    if (state.strategyLoading) "Reading which themes are trending this week…"
                    else "The theme scan needs market data. Pull down to retry.",
                    tone
                )
            } else {
                var openRow by remember { mutableStateOf<String?>(null) }
                PassMessage(strategy.headline, tone)
                if (!book.isEmpty) {
                    Spacer(Modifier.height(14.dp))
                    SplitBar(
                        title = "Your book today · ${Fmt.moneyExact(book.totalValue)}",
                        slices = book.slices.mapIndexed { i, s ->
                            SplitSlice(
                                label = "${s.sector} · ${s.symbols.joinToString(", ")}",
                                right = pct1(s.weightPct),
                                sharePct = s.weightPct,
                                color = sliceColors[i % sliceColors.size]
                            )
                        },
                        tone = tone,
                        live = live
                    )
                }
                Spacer(Modifier.height(14.dp))
                PassCaption("This week's trending themes, against your book", tone)
                gaps.forEach { gap ->
                    val lead = gap.picks.firstOrNull()
                    DetailRow(
                        title = gap.label,
                        tone = tone,
                        live = live,
                        open = openRow == gap.themeKey,
                        onToggle = { openRow = if (openRow == gap.themeKey) null else gap.themeKey },
                        symbol = lead?.symbol,
                        icon = Icons.Rounded.PieChart,
                        sub = "You hold ${pct1(gap.heldPct)} · suggested ${pct1(gap.targetPct)}",
                        right = gapWord(gap.status),
                        rightSub = "${signed1(gap.r5Pct)} in 5 days",
                        rightColor = when (gap.status) {
                            GapStatus.MISSING -> tone.loss
                            GapStatus.COVERED -> tone.gain
                            else -> tone.ink
                        }
                    ) {
                        GapFacts(gap, book.heldWeights, tone)
                        lead?.let { DetailLink("Open ${it.symbol}", tone) { onOpenDetail(it.symbol) } }
                    }
                }
                if (strategy.allocations.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SplitBar(
                        title = "This week's money · ${Fmt.moneyExact(strategy.investable)}",
                        slices = strategy.allocations.mapIndexed { i, a ->
                            SplitSlice(
                                label = a.lead?.let { "${a.label} via ${it.symbol}" } ?: a.label,
                                right = "${Fmt.moneyExact(a.amount)} · ${pct1(a.sharePct)}",
                                sharePct = a.sharePct,
                                color = sliceColors[i % sliceColors.size]
                            )
                        },
                        tone = tone,
                        live = live
                    )
                }
            }
        },
        back = if (strategy == null || strategy.notes.isEmpty()) null else (@Composable {
            BackSheet(
                title = "How the gaps are read",
                sub = "A holding counts toward a theme when it is on the theme's own list, sits in the theme's " +
                    "sector, or its industry names the theme.",
                rows = strategy.notes.map { BackRow(label = "", line = it) },
                tone = tone
            )
        })
    )
}

private fun nextSessionPass(state: WealthState, live: Boolean, onOpenDetail: (String) -> Unit): PassContent {
    val report = state.nextSession
    val picks = report?.picks.orEmpty()
    val alerts = report?.alerts.orEmpty()
    val lead = alerts.firstOrNull() ?: picks.firstOrNull()
    val tone = brightPass(AurumColors.gain, darkInk = true)
    return PassContent(
        tone = tone,
        title = "Next session",
        headLabel = if (alerts.isNotEmpty()) "Every gate cleared" else "Positioned",
        headValue = when {
            report == null -> "—"
            alerts.isNotEmpty() -> "${alerts.size} of ${picks.size}"
            else -> "${picks.size} picks"
        },
        ticket = true,
        face = {
            when {
                report == null -> PassMessage(
                    if (state.nextSessionLoading) "Scanning the screener and replaying every similar day…"
                    else "The next-session scan needs market data. Pull down to retry.",
                    tone
                )
                lead == null -> PassMessage("No name cleared the gates for the next session.", tone)
                else -> {
                    var open by remember { mutableStateOf<String?>(null) }
                    PassNote("${report.headline} ${report.sessionNote}", tone, top = 0.dp)
                    Spacer(Modifier.height(8.dp))
                    LeadRow(
                        symbol = lead.symbol,
                        icon = null,
                        label = "${lead.name} · ${signed1(lead.dayChangePct)} last session",
                        title = lead.symbol,
                        amount = Fmt.moneyExact(lead.price),
                        tone = tone,
                        live = live
                    )
                    Spacer(Modifier.height(12.dp))
                    PassFields(
                        listOf(
                            PassField("Entry", Fmt.moneyExact(lead.entry)),
                            PassField("Target", Fmt.moneyExact(lead.target)),
                            PassField("Stop", Fmt.moneyExact(lead.stop)),
                            PassField("Closed higher", if (lead.probUpPct >= 0) "${lead.probUpPct}%" else "Not measured"),
                            PassField("Similar days", lead.analogDays.toString()),
                            PassField("Score", "${lead.score} / 100")
                        ),
                        tone
                    )
                    Spacer(Modifier.height(14.dp))
                    PassCaption("Every positioned name · ⓘ for its full read", tone)
                    picks.forEachIndexed { i, p ->
                        DetailRow(
                            title = "${i + 1}. ${p.symbol} · ${p.score}",
                            tone = tone,
                            live = live,
                            open = open == p.symbol,
                            onToggle = { open = if (open == p.symbol) null else p.symbol },
                            symbol = p.symbol,
                            sub = if (p.alert) "Cleared every gate · ${followThrough(p)}" else followThrough(p),
                            right = Fmt.moneyExact(p.price),
                            rightSub = "${signed1(p.dayChangePct)} last session"
                        ) {
                            PickFacts(p, tone)
                            DetailLink("Open ${p.symbol}", tone) { onOpenDetail(p.symbol) }
                        }
                    }
                }
            }
        },
        back = if (report == null || report.notes.isEmpty()) null else (@Composable {
            BackSheet(
                title = "How the picks are scored",
                sub = report.sessionNote,
                rows = report.notes.map { BackRow(label = "", line = it) },
                tone = tone
            )
        })
    )
}

private fun mustBuyPass(state: WealthState, live: Boolean, onOpenAnalysis: (String) -> Unit): PassContent {
    val report = state.mustBuy
    val rows = report?.rows.orEmpty()
    val tone = darkPass(AurumColors.surfaceHigh)
    val call = callOf(state.pulse?.call)
    return PassContent(
        tone = tone,
        title = "Must buy",
        headLabel = "Seats",
        headValue = if (report == null) "—" else "${rows.size} of ${MustBuyEngine.SEATS}",
        face = {
            when {
                report == null -> PassMessage(
                    when {
                        state.mustBuyTotal > 0 ->
                            "Racing today's picks through every check — ${state.mustBuyScanned} of ${state.mustBuyTotal}…"
                        state.mustBuyLoading -> "Gathering today's pick lists…"
                        else -> "The pick lists are unreachable. Pull down to retry."
                    },
                    tone
                )
                rows.isEmpty() -> PassMessage("Nothing cleared enough checks today. Cash waits.", tone)
                else -> {
                    var open by remember { mutableStateOf<String?>(null) }
                    LogoTiles(rows.take(3), tone, onOpenAnalysis)
                    Spacer(Modifier.height(14.dp))
                    PassFields(
                        listOf(
                            PassField("Scanned", report.scanned.toString()),
                            PassField("Ruled out", report.disqualified.toString()),
                            PassField("Market", call?.first ?: "—", call?.second)
                        ),
                        tone
                    )
                    Spacer(Modifier.height(14.dp))
                    PassCaption("Every seat · ⓘ for its checks", tone)
                    rows.forEach { r ->
                        DetailRow(
                            title = "${r.rank}. ${r.symbol} · ${pts1(r.totalPoints)} pts",
                            tone = tone,
                            live = live,
                            open = open == r.symbol,
                            onToggle = { open = if (open == r.symbol) null else r.symbol },
                            symbol = r.symbol,
                            sub = "Passed ${r.passed} of ${r.measured} · ${r.name}",
                            right = Fmt.moneyExact(r.price),
                            rightSub = r.dayChangePct?.let { "${signed1(it)} today" }
                        ) {
                            MustBuyFacts(r, tone)
                            DetailLink("Full analysis & SPY race", tone) { onOpenAnalysis(r.symbol) }
                        }
                    }
                }
            }
        },
        back = if (report == null) null else (@Composable {
            BackSheet(
                title = "How a seat is won",
                sub = report.caveat,
                rows = report.notes.map { BackRow(label = "", line = it) },
                tone = tone
            )
        })
    )
}

private fun marketPass(state: WealthState, intro: Boolean): PassContent {
    val pulse = state.pulse
    val tone = darkPass(AurumColors.surface)
    val call = callOf(pulse?.call)
    return PassContent(
        tone = tone,
        title = "Market pulse",
        headLabel = "Call",
        headValue = call?.first ?: "—",
        headColor = call?.second,
        face = {
            if (pulse == null) {
                PassMessage(
                    if (state.pulseLoading) "Reading the whole market…"
                    else "The market data is unreachable. Pull down to retry.",
                    tone
                )
            } else {
                PassFigure(pulse.score.toDouble(), { it.roundToInt().toString() }, "/ 100", 84.sp, tone, intro)
                Text(
                    text = pulse.headline,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp),
                    color = tone.dim,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
                Spacer(Modifier.height(16.dp))
                PassFields(
                    buildList {
                        add(PassField("VIX", pulse.vix?.let { String.format(Locale.US, "%.1f", it) } ?: "—"))
                        pulse.vixChange5d?.let {
                            add(
                                PassField(
                                    "VIX, 5 days", String.format(Locale.US, "%+.1f pts", it),
                                    if (it > 0) tone.loss else tone.gain
                                )
                            )
                        }
                        add(PassField("Above 50-day", "${pulse.breadthAbove50Pct.roundToInt()}% of ${pulse.scannedCount}"))
                        add(PassField("Rose last session", "${pulse.advancersPct.roundToInt()}%"))
                    },
                    tone
                )
                Spacer(Modifier.height(10.dp))
                pulse.indexes.forEach { ix ->
                    PassRow(
                        symbol = null,
                        icon = null,
                        title = ix.name,
                        tone = tone,
                        live = false,
                        sub = "${signed1(ix.r20Pct)} in 20 days · ${signed1(ix.vs50Pct)} vs its 50-day",
                        right = signed1(ix.r5Pct),
                        rightSub = "5 days",
                        rightColor = if (ix.r5Pct >= 0) tone.gain else tone.loss
                    )
                }
                PassNote(pulse.advice, tone)
            }
        },
        back = pulse?.let { p ->
            @Composable {
                BackSheet(
                    title = "What the market says",
                    sub = null,
                    rows = p.reasons.map { BackRow(label = "", line = it) },
                    tone = tone
                )
            }
        }
    )
}

private fun racePass(state: WealthState, live: Boolean): PassContent {
    val perf = state.performance
    val tone = WhitePass
    return PassContent(
        tone = tone,
        title = "Race vs the market",
        headLabel = "Sessions",
        headValue = perf?.sessions?.toString() ?: "—",
        face = {
            if (perf == null) {
                PassMessage(
                    if (state.performanceLoading) "Replaying your trades against SPY…"
                    else "Your race against SPY starts with your first trade.",
                    tone
                )
            } else {
                RaceRoute(perf, tone)
                Spacer(Modifier.height(16.dp))
                PassFields(
                    buildList {
                        add(PassField("Gap", signedPts(perf.edgePct), if (perf.edgePct >= 0) tone.gain else tone.loss))
                        add(PassField("Departed", Fmt.dateShort(perf.startTs)))
                        add(PassField("Arrived", Fmt.dateShort(perf.endTs)))
                        add(PassField("Deepest fall", "-" + Fmt.pct(perf.maxDrawdownPct), tone.loss))
                        perf.twrAnnPct?.let { add(PassField("A year, at this pace", Fmt.signedPct(it))) }
                        perf.mwrAnnPct?.let { add(PassField("Money-weighted, a year", Fmt.signedPct(it))) }
                        add(PassField("Measured", Fmt.pct(perf.coveragePct)))
                    },
                    tone
                )
                Spacer(Modifier.height(10.dp))
                Perforation(tone)
                RaceChart(perf.curve, tone, live)
                PassNote(perf.headline, tone)
            }
        },
        back = perf?.let { p ->
            @Composable {
                BackSheet(
                    title = "The same money in SPY",
                    sub = "Every buy and sell you made, mirrored into SPY on the same days.",
                    rows = listOfNotNull(
                        BackRow("Your book now", Fmt.moneyExact(p.bookNow)),
                        BackRow(
                            "SPY instead", Fmt.moneyExact(p.spyAltNow),
                            if (p.spyAltClamped) "A withdrawal was bigger than the SPY shadow held; it stops at zero." else null
                        ),
                        BackRow("You put in", Fmt.moneyExact(p.investedIn)),
                        BackRow("You took out", Fmt.moneyExact(p.takenOut)),
                        if (p.droppedSymbols.isNotEmpty()) {
                            BackRow("Left out", p.droppedSymbols.joinToString(", "), "No price history could be read for these.")
                        } else null
                    ) + p.notes.map { BackRow(label = "", line = it) } + BackRow(label = "", line = p.caveat),
                    tone = tone
                )
            }
        }
    )
}

// ---------------------------------------------------------------- row details

/** A move in full: why, then the holding's evaluation and a way into the stock. */
@Composable
private fun MoveDetail(
    a: ManagementAction,
    evals: Map<String, HoldingEvaluation>,
    tone: PassTone,
    onOpenDetail: (String) -> Unit
) {
    DetailText(a.detail, tone)
    a.symbol.takeIf { it.isNotEmpty() }?.let { s ->
        evals[s]?.let { HoldingFacts(it, tone, shown = a.detail) }
        DetailLink("Open $s", tone) { onOpenDetail(s) }
    }
}

/**
 * A holding's evaluation: the board, the trend, the stop, the earnings date.
 * [shown] is a reason already printed above, left out of the list.
 */
@Composable
private fun HoldingFacts(e: HoldingEvaluation, tone: PassTone, shown: String? = null) {
    DetailFields(
        listOfNotNull(
            PassField("Value", Fmt.moneyExact(e.marketValue)),
            PassField("Since bought", Fmt.signedPct(e.unrealizedPlPct), if (e.unrealizedPlPct >= 0) tone.gain else tone.loss),
            PassField("Of the book", pct1(e.weightPct)),
            if (e.boardTotal > 0) PassField("Board bullish", "${e.boardBullish} of ${e.boardTotal}") else null,
            e.rsi?.let { PassField("RSI", String.format(Locale.US, "%.0f", it)) },
            e.above50?.let { PassField("50-day line", if (it) "Above" else "Below", if (it) tone.gain else tone.loss) },
            e.above200?.let { PassField("200-day line", if (it) "Above" else "Below", if (it) tone.gain else tone.loss) },
            e.trailStop?.let { PassField("Trailing stop", Fmt.moneyExact(it)) },
            e.stopDistancePct?.let { PassField("Stop room", pct1(it)) },
            e.nextEarningsTs?.let { PassField(if (e.earningsEstimate) "Earnings, estimated" else "Earnings", Fmt.dateShort(it)) },
            PassField("Conviction", "${e.conviction}%")
        ),
        tone
    )
    e.reasons.filter { it != shown }.forEach { DetailText(it, tone) }
}

/** A next-session pick in full: its levels, its odds, its board and its notes. */
@Composable
private fun PickFacts(p: NextSessionPick, tone: PassTone) {
    DetailFields(
        listOfNotNull(
            PassField("Entry", Fmt.moneyExact(p.entry)),
            PassField("Target", Fmt.moneyExact(p.target)),
            PassField("Stop", Fmt.moneyExact(p.stop)),
            PassField("Closed higher", if (p.probUpPct >= 0) "${p.probUpPct}%" else "Not measured"),
            PassField("Similar days", p.analogDays.toString()),
            PassField("Avg next day", if (p.analogDays > 0) Fmt.signedPct(p.avgNextDayPct) else "—"),
            PassField("Next-session range", "${signed1(p.expectedLowPct)} / ${signed1(p.expectedHighPct)}"),
            PassField("Board bullish", "${p.techBullish} of ${p.techTotal}"),
            PassField("RSI", String.format(Locale.US, "%.0f", p.rsi)),
            PassField("Volume", String.format(Locale.US, "%.1f×", p.volumeRatio)),
            p.rel20Pct?.let { PassField("vs SPY, 20 days", signedPts(it)) },
            p.riskReward?.let { PassField("Risk to reward", String.format(Locale.US, "%.1f", it)) },
            p.vwapDistPct?.let { PassField("vs VWAP", signed1(it)) }
        ),
        tone
    )
    if (p.sector.isNotBlank()) DetailText("Sector: ${p.sector}", tone, strong = true)
    DetailText(p.reason, tone)
    listOf(p.heldNote, p.extNote, p.flowNote, p.newsNote.takeIf { it.isNotBlank() }?.let { "News: $it" }.orEmpty())
        .filter { it.isNotBlank() }
        .forEach { DetailText(it, tone, strong = true) }
}

/** A must-buy seat's every check, then what backs it. */
@Composable
private fun MustBuyFacts(r: MustBuyRow, tone: PassTone) {
    r.checks.forEach { c ->
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (c.state) {
                            CheckState.PASS -> tone.gain
                            CheckState.FAIL -> tone.loss
                            CheckState.UNMEASURED -> tone.dim
                        }
                    )
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "${c.label} · ${pts1(c.weight)} pt",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                    color = tone.ink
                )
                Text(
                    text = c.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = tone.dim
                )
            }
        }
    }
    DetailFields(
        listOfNotNull(
            PassField("Checks", "${pts1(r.checkPoints)} of ${pts1(r.checkMax)}"),
            PassField("Backing", "${pts1(r.backingPoints)} pts"),
            r.beatSharePct?.let { PassField("Months beating SPY", "${it.roundToInt()}%") },
            r.edgeEntry?.let { PassField("Green-zone entry", Fmt.moneyExact(it)) }
        ),
        tone
    )
    if (r.backing.isNotEmpty()) {
        DetailText(
            "Backed by " + r.backing.joinToString(" · ") { b ->
                "${b.source} ×${String.format(Locale.US, "%.1f", b.multiplier)}" +
                    if (b.measured) " (${b.gradedCalls} graded)" else ""
            },
            tone
        )
    }
}

/** A theme against the book: who in the book carries it, and the name that would fill it. */
@Composable
private fun GapFacts(gap: SectorGap, weights: Map<String, Double>, tone: PassTone) {
    DetailText(
        if (gap.holders.isEmpty()) "Nothing you hold is in this theme."
        else "In your book: " + gap.holders.joinToString(" · ") { s -> "$s ${pct1(weights[s] ?: 0.0)}" },
        tone,
        strong = true
    )
    if (gap.coverageFromHoldings) {
        DetailText("This theme has no clean sector or industry, so only stocks on its own list count.", tone)
    }
    DetailFields(
        listOf(
            PassField("${gap.etf}, 5 days", signed1(gap.r5Pct), if (gap.r5Pct >= 0) tone.gain else tone.loss),
            PassField("${gap.etf}, 20 days", signed1(gap.r20Pct), if (gap.r20Pct >= 0) tone.gain else tone.loss),
            PassField("Suggested", pct1(gap.targetPct))
        ),
        tone
    )
    gap.picks.firstOrNull()?.let { p ->
        DetailText("Strongest name: ${p.symbol} · ${p.name}", tone, strong = true)
        DetailFields(
            listOf(
                PassField("Price", Fmt.moneyExact(p.price)),
                PassField(if (p.entry < p.price - 0.005) "Wait for" else "Entry", Fmt.moneyExact(p.entry)),
                PassField("3 days", signed1(p.r3Pct)),
                PassField("20 days", signed1(p.r20Pct)),
                PassField("RSI", String.format(Locale.US, "%.0f", p.rsi)),
                PassField("Board bullish", "${p.techBullish} of ${p.techTotal}")
            ),
            tone
        )
        DetailText(p.reason, tone)
        if (p.newsNote.isNotBlank()) DetailText("News: ${p.newsNote}", tone)
    }
    if (gap.picks.size > 1) {
        DetailText("Also passing: " + gap.picks.drop(1).joinToString(", ") { it.symbol }, tone)
    }
}

// ---------------------------------------------------------------- pieces

/** Plays once each time its pass comes to the front. */
@Composable
private fun rememberReveal(live: Boolean, delayMs: Int = 250, durationMs: Int = 900): Float {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(live) {
        if (live) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(durationMs, delayMillis = delayMs, easing = FastOutSlowInEasing))
        } else {
            reveal.snapTo(1f)
        }
    }
    return reveal.value
}

/** A pass's headline figure: rolls per digit and counts in on the first visit. */
@Composable
private fun PassFigure(
    value: Double,
    format: (Double) -> String,
    unit: String,
    size: TextUnit,
    tone: PassTone,
    intro: Boolean
) {
    Row(modifier = Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.Bottom) {
        AnimatedMoney(
            value = value,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = size,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-2).sp
            ),
            baseColor = tone.ink,
            format = format,
            rollIn = intro,
            riseColor = tone.ink,
            fallColor = tone.ink
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                color = tone.dim,
                modifier = Modifier.padding(start = 6.dp, bottom = (size.value * 0.2f).dp)
            )
        }
    }
}

@Composable
private fun PassMessage(text: String, tone: PassTone) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 24.sp),
        color = tone.ink,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp)
    )
}

@Composable
private fun PassNote(text: String, tone: PassTone, top: Dp = 12.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
        color = tone.dim,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = top)
    )
}

@Composable
private fun PassCaption(text: String, tone: PassTone) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        color = tone.dim,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp)
    )
}

/** Three fields to a row, the last one right-aligned, the way a Wallet pass lays them out. */
@Composable
private fun PassFields(
    fields: List<PassField>,
    tone: PassTone,
    gutter: Dp = 18.dp,
    valueSize: TextUnit = 16.sp
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        fields.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in 0 until 3) {
                    val field = row.getOrNull(i)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = if (i == 2) Alignment.End else Alignment.Start
                    ) {
                        if (field != null) {
                            Text(
                                text = field.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = tone.dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = field.value,
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = valueSize, fontWeight = FontWeight.ExtraBold),
                                color = field.color ?: tone.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The fields inside an opened row: the same grid, flush with the row's text. */
@Composable
private fun DetailFields(fields: List<PassField>, tone: PassTone) =
    PassFields(fields, tone, gutter = 0.dp, valueSize = 14.sp)

@Composable
private fun DetailText(text: String, tone: PassTone, strong: Boolean = false) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal
        ),
        color = if (strong) tone.ink else tone.dim
    )
}

@Composable
private fun DetailLink(text: String, tone: PassTone, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
            color = tone.ink
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = tone.ink,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** One bar per graded part of the score, filling as the pass comes forward. */
@Composable
private fun PassBars(fractions: List<Float>, tone: PassTone) {
    val grow = rememberReveal(true, delayMs = 500, durationMs = 1000)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        fractions.forEach { f ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tone.track)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((f * grow).coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(tone.ink)
                )
            }
        }
    }
}

/** A logo — or a glyph for a move without a stock — the size of a pass row. */
@Composable
private fun RowMark(symbol: String?, icon: ImageVector?, size: Dp, tone: PassTone, live: Boolean) {
    if (symbol != null) {
        CompanyLogo(symbol = symbol, size = size, launchPad = live)
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(tone.ink.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tone.ink, modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}

/**
 * The pass's lead line: a big logo, a big name, and what it is worth. Given
 * [detail], it carries an ⓘ and opens that detail underneath.
 */
@Composable
private fun LeadRow(
    symbol: String?,
    icon: ImageVector?,
    label: String,
    title: String,
    amount: String,
    tone: PassTone,
    live: Boolean,
    open: Boolean = false,
    onToggle: (() -> Unit)? = null,
    detail: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LeadHead(symbol, icon, label, title, amount, tone, live, open, onToggle)
        if (detail != null) {
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 88.dp, end = 18.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = detail
                )
            }
        }
    }
}

@Composable
private fun LeadHead(
    symbol: String?,
    icon: ImageVector?,
    label: String,
    title: String,
    amount: String,
    tone: PassTone,
    live: Boolean,
    open: Boolean,
    onToggle: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowMark(symbol, icon, 56.dp, tone, live)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                color = tone.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold),
                color = tone.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (amount.isNotEmpty()) {
            Text(
                text = amount,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                color = tone.ink
            )
        }
        if (onToggle != null) {
            Spacer(Modifier.width(10.dp))
            InfoDot(open, tone)
        }
    }
}

/** The head of a pass row: mark, title and sub, and the figure on the right. */
@Composable
private fun RowHead(
    symbol: String?,
    icon: ImageVector?,
    title: String,
    tone: PassTone,
    live: Boolean,
    sub: String?,
    right: String?,
    rightSub: String?,
    rightColor: Color?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (symbol != null || icon != null) {
            RowMark(symbol, icon, 32.dp, tone, live)
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                color = tone.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = tone.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (right != null) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = right,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                    color = rightColor ?: tone.ink,
                    maxLines = 1
                )
                if (rightSub != null) {
                    Text(
                        text = rightSub,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = tone.dim,
                        maxLines = 1
                    )
                }
            }
        }
        trailing()
    }
}

@Composable
private fun PassRow(
    symbol: String?,
    icon: ImageVector?,
    title: String,
    tone: PassTone,
    live: Boolean,
    sub: String? = null,
    right: String? = null,
    rightSub: String? = null,
    rightColor: Color? = null
) {
    RowHead(
        symbol, icon, title, tone, live, sub, right, rightSub, rightColor,
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .drawBehind { drawLine(tone.rule, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
    )
}

/**
 * A pass row that opens in place: a tap on the row or its ⓘ shows the full
 * detail underneath, and a second tap folds it away.
 */
@Composable
private fun DetailRow(
    title: String,
    tone: PassTone,
    live: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    symbol: String? = null,
    icon: ImageVector? = null,
    sub: String? = null,
    right: String? = null,
    rightSub: String? = null,
    rightColor: Color? = null,
    detail: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .drawBehind { drawLine(tone.rule, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
    ) {
        RowHead(
            symbol, icon, title, tone, live, sub, right, rightSub, rightColor,
            modifier = Modifier
                .clickable(onClick = onToggle)
                .semantics {
                    role = Role.Button
                    stateDescription = if (open) "Details shown" else "Details hidden"
                }
        ) {
            Spacer(Modifier.width(10.dp))
            InfoDot(open, tone)
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (symbol != null || icon != null) 42.dp else 0.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = detail
            )
        }
    }
}

/** The small ⓘ at the end of a row: outlined while folded, filled while open. */
@Composable
private fun InfoDot(open: Boolean, tone: PassTone) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (open) tone.ink else Color.Transparent)
            .border(1.5.dp, if (open) tone.ink else tone.dim, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "i",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
            color = if (open) tone.ground else tone.ink
        )
    }
}

/** The must-buys as three big logos, each opening its full analysis. */
@Composable
private fun LogoTiles(rows: List<MustBuyRow>, tone: PassTone, onOpenAnalysis: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { r ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenAnalysis(r.symbol) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CompanyLogo(symbol = r.symbol, size = 64.dp, launchPad = false)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = r.symbol,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                    color = tone.ink,
                    maxLines = 1
                )
                Text(
                    text = "${pts1(r.totalPoints)} pts",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                    color = AurumColors.accent
                )
                Text(
                    text = "Passed ${r.passed} of ${r.measured}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = tone.dim,
                    maxLines = 1
                )
            }
        }
    }
}

/** You and SPY as the two ends of a boarding pass. */
@Composable
private fun RaceRoute(perf: PerformanceReport, tone: PassTone) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        RouteEnd("Your trades", "YOU", Fmt.signedPct(perf.twrPct), if (perf.twrPct >= 0) tone.gain else tone.loss, tone, Alignment.Start)
        Box(modifier = Modifier.weight(1f).padding(bottom = 30.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = tone.dim,
                modifier = Modifier.size(28.dp)
            )
        }
        RouteEnd("S&P 500 fund", "SPY", Fmt.signedPct(perf.spyTwrPct), if (perf.spyTwrPct >= 0) tone.gain else tone.loss, tone, Alignment.End)
    }
}

@Composable
private fun RouteEnd(caption: String, code: String, value: String, color: Color, tone: PassTone, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = tone.dim
        )
        Text(
            text = code,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
            color = tone.ink
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
            color = color
        )
    }
}

/** The tear line of a boarding pass, notched at both edges. */
@Composable
private fun Perforation(tone: PassTone) {
    Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
        val r = 14.dp.toPx()
        val cy = size.height / 2f
        drawCircle(AurumColors.bg, r, Offset(0f, cy))
        drawCircle(AurumColors.bg, r, Offset(size.width, cy))
        drawLine(
            color = tone.rule,
            start = Offset(22.dp.toPx(), cy),
            end = Offset(size.width - 22.dp.toPx(), cy),
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        )
    }
}

/** Your book against SPY, session by session, drawn in as the pass comes forward. */
@Composable
private fun RaceChart(curve: List<CurvePoint>, tone: PassTone, live: Boolean) {
    val reveal = rememberReveal(live, delayMs = 300, durationMs = 1100)
    val spyColor = AurumColors.textDim
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(horizontal = 18.dp, vertical = 4.dp)
    ) {
        if (curve.size < 2) return@Canvas
        val lo = curve.minOf { minOf(it.book, it.spy) }
        val hi = curve.maxOf { maxOf(it.book, it.spy) }
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val inset = 4.dp.toPx()
        fun x(i: Int) = i / (curve.size - 1f) * size.width
        fun y(v: Double) = (inset + (1 - (v - lo) / span) * (size.height - inset * 2)).toFloat()
        val spy = Path()
        val you = Path()
        curve.forEachIndexed { i, p ->
            if (i == 0) {
                spy.moveTo(x(i), y(p.spy))
                you.moveTo(x(i), y(p.book))
            } else {
                spy.lineTo(x(i), y(p.spy))
                you.lineTo(x(i), y(p.book))
            }
        }
        val last = curve.last()
        clipRect(right = size.width * reveal) {
            drawPath(spy, spyColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(
                you,
                if (last.book >= last.spy) tone.gain else tone.loss,
                style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/** A whole split into shares: one bar, and a line per slice. */
@Composable
private fun SplitBar(title: String, slices: List<SplitSlice>, tone: PassTone, live: Boolean) {
    val reveal = rememberReveal(live, delayMs = 400, durationMs = 900)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = tone.dim
        )
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            val total = slices.sumOf { it.sharePct }.takeIf { it > 0 } ?: 1.0
            val gap = 3.dp.toPx()
            val usable = size.width - gap * (slices.size - 1)
            var x = 0f
            clipRect(right = size.width * reveal) {
                slices.forEach { s ->
                    val w = (usable * (s.sharePct / total)).toFloat()
                    drawRect(s.color, Offset(x, 0f), Size(w, size.height))
                    x += w + gap
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        slices.forEach { s ->
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(s.color))
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    color = tone.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                Text(
                    text = s.right,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
                    color = tone.ink,
                    maxLines = 1
                )
            }
        }
    }
}

/** The back of a pass: the reasons behind what its face shows. */
@Composable
private fun BackSheet(title: String, sub: String?, rows: List<BackRow>, tone: PassTone, inline: Boolean = false) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
        color = tone.ink
    )
    if (!sub.isNullOrBlank()) {
        Text(
            text = sub,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = tone.dim,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
    rows.forEach { row ->
        val ruled = Modifier.drawBehind {
            drawLine(tone.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        if (inline) {
            Row(modifier = Modifier.fillMaxWidth().then(ruled).padding(vertical = 7.dp)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 18.sp),
                    color = tone.ink,
                    modifier = Modifier.width(96.dp)
                )
                Text(
                    text = row.line.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = tone.dim
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().then(ruled).padding(vertical = 10.dp)) {
                if (row.label.isNotBlank()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
                            color = tone.ink,
                            modifier = Modifier.weight(1f)
                        )
                        if (row.value != null) {
                            Text(
                                text = row.value,
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
                                color = row.color ?: tone.ink,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
                if (row.line != null) {
                    Text(
                        text = row.line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = if (row.label.isBlank()) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (row.label.isBlank()) tone.ink else tone.dim,
                        modifier = Modifier.padding(top = if (row.label.isBlank()) 0.dp else 2.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- words

private fun moveTitle(a: ManagementAction): String = when (a.kind) {
    ActionKind.EXIT -> "Exit ${a.symbol}"
    ActionKind.TRIM -> "Trim ${a.symbol}"
    ActionKind.ADD -> "Add to ${a.symbol}"
    ActionKind.SET_STOP -> if (a.symbol.isEmpty()) "Set trailing stops" else "Set a stop on ${a.symbol}"
    ActionKind.DEPLOY -> "Deploy idle cash"
    ActionKind.RAISE_CASH -> "Raise cash"
    ActionKind.DIVERSIFY -> "Spread the concentration"
}

private fun moveIcon(kind: ActionKind): ImageVector = when (kind) {
    ActionKind.EXIT -> Icons.AutoMirrored.Rounded.Logout
    ActionKind.TRIM -> Icons.Rounded.ContentCut
    ActionKind.ADD -> Icons.Rounded.Add
    ActionKind.SET_STOP -> Icons.Rounded.Shield
    ActionKind.DEPLOY -> Icons.Rounded.Download
    ActionKind.RAISE_CASH -> Icons.Rounded.Savings
    ActionKind.DIVERSIFY -> Icons.Rounded.PieChart
}

/** A holding's one-line read for a row: its share of the book and its run since the buy. */
private fun holdingLine(e: HoldingEvaluation): String =
    "${pct1(e.weightPct)} of the book · ${signed1(e.unrealizedPlPct)}"

private fun gapWord(status: GapStatus): String = when (status) {
    GapStatus.MISSING -> "Missing"
    GapStatus.UNDER -> "Light"
    GapStatus.COVERED -> "Covered"
    GapStatus.OVER -> "Heavy"
}

private fun callOf(call: MarketCall?): Pair<String, Color>? = when (call) {
    MarketCall.INVEST -> "Worth investing" to AurumColors.gain
    MarketCall.SELECTIVE -> "Selective only" to AurumColors.accent
    MarketCall.DEFENSIVE -> "Not this week" to AurumColors.loss
    null -> null
}

private fun followThrough(p: NextSessionPick): String =
    if (p.probUpPct >= 0 && p.analogDays > 0) "${p.probUpPct}% up · ${p.analogDays} similar days"
    else "Too few similar days"

private fun pct1(v: Double): String = String.format(Locale.US, "%.1f%%", v)

private fun signed1(v: Double): String = (if (v >= 0) "+" else "-") + String.format(Locale.US, "%.1f%%", abs(v))

private fun signedPts(v: Double): String = (if (v >= 0) "+" else "-") + String.format(Locale.US, "%.1f pts", abs(v))

private fun pts1(v: Double): String = String.format(Locale.US, "%.1f", v)
