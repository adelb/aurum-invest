package com.aurum.invest.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.CompanyLogo
import com.aurum.invest.ui.components.Hint
import com.aurum.invest.ui.components.pressScale
import com.aurum.invest.ui.theme.AurumColors
import kotlin.math.abs
import kotlin.math.sin

/** The three cards, back to front. */
private enum class WalletCard { CASH, STOCKS, GOLD }

/** One card's paint: its ground, its ink, and the deeper gain/loss it can carry. */
private class CardTone(
    val ground: Color,
    val ink: Color,
    val dim: Color,
    val chip: Color,
    val gain: Color,
    val loss: Color
)

private val CashTone = CardTone(
    ground = AurumColors.surfaceHigh, ink = AurumColors.text, dim = AurumColors.textSub,
    chip = AurumColors.surfaceTop, gain = AurumColors.gain, loss = AurumColors.loss
)
private val StocksTone = CardTone(
    ground = AurumColors.cardWhite, ink = AurumColors.inkDark, dim = AurumColors.inkDarkDim,
    chip = AurumColors.cardWhiteChip, gain = AurumColors.gainOnLight, loss = AurumColors.lossOnLight
)
private val GoldTone = CardTone(
    ground = AurumColors.gold, ink = AurumColors.goldInk, dim = AurumColors.goldInkDim,
    chip = AurumColors.goldBright, gain = AurumColors.gainOnGold, loss = AurumColors.lossOnGold
)

/** A label over a figure in an open card's ledger. */
private data class CardFact(val label: String, val value: String, val color: Color? = null)

/** How far each card peeks out from under the next while the stack is closed. */
private val PEEK = 60.dp
private val CARD_H = 200.dp
private val OPEN_H = 304.dp
/** A card pushed to the pile shows only its label strip. */
private val STRIP_H = 64.dp
private val PILE_Y = 320.dp
private val PILE_STEP = 52.dp

private val CardShape = RoundedCornerShape(22.dp)
private val DropEasing = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)

/**
 * Home's wallet as a stack of cards, the Apple Wallet way the user picked:
 * cash, stocks and gold each on its own card, peeking out from under the
 * next. Tapping one brings it to the top and opens its ledger while the other
 * two drop to a pile of label strips; tapping it again closes the stack.
 *
 * On the first visit ([intro]) the cards drop into place one after another
 * and their figures count in; afterwards the stack simply sits there, so
 * coming back from a stock does not replay it.
 */
@Composable
fun WalletStack(
    state: DashboardState,
    intro: Boolean,
    onIntroDone: () -> Unit,
    onEditWallet: () -> Unit,
    onAddGold: () -> Unit,
    onOpenGold: () -> Unit
) {
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openName?.let { WalletCard.valueOf(it) }
    LaunchedEffect(Unit) {
        if (intro) {
            kotlinx.coroutines.delay(1400)
            onIntroDone()
        }
    }

    val wallet = state.wallet
    val summary = state.summary
    val book = state.goldBook
    val openLots = state.goldLots.count { !it.sold }

    val stackHeight by animateDpAsState(
        targetValue = if (open == null) PEEK * 2 + CARD_H else PILE_Y + PILE_STEP + STRIP_H,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "walletStackHeight"
    )

    Box(modifier = Modifier.fillMaxWidth().height(stackHeight)) {
        WalletCard.entries.forEachIndexed { index, card ->
            val others = WalletCard.entries.filter { it != open }
            val y = when {
                open == null -> PEEK * index
                card == open -> 0.dp
                else -> PILE_Y + PILE_STEP * others.indexOf(card)
            }
            val h = when {
                open == null -> CARD_H
                card == open -> OPEN_H
                else -> STRIP_H
            }
            val z = when {
                open == null -> index.toFloat()
                card == open -> 3f
                else -> others.indexOf(card).toFloat()
            }
            val toggle = { openName = if (open == card) null else card.name }
            when (card) {
                WalletCard.CASH -> StackCard(
                    tone = CashTone, y = y, height = h, z = z, index = index, intro = intro,
                    open = open == card,
                    icon = Icons.Rounded.AccountBalanceWallet,
                    label = "Cash",
                    description = if (wallet.configured) "Cash, ${Fmt.moneyExact(wallet.liquidity)} free to invest"
                    else "Cash. Set your wallet",
                    onClick = if (wallet.configured) toggle else onEditWallet,
                    trailing = { Tag("USD", CashTone) },
                    figure = {
                        if (wallet.configured) {
                            Figure(
                                value = wallet.liquidity, tone = CashTone, intro = intro,
                                color = if (wallet.shortfall) AurumColors.loss else CashTone.ink
                            )
                        } else {
                            Placeholder("Set your wallet", CashTone)
                        }
                    },
                    sub = if (!wallet.configured) {
                        AnnotatedString("Tell Aurum the cash you put in.")
                    } else if (wallet.shortfall) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = AurumColors.loss, fontWeight = FontWeight.ExtraBold)) {
                                append(Fmt.moneyExact(-wallet.liquidity))
                            }
                            append(" more invested than the wallet covers")
                        }
                    } else {
                        AnnotatedString("Free to invest, from your ${Fmt.money(wallet.total)} wallet")
                    },
                    facts = listOf(
                        CardFact("Wallet", Fmt.moneyExact(wallet.total)),
                        CardFact("Invested", Fmt.moneyExact(wallet.invested)),
                        CardFact(
                            "Realized", Fmt.signedMoneyExact(wallet.realizedPl),
                            if (wallet.realizedPl >= 0) CashTone.gain else CashTone.loss
                        ),
                        CardFact("Account value", Fmt.moneyExact(wallet.netWorth))
                    ),
                    link = "Edit wallet" to onEditWallet
                )

                WalletCard.STOCKS -> {
                    val value = summary?.marketValue ?: state.holdings.sumOf { it.view.marketValue }
                    val invested = summary?.investedCost ?: 0.0
                    val unrealized = summary?.unrealizedPl ?: 0.0
                    val held = state.holdings.isNotEmpty()
                    StackCard(
                        tone = StocksTone, y = y, height = h, z = z, index = index, intro = intro,
                        open = open == card,
                        icon = Icons.AutoMirrored.Rounded.ShowChart,
                        label = "Stocks",
                        description = "Stocks, ${Fmt.moneyExact(value)}",
                        onClick = toggle,
                        trailing = { if (held) LogoFan(state) else Tag("No stocks yet", StocksTone) },
                        figure = {
                            if (summary != null || held) {
                                Figure(value = value, tone = StocksTone, intro = intro)
                            } else {
                                Placeholder(if (state.loading) "Pricing…" else "$0.00", StocksTone)
                            }
                        },
                        sub = if (invested > 0.0) {
                            buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = if (unrealized >= 0) StocksTone.gain else StocksTone.loss,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                ) {
                                    append(Fmt.signedMoneyExact(unrealized))
                                    append(" · ")
                                    append(Fmt.signedPct(unrealized / invested * 100.0))
                                }
                                append(" on what you put in")
                            }
                        } else {
                            AnnotatedString("Record a buy and it is priced here, live.")
                        },
                        facts = listOf(
                            CardFact("Cost", Fmt.moneyExact(invested)),
                            CardFact(
                                "Unrealized", Fmt.signedMoneyExact(unrealized),
                                if (unrealized >= 0) StocksTone.gain else StocksTone.loss
                            ),
                            CardFact(
                                "Today", Fmt.signedMoneyExact(summary?.dayPl ?: 0.0),
                                if ((summary?.dayPl ?: 0.0) >= 0) StocksTone.gain else StocksTone.loss
                            ),
                            CardFact("Holdings", state.holdings.size.toString())
                        ),
                        link = null
                    )
                }

                WalletCard.GOLD -> {
                    val karats = book.lots.map { it.lot.karat }.distinct().sorted().joinToString(" + ") { "${it}K" }
                    val grams = Fmt.qty(Math.round(book.grams * 10) / 10.0)
                    val priced = book.lots.isNotEmpty()
                    StackCard(
                        tone = GoldTone, y = y, height = h, z = z, index = index, intro = intro,
                        open = open == card,
                        icon = Icons.Rounded.Toll,
                        label = "Gold",
                        description = if (priced) "Gold, ${Fmt.jod(book.value)}" else "Gold. Add gold you bought",
                        onClick = if (openLots == 0) onAddGold else toggle,
                        trailing = { if (priced) Tag("$karats · $grams g", GoldTone) },
                        figure = {
                            when {
                                priced -> Figure(
                                    value = book.value, tone = GoldTone, intro = intro,
                                    format = { Fmt.jod(it) }
                                )
                                openLots == 0 -> Placeholder("No gold yet", GoldTone)
                                else -> Placeholder("Pricing…", GoldTone)
                            }
                        },
                        sub = when {
                            priced -> buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = if (book.pl >= 0) GoldTone.gain else GoldTone.loss,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                ) {
                                    append(Fmt.signedJod(book.pl))
                                    append(" · ")
                                    append(Fmt.signedPct(book.plPct))
                                }
                                append(" since you bought")
                            }
                            openLots == 0 -> AnnotatedString("Record what you bought to see it valued.")
                            else -> AnnotatedString("Today’s Syndicate list hasn’t loaded yet.")
                        },
                        facts = listOf(
                            CardFact("Paid", Fmt.jod(book.paid)),
                            CardFact("In dollars", Fmt.moneyExact(book.valueUsd)),
                            CardFact("Weight", "$grams g"),
                            if (book.pl >= 0) CardFact("Room to fall", Fmt.pct(abs(book.cushionPct)))
                            else CardFact("Needs to rise", Fmt.pct(abs(book.cushionPct)))
                        ),
                        link = "Open Gold" to onOpenGold,
                        glints = priced
                    )
                }
            }
        }
    }
    Hint(
        if (state.goldList != null) {
            "Tap a card to bring it forward. Gold is valued at what a Jordan shop would pay you " +
                "today, from the Syndicate list of ${listLabel(state.goldList)}."
        } else {
            "Tap a card to bring it forward. Gold is valued at what a Jordan shop would pay you today."
        }
    )
}

@Composable
private fun StackCard(
    tone: CardTone,
    y: Dp,
    height: Dp,
    z: Float,
    index: Int,
    intro: Boolean,
    open: Boolean,
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    figure: @Composable () -> Unit,
    sub: AnnotatedString,
    facts: List<CardFact>,
    link: Pair<String, () -> Unit>?,
    glints: Boolean = false
) {
    val shift by animateDpAsState(
        targetValue = y,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "walletCardY"
    )
    val tall by animateDpAsState(
        targetValue = height,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "walletCardH"
    )
    // The first-visit drop: from 70dp above, slightly small, into place.
    val drop = remember { Animatable(if (intro) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (drop.value < 1f) {
            drop.animateTo(1f, tween(780, delayMillis = 80 + index * 130, easing = DropEasing))
        }
    }
    val lift = with(LocalDensity.current) { 70.dp.toPx() }
    val ledger by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = if (open) tween(360, delayMillis = 140) else tween(160),
        label = "walletLedger"
    )

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
                val s = 0.94f + 0.06f * p
                scaleX = s
                scaleY = s
            }
            .pressScale(pressedScale = 0.98f, onClick = onClick)
            .shadow(elevation = 14.dp, shape = CardShape, clip = false)
            .clip(CardShape)
            .background(tone.ground)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
    ) {
        if (glints) GoldGlints(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(tone.chip),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tone.ink, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = tone.ink,
                    modifier = Modifier.weight(1f)
                )
                trailing()
            }
            Spacer(Modifier.height(16.dp))
            figure()
            Spacer(Modifier.height(4.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = tone.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The ledger: fades and rises in once the card has come forward.
            Column(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .graphicsLayer {
                        alpha = ledger
                        translationY = (1f - ledger) * 12.dp.toPx()
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                facts.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { fact ->
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fact.label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                    color = tone.dim,
                                    maxLines = 1
                                )
                                Text(
                                    text = fact.value,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = fact.color ?: tone.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (link != null && open) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .pressScale(onClick = link.second)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = link.first,
                            style = MaterialTheme.typography.titleSmall,
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
            }
        }
    }
}

/** A card's balance: rolls per digit, and counts in on the first visit. */
@Composable
private fun Figure(
    value: Double,
    tone: CardTone,
    intro: Boolean,
    color: Color = tone.ink,
    format: ((Double) -> String)? = null
) {
    AnimatedMoney(
        value = value,
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold),
        baseColor = color,
        format = format,
        rollIn = intro,
        riseColor = tone.gain,
        fallColor = tone.loss
    )
}

/** Stands where a balance will be, until there is one. */
@Composable
private fun Placeholder(text: String, tone: CardTone) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold),
        color = tone.ink
    )
}

@Composable
private fun Tag(text: String, tone: CardTone) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
        color = tone.dim,
        maxLines = 1
    )
}

/** The biggest holdings' logos, fanned out in the stocks card's corner. */
@Composable
private fun LogoFan(state: DashboardState) {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        state.holdings.take(5).forEach { row ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AurumColors.cardWhite)
                    .padding(2.dp)
            ) {
                CompanyLogo(symbol = row.view.position.symbol, size = 24.dp, launchPad = false)
            }
        }
    }
}

/**
 * Three small four-point glints that twinkle across the gold card in turn —
 * flat light on a flat card, no sheen or gradient.
 */
@Composable
private fun GoldGlints(modifier: Modifier = Modifier) {
    val clock = rememberInfiniteTransition(label = "goldGlints")
    val t by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "goldGlintClock"
    )
    val glint = remember { Color(0xFFFFF6D6) }
    Canvas(modifier = modifier) {
        // (from the right edge, from the top, size, phase)
        val spots = listOf(
            floatArrayOf(28f, 64f, 14f, 0f),
            floatArrayOf(74f, 102f, 10f, 0.35f),
            floatArrayOf(44f, 140f, 12f, 0.65f)
        )
        for (s in spots) {
            val local = ((t + s[3]) % 1f)
            // Up and out over the first half of each cycle, then dark.
            val life = if (local < 0.5f) sin(local / 0.5f * Math.PI).toFloat() else 0f
            if (life <= 0.01f) continue
            val r = s[2].dp.toPx() / 2f * life
            val c = Offset(size.width - s[0].dp.toPx(), s[1].dp.toPx())
            val waist = r * 0.18f
            val star = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + waist, c.y - waist)
                lineTo(c.x + r, c.y)
                lineTo(c.x + waist, c.y + waist)
                lineTo(c.x, c.y + r)
                lineTo(c.x - waist, c.y + waist)
                lineTo(c.x - r, c.y)
                lineTo(c.x - waist, c.y - waist)
                close()
            }
            drawPath(star, color = glint.copy(alpha = life))
        }
    }
}
