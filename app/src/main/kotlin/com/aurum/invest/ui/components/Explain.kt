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
        "Market health on a fixed 0-100 scale, rebuilt from live data: benchmark trend " +
            "(S&P 500, Nasdaq 100, Russell 2000 vs their 50-day averages) is worth 40 " +
            "points, the share of a few hundred liquid scanned names above their own " +
            "50-day average 30, the share that advanced last session 15, and the VIX " +
            "volatility regime 15.\n\n" +
            "60 or more reads as a tape worth new money, 42-59 selective entries only, " +
            "below 42 defensive. The scale is fixed - 70 means the same thing next month. " +
            "An unreachable input scores neutral and the reasons list says so."

    const val VIX =
        "The Cboe Volatility Index - the market's own forecast of how much the S&P 500 " +
            "will swing over the next 30 days, derived from what traders pay for options " +
            "protection. It is quoted in points, not dollars.\n\n" +
            "Rough map: below 14 very calm; 14-17 calm; 17-20 normal; 20-25 elevated " +
            "(smaller positions, wider stops); 25-30 stressed; above 30 fear. A high VIX " +
            "cuts the market-pulse score because entries fail more often in violent tape; " +
            "spikes also mark the panic that long-term buyers watch for."

    const val BREADTH =
        "Of the few hundred liquid names the market scan reached, the share trading above " +
            "their own 50-day average. Above ~60% the advance is broad; below ~40% a rising " +
            "index is being carried by a handful of large names - a thinner, more fragile rally."

    const val CONVICTION =
        "How strongly the measured evidence backs this ticket, 0-100 on a fixed recipe: " +
            "the 35-technique board's bullish agreement carries 60%, then volume against " +
            "its own 20-day average, the RSI regime, the 20-day trend, and headline tone. " +
            "Anything that couldn't be measured adds zero - a missing signal never fakes " +
            "a point. Below 55 a stock gets no money at all; cash is treated as the " +
            "honest alternative."

    const val SECTOR_TARGET =
        "Held -> target: this theme's current share of your book against the share the " +
            "week's trend strength suggests. New money is steered toward trending themes " +
            "your book is thin in, but never past your policy's sector cap - and the plan " +
            "only fills gaps with new money, it does not tell you to sell down to a target."

    const val RESERVE_CASH =
        "The part of your uninvested cash the plan deliberately does NOT deploy. It exists " +
            "because stocks below the conviction bar earn nothing, and because your " +
            "policy's position and sector caps bound every ticket. Reserve cash is advice, " +
            "not leftovers - when nothing clears the bar, holding cash IS the recommendation."

    const val TECH_AGREEMENT =
        "Of the techniques on the 35-technique board that produced a verdict for this " +
            "stock, the share pointing the same way. High agreement means the signals " +
            "line up unusually strongly - but agreement is not accuracy; the Trusted " +
            "badge marks the techniques that have actually called this stock right " +
            "over a year of replayed sessions."

    const val THEME_AMOUNT =
        "Dollars of your uninvested cash this theme would receive this week - trend " +
            "strength scaled by how much room your book has left in the theme. The named " +
            "stock is the theme shelf's strongest board-passed name; the alternates " +
            "passed the same gate. When no wallet is set, the split shows the 4-month " +
            "plan's base instead - percentages of intent, not measured cash."
}
