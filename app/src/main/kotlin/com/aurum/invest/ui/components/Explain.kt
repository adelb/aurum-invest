package com.aurum.invest.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurum.invest.ui.theme.AurumColors

/**
 * The explain affordance: a small circled "?" beside a figure. Tapping it
 * opens a plain-language card saying what the number measures, what scale it
 * sits on, and how to act on it — the same honesty rule as the figures
 * themselves: explain the measurement, never oversell it.
 */
@Composable
fun InfoDot(
    title: String,
    explanation: String,
    modifier: Modifier = Modifier
) {
    var show by remember { mutableStateOf(false) }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            containerColor = AurumColors.surface,
            titleContentColor = AurumColors.text,
            textContentColor = AurumColors.textDim,
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurumColors.text
                )
            },
            text = {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurumColors.textDim
                )
            },
            confirmButton = {
                TextButton(onClick = { show = false }) {
                    Text(text = "Got it", color = AurumColors.gold)
                }
            }
        )
    }
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.dp, AurumColors.textDim.copy(alpha = 0.55f), CircleShape)
            .clickable { show = true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "?",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = AurumColors.textDim
        )
    }
}

/** A one-line label with its explain dot, for card headers and stat rows. */
@Composable
fun ExplainedLabel(
    text: String,
    explanation: String,
    modifier: Modifier = Modifier,
    dialogTitle: String = text,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = AurumColors.textDim
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        InfoDot(title = dialogTitle, explanation = explanation)
    }
}

/**
 * What every Wealth figure means, in one place — so two cards can never
 * explain the same number two different ways. Each entry states what is
 * measured, the scale it sits on, and what a high or low reading implies.
 */
object Meanings {

    const val PULSE_SCORE =
        "Market health on a fixed 0–100 scale, rebuilt from live data every half hour: " +
            "benchmark trend (S&P 500, Nasdaq 100, Russell 2000) is worth 40 points, the " +
            "share of a few hundred liquid names above their own 50-day average 30, the " +
            "share that advanced last session 15, and the VIX volatility regime 15.\n\n" +
            "60 or more reads as a tape worth new money, 42–59 selective entries only, " +
            "below 42 defensive. The scale is fixed — 70 means the same thing next month. " +
            "When too few inputs could actually be measured, the pulse shows no number at " +
            "all rather than a guess."

    const val VIX =
        "The Cboe Volatility Index — the market's own forecast of how much the S&P 500 " +
            "will swing over the next 30 days, derived from what traders pay for options " +
            "protection. It is quoted in points, not dollars.\n\n" +
            "Rough map: below 14 very calm · 14–17 calm · 17–20 normal · 20–25 elevated " +
            "(smaller positions, wider stops) · 25–30 stressed · above 30 fear. High VIX " +
            "cuts the market-pulse score because entries fail more often in violent tape; " +
            "spikes also mark the panic that long-term buyers watch for."

    const val BREADTH =
        "Of the few hundred liquid names the market scan reached, the share trading above " +
            "their own 50-day average. Above ~60% the advance is broad; below ~40% a rising " +
            "index is being carried by a handful of large names — a thinner, more fragile rally."

    const val FLOW_SCORE =
        "Where the money is measurably moving for one sector, on a fixed 0–100 scale. It " +
            "combines Chaikin Money Flow, the Money Flow Index, on-balance-volume slope, the " +
            "up-day share of dollar volume, relative strength against the S&P 500, and member " +
            "breadth — measured from real candles on the sector's ETF and members, never guessed.\n\n" +
            "Above ~60 with an INFLOW verdict: buyers are committing real dollars. Below ~40 " +
            "with OUTFLOW: they are leaving. The verdict needs at least 3 of the 4 money " +
            "signals to agree; anything less shows NEUTRAL with the disagreement visible."

    const val CONVICTION =
        "How strongly the evidence backs this ticket, 0–100 on a fixed recipe: entry-board " +
            "quality 35 points, the 35-technique board 20, sector money flow 15, volume " +
            "behaviour 10, trend structure (50/200-day averages) 10, RSI regime 6, news tone " +
            "4, plus analyst-rating adjustments. Anything that couldn't be measured adds " +
            "zero — a missing signal never fakes a point. Below 55 a candidate gets no money " +
            "at all; cash is treated as the honest alternative."

    const val SECTOR_TARGET =
        "Current → suggested share of your whole account (holdings plus uninvested cash) " +
            "for this sector. Targets steer toward sectors the money is measurably entering " +
            "and away from ones it is leaving, but never past your profile's sector cap — " +
            "and the plan only ever fills the gap between current and target, it does not " +
            "sell you down to a target."

    const val RESERVE_CASH =
        "The part of your uninvested cash the plan deliberately does NOT deploy. It exists " +
            "because candidates below the conviction bar earn nothing, and because your " +
            "profile's position and sector caps bound every ticket. Reserve cash is advice, " +
            "not leftovers — when nothing clears the bar, holding cash IS the recommendation."

    const val TECH_AGREEMENT =
        "Of the techniques on the 35-technique board that produced a verdict for this " +
            "stock, the share pointing the same way. 70% agreement means the signals agree " +
            "unusually strongly — but agreement is not accuracy; the Trusted badge marks " +
            "the techniques that have actually called this stock right over a year of replays."

    const val TWR =
        "Time-weighted return: your portfolio's growth rate with the timing and size of " +
            "your own deposits stripped out — the same convention funds report, so it is " +
            "comparable to an index over the same window. It can differ a lot from your " +
            "dollar P/L when you added or withdrew money mid-swing."

    const val SHARPE =
        "Return earned per unit of volatility endured, annualized. Above 1 is good, above " +
            "2 excellent, negative means the risk wasn't paid for. Computed from your " +
            "reconstructed daily equity curve."

    const val MAX_DRAWDOWN =
        "The deepest peak-to-trough fall your equity curve has taken in the window — the " +
            "worst day to have started checking your account. A strategy you can't sit " +
            "through at its historical drawdown is a strategy you don't actually have."

    const val BETA =
        "How hard your portfolio moves when the S&P 500 moves 1%. Beta 1.3 means a market " +
            "dip of 2% has typically dragged your book down about 2.6%. Below 1 the book " +
            "swings less than the market; negative moves against it."

    const val GRADE =
        "Your book scored 0–100 against the elite-desk rulebook: position sizing, sector " +
            "concentration, winner/loser handling, stop discipline, and cash posture. Each " +
            "weak discipline lists the concrete action that would repair it — the grade " +
            "moves when the book does, not when prices wiggle."

    const val THEME_AMOUNT =
        "Dollars of your uninvested cash this theme would receive this week — trend " +
            "strength scaled by how much room your book has left in the theme, capped by " +
            "your profile. The named stock is the theme shelf's strongest board-passed " +
            "name; the alternates passed the same gate."
}
