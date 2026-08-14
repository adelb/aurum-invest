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

/** The week's verdict: deploy, deploy selectively, or keep the powder dry. */
enum class MarketCall { INVEST, SELECTIVE, DEFENSIVE }

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

/** A candidate positioned for the next session, confirmed by the technique board. */
data class TomorrowPick(
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double,
    val score: Double,           // 0..100 display score
    val entry: Double,           // suggested entry (== price when buying near the open)
    val expectedLowPct: Double,  // honest next-session range, low bound (negative)
    val expectedHighPct: Double, // high bound (positive)
    val rsi: Double,
    val techBullish: Int,
    val techTotal: Int,
    val reason: String
)

/** The whole-market read shown in the Wealth tab. */
data class MarketRating(
    val date: String,
    val computedAt: Long,
    val score: Int,                     // 0..100 market health
    val call: MarketCall,
    val headline: String,
    val advice: String,
    val reasons: List<String>,          // concrete numbers behind the score
    val breadthAbove50Pct: Double,      // % of scanned names above their 50-day avg
    val advancersPct: Double,           // % of scanned names green in the last session
    val scannedCount: Int,              // how many liquid names the breadth used
    val vix: Double?,                   // null when the volatility read failed
    val indexes: List<IndexRead>,
    val bestYesterday: List<MarketMover>,
    val nextDay: List<TomorrowPick>
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
 * next session, each confirmed by the 20-technique board with an honest
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

        fun toJson(r: MarketRating): String = JSONObject().apply {
            put("date", r.date)
            put("computedAt", r.computedAt)
            put("score", r.score)
            put("call", r.call.name)
            put("headline", r.headline)
            put("advice", r.advice)
            put("reasons", JSONArray(r.reasons))
            put("breadth", r.breadthAbove50Pct)
            put("advancers", r.advancersPct)
            put("scanned", r.scannedCount)
            if (r.vix != null) put("vix", r.vix)
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
            put("next", JSONArray().apply {
                r.nextDay.forEach { p ->
                    put(JSONObject().apply {
                        put("symbol", p.symbol)
                        put("name", p.name)
                        put("price", p.price)
                        put("day", p.dayChangePct)
                        put("score", p.score)
                        put("entry", p.entry)
                        put("lo", p.expectedLowPct)
                        put("hi", p.expectedHighPct)
                        put("rsi", p.rsi)
                        put("techBullish", p.techBullish)
                        put("techTotal", p.techTotal)
                        put("reason", p.reason)
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
            val next = ArrayList<TomorrowPick>()
            o.optJSONArray("next")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    next.add(
                        TomorrowPick(
                            symbol = p.getString("symbol"),
                            name = p.optString("name", ""),
                            price = p.getDouble("price"),
                            dayChangePct = p.optDouble("day", 0.0),
                            score = p.optDouble("score", 0.0),
                            entry = p.optDouble("entry", p.getDouble("price")),
                            expectedLowPct = p.optDouble("lo", -1.0),
                            expectedHighPct = p.optDouble("hi", 2.0),
                            rsi = p.optDouble("rsi", 50.0),
                            techBullish = p.optInt("techBullish", 0),
                            techTotal = p.optInt("techTotal", 0),
                            reason = p.optString("reason", "")
                        )
                    )
                }
            }
            MarketRating(
                date = o.getString("date"),
                computedAt = o.optLong("computedAt", 0L),
                score = o.getInt("score"),
                call = runCatching { MarketCall.valueOf(o.getString("call")) }
                    .getOrDefault(MarketCall.SELECTIVE),
                headline = o.optString("headline", ""),
                advice = o.optString("advice", ""),
                reasons = reasons,
                breadthAbove50Pct = o.optDouble("breadth", 50.0),
                advancersPct = o.optDouble("advancers", 50.0),
                scannedCount = o.optInt("scanned", 0),
                vix = if (o.has("vix")) o.getDouble("vix") else null,
                indexes = indexes,
                bestYesterday = best,
                nextDay = next
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun compute(dateIso: String): MarketRating? {
        return try {
            val (indexes, vix, pool) = coroutineScope {
                val indexD = INDEXES.map { (sym, name) -> async { indexRead(sym, name) } }
                val vixD = async {
                    try {
                        market.getQuote(VIX_SYMBOL)?.price?.takeIf { it > 0.0 }
                    } catch (_: Exception) {
                        null
                    }
                }
                val poolD = async { screenerPool() }
                Triple(indexD.awaitAll().filterNotNull(), vixD.await(), poolD.await())
            }
            if (indexes.isEmpty() && pool.isEmpty()) return null

            // Breadth + participation across the scanned liquid names.
            val measurable = pool.filter { it.fiftyDayAvg > 0.0 }
            val breadth =
                if (measurable.isNotEmpty()) {
                    measurable.count { it.price > it.fiftyDayAvg } * 100.0 / measurable.size
                } else 50.0
            val advancers =
                if (pool.isNotEmpty()) {
                    pool.count { it.dayChangePct > 0.0 } * 100.0 / pool.size
                } else 50.0

            val idxPts = indexPoints(indexes)          // 0..40
            val breadthPts = breadth / 100.0 * 30.0    // 0..30
            val advPts = advancers / 100.0 * 15.0      // 0..15
            val volPts = vixPoints(vix)                // 0..15
            val score = (idxPts + breadthPts + advPts + volPts).roundToInt().coerceIn(0, 100)
            val call = when {
                score >= 60 -> MarketCall.INVEST
                score >= 42 -> MarketCall.SELECTIVE
                else -> MarketCall.DEFENSIVE
            }

            val reasons = buildReasons(indexes, breadth, advancers, measurable.size, vix)
            val best = bestYesterday(pool)
            val next = tomorrowPicks(pool)

            MarketRating(
                date = dateIso,
                computedAt = System.currentTimeMillis(),
                score = score,
                call = call,
                headline = when (call) {
                    MarketCall.INVEST -> "A favorable week to put money to work."
                    MarketCall.SELECTIVE -> "A mixed tape — deploy selectively."
                    MarketCall.DEFENSIVE -> "A hostile tape — protect capital this week."
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
                },
                reasons = reasons,
                breadthAbove50Pct = round1(breadth),
                advancersPct = round1(advancers),
                scannedCount = measurable.size,
                vix = vix?.let { round1(it) },
                indexes = indexes,
                bestYesterday = best,
                nextDay = next
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ score parts

    private suspend fun indexRead(symbol: String, name: String): IndexRead? {
        return try {
            val closes = market.getDailyCandles(symbol, 120).map { it.close }
            val n = closes.size
            if (n < 55) return null
            val last = closes.last()
            val sma50 = Indicators.sma(closes, 50) ?: return null
            if (last <= 0.0 || sma50 <= 0.0) return null
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
        breadth: Double,
        advancers: Double,
        scanned: Int,
        vix: Double?
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
        if (scanned > 0) {
            out += String.format(
                Locale.US,
                "%.0f%% of %d scanned liquid names trade above their 50-day average",
                breadth, scanned
            )
            out += String.format(
                Locale.US,
                "%.0f%% of the scan advanced in the last session",
                advancers
            )
        }
        out += when {
            vix == null -> "Volatility read unavailable — scored neutral"
            vix < 17.0 -> String.format(Locale.US, "VIX at %.1f — calm tape", vix)
            vix < 25.0 -> String.format(Locale.US, "VIX at %.1f — elevated volatility", vix)
            else -> String.format(Locale.US, "VIX at %.1f — fear regime", vix)
        }
        return out
    }

    // ------------------------------------------------------------ movers + next day

    /** The market-wide pool, merged and deduped from Yahoo's saved screens. */
    private suspend fun screenerPool(): List<ScreenerQuote> {
        val pool = HashMap<String, ScreenerQuote>()
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
                }.forEach { list -> list.forEach { q -> pool.putIfAbsent(q.symbol, q) } }
            } catch (_: Exception) {
                // A failed batch just narrows the pool.
            }
        }
        return pool.values.toList()
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

    /**
     * Names positioned for the next session: strong-but-not-blow-off day,
     * closing near the high, above the 50-day, volume running hot — then the
     * ~18 best are confirmed against RSI, ATR and the 20-technique board.
     */
    private suspend fun tomorrowPicks(pool: List<ScreenerQuote>): List<TomorrowPick> {
        val shortlist = pool.asSequence()
            .filter {
                it.price in 2.0..2500.0 &&
                    it.avgVolume3M >= 1_000_000L &&
                    it.price * it.avgVolume3M >= 20_000_000.0 &&
                    it.marketCap >= 500_000_000.0 &&
                    it.fiftyDayAvg > 0.0 &&
                    it.symbol.all { ch -> ch.isLetterOrDigit() }
            }
            .mapNotNull { q -> preScore(q)?.let { q to it } }
            .sortedByDescending { it.second }
            .take(SHORTLIST)
            .toList()
        if (shortlist.isEmpty()) return emptyList()

        val deep = ArrayList<TomorrowPick>()
        for (chunk in shortlist.chunked(CANDLE_CHUNK)) {
            val results = coroutineScope {
                chunk.map { (q, pre) -> async { deepRead(q, pre) } }.awaitAll()
            }
            results.filterNotNull().forEach { deep.add(it) }
        }
        if (deep.isEmpty()) return emptyList()

        val ranked = deep.sortedByDescending { it.score }.take(5)
        val minS = ranked.minOf { it.score }
        val maxS = ranked.maxOf { it.score }
        val span = maxS - minS
        return ranked.map { p ->
            val scaled = if (span > 0.0) 55.0 + (p.score - minS) / span * 45.0 else 70.0
            p.copy(score = round1(scaled))
        }
    }

    /** Cheap next-session case from screener fields alone; null = no case. */
    private fun preScore(q: ScreenerQuote): Double? {
        val vs50 = (q.price / q.fiftyDayAvg - 1.0) * 100.0
        if (vs50 < -1.0) return null                       // trend must be intact
        if (q.dayChangePct < -0.5 || q.dayChangePct > 12.0) return null

        val closePos = if (q.dayHigh > q.dayLow && q.dayLow > 0.0) {
            (q.price - q.dayLow) / (q.dayHigh - q.dayLow)
        } else 0.5
        if (closePos < 0.5) return null                    // fading closes carry over

        val above200 = q.twoHundredDayAvg > 0.0 && q.price > q.twoHundredDayAvg
        val volPace = if (q.avgVolume3M > 0L) q.dayVolume.toDouble() / q.avgVolume3M else 0.0

        val strength = when {
            q.dayChangePct in 1.0..8.0 -> 8.0 + (4.0 - abs(q.dayChangePct - 4.0))
            q.dayChangePct > 8.0 -> 5.0
            else -> 3.0
        }
        val closeScore = ((closePos - 0.5) * 22.0).coerceIn(0.0, 11.0)
        val volScore = ((volPace - 0.8) * 6.0).coerceIn(0.0, 9.0)
        val trendScore = (vs50 * 0.3).coerceIn(0.0, 6.0) + (if (above200) 3.0 else 0.0)
        val ratingScore = q.analystRating?.let { (3.0 - it) * 2.0 }?.coerceIn(0.0, 4.0) ?: 0.0
        return strength + closeScore + volScore + trendScore + ratingScore
    }

    /** Technique-board confirmation + honest ATR range; null drops the name. */
    private suspend fun deepRead(q: ScreenerQuote, pre: Double): TomorrowPick? {
        return try {
            val candles = try {
                market.getDailyCandles(q.symbol, 180)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < 30) return null
            val closes = candles.map { it.close }
            val rsi = Indicators.rsi(closes) ?: return null
            if (rsi > 78.0) return null                    // too hot to chase tomorrow
            val atr = Indicators.atr(candles) ?: return null
            val atrPct = atr / q.price * 100.0

            val analysis = Techniques.analyze(q.symbol, candles)
            val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
            if (direction == TechniqueVerdict.BEARISH) return null
            val bullish = analysis?.outlook?.bullishCount ?: 0
            val techTotal = analysis?.results?.size ?: 0
            val confidence = analysis?.outlook?.confidence ?: 0

            val rsiScore = (10.0 - abs(rsi - 62.0) / 2.5).coerceIn(0.0, 10.0)
            val boardScore =
                if (direction == TechniqueVerdict.BULLISH) confidence * 0.12 else 2.0
            val score = pre * 0.7 + rsiScore + boardScore

            // Honest next-session potential from volatility capacity — a range,
            // never a promise. Extended RSI earns a pullback entry instead of
            // a chase at the open.
            val volPace = if (q.avgVolume3M > 0L) q.dayVolume.toDouble() / q.avgVolume3M else 0.0
            val catalyst = ((volPace - 1.2).coerceAtLeast(0.0) * 0.8).coerceAtMost(1.5)
            var hiPct = (atrPct * 1.3 + catalyst).coerceIn(1.5, 10.0)
            val loPct = (atrPct * 0.8).coerceIn(0.8, 6.0)
            if (hiPct < loPct + 0.7) hiPct = min(10.0, loPct + 0.7)
            val entry = if (rsi >= 68.0) q.price - atr * 0.35 else q.price

            TomorrowPick(
                symbol = q.symbol,
                name = q.name.ifEmpty { q.symbol },
                price = round2(q.price),
                dayChangePct = round1(q.dayChangePct),
                score = score,
                entry = round2(entry),
                expectedLowPct = -round1(loPct),
                expectedHighPct = round1(hiPct),
                rsi = round1(rsi),
                techBullish = bullish,
                techTotal = techTotal,
                reason = buildPickReason(q, volPace, rsi, bullish, techTotal, entry)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPickReason(
        q: ScreenerQuote,
        volPace: Double,
        rsi: Double,
        bullish: Int,
        techTotal: Int,
        entry: Double
    ): String {
        val parts = mutableListOf<String>()
        parts += String.format(Locale.US, "%+.1f%% last session", q.dayChangePct)
        if (volPace >= 1.2) parts += String.format(Locale.US, "%.1fx volume", volPace)
        if (q.dayHigh > q.dayLow && q.dayLow > 0.0) {
            val closePos = (q.price - q.dayLow) / (q.dayHigh - q.dayLow) * 100.0
            parts += String.format(Locale.US, "closed at %.0f%% of the range", closePos)
        }
        if (techTotal > 0) parts += "$bullish of $techTotal techniques bullish"
        parts += String.format(Locale.US, "RSI %.0f", rsi)
        if (entry < q.price) {
            parts += String.format(Locale.US, "extended — wait for a dip to $%.2f", entry)
        }
        return parts.joinToString(", ")
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
