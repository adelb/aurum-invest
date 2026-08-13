package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import kotlin.math.abs

/**
 * ATR-based trade setup for a specific holding horizon. Direction is derived
 * from the candles at that horizon (short EMA vs longer EMA plus RSI band),
 * not asserted — a NEUTRAL reading means the levels are shown but conviction
 * is low. Levels never assume equity-market session structure, so the same
 * math applies to metals, FX, and indices.
 */
data class MarketIdea(
    val horizon: Horizon,
    val direction: Direction,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val rr1: Double,      // reward-to-risk at TP1
    val atr: Double,
    val rationale: String
) {
    enum class Horizon { SCALP, SHORT, LONG }
    enum class Direction { LONG, SHORT, NEUTRAL }
}

object MarketsIdeas {

    /** Yahoo (range, interval) tuple that best matches a horizon. */
    fun yahooRangeInterval(h: MarketIdea.Horizon): Pair<String, String> = when (h) {
        MarketIdea.Horizon.SCALP -> "5d" to "15m"     // 5 days of 15-minute bars
        MarketIdea.Horizon.SHORT -> "60d" to "1h"     // 60 days of hourly bars
        MarketIdea.Horizon.LONG -> "1y" to "1d"       // 1 year of daily bars
    }

    /**
     * Compute a full setup — entry / SL / three TPs — from the horizon's
     * candles. ATR multipliers tighten toward scalping and widen toward
     * position sizing.
     */
    fun compute(horizon: MarketIdea.Horizon, price: Double, candles: List<Candle>): MarketIdea? {
        if (price <= 0.0 || candles.size < 25) return null
        val atr = Indicators.atr(candles) ?: return null
        val closes = candles.map { it.close }
        val ema20 = Indicators.sma(closes, 20) ?: closes.last()
        val ema50 = Indicators.sma(closes.takeLast(50.coerceAtMost(closes.size)), 50.coerceAtMost(closes.size))
            ?: closes.last()
        val rsi = Indicators.rsi(closes) ?: 50.0

        val (slMult, tp1Mult, tp2Mult, tp3Mult) = when (horizon) {
            MarketIdea.Horizon.SCALP -> arrayOf(1.0, 1.0, 1.5, 2.0)
            MarketIdea.Horizon.SHORT -> arrayOf(2.0, 2.0, 3.5, 5.0)
            MarketIdea.Horizon.LONG -> arrayOf(3.0, 4.0, 7.0, 10.0)
        }

        val uptrend = price > ema20 && ema20 > ema50 && rsi in 40.0..72.0
        val downtrend = price < ema20 && ema20 < ema50 && rsi in 28.0..60.0
        val dir = when {
            uptrend -> MarketIdea.Direction.LONG
            downtrend -> MarketIdea.Direction.SHORT
            else -> MarketIdea.Direction.NEUTRAL
        }
        // NEUTRAL renders long-side levels for reference (clearly labelled).
        val sign = if (dir == MarketIdea.Direction.SHORT) -1.0 else 1.0

        val sl = price - sign * slMult * atr
        val tp1 = price + sign * tp1Mult * atr
        val tp2 = price + sign * tp2Mult * atr
        val tp3 = price + sign * tp3Mult * atr

        val risk = abs(price - sl)
        val rr1 = if (risk > 0.0) abs(tp1 - price) / risk else 0.0

        val horizonWord = when (horizon) {
            MarketIdea.Horizon.SCALP -> "scalp (15m bars)"
            MarketIdea.Horizon.SHORT -> "short-term (1h bars)"
            MarketIdea.Horizon.LONG -> "long-term (daily bars)"
        }
        val trendWord = when (dir) {
            MarketIdea.Direction.LONG -> "uptrend continuation"
            MarketIdea.Direction.SHORT -> "downtrend continuation"
            MarketIdea.Direction.NEUTRAL -> "no confirmed trend"
        }
        val rationale = "$horizonWord — $trendWord. Price ${bp(price, ema20)} the 20-EMA, RSI ${rsi.toInt()}."

        return MarketIdea(
            horizon = horizon,
            direction = dir,
            entry = price,
            sl = sl,
            tp1 = tp1,
            tp2 = tp2,
            tp3 = tp3,
            rr1 = rr1,
            atr = atr,
            rationale = rationale
        )
    }

    private fun bp(price: Double, ma: Double): String = if (price >= ma) "above" else "below"
}
