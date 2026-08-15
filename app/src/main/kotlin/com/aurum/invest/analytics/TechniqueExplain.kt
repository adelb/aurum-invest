package com.aurum.invest.analytics

import com.aurum.invest.core.Fmt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full written analysis of one technique, generated from the exact series that
 * were drawn on its chart. Pure Kotlin, never throws. Shown when the user taps
 * a diagram on the analysis screen.
 */
data class TechniqueDetail(
    val key: String,
    val title: String,
    val verdict: TechniqueVerdict,
    val strength: Int,
    /** What the technique measures — 2-3 plain sentences. */
    val whatItIs: String,
    /** What each element drawn on this chart is. */
    val drawn: List<String>,
    /** Current readings with concrete numbers, most important first. */
    val reading: List<String>,
    /** Labeled values to watch: (label, formatted value). */
    val levels: List<Pair<String, String>>,
    /** How a trader would act on this read. */
    val playbook: List<String>
)

object TechniqueExplain {

    /** Detail for the technique with [key], or null when the key is unknown. */
    fun detail(analysis: TechniqueAnalysis, key: String, price: Double): TechniqueDetail? {
        val result = analysis.results.firstOrNull { it.key == key } ?: return null
        return when (key) {
            "ma" -> ma(analysis, result, price)
            "rsi" -> rsi(analysis, result)
            "macd" -> macd(analysis, result)
            "bollinger" -> bollinger(analysis, result, price)
            "sr" -> sr(analysis, result, price)
            "fvg" -> fvg(analysis, result, price)
            "fib" -> fib(analysis, result, price)
            "ichimoku" -> ichimoku(analysis, result, price)
            "stoch" -> stoch(analysis, result)
            "obv" -> obv(analysis, result)
            "adx" -> adx(analysis, result)
            "donchian" -> donchian(analysis, result, price)
            "psar" -> psar(analysis, result, price)
            "mfi" -> mfi(analysis, result)
            "gc" -> gc(analysis, result, price)
            "willr" -> willr(analysis, result)
            "cci" -> cci(analysis, result)
            "keltner" -> keltner(analysis, result, price)
            "cmf" -> cmf(analysis, result)
            "aroon" -> aroon(analysis, result)
            "stochrsi" -> stochRsi(analysis, result)
            "roc" -> roc(analysis, result)
            "trix" -> trix(analysis, result)
            "uo" -> uo(analysis, result)
            "vortex" -> vortex(analysis, result)
            "efi" -> efi(analysis, result)
            "cmo" -> cmo(analysis, result)
            "dpo" -> dpo(analysis, result, price)
            "kst" -> kst(analysis, result)
            "hull" -> hull(analysis, result, price)
            "supertrend" -> supertrend(analysis, result, price)
            "chandelier" -> chandelier(analysis, result, price)
            "vwap" -> vwap(analysis, result, price)
            "ad" -> adLine(analysis, result)
            "pivot" -> pivot(analysis, result, price)
            else -> null
        }
    }

    // ---------------------------------------------------------------- ma

    private fun ma(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val s20 = a.maData.sma20.lastOrNull { it != null }
        val s50 = a.maData.sma50.lastOrNull { it != null }
        val reading = mutableListOf(r.summary)
        if (s20 != null) {
            val d = pctFrom(price, s20)
            reading += "Price sits ${fmtPct(abs(d))} ${aboveBelow(d)} the 20-day average — " +
                "the short-term trend anchor."
        }
        if (s20 != null && s50 != null) {
            val gap = pctFrom(s20, s50)
            reading += "The 20-day average is ${fmtPct(abs(gap))} ${aboveBelow(gap)} the 50-day; " +
                "a widening gap means the trend is gaining pace."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Moving averages smooth daily closes to expose the trend. When price " +
                "holds above a rising 20-day average and that average rides above the 50-day, " +
                "buyers control both the short and the medium term. The classic entry signal " +
                "is the 20-day crossing over the 50-day.",
            drawn = listOf(
                "Price — the daily closes (line or candles).",
                "Gold line — 20-day simple moving average, the short-term trend.",
                "Blue line — 50-day simple moving average, the medium-term trend."
            ),
            reading = reading,
            levels = buildList {
                add("Last close" to Fmt.money(price))
                s20?.let { add("20-day average" to Fmt.money(it)) }
                s50?.let { add("50-day average" to Fmt.money(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Trend is aligned up: pullbacks toward the 20-day average near ${s20?.let { Fmt.money(it) } ?: "—"} are the classic add points.",
                    "A close back under the 50-day average${s50?.let { " at ${Fmt.money(it)}" } ?: ""} would break the alignment — tighten stops there.",
                    "Avoid chasing when price stretches far above the 20-day; wait for it to come back."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Both averages are overhead resistance: rallies into ${s20?.let { Fmt.money(it) } ?: "the 20-day"} tend to stall in a downtrend.",
                    "New buying is early until price recloses above the 20-day and the 20 turns back over the 50.",
                    "If holding, a failed bounce at the averages is the standard reduce signal."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "The averages are tangled — wait for price to pick a side of both before committing.",
                    "Watch for a 20/50 cross: upward cross is a classic buy trigger, downward a warning."
                )
            }
        )
    }

    // ---------------------------------------------------------------- rsi

    private fun rsi(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val now = a.rsiData.rsi.lastOrNull { it != null }
        val prev5 = a.rsiData.rsi.let { s -> if (s.size >= 6) s[s.size - 6] else null }
        val reading = mutableListOf(r.summary)
        if (now != null && prev5 != null) {
            val dir = if (now >= prev5) "rising" else "falling"
            reading += "Momentum is $dir: RSI moved from ${fmt0(prev5)} to ${fmt0(now)} in five sessions."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "RSI(14) compares the size of recent up-days against down-days on a " +
                "0-100 scale. Under 30 the selling is usually overdone (oversold); over 70 " +
                "the buying is stretched (overbought). Between the lines, its direction shows " +
                "whether momentum is building or fading.",
            drawn = listOf(
                "Gold line — the 14-day RSI value.",
                "Shaded band — the 30-70 neutral zone.",
                "Dashed lines — the 30 oversold and 70 overbought thresholds."
            ),
            reading = reading,
            levels = buildList {
                now?.let { add("RSI now" to fmt0(it)) }
                prev5?.let { add("RSI 5 bars ago" to fmt0(it)) }
                add("Oversold line" to "30")
                add("Overbought line" to "70")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Oversold reads reward patience: the higher-probability entry is when RSI turns back up through 30, not while it is still falling.",
                    "Oversold can stay oversold in a downtrend — confirm with price holding a support level before sizing up."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Overbought plus a stall in price is the classic take-profit window.",
                    "New buying here has poor odds — wait for RSI to cool toward 50 or for a pullback to support."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Mid-band RSI carries little edge by itself — lean on the trend techniques for direction.",
                    "A push through 70 with the trend confirms strength; a slide under 30 flags capitulation to watch for a reversal."
                )
            }
        )
    }

    // ---------------------------------------------------------------- macd

    private fun macd(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val m = a.macdData.macd.lastOrNull { it != null }
        val s = a.macdData.signal.lastOrNull { it != null }
        val h = a.macdData.histogram.lastOrNull { it != null }
        val hPrev = a.macdData.histogram.let { seq ->
            if (seq.size >= 2) seq[seq.size - 2] else null
        }
        val reading = mutableListOf(r.summary)
        if (h != null && hPrev != null) {
            val dir = if (h >= hPrev) "expanding" else "shrinking"
            reading += "The histogram is $dir bar over bar — momentum is " +
                (if ((h >= hPrev) == (h >= 0.0)) "building with" else "leaking from") + " the current move."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "MACD subtracts the 26-day EMA from the 12-day EMA to measure trend " +
                "momentum, then smooths itself into a 9-day signal line. Crosses of the two " +
                "lines mark momentum turns; the histogram (their difference) shows the turn " +
                "coming before it happens.",
            drawn = listOf(
                "Gold line — MACD (12-day EMA minus 26-day EMA).",
                "Blue line — 9-day signal average of MACD.",
                "Green/red bars — the histogram, MACD minus signal.",
                "Dashed line — zero; above it the 12-day trend leads the 26-day."
            ),
            reading = reading,
            levels = buildList {
                m?.let { add("MACD" to fmtSig(it)) }
                s?.let { add("Signal" to fmtSig(it)) }
                h?.let { add("Histogram" to fmtSig(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Momentum is with the buyers — the standard play is to hold or add while the histogram keeps expanding.",
                    "A shrinking histogram is the early exit warning that arrives before the actual cross."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Momentum is with the sellers — bounces are suspect until MACD recrosses the signal line.",
                    "Watch the histogram: two consecutive higher bars is the first hint the slide is slowing."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "The lines are flat or tangled — treat any cross here as low-conviction until the histogram expands.",
                    "A cross above zero carries far more weight than one below it."
                )
            }
        )
    }

    // ---------------------------------------------------------------- bollinger

    private fun bollinger(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val u = a.bollingerData.upper.lastOrNull { it != null }
        val m = a.bollingerData.middle.lastOrNull { it != null }
        val l = a.bollingerData.lower.lastOrNull { it != null }
        val widthPct = if (u != null && l != null && m != null && m > 0.0) (u - l) / m * 100.0 else null
        val posPct = if (u != null && l != null && u - l > 1e-9)
            ((price - l) / (u - l) * 100.0).coerceIn(0.0, 100.0) else null
        val reading = mutableListOf(r.summary)
        posPct?.let {
            reading += "Price sits at ${fmt0(it)}% of the band span (0% = lower band, 100% = upper)."
        }
        widthPct?.let {
            reading += "Band width is ${fmt1(it)}% of the middle — " +
                (if (it < 4.0) "a tight squeeze; breakouts often follow." else "normal volatility.")
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Bollinger Bands wrap a 20-day average with lines two standard " +
                "deviations away, so roughly 95% of normal closes land inside. A close " +
                "outside a band is statistically stretched and tends to snap back; bands " +
                "squeezing tight signal stored energy before a breakout.",
            drawn = listOf(
                "Blue shaded band — the two-sigma envelope around the 20-day average.",
                "Dashed middle line — the 20-day average itself.",
                "Price — the daily closes (line or candles)."
            ),
            reading = reading,
            levels = buildList {
                u?.let { add("Upper band" to Fmt.money(it)) }
                m?.let { add("Middle (SMA 20)" to Fmt.money(it)) }
                l?.let { add("Lower band" to Fmt.money(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A close under the lower band is a stretched spring — mean-reversion entries target the middle line${m?.let { " near ${Fmt.money(it)}" } ?: ""}.",
                    "Wait for the first up-close before buying; catching the knife mid-slide is the common mistake."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "A close over the upper band is stretched — the fade targets the middle line${m?.let { " near ${Fmt.money(it)}" } ?: ""}.",
                    "In a strong uptrend price can ride the upper band; only fade when momentum (MACD/RSI) also rolls over."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    if (widthPct != null && widthPct < 4.0)
                        "The squeeze is on — set alerts at both bands; the direction of the break usually runs."
                    else
                        "Mid-band price carries no edge — combine with trend and momentum reads.",
                    "The middle line often acts as the working support/resistance inside the band."
                )
            }
        )
    }

    // ---------------------------------------------------------------- sr

    private fun sr(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val reading = mutableListOf(r.summary)
        a.srData.supports.lastOrNull()?.let {
            reading += "Nearest support ${Fmt.money(it)} sits ${fmtPct(pctFrom(price, it))} below the current price."
        }
        a.srData.resistances.firstOrNull()?.let {
            reading += "Nearest resistance ${Fmt.money(it)} sits ${fmtPct(pctFrom(it, price))} above the current price."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Support and resistance are prices where the stock repeatedly reversed " +
                "— floors where buyers stepped in and ceilings where sellers took over. The " +
                "more times a level held, the more traders watch it, which is what makes it " +
                "hold again or break with force.",
            drawn = listOf(
                "Green dashed lines — clustered swing-low floors (support).",
                "Red dashed lines — clustered swing-high ceilings (resistance).",
                "Price — the daily closes (line or candles); labels give each level's price."
            ),
            reading = reading,
            levels = a.srData.supports.map { "Support" to Fmt.money(it) } +
                a.srData.resistances.map { "Resistance" to Fmt.money(it) },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Buying just above a tested floor gives a clear line in the sand — the stop belongs just under that support.",
                    "If the floor breaks on a closing basis, exit fast; broken support becomes resistance."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Price is pressing into a tested ceiling — chasing here means buying where sellers repeatedly won.",
                    "The better long entry is either a clean breakout close above the ceiling or a pullback to the floor below."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Mid-range price means poor risk/reward in both directions — the edges are where the trades are.",
                    "Place limit orders at the floor rather than paying the middle of the range."
                )
            }
        )
    }

    // ---------------------------------------------------------------- fvg

    private fun fvg(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val unfilled = a.fvgData.zones.filter { !it.filled }
        val reading = mutableListOf(r.summary)
        reading += "${a.fvgData.zones.size} gaps tracked in the window, ${unfilled.size} still unfilled."
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "A fair value gap appears when price jumps so fast that one candle's " +
                "range never overlaps the candle two back, leaving a zone that never traded. " +
                "Price has a documented tendency to return and \"fill\" these zones, so " +
                "unfilled gaps below act as magnets and support, gaps above as targets and " +
                "resistance.",
            drawn = listOf(
                "Green bands — bullish gaps (potential support below price).",
                "Red bands — bearish gaps (potential resistance above price).",
                "Faded bands — gaps already filled; their pull is spent."
            ),
            reading = reading,
            levels = unfilled.takeLast(4).map {
                (if (it.bullish) "Bullish gap" else "Bearish gap") to
                    "${Fmt.money(it.low)}–${Fmt.money(it.high)}"
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "An unfilled gap just below is a favored limit-buy zone — orders inside the gap, stop under its low.",
                    "If price slices through the gap without pausing, the magnet failed; stand aside."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "An unfilled gap overhead often caps rallies — a sensible place to take profit or expect a stall.",
                    "A strong close through the gap converts it to fuel; the move usually extends."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "No unfilled gap near price — this technique has no pull to offer right now.",
                    "Fresh gaps form on earnings and news days; re-check after the next big candle."
                )
            }
        )
    }

    // ---------------------------------------------------------------- fib

    private fun fib(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val nearest = a.fibData.levels.minByOrNull {
            if (it.second > 0.0) abs(price - it.second) / it.second else Double.MAX_VALUE
        }
        val reading = mutableListOf(r.summary)
        reading += "Swing measured: ${Fmt.money(a.fibData.swingLow)} low to ${Fmt.money(a.fibData.swingHigh)} high."
        nearest?.let {
            reading += "Closest level is the ${it.first} retracement at ${Fmt.money(it.second)}."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Fibonacci retracement divides the last major swing into ratio levels " +
                "(23.6%, 38.2%, 50%, 61.8%, 78.6%) where pullbacks statistically pause. " +
                "Shallow pullbacks that hold 38.2-61.8% keep the uptrend alive; losing 78.6% " +
                "usually means the whole swing is being unwound.",
            drawn = listOf(
                "Gold dashed lines — the retracement ratios of the swing, labeled with price.",
                "Dim dashed lines — the swing's own low (1.0) and high (0.0).",
                "Price — the daily closes (line or candles)."
            ),
            reading = reading,
            levels = a.fibData.levels.map { "Level ${it.first}" to Fmt.money(it.second) },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Holding a golden-zone level (38.2-61.8%) is the classic buy-the-dip spot — stop goes under the next level down.",
                    "The bounce targets the swing high first; partial profits there are standard."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "The retracement failed — old fib supports now act as resistance overhead.",
                    "Wait for a base to form rather than guessing the bottom of a broken swing."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Price is between levels — let it come to a ratio rather than buying mid-air.",
                    "Set alerts at the 38.2% and 61.8% prices; those are the decision points."
                )
            }
        )
    }

    // ---------------------------------------------------------------- ichimoku

    private fun ichimoku(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val t = a.ichimokuData.tenkan.lastOrNull { it != null }
        val k = a.ichimokuData.kijun.lastOrNull { it != null }
        val sa = a.ichimokuData.senkouA.lastOrNull { it != null }
        val sb = a.ichimokuData.senkouB.lastOrNull { it != null }
        val top = if (sa != null && sb != null) max(sa, sb) else null
        val bottom = if (sa != null && sb != null) min(sa, sb) else null
        val reading = mutableListOf(r.summary)
        if (t != null && k != null) {
            reading += "Tenkan ${Fmt.money(t)} is ${if (t >= k) "above" else "below"} kijun ${Fmt.money(k)} — " +
                "the short-term impulse ${if (t >= k) "supports" else "fights"} the medium-term base."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Ichimoku is a complete trend system in one picture. The cloud — drawn " +
                "26 days ahead — is projected support/resistance; price above a rising cloud " +
                "is an uptrend, below it a downtrend, inside it no-man's-land. Tenkan over " +
                "kijun confirms short-term momentum agrees.",
            drawn = listOf(
                "Blue shaded area — the cloud (kumo) between the two projected span lines.",
                "Gold line — tenkan-sen, the 9-day midpoint (fast).",
                "Blue line — kijun-sen, the 26-day midpoint (slow).",
                "Price — the daily closes (line or candles)."
            ),
            reading = reading,
            levels = buildList {
                top?.let { add("Cloud top" to Fmt.money(it)) }
                bottom?.let { add("Cloud bottom" to Fmt.money(it)) }
                t?.let { add("Tenkan (9)" to Fmt.money(it)) }
                k?.let { add("Kijun (26)" to Fmt.money(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Above the cloud, dips to the kijun${k?.let { " near ${Fmt.money(it)}" } ?: ""} are the textbook add zone.",
                    "The cloud top${top?.let { " at ${Fmt.money(it)}" } ?: ""} is the trend's safety net — a close below it ends the bullish case."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Below the cloud every rally into it tends to get sold — the cloud bottom${bottom?.let { " at ${Fmt.money(it)}" } ?: ""} is the ceiling to beat.",
                    "The all-clear needs a close above the cloud top with tenkan over kijun."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Inside the cloud is chop — the system itself says stand aside.",
                    "Trade the exit: a clean close out of either side of the cloud usually starts the next leg."
                )
            }
        )
    }

    // ---------------------------------------------------------------- stoch

    private fun stoch(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val k = a.stochData.k.lastOrNull { it != null }
        val d = a.stochData.d.lastOrNull { it != null }
        val reading = mutableListOf(r.summary)
        if (k != null && d != null) {
            reading += "%K is ${if (k >= d) "above" else "below"} %D — the fast line " +
                "${if (k >= d) "leads up" else "leads down"}."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "The stochastic oscillator locates today's close inside the last 14 " +
                "days' high-low range. Closes pinned near the range top (over 80) mean " +
                "stretched buying; near the bottom (under 20) stretched selling. The " +
                "actionable signal is the fast %K crossing the slow %D inside those zones.",
            drawn = listOf(
                "Gold line — %K, the smoothed 14-day range position.",
                "Blue line — %D, the 3-day average of %K (the trigger line).",
                "Shaded band — the 20-80 neutral zone with dashed extremes."
            ),
            reading = reading,
            levels = buildList {
                k?.let { add("%K" to fmt0(it)) }
                d?.let { add("%D" to fmt0(it)) }
                add("Oversold" to "20")
                add("Overbought" to "80")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A %K-over-%D cross under 20 is one of the oldest swing entries — act on it while it is fresh.",
                    "The signal ages fast: if price does not follow within a few sessions, stand down."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "A %K-under-%D roll above 80 flags exhaustion — the take-profit or tighten-stop cue.",
                    "In strong trends the oscillator can stay pinned; confirm with a price-level break."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Mid-range stochastic offers no timing edge — wait for the extremes.",
                    "Use it as a filter: avoid new buys above 80, avoid panic sells below 20."
                )
            }
        )
    }

    // ---------------------------------------------------------------- obv

    private fun obv(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val obvNow = a.obvData.obv.lastOrNull()
        val n = a.obvData.obv.size
        val w = min(20, n)
        val delta = if (n >= w && w > 0) a.obvData.obv.last() - a.obvData.obv[n - w] else null
        val reading = mutableListOf(r.summary)
        delta?.let {
            reading += "Net ${if (it >= 0) "buying" else "selling"} volume of ${Fmt.compact(abs(it))} " +
                "over the last $w sessions."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "On-balance volume adds each day's volume on up-closes and subtracts " +
                "it on down-closes, exposing whether big money is accumulating or " +
                "distributing. Volume tends to move before price: OBV rising while price " +
                "stalls hints at quiet accumulation, and vice versa.",
            drawn = listOf(
                "Gold line — cumulative on-balance volume.",
                "Dim line — price rescaled onto the same pane, so divergences stand out."
            ),
            reading = reading,
            levels = buildList {
                obvNow?.let { add("OBV now" to Fmt.compact(it)) }
                delta?.let { add("20-bar change" to (if (it >= 0) "+" else "-") + Fmt.compact(abs(it))) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Accumulation divergence: volume is buying while price marks time — early positioning ahead of a mark-up is the classic read.",
                    "Confirmation is price breaking its nearest resistance on expanding volume."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Distribution divergence: price is up but volume is leaving — rallies on shrinking OBV are for selling into, not buying.",
                    "Tighten stops; divergences resolve suddenly."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "OBV agrees with price — volume neither adds nor subtracts conviction right now.",
                    "Watch for the divergence: OBV making a new high or low before price does is the tell."
                )
            }
        )
    }

    // ---------------------------------------------------------------- adx

    private fun adx(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val ax = a.adxData.adx.lastOrNull { it != null }
        val p = a.adxData.plusDi.lastOrNull { it != null }
        val m = a.adxData.minusDi.lastOrNull { it != null }
        val reading = mutableListOf(r.summary)
        ax?.let {
            reading += when {
                it >= 25.0 -> "ADX ${fmt0(it)} marks a genuine trend — trend-following signals carry full weight."
                it < 20.0 -> "ADX ${fmt0(it)} marks a rangebound market — mean-reversion beats trend-chasing here."
                else -> "ADX ${fmt0(it)} is in the gray zone — a trend may be forming but is not confirmed."
            }
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "ADX measures how strongly price is trending, regardless of direction; " +
                "the +DI and -DI lines say which side owns that trend. Over 25 the trend is " +
                "real and worth following; under 20 the market is drifting and breakout " +
                "signals routinely fail.",
            drawn = listOf(
                "Gold line — ADX, the strength of the trend (not its direction).",
                "Green line — +DI, upward directional pressure.",
                "Red line — -DI, downward directional pressure.",
                "Dashed line — the 25 threshold where a trend is confirmed."
            ),
            reading = reading,
            levels = buildList {
                ax?.let { add("ADX" to fmt0(it)) }
                p?.let { add("+DI" to fmt0(it)) }
                m?.let { add("-DI" to fmt0(it)) }
                add("Trend threshold" to "25")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A confirmed uptrend rewards holding winners — trail stops instead of taking quick profits.",
                    "A +DI/-DI cross against you while ADX stays high is the exit that usually pays."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "A confirmed downtrend punishes dip-buying — wait for ADX to fade or the DIs to recross before stepping in.",
                    "Strong ADX with -DI on top is when \"cheap keeps getting cheaper.\""
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "No trend to follow — fade the range edges and skip breakout entries until ADX pushes over 25.",
                    "A rising ADX from under 20 is the earliest hint the next real trend is starting."
                )
            }
        )
    }

    // ---------------------------------------------------------------- donchian

    private fun donchian(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val upper = a.donchianData.upper.lastOrNull()
        val lower = a.donchianData.lower.lastOrNull()
        val middle = a.donchianData.middle.lastOrNull()
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "The Donchian channel marks the highest high and lowest low of the " +
                "last 20 days. Richard Dennis's Turtle traders — one of the most profitable " +
                "trading experiments in history — bought every close above the channel top " +
                "and exited below the bottom, betting that fresh 20-day extremes start trends.",
            drawn = listOf(
                "Gold band — the 20-day high-to-low channel.",
                "Dashed middle line — the channel midpoint.",
                "Price — the daily closes (line or candles)."
            ),
            reading = listOf(r.summary),
            levels = buildList {
                upper?.let { add("Channel top (buy trigger)" to Fmt.money(it)) }
                middle?.let { add("Midpoint" to Fmt.money(it)) }
                lower?.let { add("Channel floor (exit trigger)" to Fmt.money(it)) }
                add("Last close" to Fmt.money(price))
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A close through the channel top is the Turtle entry — trend followers are in from here.",
                    "The system's exit is mechanical: a close back through the 10- or 20-day low, no debating."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "A close through the channel floor is the Turtle exit/short signal — fresh 20-day lows breed lower lows.",
                    "No bottom-guessing: the system waits for price to re-break the channel top before turning long."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Inside the channel there is no signal — set alerts at both edges and let the breakout decide.",
                    "The narrower the channel, the more explosive the eventual break tends to be."
                )
            }
        )
    }

    // ---------------------------------------------------------------- psar

    private fun psar(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val lastIdx = a.psarData.sar.indexOfLast { it != null }
        val sarV = if (lastIdx >= 0) a.psarData.sar[lastIdx] else null
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Welles Wilder's Parabolic SAR (stop-and-reverse) trails a dot behind " +
                "the trend that accelerates as the move extends. Price crossing its SAR dot " +
                "flips the trend call instantly — which is why traders use the dot as a " +
                "ready-made trailing stop that tightens automatically.",
            drawn = listOf(
                "Green dots — SAR below price: uptrend, dots act as the trailing stop.",
                "Red dots — SAR above price: downtrend.",
                "Price — the daily closes (line or candles)."
            ),
            reading = listOf(r.summary),
            levels = buildList {
                sarV?.let { add("SAR (trailing stop)" to Fmt.money(it)) }
                add("Last close" to Fmt.money(price))
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Ride the trend with the dot as your stop — exit only when price closes through the SAR.",
                    "A fresh flip (first 1-3 dots) is the entry with the most room; late in a run the dot hugs price and shakes you out."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Dots overhead mean every bounce has a mechanical seller at the SAR — wait for the flip before buying.",
                    "The SAR price itself is the reversal trigger to set an alert on."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "SAR whipsaws in sideways markets — confirm with ADX before trusting a flip.",
                    "Use the dot as a stop reference, not an entry, until a trend establishes."
                )
            }
        )
    }

    // ---------------------------------------------------------------- mfi

    private fun mfi(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val now = a.mfiData.mfi.lastOrNull { it != null }
        val prev5 = a.mfiData.mfi.let { s -> if (s.size >= 6) s[s.size - 6] else null }
        val reading = mutableListOf(r.summary)
        if (now != null && prev5 != null) {
            val dir = if (now >= prev5) "rising" else "falling"
            reading += "Money flow is $dir: MFI moved from ${fmt0(prev5)} to ${fmt0(now)} in five sessions."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "The Money Flow Index is RSI weighted by dollar volume: it tracks " +
                "whether real money is flowing in or out, not just whether price ticked up. " +
                "Because volume leads price, MFI extremes and divergences often fire a step " +
                "ahead of the plain RSI.",
            drawn = listOf(
                "Gold line — the 14-day Money Flow Index.",
                "Shaded band — the 20-80 neutral zone.",
                "Dashed lines — 20 (money-flow oversold) and 80 (overbought)."
            ),
            reading = reading,
            levels = buildList {
                now?.let { add("MFI now" to fmt0(it)) }
                prev5?.let { add("MFI 5 bars ago" to fmt0(it)) }
                add("Oversold" to "20")
                add("Overbought" to "80")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Sub-20 MFI says the dollar-volume selling is spent — the reversal entry is MFI turning back up through 20.",
                    "Strongest when plain RSI is still mid-range: volume saw the bottom first."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Over-80 MFI flags stretched dollar-volume buying — take profits into it rather than adding.",
                    "MFI rolling down through 80 while price makes a new high is a classic distribution warning."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Mid-band money flow adds nothing yet — watch for a divergence against price.",
                    "MFI making higher lows while price makes lower lows is quiet accumulation."
                )
            }
        )
    }

    // ---------------------------------------------------------------- gc

    private fun gc(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val s50 = a.gcData.sma50.lastOrNull()
        val s200 = a.gcData.sma200.lastOrNull()
        val reading = mutableListOf(r.summary)
        if (s50 != null && s200 != null) {
            val d = pctFrom(price, s200)
            reading += "Price sits ${fmtPct(abs(d))} ${aboveBelow(d)} the 200-day average — " +
                "Paul Tudor Jones's line between offense and defense."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "The 50/200-day moving-average relationship defines the market's " +
                "long-term regime. The 50 crossing above the 200 — the golden cross — has " +
                "opened most major bull runs; the death cross has preceded most deep " +
                "drawdowns. Institutions watch it because everyone else does, which is " +
                "exactly what keeps it working.",
            drawn = listOf(
                "Gold line — 50-day simple moving average (the institutional quarter).",
                "Blue line — 200-day simple moving average (the regime line).",
                "Price — the daily closes (line or candles)."
            ),
            reading = reading,
            levels = buildList {
                s50?.let { add("50-day average" to Fmt.money(it)) }
                s200?.let { add("200-day average" to Fmt.money(it)) }
                add("Last close" to Fmt.money(price))
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Golden-cross regime: dips toward the 50-day are the standard institutional add zone.",
                    "The regime holds until price and the 50-day both lose the 200-day — before that, sell-offs are usually noise."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Death-cross regime: rallies into the 200-day from below tend to fail — that is where trapped longs sell.",
                    "Tudor Jones's rule applies: below the 200-day, keep positions small and defensive."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "The averages disagree with price — the regime is being decided right now.",
                    "A weekly close on the far side of the 200-day usually settles it."
                )
            }
        )
    }

    // ---------------------------------------------------------------- willr

    private fun willr(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val now = a.willrData.r.lastOrNull { it != null }
        val prev5 = a.willrData.r.let { s -> if (s.size >= 6) s[s.size - 6] else null }
        val reading = mutableListOf(r.summary)
        if (now != null && prev5 != null) {
            val dir = if (now >= prev5) "rising" else "falling"
            reading += "The line is $dir: %R moved from ${fmt0(prev5)} to ${fmt0(now)} in five sessions."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Larry Williams' %R locates today's close inside the last 14 days' " +
                "high-low range on a -100..0 scale. Near 0 the stock keeps closing at the " +
                "top of its range (overbought); near -100 at the bottom (oversold). It is " +
                "the fastest of the range oscillators — it fires early, at the cost of " +
                "more noise.",
            drawn = listOf(
                "Gold line — the 14-day Williams %R.",
                "Shaded band — the -20 to -80 neutral zone.",
                "Dashed lines — -20 (overbought) and -80 (oversold)."
            ),
            reading = reading,
            levels = buildList {
                now?.let { add("%R now" to fmt0(it)) }
                prev5?.let { add("%R 5 bars ago" to fmt0(it)) }
                add("Overbought line" to "-20")
                add("Oversold line" to "-80")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "The higher-probability entry is %R climbing back OUT of the sub--80 zone, not sitting in it.",
                    "Williams himself waited for price confirmation — a close above the prior day's high — before acting."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Closes pinned near the range top fade eventually — take profits into strength rather than chasing.",
                    "In a strong uptrend %R can hug the -20 line for weeks; only fade with a trend technique agreeing."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Mid-range %R has no edge — wait for a tag of either extreme.",
                    "Use it as a timing overlay on the slower trend techniques, not on its own."
                )
            }
        )
    }

    // ---------------------------------------------------------------- cci

    private fun cci(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val now = a.cciData.cci.lastOrNull { it != null }
        val prev5 = a.cciData.cci.let { s -> if (s.size >= 6) s[s.size - 6] else null }
        val reading = mutableListOf(r.summary)
        if (now != null && prev5 != null) {
            val dir = if (now >= prev5) "rising" else "falling"
            reading += "CCI is $dir: from ${fmt0(prev5)} to ${fmt0(now)} in five sessions."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Donald Lambert's Commodity Channel Index measures how far the " +
                "typical price has strayed from its own 20-day average, in units of its " +
                "normal deviation. Lambert's original system is trend-following: a push " +
                "OVER +100 marks the start of a strong up-move worth riding, a drop under " +
                "-100 a strong down-move. Roughly 70-80% of the time CCI stays inside the " +
                "band — those readings carry no signal.",
            drawn = listOf(
                "Gold line — the 20-day CCI.",
                "Green dashed line — +100, the strong-uptrend trigger.",
                "Red dashed line — -100, the strong-downtrend trigger.",
                "Dim dashed line — zero, the 20-day average itself."
            ),
            reading = reading,
            levels = buildList {
                now?.let { add("CCI now" to fmt0(it)) }
                prev5?.let { add("CCI 5 bars ago" to fmt0(it)) }
                add("Uptrend trigger" to "+100")
                add("Downtrend trigger" to "-100")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Lambert's rule: long while CCI holds above +100; the exit is the drop back below it.",
                    "The further above +100, the stronger the move — but late entries above +200 have the worst risk/reward."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Below -100 the down-move is statistically established — dip-buying against it has poor odds.",
                    "The all-clear is CCI recrossing -100 from below; before that, bounces are suspect."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Inside the ±100 band CCI is silent by design — no trade from this technique.",
                    "Watch for the band exit: the direction of the first ±100 break tends to run."
                )
            }
        )
    }

    // ---------------------------------------------------------------- keltner

    private fun keltner(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val u = a.keltnerData.upper.lastOrNull { it != null }
        val m = a.keltnerData.middle.lastOrNull { it != null }
        val l = a.keltnerData.lower.lastOrNull { it != null }
        val reading = mutableListOf(r.summary)
        if (u != null && l != null && u - l > 1e-9) {
            val pos = ((price - l) / (u - l) * 100.0).coerceIn(-25.0, 125.0)
            reading += "Price sits at ${fmt0(pos)}% of the channel span (0% = lower band, 100% = upper)."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Keltner Channels wrap a 20-day EMA with bands two ATRs away — a " +
                "volatility-adjusted envelope of normal trading. Unlike Bollinger Bands " +
                "(which widen on the very volatility they measure), the ATR basis makes a " +
                "close OUTSIDE the channel a genuine breakout signal: price left its " +
                "normal envelope by more than its own volatility allows.",
            drawn = listOf(
                "Green shaded band — EMA(20) ± 2 x ATR(10).",
                "Dashed middle line — the 20-day EMA.",
                "Price — the daily closes (line or candles)."
            ),
            reading = reading,
            levels = buildList {
                u?.let { add("Upper channel" to Fmt.money(it)) }
                m?.let { add("Middle (EMA 20)" to Fmt.money(it)) }
                l?.let { add("Lower channel" to Fmt.money(it)) }
                add("Last close" to Fmt.money(price))
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A close above the channel is the trend-entry signal Linda Raschke built systems on — ride it with the EMA as the trailing reference.",
                    "The move is over when price closes back inside and loses the middle line."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "A close below the channel is a genuine breakdown, not noise — bounces toward the middle line tend to get sold.",
                    "Wait for a reclaim of the EMA before treating any bounce as a turn."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Inside the channel is normal trading — no breakout to act on.",
                    "Combine with Bollinger: Bollinger squeezing INSIDE Keltner is the classic pre-breakout compression."
                )
            }
        )
    }

    // ---------------------------------------------------------------- cmf

    private fun cmf(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val now = a.cmfData.cmf.lastOrNull { it != null }
        val prev5 = a.cmfData.cmf.let { s -> if (s.size >= 6) s[s.size - 6] else null }
        val reading = mutableListOf(r.summary)
        if (now != null && prev5 != null) {
            val dir = if (now >= prev5) "improving" else "deteriorating"
            reading += "Money flow is $dir: CMF moved from ${fmtCmfE(prev5)} to ${fmtCmfE(now)} in five sessions."
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Marc Chaikin's Money Flow asks WHERE inside each day's range the " +
                "stock closed, and weights the answer by volume over 20 days. Persistent " +
                "closes near daily highs on real volume (+CMF) are the footprint of " +
                "accumulation; closes near the lows (-CMF) of distribution. It reads " +
                "conviction, not direction — which is why it often disagrees with price " +
                "just before turns.",
            drawn = listOf(
                "Gold line — the 20-day Chaikin Money Flow.",
                "Green dashed line — +0.05, above which accumulation is meaningful.",
                "Red dashed line — -0.05, below which distribution is meaningful.",
                "Dim dashed line — zero."
            ),
            reading = reading,
            levels = buildList {
                now?.let { add("CMF now" to fmtCmfE(it)) }
                prev5?.let { add("CMF 5 bars ago" to fmtCmfE(it)) }
                add("Accumulation line" to "+0.05")
                add("Distribution line" to "-0.05")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Sustained positive CMF behind a flat price is quiet accumulation — the classic setup before a mark-up.",
                    "Strongest when CMF holds positive through a price dip: buyers absorbing the selling."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Negative CMF under a rising price is distribution — rallies are being sold into; tighten stops.",
                    "New buying is early until CMF recrosses zero."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Inside the ±0.05 band money flow is balanced — no conviction either way.",
                    "Watch the zero cross: it is the earliest tell that one side has taken over."
                )
            }
        )
    }

    // ---------------------------------------------------------------- aroon

    private fun aroon(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val up = a.aroonData.up.lastOrNull { it != null }
        val down = a.aroonData.down.lastOrNull { it != null }
        val reading = mutableListOf(r.summary)
        if (up != null && down != null) {
            reading += when {
                up >= 100.0 -> "Aroon up at 100 means a fresh 25-day high printed this bar."
                down >= 100.0 -> "Aroon down at 100 means a fresh 25-day low printed this bar."
                else -> "Neither extreme is fresh: the last 25-day high was ${fmt0((100 - up) * 0.25)} bars ago, the last low ${fmt0((100 - down) * 0.25)} bars ago."
            }
        }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Tushar Chande's Aroon asks a disarmingly simple question: how " +
                "recently did the stock print its 25-day high, and how recently its 25-day " +
                "low? Fresh highs with stale lows define an uptrend; the reverse a " +
                "downtrend. Because it measures TIME rather than distance, it spots " +
                "emerging trends earlier than moving averages do.",
            drawn = listOf(
                "Green line — Aroon up: 100 when the 25-day high is fresh, 0 when it is 25 bars old.",
                "Red line — Aroon down: the same clock for the 25-day low.",
                "Dashed lines — the 70/30 split that defines an established trend."
            ),
            reading = reading,
            levels = buildList {
                up?.let { add("Aroon up" to fmt0(it)) }
                down?.let { add("Aroon down" to fmt0(it)) }
                add("Trend threshold" to "70 / 30")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Up over 70 with down under 30 is an established uptrend — hold or add on dips while the split lasts.",
                    "The warning is Aroon up decaying below 70 without a new high: the trend is aging."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Down over 70 with up under 30 is an established downtrend — fresh lows keep printing; stand aside.",
                    "The first REAL signal of repair is Aroon up crossing above Aroon down."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "The lines are tangled — the market is deciding; the next 25-day extreme picks the winner.",
                    "An up/down cross is the earliest trend-change hint this technique gives."
                )
            }
        )
    }

    // ---------------------------------------------------------------- helpers

    // ---------------------------------------------------------------- stochrsi

    private fun stochRsi(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val k = a.stochRsiData.k.lastOrNull { it != null }
        val d = a.stochRsiData.d.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Stochastic RSI runs the stochastic formula on the RSI itself instead of " +
                "on price — momentum of momentum. It swings harder and turns earlier than plain " +
                "RSI, which is why short-term desks use it to time entries inside a trend the " +
                "slower tools already confirmed.",
            drawn = listOf(
                "Gold line — %K: where today's RSI sits inside its own 14-day range, smoothed over 3 bars.",
                "Blue line — %D: a 3-bar average of %K, the trigger line.",
                "Shaded band — the 20–80 neutral zone; signals fire at the extremes."
            ),
            reading = buildList {
                add(r.summary)
                if (k != null && d != null) {
                    add("%K ${fmt0(k)} vs %D ${fmt0(d)} — ${if (k >= d) "the fast line leads up" else "the fast line leads down"}.")
                }
                add("Because it is doubly derived, StochRSI whipsaws in flat tape — it earns its keep when a trend tool agrees.")
            },
            levels = buildList {
                k?.let { add("%K" to fmt0(it)) }
                d?.let { add("%D" to fmt0(it)) }
                add("Oversold line" to "20")
                add("Overbought line" to "80")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "The sub-20 upturn is the entry timing signal — take it only in the direction of the larger trend.",
                    "A push back over 20 confirms the turn; a failure back down cancels it.",
                    "Pair with the MA or Supertrend read: countertrend StochRSI turns are the ones that fail."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "The over-80 rollover warns the short-term push is exhausted — tighten stops on quick trades.",
                    "In a strong uptrend an over-80 reading alone is not a sell; wait for the %K/%D cross.",
                    "A drop back under 80 confirms the fade."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Mid-range StochRSI carries no edge — let it reach an extreme before acting.",
                    "Watch for %K pinning above 80 in strong trends: that is strength, not an automatic sell."
                )
            }
        )
    }

    // ---------------------------------------------------------------- roc

    private fun roc(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val v = a.rocData.roc.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Rate of Change is the purest momentum gauge: today's close against the " +
                "close 12 sessions ago, as a percentage. Above zero the stock is gaining ground, " +
                "below zero it is losing it — and the zero-line cross is the classic momentum " +
                "turn signal.",
            drawn = listOf(
                "Gold line — ROC(12), the 12-session percentage change.",
                "Dashed line — zero: the boundary between gaining and losing ground."
            ),
            reading = buildList {
                add(r.summary)
                v?.let {
                    add("A reading of ${fmt1(it)}% means \$100 held 12 sessions ago is worth ${Fmt.money(100.0 * (1.0 + it / 100.0))} today.")
                }
            },
            levels = buildList {
                v?.let { add("ROC(12)" to "${fmt1(it)}%") }
                add("Momentum boundary" to "0%")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Positive and rising ROC backs momentum entries; the trade thesis holds while ROC holds above zero.",
                    "Extreme ROC readings (far above the last months' peaks) warn the move is stretched — scale out, don't pile in.",
                    "A lower ROC high while price makes a higher high is the divergence that precedes stalls."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Negative ROC says the last 12 sessions lost money — buying against it needs a reversal signal, not hope.",
                    "The zero-line recross from below is the earliest honest turn signal to wait for.",
                    "Rallies while ROC stays negative are bounces inside a decline until proven otherwise."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "ROC near zero means the last 12 sessions went nowhere — momentum offers no edge here.",
                    "Watch the next decisive zero cross; enter with it, not before it."
                )
            }
        )
    }

    // ---------------------------------------------------------------- trix

    private fun trix(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val t = a.trixData.trix.lastOrNull { it != null }
        val s = a.trixData.signal.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "TRIX smooths price three times with a 15-day EMA, then plots the daily " +
                "percentage change of that triple-smoothed line. The triple pass strips almost " +
                "all the noise: what remains is the underlying trend's direction and its " +
                "acceleration, which is why trend desks use TRIX as a low-whipsaw filter.",
            drawn = listOf(
                "Gold line — TRIX(15): the 1-bar % change of the triple EMA.",
                "Blue line — the EMA(9) signal of TRIX.",
                "Dashed line — zero: trend up above it, trend down below."
            ),
            reading = buildList {
                add(r.summary)
                if (t != null && s != null) {
                    add("TRIX ${fmtSig(t)} vs signal ${fmtSig(s)} — ${if (t > s) "the smoothed trend is accelerating" else "the smoothed trend is decelerating"}.")
                }
            },
            levels = buildList {
                t?.let { add("TRIX" to fmtSig(it)) }
                s?.let { add("Signal" to fmtSig(it)) }
                add("Trend boundary" to "0")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "TRIX above its signal and above zero is the full bullish alignment — trend entries have the filter's blessing.",
                    "Because TRIX lags by design, use a faster tool for the entry price; TRIX defines the regime.",
                    "Exit the regime call when TRIX closes back under its signal line."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "TRIX below its signal and falling says the smoothed trend is down — treat rallies as suspect.",
                    "The signal-line recross from below is the earliest re-entry trigger worth waiting for.",
                    "A TRIX zero-line recross confirms a full regime change, not just a bounce."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "TRIX pinned to its signal means the smoothed trend is flat — no regime call.",
                    "Let the next decisive signal-line cross pick the direction."
                )
            }
        )
    }

    // ---------------------------------------------------------------- uo

    private fun uo(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val v = a.uoData.uo.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Larry Williams' Ultimate Oscillator measures buying pressure — how far " +
                "each close sits above its true low — across three timeframes at once (7, 14 and " +
                "28 days), blended 4:2:1. Using three windows was Williams' fix for the false " +
                "divergences that plague single-period oscillators.",
            drawn = listOf(
                "Gold line — the blended buying-pressure oscillator, 0..100.",
                "Shaded band — the 30–70 neutral zone; extremes fire outside it."
            ),
            reading = buildList {
                add(r.summary)
                v?.let {
                    add("At ${fmt0(it)}, closes are landing ${if (it >= 50) "nearer their true highs" else "nearer their true lows"} across all three windows.")
                }
            },
            levels = buildList {
                v?.let { add("Ultimate Oscillator" to fmt0(it)) }
                add("Oversold line" to "30")
                add("Overbought line" to "70")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A sub-30 reading marks washed-out selling on all three timeframes — the highest-probability zone Williams traded from.",
                    "His full signal adds a bullish divergence (price lower low, UO higher low) and a break of the divergence high.",
                    "Scale in rather than betting the turn on one bar."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Over 70, buying pressure is stretched on every window — chasing here pays the worst prices.",
                    "Watch for a bearish divergence: price higher high with a lower UO high is the distribution tell.",
                    "Existing longs tighten stops rather than add."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Inside 30–70 the oscillator has no edge; direction belongs to the trend tools.",
                    "Track the 50 line: holding above it keeps the pressure balance with the buyers."
                )
            }
        )
    }

    // ---------------------------------------------------------------- vortex

    private fun vortex(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val p = a.vortexData.plus.lastOrNull { it != null }
        val m = a.vortexData.minus.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "The Vortex Indicator tracks upward and downward range expansion: VI+ " +
                "sums how far each high stretches above the prior low, VI- how far each low " +
                "drops below the prior high, both normalized by true range over 14 days. The " +
                "line that dominates names the trend; their crosses mark its turns.",
            drawn = listOf(
                "Green line — VI+, upward range expansion.",
                "Red line — VI-, downward range expansion.",
                "Dashed line — 1.0: a vortex line above it is genuinely expanding."
            ),
            reading = buildList {
                add(r.summary)
                if (p != null && m != null) {
                    add("Spread ${fmt2(abs(p - m))} between the lines — ${if (abs(p - m) >= 0.15) "a decisive gap" else "a narrow gap that flips easily"}.")
                }
            },
            levels = buildList {
                p?.let { add("VI+" to fmt2(it)) }
                m?.let { add("VI-" to fmt2(it)) }
                add("Expansion line" to "1.00")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "VI+ in command backs trend-following entries; hold the bias while VI+ stays on top.",
                    "The VI cross is prone to whipsaw in chop — require the crossing line above 1.0, as here.",
                    "Exit the bias when the lines cross back, not on the first red day."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "VI- in command says down-days are expanding range faster — rallies are for reducing, not chasing.",
                    "Wait for VI+ to retake the top line before treating strength as a turn.",
                    "Pair with ADX: a rising ADX behind a VI- lead is a trend, not a dip."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Both lines hugging 1.0 means neither side expands range — a rotation, not a trend.",
                    "The next decisive cross with follow-through names the new trend."
                )
            }
        )
    }

    // ---------------------------------------------------------------- efi

    private fun efi(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val f = a.forceData.force.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Alexander Elder's Force Index multiplies each day's price change by its " +
                "volume: a small move on huge volume carries more force than a big move on air. " +
                "Smoothed with a 13-day EMA it shows which side — bulls or bears — is actually " +
                "committing money, not just moving price.",
            drawn = listOf(
                "Gold line — Force Index, EMA(13)-smoothed price-change x volume.",
                "Dashed line — zero: bull force above, bear force below."
            ),
            reading = buildList {
                add(r.summary)
                f?.let {
                    add("The current force reads ${(if (it >= 0) "+" else "-") + Fmt.compact(abs(it))} — sign says who is winning, size says how hard they press.")
                }
            },
            levels = buildList {
                f?.let { add("Force Index" to (if (it >= 0) "+" else "-") + Fmt.compact(abs(it))) }
                add("Balance line" to "0")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Positive, rising force backs the uptrend — Elder bought pullbacks while the 13-day force held positive.",
                    "A force spike far above recent peaks often marks short-term climax — pause adds there.",
                    "Divergence check: price higher high on weaker force is the fade warning."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Negative, falling force says sellers commit real volume — bounces without force behind them fail.",
                    "Wait for force to recross zero before trusting a bottom.",
                    "Elder's rule: trade only in the direction the 13-day force points."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Force near zero means neither side presses with volume — a truce, not a trend.",
                    "The next sustained push away from zero picks the tradable side."
                )
            }
        )
    }

    // ---------------------------------------------------------------- cmo

    private fun cmo(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val v = a.cmoData.cmo.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Tushar Chande's Momentum Oscillator nets 14 days of up-moves against " +
                "down-moves on a -100..+100 scale. Unlike RSI it is unsmoothed and symmetric, " +
                "so it reaches its ±50 extreme zones faster — Chande designed it to catch " +
                "overbought and oversold conditions RSI reports late.",
            drawn = listOf(
                "Gold line — CMO(14) on the -100..+100 scale.",
                "Dashed lines — Chande's +50 overbought and -50 oversold triggers.",
                "Shaded band — the ±50 neutral zone."
            ),
            reading = buildList {
                add(r.summary)
                v?.let {
                    add("At ${fmt0(it)}, ${fmt0((it + 100.0) / 2.0)}% of the last 14 days' total movement was upward.")
                }
            },
            levels = buildList {
                v?.let { add("CMO(14)" to fmt0(it)) }
                add("Overbought" to "+50")
                add("Oversold" to "-50")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Below -50 the selling is one-sided to the point of exhaustion — the reversion zone Chande targeted.",
                    "Enter on the turn back toward -50, not on the extreme itself; falling knives print -80 too.",
                    "Confirm with a support or VWAP test so the bounce has a floor under it."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Above +50 the buying is one-sided — late chasers supply the next pullback.",
                    "In strong trends CMO can pin high; sell signals need the turn back under +50.",
                    "Tighten stops on extended longs rather than shorting strength blindly."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Inside ±50 the days are two-sided — no exhaustion to trade against.",
                    "Use CMO's zero line as the momentum lean while waiting for an extreme."
                )
            }
        )
    }

    // ---------------------------------------------------------------- dpo

    private fun dpo(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val v = a.dpoData.dpo.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "The Detrended Price Oscillator subtracts a displaced 20-day average from " +
                "price, deliberately removing the longer trend so what remains is the short " +
                "CYCLE — the rhythm of swings a few weeks long. Cycle traders use it to time " +
                "entries inside a trend the other tools own.",
            drawn = listOf(
                "Gold line — DPO(20): price minus the 20-day average from 11 bars back.",
                "Dashed line — zero: the cycle's midpoint."
            ),
            reading = buildList {
                add(r.summary)
                if (v != null && price > 0.0) {
                    add("The cycle swing is worth ${fmt1(abs(v) / price * 100.0)}% of price right now.")
                }
            },
            levels = buildList {
                v?.let { add("DPO(20)" to fmtSig(it)) }
                add("Cycle midpoint" to "0")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A rising cycle from below zero is the swing-entry window — buy the upswing, not the peak.",
                    "Measure past DPO peaks: the cycle tends to top near the same amplitude.",
                    "Trade the cycle only in the direction of the larger trend."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "A falling cycle from above zero says the short swing is rolling down — poor timing for fresh buys.",
                    "The zero recross from above usually runs to the prior trough's amplitude.",
                    "Wait for the downswing to trough before timing the next entry."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "The cycle sits at its midpoint with no push — timing edge is absent.",
                    "Note the recent DPO peaks and troughs: they sketch where the next swing should stall."
                )
            }
        )
    }

    // ---------------------------------------------------------------- kst

    private fun kst(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        val k = a.kstData.kst.lastOrNull { it != null }
        val s = a.kstData.signal.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Martin Pring's Know Sure Thing blends four smoothed rate-of-change " +
                "readings (10, 15, 20 and 30 days), weighting the longest most. One line then " +
                "carries the whole momentum spectrum — short swings can't fake it, and a " +
                "signal-line cross means several timeframes turned together.",
            drawn = listOf(
                "Gold line — KST: the 1:2:3:4 weighted sum of four smoothed ROCs.",
                "Blue line — its SMA(9) signal.",
                "Dashed line — zero: net momentum positive above, negative below."
            ),
            reading = buildList {
                add(r.summary)
                if (k != null && s != null) {
                    add("KST ${fmt1(k)} vs signal ${fmt1(s)} — the momentum spectrum is ${if (k > s) "improving" else "deteriorating"}.")
                }
            },
            levels = buildList {
                k?.let { add("KST" to fmt1(it)) }
                s?.let { add("Signal" to fmt1(it)) }
                add("Zero line" to "0")
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "KST over its signal with both above zero is Pring's full buy alignment — momentum agrees on every window.",
                    "Crosses below zero are early: real, but they need price confirmation before full size.",
                    "Hold the bias until KST closes back under its signal."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "KST under its signal says the blended momentum rolled over — distribute into strength.",
                    "The deeper below zero, the more repair the stock needs before trend buys make sense.",
                    "Wait for the signal recross; front-running it is guessing."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "KST pinned to its signal carries no verdict — the momentum spectrum is undecided.",
                    "The next clean cross, especially near the zero line, is the actionable event."
                )
            }
        )
    }

    // ---------------------------------------------------------------- hull

    private fun hull(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val h = a.hullData.hull.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Alan Hull's moving average nearly eliminates the lag that makes normal " +
                "averages late: it extrapolates from two weighted averages, then smooths over " +
                "the square root of the period. The result hugs price yet stays smooth — its " +
                "own turn in direction is the signal.",
            drawn = listOf(
                "Price — the daily closes (line or candles).",
                "Gold line — HMA(20), the Hull moving average."
            ),
            reading = buildList {
                add(r.summary)
                if (h != null) {
                    val d = pctFrom(price, h)
                    add("Price sits ${fmtPct(abs(d))} ${aboveBelow(d)} the HMA — with Hull, the average's own slope matters as much as which side price is on.")
                }
            },
            levels = buildList {
                add("Last close" to Fmt.money(price))
                h?.let { add("HMA(20)" to Fmt.money(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Price above a rising HMA is the aligned read — pullbacks that hold the line are add points.",
                    "The HMA turning down while price holds is the earliest caution flag; a close below it confirms.",
                    "Because HMA is fast, confirm regime with the slower 50/200 read before sizing up."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Price below a falling HMA keeps sellers in charge — rallies into the line tend to stall there.",
                    "The HMA's own upturn is the first honest turn signal; price reclosing above it is the second.",
                    "Do not fade a falling HMA on hope alone."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Price and the HMA disagree — one is turning. Let them realign before acting.",
                    "Watch the HMA slope: its flip usually precedes the price cross."
                )
            }
        )
    }

    // ---------------------------------------------------------------- supertrend

    private fun supertrend(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val idx = a.supertrendData.line.indexOfLast { it != null }
        val line = if (idx >= 0) a.supertrendData.line[idx] else null
        val up = idx >= 0 && a.supertrendData.bullish[idx] == true
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Supertrend draws a single ratcheting line 3 ATRs from the bar midpoint: " +
                "below price in an uptrend, above it in a downtrend. The line only tightens, " +
                "never loosens, and a close through it flips the regime — one unambiguous " +
                "trend state plus a ready-made trailing stop.",
            drawn = listOf(
                "Price — the daily closes (line or candles).",
                "Green segments — the line under price: uptrend regime, and the long trailing stop.",
                "Red segments — the line over price: downtrend regime, and the short trailing stop."
            ),
            reading = buildList {
                add(r.summary)
                if (line != null) {
                    add("The flip level is ${Fmt.money(line)} — a daily close ${if (up) "below" else "above"} it changes the regime.")
                }
            },
            levels = buildList {
                add("Last close" to Fmt.money(price))
                line?.let { add((if (up) "Trailing stop / flip" else "Overhead line / flip") to Fmt.money(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Hold longs while price stays above the line; the line IS the exit — no second-guessing.",
                    "Fresh flips give the best entries; late in a regime, buy pullbacks toward the line instead of chasing.",
                    "The 3-ATR gap means normal noise won't stop you out — but honor the flip when it comes."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "The line overhead caps rallies — the trend is down until a close above it proves otherwise.",
                    "The flip level is the exact number to watch; above it, shorts cover and longs re-engage.",
                    "Avoid anticipating the flip; the ratchet exists to keep you out of premature bottoms."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Not enough history to seed the ratchet — no regime call yet."
                )
            }
        )
    }

    // ---------------------------------------------------------------- chandelier

    private fun chandelier(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val long = a.chandelierData.longStop.lastOrNull { it != null }
        val short = a.chandelierData.shortStop.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Chuck LeBeau's Chandelier Exit hangs a trailing stop 3 ATRs below the " +
                "22-day high (for longs) or above the 22-day low (for shorts). It answers the " +
                "hardest question in trading — where to get out — with volatility, not emotion: " +
                "the stop gives the trade room exactly proportional to how much the stock moves.",
            drawn = listOf(
                "Price — the daily closes (line or candles).",
                "Green dashed line — the long exit: 22-day high minus 3 ATR(22).",
                "Red dashed line — the short exit: 22-day low plus 3 ATR(22)."
            ),
            reading = buildList {
                add(r.summary)
                if (long != null && price > 0.0) {
                    add("The long exit sits ${fmtPct(abs(pctFrom(long, price)))} away — that distance is the trade's built-in risk budget.")
                }
            },
            levels = buildList {
                add("Last close" to Fmt.money(price))
                long?.let { add("Long exit" to Fmt.money(it)) }
                short?.let { add("Short exit" to Fmt.money(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Longs stay on while price holds above the green line — that line is the stop, placed the day you buy.",
                    "The exit only rises as new highs print; never move it down to 'give the trade room'.",
                    "A close below the exit is the trade over — LeBeau's whole point is not to negotiate with it."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Price under the short exit keeps the downtrend's stop overhead — longs here fight the exit math.",
                    "A close back above the red line is the cover/turn signal.",
                    "Fresh longs wait until the long exit is reclaimed and can be placed at a sane distance."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Price sits between both exits — neither side's trailing stop protects a position here.",
                    "Enter only when a close puts one exit cleanly behind the trade."
                )
            }
        )
    }

    // ---------------------------------------------------------------- vwap

    private fun vwap(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val v = a.vwapData.vwap.lastOrNull { it != null }
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "VWAP is the volume-weighted average price — the true average cost of " +
                "every share that changed hands over the window. Institutions benchmark their " +
                "fills against it, which makes the rolling 20-day VWAP a living map of where " +
                "the big money's cost basis sits.",
            drawn = listOf(
                "Price — the daily closes (line or candles).",
                "Gold line — the rolling 20-day VWAP on the typical price."
            ),
            reading = buildList {
                add(r.summary)
                if (v != null) {
                    val d = pctFrom(price, v)
                    add(
                        if (d >= 0.0) "Above VWAP, the average recent buyer is in profit — dips toward the line meet buyers defending their basis."
                        else "Below VWAP, the average recent buyer is underwater — rallies into the line meet sellers happy to break even."
                    )
                }
            },
            levels = buildList {
                add("Last close" to Fmt.money(price))
                v?.let { add("VWAP(20)" to Fmt.money(it)) }
            },
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Pullbacks to the VWAP from above are the institutional buy zone — the highest-quality dip entries.",
                    "A high-volume close below VWAP is the warning that the basis broke; respect it.",
                    "Far above VWAP, entries are paying a premium to the big money — wait for the line to catch up."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Rallies into VWAP from below are where trapped buyers sell out — expect supply there.",
                    "Only a decisive reclaim of VWAP on volume flips the read.",
                    "Below a falling VWAP, patience beats knife-catching."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Price sits at the cost basis — the argument between buyers and sellers is live at this exact level.",
                    "The break away from VWAP on volume picks the next leg; trade with it."
                )
            }
        )
    }

    // ---------------------------------------------------------------- ad line

    private fun adLine(a: TechniqueAnalysis, r: TechniqueResult): TechniqueDetail {
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Marc Chaikin's Accumulation/Distribution line asks WHERE inside its " +
                "daily range each close landed, weights that by volume, and keeps a running " +
                "total. Closes near the highs on volume add; closes near the lows subtract. " +
                "Its divergences from price expose quiet accumulation and distribution before " +
                "price confesses.",
            drawn = listOf(
                "Gold line — the cumulative A/D line, scaled to the pane.",
                "Faint line — price over the same window, scaled to its own range."
            ),
            reading = listOf(
                r.summary,
                "A/D differs from OBV by using the close's position INSIDE the range, not just up-day vs down-day — it catches days where price closed down but buyers absorbed the volume high in the range."
            ),
            levels = emptyList(),
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "A rising A/D under a flat or falling price is the accumulation divergence — someone is buying the dips with size.",
                    "Enter on price confirmation (a resistance break), not on the divergence alone.",
                    "The divergence thesis dies if A/D rolls over with price."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "A falling A/D under a rising price is distribution — strength is being sold into.",
                    "Tighten stops on longs; breakouts without A/D support are the ones that fail.",
                    "Only an A/D turn back up repairs the read."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "A/D moves with price — volume confirms but adds no divergence edge.",
                    "Keep watching: the divergences are the only A/D signals worth money."
                )
            }
        )
    }

    // ---------------------------------------------------------------- pivot

    private fun pivot(a: TechniqueAnalysis, r: TechniqueResult, price: Double): TechniqueDetail {
        val pd = a.pivotData
        return TechniqueDetail(
            key = r.key, title = r.name, verdict = r.verdict, strength = r.strength,
            whatItIs = "Floor-trader pivots come from the exchange floors: the prior month's " +
                "high, low and close define a balance point (P) with two resistance rungs above " +
                "(R1, R2) and two support rungs below (S1, S2). Because thousands of desks " +
                "compute the identical levels, orders cluster there — the levels work partly " +
                "because everyone watches them.",
            drawn = listOf(
                "Price — the daily closes (line or candles).",
                "Green dashed lines — the pivot rungs below price.",
                "Red dashed lines — the pivot rungs above price."
            ),
            reading = buildList {
                add(r.summary)
                if (pd.valid) {
                    add("This month's map: S2 ${Fmt.money(pd.s2)} · S1 ${Fmt.money(pd.s1)} · P ${Fmt.money(pd.pivot)} · R1 ${Fmt.money(pd.r1)} · R2 ${Fmt.money(pd.r2)}.")
                }
            },
            levels = if (pd.valid) listOf(
                "R2" to Fmt.money(pd.r2),
                "R1" to Fmt.money(pd.r1),
                "Pivot" to Fmt.money(pd.pivot),
                "S1" to Fmt.money(pd.s1),
                "S2" to Fmt.money(pd.s2)
            ) else emptyList(),
            playbook = when (r.verdict) {
                TechniqueVerdict.BULLISH -> listOf(
                    "Above the pivot the month's bias is long — dips to P are the classic buy zone with a stop just beneath it.",
                    "R1 is the first profit rung; through R1 on volume, R2 becomes the stretch target.",
                    "Lose the pivot on a close and the bias resets to neutral."
                )
                TechniqueVerdict.BEARISH -> listOf(
                    "Below the pivot the month's bias is short — rallies to P are the fade zone.",
                    "S1 is the first downside rung; through it, S2 opens.",
                    "Only a close back above P flips the monthly bias."
                )
                TechniqueVerdict.NEUTRAL -> listOf(
                    "Price sits at a decision rung — pivots are reaction levels, so let the reaction print first.",
                    "Range traders fade the outer rungs (R2/S2); breakout traders need a close through them."
                )
            }
        )
    }

    private fun pctFrom(a: Double, b: Double): Double =
        if (b != 0.0) (a - b) / b * 100.0 else 0.0

    private fun aboveBelow(deltaPct: Double): String = if (deltaPct >= 0.0) "above" else "below"

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun fmtPct(v: Double): String = String.format(Locale.US, "%.1f%%", abs(v))

    private fun fmtSig(v: Double): String =
        if (abs(v) < 0.05) String.format(Locale.US, "%.3f", v)
        else String.format(Locale.US, "%.2f", v)

    private fun fmtCmfE(v: Double): String = String.format(Locale.US, "%+.2f", v)
}
