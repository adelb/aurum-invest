package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import java.util.Locale
import kotlin.math.sqrt

/**
 * The Beat-the-SPY engine: before a single dollar goes into a stock, the same
 * dollar's default home — the S&P 500 — gets its say. Every stock session is
 * date-aligned with SPY's, and each horizon replays every comparable past
 * window BOTH ways at once: what the stock did and what SPY did over the very
 * same days. The verdict is the measured share of those paired windows the
 * stock actually won, never an opinion.
 *
 * Entry price levels come out of the same paired windows: buying at or below
 * [BeatSpyHorizon.breakevenEntry] means the stock's MEDIAN measured outcome
 * lands above SPY's median outcome; at or below [BeatSpyHorizon.edgeEntry]
 * even the stock's SOFT quartile beats SPY's median. Both are anchored on the
 * stock's median end price from TODAY — a limit order fills lower, the
 * destination stays the measured one.
 *
 * Integrity rules (house law):
 *  - pure function over candles; no I/O, never throws
 *  - every figure is a percentile of paired measured windows — the analog
 *    count and conditioning tier are printed beside the numbers
 *  - a listing too young for the race gets no verdict, not a padded one
 */

/** One horizon of the race, built from paired stock/SPY windows. */
data class BeatSpyHorizon(
    val label: String,
    val horizonSessions: Int,
    /** Which analog tier produced it — printed with the numbers. */
    val basis: String,
    val analogCount: Int,
    /** True when the analogs share today's relative-strength regime. */
    val conditioned: Boolean,
    /** Share of paired windows where the stock returned strictly more than SPY. */
    val beatSharePct: Double,
    /** Stock return minus SPY return over the same days, percentage points. */
    val medianEdgePct: Double,
    val q1EdgePct: Double,
    val q3EdgePct: Double,
    val p10EdgePct: Double,
    val p90EdgePct: Double,
    /** Each side's own outcomes over the same paired windows. */
    val stockMedianPct: Double,
    val stockQ1Pct: Double,
    val stockQ3Pct: Double,
    val spyMedianPct: Double,
    val spyQ1Pct: Double,
    val spyQ3Pct: Double,
    /** Buy at or below this and the stock's median outcome beats SPY's median. */
    val breakevenEntry: Double,
    /** Buy at or below this and even the stock's soft (Q1) outcome beats SPY's median. */
    val edgeEntry: Double,
    /** BULLISH = the stock earns the ticket, BEARISH = SPY keeps it, NEUTRAL = coin flip. */
    val verdict: TechniqueVerdict
)

/** The whole race: one stock against the index it must beat to deserve the buy. */
data class BeatSpyReport(
    val computedAt: Long,
    val symbol: String,
    val price: Double,
    val spyPrice: Double,
    /** Sessions where both the stock and SPY printed a close — the shared clock. */
    val alignedSessions: Int,
    val beta: Double?,
    val correlation: Double?,
    /** Stock's average move per 1% SPY up-day move, last aligned year, percent. */
    val upCapturePct: Double?,
    val downCapturePct: Double?,
    /** Realized head-to-head over the aligned clock. */
    val spans: List<SpanReturn>,
    val horizons: List<BeatSpyHorizon>,
    /** The buy horizon's ([BUY_HORIZON_SESSIONS]) verdict, or the nearest measured one. */
    val verdict: TechniqueVerdict,
    val verdictLine: String,
    val notes: List<String>,
    val caveat: String
)

object BeatSpyEngine {

    /** Every race horizon, in trading sessions. */
    val HORIZONS = listOf(
        5 to "1 week",
        21 to "1 month",
        63 to "3 months",
        126 to "6 months",
        252 to "1 year"
    )

    /** The horizon the headline verdict speaks for — the buy decision's month. */
    const val BUY_HORIZON_SESSIONS = 21

    /** Aligned sessions needed before any horizon is raced at all. */
    const val MIN_ALIGNED_BARS = 150

    /** Aligned sessions needed for the regime-conditioned tiers. */
    const val MIN_CONDITIONED_BARS = 250

    /** An analog tier must hold at least this many paired windows to be used. */
    const val MIN_ANALOGS = 40

    /** A horizon with fewer candidate windows than this is not raced at all. */
    const val MIN_POOL = 30

    /** Beat-share bars for the verdict, percent. */
    const val BEAT_BAR_PCT = 55.0
    const val LOSE_BAR_PCT = 45.0

    private const val WARMUP = 60
    private const val DAY_MS = 86_400_000L

    /**
     * Null only when either side has no candles at all, or no session could
     * be date-aligned — with any shared history a report is produced, even if
     * it is only the note that the race can't be run yet.
     */
    fun build(
        symbol: String,
        stockCandles: List<Candle>,
        spyCandles: List<Candle>,
        livePrice: Double? = null,
        liveSpyPrice: Double? = null,
        now: Long = System.currentTimeMillis()
    ): BeatSpyReport? {
        val caveat = "Every figure is a percentile of this stock's own paired past windows " +
            "against SPY over the same days — a measured record, never a promise. " +
            "Decision support, not financial advice."
        if (stockCandles.isEmpty() || spyCandles.isEmpty()) return null

        // ---- the shared clock: keep only sessions both sides printed -------
        val spyByDay = HashMap<Long, Double>(spyCandles.size * 2)
        for (c in spyCandles) {
            if (c.close > 0.0 && c.close.isFinite()) spyByDay[c.ts / DAY_MS] = c.close
        }
        val s = ArrayList<Double>(stockCandles.size)
        val b = ArrayList<Double>(stockCandles.size)
        for (c in stockCandles) {
            if (c.close <= 0.0 || !c.close.isFinite()) continue
            val spyClose = spyByDay[c.ts / DAY_MS] ?: continue
            s.add(c.close)
            b.add(spyClose)
        }
        val n = s.size
        if (n == 0) return null

        val price = livePrice?.takeIf { it > 0.0 } ?: s.last()
        val spyPrice = liveSpyPrice?.takeIf { it > 0.0 } ?: b.last()
        val notes = ArrayList<String>()

        // ---- realized head-to-head on the same clock ----------------------
        val spans = listOf(
            SpanReturn("1M", 21, StockStudyEngine.spanReturn(s, 21), StockStudyEngine.spanReturn(b, 21)),
            SpanReturn("3M", 63, StockStudyEngine.spanReturn(s, 63), StockStudyEngine.spanReturn(b, 63)),
            SpanReturn("6M", 126, StockStudyEngine.spanReturn(s, 126), StockStudyEngine.spanReturn(b, 126)),
            SpanReturn("1Y", 252, StockStudyEngine.spanReturn(s, 252), StockStudyEngine.spanReturn(b, 252))
        )

        // ---- beta / correlation / capture over the last aligned year ------
        val year = minOf(n, 253)
        val sr = Indicators.dailyReturns(s.takeLast(year))
        val br = Indicators.dailyReturns(b.takeLast(year))
        val m = minOf(sr.size, br.size)
        var beta: Double? = null
        var correlation: Double? = null
        var upCapture: Double? = null
        var downCapture: Double? = null
        if (m >= 40) {
            val srr = sr.takeLast(m)
            val brr = br.takeLast(m)
            val sMean = srr.average()
            val bMean = brr.average()
            var cov = 0.0; var varS = 0.0; var varB = 0.0
            for (i in 0 until m) {
                cov += (srr[i] - sMean) * (brr[i] - bMean)
                varS += (srr[i] - sMean) * (srr[i] - sMean)
                varB += (brr[i] - bMean) * (brr[i] - bMean)
            }
            if (varB > 1e-12) beta = cov / varB
            if (varS > 1e-12 && varB > 1e-12) correlation = cov / sqrt(varS * varB)
            val upS = ArrayList<Double>(); val upB = ArrayList<Double>()
            val dnS = ArrayList<Double>(); val dnB = ArrayList<Double>()
            for (i in 0 until m) {
                if (brr[i] > 0.0) { upS.add(srr[i]); upB.add(brr[i]) }
                if (brr[i] < 0.0) { dnS.add(srr[i]); dnB.add(brr[i]) }
            }
            if (upB.size >= 30 && upB.average() > 1e-9) {
                upCapture = upS.average() / upB.average() * 100.0
            }
            if (dnB.size >= 30 && dnB.average() < -1e-9) {
                downCapture = dnS.average() / dnB.average() * 100.0
            }
        }

        // ---- the race, one horizon at a time ------------------------------
        val horizons =
            if (n >= MIN_ALIGNED_BARS) raceAll(s, b, price)
            else {
                notes += String.format(
                    Locale.US,
                    "Only %d sessions are shared between %s and SPY — under the %d needed " +
                        "to race a horizon honestly. No verdict is claimed on a pool that thin.",
                    n, symbol, MIN_ALIGNED_BARS
                )
                emptyList()
            }
        if (horizons.isNotEmpty() && horizons.size < HORIZONS.size) {
            val missing = HORIZONS.map { it.second } - horizons.map { it.label }.toSet()
            notes += String.format(
                Locale.US,
                "The %s horizon%s would need more shared history than %s carries — not raced.",
                missing.joinToString(" and "),
                if (missing.size == 1) "" else "s",
                symbol
            )
        }

        // ---- the headline verdict: the buy decision's month ---------------
        val head = horizons.firstOrNull { it.horizonSessions == BUY_HORIZON_SESSIONS }
            ?: horizons.firstOrNull()
        val verdict = head?.verdict ?: TechniqueVerdict.NEUTRAL
        val verdictLine = when {
            head == null ->
                "$symbol and SPY don't share enough history to race — the index stays " +
                    "the default until the record exists."
            head.verdict == TechniqueVerdict.BULLISH -> String.format(
                Locale.US,
                "%s earns the ticket over SPY: across %d comparable %s windows it beat " +
                    "the index %.0f%% of the time, median edge %+.1f points.",
                symbol, head.analogCount, head.label, head.beatSharePct, head.medianEdgePct
            )
            head.verdict == TechniqueVerdict.BEARISH -> String.format(
                Locale.US,
                "SPY keeps the ticket: %s beat the index in only %.0f%% of %d comparable " +
                    "%s windows (median edge %+.1f points). The same dollars default to the index.",
                symbol, head.beatSharePct, head.analogCount, head.label, head.medianEdgePct
            )
            else -> String.format(
                Locale.US,
                "A coin flip against SPY over the %s (%.0f%% beat share across %d windows) — " +
                    "the index is the default until the price or the record improves.",
                head.label, head.beatSharePct, head.analogCount
            )
        }

        return BeatSpyReport(
            computedAt = now,
            symbol = symbol,
            price = round2(price),
            spyPrice = round2(spyPrice),
            alignedSessions = n,
            beta = beta?.let { round2(it) },
            correlation = correlation?.let { round2(it) },
            upCapturePct = upCapture?.let { round1(it) },
            downCapturePct = downCapture?.let { round1(it) },
            spans = spans,
            horizons = horizons,
            verdict = verdict,
            verdictLine = verdictLine,
            notes = notes,
            caveat = caveat
        )
    }

    /**
     * Paired analog replay per horizon. Regime of a session = the stock/SPY
     * relative-strength line against its own 50-session average (leading or
     * lagging the index) × the stock's own 50-session trend side. Tiers relax
     * one dimension at a time until [MIN_ANALOGS] paired windows qualify, and
     * the basis names the tier.
     */
    private fun raceAll(s: List<Double>, b: List<Double>, price: Double): List<BeatSpyHorizon> {
        val n = s.size
        if (price <= 0.0) return emptyList()

        val rs = ArrayList<Double>(n)
        for (i in 0 until n) rs.add(s[i] / b[i])
        val rsSma = StockStudyEngine.rollingSma(rs, 50)
        val sSma = StockStudyEngine.rollingSma(s, 50)

        // Leading (1) / lagging (0) the index; own trend up (1) / down (0).
        fun rsReg(i: Int): Int {
            val m = rsSma[i] ?: return 1
            return if (rs[i] > m) 1 else 0
        }
        fun trendReg(i: Int): Int {
            val m = sSma[i] ?: return 1
            return if (s[i] > m) 1 else 0
        }

        val last = n - 1
        val curRs = rsReg(last)
        val curTrend = trendReg(last)
        val conditionedAllowed = n >= MIN_CONDITIONED_BARS

        data class Tier(val basis: String, val match: (Int) -> Boolean, val conditioned: Boolean)
        val rsWord = if (curRs == 1) "leading" else "lagging"
        val tiers = listOf(
            Tier(
                "windows where the stock was $rsWord the index with today's own trend side",
                { i -> rsReg(i) == curRs && trendReg(i) == curTrend },
                true
            ),
            Tier(
                "windows where the stock was $rsWord the index, any trend",
                { i -> rsReg(i) == curRs },
                true
            ),
            Tier(
                "every shared window — too few regime matches to condition on",
                { true },
                false
            )
        )

        val out = ArrayList<BeatSpyHorizon>(HORIZONS.size)
        for ((horizon, label) in HORIZONS) {
            val candidates = (WARMUP until n - horizon).toList()
            if (candidates.size < MIN_POOL) continue

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
            if (analogIdx.size < 10) continue

            val stockR = ArrayList<Double>(analogIdx.size)
            val spyR = ArrayList<Double>(analogIdx.size)
            val edges = ArrayList<Double>(analogIdx.size)
            var beats = 0
            for (i in analogIdx) {
                val rsF = s[i + horizon] / s[i] - 1.0
                val rbF = b[i + horizon] / b[i] - 1.0
                stockR.add(rsF)
                spyR.add(rbF)
                edges.add((rsF - rbF) * 100.0)
                if (rsF > rbF) beats++
            }
            stockR.sort(); spyR.sort(); edges.sort()

            val stockMed = percentile(stockR, 0.50)
            val stockQ1 = percentile(stockR, 0.25)
            val stockQ3 = percentile(stockR, 0.75)
            val spyMed = percentile(spyR, 0.50)
            val spyQ1 = percentile(spyR, 0.25)
            val spyQ3 = percentile(spyR, 0.75)
            val beatShare = beats * 100.0 / edges.size
            val medianEdge = percentile(edges, 0.50)

            // Enter at E; the stock's measured destination from TODAY stays
            // price × (1 + r). It beats SPY's median when the return off E does.
            val spyDenom = 1.0 + spyMed
            val breakeven = if (spyDenom > 1e-9) price * (1.0 + stockMed) / spyDenom else 0.0
            val edgeEntry = if (spyDenom > 1e-9) price * (1.0 + stockQ1) / spyDenom else 0.0

            out += BeatSpyHorizon(
                label = label,
                horizonSessions = horizon,
                basis = basis,
                analogCount = edges.size,
                conditioned = conditioned,
                beatSharePct = round1(beatShare),
                medianEdgePct = round1(medianEdge),
                q1EdgePct = round1(percentile(edges, 0.25)),
                q3EdgePct = round1(percentile(edges, 0.75)),
                p10EdgePct = round1(percentile(edges, 0.10)),
                p90EdgePct = round1(percentile(edges, 0.90)),
                stockMedianPct = round1(stockMed * 100.0),
                stockQ1Pct = round1(stockQ1 * 100.0),
                stockQ3Pct = round1(stockQ3 * 100.0),
                spyMedianPct = round1(spyMed * 100.0),
                spyQ1Pct = round1(spyQ1 * 100.0),
                spyQ3Pct = round1(spyQ3 * 100.0),
                breakevenEntry = round2(breakeven),
                edgeEntry = round2(edgeEntry),
                verdict = when {
                    beatShare >= BEAT_BAR_PCT -> TechniqueVerdict.BULLISH
                    beatShare <= LOSE_BAR_PCT && medianEdge < 0.0 -> TechniqueVerdict.BEARISH
                    else -> TechniqueVerdict.NEUTRAL
                }
            )
        }
        return out
    }

    /** Linear-interpolated percentile of an ALREADY SORTED list. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = p * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
