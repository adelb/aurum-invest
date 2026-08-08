package com.aurum.invest.analytics

import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.Advice
import com.aurum.invest.data.model.AdviceAction
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Position
import com.aurum.invest.data.model.Quote
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Rule-based buy/sell advisor. Pure Kotlin — no Android dependencies, never throws.
 *
 * Sell side: ATR-derived target (avgCost * (1 + 2*atrPct)) and stop
 * (avgCost * (1 - 1.5*atrPct)), RSI stretch, and news tone.
 * Buy side: RSI dip inside an uptrend plus news tone; a suggested entry price is
 * always produced once 20 daily candles exist.
 */
object AdviceEngine {

    fun sellAdvice(position: Position, quote: Quote, candles: List<Candle>, newsScore: Int = 0): Advice {
        val closes = candles.map { it.close }
        val price = if (quote.price > 0.0) quote.price else (closes.lastOrNull() ?: 0.0)
        val avgCost = position.avgCost

        val rsi = Indicators.rsi(closes)
        val sma20 = Indicators.sma(closes, 20)
        val atr = Indicators.atr(candles)
        val atrPct = if (atr != null && price > 0.0) atr / price else null
        val target = if (atrPct != null && avgCost > 0.0) round2(avgCost * (1.0 + 2.0 * atrPct)) else null
        val stop = if (atrPct != null && avgCost > 0.0) round2(avgCost * (1.0 - 1.5 * atrPct)) else null

        if (price <= 0.0 || avgCost <= 0.0) {
            return Advice(
                action = AdviceAction.HOLD,
                headline = "Not enough data on ${position.symbol} yet. Holding by default.",
                reasons = listOf(
                    "Live price or average cost is unavailable right now",
                    "Advice refreshes once quotes and price history load"
                ),
                score = 30,
                targetPrice = target,
                stopLoss = stop
            )
        }

        val plPct = (price - avgCost) / avgCost * 100.0

        // Bearish composite: news tone, stretched RSI, price under its 20-day average,
        // and a meaningful drawdown all add weight.
        var bear = 0
        if (newsScore < 0) bear += -newsScore * 2
        if (rsi != null && rsi > 68.0) bear += 2
        if (sma20 != null && price < sma20 * 0.98) bear += 2
        if (plPct < -5.0) bear += 2
        if (rsi != null && rsi < 40.0 && plPct < 0.0) bear += 1

        val decision: Pair<AdviceAction, String> = when {
            target != null && price >= target ->
                AdviceAction.TAKE_PROFIT to
                    "Up ${fmt1(plPct)}% from cost. Target ${Fmt.money(target)} reached."

            rsi != null && rsi > 72.0 && plPct > 0.0 ->
                AdviceAction.TAKE_PROFIT to
                    "Up ${fmt1(plPct)}% from cost. RSI ${fmt0(rsi)} is overbought."

            stop != null && price <= stop ->
                AdviceAction.CUT_LOSS to
                    "Down ${fmt1(abs(plPct))}%, below the ${Fmt.money(stop)} stop."

            bear >= 6 -> {
                val drivers = mutableListOf<String>()
                if (newsScore < 0) drivers += "news tone at $newsScore"
                if (rsi != null && rsi > 68.0) drivers += "RSI ${fmt0(rsi)} overbought"
                if (sma20 != null && price < sma20 * 0.98) drivers += "price under its 20-day average ${Fmt.money(sma20)}"
                if (plPct < -5.0) drivers += "a ${fmt1(abs(plPct))}% drawdown"
                if (drivers.isEmpty()) drivers += "weak momentum and trend"
                AdviceAction.SELL to
                    "Sell signal: ${drivers.joinToString(", ")}."
            }

            else -> {
                val headline = if (target != null && stop != null) {
                    "No exit signal. Between stop ${Fmt.money(stop)} and target ${Fmt.money(target)}."
                } else {
                    "No exit signal at ${Fmt.money(price)} (${signed1(plPct)}% vs cost)."
                }
                AdviceAction.HOLD to headline
            }
        }
        val (action, headline) = decision

        val reasons = mutableListOf<String>()
        reasons += "Price ${Fmt.money(price)} is ${signed1(plPct)}% vs your ${Fmt.money(avgCost)} average cost"
        if (rsi != null) {
            reasons += "RSI(14) at ${fmt0(rsi)}" + when {
                rsi > 70.0 -> " — overbought territory above 70"
                rsi < 30.0 -> " — oversold territory below 30"
                else -> " — inside the neutral 30-70 band"
            }
        }
        if (target != null && stop != null) {
            reasons += "ATR exits: target ${Fmt.money(target)} (2×ATR above cost), stop ${Fmt.money(stop)} (1.5×ATR below cost)"
        }
        if (newsScore != 0) {
            reasons += "News tone over recent days scores ${signedInt(newsScore)} on a -2..+2 scale"
        }
        if (sma20 != null && reasons.size < 4) {
            val gapPct = (price - sma20) / sma20 * 100.0
            reasons += "Trades ${signed1(gapPct)}% ${if (price >= sma20) "above" else "below"} the 20-day average ${Fmt.money(sma20)}"
        }
        if (reasons.size < 2) {
            reasons += "Only ${closes.size} daily candles available — indicators firm up with more history"
        }

        val score = when (action) {
            AdviceAction.TAKE_PROFIT -> (78 + plPct.coerceAtMost(17.0).toInt()).coerceIn(60, 95)
            AdviceAction.CUT_LOSS -> 88
            AdviceAction.SELL -> (58 + bear * 4).coerceAtMost(90)
            else -> (60 - bear * 6).coerceIn(25, 60)
        }

        return Advice(
            action = action,
            headline = headline,
            reasons = reasons.take(4),
            score = score,
            targetPrice = target,
            stopLoss = stop
        )
    }

    fun buyAdvice(quote: Quote, candles: List<Candle>, newsScore: Int = 0): Advice {
        val closes = candles.map { it.close }
        val price = if (quote.price > 0.0) quote.price else (closes.lastOrNull() ?: 0.0)
        val symbol = quote.symbol

        if (price <= 0.0 || closes.size < 2) {
            return Advice(
                action = AdviceAction.WAIT,
                headline = "Waiting for market data on $symbol.",
                reasons = listOf(
                    "No live price or fewer than 2 daily candles available",
                    "Advice refreshes automatically once market data arrives"
                ),
                score = 20
            )
        }

        val rsi = Indicators.rsi(closes)
        val sma20 = Indicators.sma(closes, 20)
        val sma50 = Indicators.sma(closes, 50)
        val low20 = Indicators.recentLow(closes, 20)

        val uptrend = when {
            sma20 != null && sma50 != null -> sma20 >= sma50
            sma20 != null -> price >= sma20
            else -> closes.last() >= closes.first()
        }

        // Bullish composite: dip-zone RSI, trend, positive news, not extended above trend.
        var bull = 0
        if (rsi != null) {
            when {
                rsi < 32.0 -> bull += 3
                rsi < 45.0 -> bull += 2
                rsi <= 60.0 -> bull += 1
            }
        }
        if (uptrend) bull += 2
        if (newsScore > 0) bull += newsScore * 2
        if (sma20 != null && price <= sma20 * 1.01) bull += 1

        val action = when {
            rsi != null && rsi < 32.0 && uptrend && newsScore > 0 -> AdviceAction.STRONG_BUY
            bull >= 5 -> AdviceAction.BUY
            else -> AdviceAction.WAIT
        }

        // Good entry = max(20-day low, 20-day SMA * 0.985), rounded to cents.
        // Always available once 20 daily candles exist; kept below the live price on WAIT.
        var entry: Double? = null
        if (low20 != null && sma20 != null) {
            var e = max(low20, sma20 * 0.985)
            if (action == AdviceAction.WAIT && e >= price) e = price * 0.99
            if (action != AdviceAction.WAIT && e > price) e = price
            entry = round2(e)
        }

        val headline = when (action) {
            AdviceAction.STRONG_BUY ->
                "Oversold dip in an uptrend. RSI ${fmt0(rsi ?: 0.0)}, entry near ${Fmt.money(entry ?: price)}."
            AdviceAction.BUY ->
                "Bullish setup." +
                    (rsi?.let { " RSI ${fmt0(it)}." } ?: "") +
                    (entry?.let { " Entry near ${Fmt.money(it)}." } ?: "")
            else ->
                if (entry != null)
                    "No edge at ${Fmt.money(price)}. Better entry near ${Fmt.money(entry)}."
                else
                    "Not enough history to call an entry on $symbol."
        }

        val reasons = mutableListOf<String>()
        if (rsi != null) {
            reasons += "RSI(14) at ${fmt0(rsi)}" + when {
                rsi < 32.0 -> " — oversold below 32"
                rsi < 45.0 -> " — pulled back"
                rsi <= 60.0 -> " — neutral"
                else -> " — elevated"
            }
        }
        if (entry != null && low20 != null) {
            reasons += "Good entry ≈ ${Fmt.money(entry)} — the higher of the 20-day low ${Fmt.money(low20)} and the 20-day average minus 1.5%"
        }
        if (sma20 != null && sma50 != null) {
            reasons += "Trend: 20-day ${Fmt.money(sma20)} ${if (sma20 >= sma50) "above" else "below"} 50-day ${Fmt.money(sma50)} — " +
                (if (uptrend) "uptrend intact" else "downtrend")
        } else if (sma20 != null) {
            val gapPct = (price - sma20) / sma20 * 100.0
            reasons += "Price ${Fmt.money(price)} trades ${signed1(gapPct)}% ${if (price >= sma20) "above" else "below"} the 20-day average ${Fmt.money(sma20)}"
        }
        if (newsScore != 0) {
            reasons += "News tone scores ${signedInt(newsScore)} on a -2..+2 scale"
        }
        if (reasons.size < 2) {
            reasons += "Only ${closes.size} daily candles of history — signals firm up with more data"
        }

        val score = when (action) {
            AdviceAction.STRONG_BUY -> 90
            AdviceAction.BUY -> (55 + bull * 4).coerceAtMost(85)
            else -> (30 + bull * 4).coerceAtMost(50)
        }

        return Advice(
            action = action,
            headline = headline,
            reasons = reasons.take(4),
            score = score,
            suggestedBuyPrice = entry
        )
    }

    // -- helpers ------------------------------------------------------------

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun signed1(v: Double): String = String.format(Locale.US, "%+.1f", v)

    private fun signedInt(v: Int): String = if (v > 0) "+$v" else "$v"
}
