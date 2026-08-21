package com.aurum.invest.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.invest.analytics.BoardAction
import com.aurum.invest.analytics.HoldingBoardReview
import com.aurum.invest.analytics.PortfolioBoardEngine
import com.aurum.invest.analytics.PortfolioBoardReview
import com.aurum.invest.analytics.TechniqueVerdict
import com.aurum.invest.core.Fmt
import com.aurum.invest.ui.components.AurumCard
import com.aurum.invest.ui.components.EmptyState
import com.aurum.invest.ui.components.PillTag
import com.aurum.invest.ui.components.StatTile
import com.aurum.invest.ui.theme.AurumColors
import java.util.Locale

/**
 * The portfolio review: every open holding through the 35-technique board —
 * re-voted with its measured 1-year record as each grade lands — merged with
 * the sell-side stops and targets, and ranked into one to-do list.
 */
@Composable
fun PortfolioReviewScreen(onBack: () -> Unit, onOpenAnalysis: (String) -> Unit) {
    val vm: PortfolioReviewViewModel = viewModel()
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
                    text = "Portfolio review",
                    style = MaterialTheme.typography.titleLarge,
                    color = AurumColors.text
                )
                Text(
                    text = "every holding through the 35-technique board",
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

        val review = state.review
        when {
            state.loading && review == null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AurumColors.gold)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Reading the whole book…",
                            style = MaterialTheme.typography.bodySmall,
                            color = AurumColors.textDim
                        )
                    }
                }
            }
            review == null -> {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    EmptyState(
                        title = "The review couldn't run",
                        message = "The ledger or the market data was unreachable. " +
                            "Tap refresh to retry.",
                        actionLabel = "Retry",
                        onAction = vm::refresh
                    )
                }
            }
            review.holdings.isEmpty() -> {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    EmptyState(
                        title = "No open positions",
                        message = "The board reads holdings — add a position and the " +
                            "review fills itself."
                    )
                }
            }
            else -> ReviewList(state, review, onOpenAnalysis)
        }
    }
}

@Composable
private fun ReviewList(
    state: ReviewScreenState,
    review: PortfolioBoardReview,
    onOpenAnalysis: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)
    ) {
        item {
            RollupCard(review)
            Spacer(Modifier.height(14.dp))
        }
        if (state.grading != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = AurumColors.gold,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Grading each stock's own 1-year record — " +
                            "${state.graded} of ${state.total} done, ${state.grading} now",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.textDim
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        item {
            ActionsCard(review)
            Spacer(Modifier.height(14.dp))
        }
        review.holdings.forEach { holding ->
            item {
                HoldingReviewCard(holding, onOpen = { onOpenAnalysis(holding.symbol) })
                Spacer(Modifier.height(14.dp))
            }
        }
        if (review.notes.isNotEmpty()) {
            item {
                for (note in review.notes) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.textDim
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        item {
            Text(
                text = "Every verdict is the measured board's, every stop the advice " +
                    "engine's — decision support, not financial advice.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }
    }
}

// ------------------------------------------------------------ the rollup

@Composable
private fun RollupCard(review: PortfolioBoardReview) {
    AurumCard {
        Text(
            text = "The book on the board",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = review.boardTempPct?.toString() ?: "—",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = when {
                    review.boardTempPct == null -> AurumColors.textDim
                    review.boardTempPct >= 60 -> AurumColors.gain
                    review.boardTempPct <= 40 -> AurumColors.loss
                    else -> AurumColors.gold
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (review.boardTempPct != null) {
                    "/ 100 board temperature\nvalue-weighted bullish share of deciding techniques"
                } else {
                    "board temperature withheld\nunder ${
                        String.format(Locale.US, "%.0f", PortfolioBoardEngine.MIN_TEMP_COVERAGE_PCT)
                    }% of the book's value is measured"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = review.headline,
            style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.text
        )
        Spacer(Modifier.height(12.dp))
        Row {
            StatTile(
                label = "Bullish weight",
                value = String.format(Locale.US, "%.0f%%", review.bullishWeightPct),
                valueColor = AurumColors.gain,
                modifier = Modifier.weight(1f),
                info = "Bullish weight" to "Share of the book's live value sitting in " +
                    "holdings whose board reads bullish right now."
            )
            StatTile(
                label = "Bearish weight",
                value = String.format(Locale.US, "%.0f%%", review.bearishWeightPct),
                valueColor = AurumColors.loss,
                modifier = Modifier.weight(1f),
                info = "Bearish weight" to "Share of the book's live value sitting in " +
                    "holdings whose board reads bearish right now."
            )
            StatTile(
                label = "Measured",
                value = String.format(Locale.US, "%.0f%%", review.measuredWeightPct),
                modifier = Modifier.weight(1f),
                info = "Measured weight" to "Share of the book's value whose 35-technique " +
                    "board could actually be read this run. The rest is reported " +
                    "unmeasured, never guessed."
            )
        }
    }
}

// ------------------------------------------------------------ the to-do list

@Composable
private fun ActionsCard(review: PortfolioBoardReview) {
    AurumCard {
        Text(
            text = "What to do now",
            style = MaterialTheme.typography.titleSmall,
            color = AurumColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        if (review.actions.isEmpty()) {
            Text(
                text = "Nothing demands money to move today — every holding sits inside " +
                    "its stop and no exit signal fired.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurumColors.text
            )
        } else {
            review.actions.forEachIndexed { i, action ->
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "${i + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.gold,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(
                        text = action,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurumColors.text,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ one holding

@Composable
private fun HoldingReviewCard(h: HoldingBoardReview, onOpen: () -> Unit) {
    AurumCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = h.symbol,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = AurumColors.text
                )
                Text(
                    text = String.format(
                        Locale.US, "%s · %.0f%% of book",
                        Fmt.money(h.marketValue), h.weightPct
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            h.plPct?.let { pl ->
                Text(
                    text = Fmt.signedPct(pl),
                    style = MaterialTheme.typography.titleSmall,
                    color = AurumColors.deltaColor(pl)
                )
                Spacer(Modifier.width(10.dp))
            }
            ActionPill(h.action)
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = AurumColors.hairline, thickness = 1.dp)
        Spacer(Modifier.height(10.dp))

        if (h.measured) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoardWord(h.boardVerdict)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${h.boardBullish} bull · ${h.boardBearish} bear · " +
                        "${h.boardNeutral} neutral" +
                        if (h.graded) " · weighted by its own 1-year record" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            h.outlook?.let { o ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = String.format(
                        Locale.US, "5-day outlook: %s – %s",
                        Fmt.money(o.expectedLow), Fmt.money(o.expectedHigh)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.textDim
                )
            }
            if (h.trustedBull.isNotEmpty() || h.trustedBear.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Trusted on this stock: ${h.trustedBull.size} bullish, " +
                        "${h.trustedBear.size} bearish",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.gold
                )
            }
        } else {
            Text(
                text = "The board could not read this holding this run — unmeasured, " +
                    "not judged.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.textDim
            )
        }

        Spacer(Modifier.height(10.dp))
        h.why.take(3).forEach { reason ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.gold,
                    modifier = Modifier.width(12.dp)
                )
                Text(
                    text = reason.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.text,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ActionPill(action: BoardAction) {
    val (label, color) = when (action) {
        BoardAction.EXIT -> "EXIT" to AurumColors.loss
        BoardAction.TRIM -> "TRIM" to AurumColors.gold
        BoardAction.WATCH -> "WATCH" to AurumColors.info
        BoardAction.ADD -> "ADD" to AurumColors.gain
        BoardAction.HOLD -> "HOLD" to AurumColors.textDim
    }
    PillTag(label, color)
}

@Composable
private fun BoardWord(verdict: TechniqueVerdict) {
    val (label, color) = when (verdict) {
        TechniqueVerdict.BULLISH -> "Bullish board" to AurumColors.gain
        TechniqueVerdict.BEARISH -> "Bearish board" to AurumColors.loss
        TechniqueVerdict.NEUTRAL -> "Split board" to AurumColors.textDim
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = color
    )
}
