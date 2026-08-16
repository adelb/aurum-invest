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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import com.aurum.invest.analytics.BuyPlan
import com.aurum.invest.analytics.PlanTranche
import com.aurum.invest.analytics.StochasticData
import com.aurum.invest.analytics.SupportResistanceData
import com.aurum.invest.analytics.TechniqueAnalysis
import com.aurum.invest.analytics.TechniqueDetail
import com.aurum.invest.analytics.TechniqueEvaluation
import com.aurum.invest.analytics.TechniqueEvaluator
import com.aurum.invest.analytics.TechniqueExplain
import com.aurum.invest.analytics.TechniqueResult
import com.aurum.invest.analytics.TechniqueScore
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AdxDiagram
import com.aurum.invest.ui.components.AroonDiagram
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.BollingerDiagram
import com.aurum.invest.ui.components.CciDiagram
import com.aurum.invest.ui.components.CmfDiagram
import com.aurum.invest.ui.components.DonchianDiagram
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.FibonacciDiagram
import com.aurum.invest.ui.components.FvgDiagram
import com.aurum.invest.ui.components.GoldenCrossDiagram
import com.aurum.invest.ui.components.IchimokuDiagram
import com.aurum.invest.ui.components.KeltnerDiagram
import com.aurum.invest.ui.components.MaTrendDiagram
import com.aurum.invest.ui.components.MacdDiagram
import com.aurum.invest.ui.components.MfiDiagram
import com.aurum.invest.ui.components.ObvDiagram
import com.aurum.invest.ui.components.OscillatorDiagram
import com.aurum.invest.ui.components.OverlayDiagram
import com.aurum.invest.ui.components.OverlaySeries
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.PriceStyle
import com.aurum.invest.ui.components.PsarDiagram
import com.aurum.invest.ui.components.RsiDiagram
import com.aurum.invest.ui.components.SegmentedToggle
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.components.StochasticDiagram
import com.aurum.invest.ui.components.SupportResistanceDiagram
import com.aurum.invest.ui.components.TwoLineDiagram
import com.aurum.invest.ui.components.WilliamsRDiagram
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

    var tab by rememberSaveable { mutableStateOf(AnalysisTab.TECHNIQUES) }
    var priceStyle by rememberSaveable { mutableStateOf(PriceStyle.CANDLES) }
    var sheetKey by remember { mutableStateOf<String?>(null) }
    var showAllTechniques by rememberSaveable { mutableStateOf(false) }

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
                val techCount = state.analysis?.results?.size
                Text(
                    text = if (tab == AnalysisTab.TECHNIQUES) {
                        if (techCount != null) "$techCount-technique analysis" else "Technique analysis"
                    } else "$3,000 five-day plan",
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
                    // "Couldn't load" and "listed too recently" are different
                    // diagnoses — show the one that actually happened.
                    if (state.historyStatus == com.aurum.invest.data.model.FeedStatus.FAILED) {
                        EmptyState(
                            title = "Couldn't load price history",
                            message = "The data source was unreachable, so nothing can be " +
                                "analyzed. Check your connection and pull to retry."
                        )
                    } else {
                        EmptyState(
                            title = "Not enough history",
                            message = "This symbol's verified history has fewer than 30 daily " +
                                "candles — the techniques can't read it yet."
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)
                ) {
                    item {
                        SegmentedToggle(
                            options = listOf("${analysis.results.size} techniques", "$3,000 plan"),
                            selected = if (tab == AnalysisTab.TECHNIQUES) 0 else 1,
                            onSelect = { tab = if (it == 0) AnalysisTab.TECHNIQUES else AnalysisTab.PLAN }
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    if (tab == AnalysisTab.TECHNIQUES) {
                        item {
                            OutlookCard(analysis = analysis, price = state.price)
                            Spacer(Modifier.height(14.dp))
                            AccuracyCard(
                                evaluation = state.evaluation,
                                loading = state.evaluationLoading
                            )
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
                        // Cards ordered by each technique's MEASURED 1-year rank
                        // on this stock; the top 20 lead, the rest fold away.
                        val ranking = state.evaluation?.rankByKey()
                        val ordered =
                            if (ranking != null) {
                                analysis.results.sortedBy { ranking[it.key] ?: Int.MAX_VALUE }
                            } else analysis.results
                        val visible =
                            if (ranking != null && !showAllTechniques) {
                                ordered.take(TechniqueEvaluator.TOP_TECHNIQUES)
                            } else ordered
                        visible.forEachIndexed { index, result ->
                            item {
                                TechniqueCard(
                                    result = result,
                                    analysis = analysis,
                                    style = priceStyle,
                                    score = state.evaluation?.scores?.firstOrNull { it.key == result.key },
                                    rank = ranking?.get(result.key),
                                    onTapChart = { sheetKey = result.key }
                                )
                                if (index < visible.lastIndex) {
                                    Spacer(Modifier.height(14.dp))
                                }
                            }
                        }
                        if (ranking != null && analysis.results.size > TechniqueEvaluator.TOP_TECHNIQUES) {
                            item {
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showAllTechniques = !showAllTechniques }
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = if (showAllTechniques) {
                                            "Show the top ${TechniqueEvaluator.TOP_TECHNIQUES} only"
                                        } else {
                                            "Show all ${analysis.results.size} techniques — " +
                                                "${analysis.results.size - TechniqueEvaluator.TOP_TECHNIQUES} more, ranked lower on this stock"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AurumColors.gold
                                    )
                                }
                            }
                        }
                    } else {
                        val plan = state.plan
                        if (plan == null) {
                            item {
                                EmptyState(
                                    title = "Plan unavailable",
                                    message = "A live price is needed to size the tranches.",
                                    actionLabel = "Reload data",
                                    onAction = vm::refresh
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

@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillTag(text = "${outlook.bullishCount} bullish", color = AurumColors.gain)
            PillTag(text = "${outlook.bearishCount} bearish", color = AurumColors.loss)
            PillTag(text = "${outlook.neutralCount} neutral", color = AurumColors.textDim)
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = "ATR-projected range — a volatility formula, not a calibrated forecast " +
                "interval. \"% agree\" is indicator agreement among 35 correlated " +
                "techniques, not a probability.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.textDim
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
    score: TechniqueScore?,
    rank: Int?,
    onTapChart: () -> Unit
) {
    val viewport = rememberDiagramViewport(analysis.timestamps.size)
    val trusted = score?.trusted == true
    // The gold border is EARNED: only a technique that actually called this
    // stock's 5-day moves right over the last year wears it.
    val cardModifier =
        if (trusted) Modifier.border(1.5.dp, AurumColors.gold, RoundedCornerShape(16.dp))
        else Modifier
    AurumCard(modifier = cardModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) {
                Text(
                    text = "%02d".format(rank),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (rank <= TechniqueEvaluator.TOP_TECHNIQUES) AurumColors.gold
                    else AurumColors.textDim
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = result.name,
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (trusted) {
                PillTag(text = "Trusted", color = AurumColors.gold)
                Spacer(Modifier.width(8.dp))
            }
            VerdictPill(verdict = result.verdict)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Strength ${result.strength}",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
            "adx" -> AdxDiagram(analysis.adxData, ts, viewport, m, onTapChart)
            "donchian" -> DonchianDiagram(analysis.donchianData, ts, viewport, m, ohlc, style, onTapChart)
            "psar" -> PsarDiagram(analysis.psarData, ts, viewport, m, ohlc, style, onTapChart)
            "mfi" -> MfiDiagram(analysis.mfiData, ts, viewport, m, onTapChart)
            "willr" -> WilliamsRDiagram(analysis.willrData, ts, viewport, m, onTapChart)
            "cci" -> CciDiagram(analysis.cciData, ts, viewport, m, onTapChart)
            "keltner" -> KeltnerDiagram(analysis.keltnerData, ts, viewport, m, ohlc, style, onTapChart)
            "cmf" -> CmfDiagram(analysis.cmfData, ts, viewport, m, onTapChart)
            "aroon" -> AroonDiagram(analysis.aroonData, ts, viewport, m, onTapChart)
            "stochrsi" -> StochasticDiagram(
                StochasticData(analysis.stochRsiData.k, analysis.stochRsiData.d),
                ts, viewport, m, onTapChart
            )
            "roc" -> OscillatorDiagram(
                analysis.rocData.roc, "ROC", ts, viewport, m,
                zeroLine = true, onTap = onTapChart
            )
            "trix" -> TwoLineDiagram(
                analysis.trixData.trix, analysis.trixData.signal, "TRIX", "Signal",
                ts, viewport, m, zeroLine = true, onTap = onTapChart
            )
            "uo" -> OscillatorDiagram(
                analysis.uoData.uo, "UO", ts, viewport, m,
                upper = 70.0, lower = 30.0, fixedMin = 0.0, fixedMax = 100.0, onTap = onTapChart
            )
            "vortex" -> TwoLineDiagram(
                analysis.vortexData.plus, analysis.vortexData.minus, "VI+", "VI-",
                ts, viewport, m, aColor = AurumColors.gain, bColor = AurumColors.loss,
                refLevel = 1.0, onTap = onTapChart
            )
            "efi" -> OscillatorDiagram(
                analysis.forceData.force, "Force", ts, viewport, m,
                zeroLine = true, compact = true, onTap = onTapChart
            )
            "cmo" -> OscillatorDiagram(
                analysis.cmoData.cmo, "CMO", ts, viewport, m,
                upper = 50.0, lower = -50.0, fixedMin = -100.0, fixedMax = 100.0,
                zeroLine = true, onTap = onTapChart
            )
            "dpo" -> OscillatorDiagram(
                analysis.dpoData.dpo, "DPO", ts, viewport, m,
                zeroLine = true, onTap = onTapChart
            )
            "kst" -> TwoLineDiagram(
                analysis.kstData.kst, analysis.kstData.signal, "KST", "Signal",
                ts, viewport, m, zeroLine = true, onTap = onTapChart
            )
            "hull" -> OverlayDiagram(
                analysis.hullData.closes,
                listOf(OverlaySeries("HMA 20", AurumColors.gold, analysis.hullData.hull)),
                ts, viewport, m, ohlc, style, onTapChart
            )
            "supertrend" -> OverlayDiagram(
                analysis.supertrendData.closes,
                listOf(
                    OverlaySeries(
                        "Uptrend line", AurumColors.gain,
                        analysis.supertrendData.line.mapIndexed { i, v ->
                            if (analysis.supertrendData.bullish.getOrNull(i) == true) v else null
                        }
                    ),
                    OverlaySeries(
                        "Downtrend line", AurumColors.loss,
                        analysis.supertrendData.line.mapIndexed { i, v ->
                            if (analysis.supertrendData.bullish.getOrNull(i) == false) v else null
                        }
                    )
                ),
                ts, viewport, m, ohlc, style, onTapChart
            )
            "chandelier" -> OverlayDiagram(
                analysis.chandelierData.closes,
                listOf(
                    OverlaySeries("Long exit", AurumColors.gain, analysis.chandelierData.longStop, dashed = true),
                    OverlaySeries("Short exit", AurumColors.loss, analysis.chandelierData.shortStop, dashed = true)
                ),
                ts, viewport, m, ohlc, style, onTapChart
            )
            "vwap" -> OverlayDiagram(
                analysis.vwapData.closes,
                listOf(OverlaySeries("VWAP 20", AurumColors.gold, analysis.vwapData.vwap)),
                ts, viewport, m, ohlc, style, onTapChart
            )
            "ad" -> ObvDiagram(
                com.aurum.invest.analytics.ObvData(analysis.adData.ad),
                analysis.maData.closes, ts, viewport, m, onTapChart, label = "A/D"
            )
            "pivot" -> SupportResistanceDiagram(
                analysis.pivotData.let { pd ->
                    val price = analysis.maData.closes.lastOrNull() ?: 0.0
                    val levels = if (pd.valid) {
                        listOf(pd.s2, pd.s1, pd.pivot, pd.r1, pd.r2)
                    } else emptyList()
                    SupportResistanceData(
                        closes = pd.closes,
                        supports = levels.filter { it in 0.0..price },
                        resistances = levels.filter { it > price }
                    )
                },
                ts, viewport, m, ohlc, style, onTapChart
            )
            else -> GoldenCrossDiagram(analysis.gcData, ts, viewport, m, ohlc, style, onTapChart)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = result.summary,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
        )
        if (score != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = accuracyLine(score),
                style = MaterialTheme.typography.labelSmall,
                color = if (trusted) AurumColors.gold else AurumColors.textDim
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tap the chart for the full analysis",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.gold.copy(alpha = 0.8f)
        )
    }
}

/** One honest sentence on a technique's measured 1-year record. */
private fun accuracyLine(score: TechniqueScore): String = when {
    score.signals == 0 ->
        "Last 12 months: no directional calls on this stock — nothing to grade."
    score.independentSignals < TechniqueEvaluator.MIN_INDEPENDENT_SIGNALS ->
        "Last 12 months: ${score.hits} of ${score.signals} daily calls right — but only " +
            "${score.independentSignals} independent (non-overlapping) samples, too few to " +
            "grade for trust."
    else ->
        "Last 12 months: ${score.independentHits} of ${score.independentSignals} independent " +
            "calls right (${score.independentHitRate}%, 95% CI ${score.ciLowPct}–" +
            "${score.ciHighPct}%) vs a ${score.baseRatePct}% base rate on this stock."
}

/**
 * The integrity engine's verdict: which techniques have actually called this
 * stock's moves over the last year. Trusted names wear the gold border below,
 * and the card order follows this measured ranking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccuracyCard(evaluation: TechniqueEvaluation?, loading: Boolean) {
    AurumCard {
        Text(
            text = "Technique integrity · last 12 months",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.text
        )
        Spacer(Modifier.height(8.dp))
        when {
            evaluation == null && loading -> {
                Text(
                    text = "Replaying the last year of sessions and grading every technique " +
                        "against the real 5-day moves that followed…",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            evaluation == null -> {
                Text(
                    text = "A complete 12-month grade needs at least " +
                        "${TechniqueEvaluator.MIN_CANDLES_FOR_FULL_REPLAY} daily candles: " +
                        "30 for indicator warm-up, 252 graded sessions, and 5 forward sessions. " +
                        "The board remains unranked rather than presenting a shorter sample as a year.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.textDim
                )
            }
            else -> {
                val trusted = evaluation.scores.filter { it.trusted }
                    .sortedByDescending { it.independentHitRate }
                if (trusted.isEmpty()) {
                    Text(
                        text = "No technique cleared the trust bar on this stock: at least " +
                            "${TechniqueEvaluator.MIN_INDEPENDENT_SIGNALS} independent " +
                            "(non-overlapping) calls, a ${TechniqueEvaluator.TRUST_HIT_RATE}%+ " +
                            "hit rate, and an edge of " +
                            "${TechniqueEvaluator.TRUST_EDGE_OVER_BASE}+ points over the " +
                            "stock's own drift. That is the honest answer — no border is " +
                            "painted gold without a real track record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        trusted.forEach { s ->
                            PillTag(
                                text = "${s.name} · ${s.independentHitRate}% vs ${s.baseRatePct}% base",
                                color = AurumColors.gold
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "These techniques beat this stock's own base rate by at least " +
                            "${TechniqueEvaluator.TRUST_EDGE_OVER_BASE} points on independent " +
                            "5-day windows — their cards wear the gold border, and the 5-day " +
                            "outlook weights every vote by this measured record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Record window: " +
                        (if (evaluation.fromTs > 0L) {
                            "${Fmt.dateShort(evaluation.fromTs)} – ${Fmt.dateShort(evaluation.toTs)}, "
                        } else "") +
                        "${evaluation.daysEvaluated} replayed sessions " +
                        "(~${evaluation.daysEvaluated / evaluation.horizonDays} independent " +
                        "windows). The full ${evaluation.scores.size}-technique board is ranked " +
                        "by this record; the ${TechniqueEvaluator.TOP_TECHNIQUES} strongest on " +
                        "THIS stock lead the list below, the rest fold away. A call counts as " +
                        "right when the stock then moved at least " +
                        "${TechniqueEvaluator.MOVE_DEADBAND_PCT}% in the called direction " +
                        "within ${evaluation.horizonDays} trading days. This replay is " +
                        "in-sample history on one stock — measured, not a promise, and not a " +
                        "calibrated probability.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
        }
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
            if (plan.budgetBasis.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = plan.budgetBasis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (plan.accountEquity != null) AurumColors.textDim else AurumColors.gold
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
                    label = if (plan.accountEquity != null) "Of account" else "Of budget",
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
