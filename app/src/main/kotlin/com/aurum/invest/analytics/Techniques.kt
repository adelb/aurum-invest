package com.aurum.invest.analytics

import com.aurum.invest.core.Fmt
import com.aurum.invest.data.model.Candle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Five-technique chart analysis over daily candles. Pure Kotlin — no Android
 * dependencies, never throws. All rolling series are index-aligned with the
 * (last <= 120) candles the analysis ran on: entry i belongs to candle i, and
 * is null until enough history exists at that index.
 */

enum class TechniqueVerdict { BULLISH, BEARISH, NEUTRAL }

data class MaTrendData(
    val closes: List<Double>,
    val sma20: List<Double?>,
    val sma50: List<Double?>
)

data class RsiData(val rsi: List<Double?>)

data class MacdData(
    val macd: List<Double?>,
    val signal: List<Double?>,
    val histogram: List<Double?>
)

data class BollingerData(
    val closes: List<Double>,
    val upper: List<Double?>,
    val middle: List<Double?>,
    val lower: List<Double?>
)

data class SupportResistanceData(
    val closes: List<Double>,
    val supports: List<Double>,
    val resistances: List<Double>
)

data class TechniqueResult(
    val key: String,
    val name: String,
    val verdict: TechniqueVerdict,
    val strength: Int,
    val summary: String
)

data class FiveDayOutlook(
    val direction: TechniqueVerdict,
    val bullishCount: Int,
    val bearishCount: Int,
    val neutralCount: Int,
    val expectedLow: Double,
    val expectedHigh: Double,
    val confidence: Int,
    val headline: String,
    val summary: List<String>
)

data class TechniqueAnalysis(
    val symbol: String,
    val results: List<TechniqueResult>,
    val outlook: FiveDayOutlook,
    val maData: MaTrendData,
    val rsiData: RsiData,
    val macdData: MacdData,
    val bollingerData: BollingerData,
    val srData: SupportResistanceData
)

object Techniques {

    private const val MIN_CANDLES = 30
    private const val SERIES_MAX = 120
    private const val SR_LOOKBACK = 90
    private const val SR_CLUSTER_PCT = 1.5
    private const val SR_NEAR_PCT = 2.0

    /** Null when fewer than 30 daily candles are supplied. Uses the last <= 120 candles. */
    fun analyze(symbol: String, candles: List<Candle>): TechniqueAnalysis? {
        if (candles.size < MIN_CANDLES) return null
        val cs = candles.takeLast(SERIES_MAX)
        val closes = cs.map { it.close }
        val n = closes.size
        val price = closes.last()
        if (price <= 0.0) return null

        // Rolling series, all aligned to [cs] by index.
        val sma20 = smaSeries(closes, 20)
        val sma50 = smaSeries(closes, 50)
        val rsiS = rsiSeries(closes, 14)
        val macdS = macdSeries(closes)
        val bollS = bollingerSeries(closes, 20, 2.0)
        val (supports, resistances) = srLevels(cs, price)

        val maData = MaTrendData(closes, sma20, sma50)
        val rsiData = RsiData(rsiS)
        val macdData = MacdData(macdS.macd, macdS.signal, macdS.histogram)
        val bollData = BollingerData(closes, bollS.upper, bollS.middle, bollS.lower)
        val srData = SupportResistanceData(closes, supports.map { it.level }, resistances.map { it.level })

        val results = listOf(
            maResult(n, price, sma20, sma50),
            rsiResult(n, rsiS),
            macdResult(n, price, macdS),
            bollingerResult(price, bollS),
            srResult(price, supports, resistances)
        )

        val outlook = buildOutlook(cs, price, results, supports, resistances)

        return TechniqueAnalysis(
            symbol = symbol,
            results = results,
            outlook = outlook,
            maData = maData,
            rsiData = rsiData,
            macdData = macdData,
            bollingerData = bollData,
            srData = srData
        )
    }

    // -- technique 1: moving averages ---------------------------------------

    private fun maResult(n: Int, price: Double, sma20: List<Double?>, sma50: List<Double?>): TechniqueResult {
        val name = "Moving averages"
        val s20 = sma20.last()
        val s50 = sma50.last()

        if (s20 == null) {
            return TechniqueResult(
                "ma", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 20 daily candles for the 20-day average; $n available."
            )
        }
        if (s50 == null) {
            val rel = if (price >= s20) "above" else "below"
            return TechniqueResult(
                "ma", name, TechniqueVerdict.NEUTRAL, 25,
                "Close ${Fmt.money(price)} is $rel the 20-day average ${Fmt.money(s20)}. " +
                    "The 50-day average needs 50 candles; $n available."
            )
        }

        // Most recent 20/50 cross within the last 10 bars, if any.
        var crossAgo: Int? = null
        var steps = 0
        var i = sma20.size - 1
        while (i >= 1 && steps < 10) {
            val a = sma20[i]
            val b = sma50[i]
            val pa = sma20[i - 1]
            val pb = sma50[i - 1]
            if (a != null && b != null && pa != null && pb != null) {
                val d = a - b
                val pd = pa - pb
                if (d != 0.0 && pd != 0.0 && (d > 0.0) != (pd > 0.0)) {
                    crossAgo = sma20.size - 1 - i
                    break
                }
            }
            i--
            steps++
        }

        val verdict = when {
            price > s20 && s20 > s50 -> TechniqueVerdict.BULLISH
            price < s20 && s20 < s50 -> TechniqueVerdict.BEARISH
            else -> TechniqueVerdict.NEUTRAL
        }
        val gapPct = if (s50 != 0.0) abs(s20 - s50) / s50 * 100.0 else 0.0
        val strength = if (verdict == TechniqueVerdict.NEUTRAL) 30 else {
            val base = 55.0 + min(20.0, gapPct * 4.0) + (if (crossAgo != null) 15.0 else 0.0)
            base.roundToInt().coerceIn(0, 95)
        }

        val crossNote = crossAgo?.let {
            " The 20/50 cross came ${if (it == 0) "this bar" else "$it ${bars(it)} ago"}."
        } ?: ""
        val summary = when (verdict) {
            TechniqueVerdict.BULLISH ->
                "Close ${Fmt.money(price)} above the 20-day ${Fmt.money(s20)}, itself above the 50-day ${Fmt.money(s50)}.$crossNote"
            TechniqueVerdict.BEARISH ->
                "Close ${Fmt.money(price)} below the 20-day ${Fmt.money(s20)}, itself below the 50-day ${Fmt.money(s50)}.$crossNote"
            TechniqueVerdict.NEUTRAL ->
                "Close ${Fmt.money(price)} sits between the 20-day ${Fmt.money(s20)} and 50-day ${Fmt.money(s50)}; no aligned trend.$crossNote"
        }
        return TechniqueResult("ma", name, verdict, strength, summary)
    }

    // -- technique 2: RSI momentum ------------------------------------------

    private fun rsiResult(n: Int, rsiS: List<Double?>): TechniqueResult {
        val name = "RSI momentum"
        val r = rsiS.last()
            ?: return TechniqueResult(
                "rsi", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 15 daily candles for RSI(14); $n available."
            )
        val prev5 = if (rsiS.size >= 6) rsiS[rsiS.size - 6] else null
        val trendNote = prev5?.let { " Five bars ago it was ${fmt0(it)}." } ?: ""

        return when {
            r < 30.0 -> TechniqueResult(
                "rsi", name, TechniqueVerdict.BULLISH,
                (55.0 + (30.0 - r) * 3.0).roundToInt().coerceIn(55, 95),
                "RSI(14) at ${fmt0(r)}, under the 30 oversold line.$trendNote"
            )
            r > 70.0 -> TechniqueResult(
                "rsi", name, TechniqueVerdict.BEARISH,
                (55.0 + (r - 70.0) * 3.0).roundToInt().coerceIn(55, 95),
                "RSI(14) at ${fmt0(r)}, over the 70 overbought line.$trendNote"
            )
            else -> TechniqueResult(
                "rsi", name, TechniqueVerdict.NEUTRAL, 30,
                "RSI(14) at ${fmt0(r)}, inside the 30-70 band.$trendNote"
            )
        }
    }

    // -- technique 3: MACD ---------------------------------------------------

    private fun macdResult(n: Int, price: Double, s: MacdSeries): TechniqueResult {
        val name = "MACD"
        val macd = s.macd.last()
        val sig = s.signal.last()
        val hist = s.histogram.last()
        if (macd == null || sig == null || hist == null) {
            return TechniqueResult(
                "macd", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 34 daily candles for MACD(12,26,9); $n available."
            )
        }
        val prevHist = if (s.histogram.size >= 2) s.histogram[s.histogram.size - 2] else null
        val rising = prevHist != null && hist > prevHist
        val falling = prevHist != null && hist < prevHist

        val verdict = when {
            macd > sig && rising -> TechniqueVerdict.BULLISH
            macd < sig && falling -> TechniqueVerdict.BEARISH
            else -> TechniqueVerdict.NEUTRAL
        }
        val histPct = abs(hist) / price * 100.0
        val strength = if (verdict == TechniqueVerdict.NEUTRAL) 30
        else (55.0 + histPct * 150.0).roundToInt().coerceIn(55, 95)

        val summary = when (verdict) {
            TechniqueVerdict.BULLISH ->
                "MACD ${fmtSig(macd)} above signal ${fmtSig(sig)} with a rising histogram at ${fmtSig(hist)}."
            TechniqueVerdict.BEARISH ->
                "MACD ${fmtSig(macd)} below signal ${fmtSig(sig)} with a falling histogram at ${fmtSig(hist)}."
            TechniqueVerdict.NEUTRAL ->
                "MACD ${fmtSig(macd)} vs signal ${fmtSig(sig)}; histogram ${fmtSig(hist)} shows no push either way."
        }
        return TechniqueResult("macd", name, verdict, strength, summary)
    }

    // -- technique 4: Bollinger Bands ---------------------------------------

    private fun bollingerResult(price: Double, s: BollSeries): TechniqueResult {
        val name = "Bollinger Bands"
        val u = s.upper.last()
        val m = s.middle.last()
        val l = s.lower.last()
        if (u == null || m == null || l == null) {
            return TechniqueResult(
                "bollinger", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 20 daily candles for Bollinger(20, 2); fewer available."
            )
        }
        val widthPct = if (m > 0.0) (u - l) / m * 100.0 else 0.0

        return when {
            price <= l -> {
                val pen = if (l > 0.0) (l - price) / l * 100.0 else 0.0
                TechniqueResult(
                    "bollinger", name, TechniqueVerdict.BULLISH,
                    (60.0 + pen * 10.0).roundToInt().coerceIn(60, 95),
                    "Close ${Fmt.money(price)} is ${fmt1(pen)}% under the lower band ${Fmt.money(l)}; middle ${Fmt.money(m)}. Stretched for a reversion."
                )
            }
            price >= u -> {
                val pen = if (u > 0.0) (price - u) / u * 100.0 else 0.0
                TechniqueResult(
                    "bollinger", name, TechniqueVerdict.BEARISH,
                    (60.0 + pen * 10.0).roundToInt().coerceIn(60, 95),
                    "Close ${Fmt.money(price)} is ${fmt1(pen)}% over the upper band ${Fmt.money(u)}; middle ${Fmt.money(m)}. Stretched for a reversion."
                )
            }
            widthPct < 4.0 -> TechniqueResult(
                "bollinger", name, TechniqueVerdict.NEUTRAL, 40,
                "Band width ${fmt1(widthPct)}% of the ${Fmt.money(m)} middle — squeeze, breakout pending."
            )
            else -> TechniqueResult(
                "bollinger", name, TechniqueVerdict.NEUTRAL, 30,
                "Close ${Fmt.money(price)} inside the ${Fmt.money(l)} to ${Fmt.money(u)} band; width ${fmt1(widthPct)}% of the middle."
            )
        }
    }

    // -- technique 5: support & resistance ----------------------------------

    private fun srResult(price: Double, supports: List<Level>, resistances: List<Level>): TechniqueResult {
        val name = "Support & resistance"
        val sup = supports.lastOrNull()      // highest support below price
        val res = resistances.firstOrNull()  // lowest resistance above price

        if (sup == null && res == null) {
            return TechniqueResult(
                "sr", name, TechniqueVerdict.NEUTRAL, 20,
                "No clustered swing levels near ${Fmt.money(price)} in the last $SR_LOOKBACK candles."
            )
        }

        val supDistPct = sup?.let { (price - it.level) / it.level * 100.0 }
        val resDistPct = res?.let { (res.level - price) / res.level * 100.0 }
        val nearSupport = supDistPct != null && supDistPct <= SR_NEAR_PCT
        val nearResistance = resDistPct != null && resDistPct <= SR_NEAR_PCT

        // When both levels are within 2%, the closer one decides the verdict.
        val pickSupport = nearSupport && (!nearResistance || supDistPct!! <= resDistPct!!)
        val pickResistance = nearResistance && !pickSupport

        return when {
            pickSupport -> {
                val strength = (50.0 + sup!!.touches * 8.0 + (SR_NEAR_PCT - supDistPct!!) * 10.0)
                    .roundToInt().coerceIn(50, 95)
                TechniqueResult(
                    "sr", name, TechniqueVerdict.BULLISH, strength,
                    "Price ${Fmt.money(price)} sits ${fmt1(supDistPct)}% above support at ${Fmt.money(sup.level)} " +
                        "(${sup.touches} ${touches(sup.touches)})."
                )
            }
            pickResistance -> {
                val strength = (50.0 + res!!.touches * 8.0 + (SR_NEAR_PCT - resDistPct!!) * 10.0)
                    .roundToInt().coerceIn(50, 95)
                TechniqueResult(
                    "sr", name, TechniqueVerdict.BEARISH, strength,
                    "Price ${Fmt.money(price)} sits ${fmt1(resDistPct)}% below resistance at ${Fmt.money(res.level)} " +
                        "(${res.touches} ${touches(res.touches)})."
                )
            }
            else -> {
                val parts = mutableListOf<String>()
                if (sup != null) parts += "support ${Fmt.money(sup.level)}"
                if (res != null) parts += "resistance ${Fmt.money(res.level)}"
                TechniqueResult(
                    "sr", name, TechniqueVerdict.NEUTRAL, 30,
                    "Price ${Fmt.money(price)} is mid-range: ${parts.joinToString(", ")}."
                )
            }
        }
    }

    // -- outlook -------------------------------------------------------------

    private fun buildOutlook(
        candles: List<Candle>,
        price: Double,
        results: List<TechniqueResult>,
        supports: List<Level>,
        resistances: List<Level>
    ): FiveDayOutlook {
        val bullishCount = results.count { it.verdict == TechniqueVerdict.BULLISH }
        val bearishCount = results.count { it.verdict == TechniqueVerdict.BEARISH }
        val neutralCount = results.count { it.verdict == TechniqueVerdict.NEUTRAL }

        val wBull = results.filter { it.verdict == TechniqueVerdict.BULLISH }.sumOf { it.strength }
        val wBear = results.filter { it.verdict == TechniqueVerdict.BEARISH }.sumOf { it.strength }
        val wNeut = results.filter { it.verdict == TechniqueVerdict.NEUTRAL }.sumOf { it.strength }
        val total = max(1, wBull + wBear + wNeut)

        val bullShare = wBull.toDouble() / total
        val bearShare = wBear.toDouble() / total
        val direction = when {
            bullShare >= 0.55 -> TechniqueVerdict.BULLISH
            bearShare >= 0.55 -> TechniqueVerdict.BEARISH
            else -> TechniqueVerdict.NEUTRAL
        }
        val confidence = when (direction) {
            TechniqueVerdict.BULLISH -> (bullShare * 100.0).roundToInt()
            TechniqueVerdict.BEARISH -> (bearShare * 100.0).roundToInt()
            TechniqueVerdict.NEUTRAL ->
                (max(max(wBull, wBear), wNeut).toDouble() / total * 100.0).roundToInt()
        }.coerceIn(0, 100)

        val atr = Indicators.atr(candles, 14) ?: (price * 0.02)
        val span = 1.3 * atr * sqrt(5.0)
        val shift = 0.4 * atr * sqrt(5.0) * when (direction) {
            TechniqueVerdict.BULLISH -> 1.0
            TechniqueVerdict.BEARISH -> -1.0
            TechniqueVerdict.NEUTRAL -> 0.0
        }
        var low = price - span + shift
        var high = price + span + shift
        // Keep the last close strictly inside the range unless conviction is extreme.
        if (confidence < 90) {
            low = min(low, price * 0.999)
            high = max(high, price * 1.001)
        }
        low = round2(max(0.0, low))
        high = round2(high)

        val headline = when (direction) {
            TechniqueVerdict.BULLISH -> "Leaning bullish over the next 5 days."
            TechniqueVerdict.BEARISH -> "Leaning bearish over the next 5 days."
            TechniqueVerdict.NEUTRAL -> "Mixed signals over the next 5 days."
        }

        val sup = supports.lastOrNull()
        val res = resistances.firstOrNull()
        val srSentence = when {
            sup != null && res != null ->
                "Nearest support ${Fmt.money(sup.level)}, nearest resistance ${Fmt.money(res.level)}."
            sup != null -> "Nearest support ${Fmt.money(sup.level)}; no clustered resistance above."
            res != null -> "Nearest resistance ${Fmt.money(res.level)}; no clustered support below."
            else -> "No clustered support or resistance levels in the last $SR_LOOKBACK candles."
        }

        val summary = listOf(
            "$bullishCount of 5 techniques read bullish, $bearishCount bearish, $neutralCount neutral.",
            "The leading side holds $confidence% of the strength-weighted votes.",
            "Expected range ${Fmt.money(low)} to ${Fmt.money(high)} from the ${Fmt.money(price)} close, using a 14-day ATR of ${Fmt.money(atr)}.",
            srSentence,
            "Computed from past prices only; not a guarantee of future moves."
        )

        return FiveDayOutlook(
            direction = direction,
            bullishCount = bullishCount,
            bearishCount = bearishCount,
            neutralCount = neutralCount,
            expectedLow = low,
            expectedHigh = high,
            confidence = confidence,
            headline = headline,
            summary = summary
        )
    }

    // -- rolling series math -------------------------------------------------

    /** Rolling SMA; index i covers values[i-period+1..i], null before that. */
    private fun smaSeries(values: List<Double>, period: Int): List<Double?> {
        val n = values.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0 || n < period) return out.toList()
        var sum = 0.0
        for (i in 0 until n) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out.toList()
    }

    /** Rolling EMA seeded with the SMA of the first [period] values. */
    private fun emaSeries(values: List<Double>, period: Int): List<Double?> {
        val n = values.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0 || n < period) return out.toList()
        var seed = 0.0
        for (i in 0 until period) seed += values[i]
        var ema = seed / period
        out[period - 1] = ema
        val k = 2.0 / (period + 1.0)
        for (i in period until n) {
            ema += k * (values[i] - ema)
            out[i] = ema
        }
        return out.toList()
    }

    /** Rolling Wilder RSI; first value at index [period]. */
    private fun rsiSeries(closes: List<Double>, period: Int): List<Double?> {
        val n = closes.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0 || n < period + 1) return out.toList()
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0.0) avgGain += d else avgLoss -= d
        }
        avgGain /= period
        avgLoss /= period
        out[period] = rsiValue(avgGain, avgLoss)
        for (i in period + 1 until n) {
            val d = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + max(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-d, 0.0)) / period
            out[i] = rsiValue(avgGain, avgLoss)
        }
        return out.toList()
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    private class MacdSeries(
        val macd: List<Double?>,
        val signal: List<Double?>,
        val histogram: List<Double?>
    )

    /** MACD(12,26,9): line from index 25, signal (EMA9 of the line) from index 33. */
    private fun macdSeries(closes: List<Double>): MacdSeries {
        val n = closes.size
        val ema12 = emaSeries(closes, 12)
        val ema26 = emaSeries(closes, 26)
        val macd = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val a = ema12[i]
            val b = ema26[i]
            if (a != null && b != null) macd[i] = a - b
        }
        val firstIdx = macd.indexOfFirst { it != null }
        val signal = arrayOfNulls<Double>(n)
        if (firstIdx >= 0) {
            val macdVals = ArrayList<Double>(n - firstIdx)
            for (i in firstIdx until n) macdVals.add(macd[i]!!)
            val sig = emaSeries(macdVals, 9)
            for (j in sig.indices) signal[firstIdx + j] = sig[j]
        }
        val histogram = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val m = macd[i]
            val s = signal[i]
            if (m != null && s != null) histogram[i] = m - s
        }
        return MacdSeries(macd.toList(), signal.toList(), histogram.toList())
    }

    private class BollSeries(
        val upper: List<Double?>,
        val middle: List<Double?>,
        val lower: List<Double?>
    )

    /** Bollinger(period, k): middle = SMA, bands at k population-sigma over the same window. */
    private fun bollingerSeries(closes: List<Double>, period: Int, k: Double): BollSeries {
        val n = closes.size
        val middle = smaSeries(closes, period)
        val upper = arrayOfNulls<Double>(n)
        val lower = arrayOfNulls<Double>(n)
        for (i in period - 1 until n) {
            val m = middle[i] ?: continue
            var varSum = 0.0
            for (j in i - period + 1..i) {
                val d = closes[j] - m
                varSum += d * d
            }
            val sigma = sqrt(varSum / period)
            upper[i] = m + k * sigma
            lower[i] = m - k * sigma
        }
        return BollSeries(upper.toList(), middle, lower.toList())
    }

    // -- support / resistance ------------------------------------------------

    private class Level(val level: Double, val touches: Int)

    /**
     * Swing highs/lows (local extrema, window 3) over the last [SR_LOOKBACK] candles,
     * clustered within [SR_CLUSTER_PCT]% of the running cluster mean. Returns up to the
     * 3 most-touched supports (below [price], ascending) and resistances (above, ascending).
     */
    private fun srLevels(candles: List<Candle>, price: Double): Pair<List<Level>, List<Level>> {
        val window = candles.takeLast(min(SR_LOOKBACK, candles.size))
        val w = 3
        val raw = ArrayList<Double>()
        for (i in w until window.size - w) {
            val hi = window[i].high
            val lo = window[i].low
            var isHigh = true
            var isLow = true
            for (j in i - w..i + w) {
                if (j == i) continue
                if (window[j].high > hi) isHigh = false
                if (window[j].low < lo) isLow = false
                if (!isHigh && !isLow) break
            }
            if (isHigh) raw.add(hi)
            if (isLow) raw.add(lo)
        }
        if (raw.isEmpty()) return fallbackLevels(window, price)

        raw.sort()
        val clusters = ArrayList<Level>()
        var sum = raw[0]
        var count = 1
        for (i in 1 until raw.size) {
            val mean = sum / count
            if (mean > 0.0 && abs(raw[i] - mean) / mean * 100.0 <= SR_CLUSTER_PCT) {
                sum += raw[i]
                count++
            } else {
                clusters.add(Level(sum / count, count))
                sum = raw[i]
                count = 1
            }
        }
        clusters.add(Level(sum / count, count))

        var supports = clusters.filter { it.level < price }
            .sortedWith(compareByDescending<Level> { it.touches }.thenByDescending { it.level })
            .take(3)
            .sortedBy { it.level }
        var resistances = clusters.filter { it.level > price }
            .sortedWith(compareByDescending<Level> { it.touches }.thenBy { it.level })
            .take(3)
            .sortedBy { it.level }

        if (supports.isEmpty() || resistances.isEmpty()) {
            val (fs, fr) = fallbackLevels(window, price)
            if (supports.isEmpty()) supports = fs
            if (resistances.isEmpty()) resistances = fr
        }
        return supports to resistances
    }

    /** Range extremes as single-touch levels when no swing cluster lands on a side. */
    private fun fallbackLevels(window: List<Candle>, price: Double): Pair<List<Level>, List<Level>> {
        val minLow = window.minOfOrNull { it.low }
        val maxHigh = window.maxOfOrNull { it.high }
        val supports = if (minLow != null && minLow < price) listOf(Level(minLow, 1)) else emptyList()
        val resistances = if (maxHigh != null && maxHigh > price) listOf(Level(maxHigh, 1)) else emptyList()
        return supports to resistances
    }

    // -- formatting helpers --------------------------------------------------

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** Adaptive precision for small MACD magnitudes (penny stocks). */
    private fun fmtSig(v: Double): String =
        if (abs(v) < 0.05) String.format(Locale.US, "%.3f", v)
        else String.format(Locale.US, "%.2f", v)

    private fun bars(n: Int): String = if (n == 1) "bar" else "bars"

    private fun touches(n: Int): String = if (n == 1) "touch" else "touches"
}
