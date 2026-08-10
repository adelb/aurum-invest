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
 * Sell side: the target is cost + 2×ATR; the protective stop starts at
 * cost − 1.5×ATR and, once the position is in profit, TRAILS the market at
 * 20-day high − 2.5×ATR (chandelier style). Take-profit fires when the gain
 * is actually at risk — the trailing stop is hit, or the target is reached
 * with momentum cracking, or the move goes parabolic — never merely because
 * a healthy winner sits above a level frozen at the entry price.
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
        val high20 = Indicators.recentHigh(closes, 20)

        val target = if (atr != null && avgCost > 0.0) round2(avgCost + 2.0 * atr) else null
        val costStop = if (atr != null && avgCost > 0.0) round2(avgCost - 1.5 * atr) else null
        val trailStop = if (atr != null && high20 != null) round2(high20 - 2.5 * atr) else null

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
                stopLoss = costStop
            )
        }

        val plPct = (price - avgCost) / avgCost * 100.0

        // The stop that matters NOW: the loss limit while under water, the
        // higher of loss limit and trailing stop once in profit.
        val trailing = plPct > 0.0 && costStop != null && trailStop != null && trailStop > costStop
        val stop = if (trailing) trailStop else costStop

        // Momentum crack: overbought reading or price back under its 20-day average.
        val momentumWeak = (rsi != null && rsi > 70.0) || (sma20 != null && price < sma20)

        // Bearish composite: news tone, stretched RSI, price under its 20-day average,
        // and a meaningful drawdown all add weight.
        var bear = 0
        if (newsScore < 0) bear += -newsScore * 2
        if (rsi != null && rsi > 68.0) bear += 2
        if (sma20 != null && price < sma20 * 0.98) bear += 2
        if (plPct < -5.0) bear += 2
        if (rsi != null && rsi < 40.0 && plPct < 0.0) bear += 1

        val decision: Pair<AdviceAction, String> = when {
            // Hard loss limit breached — the position never worked.
            plPct <= 0.0 && stop != null && price <= stop ->
                AdviceAction.CUT_LOSS to
                    "Down ${fmt1(abs(plPct))}%, through the ${Fmt.money(stop)} stop."

            // In profit but the trailing stop is hit — the gain is leaking away.
            trailing && stop != null && price <= stop ->
                AdviceAction.TAKE_PROFIT to
                    "Up ${fmt1(plPct)}% from cost, but the trailing stop ${Fmt.money(stop)} " +
                    "is hit — protect the gain."

            // Target reached AND momentum is cracking — bank it.
            target != null && price >= target && momentumWeak ->
                AdviceAction.TAKE_PROFIT to
                    "Up ${fmt1(plPct)}% from cost, target ${Fmt.money(target)} reached and " +
                    "momentum is cooling" +
                    (rsi?.takeIf { it > 70.0 }?.let { " (RSI ${fmt0(it)})" } ?: "") + "."

            // Parabolic stretch — lock in at least part regardless of the target.
            rsi != null && rsi > 78.0 && plPct >= 8.0 ->
                AdviceAction.TAKE_PROFIT to
                    "Up ${fmt1(plPct)}% and RSI ${fmt0(rsi)} is parabolic — take at least part."

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

            // Above target with the trend still healthy — let the winner run.
            target != null && price >= target && stop != null ->
                AdviceAction.HOLD to
                    "Up ${fmt1(plPct)}% — past the ${Fmt.money(target)} target with the trend " +
                    "healthy. Let it run; " +
                    (if (trailing) "the stop has trailed up to ${Fmt.money(stop)}."
                    else "keep the ${Fmt.money(stop)} stop.")

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
            reasons += "Exits: target ${Fmt.money(target)} (cost + 2×ATR), stop ${Fmt.money(stop)} " +
                if (trailing) "(trailing: 20-day high − 2.5×ATR)" else "(cost − 1.5×ATR)"
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
        var entryClamped = false
        if (low20 != null && sma20 != null) {
            var e = max(low20, sma20 * 0.985)
            if (action == AdviceAction.WAIT && e >= price) {
                e = price * 0.99
                entryClamped = true
            }
            if (action != AdviceAction.WAIT && e > price) {
                e = price
                entryClamped = true
            }
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
            // Only describe the low/SMA anchor when it actually set the entry —
            // after the clamp, the shown price no longer comes from it.
            reasons += if (entryClamped) {
                "Good entry ≈ ${Fmt.money(entry)} — pulled just under the live price; the usual " +
                    "20-day-low/average anchor sits above the market right now"
            } else {
                "Good entry ≈ ${Fmt.money(entry)} — the higher of the 20-day low ${Fmt.money(low20)} and the 20-day average minus 1.5%"
            }
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
