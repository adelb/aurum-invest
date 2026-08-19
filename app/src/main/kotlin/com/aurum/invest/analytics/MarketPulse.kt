package com.aurum.invest.analytics

import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * The week's verdict: deploy, deploy selectively, or keep the powder dry.
 * INCOMPLETE means too little of the input data could actually be measured
 * to honestly make any of the other three calls — it is a refusal, not a
 * neutral score.
 */
enum class MarketCall { INVEST, SELECTIVE, DEFENSIVE, INCOMPLETE }

/** One benchmark's trend read. */
data class IndexRead(
    val symbol: String,
    val name: String,
    val r5Pct: Double,     // 5-trading-day return
    val r20Pct: Double,    // 20-trading-day return
    val vs50Pct: Double    // price vs its own 50-day average
)

/** One of the last session's best performers, liquidity-gated. */
data class MarketMover(
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double,
    val volumeRatio: Double    // session volume vs the 3-month average (0 = unknown)
)

/** The whole-market read shown in the Wealth tab. */
data class MarketRating(
    val date: String,
    val computedAt: Long,
    /** 0..100 market health; null when the call is INCOMPLETE — a refusal carries no number. */
    val score: Int?,
    val call: MarketCall,
    val headline: String,
    val advice: String,
    val reasons: List<String>,          // concrete numbers behind the score
    /** % of scanned names above their 50-day avg; null when the pool was unmeasurable. */
    val breadthAbove50Pct: Double?,
    /** % of scanned names green in the last session; null when the pool was unmeasurable. */
    val advancersPct: Double?,
    val scannedCount: Int,              // how many liquid names the breadth used
    val vix: Double?,                   // null when the volatility read failed
    /** VIX now minus its close 5 sessions ago, in points; null when history was short. */
    val vixChange5d: Double?,
    val indexes: List<IndexRead>,
    val bestYesterday: List<MarketMover>,
    /**
     * Share of the score's input weight that was actually MEASURED (benchmarks
     * reached, breadth pool populated, VIX served) rather than substituted
     * with neutral points. Below [MarketPulse.MIN_COVERAGE_PCT] the call is
     * INCOMPLETE — a market call built mostly on placeholders is not a call.
     */
    val coveragePct: Double = 100.0
)

/**
 * Rates the whole US market and answers "is this week worth new money?".
 * Every input is measured, never guessed:
 *
 *  1. Benchmark trend — SPY / QQQ / IWM daily candles: 5-day and 20-day
 *     returns plus distance from the 50-day average (40 pts).
 *  2. Breadth — the share of a few hundred liquid screener names trading
 *     above their own 50-day average (30 pts).
 *  3. Participation — the share that advanced in the last session (15 pts).
 *  4. Volatility — the VIX level (15 pts; a neutral 8 when unavailable).
 *
 * Score >= 60 -> INVEST, >= 42 -> SELECTIVE, else DEFENSIVE. Alongside the
 * verdict it collects the last session's best performers (liquidity-gated so
 * a halted micro-cap spike can't rank) and scans for names positioned for the
 * next session, each confirmed by the 35-technique board with an honest
 * ATR-based range. Never throws — null on total failure.
 */
class MarketPulse(private val market: MarketRepository) {

    companion object {
        private val INDEXES = listOf(
            "SPY" to "S&P 500",
            "QQQ" to "Nasdaq 100",
            "IWM" to "Russell 2000"
        )
        private const val VIX_SYMBOL = "^VIX"
        private const val SHORTLIST = 18
        private const val CANDLE_CHUNK = 6

        /** Below this measured-input share, the pulse refuses to make a call. */
        const val MIN_COVERAGE_PCT = 60.0

        /** A breadth/participation pool smaller than this counts as partially measured. */
        private const val FULL_POOL = 100

        /**
         * The volatility regime a VIX level sits in — the same bands the
         * score's [vixPoints] uses, so the label on screen and the points in
         * the score can never tell different stories.
         */
        fun vixRegime(vix: Double): String = when {
            vix < 14.0 -> "Very calm"
            vix < 17.0 -> "Calm"
            vix < 20.0 -> "Normal"
            vix < 25.0 -> "Elevated"
            vix < 30.0 -> "Stressed"
            else -> "Fear regime"
        }

        /** Plain-language reading of a VIX level, for the explain affordance. */
        fun vixMeaning(vix: Double): String = when {
            vix < 14.0 ->
                "Options traders are pricing very small daily swings. Historically a quiet, " +
                    "steady tape — favorable for holding positions, though calm this deep can " +
                    "precede complacency."
            vix < 17.0 ->
                "Expected daily swings are below average. A comfortable market for new money."
            vix < 20.0 ->
                "Around the long-run average — normal two-sided movement. Standard position " +
                    "sizing applies."
            vix < 25.0 ->
                "Options are pricing bigger-than-usual swings. Entries deserve smaller size " +
                    "and wider stops."
            vix < 30.0 ->
                "The market is paying up for protection — sharp drops and rips both come " +
                    "easier. Half-size at most, and expect stops to be tested."
            else ->
                "Panic pricing: historically the zone of capitulation days and violent " +
                    "rallies alike. New money has no edge here without a plan for being " +
                    "immediately wrong."
        }

        fun toJson(r: MarketRating): String = JSONObject().apply {
            put("date", r.date)
            put("computedAt", r.computedAt)
            putOpt("score", r.score)
            put("call", r.call.name)
            put("headline", r.headline)
            put("advice", r.advice)
            put("reasons", JSONArray(r.reasons))
            putOpt("breadth", r.breadthAbove50Pct)
            putOpt("advancers", r.advancersPct)
            put("scanned", r.scannedCount)
            put("coverage", r.coveragePct)
            if (r.vix != null) put("vix", r.vix)
            if (r.vixChange5d != null) put("vix5d", r.vixChange5d)
            put("indexes", JSONArray().apply {
                r.indexes.forEach { ix ->
                    put(JSONObject().apply {
                        put("symbol", ix.symbol)
                        put("name", ix.name)
                        put("r5", ix.r5Pct)
                        put("r20", ix.r20Pct)
                        put("vs50", ix.vs50Pct)
                    })
                }
            })
            put("best", JSONArray().apply {
                r.bestYesterday.forEach { m ->
                    put(JSONObject().apply {
                        put("symbol", m.symbol)
                        put("name", m.name)
                        put("price", m.price)
                        put("day", m.dayChangePct)
                        put("volRatio", m.volumeRatio)
                    })
                }
            })
        }.toString()

        fun fromJson(s: String): MarketRating? = try {
            val o = JSONObject(s)
            val reasons = ArrayList<String>()
            o.optJSONArray("reasons")?.let { arr ->
                for (i in 0 until arr.length()) reasons.add(arr.optString(i))
            }
            val indexes = ArrayList<IndexRead>()
            o.optJSONArray("indexes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val ix = arr.optJSONObject(i) ?: continue
                    indexes.add(
                        IndexRead(
                            symbol = ix.getString("symbol"),
                            name = ix.optString("name", ix.getString("symbol")),
                            r5Pct = ix.optDouble("r5", 0.0),
                            r20Pct = ix.optDouble("r20", 0.0),
                            vs50Pct = ix.optDouble("vs50", 0.0)
                        )
                    )
                }
            }
            val best = ArrayList<MarketMover>()
            o.optJSONArray("best")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    best.add(
                        MarketMover(
                            symbol = m.getString("symbol"),
                            name = m.optString("name", ""),
                            price = m.getDouble("price"),
                            dayChangePct = m.optDouble("day", 0.0),
                            volumeRatio = m.optDouble("volRatio", 0.0)
                        )
                    )
                }
            }
            MarketRating(
                date = o.getString("date"),
                computedAt = o.optLong("computedAt", 0L),
                score = if (o.has("score")) o.getInt("score") else null,
                // Cache defaults fail CLOSED: a missing/unreadable call is a
                // refusal, never silently upgraded to SELECTIVE; a missing
                // coverage field means the coverage was not recorded, not 100%.
                call = runCatching { MarketCall.valueOf(o.getString("call")) }
                    .getOrDefault(MarketCall.INCOMPLETE),
                headline = o.optString("headline", ""),
                advice = o.optString("advice", ""),
                reasons = reasons,
                breadthAbove50Pct = if (o.has("breadth")) o.getDouble("breadth") else null,
                advancersPct = if (o.has("advancers")) o.getDouble("advancers") else null,
                scannedCount = o.optInt("scanned", 0),
                vix = if (o.has("vix")) o.getDouble("vix") else null,
                vixChange5d = if (o.has("vix5d")) o.getDouble("vix5d") else null,
                indexes = indexes,
                bestYesterday = best,
                coveragePct = o.optDouble("coverage", 0.0)
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun compute(dateIso: String): MarketRating? {
        return try {
            val (indexes, vixRead, poolResult) = coroutineScope {
                val indexD = INDEXES.map { (sym, name) -> async { indexRead(sym, name) } }
                val vixD = async { vixRead() }
                val poolD = async { screenerPool() }
                Triple(indexD.awaitAll().filterNotNull(), vixD.await(), poolD.await())
            }
            val (vix, vixChange5d) = vixRead
            val (pool, screensServed) = poolResult
            if (indexes.isEmpty() && pool.isEmpty()) return null

            // Breadth + participation across the scanned liquid names — the
            // SAME pool for both, so the two reasons share one denominator.
            // An unmeasurable pool yields null, never a fabricated 50%.
            val measurable = pool.filter { it.fiftyDayAvg > 0.0 }
            val breadth: Double? =
                if (measurable.isNotEmpty()) {
                    measurable.count { it.price > it.fiftyDayAvg } * 100.0 / measurable.size
                } else null
            val advancers: Double? =
                if (measurable.isNotEmpty()) {
                    measurable.count { it.dayChangePct > 0.0 } * 100.0 / measurable.size
                } else null

            val idxPts = indexPoints(indexes)                    // 0..40
            val breadthPts = breadth?.let { it / 100.0 * 30.0 } ?: 15.0
            val advPts = advancers?.let { it / 100.0 * 15.0 } ?: 7.5
            val volPts = vixPoints(vix)                          // 0..15
            val score = (idxPts + breadthPts + advPts + volPts).roundToInt().coerceIn(0, 100)

            // How much of the score's weight was actually measured, not
            // substituted with neutral points. The pool bands are additionally
            // scaled by how many of the requested screens actually served —
            // 130 names from 4 of 12 screens is not a measured market.
            // Fail closed below the floor: a call built on placeholders is a
            // guess wearing a number.
            val screensRequested = EntryPicker.MARKET_SCREENS.size
            val reach =
                if (screensRequested > 0) screensServed.toDouble() / screensRequested else 0.0
            val coverage = (
                indexes.size / 3.0 * 40.0 +
                    min(1.0, measurable.size.toDouble() / FULL_POOL) * reach * 30.0 +
                    min(1.0, measurable.size.toDouble() / FULL_POOL) * reach * 15.0 +
                    (if (vix != null) 15.0 else 0.0)
                )
            val call = when {
                coverage < MIN_COVERAGE_PCT -> MarketCall.INCOMPLETE
                score >= 60 -> MarketCall.INVEST
                score >= 42 -> MarketCall.SELECTIVE
                else -> MarketCall.DEFENSIVE
            }

            val reasons = buildReasons(indexes, breadth, advancers, measurable.size, vix, vixChange5d)
                .toMutableList()
                .apply {
                    if (coverage < 100.0) {
                        add(
                            String.format(
                                Locale.US,
                                "Only %.0f%% of the score's inputs were measured this run",
                                coverage
                            )
                        )
                    }
                }
            val best = bestYesterday(pool)

            MarketRating(
                date = dateIso,
                computedAt = System.currentTimeMillis(),
                // A refusal carries no number — an INCOMPLETE call showing
                // "47/100" would read as a measurement.
                score = if (call == MarketCall.INCOMPLETE) null else score,
                call = call,
                headline = when (call) {
                    MarketCall.INVEST -> "A favorable week to put money to work."
                    MarketCall.SELECTIVE -> "A mixed tape — deploy selectively."
                    MarketCall.DEFENSIVE -> "A hostile tape — protect capital this week."
                    MarketCall.INCOMPLETE -> "Not enough measured data for a market call."
                },
                advice = when (call) {
                    MarketCall.INVEST ->
                        "Deploy in 2-3 tranches rather than all at once, favor the names " +
                            "below, and keep a stop under every position."
                    MarketCall.SELECTIVE ->
                        "Half-size entries only, in the strongest setups. Skip anything " +
                            "extended and keep stops tight."
                    MarketCall.DEFENSIVE ->
                        "This week does not favor new money. Sit on cash, let the market " +
                            "settle, and check the pulse again after the next session."
                    MarketCall.INCOMPLETE ->
                        "Too many inputs were unreachable to rate the market honestly. " +
                            "Refresh when you're back online — no call is better than a " +
                            "made-up one."
                },
                reasons = reasons,
                breadthAbove50Pct = breadth?.let { round1(it) },
                advancersPct = advancers?.let { round1(it) },
                scannedCount = measurable.size,
                vix = vix?.let { round1(it) },
                vixChange5d = vixChange5d?.let { round1(it) },
                indexes = indexes,
                bestYesterday = best,
                coveragePct = round1(coverage)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ score parts

    /**
     * The volatility read: spot VIX plus its move over the last 5 sessions.
     * Daily candles carry both; the live quote, when it serves, refines the
     * spot. Either half that cannot be measured is null, never defaulted.
     */
    private suspend fun vixRead(): Pair<Double?, Double?> {
        val closes = try {
            market.getDailyCandles(VIX_SYMBOL, 30).map { it.close }.filter { it > 0.0 }
        } catch (_: Exception) {
            emptyList()
        }
        val quote = try {
            market.getQuote(VIX_SYMBOL)?.price?.takeIf { it > 0.0 }
        } catch (_: Exception) {
            null
        }
        val spot = quote ?: closes.lastOrNull()
        val fiveAgo = if (closes.size >= 6) closes[closes.size - 6] else null
        val change = if (spot != null && fiveAgo != null) spot - fiveAgo else null
        return spot to change
    }

    private suspend fun indexRead(symbol: String, name: String): IndexRead? {
        return try {
            val closes = market.getDailyCandles(symbol, 120).map { it.close }
            val n = closes.size
            if (n < 55) return null
            val last = closes.last()
            val sma50 = Indicators.sma(closes, 50) ?: return null
            if (last <= 0.0 || sma50 <= 0.0) return null
            // A zero close in the window would make the return Infinity and
            // blow up the JSON cache write for the whole rating.
            if (closes[n - 6] <= 0.0 || closes[n - 21] <= 0.0) return null
            IndexRead(
                symbol = symbol,
                name = name,
                r5Pct = round1((last / closes[n - 6] - 1.0) * 100.0),
                r20Pct = round1((last / closes[n - 21] - 1.0) * 100.0),
                vs50Pct = round1((last / sma50 - 1.0) * 100.0)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** SPY carries the most weight; each index scores on trend, 20d and 5d. */
    private fun indexPoints(indexes: List<IndexRead>): Double {
        if (indexes.isEmpty()) return 20.0   // neutral when benchmarks unreachable
        val weights = mapOf("SPY" to 0.45, "QQQ" to 0.30, "IWM" to 0.25)
        var total = 0.0
        var weightSum = 0.0
        for (ix in indexes) {
            val w = weights[ix.symbol] ?: 0.25
            var part = 0.0
            if (ix.vs50Pct > 0.0) part += 0.45
            if (ix.r20Pct > 0.0) part += 0.30
            if (ix.r5Pct > 0.0) part += 0.25
            total += part * w
            weightSum += w
        }
        return if (weightSum > 0.0) total / weightSum * 40.0 else 20.0
    }

    private fun vixPoints(vix: Double?): Double = when {
        vix == null -> 8.0     // neutral: don't punish a missing feed
        vix < 14.0 -> 15.0
        vix < 17.0 -> 13.0
        vix < 20.0 -> 10.0
        vix < 25.0 -> 6.0
        vix < 30.0 -> 3.0
        else -> 0.0
    }

    private fun buildReasons(
        indexes: List<IndexRead>,
        breadth: Double?,
        advancers: Double?,
        scanned: Int,
        vix: Double?,
        vixChange5d: Double?
    ): List<String> {
        val out = mutableListOf<String>()
        indexes.firstOrNull { it.symbol == "SPY" }?.let { spy ->
            out += String.format(
                Locale.US,
                "S&P 500 %+.1f%% over 5 days, %+.1f%% over 20, %s its 50-day average",
                spy.r5Pct, spy.r20Pct,
                if (spy.vs50Pct >= 0.0) {
                    String.format(Locale.US, "%.1f%% above", spy.vs50Pct)
                } else {
                    String.format(Locale.US, "%.1f%% below", -spy.vs50Pct)
                }
            )
        }
        if (indexes.size > 1) {
            out += indexes.filter { it.symbol != "SPY" }.joinToString(" · ") {
                String.format(Locale.US, "%s %+.1f%% in 5 days", it.name, it.r5Pct)
            }
        }
        if (scanned > 0 && breadth != null) {
            out += String.format(
                Locale.US,
                "%.0f%% of %d scanned liquid names trade above their 50-day average",
                breadth, scanned
            )
        }
        if (scanned > 0 && advancers != null) {
            out += String.format(
                Locale.US,
                "%.0f%% of the same %d names advanced in the last session",
                advancers, scanned
            )
        }
        if (breadth == null) {
            out += "Breadth pool unmeasurable this run — scored neutral, not measured"
        }
        out += if (vix == null) {
            "Volatility read unavailable — scored neutral"
        } else {
            val drift = vixChange5d?.let {
                String.format(Locale.US, ", %s%.1f pts in 5 days", if (it >= 0) "+" else "", it)
            } ?: ""
            String.format(
                Locale.US, "VIX at %.1f — %s%s", vix, vixRegime(vix).lowercase(Locale.US), drift
            )
        }
        return out
    }

    // ------------------------------------------------------------ movers + next day

    /**
     * The market-wide pool, merged and deduped from Yahoo's saved screens,
     * plus how many of the requested screens actually served — the coverage
     * math needs the reach, not just the row count.
     */
    private suspend fun screenerPool(): Pair<List<ScreenerQuote>, Int> {
        val pool = HashMap<String, ScreenerQuote>()
        var served = 0
        for (chunk in EntryPicker.MARKET_SCREENS.chunked(4)) {
            try {
                coroutineScope {
                    chunk.map { id ->
                        async {
                            try {
                                market.getScreener(id)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }.forEach { list ->
                    if (list.isNotEmpty()) served++
                    list.forEach { q -> pool.putIfAbsent(q.symbol, q) }
                }
            } catch (_: Exception) {
                // A failed batch just narrows the pool.
            }
        }
        return pool.values.toList() to served
    }

    /** Last session's best performers — liquid, real names only. */
    private fun bestYesterday(pool: List<ScreenerQuote>): List<MarketMover> =
        pool.asSequence()
            .filter {
                it.price >= 5.0 &&
                    it.avgVolume3M >= 1_000_000L &&
                    it.marketCap >= 500_000_000.0 &&
                    it.dayChangePct > 0.0 &&
                    it.symbol.all { ch -> ch.isLetterOrDigit() }
            }
            .sortedByDescending { it.dayChangePct }
            .take(5)
            .map {
                MarketMover(
                    symbol = it.symbol,
                    name = it.name.ifEmpty { it.symbol },
                    price = round2(it.price),
                    dayChangePct = round1(it.dayChangePct),
                    volumeRatio =
                        if (it.avgVolume3M > 0L && it.dayVolume > 0L) {
                            round1(it.dayVolume.toDouble() / it.avgVolume3M)
                        } else 0.0
                )
            }
            .toList()

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
