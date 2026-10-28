package com.aurum.invest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.HeldPosition
import com.aurum.invest.analytics.PriceProjection
import com.aurum.invest.analytics.RotationState
import com.aurum.invest.analytics.StockCatalog
import com.aurum.invest.analytics.StockStudy
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.Fundamentals
import com.aurum.invest.ui.components.AnimatedMoney
import com.aurum.invest.ui.components.AurumRefreshBox
import com.aurum.invest.ui.components.CompanyLogo
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.InfoDot
import com.aurum.invest.ui.components.Meanings
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.Sparkline
import com.aurum.invest.ui.components.adviceColor
import com.aurum.invest.ui.components.adviceLabel
import com.aurum.invest.ui.components.pressScale
import com.aurum.invest.ui.components.rememberLogoTint
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * The Stocks tab as the user picked it on 28 Oct: an Apple Wallet.
 *
 * Every watched stock is a pass in its company's own colour, peeking out from
 * under the next one. Tapping a pass brings it forward: its live price, its
 * month, the tech read, the entry and the pin, with Analyze, Study and Remove
 * at its foot; ⓘ turns it over for the advice behind the read. A dashed pass
 * adds a ticker. Search shows each sector as a boarding pass (its pulse, what
 * the book holds from it, the next-week watch) with its stocks as ticket
 * stubs, and the same stubs answer a search by tag. A study is one long pass
 * whose sections open like passes in a stack.
 *
 * Everything the tab carried before it is still here; only the look changed.
 */
@Composable
fun StocksScreen(onOpenDetail: (String) -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: StocksViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    // 0 = Watchlist, 1 = Search, 2 = Study.
    var mode by rememberSaveable { mutableIntStateOf(0) }
    // 0 = by tag, 1 = by sector.
    var searchMode by rememberSaveable { mutableIntStateOf(1) }
    // The pass just taken out of the wallet, held for its Undo.
    var removed by remember { mutableStateOf<WatchRow?>(null) }

    LaunchedEffect(mode, searchMode) {
        if (mode == 1 && searchMode == 1) vm.ensureSector()
    }
    LaunchedEffect(removed) {
        if (removed != null) {
            delay(4_500)
            removed = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AurumColors.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StocksHeader(state = state, onRefresh = vm::refresh)
            SegmentedToggle(
                options = listOf("Watchlist", "Search", "Study"),
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier.padding(horizontal = 20.dp),
                compact = true
            )
            when (mode) {
                0 -> WatchWallet(
                    vm = vm,
                    state = state,
                    onOpenDetail = onOpenDetail,
                    onOpenAnalysis = onOpenAnalysis,
                    onStudy = { symbol, name ->
                        vm.selectStudy(symbol, name)
                        mode = 2
                    },
                    onBrowse = {
                        searchMode = 1
                        mode = 1
                    },
                    onRemove = { row ->
                        removed = row
                        vm.remove(row.symbol)
                    }
                )
                1 -> SearchPane(
                    vm = vm,
                    state = state,
                    searchMode = searchMode,
                    onSearchMode = { searchMode = it },
                    onOpenDetail = onOpenDetail,
                    onOpenAnalysis = onOpenAnalysis
                )
                else -> StudyPane(vm, state, onOpenDetail, onOpenAnalysis)
            }
        }
        AnimatedVisibility(
            visible = removed != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
        ) {
            val row = removed
            if (row != null) {
                UndoToast(
                    text = "${row.symbol} left your wallet",
                    onUndo = {
                        vm.restore(row.symbol, row.name, row.pinned)
                        removed = null
                    }
                )
            }
        }
    }
}

@Composable
private fun StocksHeader(state: StocksState, onRefresh: () -> Unit) {
    val updated = state.rows.mapNotNull { it.quote?.fetchedAt }.filter { it > 0L }.maxOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Stocks",
            style = MaterialTheme.typography.headlineMedium,
            color = AurumColors.text,
            modifier = Modifier.weight(1f)
        )
        val spin = rememberInfiniteTransition(label = "refreshSpin")
        val turn by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "refreshTurn"
        )
        Row(
            modifier = Modifier
                .pressScale(onClick = onRefresh)
                .clip(RoundedCornerShape(22.dp))
                .background(AurumColors.surface)
                .height(40.dp)
                .padding(start = 12.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = AurumColors.textSub,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (state.refreshing) turn else 0f)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = when {
                    state.refreshing -> "Refreshing…"
                    updated != null -> "Updated ${Fmt.timeShort(updated)}"
                    else -> "Refresh"
                },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = AurumColors.textSub
            )
        }
    }
}

// ------------------------------------------------------------ the wallet

private val PassShape = RoundedCornerShape(22.dp)
/** The label strip of a pass: what shows of it under the pass in front. */
private val PEEK = 76.dp
private val PASS_H = 448.dp
private val PILE_GAP = 16.dp
private val PILE_STEP = 12.dp
private val DropEasing = CubicBezierEasing(0.3f, 1.3f, 0.5f, 1f)

/** The passes drop in once per launch; coming back to the tab doesn't replay it. */
private var walletDealt = false

/** One pass's paint: its brand-colour ground and the ink that reads on it. */
private class PassPaint(
    val ground: Color,
    val ink: Color,
    val dim: Color,
    val hair: Color,
    /** The dark glass under figures that must keep their gain/loss colour. */
    val glass: Color
)

private fun passPaint(tint: Color?): PassPaint {
    val ground = tint ?: AurumColors.surfaceHigh
    // Whichever ink stands out more from this ground: a mid orange reads far
    // better in dark ink than in white.
    val l = ground.luminance()
    val light = (l + 0.05f) / (AurumColors.inkDark.luminance() + 0.05f) > 1.05f / (l + 0.05f)
    val ink = if (light) AurumColors.inkDark else AurumColors.text
    return PassPaint(
        ground = ground,
        ink = ink,
        dim = ink.copy(alpha = if (light) 0.72f else 0.78f),
        hair = ink.copy(alpha = if (light) 0.16f else 0.2f),
        glass = Color(0xC717171C)
    )
}

@Composable
private fun WatchWallet(
    vm: StocksViewModel,
    state: StocksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit,
    onStudy: (String, String) -> Unit,
    onBrowse: () -> Unit,
    onRemove: (WatchRow) -> Unit
) {
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var openSymbol by rememberSaveable { mutableStateOf<String?>(null) }
    // A pass that has left the wallet can't stay open.
    LaunchedEffect(state.rows) {
        if (openSymbol != null && state.rows.none { it.symbol == openSymbol }) openSymbol = null
    }
    AurumRefreshBox(
        refreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Watching",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = AurumColors.text
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.rows.size.toString(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = AurumColors.textDim
                )
                Spacer(Modifier.weight(1f))
                if (state.rows.isNotEmpty()) {
                    Text(
                        text = if (openSymbol == null) "Pinned first · tap a pass" else "Tap the pile to put it back",
                        style = MaterialTheme.typography.labelMedium,
                        color = AurumColors.textDim
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AddPassSlot(onClick = { addOpen = true })
            Spacer(Modifier.height(14.dp))
            when {
                state.loading && state.rows.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AurumColors.accent)
                }
                state.rows.isEmpty() -> Column {
                    EmptyState(
                        title = "Nothing watched yet",
                        message = "Add a ticker above to track its price and a good entry " +
                            "point — or browse by sector in Search."
                    )
                    Spacer(Modifier.height(12.dp))
                    WalletButton(text = "Browse by sector", onClick = onBrowse)
                }
                else -> PassStack(
                    rows = state.rows,
                    openSymbol = openSymbol,
                    onOpen = { openSymbol = it },
                    onOpenDetail = onOpenDetail,
                    onAnalyze = onOpenAnalysis,
                    onStudy = onStudy,
                    onPin = { row -> vm.setPinned(row.symbol, !row.pinned) },
                    onRemove = { row ->
                        openSymbol = null
                        onRemove(row)
                    }
                )
            }
        }
    }
    if (addOpen) {
        AddSheet(vm = vm, state = state, onDismiss = { addOpen = false })
    }
}

/** The dashed empty pass that adds a ticker. */
@Composable
private fun AddPassSlot(onClick: () -> Unit) {
    val dash = AurumColors.textFaint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pressScale(onClick = onClick)
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx()))
                    )
                )
            }
            .padding(start = 12.dp, end = 14.dp)
            .semantics { contentDescription = "Add a ticker to your watchlist" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AurumColors.surfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = AurumColors.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Add ticker — try AAPL",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = AurumColors.text
            )
            Text(
                text = "A new pass, with live suggestions as you type",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
        }
        Icon(Icons.Rounded.Search, contentDescription = null, tint = AurumColors.textDim, modifier = Modifier.size(20.dp))
    }
}

/**
 * The watchlist as a stack of passes. Closed, each pass shows its label strip
 * under the next and the last one shows whole. Opened, the chosen pass sits
 * on top at full size and the rest drop to a pile below it; tapping the pile
 * puts them all back.
 */
@Composable
private fun PassStack(
    rows: List<WatchRow>,
    openSymbol: String?,
    onOpen: (String?) -> Unit,
    onOpenDetail: (String) -> Unit,
    onAnalyze: (String) -> Unit,
    onStudy: (String, String) -> Unit,
    onPin: (WatchRow) -> Unit,
    onRemove: (WatchRow) -> Unit
) {
    val deal = remember { !walletDealt }
    LaunchedEffect(Unit) { walletDealt = true }
    val n = rows.size
    val openIndex = rows.indexOfFirst { it.symbol == openSymbol }.takeIf { it >= 0 }
    val stackHeight by animateDpAsState(
        targetValue = if (openIndex == null) PEEK * (n - 1) + PASS_H
        else PASS_H + PILE_GAP + PILE_STEP * (n - 2).coerceAtLeast(0) + PEEK,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "walletHeight"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stackHeight)
    ) {
        rows.forEachIndexed { i, row ->
            androidx.compose.runtime.key(row.symbol) {
                val pile = openIndex != null && i != openIndex
                val pileIndex = if (openIndex != null && i > openIndex) i - 1 else i
                WatchPass(
                    row = row,
                    y = when {
                        openIndex == null -> PEEK * i
                        i == openIndex -> 0.dp
                        else -> PASS_H + PILE_GAP + PILE_STEP * pileIndex
                    },
                    height = if (pile) PEEK else PASS_H,
                    z = if (i == openIndex) n.toFloat() else (if (pile) pileIndex else i).toFloat(),
                    index = i,
                    deal = deal,
                    open = i == openIndex,
                    // Closed, only the last pass shows whole; the rest are their strips.
                    showFace = i == openIndex || (openIndex == null && i == n - 1),
                    onTap = {
                        when {
                            openIndex == null -> onOpen(row.symbol)
                            pile -> onOpen(null)
                        }
                    },
                    onClose = { onOpen(null) },
                    onOpenDetail = { onOpenDetail(row.symbol) },
                    onAnalyze = { onAnalyze(row.symbol) },
                    onStudy = { onStudy(row.symbol, row.name) },
                    onPin = { onPin(row) },
                    onRemove = { onRemove(row) }
                )
            }
        }
    }
}

@Composable
private fun WatchPass(
    row: WatchRow,
    y: Dp,
    height: Dp,
    z: Float,
    index: Int,
    deal: Boolean,
    open: Boolean,
    showFace: Boolean,
    onTap: () -> Unit,
    onClose: () -> Unit,
    onOpenDetail: () -> Unit,
    onAnalyze: () -> Unit,
    onStudy: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit
) {
    val density = LocalDensity.current
    val paint = passPaint(rememberLogoTint(row.symbol))
    val ground by animateColorAsState(paint.ground, tween(650), label = "passGround")
    val shift by animateDpAsState(
        targetValue = y,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow),
        label = "passY"
    )
    val tall by animateDpAsState(
        targetValue = height,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "passHeight"
    )
    var flipped by rememberSaveable(row.symbol) { mutableStateOf(false) }
    LaunchedEffect(open) { if (!open) flipped = false }
    val turn by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "passTurn"
    )
    val drop = remember { Animatable(if (deal) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (drop.value < 1f) drop.animateTo(1f, tween(760, delayMillis = 60 + index * 90, easing = DropEasing))
    }
    val lift = with(density) { 140.dp.toPx() }

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
            .shadow(elevation = 12.dp, shape = PassShape, clip = false)
            .clip(PassShape)
            .background(ground)
    ) {
        if (turn <= 90f) {
            PassFront(
                row = row,
                paint = paint,
                showFace = showFace,
                open = open,
                onHeader = onClose,
                onOpenDetail = onOpenDetail,
                onPin = onPin,
                onAnalyze = onAnalyze,
                onStudy = onStudy,
                onRemove = onRemove,
                onFlip = { flipped = true }
            )
        } else {
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                PassBack(row = row, paint = paint, onAnalyze = onAnalyze, onDone = { flipped = false })
            }
        }
        if (!open) {
            // A pass that isn't open is all label: a tap anywhere opens it (or, in the pile, closes the open one).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTap
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "${row.symbol} pass"
                    }
            )
        }
    }
}

@Composable
private fun PassFront(
    row: WatchRow,
    paint: PassPaint,
    showFace: Boolean,
    open: Boolean,
    onHeader: () -> Unit,
    onOpenDetail: () -> Unit,
    onPin: () -> Unit,
    onAnalyze: () -> Unit,
    onStudy: () -> Unit,
    onRemove: () -> Unit,
    onFlip: () -> Unit
) {
    val quote = row.quote
    val advice = row.advice
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .height(PASS_H)
    ) {
        // The label strip: all of a pass that shows in the stack.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PEEK)
                .clickable(enabled = open, onClick = onHeader)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompanyLogo(symbol = row.symbol, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.symbol,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
                        color = paint.ink,
                        maxLines = 1
                    )
                    if (row.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(paint.glass),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Star, contentDescription = "Pinned", tint = AurumColors.gold, modifier = Modifier.size(13.dp))
                        }
                    }
                }
                if (row.name.isNotBlank()) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = paint.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (quote != null) {
                    AnimatedMoney(
                        value = quote.price,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        baseColor = paint.ink,
                        riseColor = paint.ink,
                        fallColor = paint.ink,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                } else {
                    Text("—", style = MaterialTheme.typography.titleMedium, color = paint.ink)
                }
                Spacer(Modifier.height(3.dp))
                DeltaGlass(value = quote?.dayChangePct, paint = paint)
            }
        }
        if (!showFace) return@Column

        // The price, large.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                PassLabel(
                    text = quote?.fetchedAt?.takeIf { it > 0L }?.let { "Price · ${Fmt.timeShort(it)}" } ?: "Price",
                    paint = paint
                )
                if (quote != null) {
                    AnimatedMoney(
                        value = quote.price,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold),
                        baseColor = paint.ink,
                        riseColor = paint.ink,
                        fallColor = paint.ink
                    )
                } else {
                    Text("—", style = MaterialTheme.typography.headlineMedium, color = paint.ink)
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                PassLabel(text = "Today", paint = paint)
                Spacer(Modifier.height(4.dp))
                DeltaGlass(value = quote?.dayChangePct, paint = paint, large = true)
            }
        }
        Spacer(Modifier.height(6.dp))
        SparkStrip(
            data = row.spark,
            entry = advice?.suggestedBuyPrice,
            paint = paint,
            onOpen = onOpenDetail
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(paint.hair)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp)
        ) {
            PassField(label = "Tech read", paint = paint, modifier = Modifier.weight(1f)) {
                if (advice != null) {
                    GlassPill(text = adviceLabel(advice.action), color = adviceColor(advice.action), paint = paint)
                } else {
                    Text("Analyzing…", style = passValueStyle(15), color = paint.dim)
                }
            }
            PassField(label = "Entry ≈", paint = paint, modifier = Modifier.weight(1f)) {
                Text(
                    text = advice?.suggestedBuyPrice?.let { Fmt.money(it) } ?: "—",
                    style = passValueStyle(16),
                    color = paint.ink,
                    maxLines = 1
                )
            }
            PassField(label = "Pinned", paint = paint, modifier = Modifier.weight(1f)) {
                Text(
                    text = if (row.pinned) "Yes" else "No",
                    style = passValueStyle(16),
                    color = paint.ink
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp)
        ) {
            PassField(label = "Prev close", paint = paint, modifier = Modifier.weight(1f)) {
                Text(
                    text = quote?.prevClose?.takeIf { it > 0.0 }?.let { Fmt.money(it) } ?: "—",
                    style = passValueStyle(15),
                    color = paint.ink,
                    maxLines = 1
                )
            }
            PassField(label = "Last 2 weeks", paint = paint, modifier = Modifier.weight(1f)) {
                DeltaGlass(value = spanChange(row.spark, 10), paint = paint)
            }
            PassField(label = "30 sessions", paint = paint, modifier = Modifier.weight(1f)) {
                DeltaGlass(value = spanChange(row.spark, row.spark.size - 1), paint = paint)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(paint.hair)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FootAction(
                icon = if (row.pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                label = if (row.pinned) "Pinned" else "Pin",
                iconTint = if (row.pinned) AurumColors.gold else paint.ink,
                paint = paint,
                description = if (row.pinned) "Unpin ${row.symbol}" else "Pin ${row.symbol}",
                onClick = onPin,
                modifier = Modifier.weight(1f)
            )
            FootAction(Icons.Rounded.QueryStats, "Analyze", paint.ink, paint, "Analyze ${row.symbol}", onAnalyze, Modifier.weight(1f))
            FootAction(Icons.AutoMirrored.Rounded.MenuBook, "Study", paint.ink, paint, "Study ${row.symbol}", onStudy, Modifier.weight(1f))
            FootAction(Icons.Rounded.DeleteOutline, "Remove", paint.ink, paint, "Remove ${row.symbol}", onRemove, Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(paint.glass)
                    .clickable(onClick = onFlip)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Turn the ${row.symbol} pass over for the advice"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = AurumColors.text, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The back of a pass: the advice behind its tech read. */
@Composable
private fun PassBack(row: WatchRow, paint: PassPaint, onAnalyze: () -> Unit, onDone: () -> Unit) {
    val advice = row.advice
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .height(PASS_H)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompanyLogo(symbol = row.symbol, size = 40.dp, launchPad = false)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${row.symbol} · the advice",
                    style = passValueStyle(17),
                    color = paint.ink
                )
                Text(
                    text = "Tech read, from the price alone",
                    style = MaterialTheme.typography.labelMedium,
                    color = paint.dim
                )
            }
            if (advice != null) {
                GlassPill(text = adviceLabel(advice.action), color = adviceColor(advice.action), paint = paint)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(paint.hair)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            if (advice == null) {
                Text("Analyzing…", style = passValueStyle(16), color = paint.dim)
            } else {
                Text(
                    text = advice.headline,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp),
                    color = paint.ink
                )
                if (advice.reasons.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    PassLabel(text = "Why", paint = paint)
                    advice.reasons.forEach { reason ->
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 7.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(paint.ink)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = paint.ink
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    PassField(label = "Entry ≈", paint = paint, modifier = Modifier.weight(1f)) {
                        Text(advice.suggestedBuyPrice?.let { Fmt.money(it) } ?: "—", style = passValueStyle(15), color = paint.ink)
                    }
                    PassField(label = "Target", paint = paint, modifier = Modifier.weight(1f)) {
                        GlassPill(text = advice.targetPrice?.let { Fmt.money(it) } ?: "—", color = AurumColors.gold, paint = paint)
                    }
                    PassField(label = "Stop", paint = paint, modifier = Modifier.weight(1f)) {
                        GlassPill(text = advice.stopLoss?.let { Fmt.money(it) } ?: "—", color = AurumColors.loss, paint = paint)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PassField(label = "Score", paint = paint) {
                    Text("${advice.score} / 100", style = passValueStyle(15), color = paint.ink)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(paint.hair)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(text = "Analyze", icon = Icons.Rounded.QueryStats, paint = paint, onClick = onAnalyze, modifier = Modifier.weight(1f))
            GlassButton(text = "Done", icon = null, paint = paint, onClick = onDone)
        }
    }
}

/** The month as the pass's strip: its line, its entry level, and the way into the stock. */
@Composable
private fun SparkStrip(data: List<Double>, entry: Double?, paint: PassPaint, onOpen: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, color = AurumColors.gold)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "Open the stock page" }
    ) {
        if (data.size >= 2) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val shown = entry?.takeIf { e ->
                    val lo = data.min()
                    val hi = data.max()
                    // An entry far off the month would flatten the line; it stays in the fields instead.
                    e >= lo - (hi - lo) * 0.5 && e <= hi + (hi - lo) * 0.5
                }
                val lo = min(data.min(), shown ?: data.min())
                val hi = max(data.max(), shown ?: data.max())
                val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                val top = 24.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                fun yOf(v: Double) = (top + (1 - (v - lo) / span) * (bottom - top)).toFloat()
                val step = size.width / (data.size - 1)
                val line = Path()
                data.forEachIndexed { i, v ->
                    if (i == 0) line.moveTo(0f, yOf(v)) else line.lineTo(i * step, yOf(v))
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(area, paint.ink.copy(alpha = 0.12f))
                drawPath(line, paint.ink, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (shown != null) {
                    val ey = yOf(shown)
                    drawLine(
                        color = paint.ink.copy(alpha = 0.55f),
                        start = Offset(0f, ey),
                        end = Offset(size.width, ey),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                    )
                    val label = measurer.measure("Entry ≈ ${Fmt.money(shown)}", labelStyle)
                    val pad = 7.dp.toPx()
                    val w = label.size.width + pad * 2
                    val h = label.size.height + 4.dp.toPx()
                    val left = size.width - w - 12.dp.toPx()
                    val labelTop = (ey - h / 2f).coerceIn(0f, size.height - h)
                    drawRoundRect(
                        color = Color(0xB3000000),
                        topLeft = Offset(left, labelTop),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2f)
                    )
                    drawText(label, topLeft = Offset(left + pad, labelTop + 2.dp.toPx()))
                }
            }
        } else {
            Text(
                text = "Analyzing…",
                style = passValueStyle(14),
                color = paint.dim,
                modifier = Modifier.padding(start = 18.dp, top = 38.dp)
            )
        }
        Text(
            text = "${data.size} sessions",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = paint.dim,
            modifier = Modifier.padding(start = 18.dp, top = 2.dp)
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Stock page",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = paint.dim
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = paint.dim, modifier = Modifier.size(16.dp))
        }
    }
}

/** The move over the last [sessions] closes of [closes]; null when the line is too short. */
private fun spanChange(closes: List<Double>, sessions: Int): Double? {
    if (sessions <= 0 || closes.size <= sessions) return null
    val base = closes[closes.size - 1 - sessions]
    return if (base > 0.0) (closes.last() - base) / base * 100.0 else null
}

@Composable
private fun passValueStyle(size: Int): TextStyle =
    MaterialTheme.typography.titleSmall.copy(fontSize = size.sp, fontWeight = FontWeight.ExtraBold)

@Composable
private fun PassLabel(text: String, paint: PassPaint) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
        color = paint.dim,
        maxLines = 1
    )
}

@Composable
private fun PassField(
    label: String,
    paint: PassPaint,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        PassLabel(text = label, paint = paint)
        Spacer(Modifier.height(5.dp))
        value()
    }
}

/** A gain or loss on dark glass, so it reads on any brand colour. */
@Composable
private fun DeltaGlass(value: Double?, paint: PassPaint, large: Boolean = false) {
    GlassPill(
        text = value?.let { Fmt.signedPct(it) } ?: "—",
        color = when {
            value == null -> AurumColors.textSub
            value >= 0.0 -> AurumColors.gain
            else -> AurumColors.loss
        },
        paint = paint,
        large = large
    )
}

@Composable
private fun GlassPill(text: String, color: Color, paint: PassPaint, large: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(if (large) 10.dp else 8.dp))
            .background(paint.glass)
            .padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 4.dp else 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = if (large) 15.sp else 12.5.sp,
                fontWeight = FontWeight.ExtraBold
            ),
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun FootAction(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    paint: PassPaint,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(paint.glass),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (iconTint == paint.ink) AurumColors.text else iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = paint.ink,
            maxLines = 1
        )
    }
}

@Composable
private fun GlassButton(
    text: String,
    icon: ImageVector?,
    paint: PassPaint,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .pressScale(onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(paint.glass)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AurumColors.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = passValueStyle(14), color = AurumColors.text)
    }
}

@Composable
private fun WalletButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(46.dp)
            .pressScale(onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(AurumColors.surfaceHigh)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = passValueStyle(14), color = AurumColors.text)
    }
}

@Composable
private fun UndoToast(text: String, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(AurumColors.cardWhite)
            .padding(start = 16.dp, end = 6.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = AurumColors.inkDark,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onUndo)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("Undo", style = passValueStyle(15), color = AurumColors.accent)
        }
    }
}

/** Adding a ticker: the search field and its live suggestions, each a pass in waiting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(vm: StocksViewModel, state: StocksState, onDismiss: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    // The sheet opens ready to type.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(250)
        focus.requestFocus()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AurumColors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                text = "Add to watchlist",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = AurumColors.text
            )
            Text(
                text = "Each pick becomes a pass in your wallet",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = stocksFieldColors(),
                placeholder = { Text("Add ticker — try AAPL", style = MaterialTheme.typography.bodyMedium) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // The keyboard's action key adds the best match directly.
                    state.suggestions.firstOrNull()?.let { (symbol, name) ->
                        vm.addSymbol(symbol, name)
                        keyboard?.hide()
                    }
                }),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = AurumColors.textDim) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = AurumColors.textDim)
                        }
                    }
                }
            )
            if (state.suggestions.isEmpty()) {
                Text(
                    text = "Suggestions arrive while you type: each with its symbol, its company and an add button.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            state.suggestions.forEach { (symbol, name) ->
                val watched = state.watchedSymbols.contains(symbol)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A pass in miniature, the logo at its corner.
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(rememberLogoTint(symbol) ?: AurumColors.surfaceHigh)
                            .padding(start = 7.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        CompanyLogo(symbol = symbol, size = 24.dp, launchPad = false)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(symbol, style = passValueStyle(16), color = AurumColors.text)
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (watched) AurumColors.surfaceHigh else AurumColors.accent)
                            .clickable(enabled = !watched) { vm.addSymbol(symbol, name) }
                            .padding(horizontal = 14.dp)
                            .semantics { contentDescription = if (watched) "$symbol is in your wallet" else "Add $symbol" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (watched) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = null,
                            tint = AurumColors.text,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (watched) "Added" else "Add", style = passValueStyle(14), color = AurumColors.text)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- search

@Composable
private fun SearchPane(
    vm: StocksViewModel,
    state: StocksState,
    searchMode: Int,
    onSearchMode: (Int) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    // Once the trend scan lands the shelves run hot -> cold, so the trendiest sit up front.
    val orderedSectors = remember(state.pulses) {
        if (state.pulses.isEmpty()) StockCatalog.SECTORS
        else StockCatalog.SECTORS.sortedBy { state.pulses[it.name]?.rank ?: Int.MAX_VALUE }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp)
    ) {
        item {
            SegmentedToggle(
                options = listOf("By tag", "By sector"),
                selected = searchMode,
                onSelect = onSearchMode,
                modifier = Modifier.padding(horizontal = 20.dp),
                compact = true
            )
        }
        if (searchMode == 1) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(orderedSectors, key = { it.name }) { sector ->
                        val selected = sector.name == state.selectedSector
                        val hot = state.pulses[sector.name]?.hot == true
                        Row(
                            modifier = Modifier
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) AurumColors.cardWhite else AurumColors.surface)
                                .clickable { vm.selectSector(sector.name) }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hot) {
                                Icon(
                                    imageVector = Icons.Rounded.LocalFireDepartment,
                                    contentDescription = "Trending sector",
                                    tint = if (selected) Color(0xFFB25E00) else AurumColors.gold,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                text = sector.name,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (selected) AurumColors.inkDark else AurumColors.textSub,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            item { SectorPass(state) }
            item { ListTitle(title = state.selectedSector, sub = "${state.sectorRows.size} stocks · best 2 weeks first") }
            if (state.sectorLoading && state.sectorRows.isEmpty()) {
                item { Spinner() }
            } else {
                items(state.sectorRows, key = { "s-" + it.symbol }) { row ->
                    StockStub(
                        row = row,
                        watched = state.watchedSymbols.contains(row.symbol),
                        onOpen = { onOpenDetail(row.symbol) },
                        onAnalyze = { onOpenAnalysis(row.symbol) },
                        onToggleWatch = { vm.toggleWatch(row.symbol, row.name) }
                    )
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = state.tagQuery,
                    onValueChange = vm::onTagQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = stocksFieldColors(),
                    placeholder = { Text("Ticker or company — try NVDA, lithium, bank", style = MaterialTheme.typography.bodyMedium) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = AurumColors.textDim) },
                    trailingIcon = {
                        if (state.tagQuery.isNotEmpty()) {
                            IconButton(onClick = { vm.onTagQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = AurumColors.textDim)
                            }
                        }
                    }
                )
            }
            when {
                state.tagSearching -> item { Spinner() }
                state.tagRows.isEmpty() && state.tagQuery.isBlank() -> item {
                    PromptPass(
                        title = "Search the whole market",
                        message = "Type any ticker or company tag. Each match shows its live price and its " +
                            "move over the last 2 weeks; the strongest performers wear the gold border."
                    )
                }
                state.tagRows.isEmpty() -> item {
                    PromptPass(
                        title = "No matches",
                        message = "No US-listed stock matches \"${state.tagQuery.trim()}\"."
                    )
                }
                else -> {
                    item { ListTitle(title = "Results", sub = "${state.tagRows.size} for \"${state.tagQuery.trim()}\"") }
                    items(state.tagRows, key = { "t-" + it.symbol }) { row ->
                        StockStub(
                            row = row,
                            watched = state.watchedSymbols.contains(row.symbol),
                            onOpen = { onOpenDetail(row.symbol) },
                            onAnalyze = { onOpenAnalysis(row.symbol) },
                            onToggleWatch = { vm.toggleWatch(row.symbol, row.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListTitle(title: String, sub: String) {
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = AurumColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        Text(text = sub, style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim, maxLines = 1)
    }
}

@Composable
private fun Spinner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = AurumColors.accent)
    }
}

/** An empty dashed pass that says what goes here. */
@Composable
private fun PromptPass(title: String, message: String) {
    val dash = AurumColors.surfaceTop
    Column(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 14.dp)
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx()))
                    )
                )
            }
            .padding(20.dp)
    ) {
        Text(title, style = passValueStyle(18), color = AurumColors.text)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = AurumColors.textDim)
    }
}

/**
 * The selected sector as a boarding pass: its money flow on the band, its
 * rank and proxy ETF as the route, the week's figures as the fields, and —
 * below the perforation — what the book holds from it and the next-week watch.
 */
@Composable
private fun SectorPass(state: StocksState) {
    val pulse = state.pulses[state.selectedSector]
    val count = StockCatalog.SECTORS.firstOrNull { it.name == state.selectedSector }?.stocks?.size
    val ink = AurumColors.inkDark
    val dim = AurumColors.inkDarkDim
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(AurumColors.cardWhite)
    ) {
        if (pulse != null) {
            val (band, bandInk, label) = when (pulse.state) {
                RotationState.INFLOW -> Triple(AurumColors.gain, AurumColors.inkDark, "Money flowing in")
                RotationState.OUTFLOW -> Triple(AurumColors.loss, AurumColors.text, "Money rotating out")
                RotationState.STEADY -> Triple(Color(0xFFD1D6DB), AurumColors.inkDarkDim, "Steady")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(band)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = passValueStyle(15), color = bandInk, modifier = Modifier.weight(1f))
                Text("Sector pulse", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = bandInk)
            }
        }
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
            if (count != null) {
                Text("Shelf · $count stocks", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = dim)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.selectedSector,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.ExtraBold),
                    color = ink
                )
                if (pulse?.hot == true) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.LocalFireDepartment, contentDescription = "Trending sector", tint = Color(0xFFB25E00), modifier = Modifier.size(20.dp))
                }
            }
            if (pulse != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text("Rank this week", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = dim)
                        Text("#${pulse.rank}", style = passValueStyle(34), color = ink)
                        Text("of ${pulse.ofTotal}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = dim)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp, end = 12.dp, bottom = 26.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DashedRule(color = Color(0xFFB0B8C1), modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = dim, modifier = Modifier.size(20.dp))
                        DashedRule(color = Color(0xFFB0B8C1), modifier = Modifier.weight(1f))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Tracked by ETF", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = dim)
                        Text(pulse.etf, style = passValueStyle(34), color = ink)
                        Text("this week", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = dim)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFFD1D6DB))
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    SectorField("5 days", String.format(Locale.US, "%+.1f%%", pulse.r5Pct), signColor(pulse.r5Pct), Modifier.weight(1f))
                    SectorField("20 days", String.format(Locale.US, "%+.1f%%", pulse.r20Pct), signColor(pulse.r20Pct), Modifier.weight(1f))
                    if (pulse.volumeRatio > 0.0) {
                        SectorField("Volume", String.format(Locale.US, "%.1fx", pulse.volumeRatio), ink, Modifier.weight(1f))
                    }
                    if (pulse.newsTone != 0) {
                        SectorField("News tone", String.format(Locale.US, "%+d", pulse.newsTone), signColor(pulse.newsTone.toDouble()), Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                val stats = buildString {
                    append(
                        String.format(
                            Locale.US,
                            "#%d of %d this week · %+.1f%% in 5 days · %+.1f%% in 20",
                            pulse.rank, pulse.ofTotal, pulse.r5Pct, pulse.r20Pct
                        )
                    )
                    if (pulse.volumeRatio > 0.0) append(String.format(Locale.US, " · %.1fx volume", pulse.volumeRatio))
                    if (pulse.newsTone != 0) append(String.format(Locale.US, " · news tone %+d", pulse.newsTone))
                    append(" (${pulse.etf})")
                }
                Text(stats, style = MaterialTheme.typography.labelMedium, color = dim)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Sorted by the last 2 weeks' real move; the gold border marks the shelf's best performers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim
                )
            }
        }
        Perforation(ink = Color(0xFFC4CAD0))
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            // What the book already holds from this shelf — exact membership only.
            if (!state.book.isEmpty) {
                val held = state.sectorRows.filter { it.heldPct != null }
                val text = when {
                    held.isNotEmpty() -> "In your book from this shelf: " +
                        held.joinToString(", ") { String.format(Locale.US, "%s %.0f%%", it.symbol, it.heldPct) } +
                        String.format(Locale.US, " — %.0f%% of the book", held.sumOf { it.heldPct ?: 0.0 })
                    state.sectorRows.isNotEmpty() -> "Nothing from this shelf in your portfolio."
                    else -> null
                }
                if (text != null) {
                    PassNoteRow(
                        icon = Icons.Rounded.Work,
                        chip = Color(0xFFE1EBFB),
                        chipInk = Color(0xFF1B64DA),
                        label = "Your book",
                        text = text,
                        textColor = if (held.isNotEmpty()) Color(0xFF1B64DA) else dim
                    ) {
                        held.take(4).forEach { CompanyLogo(symbol = it.symbol, size = 28.dp, launchPad = false, modifier = Modifier.padding(start = 4.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            // The forward look: names pressing their highs on real volume.
            val (watchText, watchColor) = when {
                state.breakoutScanning -> "Scanning for next-week setups…" to dim
                state.breakouts.isNotEmpty() -> ("Next-week watch: " + state.breakouts.joinToString(", ") { it.symbol } +
                    " — pressing highs on volume.") to AurumColors.gainOnLight
                else -> "No name here passes the next-week breakout bar right now." to dim
            }
            PassNoteRow(
                icon = Icons.Rounded.RocketLaunch,
                chip = if (state.breakouts.isNotEmpty()) Color(0xFFD5F2E3) else Color(0xFFE5E8EB),
                chipInk = if (state.breakouts.isNotEmpty()) AurumColors.gainOnLight else dim,
                label = "Next-week watch",
                text = watchText,
                textColor = watchColor
            )
        }
    }
}

private fun signColor(v: Double): Color = if (v >= 0.0) AurumColors.gainOnLight else AurumColors.lossOnLight

@Composable
private fun SectorField(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AurumColors.inkDarkDim)
        Text(value, style = passValueStyle(17), color = color, maxLines = 1)
    }
}

@Composable
private fun PassNoteRow(
    icon: ImageVector,
    chip: Color,
    chipInk: Color,
    label: String,
    text: String,
    textColor: Color,
    trailing: @Composable () -> Unit = {}
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(chip),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = chipInk, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AurumColors.inkDarkDim)
            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = textColor)
        }
        trailing()
    }
}

/** A pass's tear line: a dashed rule with a bite out of each edge. */
@Composable
private fun Perforation(ink: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-13).dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(AurumColors.bg)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 13.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(AurumColors.bg)
        )
        DashedRule(
            color = ink,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 22.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun DashedRule(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(2.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        )
    }
}

/**
 * One stock from a sector or a search, as a ticket stub: the stock on the
 * left, its 2-week move and the watch star on the tear-off, and — when the
 * next-week scout picked it — the reason on a second stub below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockStub(
    row: BrowseRow,
    watched: Boolean,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
    onToggleWatch: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .fillMaxWidth()
            .then(if (row.top) Modifier.border(1.5.dp, AurumColors.gold, shape) else Modifier)
            .clip(shape)
            .background(AurumColors.surface)
            .clickable(onClick = onOpen)
    ) {
        Column {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompanyLogo(symbol = row.symbol, size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.symbol, style = passValueStyle(16), color = AurumColors.text, maxLines = 1)
                            if (row.name.isNotBlank()) {
                                Text(
                                    text = row.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.textDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (row.top || row.breakout != null || row.heldPct != null) {
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (row.top) StubPill("Top 2 weeks", AurumColors.gold)
                            if (row.breakout != null) StubPill("Next week", AurumColors.gain)
                            row.heldPct?.let { StubPill(String.format(Locale.US, "Held · %.0f%%", it), AurumColors.info) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sparkline(
                            data = row.spark,
                            modifier = Modifier
                                .width(96.dp)
                                .height(30.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            val quote = row.quote
                            if (quote != null) {
                                AnimatedMoney(
                                    value = quote.price,
                                    style = passValueStyle(15),
                                    baseColor = AurumColors.text,
                                    modifier = Modifier.widthIn(max = 110.dp)
                                )
                                Text(
                                    text = Fmt.signedPct(quote.dayChangePct) + " today",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = AurumColors.deltaColor(quote.dayChangePct)
                                )
                            } else {
                                Text("—", style = passValueStyle(15), color = AurumColors.textDim)
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 14.dp)
                        .width(2.dp)
                        .drawBehind {
                            drawLine(
                                color = AurumColors.surfaceTop,
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                            )
                        }
                )
                Column(
                    modifier = Modifier
                        .width(108.dp)
                        .padding(start = 10.dp, end = 4.dp, top = 14.dp, bottom = 8.dp)
                ) {
                    val twoWeek = row.twoWeekPct
                    Column(modifier = Modifier.weight(1f)) {
                        if (twoWeek != null) {
                            Text(Fmt.signedPct(twoWeek), style = passValueStyle(17), color = AurumColors.deltaColor(twoWeek), maxLines = 1)
                            Text("last 2 weeks", style = MaterialTheme.typography.labelSmall, color = AurumColors.textDim)
                        } else {
                            Text(
                                text = "2-week read needs more history",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.textDim
                            )
                        }
                    }
                    Row {
                        IconButton(onClick = onToggleWatch, modifier = Modifier.size(44.dp)) {
                            Icon(
                                imageVector = if (watched) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = if (watched) "Remove ${row.symbol} from watchlist" else "Add ${row.symbol} to watchlist",
                                tint = if (watched) AurumColors.gold else AurumColors.textDim,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        IconButton(onClick = onAnalyze, modifier = Modifier.size(44.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.QueryStats,
                                contentDescription = "Analyze ${row.symbol}",
                                tint = AurumColors.textSub,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            val breakout = row.breakout
            if (breakout != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    DashedRule(color = AurumColors.surfaceTop, modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth())
                    Row(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 13.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.RocketLaunch,
                            contentDescription = "Next-week breakout watch",
                            tint = AurumColors.gain,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(15.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(breakout.reason, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = AurumColors.gain)
                            if (breakout.newsNote.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("“${breakout.newsNote}”", style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim)
                            }
                        }
                    }
                }
            }
        }
        // The bites where the stub tears off.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-99).dp, y = (-10).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(AurumColors.bg)
        )
    }
}

@Composable
private fun StubPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = color, maxLines = 1)
    }
}

// ---------------------------------------------------------------- study

@Composable
private fun StudyPane(
    vm: StocksViewModel,
    state: StocksState,
    onOpenDetail: (String) -> Unit,
    onOpenAnalysis: (String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = state.studyQuery,
                onValueChange = vm::onStudyQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = stocksFieldColors(),
                placeholder = { Text("Ticker or company name — NVDA, Nvidia…", style = MaterialTheme.typography.bodyMedium) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = AurumColors.textDim) },
                trailingIcon = {
                    if (state.studyQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.onStudyQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = AurumColors.textDim)
                        }
                    }
                }
            )
        }
        if (state.studySuggestions.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(AurumColors.surface)
                ) {
                    state.studySuggestions.forEach { (symbol, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    keyboard?.hide()
                                    vm.selectStudy(symbol, name)
                                }
                                .padding(horizontal = 14.dp)
                                .height(62.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompanyLogo(symbol = symbol, size = 36.dp, launchPad = false)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(symbol, style = passValueStyle(15), color = AurumColors.text)
                                Text(name, style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("Study", style = passValueStyle(13), color = AurumColors.info)
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = AurumColors.info, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        when {
            state.studyLoading -> item {
                StudyNoteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AurumColors.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Reading two years of history, the 35-technique board, the headlines, and the balance sheet…",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
            state.study == null -> item {
                StudyNoteCard {
                    Text(
                        text = state.studyError.ifEmpty {
                            "Pick any US stock — by ticker or by company name — and the engine studies it end " +
                                "to end: trend, momentum against the market, the 35-technique board, volume, " +
                                "volatility, fundamentals, headlines, and a one-month projection built from the " +
                                "stock's own comparable months."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
            }
            else -> {
                val s = state.study
                if (state.studyError.isNotEmpty()) {
                    item {
                        StudyNoteCard {
                            Text(state.studyError + " Showing the previous study.", style = MaterialTheme.typography.bodySmall, color = AurumColors.loss)
                        }
                    }
                }
                item(key = "study-" + s.symbol) {
                    StudyMainPass(
                        s = s,
                        onOpen = { onOpenDetail(s.symbol) },
                        onAnalyze = { onOpenAnalysis(s.symbol) }
                    )
                }
                item(key = "subs-" + s.symbol) { StudySubPasses(s) }
            }
        }
    }
}

@Composable
private fun StudyNoteCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AurumColors.surface)
            .padding(16.dp),
        content = content
    )
}

private fun gradeColor(grade: Int?): Color = when {
    grade == null -> AurumColors.textDim
    grade >= 80 -> AurumColors.gain
    grade >= 60 -> AurumColors.accent
    grade >= 40 -> AurumColors.textDim
    else -> AurumColors.loss
}

/** The study's own pass: the stock on the band, its grade, and the road ahead below the tear line. */
@Composable
private fun StudyMainPass(s: StockStudy, onOpen: () -> Unit, onAnalyze: () -> Unit) {
    val paint = passPaint(rememberLogoTint(s.symbol))
    val band by animateColorAsState(paint.ground, tween(650), label = "studyBand")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(AurumColors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(band)
                .clickable(onClick = onOpen)
                .height(84.dp)
                .padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompanyLogo(symbol = s.symbol, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(s.symbol, style = passValueStyle(22), color = paint.ink)
                Text(s.name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = paint.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Fmt.money(s.price), style = passValueStyle(20), color = paint.ink)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Open ${s.symbol}", tint = paint.ink, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradeRing(grade = s.grade)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Grade", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = AurumColors.textDim)
                    Text(
                        text = s.grade?.let { "${it}/100 · ${s.gradeBand}" } ?: s.gradeBand,
                        style = passValueStyle(18),
                        color = gradeColor(s.grade)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(s.headline, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = AurumColors.text)
            if (s.newsNote.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AurumColors.surfaceHigh)
                        .padding(12.dp)
                ) {
                    Icon(Icons.Rounded.Newspaper, contentDescription = null, tint = AurumColors.textDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Freshest headline", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AurumColors.textDim)
                        Text(s.newsNote, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = AurumColors.text)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (s.projections.isNotEmpty()) {
            Perforation(ink = AurumColors.surfaceTop)
            RoadAhead(projections = s.projections, price = s.price)
        }
        Box(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth()
                .height(52.dp)
                .pressScale(onClick = onAnalyze)
                .clip(RoundedCornerShape(16.dp))
                .background(AurumColors.surfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QueryStats, contentDescription = null, tint = AurumColors.info, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Full 35-technique analysis of ${s.symbol}", style = passValueStyle(15), color = AurumColors.info)
            }
        }
    }
}

@Composable
private fun GradeRing(grade: Int?) {
    val reach = remember { Animatable(0f) }
    LaunchedEffect(grade) {
        reach.snapTo(0f)
        reach.animateTo((grade ?: 0) / 100f, tween(1_000, delayMillis = 150))
    }
    val color = gradeColor(grade)
    Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = AurumColors.surfaceHigh,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * reach.value,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(grade?.toString() ?: "—", style = passValueStyle(24), color = color)
            Text("of 100", style = MaterialTheme.typography.labelSmall, color = AurumColors.textDim)
        }
    }
}

/** "The road ahead": today to the chosen horizon's median, every stop on the route, and the measured range. */
@Composable
private fun RoadAhead(projections: List<PriceProjection>, price: Double) {
    // The chosen horizon survives new studies by label, not by index.
    var selLabel by rememberSaveable { mutableStateOf("1 month") }
    val p = projections.firstOrNull { it.label == selLabel } ?: projections.first()
    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "The road ahead — measured ranges",
                style = passValueStyle(17),
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            InfoDot(title = "The projection", explanation = Meanings.PROJECTION)
        }
        Spacer(Modifier.height(8.dp))
        // The route: today, then the chosen horizon's median.
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text("Today", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AurumColors.textDim)
                Text(Fmt.money(price), style = passValueStyle(22), color = AurumColors.text)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashedRule(color = AurumColors.textFaint, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = AurumColors.info, modifier = Modifier.size(18.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${p.label} median", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AurumColors.textDim)
                Text(Fmt.money(p.medianPrice), style = passValueStyle(22), color = AurumColors.deltaColor(p.medianPct))
            }
        }
        Spacer(Modifier.height(10.dp))
        // Every stop on the route; tapping one makes it the destination.
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 30.dp)
                    .padding(top = 31.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AurumColors.surfaceTop)
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                projections.forEach { proj ->
                    val on = proj.label == p.label
                    val dot by animateDpAsState(if (on) 14.dp else 10.dp, label = "stopDot")
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selLabel = proj.label }
                            .padding(vertical = 4.dp)
                            .semantics { contentDescription = "${proj.label}: median ${Fmt.money(proj.medianPrice)}" },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = proj.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = if (on) AurumColors.info else AurumColors.textDim,
                            maxLines = 1
                        )
                        Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(dot)
                                    .clip(CircleShape)
                                    .background(if (on) AurumColors.info else AurumColors.textFaint)
                            )
                        }
                        Text(
                            text = Fmt.money(proj.medianPrice),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (on) AurumColors.deltaColor(proj.medianPct) else AurumColors.textDim,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniTile("Median", Fmt.money(p.medianPrice) + "  " + Fmt.signedPct(p.medianPct), AurumColors.deltaColor(p.medianPct), Modifier.weight(1f))
            MiniTile(
                label = "Ended higher",
                value = if (p.volImplied) "—" else Fmt.pct(p.upSharePct),
                color = when {
                    p.volImplied -> AurumColors.textDim
                    p.upSharePct >= 50.0 -> AurumColors.gain
                    else -> AurumColors.loss
                },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        MiniTile("Middle half", "${Fmt.money(p.q1Price)}–${Fmt.money(p.q3Price)}", AurumColors.text, Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        RangeBar(p = p, price = price)
        Spacer(Modifier.height(10.dp))
        Text(
            text = String.format(
                Locale.US,
                if (p.volImplied) "The 9-in-10 band spans %s (%+.1f%%) to %s (%+.1f%%)."
                else "9 in 10 of those windows landed between %s (%+.1f%%) and %s (%+.1f%%).",
                Fmt.money(p.p10Price), p.p10Pct, Fmt.money(p.p90Price), p.p90Pct
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = AurumColors.text
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = (if (p.volImplied) "Basis: " + p.basis + "."
            else "Basis: ${p.analogCount} of this stock's own past ${p.label} windows — " + p.basis + ".") +
                (p.volSwingPct?.takeIf { !p.volImplied }?.let {
                    String.format(Locale.US, " Its recent volatility implies a typical ${p.label} swing of ±%.1f%%.", it)
                } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(14.dp))
        Text("Every horizon", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = AurumColors.textDim)
        Spacer(Modifier.height(4.dp))
        projections.forEach { proj ->
            val on = proj.label == p.label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) AurumColors.surfaceHigh else Color.Transparent)
                    .clickable { selLabel = proj.label }
                    .height(44.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = proj.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (on) AurumColors.info else AurumColors.textDim,
                    modifier = Modifier.widthIn(min = 72.dp)
                )
                Text(
                    text = "median ${Fmt.money(proj.medianPrice)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = AurumColors.deltaColor(proj.medianPct)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (proj.volImplied) "±${proj.volSwingPct ?: 0.0}% vol band"
                    else "${Fmt.signedPct(proj.medianPct)} · ${Fmt.pct(proj.upSharePct)} higher",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun MiniTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AurumColors.surfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AurumColors.textDim, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = passValueStyle(16), color = color, maxLines = 2)
    }
}

/** The p10–p90 track, the middle half, the median tick and today's price, labelled. */
@Composable
private fun RangeBar(p: PriceProjection, price: Double) {
    val measurer = rememberTextMeasurer()
    val gold = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, color = AurumColors.gold)
    val dim = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = AurumColors.textDim)
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            val lo = minOf(p.p10Price, price)
            val hi = maxOf(p.p90Price, price)
            val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
            val pad = 28.dp.toPx()
            fun x(v: Double): Float = pad + ((v - lo) / span).toFloat() * (size.width - pad * 2)
            val midY = 36.dp.toPx()
            fun label(text: String, style: TextStyle, cx: Float, top: Float) {
                val m = measurer.measure(text, style)
                val left = (cx - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
                drawText(m, topLeft = Offset(left, top))
            }
            // The 9-in-10 track.
            drawLine(AurumColors.textDim, Offset(x(p.p10Price), midY), Offset(x(p.p90Price), midY), 3.dp.toPx(), StrokeCap.Round)
            // The middle half.
            drawLine(AurumColors.accent, Offset(x(p.q1Price), midY), Offset(x(p.q3Price), midY), 14.dp.toPx(), StrokeCap.Round)
            // The median tick.
            drawLine(AurumColors.text, Offset(x(p.medianPrice), midY - 12.dp.toPx()), Offset(x(p.medianPrice), midY + 12.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
            // Where the price stands today.
            drawLine(AurumColors.gold, Offset(x(price), midY - 18.dp.toPx()), Offset(x(price), midY + 18.dp.toPx()), 2.dp.toPx())
            label("Today ${Fmt.money(price)}", gold, x(price), 0f)
            label(Fmt.money(p.p10Price), dim, x(p.p10Price), midY + 18.dp.toPx())
            label(Fmt.money(p.p90Price), dim, x(p.p90Price), midY + 18.dp.toPx())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(color = AurumColors.accent, w = 16.dp, h = 8.dp, text = "Middle half")
            Legend(color = AurumColors.text, w = 4.dp, h = 12.dp, text = "Median")
            Legend(color = AurumColors.textDim, w = 16.dp, h = 3.dp, text = "9 in 10")
            Legend(color = AurumColors.gold, w = 2.dp, h = 12.dp, text = "Today")
        }
    }
}

@Composable
private fun Legend(color: Color, w: Dp, h: Dp, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = w, height = h)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = AurumColors.textDim)
    }
}

/** The study's sections as passes in a stack, each opening in place. */
@Composable
private fun StudySubPasses(s: StockStudy) {
    val held = s.held
    val tiles = fundamentalTiles(s.fundamentals)
    var openPart by rememberSaveable(s.symbol) { mutableStateOf<String?>(null) }
    fun toggle(part: String) {
        openPart = if (openPart == part) null else part
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (held != null) {
            SubPass(
                title = "Your position — ${s.symbol}",
                summary = "${Fmt.qty(held.shares)} shares · open P/L ${Fmt.signedMoney(held.unrealizedPl)}",
                icon = Icons.Rounded.Work,
                open = openPart == "position",
                onToggle = { toggle("position") }
            ) { PositionPart(held, s.projections) }
        }
        val scored = s.factors.count { it.score != null }
        SubPass(
            title = "The graded evidence",
            summary = "${Fmt.pct(s.coveragePct)} measured · $scored of ${s.factors.size} factors scored",
            icon = Icons.Rounded.QueryStats,
            open = openPart == "evidence",
            onToggle = { toggle("evidence") }
        ) { EvidencePart(s) }
        val year = s.spans.lastOrNull()
        SubPass(
            title = "Performance & risk",
            summary = year?.stockPct?.let { stock ->
                "${year.label} ${Fmt.signedPct(stock)}" + (year.spyPct?.let { " · SPY ${Fmt.signedPct(it)}" } ?: "")
            } ?: (s.volatilityPct?.let { "Volatility ${Fmt.pct(it)} a year" } ?: "Returns against SPY and the risk"),
            icon = Icons.Rounded.Shield,
            open = openPart == "risk",
            onToggle = { toggle("risk") }
        ) { RiskPart(s) }
        if (tiles.isNotEmpty()) {
            SubPass(
                title = "Fundamentals",
                summary = "${tiles.size} figures" + (tiles.firstOrNull { it.first == "Market cap" }?.let { " · market cap ${it.second}" } ?: ""),
                icon = Icons.Rounded.PieChart,
                open = openPart == "fundamentals",
                onToggle = { toggle("fundamentals") }
            ) {
                TileGrid(tiles.map { Triple(it.first, it.second, AurumColors.text) })
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "As reported to Yahoo, refreshed daily. An absent figure stays absent.",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.textDim
                )
            }
        }
        if (s.analystNote.isNotEmpty()) {
            SubPass(
                title = "The street",
                summary = "External opinion, shown apart from ours",
                icon = Icons.Rounded.Newspaper,
                open = openPart == "street",
                onToggle = { toggle("street") }
            ) {
                Row {
                    Text("“", style = passValueStyle(36), color = AurumColors.gold)
                    Spacer(Modifier.width(10.dp))
                    Text(s.analystNote, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = AurumColors.text)
                }
            }
        }
        SubPass(
            title = if (s.notes.isEmpty()) "Caveat" else "Notes & caveat",
            summary = if (s.notes.isEmpty()) "What the study can and can't say" else "${s.notes.size} notes and the caveat",
            icon = Icons.Rounded.Info,
            open = openPart == "notes",
            onToggle = { toggle("notes") }
        ) {
            s.notes.forEach { note ->
                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AurumColors.info)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = AurumColors.text)
                }
            }
            Text(s.caveat, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = AurumColors.textSub)
        }
    }
}

@Composable
private fun SubPass(
    title: String,
    summary: String,
    icon: ImageVector,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevron by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.6f), label = "subChevron")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(AurumColors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .height(66.dp)
                .padding(start = 18.dp, end = 14.dp)
                .semantics { stateDescription = if (open) "Open" else "Closed" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AurumColors.surfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AurumColors.textSub, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = passValueStyle(15), color = AurumColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (open) "Close $title" else "Open $title",
                tint = AurumColors.textSub,
                modifier = Modifier.rotate(chevron)
            )
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), content = content)
        }
    }
}

@Composable
private fun PositionPart(h: HeldPosition, projections: List<PriceProjection>) {
    TileGrid(
        listOf(
            Triple("Shares", Fmt.qty(h.shares), AurumColors.text),
            Triple("Avg cost", Fmt.money(h.avgCost), AurumColors.text),
            Triple("Value now", Fmt.money(h.marketValue), AurumColors.accent),
            Triple("Open P/L", Fmt.signedMoney(h.unrealizedPl), AurumColors.deltaColor(h.unrealizedPl))
        )
    )
    if (projections.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your ${Fmt.qty(h.shares)} shares at the measured medians:",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = AurumColors.textDim
        )
        projections.forEach { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = AurumColors.textDim, modifier = Modifier.width(72.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(Fmt.money(h.shares * p.medianPrice), style = passValueStyle(15), color = AurumColors.deltaColor(p.medianPct))
                    Text(
                        text = "middle half ${Fmt.money(h.shares * p.q1Price)}–" + Fmt.money(h.shares * p.q3Price),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
            }
        }
        Text(
            text = "The ranges are the projection's own — your shares just ride them.",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun EvidencePart(s: StockStudy) {
    s.factors.forEachIndexed { i, f ->
        val color = when {
            f.score == null -> AurumColors.textDim
            f.score * 2 >= f.maxScore -> AurumColors.gain
            f.score * 4 >= f.maxScore -> AurumColors.accent
            else -> AurumColors.loss
        }
        val fill = remember(f.label, f.score) { Animatable(0f) }
        LaunchedEffect(f.label, f.score) {
            fill.animateTo(
                (f.score ?: 0).toFloat() / f.maxScore.coerceAtLeast(1),
                tween(800, delayMillis = 60 * i)
            )
        }
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.label, style = passValueStyle(14), color = AurumColors.text, modifier = Modifier.weight(1f))
                Text(f.score?.let { "$it/${f.maxScore}" } ?: "unmeasured", style = passValueStyle(14), color = color)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AurumColors.surfaceHigh)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fill.value)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(f.detail, style = MaterialTheme.typography.labelMedium, color = AurumColors.textDim)
        }
    }
}

@Composable
private fun RiskPart(s: StockStudy) {
    TileGrid(
        s.spans.map { span ->
            Triple(
                span.label + (span.spyPct?.let { " · SPY ${Fmt.signedPct(it)}" } ?: ""),
                span.stockPct?.let { Fmt.signedPct(it) } ?: "—",
                span.stockPct?.let { AurumColors.deltaColor(it) } ?: AurumColors.textDim
            )
        }
    )
    Spacer(Modifier.height(8.dp))
    TileGrid(
        listOf(
            Triple("Volatility (ann.)", s.volatilityPct?.let { Fmt.pct(it) } ?: "—", AurumColors.text),
            Triple("Beta vs SPY", s.beta?.let { String.format(Locale.US, "%.2f", it) } ?: "—", AurumColors.text),
            Triple("Max drawdown (1Y)", s.maxDrawdownPct?.let { Fmt.pct(it) } ?: "—", AurumColors.loss),
            Triple("Below 52w high", s.below52HighPct?.let { Fmt.pct(it) } ?: "—", AurumColors.text)
        )
    )
    if (s.boardTotal > 0) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${s.boardBullish} of ${s.boardTotal} deciding techniques read bullish.",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = when (s.boardVerdict) {
                TechniqueVerdict.BULLISH -> AurumColors.gain
                TechniqueVerdict.BEARISH -> AurumColors.loss
                TechniqueVerdict.NEUTRAL -> AurumColors.textDim
            }
        )
    }
}

/** Label, figure and colour in a two-across grid. */
@Composable
private fun TileGrid(tiles: List<Triple<String, String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value, color) ->
                    MiniTile(label = label, value = value, color = color, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun fundamentalTiles(f: Fundamentals?): List<Pair<String, String>> {
    if (f == null) return emptyList()
    return buildList {
        f.marketCap?.let { add("Market cap" to "$" + Fmt.compact(it)) }
        f.totalDebt?.let { add("Total debt" to "$" + Fmt.compact(it)) }
        f.totalCash?.let { add("Total cash" to "$" + Fmt.compact(it)) }
        f.debtToEquity?.let { add("Debt / equity" to Fmt.pct(it)) }
        f.profitMargins?.let { add("Profit margin" to Fmt.pct(it * 100.0)) }
        f.revenueGrowth?.let { add("Revenue growth" to Fmt.signedPct(it * 100.0)) }
        f.earningsGrowth?.let { add("Earnings growth" to Fmt.signedPct(it * 100.0)) }
        f.forwardPE?.let { add("Forward P/E" to String.format(Locale.US, "%.1f", it)) }
        f.trailingPE?.let { add("Trailing P/E" to String.format(Locale.US, "%.1f", it)) }
        f.priceToBook?.let { add("Price / book" to String.format(Locale.US, "%.1f", it)) }
        f.freeCashflow?.let { add("Free cash flow" to "$" + Fmt.compact(it)) }
        f.shortPctFloat?.let { add("Short % of float" to Fmt.pct(it * 100.0)) }
        f.dividendYield?.let { add("Dividend yield" to Fmt.pct(it * 100.0)) }
    }
}

@Composable
private fun stocksFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AurumColors.text,
    unfocusedTextColor = AurumColors.text,
    focusedContainerColor = AurumColors.surface,
    unfocusedContainerColor = AurumColors.surface,
    cursorColor = AurumColors.accent,
    focusedBorderColor = AurumColors.accent,
    unfocusedBorderColor = AurumColors.hairline,
    focusedLabelColor = AurumColors.accent,
    unfocusedLabelColor = AurumColors.textDim,
    focusedPlaceholderColor = AurumColors.textDim,
    unfocusedPlaceholderColor = AurumColors.textDim
)
