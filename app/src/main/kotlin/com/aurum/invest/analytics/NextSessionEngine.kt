package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** One stock positioned for the next session, fully measured. */
data class NextSessionPick(
    val symbol: String,
    val name: String,
    val price: Double,             // live print at build time (extended-hours aware)
    val dayChangePct: Double,      // last regular session's move
    val score: Int,                // 0..100 composite on a FIXED scale
    /**
     * Measured follow-through: of the past sessions that looked like today
     * (same-direction move of similar size, similar close position in range),
     * the share that closed HIGHER the next day. -1 when fewer than
     * [NextSessionEngine.MIN_ANALOGS] analog days exist — shown as not
     * measured, never invented.
     */
    val probUpPct: Int,
    val analogDays: Int,           // how many analog sessions the probability used
    val avgNextDayPct: Double,     // average next-day move after those analogs
    val entry: Double,
    val target: Double,
    val stop: Double,
    val expectedLowPct: Double,    // honest ATR-based next-session range
    val expectedHighPct: Double,
    val rsi: Double,
    val techBullish: Int,
    val techTotal: Int,
    val techConfidence: Int,
    val volumeRatio: Double,       // last session vs the 3-month average
    val closePosPct: Double,       // where the close landed in the day's range
    val extNote: String,           // "" unless a pre/post print says something
    val heldNote: String,          // "" unless the user already holds it
    /** True when every gate of the extreme-probability alert fired. */
    val alert: Boolean,
    val reason: String
)

/** The next-session answer: 10 picks, ranked, with the alert flags. */
data class NextSessionReport(
    val computedAt: Long,
    val sessionNote: String,       // which session the picks aim at
    val picks: List<NextSessionPick>,
    val headline: String,
    val notes: List<String>
) {
    val alerts: List<NextSessionPick> get() = picks.filter { it.alert }

    companion object {
        fun toJson(r: NextSessionReport): String = JSONObject().apply {
            put("computedAt", r.computedAt)
            put("sessionNote", r.sessionNote)
            put("headline", r.headline)
            put("notes", JSONArray(r.notes))
            put("picks", JSONArray().apply {
                r.picks.forEach { p ->
                    put(JSONObject().apply {
                        put("symbol", p.symbol); put("name", p.name)
                        put("price", p.price); put("day", p.dayChangePct)
                        put("score", p.score); put("probUp", p.probUpPct)
                        put("analogs", p.analogDays); put("avgNext", p.avgNextDayPct)
                        put("entry", p.entry); put("target", p.target); put("stop", p.stop)
                        put("lo", p.expectedLowPct); put("hi", p.expectedHighPct)
                        put("rsi", p.rsi); put("tb", p.techBullish); put("tt", p.techTotal)
                        put("conf", p.techConfidence); put("volRatio", p.volumeRatio)
                        put("closePos", p.closePosPct); put("ext", p.extNote)
                        put("held", p.heldNote); put("alert", p.alert)
                        put("reason", p.reason)
                    })
                }
            })
        }.toString()

        fun fromJson(json: String): NextSessionReport? = try {
            val o = JSONObject(json)
            val picks = ArrayList<NextSessionPick>()
            o.optJSONArray("picks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    picks.add(
                        NextSessionPick(
                            symbol = p.getString("symbol"),
                            name = p.optString("name", ""),
                            price = p.optDouble("price", 0.0),
                            dayChangePct = p.optDouble("day", 0.0),
                            score = p.optInt("score", 0),
                            probUpPct = p.optInt("probUp", -1),
                            analogDays = p.optInt("analogs", 0),
                            avgNextDayPct = p.optDouble("avgNext", 0.0),
                            entry = p.optDouble("entry", 0.0),
                            target = p.optDouble("target", 0.0),
                            stop = p.optDouble("stop", 0.0),
                            expectedLowPct = p.optDouble("lo", -1.0),
                            expectedHighPct = p.optDouble("hi", 2.0),
                            rsi = p.optDouble("rsi", 50.0),
                            techBullish = p.optInt("tb", 0),
                            techTotal = p.optInt("tt", 0),
                            techConfidence = p.optInt("conf", 0),
                            volumeRatio = p.optDouble("volRatio", 0.0),
                            closePosPct = p.optDouble("closePos", 50.0),
                            extNote = p.optString("ext", ""),
                            heldNote = p.optString("held", ""),
                            alert = p.optBoolean("alert", false),
                            reason = p.optString("reason", "")
                        )
                    )
                }
            }
            NextSessionReport(
                computedAt = o.optLong("computedAt", 0L),
                sessionNote = o.optString("sessionNote", ""),
                picks = picks,
                headline = o.optString("headline", ""),
                notes = buildList {
                    val n = o.optJSONArray("notes") ?: JSONArray()
                    for (i in 0 until n.length()) add(n.optString(i))
                }
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The next-session engine: scans the whole US market (Yahoo's saved screens),
 * shortlists the names whose last session set up a continuation, then puts
 * each finalist through everything the app can measure —
 *
 *  1. the 35-technique board (a bearish board disqualifies),
 *  2. a MEASURED analog-day study: it finds the past sessions of the last ~6
 *     months that looked like today (same-direction move of similar size,
 *     similar close position in the range) and counts how often the NEXT day
 *     actually closed higher, and by how much on average,
 *  3. trend quality against the 50- and 200-day averages,
 *  4. volume pace against the 3-month average,
 *  5. the latest pre/post-market print,
 *
 * — and returns the 10 strongest with a fixed-scale score, an entry, a
 * target, a stop, and an honest ATR-based range. A pick raises the
 * EXTREME-PROBABILITY alert only when every gate fires at once: score >=
 * [ALERT_SCORE], a measured [ALERT_PROB]%+ analog follow-through over >=
 * [ALERT_MIN_ANALOGS] analog days, and a bullish board at >=
 * [ALERT_CONFIDENCE]% confidence. The alert is a measured statement about
 * history, never a promise.
 *
 * Portfolio-aware: names already held are tagged. Never throws — null on
 * total failure.
 */
class NextSessionEngine(private val market: MarketRepository) {

    companion object {
        const val PICKS = 10
        const val MIN_ANALOGS = 6

        // The extreme-probability alert gates — all must fire.
        const val ALERT_SCORE = 78
        const val ALERT_PROB = 65
        const val ALERT_MIN_ANALOGS = 8
        const val ALERT_CONFIDENCE = 60

        private const val SHORTLIST = 26
        private const val DEEP_CHUNK = 5
        private const val ANALOG_LOOKBACK = 130
    }

    /** [held] maps open-position symbols to cost dollars for the held tags. */
    suspend fun compute(held: Map<String, Double> = emptyMap()): NextSessionReport? {
        return try {
            // 1 — the whole market, merged and deduped from the saved screens.
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
            if (pool.isEmpty()) return null

            // 2 — cheap shortlist from screener fields alone.
            val shortlist = pool.values.asSequence()
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
            if (shortlist.isEmpty()) return null

            // 3 — the deep read, chunked.
            val deep = ArrayList<NextSessionPick>()
            for (chunk in shortlist.chunked(DEEP_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (q, pre) -> async { deepRead(q, pre, held) } }.awaitAll()
                }
                results.filterNotNull().forEach { deep.add(it) }
            }
            if (deep.isEmpty()) return null

            val picks = deep.sortedByDescending { it.score }.take(PICKS)
            val alerts = picks.count { it.alert }
            val headline = when {
                alerts > 0 -> "$alerts of ${picks.size} names cleared every extreme-probability gate."
                picks.isNotEmpty() ->
                    "${picks.size} names positioned for the next session — none clears the extreme-probability bar."
                else -> "No name sets up for the next session."
            }

            NextSessionReport(
                computedAt = System.currentTimeMillis(),
                sessionNote = sessionNote(),
                picks = picks,
                headline = headline,
                notes = listOf(
                    "Scores are on a fixed 0–100 scale from measured inputs: setup strength, " +
                        "trend quality, the 35-technique board, the analog-day follow-through " +
                        "study, volume pace, and the latest extended-hours print.",
                    "Follow-through probability is counted from this stock's own past " +
                        "sessions that looked like today — fewer than $MIN_ANALOGS analogs " +
                        "and it is shown as not measured, never invented.",
                    "An alert fires only when score, measured probability, analog count, and " +
                        "board confidence ALL clear their bars. It is a statement about " +
                        "history, not a promise about tomorrow."
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ shortlist

    /** Cheap continuation case from screener fields alone; null = no case. */
    private fun preScore(q: ScreenerQuote): Double? {
        val vs50 = (q.price / q.fiftyDayAvg - 1.0) * 100.0
        if (vs50 < -1.0) return null                       // trend must be intact
        if (q.dayChangePct < 0.0 || q.dayChangePct > 12.0) return null

        val closePos = if (q.dayHigh > q.dayLow && q.dayLow > 0.0) {
            (q.price - q.dayLow) / (q.dayHigh - q.dayLow)
        } else 0.5
        if (closePos < 0.55) return null                   // fading closes carry over

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

    // ------------------------------------------------------------ deep read

    private suspend fun deepRead(
        q: ScreenerQuote,
        pre: Double,
        held: Map<String, Double>
    ): NextSessionPick? {
        return try {
            val candles = try {
                market.getDailyCandles(q.symbol, 365)
            } catch (_: Exception) {
                emptyList()
            }
            if (candles.size < 40) return null
            val closes = candles.map { it.close }
            val rsi = Indicators.rsi(closes) ?: return null
            if (rsi > 80.0) return null                    // too hot to chase tomorrow
            val atr = Indicators.atr(candles) ?: return null

            val analysis = Techniques.analyze(q.symbol, candles)
            val direction = analysis?.outlook?.direction ?: TechniqueVerdict.NEUTRAL
            // A bearish board disqualifies — this list is what to BUY.
            if (direction == TechniqueVerdict.BEARISH) return null
            val bullish = analysis?.outlook?.bullishCount ?: 0
            val techTotal = analysis?.results?.size ?: 0
            val confidence = analysis?.outlook?.confidence ?: 0

            // The analog-day study on completed sessions only.
            val closePos = if (q.dayHigh > q.dayLow && q.dayLow > 0.0) {
                (q.price - q.dayLow) / (q.dayHigh - q.dayLow) * 100.0
            } else 50.0
            val analogs = analogStudy(candles, q.dayChangePct, closePos)

            // Extended-hours print, so entries are not priced off a stale close.
            val ext = try {
                market.getExtendedHours(q.symbol)
            } catch (_: Exception) {
                null
            }
            val price = ext?.livePrice ?: q.price
            if (price <= 0.0) return null
            val extPct = ext?.preMarketPct ?: ext?.postMarketPct
            val extNote = when {
                ext?.preMarketPct != null && abs(ext.preMarketPct) >= 1.0 ->
                    String.format(Locale.US, "Pre-market %+.1f%% on the latest print.", ext.preMarketPct)
                ext?.postMarketPct != null && abs(ext.postMarketPct) >= 1.0 ->
                    String.format(Locale.US, "After-hours %+.1f%% on the latest print.", ext.postMarketPct)
                else -> ""
            }

            val volPace = if (q.avgVolume3M > 0L) q.dayVolume.toDouble() / q.avgVolume3M else 0.0
            val atrPct = atr / price * 100.0

            // ---- the fixed-scale composite ----
            val setupPts = (pre / 40.0).coerceIn(0.0, 1.0) * 25.0
            val vs50 = (q.price / q.fiftyDayAvg - 1.0) * 100.0
            val above200 = q.twoHundredDayAvg > 0.0 && q.price > q.twoHundredDayAvg
            val trendPts = ((vs50 / 10.0).coerceIn(0.0, 1.0) * 9.0) + (if (above200) 6.0 else 0.0)
            val boardPts =
                if (direction == TechniqueVerdict.BULLISH) (confidence / 100.0) * 20.0 else 6.0
            val probPts = when {
                analogs.count >= MIN_ANALOGS -> (analogs.probUp / 100.0) * 25.0
                else -> 10.0   // unmeasured: a deliberate mid value, not a reward
            }
            val extPts = extPct?.let { ((it + 2.0) / 4.0).coerceIn(0.0, 1.0) * 10.0 } ?: 5.0
            val rsiPts = (1.0 - ((rsi - 45.0).coerceAtLeast(0.0) / 35.0)).coerceIn(0.0, 1.0) * 5.0
            val score = (setupPts + trendPts + boardPts + probPts + extPts + rsiPts)
                .toInt().coerceIn(0, 100)

            // Honest next-session range from volatility capacity.
            val catalyst = ((volPace - 1.2).coerceAtLeast(0.0) * 0.8).coerceAtMost(1.5)
            var hiPct = (atrPct * 1.3 + catalyst).coerceIn(1.5, 10.0)
            val loPct = (atrPct * 0.8).coerceIn(0.8, 6.0)
            if (hiPct < loPct + 0.7) hiPct = min(10.0, loPct + 0.7)

            val entry = if (rsi >= 70.0) round2(price - atr * 0.35) else round2(price)
            val structural = analysis?.srData?.supports?.filter { it < entry }?.maxOrNull()
            val stop = round2(
                max(
                    structural?.let { min(it - 0.25 * atr, entry - 1.0 * atr) }
                        ?: (entry - 1.2 * atr),
                    entry * 0.94
                )
            )
            // Target: the measured analog average when it exists, floored at a
            // half-ATR so the trade has a reason, capped by the ATR range.
            val targetPct = when {
                analogs.count >= MIN_ANALOGS && analogs.avgNext > 0.0 ->
                    min(analogs.avgNext + atrPct * 0.5, hiPct)
                else -> min(atrPct * 1.0, hiPct)
            }.coerceAtLeast(0.8)
            val target = round2(entry * (1.0 + targetPct / 100.0))

            val alert = score >= ALERT_SCORE &&
                analogs.count >= ALERT_MIN_ANALOGS &&
                analogs.probUp >= ALERT_PROB &&
                direction == TechniqueVerdict.BULLISH &&
                confidence >= ALERT_CONFIDENCE

            val reasonParts = mutableListOf<String>()
            reasonParts += String.format(Locale.US, "%+.1f%% last session", q.dayChangePct)
            reasonParts += String.format(Locale.US, "closed at %.0f%% of the range", closePos)
            if (volPace >= 1.2) reasonParts += String.format(Locale.US, "%.1fx volume", volPace)
            if (analogs.count >= MIN_ANALOGS) {
                reasonParts += String.format(
                    Locale.US,
                    "after %d similar days it closed higher %d%% of the time (avg %+.1f%%)",
                    analogs.count, analogs.probUp, analogs.avgNext
                )
            } else {
                reasonParts += "too few similar past days to measure follow-through"
            }
            if (techTotal > 0) reasonParts += "$bullish of $techTotal techniques bullish ($confidence%)"
            reasonParts += String.format(Locale.US, "RSI %.0f", rsi)
            if (entry < price) {
                reasonParts += String.format(Locale.US, "extended — wait for a dip to $%.2f", entry)
            }

            NextSessionPick(
                symbol = q.symbol,
                name = q.name.ifEmpty { q.symbol },
                price = round2(price),
                dayChangePct = round1(q.dayChangePct),
                score = score,
                probUpPct = if (analogs.count >= MIN_ANALOGS) analogs.probUp else -1,
                analogDays = analogs.count,
                avgNextDayPct = round1(analogs.avgNext),
                entry = entry,
                target = target,
                stop = stop,
                expectedLowPct = -round1(loPct),
                expectedHighPct = round1(hiPct),
                rsi = round1(rsi),
                techBullish = bullish,
                techTotal = techTotal,
                techConfidence = confidence,
                volumeRatio = round1(volPace),
                closePosPct = round1(closePos),
                extNote = extNote,
                heldNote = held[q.symbol]?.let {
                    String.format(
                        Locale.US,
                        "Already in your book (~%s at cost) — manage the position, don't double it.",
                        com.aurum.invest.core.Fmt.money(it)
                    )
                } ?: "",
                alert = alert,
                reason = reasonParts.joinToString(", ")
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ analog study

    private class AnalogResult(val count: Int, val probUp: Int, val avgNext: Double)

    /**
     * Finds past COMPLETED sessions similar to today — same-direction daily
     * move within ±2 percentage points, close landing within ±25 points of
     * today's position in the range — and measures what the NEXT session
     * actually did.
     */
    private fun analogStudy(
        candles: List<Candle>,
        todayChangePct: Double,
        todayClosePos: Double
    ): AnalogResult {
        val lastIsToday = Dates.sameEtDay(candles.last().ts, System.currentTimeMillis())
        val complete = if (lastIsToday && candles.size >= 2) candles.dropLast(1) else candles
        val n = complete.size
        if (n < 30) return AnalogResult(0, 0, 0.0)
        val start = max(1, n - 1 - ANALOG_LOOKBACK)

        var count = 0
        var ups = 0
        var moveSum = 0.0
        for (i in start until n - 1) {   // n-1 so a next day always exists
            val prevClose = complete[i - 1].close
            if (prevClose <= 0.0) continue
            val chg = (complete[i].close / prevClose - 1.0) * 100.0
            if (todayChangePct > 0.0 && chg <= 0.0) continue
            if (todayChangePct < 0.0 && chg >= 0.0) continue
            if (abs(chg - todayChangePct) > 2.0) continue
            val range = complete[i].high - complete[i].low
            val pos = if (range > 1e-9) {
                (complete[i].close - complete[i].low) / range * 100.0
            } else 50.0
            if (abs(pos - todayClosePos) > 25.0) continue

            val nextMove = (complete[i + 1].close / complete[i].close - 1.0) * 100.0
            count++
            moveSum += nextMove
            if (nextMove > 0.0) ups++
        }
        return if (count == 0) AnalogResult(0, 0, 0.0)
        else AnalogResult(count, (ups * 100.0 / count).toInt(), moveSum / count)
    }

    // ------------------------------------------------------------ helpers

    private fun sessionNote(): String {
        val nowEt = java.time.ZonedDateTime.now(ZoneId.of("America/New_York"))
        return when (Dates.marketSessionNow()) {
            Dates.MarketSession.REGULAR -> "Built during today's session — aimed at the close and tomorrow's open."
            Dates.MarketSession.POST, Dates.MarketSession.OVERNIGHT ->
                "Built after the ${nowEt.dayOfWeek.toString().lowercase(Locale.US)
                    .replaceFirstChar { it.uppercase() }} close — aimed at the next session."
            Dates.MarketSession.PRE -> "Built pre-market — aimed at today's open."
            Dates.MarketSession.WEEKEND -> "Built over the weekend — aimed at Monday's session."
        }
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
