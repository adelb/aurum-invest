package com.aurum.invest.analytics

import com.aurum.invest.data.model.Candle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Standalone back-testing engine for the technique board. It replays the last
 * ~3 months day by day: for each session it re-runs [Techniques.analyze] on
 * only the candles that existed that day, records every technique's verdict,
 * and grades it against the REAL move of the following 5 trading days.
 *
 * A directional call is a hit when the stock then moved at least
 * [MOVE_DEADBAND_PCT] in the called direction. Neutral verdicts are not
 * graded — saying "no signal" is not a prediction.
 *
 * A technique earns TRUSTED on a stock only with a real track record there:
 * at least [MIN_SIGNALS] directional calls and a hit rate of
 * [TRUST_HIT_RATE]% or better. Trusted techniques get the gold border on the
 * analysis screen and extra vote weight in the 5-day outlook.
 *
 * Pure Kotlin, deterministic, never throws.
 */

/** One technique's measured 3-month track record on one stock. */
data class TechniqueScore(
    val key: String,
    val name: String,
    /** Directional (non-neutral) verdicts that could be graded. */
    val signals: Int,
    /** Calls where the stock moved >= deadband in the called direction. */
    val hits: Int,
    /** hits / signals as a percent, 0 when there were no signals. */
    val hitRate: Int,
    /** Average signed 5-day move in the called direction, percent. */
    val avgMovePct: Double,
    /** True when signals >= MIN_SIGNALS and hitRate >= TRUST_HIT_RATE. */
    val trusted: Boolean
)

data class TechniqueEvaluation(
    val symbol: String,
    /** Trading days actually replayed. */
    val daysEvaluated: Int,
    /** Forward window each call was graded against, in trading days. */
    val horizonDays: Int,
    /** One score per technique, in board order. */
    val scores: List<TechniqueScore>
) {
    val trustedKeys: Set<String> get() = scores.filter { it.trusted }.map { it.key }.toSet()

    /**
     * Hit rate per technique key for outlook weighting — only techniques with
     * enough calls to mean anything ([TechniqueEvaluator.MIN_WEIGHT_SIGNALS]).
     */
    fun weights(): Map<String, Int> = scores
        .filter { it.signals >= TechniqueEvaluator.MIN_WEIGHT_SIGNALS }
        .associate { it.key to it.hitRate }
}

object TechniqueEvaluator {

    /** Trading days replayed — ~3 months. */
    const val LOOKBACK_DAYS = 63

    /** Forward horizon each verdict is graded against, matching the 5-day outlook. */
    const val HORIZON_DAYS = 5

    /** Minimum move (percent) in the called direction to count as a hit. */
    const val MOVE_DEADBAND_PCT = 0.5

    /** Directional calls needed before a technique can be trusted. */
    const val MIN_SIGNALS = 8

    /** Hit-rate bar (percent) for the trusted badge. */
    const val TRUST_HIT_RATE = 60

    /** Calls needed before a hit rate is allowed to weight the outlook at all. */
    const val MIN_WEIGHT_SIGNALS = 5

    private class Tally(val name: String) {
        var signals = 0
        var hits = 0
        var moveSum = 0.0
    }

    /**
     * Replays the last [LOOKBACK_DAYS] sessions of [candles] (daily, oldest
     * first — the same list the analysis screen already fetches) and grades
     * every technique. Null when there is not enough history to replay a
     * single day (needs 30 candles for the board plus the forward window).
     */
    fun evaluate(symbol: String, candles: List<Candle>): TechniqueEvaluation? {
        val n = candles.size
        val lastEval = n - 1 - HORIZON_DAYS
        // analyze() needs >= 30 candles, i.e. as-of index >= 29.
        val firstEval = max(29, lastEval - LOOKBACK_DAYS + 1)
        if (lastEval < firstEval) return null

        val tallies = LinkedHashMap<String, Tally>()
        var days = 0
        for (t in firstEval..lastEval) {
            val base = candles[t].close
            if (base <= 0.0) continue
            val analysis = Techniques.analyze(symbol, candles.subList(0, t + 1)) ?: continue
            val movePct = (candles[t + HORIZON_DAYS].close - base) / base * 100.0
            days++
            for (r in analysis.results) {
                val tally = tallies.getOrPut(r.key) { Tally(r.name) }
                when (r.verdict) {
                    TechniqueVerdict.BULLISH -> {
                        tally.signals++
                        tally.moveSum += movePct
                        if (movePct >= MOVE_DEADBAND_PCT) tally.hits++
                    }
                    TechniqueVerdict.BEARISH -> {
                        tally.signals++
                        tally.moveSum -= movePct
                        if (movePct <= -MOVE_DEADBAND_PCT) tally.hits++
                    }
                    TechniqueVerdict.NEUTRAL -> Unit
                }
            }
        }
        if (days == 0 || tallies.isEmpty()) return null

        val scores = tallies.map { (key, t) ->
            val rate = if (t.signals > 0) (t.hits * 100.0 / t.signals).roundToInt() else 0
            TechniqueScore(
                key = key,
                name = t.name,
                signals = t.signals,
                hits = t.hits,
                hitRate = rate,
                avgMovePct = if (t.signals > 0) t.moveSum / t.signals else 0.0,
                trusted = t.signals >= MIN_SIGNALS && rate >= TRUST_HIT_RATE
            )
        }
        return TechniqueEvaluation(
            symbol = symbol,
            daysEvaluated = days,
            horizonDays = HORIZON_DAYS,
            scores = scores
        )
    }

    // ---- JSON (for the on-device cache) ------------------------------------

    fun toJson(e: TechniqueEvaluation): String {
        val root = JSONObject()
        root.put("symbol", e.symbol)
        root.put("days", e.daysEvaluated)
        root.put("horizon", e.horizonDays)
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
                    trusted = o.optBoolean("trusted", false)
                )
            )
        }
        if (scores.isEmpty()) null
        else TechniqueEvaluation(
            symbol = root.getString("symbol"),
            daysEvaluated = root.optInt("days", 0),
            horizonDays = root.optInt("horizon", HORIZON_DAYS),
            scores = scores
        )
    } catch (_: Exception) {
        null
    }
}
