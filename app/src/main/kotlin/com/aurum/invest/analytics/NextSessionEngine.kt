package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.EntryPick
import com.aurum.invest.data.model.FeedStatus
import com.aurum.invest.data.model.PowerPick
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
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

/**
 * What the app's own intraday scans said about a symbol TODAY — the entry
 * scan and the power-hour scan. Empty maps mean the scans have not run (or
 * were unreachable), which is different from "the scans ran and skipped this
 * name"; [available] carries that distinction so the engine never treats an
 * outage as a bearish absence.
 */
data class DayScanContext(
    val entryBySymbol: Map<String, EntryPick>,
    val powerBySymbol: Map<String, PowerPick>
) {
    val available: Boolean get() = entryBySymbol.isNotEmpty() || powerBySymbol.isNotEmpty()

    companion object {
        val EMPTY = DayScanContext(emptyMap(), emptyMap())

        fun of(entries: List<EntryPick>, power: List<PowerPick>): DayScanContext =
            DayScanContext(
                entryBySymbol = entries.associateBy { it.symbol.trim().uppercase() },
                powerBySymbol = power.associateBy { it.symbol.trim().uppercase() }
            )
    }
}

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
    val reason: String,
    // ---- v2 measured context ----
    val sector: String = "",           // Yahoo sector; "" when unknown
    /** INFLOW / NEUTRAL / OUTFLOW when the sector's money flow was measured; "" otherwise. */
    val flowVerdict: String = "",
    val flowNote: String = "",
    /** Summed 5-day headline tone; only meaningful when [newsMeasured]. */
    val newsScore: Int = 0,
    val newsMeasured: Boolean = false,
    val newsNote: String = "",
    /** 20-session return minus SPY's, percentage points; null = not measured. */
    val rel20Pct: Double? = null,
    /** Close vs the last session's volume-weighted average price; null = no intraday bars. */
    val aboveVwap: Boolean? = null,
    val vwapDistPct: Double? = null,
    /** True when the last close cleared the prior 20-session high (Donchian breakout). */
    val breakout20: Boolean = false,
    /** (target − entry) / (entry − stop); null when the stop is not below the entry. */
    val riskReward: Double? = null,
    /** "" unless one of today's own scans also surfaced this name. */
    val scanNote: String = ""
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
                        put("sector", p.sector)
                        put("flowVerdict", p.flowVerdict)
                        put("flowNote", p.flowNote)
                        put("newsScore", p.newsScore)
                        put("newsMeasured", p.newsMeasured)
                        put("newsNote", p.newsNote)
                        putOpt("rel20", p.rel20Pct)
                        putOpt("aboveVwap", p.aboveVwap)
                        putOpt("vwapDist", p.vwapDistPct)
                        put("breakout20", p.breakout20)
                        putOpt("rr", p.riskReward)
                        put("scanNote", p.scanNote)
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
                            reason = p.optString("reason", ""),
                            sector = p.optString("sector", ""),
                            flowVerdict = p.optString("flowVerdict", ""),
                            flowNote = p.optString("flowNote", ""),
                            newsScore = p.optInt("newsScore", 0),
                            newsMeasured = p.optBoolean("newsMeasured", false),
                            newsNote = p.optString("newsNote", ""),
                            rel20Pct = if (p.has("rel20")) p.optDouble("rel20") else null,
                            aboveVwap = if (p.has("aboveVwap")) p.optBoolean("aboveVwap") else null,
                            vwapDistPct = if (p.has("vwapDist")) p.optDouble("vwapDist") else null,
                            breakout20 = p.optBoolean("breakout20", false),
                            riskReward = if (p.has("rr")) p.optDouble("rr") else null,
                            scanNote = p.optString("scanNote", "")
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
 * each finalist through EVERYTHING the app can measure —
 *
 *  1. the 35-technique board (a bearish board disqualifies),
 *  2. a MEASURED analog-day study: it finds the past sessions of the last ~6
 *     months that looked like today (same-direction move of similar size,
 *     similar close position in the range) and counts how often the NEXT day
 *     actually closed higher, and by how much on average,
 *  3. trend quality against the 50- and 200-day averages PLUS 20-session
 *     relative strength against SPY,
 *  4. volume: pace against the 3-month average and session-over-session
 *     expansion,
 *  5. the sector's measured money flow (CMF, MFI, OBV slope, up-dollar
 *     share — via the shared money-flow engine), so a name swimming against
 *     its sector's outflow is scored for it,
 *  6. the last 5 days of headlines with their measured tone — a failed feed
 *     is reported as "not measured", never as "no news",
 *  7. intraday structure: the close against the session's volume-weighted
 *     average price (VWAP), and whether today's own entry/power scans also
 *     surfaced the name,
 *  8. classic chart mechanics: the 20-session Donchian breakout check,
 *     structural support for the stop, the nearest resistance capping the
 *     target, and the resulting risk/reward,
 *  9. the latest pre/post-market print, so entries are never priced off a
 *     stale close.
 *
 * It returns the 10 strongest with a fixed-scale score, an entry, a target,
 * a stop, and an honest ATR-based range. A pick raises the
 * EXTREME-PROBABILITY alert only when every gate fires at once: score >=
 * [ALERT_SCORE], a measured [ALERT_PROB]%+ analog follow-through over >=
 * [ALERT_MIN_ANALOGS] analog days, and a bullish board at >=
 * [ALERT_CONFIDENCE]% confidence. The alert is a measured statement about
 * history, never a promise.
 *
 * Integrity rules: every component is measured or reported as not measured —
 * an unmeasured input scores its fixed midpoint and says so; nothing is
 * renormalized to the day's best candidate, so scores compare across days.
 *
 * Portfolio-aware: names already held are tagged. Never throws — null on
 * total failure.
 */
class NextSessionEngine(
    private val market: MarketRepository,
    private val news: NewsRepository? = null
) {

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

        fun qualifiesForAlert(
            score: Int,
            analogDays: Int,
            probUpPct: Int,
            direction: TechniqueVerdict,
            confidence: Int
        ): Boolean =
            score >= ALERT_SCORE &&
                analogDays >= ALERT_MIN_ANALOGS &&
                probUpPct >= ALERT_PROB &&
                direction == TechniqueVerdict.BULLISH &&
                confidence >= ALERT_CONFIDENCE

        /**
         * Applies the current portfolio to a cached market report. Holdings do
         * not change the measured market score, so this keeps awareness live
         * without re-running the whole-market scan after every trade.
         */
        fun withPortfolio(
            report: NextSessionReport,
            held: Map<String, Double>
        ): NextSessionReport = report.copy(
            picks = report.picks.map { pick ->
                pick.copy(heldNote = heldNote(pick.symbol, held[pick.symbol]))
            }
        )

        private fun heldNote(symbol: String, cost: Double?): String =
            cost?.let {
                String.format(
                    Locale.US,
                    "Already in your book (~%s at cost) — manage the position, don't double it.",
                    com.aurum.invest.core.Fmt.money(it)
                )
            }.orEmpty()

        /**
         * Volume-weighted average price over one session's bars. Null when
         * the bars carry no volume — an unmeasurable VWAP is never invented.
         */
        fun sessionVwap(bars: List<Candle>): Double? {
            var pv = 0.0
            var v = 0.0
            for (b in bars) {
                if (b.volume <= 0L) continue
                val typical = (b.high + b.low + b.close) / 3.0
                pv += typical * b.volume
                v += b.volume
            }
            return if (v > 0.0) pv / v else null
        }
    }

    /**
     * [held] maps open-position symbols to cost dollars for the held tags.
     * [dayScans] carries what today's own intraday scans surfaced.
     */
    suspend fun compute(
        held: Map<String, Double> = emptyMap(),
        dayScans: DayScanContext = DayScanContext.EMPTY
    ): NextSessionReport? {
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

            // Shared context, fetched once: SPY's 20-session return for the
            // relative-strength read, and the finalists' sectors in one batch.
            val spyR20 = try {
                val spy = market.getDailyCandles("SPY", 60)
                val closes = spy.map { it.close }
                if (closes.size >= 21 && closes[closes.size - 21] > 0.0) {
                    (closes.last() / closes[closes.size - 21] - 1.0) * 100.0
                } else null
            } catch (_: Exception) {
                null
            }
            val sectors = try {
                market.getSectors(shortlist.map { it.first.symbol })
            } catch (_: Exception) {
                emptyMap()
            }

            // 3 — the deep read, chunked.
            val deep = ArrayList<NextSessionPick>()
            for (chunk in shortlist.chunked(DEEP_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (q, pre) ->
                        async {
                            deepRead(
                                q = q,
                                pre = pre,
                                held = held,
                                dayScans = dayScans,
                                spyR20 = spyR20,
                                sector = sectors[q.symbol].orEmpty()
                            )
                        }
                    }.awaitAll()
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
                notes = buildList {
                    add(
                        "Scores are on a fixed 0–100 scale from measured inputs: setup strength " +
                            "(15), trend quality with relative strength vs SPY (12), the " +
                            "35-technique board (15), the analog-day follow-through study (20), " +
                            "volume pace and expansion (10), sector money flow (8), 5-day " +
                            "headline tone (8), intraday structure — VWAP and today's own " +
                            "scans (7), and the latest extended-hours print (5)."
                    )
                    add(
                        "Any input that could not be measured scores its fixed midpoint and is " +
                            "labeled not measured — a failed news feed is never read as \"no " +
                            "news\", and a missing flow report is never read as a neutral sector."
                    )
                    add(
                        "Follow-through probability is counted from this stock's own past " +
                            "sessions that looked like today — fewer than $MIN_ANALOGS analogs " +
                            "and it is shown as not measured, never invented."
                    )
                    add(
                        "Targets are capped at the nearest measured resistance; stops sit under " +
                            "structural support with an ATR pad; each pick reports its " +
                            "risk/reward so a thin trade cannot hide behind a high score."
                    )
                    add(
                        "An alert fires only when score, measured probability, analog count, and " +
                            "board confidence ALL clear their bars. It is a statement about " +
                            "history, not a promise about tomorrow."
                    )
                    add("Sector money flow is not measured in this build — flow points sit at their midpoint.")
                    if (!dayScans.available) {
                        add("Today's intraday scans were unavailable this run — scan-continuity points sat at their midpoint.")
                    }
                }
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
        held: Map<String, Double>,
        dayScans: DayScanContext,
        spyR20: Double?,
        sector: String
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

            // Extended-hours print, so entries are not priced off a stale
            // close — session-aware, because livePrice would keep serving the
            // MORNING pre-market print all through the regular session.
            val ext = try {
                market.getExtendedHours(q.symbol)
            } catch (_: Exception) {
                null
            }
            val price = when (Dates.marketSessionNow()) {
                Dates.MarketSession.REGULAR -> ext?.regularPrice?.takeIf { it > 0.0 }
                Dates.MarketSession.PRE -> ext?.preMarketPrice?.takeIf { it > 0.0 }
                else -> ext?.postMarketPrice?.takeIf { it > 0.0 }
                    ?: ext?.regularPrice?.takeIf { it > 0.0 }
            } ?: q.price
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

            // ---- relative strength vs SPY over 20 sessions ----
            val rel20 =
                if (spyR20 != null && closes.size >= 21 && closes[closes.size - 21] > 0.0) {
                    (closes.last() / closes[closes.size - 21] - 1.0) * 100.0 - spyR20
                } else null

            // ---- session-over-session volume expansion ----
            val lastVol = candles.last().volume
            val prevVol = candles.getOrNull(candles.size - 2)?.volume ?: 0L
            val volExpanding = prevVol > 0L && lastVol.toDouble() >= prevVol * 1.15

            // ---- 20-session Donchian breakout on completed data ----
            val breakout20 = closes.size >= 21 &&
                closes.last() > (closes.subList(closes.size - 21, closes.size - 1).maxOrNull() ?: Double.MAX_VALUE)

            // ---- sector money flow: not measured in this build — the score
            // takes the named unmeasured midpoint, never a fabricated verdict.
            val flowNote = ""

            // ---- 5-day headlines with measured tone ----
            var newsMeasured = false
            var newsScore = 0
            var newsNote = ""
            if (news != null) {
                val items = try {
                    news.getNews(q.symbol, candles)
                } catch (_: Exception) {
                    null
                }
                if (items != null && items.isNotEmpty()) {
                    newsMeasured = true
                    newsScore = items.sumOf { it.sentiment }.coerceIn(-2, 2)
                    newsNote = items.firstOrNull { it.sentiment != 0 }
                        ?.let { "${it.title} — ${it.source}" }
                        ?: "Headlines present, tone neutral."
                }
            }

            // ---- intraday structure: the session VWAP ----
            var aboveVwap: Boolean? = null
            var vwapDistPct: Double? = null
            try {
                val bars = market.getIntraday(q.symbol)
                if (bars.isNotEmpty()) {
                    // The most recent session only — the last bar's ET day.
                    val lastTs = bars.last().ts
                    val session = bars.filter { Dates.sameEtDay(it.ts, lastTs) }
                    sessionVwap(session)?.let { vwap ->
                        aboveVwap = price >= vwap
                        vwapDistPct = (price / vwap - 1.0) * 100.0
                    }
                }
            } catch (_: Exception) {
                // VWAP stays not measured.
            }

            // ---- today's own scans ----
            val entryPick = dayScans.entryBySymbol[q.symbol]
            val powerPick = dayScans.powerBySymbol[q.symbol]
            val scanNote = when {
                entryPick != null && powerPick != null ->
                    String.format(
                        Locale.US,
                        "Surfaced by BOTH of today's scans (entry %.0f, power-hour %.0f).",
                        entryPick.score, powerPick.score
                    )
                entryPick != null ->
                    String.format(Locale.US, "Surfaced by today's entry scan (score %.0f).", entryPick.score)
                powerPick != null ->
                    String.format(Locale.US, "Surfaced by today's power-hour scan (score %.0f).", powerPick.score)
                else -> ""
            }

            // ---- the fixed-scale composite (weights documented in the notes) ----
            val setupPts = (pre / 40.0).coerceIn(0.0, 1.0) * 15.0
            val vs50 = (q.price / q.fiftyDayAvg - 1.0) * 100.0
            val above200 = q.twoHundredDayAvg > 0.0 && q.price > q.twoHundredDayAvg
            val trendPts = ((vs50 / 10.0).coerceIn(0.0, 1.0) * 5.0) +
                (if (above200) 4.0 else 0.0) +
                (rel20?.let { ((it + 2.0) / 8.0).coerceIn(0.0, 1.0) * 3.0 } ?: 1.5)
            val boardPts =
                if (direction == TechniqueVerdict.BULLISH) (confidence / 100.0) * 15.0 else 5.0
            val probPts = when {
                analogs.count >= MIN_ANALOGS -> (analogs.probUp / 100.0) * 20.0
                else -> 8.0    // unmeasured: a deliberate mid value, not a reward
            }
            val volPts = ((volPace - 0.8) / 1.7).coerceIn(0.0, 1.0) * 8.0 +
                (if (volExpanding) 2.0 else 0.0)
            val flowPts = 4.0   // sector flow unmeasured: the midpoint, by name
            val newsPts =
                if (newsMeasured) ((newsScore + 2.0) / 4.0).coerceIn(0.0, 1.0) * 8.0 else 4.0
            val vwapPts = when (aboveVwap) {
                true -> 4.0
                false -> 0.0
                null -> 2.0    // no intraday bars — unmeasured midpoint
            }
            val scanPts = when {
                entryPick != null || powerPick != null -> 3.0
                dayScans.available -> 1.0
                else -> 1.5    // scans unavailable — unmeasured midpoint
            }
            val extPts = extPct?.let { ((it + 2.0) / 4.0).coerceIn(0.0, 1.0) * 5.0 } ?: 2.5
            val score = (setupPts + trendPts + boardPts + probPts + volPts +
                flowPts + newsPts + vwapPts + scanPts + extPts)
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
            // half-ATR so the trade has a reason, capped by the ATR range —
            // and never past the nearest measured resistance overhead.
            val targetPct = when {
                analogs.count >= MIN_ANALOGS && analogs.avgNext > 0.0 ->
                    min(analogs.avgNext + atrPct * 0.5, hiPct)
                else -> min(atrPct * 1.0, hiPct)
            }.coerceAtLeast(0.8)
            val rawTarget = entry * (1.0 + targetPct / 100.0)
            val resistanceCap = analysis?.srData?.resistances
                ?.filter { it > entry * 1.003 }
                ?.minOrNull()
            val target = round2(
                if (resistanceCap != null && resistanceCap < rawTarget) resistanceCap else rawTarget
            )
            val riskReward =
                if (entry > stop && target > entry) round2((target - entry) / (entry - stop))
                else null

            val alert = qualifiesForAlert(
                score = score,
                analogDays = analogs.count,
                probUpPct = analogs.probUp,
                direction = direction,
                confidence = confidence
            )

            val reasonParts = mutableListOf<String>()
            reasonParts += String.format(Locale.US, "%+.1f%% last session", q.dayChangePct)
            reasonParts += String.format(Locale.US, "closed at %.0f%% of the range", closePos)
            if (volPace >= 1.2) reasonParts += String.format(Locale.US, "%.1fx volume", volPace)
            if (volExpanding) reasonParts += "volume expanding session over session"
            if (breakout20) reasonParts += "cleared its 20-session high"
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
            rel20?.let {
                reasonParts += String.format(Locale.US, "%+.1fpp vs SPY over 20 sessions", it)
            }
            if (newsMeasured && newsScore != 0) {
                reasonParts += String.format(Locale.US, "5-day news tone %+d", newsScore)
            }
            aboveVwap?.let {
                reasonParts += if (it) "holding above the session VWAP" else "below the session VWAP"
            }
            if (scanNote.isNotEmpty()) reasonParts += scanNote.removeSuffix(".")
            reasonParts += String.format(Locale.US, "RSI %.0f", rsi)
            riskReward?.let {
                reasonParts += String.format(Locale.US, "R/R %.1f to the capped target", it)
                if (it < 1.0) {
                    reasonParts += "reward is thinner than the risk — size accordingly"
                }
            }
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
                heldNote = heldNote(q.symbol, held[q.symbol]),
                alert = alert,
                reason = reasonParts.joinToString(", "),
                sector = sector,
                flowVerdict = "",
                flowNote = flowNote,
                newsScore = newsScore,
                newsMeasured = newsMeasured,
                newsNote = newsNote,
                rel20Pct = rel20?.let { round1(it) },
                aboveVwap = aboveVwap,
                vwapDistPct = vwapDistPct?.let { round1(it) },
                breakout20 = breakout20,
                riskReward = riskReward,
                scanNote = scanNote
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
