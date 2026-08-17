package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The technique-integrity engine: a standalone back-tester that replays the
 * last YEAR of sessions day by day. For each session it re-runs
 * [Techniques.analyze] on only the candles that existed that day, records
 * every technique's verdict, and grades it against the REAL move of the
 * following 5 trading days — so every verdict the app shows carries its own
 * measured, verifiable record on that exact stock.
 *
 * A directional call is a hit when the stock then moved at least
 * [MOVE_DEADBAND_PCT] in the called direction. Neutral verdicts are not
 * graded — saying "no signal" is not a prediction.
 *
 * A technique earns TRUSTED on a stock only with a real track record there:
 * at least [MIN_INDEPENDENT_SIGNALS] calls on the NON-OVERLAPPING grid, a
 * hit rate of [TRUST_HIT_RATE]%+ there, AND an edge of
 * [TRUST_EDGE_OVER_BASE]+ points over the stock's own unconditional base
 * rate — a rule that merely rode a rising tape is not trusted for it.
 * Trusted techniques get the gold border on the analysis screen and extra
 * vote weight in the 5-day outlook.
 *
 * [TechniqueEvaluation.ranked] orders the whole 35-technique board by that
 * measured record, so the app can surface the [TOP_TECHNIQUES] that have
 * actually worked on this stock and fold the rest away.
 *
 * Pure Kotlin, deterministic, never throws.
 */

/** One technique's measured 1-year track record on one stock. */
data class TechniqueScore(
    val key: String,
    val name: String,
    /** Directional (non-neutral) verdicts that could be graded (daily, overlapping windows). */
    val signals: Int,
    /** Calls where the stock moved >= deadband in the called direction. */
    val hits: Int,
    /** hits / signals as a percent, 0 when there were no signals. */
    val hitRate: Int,
    /** Average signed 5-day move in the called direction, percent. */
    val avgMovePct: Double,
    /** True when the INDEPENDENT record clears every trust gate (see below). */
    val trusted: Boolean,
    /**
     * Directional calls on the non-overlapping (every-5th-session) grid.
     * Daily replays share most of their forward window with their neighbors,
     * so [signals] overstates the effective sample — trust and intervals are
     * judged on this independent count instead.
     */
    val independentSignals: Int = 0,
    val independentHits: Int = 0,
    /** independentHits / independentSignals as a percent. */
    val independentHitRate: Int = 0,
    /**
     * The stock's own unconditional hit rate over the same windows, weighted
     * by this technique's bull/bear call mix — the drift an always-bullish
     * rule would have scored without any skill. A technique only shows edge
     * when it beats this, not 50%.
     */
    val baseRatePct: Int = 0,
    /** Wilson 95% interval on the independent hit rate, percent. */
    val ciLowPct: Int = 0,
    val ciHighPct: Int = 0
)

data class TechniqueEvaluation(
    val symbol: String,
    /** Trading days actually replayed. */
    val daysEvaluated: Int,
    /** Forward window each call was graded against, in trading days. */
    val horizonDays: Int,
    /** One score per technique, in board order. */
    val scores: List<TechniqueScore>,
    /** First and last replayed session (epoch millis) — the record's date range. */
    val fromTs: Long = 0L,
    val toTs: Long = 0L
) {
    val trustedKeys: Set<String> get() = scores.filter { it.trusted }.map { it.key }.toSet()

    /**
     * Hit rate per technique key for outlook weighting — judged on the SAME
     * independent (stride-5) evidence bar as the trust badge. Overlapping
     * daily replays overstate the sample, so a rule with 12 overlapping calls
     * that are really 2-3 independent windows must not get a heavier vote.
     */
    fun weights(): Map<String, Int> = scores
        .filter { it.independentSignals >= TechniqueEvaluator.MIN_INDEPENDENT_SIGNALS }
        .associate { it.key to it.independentHitRate }

    /**
     * The whole board ordered by measured merit on THIS stock: trusted
     * techniques first (by hit rate, then evidence), then graded-but-untrusted
     * ones by hit rate, then techniques with too few calls to grade — each
     * tier honestly separated so a 2-for-2 fluke never outranks a 15-for-20
     * record.
     */
    fun ranked(): List<TechniqueScore> = scores.sortedWith(
        compareByDescending<TechniqueScore> { it.trusted }
            .thenByDescending { it.signals >= TechniqueEvaluator.MIN_WEIGHT_SIGNALS }
            .thenByDescending { it.hitRate }
            .thenByDescending { it.signals }
    )

    /** Rank position (1-based) per technique key, following [ranked]. */
    fun rankByKey(): Map<String, Int> =
        ranked().mapIndexed { i, s -> s.key to i + 1 }.toMap()
}

object TechniqueEvaluator {

    /** Trading days replayed — a full year of sessions. */
    const val LOOKBACK_DAYS = 252

    /** How many techniques the analysis screen surfaces by default, best first. */
    const val TOP_TECHNIQUES = 20

    /** Forward horizon each verdict is graded against, matching the 5-day outlook. */
    const val HORIZON_DAYS = 5

    /** 30 warm-up bars, the full replay, and five forward bars to grade it. */
    const val MIN_CANDLES_FOR_FULL_REPLAY = LOOKBACK_DAYS + HORIZON_DAYS + 29

    /** Minimum move (percent) in the called direction to count as a hit. */
    const val MOVE_DEADBAND_PCT = 0.5

    /**
     * INDEPENDENT (non-overlapping-window) calls needed before a technique
     * can be trusted. Overlapping daily replays are not independent evidence.
     */
    const val MIN_INDEPENDENT_SIGNALS = 10

    /** Hit-rate bar (percent) for the trusted badge, on the independent record. */
    const val TRUST_HIT_RATE = 60

    /** How far (points) the independent hit rate must beat the stock's own base rate. */
    const val TRUST_EDGE_OVER_BASE = 5

    /** Overlapping calls needed before a hit rate is allowed to weight the outlook at all. */
    const val MIN_WEIGHT_SIGNALS = 10

    private class Tally(val name: String) {
        var signals = 0
        var hits = 0
        var moveSum = 0.0
        // Independent (stride-5) grid.
        var iSignals = 0
        var iHits = 0
        var iBull = 0
        var iBear = 0
    }

    /** Wilson 95% score interval for [hits] of [n]; (0,100) when n == 0. */
    fun wilson95(hits: Int, n: Int): Pair<Double, Double> {
        if (n <= 0) return 0.0 to 100.0
        val z = 1.96
        val p = hits.toDouble() / n
        val z2 = z * z
        val denom = 1.0 + z2 / n
        val center = p + z2 / (2 * n)
        val margin = z * kotlin.math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))
        val low = ((center - margin) / denom * 100.0).coerceIn(0.0, 100.0)
        val high = ((center + margin) / denom * 100.0).coerceIn(0.0, 100.0)
        return low to high
    }

    /**
     * Replays the last [LOOKBACK_DAYS] sessions of [candles] (daily, oldest
     * first — the same list the analysis screen already fetches) and grades
     * every technique. Null unless a complete 252-session replay can be
     * graded after the board's warm-up and five-session forward window.
     */
    fun evaluate(symbol: String, candles: List<Candle>): TechniqueEvaluation? {
        val n = candles.size
        if (n < MIN_CANDLES_FOR_FULL_REPLAY) return null
        val lastEval = n - 1 - HORIZON_DAYS
        // analyze() needs >= 30 candles, i.e. as-of index >= 29.
        val firstEval = max(29, lastEval - LOOKBACK_DAYS + 1)
        if (lastEval < firstEval) return null

        val tallies = LinkedHashMap<String, Tally>()
        var days = 0
        // The stock's own unconditional record on the independent grid: how
        // often ANY 5-day window moved past the deadband up or down. This is
        // the drift a skill-less always-bullish (or always-bearish) rule
        // would collect — the honest benchmark for every technique.
        var baseWindows = 0
        var baseUp = 0
        var baseDown = 0
        for (t in firstEval..lastEval) {
            val base = candles[t].close
            val forward = candles[t + HORIZON_DAYS].close
            if (!base.isFinite() || base <= 0.0 || !forward.isFinite() || forward <= 0.0) {
                continue
            }
            val analysis = Techniques.analyze(symbol, candles.subList(0, t + 1)) ?: continue
            if (
                analysis.results.size != Techniques.TECHNIQUE_COUNT ||
                analysis.results.map { it.key }.toSet().size != Techniques.TECHNIQUE_COUNT
            ) {
                continue
            }
            val movePct = (forward - base) / base * 100.0
            val independent = (t - firstEval) % HORIZON_DAYS == 0
            if (independent) {
                baseWindows++
                if (movePct >= MOVE_DEADBAND_PCT) baseUp++
                if (movePct <= -MOVE_DEADBAND_PCT) baseDown++
            }
            days++
            for (r in analysis.results) {
                val tally = tallies.getOrPut(r.key) { Tally(r.name) }
                when (r.verdict) {
                    TechniqueVerdict.BULLISH -> {
                        tally.signals++
                        tally.moveSum += movePct
                        if (movePct >= MOVE_DEADBAND_PCT) tally.hits++
                        if (independent) {
                            tally.iSignals++; tally.iBull++
                            if (movePct >= MOVE_DEADBAND_PCT) tally.iHits++
                        }
                    }
                    TechniqueVerdict.BEARISH -> {
                        tally.signals++
                        tally.moveSum -= movePct
                        if (movePct <= -MOVE_DEADBAND_PCT) tally.hits++
                        if (independent) {
                            tally.iSignals++; tally.iBear++
                            if (movePct <= -MOVE_DEADBAND_PCT) tally.iHits++
                        }
                    }
                    TechniqueVerdict.NEUTRAL -> Unit
                }
            }
        }
        if (days != LOOKBACK_DAYS || tallies.size != Techniques.TECHNIQUE_COUNT) return null

        val baseUpPct = if (baseWindows > 0) baseUp * 100.0 / baseWindows else 0.0
        val baseDownPct = if (baseWindows > 0) baseDown * 100.0 / baseWindows else 0.0

        val scores = tallies.map { (key, t) ->
            val rate = if (t.signals > 0) (t.hits * 100.0 / t.signals).roundToInt() else 0
            val iRate = if (t.iSignals > 0) (t.iHits * 100.0 / t.iSignals).roundToInt() else 0
            // The base rate this technique must beat, weighted by its own
            // bull/bear call mix on the independent grid.
            val baseRate = if (t.iSignals > 0) {
                ((t.iBull * baseUpPct + t.iBear * baseDownPct) / t.iSignals).roundToInt()
            } else 0
            val (ciLow, ciHigh) = wilson95(t.iHits, t.iSignals)
            TechniqueScore(
                key = key,
                name = t.name,
                signals = t.signals,
                hits = t.hits,
                hitRate = rate,
                avgMovePct = if (t.signals > 0) t.moveSum / t.signals else 0.0,
                trusted = isTrustworthy(t.iSignals, iRate, baseRate),
                independentSignals = t.iSignals,
                independentHits = t.iHits,
                independentHitRate = iRate,
                baseRatePct = baseRate,
                ciLowPct = ciLow.roundToInt(),
                ciHighPct = ciHigh.roundToInt()
            )
        }
        return TechniqueEvaluation(
            symbol = symbol,
            daysEvaluated = days,
            horizonDays = HORIZON_DAYS,
            scores = scores,
            fromTs = candles[firstEval].ts,
            toTs = candles[lastEval].ts
        ).takeIf { isComplete(it, symbol) }
    }

    /**
     * The trust gate: enough INDEPENDENT calls, a hit rate over the bar, and
     * a real edge over the stock's own drift. A rule that merely rode a
     * rising tape scores its base rate and is not trusted for it.
     */
    fun isTrustworthy(independentSignals: Int, independentHitRate: Int, baseRatePct: Int): Boolean =
        independentSignals >= MIN_INDEPENDENT_SIGNALS &&
            independentHitRate >= TRUST_HIT_RATE &&
            independentHitRate >= baseRatePct + TRUST_EDGE_OVER_BASE

    fun isComplete(evaluation: TechniqueEvaluation, symbol: String = evaluation.symbol): Boolean =
        evaluation.symbol.isNotBlank() &&
            evaluation.symbol.equals(symbol, ignoreCase = true) &&
            evaluation.daysEvaluated == LOOKBACK_DAYS &&
            evaluation.horizonDays == HORIZON_DAYS &&
            evaluation.scores.size == Techniques.TECHNIQUE_COUNT &&
            evaluation.scores.map { it.key }.toSet().size == Techniques.TECHNIQUE_COUNT &&
            evaluation.scores.all {
                it.key.isNotBlank() &&
                    it.signals in 0..LOOKBACK_DAYS &&
                    it.hits in 0..it.signals &&
                    it.hitRate in 0..100 &&
                    it.independentSignals in 0..it.signals &&
                    it.independentHits in 0..it.independentSignals &&
                    it.avgMovePct.isFinite() &&
                    it.trusted ==
                    isTrustworthy(it.independentSignals, it.independentHitRate, it.baseRatePct)
            }

    // ---- JSON (for the on-device cache) ------------------------------------

    fun toJson(e: TechniqueEvaluation): String {
        val root = JSONObject()
        root.put("symbol", e.symbol)
        root.put("days", e.daysEvaluated)
        root.put("horizon", e.horizonDays)
        root.put("fromTs", e.fromTs)
        root.put("toTs", e.toTs)
        val arr = JSONArray()
        for (s in e.scores) {
            arr.put(
                JSONObject().apply {
                    put("key", s.key)
                    put("name", s.name)
                    put("signals", s.signals)
                    put("hits", s.hits)
                    put("hitRate", s.hitRate)
                    put("avgMove", s.avgMovePct)
                    put("trusted", s.trusted)
                    put("iSignals", s.independentSignals)
                    put("iHits", s.independentHits)
                    put("iHitRate", s.independentHitRate)
                    put("baseRate", s.baseRatePct)
                    put("ciLow", s.ciLowPct)
                    put("ciHigh", s.ciHighPct)
                }
            )
        }
        root.put("scores", arr)
        return root.toString()
    }

    fun fromJson(json: String): TechniqueEvaluation? = try {
        val root = JSONObject(json)
        val arr = root.getJSONArray("scores")
        val scores = ArrayList<TechniqueScore>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            scores.add(
                TechniqueScore(
                    key = o.getString("key"),
                    name = o.optString("name", o.getString("key")),
                    signals = o.optInt("signals", 0),
                    hits = o.optInt("hits", 0),
                    hitRate = o.optInt("hitRate", 0),
                    avgMovePct = o.optDouble("avgMove", 0.0),
                    trusted = o.optBoolean("trusted", false),
                    independentSignals = o.optInt("iSignals", 0),
                    independentHits = o.optInt("iHits", 0),
                    independentHitRate = o.optInt("iHitRate", 0),
                    baseRatePct = o.optInt("baseRate", 0),
                    ciLowPct = o.optInt("ciLow", 0),
                    ciHighPct = o.optInt("ciHigh", 0)
                )
            )
        }
        TechniqueEvaluation(
            symbol = root.getString("symbol"),
            daysEvaluated = root.optInt("days", 0),
            horizonDays = root.optInt("horizon", HORIZON_DAYS),
            scores = scores,
            fromTs = root.optLong("fromTs", 0L),
            toTs = root.optLong("toTs", 0L)
        ).takeIf { isComplete(it) }
    } catch (_: Exception) {
        null
    }
}
