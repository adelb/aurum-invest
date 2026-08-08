package com.aurum.invest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.TechniqueAnalysis
import com.aurum.invest.analytics.TechniqueResult
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.BollingerDiagram
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.MaTrendDiagram
import com.aurum.invest.ui.components.MacdDiagram
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.RsiDiagram
import com.aurum.invest.ui.components.SupportResistanceDiagram
import com.aurum.invest.ui.theme.AurumColors

@Composable
fun AnalysisScreen(symbol: String, onBack: () -> Unit) {
    val vm: AnalysisViewModel = viewModel()
    LaunchedEffect(symbol) { vm.start(symbol) }
    val state by vm.state.collectAsStateWithLifecycle()

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
                    text = "5-technique analysis",
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
                        message = "This symbol needs at least 30 daily candles before the five techniques can read it."
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)
                ) {
                    item {
                        OutlookCard(analysis = analysis, price = state.price)
                        Spacer(Modifier.height(28.dp))
                    }
                    analysis.results.forEachIndexed { index, result ->
                        item {
                            TechniqueCard(result = result, analysis = analysis)
                            if (index < analysis.results.lastIndex) {
                                Spacer(Modifier.height(14.dp))
                            }
                        }
                    }
                }
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
private fun TechniqueCard(result: TechniqueResult, analysis: TechniqueAnalysis) {
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
        val diagramModifier = Modifier.fillMaxWidth().height(170.dp)
        when (result.key) {
            "ma" -> MaTrendDiagram(data = analysis.maData, modifier = diagramModifier)
            "rsi" -> RsiDiagram(data = analysis.rsiData, modifier = diagramModifier)
            "macd" -> MacdDiagram(data = analysis.macdData, modifier = diagramModifier)
            "bollinger" -> BollingerDiagram(data = analysis.bollingerData, modifier = diagramModifier)
            else -> SupportResistanceDiagram(data = analysis.srData, modifier = diagramModifier)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = result.summary,
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.textDim
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
