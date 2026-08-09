package com.aurum.invest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.BuyPlan
import com.aurum.invest.analytics.PlanTranche
import com.aurum.invest.analytics.TechniqueAnalysis
import com.aurum.invest.analytics.TechniqueDetail
import com.aurum.invest.analytics.TechniqueExplain
import com.aurum.invest.analytics.TechniqueResult
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AdxDiagram
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.BollingerDiagram
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.FibonacciDiagram
import com.aurum.invest.ui.components.FvgDiagram
import com.aurum.invest.ui.components.IchimokuDiagram
import com.aurum.invest.ui.components.MaTrendDiagram
import com.aurum.invest.ui.components.MacdDiagram
import com.aurum.invest.ui.components.ObvDiagram
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.PriceStyle
import com.aurum.invest.ui.components.RsiDiagram
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.components.StochasticDiagram
import com.aurum.invest.ui.components.SupportResistanceDiagram
import com.aurum.invest.ui.components.rememberDiagramViewport
import com.aurum.invest.ui.theme.AurumColors

/** Which of the two analysis views is showing. */
private enum class AnalysisTab { TECHNIQUES, PLAN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(symbol: String, onBack: () -> Unit) {
    val vm: AnalysisViewModel = viewModel()
    LaunchedEffect(symbol) { vm.start(symbol) }
    val state by vm.state.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(AnalysisTab.TECHNIQUES) }
    var priceStyle by remember { mutableStateOf(PriceStyle.CANDLES) }
    var sheetKey by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(AurumColors.bg)) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = AurumColors.text
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.symbol.ifEmpty { symbol.uppercase() },
                    style = MaterialTheme.typography.titleLarge,
                    color = AurumColors.text
                )
                Text(
                    text = if (tab == AnalysisTab.TECHNIQUES) "11-technique analysis"
                    else "$3,000 five-day plan",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            IconButton(onClick = vm::refresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = AurumColors.textDim
                )
            }
        }

        val analysis = state.analysis
        when {
            state.loading && analysis == null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AurumColors.gold)
                }
            }
            analysis == null -> {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    EmptyState(
                        title = "Not enough history",
                        message = "This symbol needs at least 30 daily candles before the techniques can read it."
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)
                ) {
                    item {
                        SegmentedToggle(
                            options = listOf("11 techniques", "$3,000 plan"),
                            selected = if (tab == AnalysisTab.TECHNIQUES) 0 else 1,
                            onSelect = { tab = if (it == 0) AnalysisTab.TECHNIQUES else AnalysisTab.PLAN }
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    if (tab == AnalysisTab.TECHNIQUES) {
                        item {
                            OutlookCard(analysis = analysis, price = state.price)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Chart style",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AurumColors.textDim,
                                    modifier = Modifier.weight(1f)
                                )
                                SegmentedToggle(
                                    options = listOf("Line", "Candles"),
                                    selected = if (priceStyle == PriceStyle.LINE) 0 else 1,
                                    onSelect = {
                                        priceStyle = if (it == 0) PriceStyle.LINE else PriceStyle.CANDLES
                                    },
                                    modifier = Modifier.width(170.dp),
                                    compact = true
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Tap a chart for its full analysis · hold and drag for the crosshair · pinch to zoom",
                                style = MaterialTheme.typography.labelSmall,
                                color = AurumColors.textDim
                            )
                            Spacer(Modifier.height(18.dp))
                        }
                        analysis.results.forEachIndexed { index, result ->
                            item {
                                TechniqueCard(
                                    result = result,
                                    analysis = analysis,
                                    style = priceStyle,
                                    onTapChart = { sheetKey = result.key }
                                )
                                if (index < analysis.results.lastIndex) {
                                    Spacer(Modifier.height(14.dp))
                                }
                            }
                        }
                    } else {
                        val plan = state.plan
                        if (plan == null) {
                            item {
                                EmptyState(
                                    title = "Plan unavailable",
                                    message = "A live price is needed to size the tranches. Pull to refresh once the market data loads."
                                )
                            }
                        } else {
                            planItems(plan)
                        }
                    }
                }
            }
        }
    }

    // Full technique write-up, opened by tapping a chart.
    val analysisNow = state.analysis
    val keyNow = sheetKey
    if (keyNow != null && analysisNow != null) {
        val detail = remember(keyNow, analysisNow) {
            TechniqueExplain.detail(
                analysisNow, keyNow,
                state.price ?: analysisNow.candles.last().close
            )
        }
        if (detail != null) {
            ModalBottomSheet(
                onDismissRequest = { sheetKey = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = AurumColors.surface
            ) {
                TechniqueDetailSheet(detail)
            }
        }
    }
}

/** Flat segmented toggle — gold fill marks the selected option. */
@Composable
private fun SegmentedToggle(
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (sel) AurumColors.gold else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = if (compact) 6.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) AurumColors.bg else AurumColors.textDim
                )
            }
        }
    }
}

@Composable
private fun OutlookCard(analysis: TechniqueAnalysis, price: Double?) {
    val outlook = analysis.outlook
    AurumCard {
        Text(
            text = outlook.headline,
            style = MaterialTheme.typography.titleMedium,
            color = AurumColors.text
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillTag(text = "${outlook.bullishCount} bullish", color = AurumColors.gain)
            Spacer(Modifier.width(8.dp))
            PillTag(text = "${outlook.bearishCount} bearish", color = AurumColors.loss)
            Spacer(Modifier.width(8.dp))
            PillTag(text = "${outlook.neutralCount} neutral", color = AurumColors.textDim)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${outlook.confidence}% agree",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Next 5 days",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        RangeBar(
            low = outlook.expectedLow,
            high = outlook.expectedHigh,
            price = price ?: analysis.maData.closes.lastOrNull() ?: outlook.expectedLow
        )
        Spacer(Modifier.height(14.dp))
        outlook.summary.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.gold
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text
                )
            }
        }
    }
}

/** Thin flat bar from expectedLow to expectedHigh with a gold dot at the current price. */
@Composable
private fun RangeBar(low: Double, high: Double, price: Double) {
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            val trackY = size.height / 2f
            drawLine(
                color = AurumColors.hairline,
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = 6f
            )
            val span = (high - low).takeIf { it > 1e-9 } ?: 1.0
            val frac = ((price - low) / span).coerceIn(0.0, 1.0).toFloat()
            drawCircle(
                color = AurumColors.gold,
                radius = 7f,
                center = Offset(frac * size.width, trackY)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = Fmt.money(low),
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.loss
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = Fmt.money(price),
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gold
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = Fmt.money(high),
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.gain
            )
        }
    }
}

@Composable
private fun TechniqueCard(
    result: TechniqueResult,
    analysis: TechniqueAnalysis,
    style: PriceStyle,
    onTapChart: () -> Unit
) {
    val viewport = rememberDiagramViewport(analysis.timestamps.size)
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            VerdictPill(verdict = result.verdict)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Strength ${result.strength}",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(14.dp))
        val m = Modifier.fillMaxWidth()
        val ts = analysis.timestamps
        val ohlc = analysis.candles
        when (result.key) {
            "ma" -> MaTrendDiagram(analysis.maData, ts, viewport, m, ohlc, style, onTapChart)
            "rsi" -> RsiDiagram(analysis.rsiData, ts, viewport, m, onTapChart)
            "macd" -> MacdDiagram(analysis.macdData, ts, viewport, m, onTapChart)
            "bollinger" -> BollingerDiagram(analysis.bollingerData, ts, viewport, m, ohlc, style, onTapChart)
            "sr" -> SupportResistanceDiagram(analysis.srData, ts, viewport, m, ohlc, style, onTapChart)
            "fvg" -> FvgDiagram(analysis.fvgData, ts, viewport, m, ohlc, style, onTapChart)
            "fib" -> FibonacciDiagram(analysis.fibData, ts, viewport, m, ohlc, style, onTapChart)
            "ichimoku" -> IchimokuDiagram(analysis.ichimokuData, ts, viewport, m, ohlc, style, onTapChart)
            "stoch" -> StochasticDiagram(analysis.stochData, ts, viewport, m, onTapChart)
            "obv" -> ObvDiagram(analysis.obvData, analysis.maData.closes, ts, viewport, m, onTapChart)
            else -> AdxDiagram(analysis.adxData, ts, viewport, m, onTapChart)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = result.summary,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tap the chart for the full analysis",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.gold.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun VerdictPill(verdict: TechniqueVerdict) {
    when (verdict) {
        TechniqueVerdict.BULLISH -> PillTag(text = "Bullish", color = AurumColors.gain)
        TechniqueVerdict.BEARISH -> PillTag(text = "Bearish", color = AurumColors.loss)
        TechniqueVerdict.NEUTRAL -> PillTag(text = "Neutral", color = AurumColors.textDim)
    }
}

// ---------------------------------------------------------------- detail sheet

@Composable
private fun TechniqueDetailSheet(detail: TechniqueDetail) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleLarge,
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            VerdictPill(verdict = detail.verdict)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Signal strength ${detail.strength} of 100",
            style = MaterialTheme.typography.labelMedium,
            color = AurumColors.textDim
        )

        SheetSection("What this technique is")
        Text(
            text = detail.whatItIs,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.text
        )

        SheetSection("What is drawn on the chart")
        detail.drawn.forEach { SheetBullet(it) }

        SheetSection("Current reading")
        detail.reading.forEach { SheetBullet(it) }

        if (detail.levels.isNotEmpty()) {
            SheetSection("Levels to watch")
            detail.levels.forEach { (label, value) ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.textDim,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.text
                    )
                }
            }
        }

        SheetSection("Playbook")
        detail.playbook.forEach { SheetBullet(it) }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Computed from past prices only; not financial advice.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
        )
    }
}

@Composable
private fun SheetSection(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = AurumColors.gold
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SheetBullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "•  ",
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.gold
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.text
        )
    }
}

// ---------------------------------------------------------------- plan view

private fun androidx.compose.foundation.lazy.LazyListScope.planItems(plan: BuyPlan) {
    item {
        AurumCard {
            Text(
                text = plan.posture,
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.text
            )
            Spacer(Modifier.height(10.dp))
            plan.postureDetail.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.text,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = plan.trendNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(14.dp))
    }

    item {
        AurumCard {
            Text(
                text = "If every tranche fills",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Budget",
                    value = Fmt.money(plan.budget),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Avg entry",
                    value = Fmt.money(plan.avgEntry),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Shares",
                    value = Fmt.qty(plan.totalShares),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    plan.tranches.forEachIndexed { i, tranche ->
        item {
            TrancheCard(index = i + 1, tranche = tranche)
            Spacer(Modifier.height(14.dp))
        }
    }

    item {
        AurumCard {
            Text(
                text = "Risk control",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Stop loss",
                    value = Fmt.money(plan.stop),
                    modifier = Modifier.weight(1f),
                    valueColor = AurumColors.loss
                )
                StatTile(
                    label = "At risk",
                    value = Fmt.money(plan.riskDollars),
                    modifier = Modifier.weight(1f),
                    valueColor = AurumColors.loss
                )
                StatTile(
                    label = "Of budget",
                    value = Fmt.pct(plan.riskPct),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = plan.stopBasis,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "First target",
                    value = Fmt.money(plan.firstTarget),
                    modifier = Modifier.weight(1f),
                    valueColor = AurumColors.gain
                )
                StatTile(
                    label = "Stretch (5R)",
                    value = Fmt.money(plan.stretchTarget),
                    modifier = Modifier.weight(1f),
                    valueColor = AurumColors.gain
                )
                StatTile(
                    label = "R/R to first",
                    value = "${plan.rewardRisk} : 1",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = plan.firstTargetNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = plan.stretchTargetNote,
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(14.dp))
    }

    item {
        AurumCard {
            Text(
                text = "Day by day",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            Spacer(Modifier.height(6.dp))
            plan.schedule.forEach { day ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "D${day.day}",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurumColors.gold,
                        modifier = Modifier.width(36.dp)
                    )
                    Column {
                        Text(
                            text = day.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = AurumColors.text
                        )
                        day.actions.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurumColors.textDim,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    item {
        AurumCard {
            Text(
                text = "What the pros this plan borrows from would say",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim
            )
            plan.principles.forEach { (who, what) ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = who,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.gold
                )
                Text(
                    text = what,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.text,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    item {
        Text(
            text = plan.caveat,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun TrancheCard(index: Int, tranche: PlanTranche) {
    AurumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Tranche $index — ${tranche.label}",
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text,
                modifier = Modifier.weight(1f)
            )
            PillTag(text = tranche.day, color = AurumColors.gold)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Fmt.money(tranche.amount),
                style = MaterialTheme.typography.titleMedium,
                color = AurumColors.gold
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (tranche.shares > 0.0)
                    "≈ ${Fmt.qty(tranche.shares)} shares at ${Fmt.money(tranche.price)}"
                else
                    "held in cash",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.textDim
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = tranche.condition,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
    }
}
