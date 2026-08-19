package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.Fundamentals
import com.aurum.invest.data.model.NewsItem
import com.aurum.invest.data.model.Quote
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------- outputs

/** One graded factor of the study, [score] null when unmeasured. */
data class StudyFactor(
    val key: String,
    val label: String,
    val score: Int?,
    val maxScore: Int,
    val detail: String
)

/**
 * The 21-session price projection: the measured distribution of what THIS
 * stock's own comparable past sessions did over the following month. A range
 * with named percentiles — never a single promised number.
 */
data class PriceProjection(
    val horizonSessions: Int,
    /** Which analog tier produced it — printed with the numbers. */
    val basis: String,
    val analogCount: Int,
    /** True when the analogs share today's trend/RSI/volatility regime. */
    val conditioned: Boolean,
    val medianPct: Double,
    val q1Pct: Double,
    val q3Pct: Double,
    val p10Pct: Double,
    val p90Pct: Double,
    val medianPrice: Double,
    val q1Price: Double,
    val q3Price: Double,
    val p10Price: Double,
    val p90Price: Double,
    /** Share of analog months that ended higher. */
    val upSharePct: Double,
    /** σ√21 of recent daily returns — the month's typical swing, for context. */
    val monthlyVolPct: Double?
)

/** One return span, stock vs benchmark; null = not enough history. */
data class SpanReturn(
    val label: String,
    val sessions: Int,
    val stockPct: Double?,
    val spyPct: Double?
)

/** The whole study of one stock. */
data class StockStudy(
    val computedAt: Long,
    val symbol: String,
    val name: String,
    val price: Double,
    /** 0..100 fixed scale; null below [StockStudyEngine.MIN_COVERAGE_PCT]% measured. */
    val grade: Int?,
    val gradeBand: String,
    val coveragePct: Double,
    val factors: List<StudyFactor>,
    val projection: PriceProjection?,
    val spans: List<SpanReturn>,
    val volatilityPct: Double?,
    val beta: Double?,
    val maxDrawdownPct: Double?,
    val below52HighPct: Double?,
    val boardBullish: Int,
    val boardTotal: Int,
    val boardVerdict: TechniqueVerdict,
    val fundamentals: Fundamentals?,
    /** The street's numbers, labeled external — never mixed into ours. */
    val analystNote: String,
    val newsNote: String,
    val headline: String,
    val notes: List<String>,
    val caveat: String
)

// ---------------------------------------------------------------- inputs

data class StudyInputs(
    val symbol: String,
    val name: String,
    val quote: Quote?,
    /** Two years of dailies — the analog pool AND the indicator warm-up. */
    val candles: List<Candle>,
    val spy: List<Candle>,
    val fundamentals: Fundamentals?,
    val news: List<NewsItem>,
    val vix: Double?
)

/**
 * The stock study engine: one name, fully evaluated for a trader who wants
 * the evidence, and a one-month price projection built the only honest way —
 * from the stock's OWN measured history.
 *
 * The projection: every past session in the two-year window is classified by
 * regime (trend structure × RSI band × volatility band); the sessions that
 * looked like TODAY are replayed 21 sessions forward, and the outcomes'
 * percentiles become the projected range. When too few analogs share the full
 * regime the match is relaxed one dimension at a time — and the basis line
 * says exactly which tier the numbers came from. Below [MIN_HISTORY_BARS]
 * bars no projection is claimed at all.
 *
 * The grade: seven factors on a fixed 100-point scale — trend structure,
 * momentum against SPY, the 35-technique board, volume behavior, volatility
 * quality, fundamentals (balance sheet, margins, growth, valuation), and
 * headline tone. Unmeasured factors contribute nothing and narrow the claim;
 * below [MIN_COVERAGE_PCT]% measured weight the grade is withheld.
 *
 * Integrity rules (house law):
 *  - pure function over [StudyInputs]; no I/O, never throws
 *  - a projection is a DISTRIBUTION of measured outcomes, never a promise;
 *    the analog count and tier are printed beside every number
 *  - the street's target is shown labeled as the street's, never blended
 */
object StockStudyEngine {

    /** Below this share of measured factor weight, no grade is claimed. */
    const val MIN_COVERAGE_PCT = 60.0

    /** The projection horizon: one trading month. */
    const val HORIZON_SESSIONS = 21

    /** Bars needed before any projection is claimed (analogs + warm-up). */
    const val MIN_HISTORY_BARS = 150

    /** Bars needed for the regime-conditioned tiers. */
    const val MIN_CONDITIONED_BARS = 250

    /** An analog tier must hold at least this many months to be trusted. */
    const val MIN_ANALOGS = 40

    private const val WARMUP = 60

    fun study(inputs: StudyInputs, now: Long = System.currentTimeMillis()): StockStudy {
        val caveat = "The projection is the measured distribution of this stock's own " +
            "comparable past months — a range of what HAS happened, never a promise of " +
            "what will. Decision support, not financial advice."
        val notes = ArrayList<String>()
        val candles = inputs.candles
        val closes = candles.map { it.close }
        val price = inputs.quote?.price ?: closes.lastOrNull() ?: 0.0

        // ---- the board ---------------------------------------------------
        val analysis = if (candles.size >= 30) Techniques.analyze(inputs.symbol, candles) else null
        val bullish = analysis?.results?.count { it.verdict == TechniqueVerdict.BULLISH } ?: 0
        val deciding = analysis?.results?.count { it.verdict != TechniqueVerdict.NEUTRAL } ?: 0
        val boardVerdict = when {
            deciding == 0 -> TechniqueVerdict.NEUTRAL
            bullish * 2 > deciding -> TechniqueVerdict.BULLISH
            (deciding - bullish) * 2 > deciding -> TechniqueVerdict.BEARISH
            else -> TechniqueVerdict.NEUTRAL
        }

        // ---- performance spans, always against the same clock ------------
        val spyCloses = inputs.spy.map { it.close }
        val spans = listOf(
            SpanReturn("1M", 21, spanReturn(closes, 21), spanReturn(spyCloses, 21)),
            SpanReturn("3M", 63, spanReturn(closes, 63), spanReturn(spyCloses, 63)),
            SpanReturn("6M", 126, spanReturn(closes, 126), spanReturn(spyCloses, 126)),
            SpanReturn("1Y", 252, spanReturn(closes, 252), spanReturn(spyCloses, 252))
        )

        // ---- risk stats over the last year -------------------------------
        val year = closes.takeLast(252)
        val dailyR = Indicators.dailyReturns(year)
        val vol = if (dailyR.size >= 40) {
            val m = dailyR.average()
            sqrt(dailyR.sumOf { (it - m) * (it - m) } / (dailyR.size - 1)) * sqrt(252.0) * 100.0
        } else null
        val beta = betaVsSpy(closes, spyCloses)
        val maxDd = maxDrawdown(year)
        val high52 = year.maxOrNull()?.let { hi ->
            if (hi > 0.0 && price > 0.0) (hi - price) / hi * 100.0 else null
        }

        // ---- the projection ----------------------------------------------
        val projection = project(candles, price)
        if (projection == null && candles.isNotEmpty()) {
            notes += "Under $MIN_HISTORY_BARS sessions of history reached this run — no " +
                "projection is claimed on a pool that thin."
        }

        // ---- factors ------------------------------------------------------
        val factors = ArrayList<StudyFactor>()
        factors += trendFactor(closes, price)
        factors += momentumFactor(closes, spyCloses)
        factors += boardFactor(bullish, deciding)
        factors += volumeFactor(candles)
        factors += riskFactor(vol)
        factors += fundamentalsFactor(inputs.fundamentals)
        factors += newsFactor(inputs.news)

        val maxTotal = factors.sumOf { it.maxScore }
        val measuredMax = factors.filter { it.score != null }.sumOf { it.maxScore }
        val earned = factors.sumOf { it.score ?: 0 }
        val coverage = if (maxTotal > 0) measuredMax * 100.0 / maxTotal else 0.0
        val grade =
            if (coverage >= MIN_COVERAGE_PCT && measuredMax > 0) {
                (earned * 100.0 / measuredMax).roundToInt().coerceIn(0, 100)
            } else null
        if (grade == null) {
            notes += String.format(
                Locale.US,
                "Only %.0f%% of the grade's inputs could be measured — no grade is " +
                    "claimed below %.0f%%.",
                coverage, MIN_COVERAGE_PCT
            )
        }

        // ---- the street, labeled external --------------------------------
        val f = inputs.fundamentals
        val analystNote =
            if (f?.targetMeanPrice != null && price > 0.0) {
                val pct = (f.targetMeanPrice - price) / price * 100.0
                String.format(
                    Locale.US,
                    "The street's 12-MONTH mean target is %s (%+.1f%% from here)%s%s. " +
                        "External opinion on a different horizon — shown, never mixed " +
                        "into the month's projection.",
                    money(f.targetMeanPrice), pct,
                    f.analystCount?.let { ", $it analysts" } ?: "",
                    f.recommendationMean?.let {
                        String.format(Locale.US, ", consensus %.1f (1 strong buy – 5 sell)", it)
                    } ?: ""
                )
            } else ""

        val newsNote = newsSummary(inputs.news)

        val headline = buildHeadline(inputs.symbol, grade, projection)

        return StockStudy(
            computedAt = now,
            symbol = inputs.symbol,
            name = inputs.name,
            price = round2(price),
            grade = grade,
            gradeBand = when {
                grade == null -> "Not enough measured data"
                grade >= 80 -> "Elite setup"
                grade >= 60 -> "Solid, with named gaps"
                grade >= 40 -> "Mixed evidence"
                else -> "Weak setup"
            },
            coveragePct = round1(coverage),
            factors = factors,
            projection = projection,
            spans = spans,
            volatilityPct = vol?.let { round1(it) },
            beta = beta?.let { round2(it) },
            maxDrawdownPct = maxDd?.let { round1(it) },
            below52HighPct = high52?.let { round1(it) },
            boardBullish = bullish,
            boardTotal = deciding,
            boardVerdict = boardVerdict,
            fundamentals = f,
            analystNote = analystNote,
            newsNote = newsNote,
            headline = headline,
            notes = notes,
            caveat = caveat
        )
    }

    // ------------------------------------------------------------ projection

    /**
     * The analog replay. Regime of a session = trend structure (above both
     * MAs / mixed / below both) × RSI band (<40 / 40–60 / >60) × ATR%
     * regime (below / above its own median). Tiers relax one dimension at a
     * time until [MIN_ANALOGS] months qualify, and the basis names the tier.
     */
    fun project(candles: List<Candle>, price: Double): PriceProjection? {
        val closes = candles.map { it.close }
        val n = closes.size
        if (n < MIN_HISTORY_BARS || price <= 0.0) return null

        val sma50 = rollingSma(closes, 50)
        val sma200 = rollingSma(closes, 200)
        val rsi = rollingRsi(closes, 14)
        val atrPct = rollingAtrPct(candles, 14)
        val atrMedian = atrPct.filterNotNull().sorted().let {
            if (it.isEmpty()) null else it[it.size / 2]
        }

        fun trendReg(i: Int): Int {
            val s50 = sma50[i]
            val s200 = sma200[i] ?: s50   // young listing: 50-day carries the read
            if (s50 == null || s200 == null) return 1
            val c = closes[i]
            return when {
                c > s50 && c > s200 -> 2
                c < s50 && c < s200 -> 0
                else -> 1
            }
        }

        fun rsiBand(i: Int): Int = when {
            rsi[i] == null -> 1
            rsi[i]!! < 40.0 -> 0
            rsi[i]!! <= 60.0 -> 1
            else -> 2
        }

        fun volReg(i: Int): Int {
            val a = atrPct[i] ?: return 0
            val m = atrMedian ?: return 0
            return if (a > m) 1 else 0
        }

        val last = n - 1
        val curTrend = trendReg(last)
        val curRsi = rsiBand(last)
        val curVol = volReg(last)

        val candidates = (WARMUP until n - HORIZON_SESSIONS).filter { closes[it] > 0.0 }
        if (candidates.isEmpty()) return null

        val conditionedAllowed = n >= MIN_CONDITIONED_BARS
        data class Tier(val basis: String, val match: (Int) -> Boolean, val conditioned: Boolean)
        val tiers = listOf(
            Tier(
                "months that shared today's trend structure, RSI band, and volatility regime",
                { i -> trendReg(i) == curTrend && rsiBand(i) == curRsi && volReg(i) == curVol },
                true
            ),
            Tier(
                "months that shared today's trend structure and RSI band",
                { i -> trendReg(i) == curTrend && rsiBand(i) == curRsi },
                true
            ),
            Tier(
                "months that shared today's trend structure",
                { i -> trendReg(i) == curTrend },
                true
            ),
            Tier("every month in the window — too few regime matches to condition on",
                { true }, false)
        )
        var analogIdx: List<Int> = emptyList()
        var basis = ""
        var conditioned = false
        for (tier in tiers) {
            if (tier.conditioned && !conditionedAllowed) continue
            val matched = candidates.filter(tier.match)
            if (matched.size >= MIN_ANALOGS || tier === tiers.last()) {
                analogIdx = matched
                basis = tier.basis
                conditioned = tier.conditioned && matched.size >= MIN_ANALOGS
                break
            }
        }
        if (analogIdx.size < 10) return null

        val forwards = analogIdx.map { i -> closes[i + HORIZON_SESSIONS] / closes[i] - 1.0 }
            .sorted()
        fun pct(p: Double): Double {
            val pos = p * (forwards.size - 1)
            val lo = pos.toInt()
            val hi = (lo + 1).coerceAtMost(forwards.size - 1)
            val frac = pos - lo
            return forwards[lo] * (1 - frac) + forwards[hi] * frac
        }
        val p10 = pct(0.10); val q1 = pct(0.25); val med = pct(0.50)
        val q3 = pct(0.75); val p90 = pct(0.90)
        val upShare = forwards.count { it > 0.0 } * 100.0 / forwards.size

        val recent = Indicators.dailyReturns(closes.takeLast(121))
        val monthlyVol = if (recent.size >= 40) {
            val m = recent.average()
            sqrt(recent.sumOf { (it - m) * (it - m) } / (recent.size - 1)) *
                sqrt(HORIZON_SESSIONS.toDouble()) * 100.0
        } else null

        return PriceProjection(
            horizonSessions = HORIZON_SESSIONS,
            basis = basis,
            analogCount = forwards.size,
            conditioned = conditioned,
            medianPct = round1(med * 100.0),
            q1Pct = round1(q1 * 100.0),
            q3Pct = round1(q3 * 100.0),
            p10Pct = round1(p10 * 100.0),
            p90Pct = round1(p90 * 100.0),
            medianPrice = round2(price * (1.0 + med)),
            q1Price = round2(price * (1.0 + q1)),
            q3Price = round2(price * (1.0 + q3)),
            p10Price = round2(price * (1.0 + p10)),
            p90Price = round2(price * (1.0 + p90)),
            upSharePct = round1(upShare),
            monthlyVolPct = monthlyVol?.let { round1(it) }
        )
    }

    // ------------------------------------------------------------ factors

    private fun trendFactor(closes: List<Double>, price: Double): StudyFactor {
        if (closes.size < 60 || price <= 0.0) {
            return StudyFactor(
                "trend", "Trend structure", null, 15,
                "Too little history for a moving-average read."
            )
        }
        val s50 = closes.takeLast(50).average()
        val s200 = if (closes.size >= 200) closes.takeLast(200).average() else null
        val above50 = price > s50
        val above200 = s200?.let { price > it }
        val golden = s200?.let { s50 > it }
        val score = when {
            above50 && above200 == true && golden == true -> 15
            above50 && above200 == true -> 12
            above50 || above200 == true -> 7
            else -> 2
        }
        val detail = String.format(
            Locale.US,
            "Price %s the 50-day%s%s.",
            if (above50) "above" else "below",
            above200?.let { ", ${if (it) "above" else "below"} the 200-day" } ?: "",
            if (golden == true) "; the 50-day rides above the 200-day" else ""
        )
        return StudyFactor("trend", "Trend structure", score, 15, detail)
    }

    private fun momentumFactor(closes: List<Double>, spy: List<Double>): StudyFactor {
        val stock = spanReturn(closes, 63)
        val bench = spanReturn(spy, 63)
        if (stock == null || bench == null) {
            return StudyFactor(
                "momentum", "Momentum vs the market", null, 15,
                "The 3-month move could not be measured against SPY this run."
            )
        }
        val edge = stock - bench
        val score = ((edge + 10.0) / 20.0 * 15.0).coerceIn(0.0, 15.0).roundToInt()
        return StudyFactor(
            "momentum", "Momentum vs the market", score, 15,
            String.format(
                Locale.US, "%+.1f%% over 3 months vs SPY %+.1f%% — a %+.1f-point edge.",
                stock, bench, edge
            )
        )
    }

    private fun boardFactor(bullish: Int, deciding: Int): StudyFactor {
        if (deciding < 10) {
            return StudyFactor(
                "board", "35-technique board", null, 15,
                "Fewer than 10 deciding techniques — the board is unmeasured."
            )
        }
        val share = bullish * 100.0 / deciding
        return StudyFactor(
            "board", "35-technique board",
            (share / 100.0 * 15.0).roundToInt(), 15,
            String.format(
                Locale.US, "%d of %d deciding techniques read bullish (%.0f%%).",
                bullish, deciding, share
            )
        )
    }

    private fun volumeFactor(candles: List<Candle>): StudyFactor {
        val vols = candles.map { it.volume }.filter { it > 0L }
        if (vols.size < 80) {
            return StudyFactor(
                "volume", "Volume behavior", null, 10,
                "Not enough volume history to measure."
            )
        }
        val recent = candles.takeLast(20).map { it.volume.toDouble() }.average()
        val base = candles.takeLast(63).map { it.volume.toDouble() }.average()
        if (base <= 0.0) {
            return StudyFactor("volume", "Volume behavior", null, 10, "No measurable base volume.")
        }
        val ratio = recent / base
        val score = when {
            ratio >= 1.5 -> 10
            ratio >= 1.1 -> 8
            ratio >= 0.8 -> 5
            else -> 2
        }
        return StudyFactor(
            "volume", "Volume behavior", score, 10,
            String.format(
                Locale.US,
                "The last 20 sessions traded %.1fx the 3-month average volume.",
                ratio
            )
        )
    }

    private fun riskFactor(vol: Double?): StudyFactor {
        if (vol == null) {
            return StudyFactor(
                "risk", "Volatility", null, 10,
                "Too little history for an annualized volatility read."
            )
        }
        val score = when {
            vol <= 25.0 -> 10
            vol <= 40.0 -> 7
            vol <= 60.0 -> 4
            else -> 1
        }
        return StudyFactor(
            "risk", "Volatility", score, 10,
            String.format(
                Locale.US,
                "%.0f%% annualized — the yearly swing size the position signs up for.",
                vol
            )
        )
    }

    /** Balance sheet, profitability, growth, cash, valuation — 5 points each. */
    private fun fundamentalsFactor(f: Fundamentals?): StudyFactor {
        if (f == null) {
            return StudyFactor(
                "fundamentals", "Fundamentals", null, 25,
                "No fundamental data reached this run (ETFs and funds carry none)."
            )
        }
        var earned = 0.0
        var measured = 0
        val parts = ArrayList<String>()
        f.debtToEquity?.let { dte ->
            measured += 5
            earned += when {
                dte <= 50.0 -> 5.0
                dte <= 100.0 -> 3.5
                dte <= 200.0 -> 2.0
                else -> 0.0
            }
            parts += String.format(Locale.US, "debt/equity %.0f%%", dte)
        }
        f.profitMargins?.let { m ->
            measured += 5
            earned += when {
                m >= 0.20 -> 5.0
                m >= 0.10 -> 3.5
                m >= 0.03 -> 2.0
                m >= 0.0 -> 1.0
                else -> 0.0
            }
            parts += String.format(Locale.US, "margins %.1f%%", m * 100.0)
        }
        f.revenueGrowth?.let { g ->
            measured += 5
            earned += when {
                g >= 0.15 -> 5.0
                g >= 0.05 -> 3.5
                g >= 0.0 -> 2.0
                else -> 0.0
            }
            parts += String.format(Locale.US, "revenue %+.1f%% yoy", g * 100.0)
        }
        f.freeCashflow?.let { fcf ->
            measured += 5
            earned += if (fcf > 0.0) 5.0 else 0.0
            parts += if (fcf > 0.0) "FCF positive" else "FCF negative"
        }
        f.forwardPE?.takeIf { it > 0.0 }?.let { pe ->
            measured += 5
            earned += when {
                pe <= 15.0 -> 5.0
                pe <= 25.0 -> 3.5
                pe <= 40.0 -> 2.0
                else -> 1.0
            }
            parts += String.format(Locale.US, "forward P/E %.1f", pe)
        }
        if (measured == 0) {
            return StudyFactor(
                "fundamentals", "Fundamentals", null, 25,
                "The snapshot arrived empty — nothing to grade."
            )
        }
        // Scaled to the measured sub-parts: an absent figure narrows the
        // claim instead of failing it.
        val score = (earned / measured * 25.0).roundToInt()
        val detail = parts.joinToString(" · ").replaceFirstChar { it.uppercase() } +
            if (measured < 25) " (graded on the ${measured / 5} measured of 5 components)." else "."
        return StudyFactor("fundamentals", "Fundamentals", score, 25, detail)
    }

    private fun newsFactor(news: List<NewsItem>): StudyFactor {
        if (news.isEmpty()) {
            return StudyFactor(
                "news", "Headline tone", null, 10,
                "No recent headlines reached the scan — tone unmeasured."
            )
        }
        val sum = news.sumOf { it.sentiment }.coerceIn(-3, 3)
        val score = ((sum + 3) / 6.0 * 10.0).roundToInt()
        return StudyFactor(
            "news", "Headline tone", score, 10,
            String.format(
                Locale.US, "%d recent headline%s, summed tone %+d (clamped ±3).",
                news.size, if (news.size == 1) "" else "s", sum
            )
        )
    }

    // ------------------------------------------------------------ helpers

    private fun buildHeadline(symbol: String, grade: Int?, p: PriceProjection?): String {
        val gradePart = grade?.let { "$symbol grades $it/100" }
            ?: "$symbol could not be fully graded this run"
        val projPart = p?.let {
            String.format(
                Locale.US,
                " — months like this one have typically landed between %s and %s " +
                    "(median %s), %s of them higher.",
                money(it.q1Price), money(it.q3Price), money(it.medianPrice),
                Fmt0(it.upSharePct)
            )
        } ?: "."
        return gradePart + projPart
    }

    private fun newsSummary(news: List<NewsItem>): String {
        if (news.isEmpty()) return ""
        val newest = news.maxByOrNull { it.publishedAt } ?: return ""
        return newest.title
    }

    fun spanReturn(closes: List<Double>, sessions: Int): Double? {
        if (closes.size <= sessions) return null
        val base = closes[closes.size - 1 - sessions]
        if (base <= 0.0) return null
        return round1((closes.last() - base) / base * 100.0)
    }

    private fun betaVsSpy(closes: List<Double>, spy: List<Double>): Double? {
        val n = minOf(closes.size, spy.size, 253)
        if (n < 60) return null
        val r = Indicators.dailyReturns(closes.takeLast(n))
        val b = Indicators.dailyReturns(spy.takeLast(n))
        val m = minOf(r.size, b.size)
        if (m < 40) return null
        val rr = r.takeLast(m); val bb = b.takeLast(m)
        val rMean = rr.average(); val bMean = bb.average()
        var cov = 0.0; var varB = 0.0
        for (i in 0 until m) {
            cov += (rr[i] - rMean) * (bb[i] - bMean)
            varB += (bb[i] - bMean) * (bb[i] - bMean)
        }
        if (varB < 1e-12) return null
        return cov / varB
    }

    private fun maxDrawdown(closes: List<Double>): Double? {
        if (closes.size < 40) return null
        var peak = closes.first()
        var maxDd = 0.0
        for (c in closes) {
            if (c > peak) peak = c
            val dd = (peak - c) / peak
            if (dd > maxDd) maxDd = dd
        }
        return maxDd * 100.0
    }

    // Rolling indicator series, one value per bar (null while warming up).

    fun rollingSma(closes: List<Double>, period: Int): List<Double?> {
        val out = arrayOfNulls<Double>(closes.size)
        var sum = 0.0
        for (i in closes.indices) {
            sum += closes[i]
            if (i >= period) sum -= closes[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out.toList()
    }

    fun rollingRsi(closes: List<Double>, period: Int = 14): List<Double?> {
        val out = arrayOfNulls<Double>(closes.size)
        if (closes.size <= period) return out.toList()
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) avgGain += d else avgLoss -= d
        }
        avgGain /= period
        avgLoss /= period
        out[period] = rsiOf(avgGain, avgLoss)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            val gain = max(d, 0.0)
            val loss = max(-d, 0.0)
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiOf(avgGain, avgLoss)
        }
        return out.toList()
    }

    private fun rsiOf(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss < 1e-12) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)

    /** Wilder ATR as a percent of the close — comparable across price levels. */
    fun rollingAtrPct(candles: List<Candle>, period: Int = 14): List<Double?> {
        val out = arrayOfNulls<Double>(candles.size)
        if (candles.size <= period) return out.toList()
        var atr = 0.0
        for (i in 1..period) {
            atr += trueRange(candles[i], candles[i - 1])
        }
        atr /= period
        if (candles[period].close > 0.0) out[period] = atr / candles[period].close * 100.0
        for (i in period + 1 until candles.size) {
            atr = (atr * (period - 1) + trueRange(candles[i], candles[i - 1])) / period
            if (candles[i].close > 0.0) out[i] = atr / candles[i].close * 100.0
        }
        return out.toList()
    }

    private fun trueRange(c: Candle, prev: Candle): Double =
        maxOf(c.high - c.low, abs(c.high - prev.close), abs(c.low - prev.close))

    private fun money(v: Double): String =
        if (abs(v) >= 10_000) String.format(Locale.US, "$%,.0f", v)
        else String.format(Locale.US, "$%,.2f", v)

    private fun Fmt0(v: Double): String = String.format(Locale.US, "%.0f%%", v)
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
