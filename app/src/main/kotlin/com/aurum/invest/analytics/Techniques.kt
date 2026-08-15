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
 * Thirty-five-technique chart analysis over daily candles — the well-known
 * playbook of professional desks, one verdict per technique. Pure Kotlin — no
 * Android dependencies, never throws. All rolling series are index-aligned with
 * the (last <= 120) candles the analysis ran on: entry i belongs to candle i,
 * and is null until enough history exists at that index.
 * [TechniqueAnalysis.timestamps] carries the epoch millis of those same
 * candles, one per index.
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

/** One 3-candle fair value gap. [startIndex] is the first candle of the pattern. */
data class FvgZone(val startIndex: Int, val low: Double, val high: Double, val bullish: Boolean, val filled: Boolean)

data class FvgData(val closes: List<Double>, val zones: List<FvgZone>)

data class FibonacciData(
    val closes: List<Double>,
    val swingLow: Double,
    val swingHigh: Double,
    val levels: List<Pair<String, Double>>,
    /** True when the window's dominant swing is low -> high (levels retrace a rally);
     *  false when high -> low (levels measure the bounce of a decline). */
    val upswing: Boolean = true
)

/** Senkou arrays are displaced +26 and aligned to candle index; null where undefined. */
data class IchimokuData(
    val closes: List<Double>,
    val tenkan: List<Double?>,
    val kijun: List<Double?>,
    val senkouA: List<Double?>,
    val senkouB: List<Double?>
)

data class StochasticData(val k: List<Double?>, val d: List<Double?>)

data class ObvData(val obv: List<Double>)

data class AdxData(val adx: List<Double?>, val plusDi: List<Double?>, val minusDi: List<Double?>)

/** 20-day Donchian channel (the Turtle traders' breakout system). */
data class DonchianData(
    val closes: List<Double>,
    val upper: List<Double?>,
    val lower: List<Double?>,
    val middle: List<Double?>
)

/** Wilder's Parabolic SAR. [bullish] is true where the SAR sits below price. */
data class PsarData(
    val closes: List<Double>,
    val sar: List<Double?>,
    val bullish: List<Boolean?>
)

/** Money Flow Index (volume-weighted RSI), 0..100. */
data class MfiData(val mfi: List<Double?>)

/** 50-day vs 200-day moving averages — the golden/death cross regime. */
data class GoldenCrossData(
    val closes: List<Double>,
    val sma50: List<Double?>,
    val sma200: List<Double?>
)

/** Larry Williams' %R(14): -100 (close at the range low) to 0 (at the high). */
data class WilliamsRData(val r: List<Double?>)

/** Donald Lambert's Commodity Channel Index (20). */
data class CciData(val cci: List<Double?>)

/** Keltner Channels: EMA(20) middle, bands at 2 x ATR(10). */
data class KeltnerData(
    val closes: List<Double>,
    val upper: List<Double?>,
    val middle: List<Double?>,
    val lower: List<Double?>
)

/** Marc Chaikin's Money Flow (20), -1..+1. */
data class CmfData(val cmf: List<Double?>)

/** Tushar Chande's Aroon(25): up/down lines, each 0..100. */
data class AroonData(val up: List<Double?>, val down: List<Double?>)

/** Chande & Kroll's Stochastic RSI (14,14,3,3), 0..100. */
data class StochRsiData(val k: List<Double?>, val d: List<Double?>)

/** 12-day Rate of Change, in percent. */
data class RocData(val roc: List<Double?>)

/** TRIX(15): 1-bar % change of a triple-smoothed EMA, with an EMA(9) signal. */
data class TrixData(val trix: List<Double?>, val signal: List<Double?>)

/** Larry Williams' Ultimate Oscillator (7,14,28), 0..100. */
data class UltimateData(val uo: List<Double?>)

/** Botes & Siepman's Vortex Indicator (14): VI+ and VI- around the 1.0 line. */
data class VortexData(val plus: List<Double?>, val minus: List<Double?>)

/** Elder's Force Index, EMA(13)-smoothed, in price x volume units. */
data class ForceData(val force: List<Double?>)

/** Chande Momentum Oscillator (14), -100..+100. */
data class CmoData(val cmo: List<Double?>)

/** Detrended Price Oscillator (20), non-centered: close minus the mid-shifted average. */
data class DpoData(val dpo: List<Double?>)

/** Martin Pring's Know Sure Thing: four weighted smoothed ROCs plus an SMA(9) signal. */
data class KstData(val kst: List<Double?>, val signal: List<Double?>)

/** Alan Hull's moving average (20) overlay. */
data class HullData(val closes: List<Double>, val hull: List<Double?>)

/** Supertrend (10, 3x ATR): the flip line and the regime it prints. */
data class SupertrendData(
    val closes: List<Double>,
    val line: List<Double?>,
    val bullish: List<Boolean?>
)

/** Chuck LeBeau's Chandelier Exit (22, 3x ATR): long and short trailing stops. */
data class ChandelierData(
    val closes: List<Double>,
    val longStop: List<Double?>,
    val shortStop: List<Double?>
)

/** Rolling 20-day volume-weighted average price — the institutional cost basis. */
data class VwapData(val closes: List<Double>, val vwap: List<Double?>)

/** Marc Chaikin's Accumulation/Distribution line (cumulative). */
data class AdLineData(val ad: List<Double>)

/**
 * Classic floor-trader pivots computed from the last COMPLETED calendar
 * month's high, low and close. [valid] is false when the window holds no
 * completed month.
 */
data class PivotData(
    val closes: List<Double>,
    val valid: Boolean,
    val pivot: Double,
    val r1: Double,
    val r2: Double,
    val s1: Double,
    val s2: Double
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
    val timestamps: List<Long>,
    /** The exact candle window every series is aligned to, one per index. */
    val candles: List<Candle>,
    val results: List<TechniqueResult>,
    val outlook: FiveDayOutlook,
    val maData: MaTrendData,
    val rsiData: RsiData,
    val macdData: MacdData,
    val bollingerData: BollingerData,
    val srData: SupportResistanceData,
    val fvgData: FvgData,
    val fibData: FibonacciData,
    val ichimokuData: IchimokuData,
    val stochData: StochasticData,
    val obvData: ObvData,
    val adxData: AdxData,
    val donchianData: DonchianData,
    val psarData: PsarData,
    val mfiData: MfiData,
    val gcData: GoldenCrossData,
    val willrData: WilliamsRData,
    val cciData: CciData,
    val keltnerData: KeltnerData,
    val cmfData: CmfData,
    val aroonData: AroonData,
    val stochRsiData: StochRsiData,
    val rocData: RocData,
    val trixData: TrixData,
    val uoData: UltimateData,
    val vortexData: VortexData,
    val forceData: ForceData,
    val cmoData: CmoData,
    val dpoData: DpoData,
    val kstData: KstData,
    val hullData: HullData,
    val supertrendData: SupertrendData,
    val chandelierData: ChandelierData,
    val vwapData: VwapData,
    val adData: AdLineData,
    val pivotData: PivotData
)

object Techniques {

    private const val MIN_CANDLES = 30
    private const val SERIES_MAX = 120
    private const val SR_LOOKBACK = 90
    private const val SR_CLUSTER_PCT = 1.5
    private const val SR_NEAR_PCT = 2.0
    private const val FVG_NEAR_PCT = 5.0
    private const val FIB_NEAR_PCT = 1.5
    private const val ICHIMOKU_SHIFT = 26
    private const val OBV_WINDOW = 20

    private val FIB_RATIOS = listOf(
        "0.0" to 0.0, "0.236" to 0.236, "0.382" to 0.382, "0.5" to 0.5,
        "0.618" to 0.618, "0.786" to 0.786, "1.0" to 1.0
    )

    /**
     * Null when fewer than 30 daily candles are supplied. Uses the last <= 120 candles.
     *
     * [accuracyWeights] — optional measured 3-month hit rate (0..100) per technique
     * key, from [TechniqueEvaluator]. When present, each technique's outlook vote
     * is scaled by its own track record on this stock, so proven techniques speak
     * louder and coin-flip ones quieter. Verdicts and strengths are unchanged.
     */
    fun analyze(
        symbol: String,
        candles: List<Candle>,
        accuracyWeights: Map<String, Int>? = null
    ): TechniqueAnalysis? {
        if (candles.size < MIN_CANDLES) return null
        val cs = candles.takeLast(SERIES_MAX)
        val closes = cs.map { it.close }
        val timestamps = cs.map { it.ts }
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
        val fvgData = FvgData(closes, fvgZones(cs))
        val fibData = fibonacciData(cs, closes)
        val ichiData = ichimokuData(cs, closes)
        val stochData = stochasticData(cs)
        val obvData = ObvData(obvSeries(cs))
        val adxData = adxSeries(cs, 14)
        val donData = donchianData(cs, closes)
        val psarData = psarData(cs, closes)
        val mfiData = MfiData(mfiSeries(cs, 14))
        val willrData = WilliamsRData(williamsRSeries(cs, 14))
        val cciData = CciData(cciSeries(cs, 20))
        val keltnerData = keltnerData(cs, closes)
        val cmfData = CmfData(cmfSeries(cs, 20))
        val aroonData = aroonData(cs, 25)
        val stochRsiData = stochRsiData(closes)
        val rocData = RocData(rocSeries(closes, 12))
        val trixData = trixData(closes)
        val uoData = UltimateData(ultimateSeries(cs))
        val vortexData = vortexData(cs, 14)
        val forceData = ForceData(forceSeries(cs, 13))
        val cmoData = CmoData(cmoSeries(closes, 14))
        val dpoData = DpoData(dpoSeries(closes, 20))
        val kstData = kstData(closes)
        val hullData = HullData(closes, hullSeries(closes, 20))
        val supertrendData = supertrendData(cs, closes)
        val chandelierData = chandelierData(cs, closes)
        val vwapData = VwapData(closes, vwapSeries(cs, 20))
        val adData = AdLineData(adLineSeries(cs))
        val pivotData = pivotData(cs, closes)
        // Golden cross needs history beyond the display window: compute the
        // 50/200 averages over the FULL candle list, then trim to the window.
        val fullCloses = candles.map { it.close }
        val offset = fullCloses.size - n
        val gcData = GoldenCrossData(
            closes = closes,
            sma50 = smaSeries(fullCloses, 50).drop(offset),
            sma200 = smaSeries(fullCloses, 200).drop(offset)
        )

        val results = listOf(
            maResult(n, price, sma20, sma50),
            rsiResult(n, rsiS),
            macdResult(n, price, macdS),
            bollingerResult(price, bollS),
            srResult(price, supports, resistances),
            fvgResult(price, fvgData.zones),
            fibResult(price, fibData),
            ichimokuResult(n, price, ichiData),
            stochResult(n, stochData),
            obvResult(closes, obvData.obv),
            adxResult(n, adxData),
            donchianResult(price, donData),
            psarResult(price, psarData),
            mfiResult(n, mfiData),
            gcResult(candles.size, price, gcData),
            willrResult(n, willrData),
            cciResult(n, cciData),
            keltnerResult(price, keltnerData),
            cmfResult(n, cmfData),
            aroonResult(n, aroonData),
            stochRsiResult(n, stochRsiData),
            rocResult(n, rocData),
            trixResult(n, trixData),
            uoResult(n, uoData),
            vortexResult(n, vortexData),
            forceResult(n, forceData, price),
            cmoResult(n, cmoData),
            dpoResult(n, price, dpoData),
            kstResult(n, kstData),
            hullResult(price, hullData),
            supertrendResult(price, supertrendData),
            chandelierResult(price, chandelierData),
            vwapResult(price, vwapData),
            adResult(closes, adData.ad),
            pivotResult(price, pivotData)
        )

        val outlook = buildOutlook(cs, price, results, supports, resistances, accuracyWeights)

        return TechniqueAnalysis(
            symbol = symbol,
            timestamps = timestamps,
            candles = cs,
            results = results,
            outlook = outlook,
            maData = maData,
            rsiData = rsiData,
            macdData = macdData,
            bollingerData = bollData,
            srData = srData,
            fvgData = fvgData,
            fibData = fibData,
            ichimokuData = ichiData,
            stochData = stochData,
            obvData = obvData,
            adxData = adxData,
            donchianData = donData,
            psarData = psarData,
            mfiData = mfiData,
            gcData = gcData,
            willrData = willrData,
            cciData = cciData,
            keltnerData = keltnerData,
            cmfData = cmfData,
            aroonData = aroonData,
            stochRsiData = stochRsiData,
            rocData = rocData,
            trixData = trixData,
            uoData = uoData,
            vortexData = vortexData,
            forceData = forceData,
            cmoData = cmoData,
            dpoData = dpoData,
            kstData = kstData,
            hullData = hullData,
            supertrendData = supertrendData,
            chandelierData = chandelierData,
            vwapData = vwapData,
            adData = adData,
            pivotData = pivotData
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
            TechniqueVerdict.NEUTRAL -> {
                // NEUTRAL also covers price outside BOTH averages with a
                // crossed 20/50 — only claim "between" when it is true.
                val position = when {
                    price > maxOf(s20, s50) ->
                        "holds above both the 20-day ${Fmt.money(s20)} and 50-day ${Fmt.money(s50)}, but the averages are crossed"
                    price < minOf(s20, s50) ->
                        "sits below both the 20-day ${Fmt.money(s20)} and 50-day ${Fmt.money(s50)}, but the averages are crossed"
                    else ->
                        "sits between the 20-day ${Fmt.money(s20)} and 50-day ${Fmt.money(s50)}"
                }
                "Close ${Fmt.money(price)} $position; no aligned trend.$crossNote"
            }
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
        val prev = if (rsiS.size >= 2) rsiS[rsiS.size - 2] else null
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
            // The turn back THROUGH the line is the higher-probability signal:
            // an oversold read can stay oversold, but the recross confirms the
            // sellers (or buyers) actually let go.
            prev != null && prev < 30.0 -> TechniqueResult(
                "rsi", name, TechniqueVerdict.BULLISH, 68,
                "RSI(14) crossed back up through 30 — now ${fmt0(r)} from ${fmt0(prev)}; the oversold turn is confirmed."
            )
            prev != null && prev > 70.0 -> TechniqueResult(
                "rsi", name, TechniqueVerdict.BEARISH, 68,
                "RSI(14) rolled back under 70 — now ${fmt0(r)} from ${fmt0(prev)}; the overbought turn is confirmed."
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

    // -- technique 6: fair value gap ----------------------------------------

    /**
     * 3-candle gaps over the window. Bullish: high[i-2] < low[i], zone
     * [high[i-2], low[i]]; bearish mirrored. A zone is filled when a later
     * candle trades fully through it. Keeps the 12 most recent zones.
     */
    private fun fvgZones(candles: List<Candle>): List<FvgZone> {
        val n = candles.size
        val zones = ArrayList<FvgZone>()
        for (i in 2 until n) {
            val prevHigh = candles[i - 2].high
            val prevLow = candles[i - 2].low
            val curLow = candles[i].low
            val curHigh = candles[i].high
            if (prevHigh < curLow) {
                var filled = false
                for (j in i + 1 until n) {
                    if (candles[j].low <= prevHigh) { filled = true; break }
                }
                zones.add(FvgZone(i - 2, prevHigh, curLow, bullish = true, filled = filled))
            }
            if (prevLow > curHigh) {
                var filled = false
                for (j in i + 1 until n) {
                    if (candles[j].high >= prevLow) { filled = true; break }
                }
                zones.add(FvgZone(i - 2, curHigh, prevLow, bullish = false, filled = filled))
            }
        }
        return if (zones.size > 12) zones.takeLast(12) else zones
    }

    private fun fvgResult(price: Double, zones: List<FvgZone>): TechniqueResult {
        val name = "Fair value gap"
        if (zones.isEmpty()) {
            return TechniqueResult(
                "fvg", name, TechniqueVerdict.NEUTRAL, 25,
                "No 3-candle gaps in the window around ${Fmt.money(price)}."
            )
        }
        val unfilled = zones.filter { !it.filled }
        val below = unfilled.filter { it.bullish && it.high <= price }.maxByOrNull { it.high }
        val above = unfilled.filter { !it.bullish && it.low >= price }.minByOrNull { it.low }
        val belowPct = below?.let { (price - it.high) / price * 100.0 }
        val abovePct = above?.let { (it.low - price) / price * 100.0 }
        val nearBelow = belowPct != null && belowPct <= FVG_NEAR_PCT
        val nearAbove = abovePct != null && abovePct <= FVG_NEAR_PCT

        // When both sides qualify, the closer zone decides.
        val pickBelow = nearBelow && (!nearAbove || belowPct!! <= abovePct!!)
        val pickAbove = nearAbove && !pickBelow

        return when {
            pickBelow -> TechniqueResult(
                "fvg", name, TechniqueVerdict.BULLISH,
                (55.0 + (FVG_NEAR_PCT - belowPct!!) * 7.0).roundToInt().coerceIn(55, 90),
                "Unfilled bullish gap ${Fmt.money(below!!.low)}–${Fmt.money(below.high)} sits ${fmt1(belowPct)}% below price."
            )
            pickAbove -> TechniqueResult(
                "fvg", name, TechniqueVerdict.BEARISH,
                (55.0 + (FVG_NEAR_PCT - abovePct!!) * 7.0).roundToInt().coerceIn(55, 90),
                "Unfilled bearish gap ${Fmt.money(above!!.low)}–${Fmt.money(above.high)} sits ${fmt1(abovePct)}% above price."
            )
            else -> TechniqueResult(
                "fvg", name, TechniqueVerdict.NEUTRAL, 30,
                "No unfilled gap within ${fmt0(FVG_NEAR_PCT)}% of ${Fmt.money(price)}; " +
                    "${zones.size} ${zones(zones.size)} tracked, ${unfilled.size} unfilled."
            )
        }
    }

    // -- technique 7: Fibonacci retracement ---------------------------------

    /**
     * Direction-aware retracement. When the swing runs low -> high (the low
     * came first), level r sits at high - r * range: pullbacks of a rally.
     * When the high came first, level r sits at low + r * range: bounces of a
     * decline. The old behavior always assumed a rally, which misread every
     * downtrending window.
     */
    private fun fibonacciData(candles: List<Candle>, closes: List<Double>): FibonacciData {
        var swingLow = Double.MAX_VALUE
        var swingHigh = -Double.MAX_VALUE
        var lowIdx = 0
        var highIdx = 0
        candles.forEachIndexed { i, c ->
            if (c.low < swingLow) {
                swingLow = c.low
                lowIdx = i
            }
            if (c.high > swingHigh) {
                swingHigh = c.high
                highIdx = i
            }
        }
        val upswing = highIdx >= lowIdx
        val range = swingHigh - swingLow
        val levels = FIB_RATIOS.map { (label, r) ->
            label to if (upswing) swingHigh - r * range else swingLow + r * range
        }
        return FibonacciData(closes, swingLow, swingHigh, levels, upswing)
    }

    private fun fibResult(price: Double, fib: FibonacciData): TechniqueResult {
        val name = "Fibonacci retracement"
        val range = fib.swingHigh - fib.swingLow
        if (range <= 0.0 || fib.swingHigh <= 0.0) {
            return TechniqueResult(
                "fib", name, TechniqueVerdict.NEUTRAL, 20,
                "Window is flat at ${Fmt.money(price)}; no swing to retrace."
            )
        }
        if (!fib.upswing) return fibDownswingResult(price, fib)
        val level = fib.levels.toMap()
        val l236 = level.getValue("0.236")
        val l382 = level.getValue("0.382")
        val l50 = level.getValue("0.5")
        val l618 = level.getValue("0.618")
        val l786 = level.getValue("0.786")

        fun distPct(l: Double): Double = if (l > 0.0) abs(price - l) / l * 100.0 else Double.MAX_VALUE
        val dipLevels = listOf("0.382" to l382, "0.5" to l50, "0.618" to l618)
        val nearestDip = dipLevels.minByOrNull { distPct(it.second) }!!
        val dipDist = distPct(nearestDip.second)

        return when {
            price > l618 && dipDist <= FIB_NEAR_PCT -> TechniqueResult(
                "fib", name, TechniqueVerdict.BULLISH,
                (65.0 + (FIB_NEAR_PCT - dipDist) * 10.0).roundToInt().coerceIn(65, 80),
                "Price ${Fmt.money(price)} holds the ${nearestDip.first} retracement at ${Fmt.money(nearestDip.second)} " +
                    "of the ${Fmt.money(fib.swingLow)}–${Fmt.money(fib.swingHigh)} swing."
            )
            price < l786 -> {
                val depth = if (l786 > 0.0) (l786 - price) / l786 * 100.0 else 0.0
                TechniqueResult(
                    "fib", name, TechniqueVerdict.BEARISH,
                    (70.0 + depth * 2.0).roundToInt().coerceIn(70, 90),
                    "Price ${Fmt.money(price)} is ${fmt1(depth)}% under the 0.786 level ${Fmt.money(l786)}; " +
                        "the ${Fmt.money(fib.swingLow)}–${Fmt.money(fib.swingHigh)} retracement failed."
                )
            }
            price >= l236 || distPct(l236) <= FIB_NEAR_PCT -> TechniqueResult(
                "fib", name, TechniqueVerdict.BULLISH, 50,
                "Price ${Fmt.money(price)} holds at or above the 0.236 level ${Fmt.money(l236)}; shallow pullback keeps momentum."
            )
            else -> {
                val nearest = fib.levels.minByOrNull { distPct(it.second) }!!
                val nd = distPct(nearest.second)
                // The nearest level CAN be inside the tag distance here — the
                // bullish "holds the dip" case only counts approaches from
                // above; from below it is a test, not a hold.
                val note = if (nd <= FIB_NEAR_PCT) {
                    "${fmt1(nd)}% away, testing it from below — no hold confirmed"
                } else {
                    "no level within ${fmt1(FIB_NEAR_PCT)}%"
                }
                TechniqueResult(
                    "fib", name, TechniqueVerdict.NEUTRAL, 30,
                    "Price ${Fmt.money(price)} sits nearest the ${nearest.first} level " +
                        "${Fmt.money(nearest.second)}; $note."
                )
            }
        }
    }

    /**
     * The window's dominant move is a decline (high came before low): the
     * levels measure how much of the drop has been retraced by the bounce.
     * Rallies into the 0.382-0.618 zone of a decline are routinely sold;
     * reclaiming 0.618+ argues the downtrend is breaking.
     */
    private fun fibDownswingResult(price: Double, fib: FibonacciData): TechniqueResult {
        val name = "Fibonacci retracement"
        val level = fib.levels.toMap()
        val l236 = level.getValue("0.236")
        val l382 = level.getValue("0.382")
        val l618 = level.getValue("0.618")
        val l786 = level.getValue("0.786")
        val swing = "${Fmt.money(fib.swingHigh)}–${Fmt.money(fib.swingLow)} decline"
        return when {
            price >= l786 -> TechniqueResult(
                "fib", name, TechniqueVerdict.BULLISH, 75,
                "Price ${Fmt.money(price)} reclaimed the 0.786 level ${Fmt.money(l786)} of the $swing; the drop is nearly unwound."
            )
            price > l618 -> TechniqueResult(
                "fib", name, TechniqueVerdict.BULLISH, 60,
                "Price ${Fmt.money(price)} is above the 0.618 bounce level ${Fmt.money(l618)} of the $swing — the downtrend is breaking."
            )
            price < l236 -> TechniqueResult(
                "fib", name, TechniqueVerdict.BEARISH, 72,
                "Price ${Fmt.money(price)} has retraced under 23.6% (${Fmt.money(l236)}) of the $swing — barely a bounce."
            )
            price in l382..l618 -> TechniqueResult(
                "fib", name, TechniqueVerdict.BEARISH, 55,
                "Price ${Fmt.money(price)} sits in the 0.382–0.618 bounce zone (${Fmt.money(l382)}–${Fmt.money(l618)}) of the $swing, where rallies in a downtrend usually get sold."
            )
            else -> TechniqueResult(
                "fib", name, TechniqueVerdict.NEUTRAL, 30,
                "Price ${Fmt.money(price)} is between bounce levels of the $swing; no zone tagged."
            )
        }
    }

    // -- technique 8: Ichimoku Cloud ----------------------------------------

    /** Tenkan(9), kijun(26), senkou spans displaced +26; the tail beyond the last candle is dropped. */
    private fun ichimokuData(candles: List<Candle>, closes: List<Double>): IchimokuData {
        val n = candles.size
        val tenkan = midSeries(candles, 9)
        val kijun = midSeries(candles, 26)
        val rawB = midSeries(candles, 52)
        val senkouA = arrayOfNulls<Double>(n)
        val senkouB = arrayOfNulls<Double>(n)
        for (i in ICHIMOKU_SHIFT until n) {
            val src = i - ICHIMOKU_SHIFT
            val t = tenkan[src]
            val k = kijun[src]
            if (t != null && k != null) senkouA[i] = (t + k) / 2.0
            senkouB[i] = rawB[src]
        }
        return IchimokuData(closes, tenkan, kijun, senkouA.toList(), senkouB.toList())
    }

    private fun ichimokuResult(n: Int, price: Double, data: IchimokuData): TechniqueResult {
        val name = "Ichimoku Cloud"
        val t = data.tenkan.last()
        val k = data.kijun.last()
        val a = data.senkouA.last()
        val b = data.senkouB.last()
        if (t == null || k == null || a == null || b == null) {
            return TechniqueResult(
                "ichimoku", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 78 daily candles for the displaced cloud; $n available."
            )
        }
        val cloudTop = max(a, b)
        val cloudBottom = min(a, b)
        return when {
            price > cloudTop && t > k -> {
                val distPct = if (cloudTop > 0.0) (price - cloudTop) / cloudTop * 100.0 else 0.0
                TechniqueResult(
                    "ichimoku", name, TechniqueVerdict.BULLISH,
                    (70.0 + distPct * 3.0).roundToInt().coerceIn(70, 92),
                    "Close ${Fmt.money(price)} is ${fmt1(distPct)}% above the ${Fmt.money(cloudBottom)}–${Fmt.money(cloudTop)} cloud; " +
                        "tenkan ${Fmt.money(t)} over kijun ${Fmt.money(k)}."
                )
            }
            price < cloudBottom -> {
                val distPct = if (cloudBottom > 0.0) (cloudBottom - price) / cloudBottom * 100.0 else 0.0
                TechniqueResult(
                    "ichimoku", name, TechniqueVerdict.BEARISH,
                    (70.0 + distPct * 3.0).roundToInt().coerceIn(70, 92),
                    "Close ${Fmt.money(price)} is ${fmt1(distPct)}% below the ${Fmt.money(cloudBottom)}–${Fmt.money(cloudTop)} cloud."
                )
            }
            price > cloudTop -> TechniqueResult(
                "ichimoku", name, TechniqueVerdict.NEUTRAL, 40,
                "Close ${Fmt.money(price)} is above the ${Fmt.money(cloudBottom)}–${Fmt.money(cloudTop)} cloud, " +
                    "but tenkan ${Fmt.money(t)} under kijun ${Fmt.money(k)} withholds confirmation."
            )
            else -> TechniqueResult(
                "ichimoku", name, TechniqueVerdict.NEUTRAL, 30,
                "Close ${Fmt.money(price)} is inside the ${Fmt.money(cloudBottom)}–${Fmt.money(cloudTop)} cloud; no trend signal."
            )
        }
    }

    // -- technique 9: stochastic oscillator ---------------------------------

    /** Slow stochastic: raw %K(14), smoothed over 3; %D = SMA3 of the smoothed K. */
    private fun stochasticData(candles: List<Candle>): StochasticData {
        val n = candles.size
        val rawK = arrayOfNulls<Double>(n)
        val hh = highSeries(candles, 14)
        val ll = lowSeries(candles, 14)
        for (i in 13 until n) {
            val h = hh[i] ?: continue
            val l = ll[i] ?: continue
            val range = h - l
            rawK[i] = if (range > 0.0) (candles[i].close - l) / range * 100.0 else 50.0
        }
        val k = sma3Nullable(rawK.toList())
        val d = sma3Nullable(k)
        return StochasticData(k, d)
    }

    private fun stochResult(n: Int, data: StochasticData): TechniqueResult {
        val name = "Stochastic oscillator"
        val k = data.k.last()
        val d = data.d.last()
        if (k == null || d == null) {
            return TechniqueResult(
                "stoch", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 18 daily candles for stochastic(14,3,3); $n available."
            )
        }
        return when {
            k < 20.0 && k >= d -> TechniqueResult(
                "stoch", name, TechniqueVerdict.BULLISH,
                (70.0 + (20.0 - k)).roundToInt().coerceIn(70, 90),
                "%K ${fmt0(k)} crossing up over %D ${fmt0(d)} in the sub-20 oversold zone."
            )
            k > 80.0 && k <= d -> TechniqueResult(
                "stoch", name, TechniqueVerdict.BEARISH,
                (70.0 + (k - 80.0)).roundToInt().coerceIn(70, 90),
                "%K ${fmt0(k)} rolling under %D ${fmt0(d)} in the over-80 overbought zone."
            )
            else -> {
                val zone = when {
                    k < 20.0 -> " in the oversold zone, no turn yet"
                    k > 80.0 -> " in the overbought zone, no turn yet"
                    else -> " mid-range"
                }
                val dir = if (k >= d) "rising" else "easing"
                TechniqueResult(
                    "stoch", name, TechniqueVerdict.NEUTRAL, 30,
                    "%K ${fmt0(k)} vs %D ${fmt0(d)}, $dir$zone."
                )
            }
        }
    }

    // -- technique 10: on-balance volume ------------------------------------

    /** Cumulative OBV starting at 0: volume added on up closes, subtracted on down closes. */
    private fun obvSeries(candles: List<Candle>): List<Double> {
        val n = candles.size
        val out = ArrayList<Double>(n)
        var obv = 0.0
        out.add(obv)
        for (i in 1 until n) {
            val d = candles[i].close - candles[i - 1].close
            if (d > 0.0) obv += candles[i].volume.toDouble()
            else if (d < 0.0) obv -= candles[i].volume.toDouble()
            out.add(obv)
        }
        return out
    }

    private fun obvResult(closes: List<Double>, obv: List<Double>): TechniqueResult {
        val name = "On-balance volume"
        val n = closes.size
        val window = min(OBV_WINDOW, n)
        if (window < 3) {
            return TechniqueResult(
                "obv", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs more history for the $OBV_WINDOW-bar OBV read; $n available."
            )
        }
        val slope = lsSlope(obv.takeLast(window))
        val ref = closes[n - window]
        val priceChgPct = if (ref > 0.0) (closes.last() - ref) / ref * 100.0 else 0.0
        val obvDelta = obv.last() - obv[n - window]
        val priceTxt = when {
            priceChgPct > 0.05 -> "up ${fmt1(priceChgPct)}%"
            priceChgPct < -0.05 -> "down ${fmt1(-priceChgPct)}%"
            else -> "flat"
        }

        // The verdict is slope-driven; the printed net change must carry its
        // OWN sign — an early dip can leave the endpoint delta negative even
        // while the fitted trend is up (and vice versa).
        val netTxt = (if (obvDelta >= 0.0) "+" else "-") + Fmt.compact(abs(obvDelta))
        return when {
            slope > 0.0 && priceChgPct <= 0.0 -> TechniqueResult(
                "obv", name, TechniqueVerdict.BULLISH,
                (70.0 + min(15.0, abs(priceChgPct) * 2.0)).roundToInt().coerceIn(70, 85),
                "OBV trending higher over $window bars (net $netTxt) with price $priceTxt: accumulation divergence."
            )
            slope < 0.0 && priceChgPct > 0.0 -> TechniqueResult(
                "obv", name, TechniqueVerdict.BEARISH,
                (70.0 + min(15.0, priceChgPct * 2.0)).roundToInt().coerceIn(70, 85),
                "OBV trending lower over $window bars (net $netTxt) with price $priceTxt: distribution divergence."
            )
            priceChgPct > 0.0 -> TechniqueResult(
                "obv", name, TechniqueVerdict.BULLISH, 50,
                "OBV and price both up over $window bars ($priceTxt): volume confirms the move."
            )
            priceChgPct < 0.0 && slope < 0.0 -> TechniqueResult(
                "obv", name, TechniqueVerdict.BEARISH, 50,
                "OBV and price both down over $window bars ($priceTxt): volume confirms the slide."
            )
            else -> TechniqueResult(
                "obv", name, TechniqueVerdict.NEUTRAL, 30,
                "OBV and price both near flat over $window bars; no volume signal."
            )
        }
    }

    // -- technique 11: ADX trend strength -----------------------------------

    /** Wilder ADX(14) with +DI/-DI. DI series start at index [period], ADX at 2*[period]-1. */
    private fun adxSeries(candles: List<Candle>, period: Int): AdxData {
        val n = candles.size
        val plusDi = arrayOfNulls<Double>(n)
        val minusDi = arrayOfNulls<Double>(n)
        val adx = arrayOfNulls<Double>(n)
        if (n < period + 1) return AdxData(adx.toList(), plusDi.toList(), minusDi.toList())

        val tr = DoubleArray(n)
        val pdm = DoubleArray(n)
        val mdm = DoubleArray(n)
        for (i in 1 until n) {
            val h = candles[i].high
            val l = candles[i].low
            val pc = candles[i - 1].close
            tr[i] = max(h - l, max(abs(h - pc), abs(l - pc)))
            val up = h - candles[i - 1].high
            val down = candles[i - 1].low - l
            pdm[i] = if (up > down && up > 0.0) up else 0.0
            mdm[i] = if (down > up && down > 0.0) down else 0.0
        }

        var trS = 0.0
        var pS = 0.0
        var mS = 0.0
        for (i in 1..period) {
            trS += tr[i]
            pS += pdm[i]
            mS += mdm[i]
        }
        val dx = arrayOfNulls<Double>(n)
        fun setAt(i: Int) {
            if (trS > 0.0) {
                val p = 100.0 * pS / trS
                val m = 100.0 * mS / trS
                plusDi[i] = p
                minusDi[i] = m
                val sum = p + m
                dx[i] = if (sum > 0.0) 100.0 * abs(p - m) / sum else 0.0
            } else {
                plusDi[i] = 0.0
                minusDi[i] = 0.0
                dx[i] = 0.0
            }
        }
        setAt(period)
        for (i in period + 1 until n) {
            trS = trS - trS / period + tr[i]
            pS = pS - pS / period + pdm[i]
            mS = mS - mS / period + mdm[i]
            setAt(i)
        }
        if (n >= 2 * period) {
            var sum = 0.0
            for (i in period until 2 * period) sum += dx[i] ?: 0.0
            var a = sum / period
            adx[2 * period - 1] = a
            for (i in 2 * period until n) {
                a = (a * (period - 1) + (dx[i] ?: 0.0)) / period
                adx[i] = a
            }
        }
        return AdxData(adx.toList(), plusDi.toList(), minusDi.toList())
    }

    private fun adxResult(n: Int, data: AdxData): TechniqueResult {
        val name = "ADX trend strength"
        val a = data.adx.last()
        val p = data.plusDi.last()
        val m = data.minusDi.last()
        if (a == null || p == null || m == null) {
            return TechniqueResult(
                "adx", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 28 daily candles for ADX(14); $n available."
            )
        }
        return when {
            a > 25.0 && p > m -> TechniqueResult(
                "adx", name, TechniqueVerdict.BULLISH,
                (70.0 + (a - 25.0) + (p - m) * 0.3).roundToInt().coerceIn(70, 95),
                "ADX ${fmt0(a)} with +DI ${fmt0(p)} over -DI ${fmt0(m)}: strong uptrend."
            )
            a > 25.0 && m > p -> TechniqueResult(
                "adx", name, TechniqueVerdict.BEARISH,
                (70.0 + (a - 25.0) + (m - p) * 0.3).roundToInt().coerceIn(70, 95),
                "ADX ${fmt0(a)} with -DI ${fmt0(m)} over +DI ${fmt0(p)}: strong downtrend."
            )
            a < 20.0 -> TechniqueResult(
                "adx", name, TechniqueVerdict.NEUTRAL, 25,
                "ADX ${fmt0(a)}: no trend; +DI ${fmt0(p)} vs -DI ${fmt0(m)}."
            )
            else -> TechniqueResult(
                "adx", name, TechniqueVerdict.NEUTRAL, 35,
                "ADX ${fmt0(a)} with +DI ${fmt0(p)} vs -DI ${fmt0(m)}; trend not confirmed."
            )
        }
    }

    // -- technique 12: Donchian channel (the Turtle system) ------------------

    private fun donchianData(candles: List<Candle>, closes: List<Double>): DonchianData {
        val upper = highSeries(candles, 20)
        val lower = lowSeries(candles, 20)
        val middle = List(candles.size) { i ->
            val u = upper[i]
            val l = lower[i]
            if (u != null && l != null) (u + l) / 2.0 else null
        }
        return DonchianData(closes, upper, lower, middle)
    }

    private fun donchianResult(price: Double, d: DonchianData): TechniqueResult {
        val name = "Donchian channel"
        val n = d.closes.size
        // Breakouts are tested against the PRIOR bar's channel so today's own
        // extreme cannot cap the signal.
        val prevUpper = if (n >= 2) d.upper[n - 2] else null
        val prevLower = if (n >= 2) d.lower[n - 2] else null
        val upper = d.upper.lastOrNull()
        val lower = d.lower.lastOrNull()
        if (prevUpper == null || prevLower == null || upper == null || lower == null) {
            return TechniqueResult(
                "donchian", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 21 daily candles for the 20-day channel; $n available."
            )
        }
        return when {
            price > prevUpper -> {
                val margin = if (prevUpper > 0.0) (price - prevUpper) / prevUpper * 100.0 else 0.0
                TechniqueResult(
                    "donchian", name, TechniqueVerdict.BULLISH,
                    (65.0 + margin * 8.0).roundToInt().coerceIn(65, 95),
                    "Close ${Fmt.money(price)} broke the 20-day channel top ${Fmt.money(prevUpper)} " +
                        "by ${fmt1(margin)}% — the Turtle traders' buy signal."
                )
            }
            price < prevLower -> {
                val margin = if (prevLower > 0.0) (prevLower - price) / prevLower * 100.0 else 0.0
                TechniqueResult(
                    "donchian", name, TechniqueVerdict.BEARISH,
                    (65.0 + margin * 8.0).roundToInt().coerceIn(65, 95),
                    "Close ${Fmt.money(price)} broke the 20-day channel floor ${Fmt.money(prevLower)} " +
                        "by ${fmt1(margin)}% — the Turtle exit/short signal."
                )
            }
            else -> {
                val width = upper - lower
                val pos = if (width > 1e-9) (price - lower) / width * 100.0 else 50.0
                TechniqueResult(
                    "donchian", name, TechniqueVerdict.NEUTRAL, 30,
                    "Close ${Fmt.money(price)} sits at ${fmt0(pos)}% of the " +
                        "${Fmt.money(lower)}–${Fmt.money(upper)} channel; no breakout."
                )
            }
        }
    }

    // -- technique 13: Parabolic SAR -----------------------------------------

    /** Wilder's Parabolic SAR, af 0.02 step to 0.2 max, seeded from the first bars. */
    private fun psarData(candles: List<Candle>, closes: List<Double>): PsarData {
        val n = candles.size
        val sar = arrayOfNulls<Double>(n)
        val bull = arrayOfNulls<Boolean>(n)
        if (n < 5) return PsarData(closes, sar.toList(), bull.toList())

        var uptrend = candles[1].close >= candles[0].close
        var sarV = if (uptrend) candles[0].low else candles[0].high
        var ep = if (uptrend) candles[0].high else candles[0].low
        var af = 0.02
        for (i in 1 until n) {
            sarV += af * (ep - sarV)
            if (uptrend) {
                sarV = min(sarV, candles[i - 1].low)
                if (i >= 2) sarV = min(sarV, candles[i - 2].low)
                if (candles[i].low < sarV) {
                    uptrend = false
                    sarV = ep
                    ep = candles[i].low
                    af = 0.02
                } else if (candles[i].high > ep) {
                    ep = candles[i].high
                    af = min(0.2, af + 0.02)
                }
            } else {
                sarV = max(sarV, candles[i - 1].high)
                if (i >= 2) sarV = max(sarV, candles[i - 2].high)
                if (candles[i].high > sarV) {
                    uptrend = true
                    sarV = ep
                    ep = candles[i].high
                    af = 0.02
                } else if (candles[i].low < ep) {
                    ep = candles[i].low
                    af = min(0.2, af + 0.02)
                }
            }
            sar[i] = sarV
            bull[i] = uptrend
        }
        return PsarData(closes, sar.toList(), bull.toList())
    }

    private fun psarResult(price: Double, d: PsarData): TechniqueResult {
        val name = "Parabolic SAR"
        val lastIdx = d.sar.indexOfLast { it != null }
        if (lastIdx < 1) {
            return TechniqueResult(
                "psar", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 5 daily candles to seed the SAR; ${d.closes.size} available."
            )
        }
        val sarV = d.sar[lastIdx] ?: return TechniqueResult(
            "psar", name, TechniqueVerdict.NEUTRAL, 20, "SAR undefined for this window."
        )
        val up = d.bullish[lastIdx] == true
        var run = 0
        var i = lastIdx
        while (i >= 1 && d.bullish[i] == d.bullish[lastIdx]) {
            run++
            i--
        }
        val fresh = run <= 3
        val distPct = if (price > 0.0) abs(price - sarV) / price * 100.0 else 0.0
        // run counts the bars INSIDE the current regime, flip bar included —
        // the flip itself happened run-1 bars ago.
        val flippedAgo = run - 1
        val freshNote =
            if (fresh) {
                " Flipped ${if (flippedAgo == 0) "this bar" else "$flippedAgo ${bars(flippedAgo)} ago"} — a fresh signal."
            } else {
                " The trend has run $run ${bars(run)}."
            }
        return if (up) {
            TechniqueResult(
                "psar", name, TechniqueVerdict.BULLISH,
                (58.0 + (if (fresh) 15.0 else 5.0) + min(10.0, distPct * 2.0)).roundToInt().coerceIn(58, 92),
                "SAR ${Fmt.money(sarV)} trails ${fmt1(distPct)}% below price — dots under the bar keep the uptrend intact.$freshNote"
            )
        } else {
            TechniqueResult(
                "psar", name, TechniqueVerdict.BEARISH,
                (58.0 + (if (fresh) 15.0 else 5.0) + min(10.0, distPct * 2.0)).roundToInt().coerceIn(58, 92),
                "SAR ${Fmt.money(sarV)} sits ${fmt1(distPct)}% above price — dots over the bar keep the downtrend intact.$freshNote"
            )
        }
    }

    // -- technique 14: Money Flow Index --------------------------------------

    /** Rolling MFI over [period] bars: volume-weighted RSI on the typical price. */
    private fun mfiSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (n < period + 1) return out.toList()
        val tp = DoubleArray(n) { (candles[it].high + candles[it].low + candles[it].close) / 3.0 }
        val flow = DoubleArray(n)
        for (i in 1 until n) {
            val mf = tp[i] * candles[i].volume.toDouble()
            flow[i] = when {
                tp[i] > tp[i - 1] -> mf
                tp[i] < tp[i - 1] -> -mf
                else -> 0.0
            }
        }
        for (i in period until n) {
            var pos = 0.0
            var neg = 0.0
            for (j in i - period + 1..i) {
                if (flow[j] > 0.0) pos += flow[j] else neg -= flow[j]
            }
            out[i] = when {
                pos == 0.0 && neg == 0.0 -> 50.0
                neg == 0.0 -> 100.0
                else -> 100.0 - 100.0 / (1.0 + pos / neg)
            }
        }
        return out.toList()
    }

    private fun mfiResult(n: Int, d: MfiData): TechniqueResult {
        val name = "Money Flow Index"
        val m = d.mfi.lastOrNull()
            ?: return TechniqueResult(
                "mfi", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 15 daily candles for MFI(14); $n available."
            )
        val prev5 = if (d.mfi.size >= 6) d.mfi[d.mfi.size - 6] else null
        val trendNote = prev5?.let { " Five bars ago it was ${fmt0(it)}." } ?: ""
        return when {
            m < 20.0 -> TechniqueResult(
                "mfi", name, TechniqueVerdict.BULLISH,
                (55.0 + (20.0 - m) * 2.0).roundToInt().coerceIn(55, 90),
                "MFI(14) at ${fmt0(m)} — dollar-volume selling is exhausted (under the 20 line).$trendNote"
            )
            m > 80.0 -> TechniqueResult(
                "mfi", name, TechniqueVerdict.BEARISH,
                (55.0 + (m - 80.0) * 2.0).roundToInt().coerceIn(55, 90),
                "MFI(14) at ${fmt0(m)} — dollar-volume buying is stretched (over the 80 line).$trendNote"
            )
            else -> TechniqueResult(
                "mfi", name, TechniqueVerdict.NEUTRAL, 30,
                "MFI(14) at ${fmt0(m)}, inside the 20–80 band.$trendNote"
            )
        }
    }

    // -- technique 15: golden cross 50/200 -----------------------------------

    private fun gcResult(totalCandles: Int, price: Double, d: GoldenCrossData): TechniqueResult {
        val name = "Golden cross 50/200"
        val s50 = d.sma50.lastOrNull()
        val s200 = d.sma200.lastOrNull()
        if (s50 == null || s200 == null) {
            return TechniqueResult(
                "gc", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 200 daily candles for the 200-day average; $totalCandles available."
            )
        }
        // Most recent 50/200 cross within the last 15 window bars, if any.
        var crossAgo: Int? = null
        var i = d.sma50.size - 1
        var steps = 0
        while (i >= 1 && steps < 15) {
            val a = d.sma50[i]
            val b = d.sma200[i]
            val pa = d.sma50[i - 1]
            val pb = d.sma200[i - 1]
            if (a != null && b != null && pa != null && pb != null) {
                val now = a - b
                val prev = pa - pb
                if (now != 0.0 && prev != 0.0 && (now > 0.0) != (prev > 0.0)) {
                    crossAgo = d.sma50.size - 1 - i
                    break
                }
            }
            i--
            steps++
        }
        val gapPct = if (s200 != 0.0) (s50 - s200) / s200 * 100.0 else 0.0
        return when {
            s50 > s200 && price > s200 -> {
                val crossNote = crossAgo?.let {
                    " Golden cross ${if (it == 0) "this bar" else "$it ${bars(it)} ago"}."
                } ?: ""
                TechniqueResult(
                    "gc", name, TechniqueVerdict.BULLISH,
                    (60.0 + min(15.0, gapPct * 3.0) + (if (crossAgo != null) 15.0 else 0.0))
                        .roundToInt().coerceIn(60, 92),
                    "50-day ${Fmt.money(s50)} rides ${fmt1(abs(gapPct))}% above the 200-day ${Fmt.money(s200)} " +
                        "with price above both — the long-term uptrend regime.$crossNote"
                )
            }
            s50 < s200 -> {
                val crossNote = crossAgo?.let {
                    " Death cross ${if (it == 0) "this bar" else "$it ${bars(it)} ago"}."
                } ?: ""
                val below = price < s200
                TechniqueResult(
                    "gc", name, TechniqueVerdict.BEARISH,
                    (55.0 + min(15.0, abs(gapPct) * 3.0) + (if (below) 10.0 else 0.0))
                        .roundToInt().coerceIn(55, 90),
                    "50-day ${Fmt.money(s50)} sits ${fmt1(abs(gapPct))}% below the 200-day ${Fmt.money(s200)} — " +
                        "the death-cross regime${if (below) ", with price under both averages" else ""}.$crossNote"
                )
            }
            else -> TechniqueResult(
                "gc", name, TechniqueVerdict.NEUTRAL, 35,
                "50-day ${Fmt.money(s50)} is over the 200-day ${Fmt.money(s200)} but price ${Fmt.money(price)} " +
                    "has slipped below the 200-day — regime in question."
            )
        }
    }

    // -- technique 16: Williams %R -------------------------------------------

    /** %R = -100 * (HH14 - close) / (HH14 - LL14); -100 at the range low, 0 at the high. */
    private fun williamsRSeries(candles: List<Candle>, period: Int): List<Double?> {
        val hh = highSeries(candles, period)
        val ll = lowSeries(candles, period)
        return List(candles.size) { i ->
            val h = hh[i]
            val l = ll[i]
            if (h == null || l == null) null
            else {
                val range = h - l
                if (range > 1e-12) -100.0 * (h - candles[i].close) / range else -50.0
            }
        }
    }

    private fun willrResult(n: Int, data: WilliamsRData): TechniqueResult {
        val name = "Williams %R"
        val r = data.r.lastOrNull { it != null }
            ?: return TechniqueResult(
                "willr", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 14 daily candles for Williams %R(14); $n available."
            )
        val prev = if (data.r.size >= 2) data.r[data.r.size - 2] else null
        return when {
            r <= -80.0 -> TechniqueResult(
                "willr", name, TechniqueVerdict.BULLISH,
                (55.0 + (-r - 80.0) * 1.5).roundToInt().coerceIn(55, 90),
                "%R at ${fmt0(r)} — the close sits in the bottom fifth of the 14-day range, Larry Williams' oversold zone."
            )
            r >= -20.0 -> TechniqueResult(
                "willr", name, TechniqueVerdict.BEARISH,
                (55.0 + (r + 20.0) * 1.5).roundToInt().coerceIn(55, 90),
                "%R at ${fmt0(r)} — the close sits in the top fifth of the 14-day range, the overbought zone."
            )
            prev != null && prev <= -80.0 -> TechniqueResult(
                "willr", name, TechniqueVerdict.BULLISH, 65,
                "%R climbed out of the oversold zone — now ${fmt0(r)} from ${fmt0(prev)}; the turn is confirmed."
            )
            prev != null && prev >= -20.0 -> TechniqueResult(
                "willr", name, TechniqueVerdict.BEARISH, 65,
                "%R dropped out of the overbought zone — now ${fmt0(r)} from ${fmt0(prev)}; the rollover is confirmed."
            )
            else -> TechniqueResult(
                "willr", name, TechniqueVerdict.NEUTRAL, 30,
                "%R at ${fmt0(r)}, mid-range between the -20 and -80 lines."
            )
        }
    }

    // -- technique 17: Commodity Channel Index -------------------------------

    /** Lambert's CCI: (tp - SMA(tp)) / (0.015 * mean deviation) over [period]. */
    private fun cciSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (n < period) return out.toList()
        val tp = DoubleArray(n) { (candles[it].high + candles[it].low + candles[it].close) / 3.0 }
        for (i in period - 1 until n) {
            var sum = 0.0
            for (j in i - period + 1..i) sum += tp[j]
            val mean = sum / period
            var dev = 0.0
            for (j in i - period + 1..i) dev += abs(tp[j] - mean)
            dev /= period
            out[i] = if (dev > 1e-12) (tp[i] - mean) / (0.015 * dev) else 0.0
        }
        return out.toList()
    }

    private fun cciResult(n: Int, data: CciData): TechniqueResult {
        val name = "CCI momentum"
        val c = data.cci.lastOrNull { it != null }
            ?: return TechniqueResult(
                "cci", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 20 daily candles for CCI(20); $n available."
            )
        // Lambert's original system: a push OVER +100 marks the start of a
        // strong up-move (trend entry), under -100 a strong down-move. This is
        // deliberately the trend school — the reversion school is already
        // covered by RSI, MFI, stochastic and Williams %R.
        return when {
            c >= 100.0 -> TechniqueResult(
                "cci", name, TechniqueVerdict.BULLISH,
                (55.0 + (c - 100.0) * 0.15).roundToInt().coerceIn(55, 92),
                "CCI(20) at ${fmt0(c)}, over the +100 line — Lambert's strong-uptrend trigger."
            )
            c <= -100.0 -> TechniqueResult(
                "cci", name, TechniqueVerdict.BEARISH,
                (55.0 + (-c - 100.0) * 0.15).roundToInt().coerceIn(55, 92),
                "CCI(20) at ${fmt0(c)}, under the -100 line — Lambert's strong-downtrend trigger."
            )
            else -> {
                val lean = when {
                    c > 0.0 -> "leaning positive"
                    c < 0.0 -> "leaning negative"
                    else -> "flat"
                }
                TechniqueResult(
                    "cci", name, TechniqueVerdict.NEUTRAL, 30,
                    "CCI(20) at ${fmt0(c)}, inside the ±100 band ($lean); no trend trigger."
                )
            }
        }
    }

    // -- technique 18: Keltner Channels --------------------------------------

    /** EMA(20) middle with bands at 2 x rolling ATR(10). */
    private fun keltnerData(candles: List<Candle>, closes: List<Double>): KeltnerData {
        val n = candles.size
        val middle = emaSeries(closes, 20)
        val atr = atrSeries(candles, 10)
        val upper = arrayOfNulls<Double>(n)
        val lower = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val m = middle[i]
            val a = atr[i]
            if (m != null && a != null) {
                upper[i] = m + 2.0 * a
                lower[i] = m - 2.0 * a
            }
        }
        return KeltnerData(closes, upper.toList(), middle, lower.toList())
    }

    private fun keltnerResult(price: Double, d: KeltnerData): TechniqueResult {
        val name = "Keltner Channels"
        val n = d.closes.size
        val upper = d.upper.lastOrNull()
        val middle = d.middle.lastOrNull()
        val lower = d.lower.lastOrNull()
        if (upper == null || middle == null || lower == null) {
            return TechniqueResult(
                "keltner", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 21 daily candles for the EMA(20)/ATR(10) channel; $n available."
            )
        }
        return when {
            price > upper -> {
                val margin = if (upper > 0.0) (price - upper) / upper * 100.0 else 0.0
                TechniqueResult(
                    "keltner", name, TechniqueVerdict.BULLISH,
                    (60.0 + margin * 10.0).roundToInt().coerceIn(60, 92),
                    "Close ${Fmt.money(price)} rides ${fmt1(margin)}% above the upper channel ${Fmt.money(upper)} — " +
                        "a volatility-adjusted breakout, not a normal fluctuation."
                )
            }
            price < lower -> {
                val margin = if (lower > 0.0) (lower - price) / lower * 100.0 else 0.0
                TechniqueResult(
                    "keltner", name, TechniqueVerdict.BEARISH,
                    (60.0 + margin * 10.0).roundToInt().coerceIn(60, 92),
                    "Close ${Fmt.money(price)} sits ${fmt1(margin)}% below the lower channel ${Fmt.money(lower)} — " +
                        "a volatility-adjusted breakdown."
                )
            }
            else -> {
                val width = upper - lower
                val pos = if (width > 1e-9) (price - lower) / width * 100.0 else 50.0
                TechniqueResult(
                    "keltner", name, TechniqueVerdict.NEUTRAL, 30,
                    "Close ${Fmt.money(price)} sits at ${fmt0(pos)}% of the ${Fmt.money(lower)}–${Fmt.money(upper)} " +
                        "channel around the ${Fmt.money(middle)} EMA; no breakout."
                )
            }
        }
    }

    // -- technique 19: Chaikin Money Flow ------------------------------------

    /** CMF(period) = sum(mf volume) / sum(volume); multiplier ((c-l)-(h-c))/(h-l). */
    private fun cmfSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (n < period) return out.toList()
        val mfv = DoubleArray(n)
        val vol = DoubleArray(n)
        for (i in 0 until n) {
            val c = candles[i]
            val range = c.high - c.low
            val mult = if (range > 1e-12) ((c.close - c.low) - (c.high - c.close)) / range else 0.0
            vol[i] = c.volume.toDouble()
            mfv[i] = mult * vol[i]
        }
        for (i in period - 1 until n) {
            var sumMfv = 0.0
            var sumVol = 0.0
            for (j in i - period + 1..i) {
                sumMfv += mfv[j]
                sumVol += vol[j]
            }
            out[i] = if (sumVol > 0.0) sumMfv / sumVol else 0.0
        }
        return out.toList()
    }

    private fun cmfResult(n: Int, d: CmfData): TechniqueResult {
        val name = "Chaikin Money Flow"
        val c = d.cmf.lastOrNull { it != null }
            ?: return TechniqueResult(
                "cmf", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 20 daily candles for CMF(20); $n available."
            )
        val prev5 = if (d.cmf.size >= 6) d.cmf[d.cmf.size - 6] else null
        val trendNote = prev5?.let { " Five bars ago it was ${fmtCmf(it)}." } ?: ""
        return when {
            c >= 0.05 -> TechniqueResult(
                "cmf", name, TechniqueVerdict.BULLISH,
                (55.0 + c * 150.0).roundToInt().coerceIn(55, 90),
                "CMF(20) at ${fmtCmf(c)} — closes keep landing in the top of their daily ranges " +
                    "on real volume: buyers are accumulating.$trendNote"
            )
            c <= -0.05 -> TechniqueResult(
                "cmf", name, TechniqueVerdict.BEARISH,
                (55.0 - c * 150.0).roundToInt().coerceIn(55, 90),
                "CMF(20) at ${fmtCmf(c)} — closes keep landing in the bottom of their daily ranges " +
                    "on real volume: sellers are distributing.$trendNote"
            )
            else -> TechniqueResult(
                "cmf", name, TechniqueVerdict.NEUTRAL, 30,
                "CMF(20) at ${fmtCmf(c)}, inside the ±0.05 noise band; money flow is balanced.$trendNote"
            )
        }
    }

    // -- technique 20: Aroon -------------------------------------------------

    /** Aroon(25): up = 100*(period - bars since the period high)/period; down mirrored. */
    private fun aroonData(candles: List<Candle>, period: Int): AroonData {
        val n = candles.size
        val up = arrayOfNulls<Double>(n)
        val down = arrayOfNulls<Double>(n)
        for (i in period until n) {
            var hiIdx = i
            var loIdx = i
            for (j in i - period..i) {
                if (candles[j].high >= candles[hiIdx].high) hiIdx = j
                if (candles[j].low <= candles[loIdx].low) loIdx = j
            }
            up[i] = 100.0 * (period - (i - hiIdx)) / period
            down[i] = 100.0 * (period - (i - loIdx)) / period
        }
        return AroonData(up.toList(), down.toList())
    }

    private fun aroonResult(n: Int, d: AroonData): TechniqueResult {
        val name = "Aroon trend"
        val up = d.up.lastOrNull { it != null }
        val down = d.down.lastOrNull { it != null }
        if (up == null || down == null) {
            return TechniqueResult(
                "aroon", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 26 daily candles for Aroon(25); $n available."
            )
        }
        return when {
            up >= 70.0 && down <= 30.0 -> TechniqueResult(
                "aroon", name, TechniqueVerdict.BULLISH,
                (55.0 + (up - down) * 0.4).roundToInt().coerceIn(55, 92),
                "Aroon up ${fmt0(up)} vs down ${fmt0(down)} — the 25-day high is fresh and the " +
                    "25-day low is old: an established uptrend."
            )
            down >= 70.0 && up <= 30.0 -> TechniqueResult(
                "aroon", name, TechniqueVerdict.BEARISH,
                (55.0 + (down - up) * 0.4).roundToInt().coerceIn(55, 92),
                "Aroon down ${fmt0(down)} vs up ${fmt0(up)} — the 25-day low is fresh and the " +
                    "25-day high is old: an established downtrend."
            )
            else -> {
                val lead = if (up >= down) "up leads" else "down leads"
                TechniqueResult(
                    "aroon", name, TechniqueVerdict.NEUTRAL, 30,
                    "Aroon up ${fmt0(up)} vs down ${fmt0(down)} ($lead) — neither side holds the " +
                        "70/30 split; no established trend."
                )
            }
        }
    }

    // -- technique 21: Stochastic RSI ----------------------------------------

    /** Stochastic(14) of the RSI(14) series, smoothed 3/3 like the slow stochastic. */
    private fun stochRsiData(closes: List<Double>): StochRsiData {
        val rsi = rsiSeries(closes, 14)
        val n = rsi.size
        val raw = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            var ok = i >= 13
            if (ok) {
                for (j in i - 13..i) {
                    val v = rsi[j]
                    if (v == null) {
                        ok = false
                        break
                    }
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
            if (ok) {
                val span = hi - lo
                raw[i] = if (span > 1e-9) (rsi[i]!! - lo) / span * 100.0 else 50.0
            }
        }
        val k = sma3Nullable(raw.toList())
        val d = sma3Nullable(k)
        return StochRsiData(k, d)
    }

    private fun stochRsiResult(n: Int, data: StochRsiData): TechniqueResult {
        val name = "Stochastic RSI"
        val k = data.k.last()
        val d = data.d.last()
        if (k == null || d == null) {
            return TechniqueResult(
                "stochrsi", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 33 daily candles for StochRSI(14,14,3,3); $n available."
            )
        }
        return when {
            k < 20.0 && k >= d -> TechniqueResult(
                "stochrsi", name, TechniqueVerdict.BULLISH,
                (68.0 + (20.0 - k)).roundToInt().coerceIn(68, 90),
                "StochRSI %K ${fmt0(k)} turning up over %D ${fmt0(d)} in the sub-20 zone — " +
                    "momentum of momentum is bottoming."
            )
            k > 80.0 && k <= d -> TechniqueResult(
                "stochrsi", name, TechniqueVerdict.BEARISH,
                (68.0 + (k - 80.0)).roundToInt().coerceIn(68, 90),
                "StochRSI %K ${fmt0(k)} rolling under %D ${fmt0(d)} in the over-80 zone — " +
                    "momentum of momentum is topping."
            )
            else -> {
                val zone = when {
                    k < 20.0 -> " pinned in the oversold zone, no turn yet"
                    k > 80.0 -> " pinned in the overbought zone, no turn yet"
                    else -> " mid-range"
                }
                TechniqueResult(
                    "stochrsi", name, TechniqueVerdict.NEUTRAL, 30,
                    "StochRSI %K ${fmt0(k)} vs %D ${fmt0(d)},$zone."
                )
            }
        }
    }

    // -- technique 22: Rate of Change ----------------------------------------

    /** Percent change vs the close [period] bars back; defined from index [period]. */
    private fun rocSeries(closes: List<Double>, period: Int): List<Double?> {
        val n = closes.size
        val out = arrayOfNulls<Double>(n)
        for (i in period until n) {
            val base = closes[i - period]
            if (base > 0.0) out[i] = (closes[i] / base - 1.0) * 100.0
        }
        return out.toList()
    }

    private fun rocResult(n: Int, data: RocData): TechniqueResult {
        val name = "Rate of Change"
        val r = data.roc.last()
            ?: return TechniqueResult(
                "roc", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 13 daily candles for ROC(12); $n available."
            )
        val prev = if (data.roc.size >= 2) data.roc[data.roc.size - 2] else null
        val crossedUp = prev != null && prev <= 0.0 && r > 0.0
        val crossedDown = prev != null && prev >= 0.0 && r < 0.0
        return when {
            r >= 2.0 -> TechniqueResult(
                "roc", name, TechniqueVerdict.BULLISH,
                (55.0 + r * 2.0 + (if (crossedUp) 10.0 else 0.0)).roundToInt().coerceIn(55, 92),
                "ROC(12) at ${fmt1(r)}% — price runs ${fmt1(r)}% above its close 12 bars ago; " +
                    "momentum is positive${if (crossedUp) " and just crossed the zero line" else ""}."
            )
            r <= -2.0 -> TechniqueResult(
                "roc", name, TechniqueVerdict.BEARISH,
                (55.0 - r * 2.0 + (if (crossedDown) 10.0 else 0.0)).roundToInt().coerceIn(55, 92),
                "ROC(12) at ${fmt1(r)}% — price sits ${fmt1(-r)}% below its close 12 bars ago; " +
                    "momentum is negative${if (crossedDown) " and just crossed under zero" else ""}."
            )
            crossedUp -> TechniqueResult(
                "roc", name, TechniqueVerdict.BULLISH, 60,
                "ROC(12) crossed up through zero to ${fmt1(r)}% — the momentum turn traders buy."
            )
            crossedDown -> TechniqueResult(
                "roc", name, TechniqueVerdict.BEARISH, 60,
                "ROC(12) crossed down through zero to ${fmt1(r)}% — the momentum turn traders sell."
            )
            else -> TechniqueResult(
                "roc", name, TechniqueVerdict.NEUTRAL, 30,
                "ROC(12) at ${fmt1(r)}%, inside the ±2% noise band around zero."
            )
        }
    }

    // -- technique 23: TRIX ---------------------------------------------------

    /** TRIX(15): 1-bar % change of the triple EMA(15), with an EMA(9) signal. */
    private fun trixData(closes: List<Double>): TrixData {
        val n = closes.size
        val e1 = emaSeries(closes, 15)
        val e2 = emaNullable(e1, 15)
        val e3 = emaNullable(e2, 15)
        val trix = arrayOfNulls<Double>(n)
        for (i in 1 until n) {
            val a = e3[i]
            val b = e3[i - 1]
            if (a != null && b != null && b != 0.0) trix[i] = (a / b - 1.0) * 100.0
        }
        return TrixData(trix.toList(), emaNullable(trix.toList(), 9))
    }

    private fun trixResult(n: Int, data: TrixData): TechniqueResult {
        val name = "TRIX"
        val t = data.trix.last()
        val s = data.signal.last()
        if (t == null || s == null) {
            return TechniqueResult(
                "trix", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 55 daily candles for TRIX(15) and its signal; $n available."
            )
        }
        val prev = if (data.trix.size >= 2) data.trix[data.trix.size - 2] else null
        val rising = prev != null && t > prev
        val falling = prev != null && t < prev
        return when {
            t > s && rising -> TechniqueResult(
                "trix", name, TechniqueVerdict.BULLISH,
                (58.0 + min(20.0, abs(t) * 60.0) + (if (t > 0.0) 8.0 else 0.0))
                    .roundToInt().coerceIn(58, 92),
                "TRIX ${fmtSig(t)} rising above its signal ${fmtSig(s)} — the triple-smoothed " +
                    "trend filter reads up with the noise stripped out."
            )
            t < s && falling -> TechniqueResult(
                "trix", name, TechniqueVerdict.BEARISH,
                (58.0 + min(20.0, abs(t) * 60.0) + (if (t < 0.0) 8.0 else 0.0))
                    .roundToInt().coerceIn(58, 92),
                "TRIX ${fmtSig(t)} falling below its signal ${fmtSig(s)} — the triple-smoothed " +
                    "trend filter reads down."
            )
            else -> TechniqueResult(
                "trix", name, TechniqueVerdict.NEUTRAL, 30,
                "TRIX ${fmtSig(t)} vs signal ${fmtSig(s)} with no push either way."
            )
        }
    }

    // -- technique 24: Ultimate Oscillator -----------------------------------

    /** Williams' UO: buying pressure over true range, blended 7/14/28 as 4:2:1. */
    private fun ultimateSeries(candles: List<Candle>): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (n < 29) return out.toList()
        val bp = DoubleArray(n)
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val c = candles[i]
            val pc = candles[i - 1].close
            val trueLow = min(c.low, pc)
            val trueHigh = max(c.high, pc)
            bp[i] = c.close - trueLow
            tr[i] = trueHigh - trueLow
        }
        fun avg(i: Int, period: Int): Double? {
            var sb = 0.0
            var st = 0.0
            for (j in i - period + 1..i) {
                sb += bp[j]
                st += tr[j]
            }
            return if (st > 1e-12) sb / st else null
        }
        for (i in 28 until n) {
            val a7 = avg(i, 7) ?: continue
            val a14 = avg(i, 14) ?: continue
            val a28 = avg(i, 28) ?: continue
            out[i] = 100.0 * (4.0 * a7 + 2.0 * a14 + a28) / 7.0
        }
        return out.toList()
    }

    private fun uoResult(n: Int, data: UltimateData): TechniqueResult {
        val name = "Ultimate Oscillator"
        val u = data.uo.last()
            ?: return TechniqueResult(
                "uo", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 29 daily candles for the 7/14/28 blend; $n available."
            )
        val prev5 = if (data.uo.size >= 6) data.uo[data.uo.size - 6] else null
        val trendNote = prev5?.let { " Five bars ago it was ${fmt0(it)}." } ?: ""
        return when {
            u < 30.0 -> TechniqueResult(
                "uo", name, TechniqueVerdict.BULLISH,
                (58.0 + (30.0 - u) * 2.0).roundToInt().coerceIn(58, 90),
                "Ultimate Oscillator at ${fmt0(u)}, under the 30 line — buying pressure is " +
                    "washed out across all three timeframes.$trendNote"
            )
            u > 70.0 -> TechniqueResult(
                "uo", name, TechniqueVerdict.BEARISH,
                (58.0 + (u - 70.0) * 2.0).roundToInt().coerceIn(58, 90),
                "Ultimate Oscillator at ${fmt0(u)}, over the 70 line — buying pressure is " +
                    "stretched across all three timeframes.$trendNote"
            )
            else -> TechniqueResult(
                "uo", name, TechniqueVerdict.NEUTRAL, 30,
                "Ultimate Oscillator at ${fmt0(u)}, inside the 30–70 band.$trendNote"
            )
        }
    }

    // -- technique 25: Vortex Indicator --------------------------------------

    /** VI+ = sum14|high - prevLow| / sum14 TR; VI- mirrored on lows vs prev highs. */
    private fun vortexData(candles: List<Candle>, period: Int): VortexData {
        val n = candles.size
        val plus = arrayOfNulls<Double>(n)
        val minus = arrayOfNulls<Double>(n)
        if (n < period + 1) return VortexData(plus.toList(), minus.toList())
        val vmP = DoubleArray(n)
        val vmM = DoubleArray(n)
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val c = candles[i]
            val p = candles[i - 1]
            vmP[i] = abs(c.high - p.low)
            vmM[i] = abs(c.low - p.high)
            tr[i] = max(c.high - c.low, max(abs(c.high - p.close), abs(c.low - p.close)))
        }
        for (i in period until n) {
            var sp = 0.0
            var sm = 0.0
            var st = 0.0
            for (j in i - period + 1..i) {
                sp += vmP[j]
                sm += vmM[j]
                st += tr[j]
            }
            if (st > 1e-12) {
                plus[i] = sp / st
                minus[i] = sm / st
            }
        }
        return VortexData(plus.toList(), minus.toList())
    }

    private fun vortexResult(n: Int, data: VortexData): TechniqueResult {
        val name = "Vortex Indicator"
        val p = data.plus.last()
        val m = data.minus.last()
        if (p == null || m == null) {
            return TechniqueResult(
                "vortex", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 15 daily candles for VI(14); $n available."
            )
        }
        // Most recent VI+/VI- cross within the last 5 bars, if any.
        var crossAgo: Int? = null
        var i = data.plus.size - 1
        var steps = 0
        while (i >= 1 && steps < 5) {
            val a = data.plus[i]
            val b = data.minus[i]
            val pa = data.plus[i - 1]
            val pb = data.minus[i - 1]
            if (a != null && b != null && pa != null && pb != null) {
                if ((a - b != 0.0) && (pa - pb != 0.0) && ((a > b) != (pa > pb))) {
                    crossAgo = data.plus.size - 1 - i
                    break
                }
            }
            i--
            steps++
        }
        val gap = abs(p - m)
        val crossNote = crossAgo?.let {
            " The lines crossed ${if (it == 0) "this bar" else "$it ${bars(it)} ago"} — a fresh signal."
        } ?: ""
        return when {
            p > m && p >= 1.0 -> TechniqueResult(
                "vortex", name, TechniqueVerdict.BULLISH,
                (58.0 + gap * 100.0 + (if (crossAgo != null) 10.0 else 0.0)).roundToInt().coerceIn(58, 92),
                "VI+ ${fmt2(p)} over VI- ${fmt2(m)} with VI+ above 1.0 — upward range " +
                    "expansion dominates.$crossNote"
            )
            m > p && m >= 1.0 -> TechniqueResult(
                "vortex", name, TechniqueVerdict.BEARISH,
                (58.0 + gap * 100.0 + (if (crossAgo != null) 10.0 else 0.0)).roundToInt().coerceIn(58, 92),
                "VI- ${fmt2(m)} over VI+ ${fmt2(p)} with VI- above 1.0 — downward range " +
                    "expansion dominates.$crossNote"
            )
            else -> TechniqueResult(
                "vortex", name, TechniqueVerdict.NEUTRAL, 30,
                "VI+ ${fmt2(p)} vs VI- ${fmt2(m)}, both near the 1.0 line — no vortex has formed."
            )
        }
    }

    // -- technique 26: Elder's Force Index -----------------------------------

    /** Force = daily close change x volume, smoothed with EMA(13). */
    private fun forceSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (n < period + 1) return out.toList()
        val raw = ArrayList<Double>(n - 1)
        for (i in 1 until n) {
            raw.add((candles[i].close - candles[i - 1].close) * candles[i].volume.toDouble())
        }
        val ema = emaSeries(raw, period)
        for (j in ema.indices) out[j + 1] = ema[j]
        return out.toList()
    }

    private fun forceResult(n: Int, data: ForceData, price: Double): TechniqueResult {
        val name = "Force Index"
        val f = data.force.last()
            ?: return TechniqueResult(
                "efi", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 14 daily candles for Force Index EMA(13); $n available."
            )
        val prev = if (data.force.size >= 2) data.force[data.force.size - 2] else null
        val rising = prev != null && f > prev
        val falling = prev != null && f < prev
        // Normalize against the average absolute force so strength is comparable
        // across a $4 stock and a $400 one.
        val defined = data.force.filterNotNull()
        val avgAbs = if (defined.isNotEmpty()) defined.sumOf { abs(it) } / defined.size else 0.0
        val rel = if (avgAbs > 1e-9) abs(f) / avgAbs else 0.0
        val netTxt = (if (f >= 0.0) "+" else "-") + Fmt.compact(abs(f))
        return when {
            f > 0.0 && rising -> TechniqueResult(
                "efi", name, TechniqueVerdict.BULLISH,
                (58.0 + min(25.0, rel * 12.0)).roundToInt().coerceIn(58, 90),
                "Force Index $netTxt, positive and rising — Elder's read: the bulls are " +
                    "pushing price with real volume behind them."
            )
            f < 0.0 && falling -> TechniqueResult(
                "efi", name, TechniqueVerdict.BEARISH,
                (58.0 + min(25.0, rel * 12.0)).roundToInt().coerceIn(58, 90),
                "Force Index $netTxt, negative and falling — the bears are pressing with " +
                    "real volume behind them."
            )
            else -> TechniqueResult(
                "efi", name, TechniqueVerdict.NEUTRAL, 30,
                "Force Index $netTxt with no sustained push — buying and selling force " +
                    "roughly balance."
            )
        }
    }

    // -- technique 27: Chande Momentum Oscillator ----------------------------

    /** CMO = 100 x (sum up-moves - sum down-moves) / (sum up + sum down) over [period]. */
    private fun cmoSeries(closes: List<Double>, period: Int): List<Double?> {
        val n = closes.size
        val out = arrayOfNulls<Double>(n)
        if (n < period + 1) return out.toList()
        for (i in period until n) {
            var up = 0.0
            var down = 0.0
            for (j in i - period + 1..i) {
                val d = closes[j] - closes[j - 1]
                if (d > 0.0) up += d else down -= d
            }
            val total = up + down
            out[i] = if (total > 1e-12) 100.0 * (up - down) / total else 0.0
        }
        return out.toList()
    }

    private fun cmoResult(n: Int, data: CmoData): TechniqueResult {
        val name = "Chande Momentum"
        val c = data.cmo.last()
            ?: return TechniqueResult(
                "cmo", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 15 daily candles for CMO(14); $n available."
            )
        return when {
            c <= -50.0 -> TechniqueResult(
                "cmo", name, TechniqueVerdict.BULLISH,
                (58.0 + (-c - 50.0) * 0.6).roundToInt().coerceIn(58, 90),
                "CMO(14) at ${fmt0(c)}, under Chande's -50 oversold line — down-moves have " +
                    "dominated to the point of exhaustion."
            )
            c >= 50.0 -> TechniqueResult(
                "cmo", name, TechniqueVerdict.BEARISH,
                (58.0 + (c - 50.0) * 0.6).roundToInt().coerceIn(58, 90),
                "CMO(14) at ${fmt0(c)}, over Chande's +50 overbought line — up-moves have " +
                    "dominated to the point of stretch."
            )
            else -> {
                val lean = when {
                    c > 15.0 -> ", leaning positive"
                    c < -15.0 -> ", leaning negative"
                    else -> ""
                }
                TechniqueResult(
                    "cmo", name, TechniqueVerdict.NEUTRAL, 30,
                    "CMO(14) at ${fmt0(c)}, inside the ±50 band$lean."
                )
            }
        }
    }

    // -- technique 28: Detrended Price Oscillator ----------------------------

    /** Non-centered DPO(20): close minus the SMA(20) from (20/2 + 1) bars back. */
    private fun dpoSeries(closes: List<Double>, period: Int): List<Double?> {
        val n = closes.size
        val out = arrayOfNulls<Double>(n)
        val shift = period / 2 + 1
        val sma = smaSeries(closes, period)
        for (i in 0 until n) {
            val idx = i - shift
            if (idx >= 0) {
                val m = sma[idx]
                if (m != null) out[i] = closes[i] - m
            }
        }
        return out.toList()
    }

    private fun dpoResult(n: Int, price: Double, data: DpoData): TechniqueResult {
        val name = "Detrended Price"
        val d = data.dpo.last()
            ?: return TechniqueResult(
                "dpo", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 31 daily candles for DPO(20); $n available."
            )
        val prev = if (data.dpo.size >= 2) data.dpo[data.dpo.size - 2] else null
        val rising = prev != null && d > prev
        val falling = prev != null && d < prev
        val pct = if (price > 0.0) d / price * 100.0 else 0.0
        return when {
            d > 0.0 && rising -> TechniqueResult(
                "dpo", name, TechniqueVerdict.BULLISH,
                (55.0 + min(20.0, abs(pct) * 6.0)).roundToInt().coerceIn(55, 85),
                "DPO(20) at ${fmtSig(d)} (${fmt1(pct)}% of price), positive and rising — " +
                    "the short cycle is on its upswing."
            )
            d < 0.0 && falling -> TechniqueResult(
                "dpo", name, TechniqueVerdict.BEARISH,
                (55.0 + min(20.0, abs(pct) * 6.0)).roundToInt().coerceIn(55, 85),
                "DPO(20) at ${fmtSig(d)} (${fmt1(pct)}% of price), negative and falling — " +
                    "the short cycle is on its downswing."
            )
            else -> TechniqueResult(
                "dpo", name, TechniqueVerdict.NEUTRAL, 30,
                "DPO(20) at ${fmtSig(d)} with no cycle push — price hugs its detrended average."
            )
        }
    }

    // -- technique 29: Know Sure Thing ---------------------------------------

    /** Pring's KST: SMA-smoothed ROCs of 10/15/20/30 weighted 1:2:3:4, signal SMA(9). */
    private fun kstData(closes: List<Double>): KstData {
        val n = closes.size
        val r1 = smaNullable(rocSeries(closes, 10), 10)
        val r2 = smaNullable(rocSeries(closes, 15), 10)
        val r3 = smaNullable(rocSeries(closes, 20), 10)
        val r4 = smaNullable(rocSeries(closes, 30), 15)
        val kst = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val a = r1[i]
            val b = r2[i]
            val c = r3[i]
            val d = r4[i]
            if (a != null && b != null && c != null && d != null) {
                kst[i] = a + 2.0 * b + 3.0 * c + 4.0 * d
            }
        }
        return KstData(kst.toList(), smaNullable(kst.toList(), 9))
    }

    private fun kstResult(n: Int, data: KstData): TechniqueResult {
        val name = "Know Sure Thing"
        val k = data.kst.last()
        val s = data.signal.last()
        if (k == null || s == null) {
            return TechniqueResult(
                "kst", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 53 daily candles for Pring's KST and its signal; $n available."
            )
        }
        val gap = abs(k - s)
        return when {
            k > s && k > 0.0 -> TechniqueResult(
                "kst", name, TechniqueVerdict.BULLISH,
                (58.0 + min(22.0, gap * 2.0) + 6.0).roundToInt().coerceIn(58, 92),
                "KST ${fmt1(k)} above its signal ${fmt1(s)} and above zero — all four " +
                    "smoothed momentum cycles point up."
            )
            k > s -> TechniqueResult(
                "kst", name, TechniqueVerdict.BULLISH, 58,
                "KST ${fmt1(k)} crossed over its signal ${fmt1(s)} below zero — an early turn, " +
                    "not yet a confirmed uptrend."
            )
            k < s && k < 0.0 -> TechniqueResult(
                "kst", name, TechniqueVerdict.BEARISH,
                (58.0 + min(22.0, gap * 2.0) + 6.0).roundToInt().coerceIn(58, 92),
                "KST ${fmt1(k)} below its signal ${fmt1(s)} and below zero — all four " +
                    "smoothed momentum cycles point down."
            )
            k < s -> TechniqueResult(
                "kst", name, TechniqueVerdict.BEARISH, 58,
                "KST ${fmt1(k)} rolled under its signal ${fmt1(s)} above zero — momentum is " +
                    "fading from a high level."
            )
            else -> TechniqueResult(
                "kst", name, TechniqueVerdict.NEUTRAL, 30,
                "KST ${fmt1(k)} sits on its signal ${fmt1(s)} — no cycle verdict."
            )
        }
    }

    // -- technique 30: Hull Moving Average -----------------------------------

    /** HMA(n) = WMA(2 x WMA(n/2) - WMA(n), sqrt(n)). */
    private fun hullSeries(closes: List<Double>, period: Int): List<Double?> {
        val n = closes.size
        val half = wmaSeries(closes, period / 2)
        val full = wmaSeries(closes, period)
        val diff = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val h = half[i]
            val f = full[i]
            if (h != null && f != null) diff[i] = 2.0 * h - f
        }
        return wmaNullable(diff.toList(), sqrt(period.toDouble()).roundToInt().coerceAtLeast(2))
    }

    private fun hullResult(price: Double, data: HullData): TechniqueResult {
        val name = "Hull Moving Average"
        val n = data.closes.size
        val h = data.hull.last()
        val prev = if (data.hull.size >= 2) data.hull[data.hull.size - 2] else null
        if (h == null || prev == null) {
            return TechniqueResult(
                "hull", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 25 daily candles for HMA(20); $n available."
            )
        }
        val rising = h > prev
        val distPct = if (h > 0.0) (price - h) / h * 100.0 else 0.0
        return when {
            price > h && rising -> TechniqueResult(
                "hull", name, TechniqueVerdict.BULLISH,
                (58.0 + min(20.0, distPct * 5.0) + 8.0).roundToInt().coerceIn(58, 92),
                "Close ${Fmt.money(price)} rides ${fmt1(distPct)}% above a rising HMA(20) " +
                    "${Fmt.money(h)} — Hull's fast, smooth average confirms the up-move."
            )
            price < h && !rising -> TechniqueResult(
                "hull", name, TechniqueVerdict.BEARISH,
                (58.0 + min(20.0, -distPct * 5.0) + 8.0).roundToInt().coerceIn(58, 92),
                "Close ${Fmt.money(price)} sits ${fmt1(-distPct)}% below a falling HMA(20) " +
                    "${Fmt.money(h)} — the fast average confirms the down-move."
            )
            else -> TechniqueResult(
                "hull", name, TechniqueVerdict.NEUTRAL, 32,
                "Close ${Fmt.money(price)} and the HMA(20) ${Fmt.money(h)} disagree on " +
                    "direction — the turn is not confirmed."
            )
        }
    }

    // -- technique 31: Supertrend --------------------------------------------

    /** Supertrend(10, 3): ratcheted ATR bands off the bar midpoint; flips on closes. */
    private fun supertrendData(candles: List<Candle>, closes: List<Double>): SupertrendData {
        val n = candles.size
        val line = arrayOfNulls<Double>(n)
        val bull = arrayOfNulls<Boolean>(n)
        val atr = atrSeries(candles, 10)
        var up = true
        var finalUpper = 0.0
        var finalLower = 0.0
        var seeded = false
        for (i in 0 until n) {
            val a = atr[i] ?: continue
            val mid = (candles[i].high + candles[i].low) / 2.0
            val basicUpper = mid + 3.0 * a
            val basicLower = mid - 3.0 * a
            if (!seeded) {
                finalUpper = basicUpper
                finalLower = basicLower
                up = candles[i].close >= mid
                seeded = true
            } else {
                val prevClose = candles[i - 1].close
                finalUpper =
                    if (basicUpper < finalUpper || prevClose > finalUpper) basicUpper else finalUpper
                finalLower =
                    if (basicLower > finalLower || prevClose < finalLower) basicLower else finalLower
                up = when {
                    up && candles[i].close < finalLower -> false
                    !up && candles[i].close > finalUpper -> true
                    else -> up
                }
            }
            line[i] = if (up) finalLower else finalUpper
            bull[i] = up
        }
        return SupertrendData(closes, line.toList(), bull.toList())
    }

    private fun supertrendResult(price: Double, data: SupertrendData): TechniqueResult {
        val name = "Supertrend"
        val lastIdx = data.line.indexOfLast { it != null }
        if (lastIdx < 1) {
            return TechniqueResult(
                "supertrend", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 11 daily candles for Supertrend(10, 3); ${data.closes.size} available."
            )
        }
        val line = data.line[lastIdx]!!
        val up = data.bullish[lastIdx] == true
        var run = 0
        var i = lastIdx
        while (i >= 0 && data.bullish[i] == data.bullish[lastIdx]) {
            run++
            i--
        }
        val fresh = run <= 3
        val flippedAgo = run - 1
        val freshNote =
            if (fresh) {
                " Flipped ${if (flippedAgo == 0) "this bar" else "$flippedAgo ${bars(flippedAgo)} ago"} — a fresh signal."
            } else {
                " The regime has held $run ${bars(run)}."
            }
        val distPct = if (price > 0.0) abs(price - line) / price * 100.0 else 0.0
        return if (up) {
            TechniqueResult(
                "supertrend", name, TechniqueVerdict.BULLISH,
                (58.0 + (if (fresh) 14.0 else 5.0) + min(12.0, distPct * 2.0)).roundToInt().coerceIn(58, 92),
                "Supertrend line ${Fmt.money(line)} trails ${fmt1(distPct)}% below price — " +
                    "the 3x ATR(10) ratchet keeps the uptrend intact.$freshNote"
            )
        } else {
            TechniqueResult(
                "supertrend", name, TechniqueVerdict.BEARISH,
                (58.0 + (if (fresh) 14.0 else 5.0) + min(12.0, distPct * 2.0)).roundToInt().coerceIn(58, 92),
                "Supertrend line ${Fmt.money(line)} caps price from ${fmt1(distPct)}% above — " +
                    "the 3x ATR(10) ratchet keeps the downtrend intact.$freshNote"
            )
        }
    }

    // -- technique 32: Chandelier Exit ---------------------------------------

    /** LeBeau's exits: HH(22) - 3 x ATR(22) for longs, LL(22) + 3 x ATR(22) for shorts. */
    private fun chandelierData(candles: List<Candle>, closes: List<Double>): ChandelierData {
        val n = candles.size
        val atr = atrSeries(candles, 22)
        val hh = highSeries(candles, 22)
        val ll = lowSeries(candles, 22)
        val longStop = arrayOfNulls<Double>(n)
        val shortStop = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val a = atr[i]
            if (a != null) {
                hh[i]?.let { longStop[i] = it - 3.0 * a }
                ll[i]?.let { shortStop[i] = it + 3.0 * a }
            }
        }
        return ChandelierData(closes, longStop.toList(), shortStop.toList())
    }

    private fun chandelierResult(price: Double, data: ChandelierData): TechniqueResult {
        val name = "Chandelier Exit"
        val long = data.longStop.last()
        val short = data.shortStop.last()
        if (long == null || short == null) {
            return TechniqueResult(
                "chandelier", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 23 daily candles for the 22-bar, 3x ATR exits; ${data.closes.size} available."
            )
        }
        return when {
            price > long -> {
                val cushion = if (price > 0.0) (price - long) / price * 100.0 else 0.0
                TechniqueResult(
                    "chandelier", name, TechniqueVerdict.BULLISH,
                    (55.0 + min(25.0, cushion * 3.0)).roundToInt().coerceIn(55, 88),
                    "Close ${Fmt.money(price)} holds ${fmt1(cushion)}% above the long exit " +
                        "${Fmt.money(long)} — LeBeau's trailing stop has not fired; longs stay on."
                )
            }
            price < short -> {
                val cushion = if (price > 0.0) (short - price) / price * 100.0 else 0.0
                TechniqueResult(
                    "chandelier", name, TechniqueVerdict.BEARISH,
                    (55.0 + min(25.0, cushion * 3.0)).roundToInt().coerceIn(55, 88),
                    "Close ${Fmt.money(price)} sits ${fmt1(cushion)}% below the short exit " +
                        "${Fmt.money(short)} — the downtrend's trailing stop has not fired either way up."
                )
            }
            else -> TechniqueResult(
                "chandelier", name, TechniqueVerdict.NEUTRAL, 30,
                "Close ${Fmt.money(price)} sits between the long exit ${Fmt.money(long)} and " +
                    "short exit ${Fmt.money(short)} — neither side's trailing stop holds."
            )
        }
    }

    // -- technique 33: rolling VWAP ------------------------------------------

    /** Rolling 20-day VWAP on the typical price — a moving institutional cost basis. */
    private fun vwapSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (n < period) return out.toList()
        for (i in period - 1 until n) {
            var pv = 0.0
            var v = 0.0
            for (j in i - period + 1..i) {
                val c = candles[j]
                val tp = (c.high + c.low + c.close) / 3.0
                val vol = c.volume.toDouble()
                pv += tp * vol
                v += vol
            }
            if (v > 0.0) out[i] = pv / v
        }
        return out.toList()
    }

    private fun vwapResult(price: Double, data: VwapData): TechniqueResult {
        val name = "Rolling VWAP"
        val v = data.vwap.last()
            ?: return TechniqueResult(
                "vwap", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs 20 daily candles with volume for the 20-day VWAP; ${data.closes.size} available."
            )
        val prev = if (data.vwap.size >= 2) data.vwap[data.vwap.size - 2] else null
        val rising = prev != null && v > prev
        val distPct = if (v > 0.0) (price - v) / v * 100.0 else 0.0
        return when {
            abs(distPct) <= 0.5 -> TechniqueResult(
                "vwap", name, TechniqueVerdict.NEUTRAL, 35,
                "Close ${Fmt.money(price)} sits at the 20-day VWAP ${Fmt.money(v)} — price is " +
                    "trading right at the volume-weighted cost basis; the next push decides."
            )
            distPct > 0.0 -> TechniqueResult(
                "vwap", name, TechniqueVerdict.BULLISH,
                (55.0 + min(22.0, distPct * 4.0) + (if (rising) 6.0 else 0.0)).roundToInt().coerceIn(55, 90),
                "Close ${Fmt.money(price)} is ${fmt1(distPct)}% above the " +
                    "${if (rising) "rising " else ""}20-day VWAP ${Fmt.money(v)} — the average " +
                    "dollar in this stock is in profit, which keeps holders patient."
            )
            else -> TechniqueResult(
                "vwap", name, TechniqueVerdict.BEARISH,
                (55.0 + min(22.0, -distPct * 4.0) + (if (!rising) 6.0 else 0.0)).roundToInt().coerceIn(55, 90),
                "Close ${Fmt.money(price)} is ${fmt1(-distPct)}% below the 20-day VWAP " +
                    "${Fmt.money(v)} — the average dollar is underwater, and rallies into the " +
                    "VWAP tend to meet break-even sellers."
            )
        }
    }

    // -- technique 34: Accumulation/Distribution line ------------------------

    /** Chaikin's A/D line: cumulative close-location volume. */
    private fun adLineSeries(candles: List<Candle>): List<Double> {
        val out = ArrayList<Double>(candles.size)
        var ad = 0.0
        for (c in candles) {
            val range = c.high - c.low
            val mult = if (range > 1e-12) ((c.close - c.low) - (c.high - c.close)) / range else 0.0
            ad += mult * c.volume.toDouble()
            out.add(ad)
        }
        return out
    }

    private fun adResult(closes: List<Double>, ad: List<Double>): TechniqueResult {
        val name = "Accum/Distribution"
        val n = closes.size
        val window = min(OBV_WINDOW, n)
        if (window < 3) {
            return TechniqueResult(
                "ad", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs more history for the $OBV_WINDOW-bar A/D read; $n available."
            )
        }
        val slope = lsSlope(ad.takeLast(window))
        val ref = closes[n - window]
        val priceChgPct = if (ref > 0.0) (closes.last() - ref) / ref * 100.0 else 0.0
        val adDelta = ad.last() - ad[n - window]
        val netTxt = (if (adDelta >= 0.0) "+" else "-") + Fmt.compact(abs(adDelta))
        val priceTxt = when {
            priceChgPct > 0.05 -> "up ${fmt1(priceChgPct)}%"
            priceChgPct < -0.05 -> "down ${fmt1(-priceChgPct)}%"
            else -> "flat"
        }
        return when {
            slope > 0.0 && priceChgPct <= 0.0 -> TechniqueResult(
                "ad", name, TechniqueVerdict.BULLISH,
                (68.0 + min(15.0, abs(priceChgPct) * 2.0)).roundToInt().coerceIn(68, 85),
                "A/D line climbing over $window bars (net $netTxt) while price is $priceTxt — " +
                    "closes keep landing high in their ranges: quiet accumulation."
            )
            slope < 0.0 && priceChgPct > 0.0 -> TechniqueResult(
                "ad", name, TechniqueVerdict.BEARISH,
                (68.0 + min(15.0, priceChgPct * 2.0)).roundToInt().coerceIn(68, 85),
                "A/D line falling over $window bars (net $netTxt) while price is $priceTxt — " +
                    "closes keep landing low in their ranges: quiet distribution."
            )
            priceChgPct > 0.0 && slope > 0.0 -> TechniqueResult(
                "ad", name, TechniqueVerdict.BULLISH, 50,
                "A/D line and price both up over $window bars ($priceTxt) — intraday " +
                    "positioning confirms the advance."
            )
            priceChgPct < 0.0 && slope < 0.0 -> TechniqueResult(
                "ad", name, TechniqueVerdict.BEARISH, 50,
                "A/D line and price both down over $window bars ($priceTxt) — intraday " +
                    "positioning confirms the slide."
            )
            else -> TechniqueResult(
                "ad", name, TechniqueVerdict.NEUTRAL, 30,
                "A/D line near flat over $window bars — no positioning signal."
            )
        }
    }

    // -- technique 35: monthly pivot points ----------------------------------

    /** Floor-trader pivots from the last COMPLETED calendar month's H/L/C (ET months). */
    private fun pivotData(candles: List<Candle>, closes: List<Double>): PivotData {
        val invalid = PivotData(closes, false, 0.0, 0.0, 0.0, 0.0, 0.0)
        if (candles.isEmpty()) return invalid
        val et = java.time.ZoneId.of("America/New_York")
        fun monthKey(ts: Long): Int {
            val d = java.time.Instant.ofEpochMilli(ts).atZone(et).toLocalDate()
            return d.year * 100 + d.monthValue
        }
        val lastKey = monthKey(candles.last().ts)
        var bestKey = -1
        for (c in candles) {
            val k = monthKey(c.ts)
            if (k < lastKey && k > bestKey) bestKey = k
        }
        if (bestKey < 0) return invalid
        var high = -Double.MAX_VALUE
        var low = Double.MAX_VALUE
        var close = 0.0
        for (c in candles) {
            if (monthKey(c.ts) == bestKey) {
                if (c.high > high) high = c.high
                if (c.low < low) low = c.low
                close = c.close
            }
        }
        if (high <= 0.0 || low <= 0.0 || close <= 0.0) return invalid
        val p = (high + low + close) / 3.0
        return PivotData(
            closes = closes,
            valid = true,
            pivot = p,
            r1 = 2.0 * p - low,
            r2 = p + (high - low),
            s1 = 2.0 * p - high,
            s2 = p - (high - low)
        )
    }

    private fun pivotResult(price: Double, data: PivotData): TechniqueResult {
        val name = "Monthly pivots"
        if (!data.valid) {
            return TechniqueResult(
                "pivot", name, TechniqueVerdict.NEUTRAL, 20,
                "Needs a completed calendar month in the window to anchor the pivots."
            )
        }
        val p = data.pivot
        val distPct = if (p > 0.0) (price - p) / p * 100.0 else 0.0
        fun near(level: Double): Boolean = level > 0.0 && abs(price - level) / level * 100.0 <= 1.0
        return when {
            near(data.r2) -> TechniqueResult(
                "pivot", name, TechniqueVerdict.NEUTRAL, 38,
                "Price ${Fmt.money(price)} is pressing the monthly R2 ${Fmt.money(data.r2)} — " +
                    "the month's outer resistance, where floor traders fade strength."
            )
            near(data.s2) -> TechniqueResult(
                "pivot", name, TechniqueVerdict.NEUTRAL, 38,
                "Price ${Fmt.money(price)} is testing the monthly S2 ${Fmt.money(data.s2)} — " +
                    "the month's outer support, where floor traders fade weakness."
            )
            distPct > 0.3 -> TechniqueResult(
                "pivot", name, TechniqueVerdict.BULLISH,
                (55.0 + min(20.0, distPct * 2.5) + (if (price > data.r1) 8.0 else 0.0))
                    .roundToInt().coerceIn(55, 88),
                "Price ${Fmt.money(price)} trades ${fmt1(distPct)}% above the monthly pivot " +
                    "${Fmt.money(p)}${if (price > data.r1) ", clear of R1 ${Fmt.money(data.r1)}" else ""} — " +
                    "the floor-trader map reads bullish for the month."
            )
            distPct < -0.3 -> TechniqueResult(
                "pivot", name, TechniqueVerdict.BEARISH,
                (55.0 + min(20.0, -distPct * 2.5) + (if (price < data.s1) 8.0 else 0.0))
                    .roundToInt().coerceIn(55, 88),
                "Price ${Fmt.money(price)} trades ${fmt1(-distPct)}% below the monthly pivot " +
                    "${Fmt.money(p)}${if (price < data.s1) ", under S1 ${Fmt.money(data.s1)}" else ""} — " +
                    "the floor-trader map reads bearish for the month."
            )
            else -> TechniqueResult(
                "pivot", name, TechniqueVerdict.NEUTRAL, 30,
                "Price ${Fmt.money(price)} sits on the monthly pivot ${Fmt.money(p)} — " +
                    "the month's balance point; R1 ${Fmt.money(data.r1)}, S1 ${Fmt.money(data.s1)}."
            )
        }
    }

    /** Rolling Wilder ATR; first value at index [period]. */
    private fun atrSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0 || n < period + 1) return out.toList()
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val c = candles[i]
            val pc = candles[i - 1].close
            tr[i] = max(c.high - c.low, max(abs(c.high - pc), abs(c.low - pc)))
        }
        var atr = 0.0
        for (i in 1..period) atr += tr[i]
        atr /= period
        out[period] = atr
        for (i in period + 1 until n) {
            atr = (atr * (period - 1) + tr[i]) / period
            out[i] = atr
        }
        return out.toList()
    }

    // -- outlook -------------------------------------------------------------

    private fun buildOutlook(
        candles: List<Candle>,
        price: Double,
        results: List<TechniqueResult>,
        supports: List<Level>,
        resistances: List<Level>,
        accuracyWeights: Map<String, Int>? = null
    ): FiveDayOutlook {
        val bullishCount = results.count { it.verdict == TechniqueVerdict.BULLISH }
        val bearishCount = results.count { it.verdict == TechniqueVerdict.BEARISH }
        val neutralCount = results.count { it.verdict == TechniqueVerdict.NEUTRAL }

        // Vote weight = strength, scaled by the technique's measured 3-month
        // hit rate on this stock when available: a 75%-accurate technique
        // speaks at 1.25x, a 25% one at 0.75x. Without evaluation data every
        // technique keeps its plain strength.
        fun voteWeight(r: TechniqueResult): Double {
            val hitRate = accuracyWeights?.get(r.key) ?: return r.strength.toDouble()
            return r.strength * (0.5 + hitRate.coerceIn(0, 100) / 100.0)
        }

        val wBull = results.filter { it.verdict == TechniqueVerdict.BULLISH }.sumOf { voteWeight(it) }
        val wBear = results.filter { it.verdict == TechniqueVerdict.BEARISH }.sumOf { voteWeight(it) }
        val wNeut = results.filter { it.verdict == TechniqueVerdict.NEUTRAL }.sumOf { voteWeight(it) }
        val total = max(1.0, wBull + wBear + wNeut)

        val bullShare = wBull / total
        val bearShare = wBear / total
        val direction = when {
            bullShare >= 0.55 -> TechniqueVerdict.BULLISH
            bearShare >= 0.55 -> TechniqueVerdict.BEARISH
            else -> TechniqueVerdict.NEUTRAL
        }
        val confidence = when (direction) {
            TechniqueVerdict.BULLISH -> (bullShare * 100.0).roundToInt()
            TechniqueVerdict.BEARISH -> (bearShare * 100.0).roundToInt()
            TechniqueVerdict.NEUTRAL ->
                (max(max(wBull, wBear), wNeut) / total * 100.0).roundToInt()
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

        val voteBasis =
            if (accuracyWeights.isNullOrEmpty()) "strength-weighted votes."
            else "votes, weighted by strength and each technique's measured 3-month accuracy on this stock."
        val summary = listOf(
            "$bullishCount of ${results.size} techniques read bullish, $bearishCount bearish, $neutralCount neutral.",
            "The leading side holds $confidence% of the $voteBasis",
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

    /** EMA over a series with leading nulls; defined from the first non-null run. */
    private fun emaNullable(values: List<Double?>, period: Int): List<Double?> {
        val n = values.size
        val out = arrayOfNulls<Double>(n)
        val firstIdx = values.indexOfFirst { it != null }
        if (firstIdx < 0) return out.toList()
        val defined = ArrayList<Double>(n - firstIdx)
        for (i in firstIdx until n) {
            // Our series have no holes after the first value; stop if one appears
            // rather than smoothing across it.
            val v = values[i] ?: break
            defined.add(v)
        }
        val ema = emaSeries(defined, period)
        for (j in ema.indices) out[firstIdx + j] = ema[j]
        return out.toList()
    }

    /** SMA over a nullable series; defined only where all [period] inputs are. */
    private fun smaNullable(values: List<Double?>, period: Int): List<Double?> {
        val n = values.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0) return out.toList()
        for (i in period - 1 until n) {
            var sum = 0.0
            var ok = true
            for (j in i - period + 1..i) {
                val v = values[j]
                if (v == null) {
                    ok = false
                    break
                }
                sum += v
            }
            if (ok) out[i] = sum / period
        }
        return out.toList()
    }

    /** Linearly weighted moving average; newest value carries weight [period]. */
    private fun wmaSeries(values: List<Double>, period: Int): List<Double?> {
        val n = values.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0 || n < period) return out.toList()
        val denom = period * (period + 1) / 2.0
        for (i in period - 1 until n) {
            var sum = 0.0
            for (j in 0 until period) {
                sum += values[i - period + 1 + j] * (j + 1)
            }
            out[i] = sum / denom
        }
        return out.toList()
    }

    /** WMA over a nullable series; defined only where all [period] inputs are. */
    private fun wmaNullable(values: List<Double?>, period: Int): List<Double?> {
        val n = values.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0) return out.toList()
        val denom = period * (period + 1) / 2.0
        for (i in period - 1 until n) {
            var sum = 0.0
            var ok = true
            for (j in 0 until period) {
                val v = values[i - period + 1 + j]
                if (v == null) {
                    ok = false
                    break
                }
                sum += v * (j + 1)
            }
            if (ok) out[i] = sum / denom
        }
        return out.toList()
    }

    /** Rolling highest high over [period] candles; null before index period-1. */
    private fun highSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0 || n < period) return out.toList()
        for (i in period - 1 until n) {
            var hi = candles[i].high
            for (j in i - period + 1 until i) if (candles[j].high > hi) hi = candles[j].high
            out[i] = hi
        }
        return out.toList()
    }

    /** Rolling lowest low over [period] candles; null before index period-1. */
    private fun lowSeries(candles: List<Candle>, period: Int): List<Double?> {
        val n = candles.size
        val out = arrayOfNulls<Double>(n)
        if (period <= 0 || n < period) return out.toList()
        for (i in period - 1 until n) {
            var lo = candles[i].low
            for (j in i - period + 1 until i) if (candles[j].low < lo) lo = candles[j].low
            out[i] = lo
        }
        return out.toList()
    }

    /** Ichimoku midpoint line: (highest high + lowest low) / 2 over [period] candles. */
    private fun midSeries(candles: List<Candle>, period: Int): List<Double?> {
        val hh = highSeries(candles, period)
        val ll = lowSeries(candles, period)
        return List(candles.size) { i ->
            val h = hh[i]
            val l = ll[i]
            if (h != null && l != null) (h + l) / 2.0 else null
        }
    }

    /** SMA(3) over a nullable series; defined only where all 3 inputs are. */
    private fun sma3Nullable(values: List<Double?>): List<Double?> {
        val n = values.size
        val out = arrayOfNulls<Double>(n)
        for (i in 2 until n) {
            val a = values[i - 2]
            val b = values[i - 1]
            val c = values[i]
            if (a != null && b != null && c != null) out[i] = (a + b + c) / 3.0
        }
        return out.toList()
    }

    /** Least-squares slope of [values] against their 0-based index. */
    private fun lsSlope(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val meanX = (n - 1) / 2.0
        var meanY = 0.0
        for (v in values) meanY += v
        meanY /= n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            num += dx * (values[i] - meanY)
            den += dx * dx
        }
        return if (den > 0.0) num / den else 0.0
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

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** CMF values are small signed fractions; two decimals with an explicit sign. */
    private fun fmtCmf(v: Double): String = String.format(Locale.US, "%+.2f", v)

    /** Adaptive precision for small MACD magnitudes (penny stocks). */
    private fun fmtSig(v: Double): String =
        if (abs(v) < 0.05) String.format(Locale.US, "%.3f", v)
        else String.format(Locale.US, "%.2f", v)

    private fun bars(n: Int): String = if (n == 1) "bar" else "bars"

    private fun touches(n: Int): String = if (n == 1) "touch" else "touches"

    private fun zones(n: Int): String = if (n == 1) "zone" else "zones"
}
