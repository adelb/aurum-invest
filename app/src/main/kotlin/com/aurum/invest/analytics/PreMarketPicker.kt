package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import com.aurum.invest.data.repo.NewsRepository
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** How dependably a name has delivered the requested move on a normal day. */
enum class TargetOdds { RELIABLE, FREQUENT, COINFLIP, RARE }

/**
 * One pre-market mover, measured against the user's daily profit goal.
 *
 * Every field is observed, not assumed: [hitRatePct] counts the sessions in
 * which the move was actually available intraday, and the buy/sell windows
 * come from where the day's low and high really landed over recent sessions.
 */
data class PreMarketPick(
    val date: String,
    val rank: Int,
    val symbol: String,
    val name: String,
    val price: Double,               // latest pre-market print
    val prevClose: Double,
    val preMarketPct: Double,        // move vs previous close, pre-market
    val targetPct: Double,           // the goal being tested (e.g. 2.06)
    val targetPrice: Double,         // entry * (1 + target)
    val suggestedEntry: Double,      // where the engine expects the entry to sit
    val stop: Double,                // ATR-based, under the expected entry
    // --- evidence ---------------------------------------------------------
    val hitRatePct: Double,          // % of sessions whose reach covered neededPct
    val sessionsAnalyzed: Int,
    val medianRangePct: Double,      // median (high-open)/open of those sessions
    val atrPct: Double,
    val gapHoldRatePct: Double,      // % of gap-up days that closed above the open
    val odds: TargetOdds,
    val buyWindow: String,           // when the day's low typically prints (Amman time)
    val sellWindow: String,          // when the day's high typically prints (Amman time)
    val timingSessions: Int,         // sessions behind the timing read
    /**
     * Plain-language reading of the two windows — including the case where the
     * high lands BEFORE the low, which means waiting for a dip usually misses
     * the move on this name.
     */
    val timingNote: String,
    val reason: String,
    val caution: String,             // "" when nothing needs flagging
    /** Open-to-high total the day must print to pay +target% from the entry. */
    val neededPct: Double = targetPct,
    /** Summed 5-day headline sentiment, clamped to -3..+3. */
    val newsScore: Int = 0,
    /** The most loaded recent headline, "" when none. */
    val headline: String = "",
    val headlineSource: String = "",
    val headlineSentiment: Int = 0,
    /** Tracked theme this name belongs to, "" when untracked. */
    val sectorLabel: String = "",
    /** Set when the theme ranks top-3 this week, "" otherwise. */
    val sectorNote: String = "",
    /**
     * True when [price] and [preMarketPct] come from an actual pre-market
     * print. False means the pre-market session has no trades yet (evening,
     * weekend) and the figures describe the last regular session instead —
     * the screen says which, rather than passing one off as the other.
     */
    val livePreMarket: Boolean = true,
    /** When the underlying prints were read. */
    val asOf: Long = 0L
)

/**
 * Ranks the strongest pre-market performers and answers one question per
 * name: *can this stock realistically hand me my daily target today, and
 * when should I buy it?*
 *
 * The engine offers three independent readings rather than a single guess:
 *
 *  1. **Hit rate** — over the last ~60 sessions, how often the intraday high
 *     rose at least [targetPct] above that session's open. This is the direct
 *     historical answer to "buy at the open, sell at the target".
 *  2. **Typical range** — the median (high-open)/open. If the median day
 *     cannot reach the target, the target is being asked of an unusual day.
 *  3. **Volatility capacity** — ATR as a percentage of price, the standard
 *     read on how much room a name has on an ordinary day.
 *
 * Timing comes from five sessions of 5-minute bars: the clock times where the
 * session low and high actually printed, averaged into windows.
 *
 * Never throws — symbols that fail to fetch are skipped.
 */
class PreMarketPicker(
    private val market: MarketRepository,
    private val news: NewsRepository? = null
) {

    companion object {
        /** The user's daily goal; every reading is measured against this. */
        const val DEFAULT_TARGET_PCT = 2.06

        private const val POOL_SCREENS_CHUNK = 4
        // The liquidity sort discards everything below the cut, so the net is
        // wide enough that mid-caps are not silently dropped.
        private const val SHORTLIST = 44
        private const val DEEP_CHUNK = 6
        private const val HISTORY_DAYS = 90
        private val ET: ZoneId = ZoneId.of("America/New_York")
        private val AMMAN: ZoneId = ZoneId.of("Asia/Amman")

        /** Pre-market prints move constantly — never serve them more than a minute stale. */
        private const val LIVE_MAX_AGE_MS = 60_000L

        fun toJson(picks: List<PreMarketPick>): String {
            val arr = JSONArray()
            picks.forEach { p ->
                arr.put(JSONObject().apply {
                    put("date", p.date)
                    put("rank", p.rank)
                    put("symbol", p.symbol)
                    put("name", p.name)
                    put("price", p.price)
                    put("prevClose", p.prevClose)
                    put("preMarketPct", p.preMarketPct)
                    put("targetPct", p.targetPct)
                    put("targetPrice", p.targetPrice)
                    put("suggestedEntry", p.suggestedEntry)
                    put("stop", p.stop)
                    put("hitRatePct", p.hitRatePct)
                    put("sessionsAnalyzed", p.sessionsAnalyzed)
                    put("medianRangePct", p.medianRangePct)
                    put("atrPct", p.atrPct)
                    put("gapHoldRatePct", p.gapHoldRatePct)
                    put("odds", p.odds.name)
                    put("buyWindow", p.buyWindow)
                    put("sellWindow", p.sellWindow)
                    put("timingSessions", p.timingSessions)
                    put("timingNote", p.timingNote)
                    put("reason", p.reason)
                    put("caution", p.caution)
                    put("neededPct", p.neededPct)
                    put("newsScore", p.newsScore)
                    put("headline", p.headline)
                    put("headlineSource", p.headlineSource)
                    put("headlineSentiment", p.headlineSentiment)
                    put("sectorLabel", p.sectorLabel)
                    put("sectorNote", p.sectorNote)
                    put("livePreMarket", p.livePreMarket)
                    put("asOf", p.asOf)
                })
            }
            return arr.toString()
        }

        fun fromJson(s: String): List<PreMarketPick> = try {
            val arr = JSONArray(s)
            val out = ArrayList<PreMarketPick>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tgt = o.optDouble("targetPct", DEFAULT_TARGET_PCT)
                out.add(
                    PreMarketPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        price = o.optDouble("price", 0.0),
                        prevClose = o.optDouble("prevClose", 0.0),
                        preMarketPct = o.optDouble("preMarketPct", 0.0),
                        targetPct = tgt,
                        targetPrice = o.optDouble("targetPrice", 0.0),
                        suggestedEntry = o.optDouble("suggestedEntry", 0.0),
                        stop = o.optDouble("stop", 0.0),
                        hitRatePct = o.optDouble("hitRatePct", 0.0),
                        sessionsAnalyzed = o.optInt("sessionsAnalyzed", 0),
                        medianRangePct = o.optDouble("medianRangePct", 0.0),
                        atrPct = o.optDouble("atrPct", 0.0),
                        gapHoldRatePct = o.optDouble("gapHoldRatePct", 0.0),
                        odds = runCatching { TargetOdds.valueOf(o.optString("odds")) }
                            .getOrDefault(TargetOdds.RARE),
                        buyWindow = o.optString("buyWindow", ""),
                        sellWindow = o.optString("sellWindow", ""),
                        timingSessions = o.optInt("timingSessions", 0),
                        timingNote = o.optString("timingNote", ""),
                        reason = o.optString("reason", ""),
                        caution = o.optString("caution", ""),
                        neededPct = o.optDouble("neededPct", tgt),
                        newsScore = o.optInt("newsScore", 0),
                        headline = o.optString("headline", ""),
                        headlineSource = o.optString("headlineSource", ""),
                        headlineSentiment = o.optInt("headlineSentiment", 0),
                        sectorLabel = o.optString("sectorLabel", ""),
                        sectorNote = o.optString("sectorNote", ""),
                        livePreMarket = o.optBoolean("livePreMarket", true),
                        asOf = o.optLong("asOf", 0L)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The [count] best pre-market performers that can plausibly deliver
     * [targetPct] today, ranked by the strength of that case rather than by
     * the pre-market move alone — a huge gap on a name that never holds it
     * is worse than a steady mover that reaches the target most days.
     */
    suspend fun computePicks(
        dateIso: String,
        targetPct: Double = DEFAULT_TARGET_PCT,
        count: Int = 10
    ): List<PreMarketPick> {
        return try {
            val target = targetPct.takeIf { it > 0.0 } ?: DEFAULT_TARGET_PCT

            // 1 — candidate pool from the market-wide screens.
            val pool = HashMap<String, ScreenerQuote>()
            for (chunk in EntryPicker.MARKET_SCREENS.chunked(POOL_SCREENS_CHUNK)) {
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
                    // A failed batch only narrows the pool.
                }
            }
            if (pool.isEmpty()) return emptyList()

            // 2 — liquid, sanely priced names only; a thin book cannot be
            // traded at the open at anything like the quoted price.
            val tradeable = pool.values.filter {
                it.price in 3.0..2000.0 &&
                    it.avgVolume3M >= 1_000_000L &&
                    it.price * it.avgVolume3M >= 25_000_000.0 &&
                    it.marketCap >= 300_000_000.0 &&
                    it.symbol.all { ch -> ch.isLetterOrDigit() }
            }.sortedByDescending { it.avgVolume3M }.take(SHORTLIST)
            if (tradeable.isEmpty()) return emptyList()

            // Which session is running decides both what to rank on and which
            // price is the live one. Pre-market prints are only meaningful for
            // TODAY during the pre-market window and the session that follows
            // it; in the evening they describe a morning that has already been
            // traded, so the list falls back to the last regular session.
            val session = Dates.marketSessionNow()
            val usePreMarket = session == Dates.MarketSession.PRE ||
                session == Dates.MarketSession.REGULAR

            // 3 — the actual pre-market prints. Fetched fresh (60s) because
            // these move continuously while the pre-market session is open.
            val movers = ArrayList<Triple<ScreenerQuote, Double, ExtendedHours?>>()
            if (usePreMarket) {
                for (chunk in tradeable.chunked(DEEP_CHUNK)) {
                    val results = coroutineScope {
                        chunk.map { q ->
                            async {
                                val ext = try {
                                    market.getExtendedHours(q.symbol, maxAgeMs = LIVE_MAX_AGE_MS)
                                } catch (_: Exception) {
                                    null
                                }
                                val pre = ext?.preMarketPct
                                if (pre != null && pre > 0.0) Triple(q, pre, ext) else null
                            }
                        }.awaitAll()
                    }
                    results.filterNotNull().forEach { movers.add(it) }
                }
            }
            // Before the pre-market session has prints (evening, weekend), fall
            // back to the last session's movers so the list is never empty —
            // the copy on screen says which of the two it is showing. Each pick
            // records where its OWN percentage came from (livePreMarket); how
            // fresh that is comes from the session, which the screen shows.
            val ranked: List<Triple<ScreenerQuote, Double, ExtendedHours?>> =
                if (movers.isNotEmpty()) {
                    movers.sortedByDescending { it.second }.take(count * 2)
                } else {
                    tradeable.filter { it.dayChangePct > 0.0 }
                        .sortedByDescending { it.dayChangePct }
                        .take(count * 2)
                        .map { Triple(it, it.dayChangePct, null) }
                }
            if (ranked.isEmpty()) return emptyList()

            // Which themes are hot this week — one read shared by the whole
            // scan; a failed read costs nothing but the boost.
            val newsRepo = news
            val trends = if (newsRepo != null) {
                runCatching { SectorTrends(market, newsRepo).compute() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val rankByKey = trends.withIndex().associate { (i, t) -> t.key to i }

            // 4 — measure each candidate against the goal.
            val measured = ArrayList<Pair<PreMarketPick, Double>>()
            for (chunk in ranked.chunked(DEEP_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (q, pre, ext) ->
                        async { measure(q, pre, ext, session, target, dateIso, trends, rankByKey) }
                    }.awaitAll()
                }
                results.filterNotNull().forEach { measured.add(it) }
            }
            if (measured.isEmpty()) return emptyList()

            measured.sortedByDescending { it.second }
                .take(count)
                .mapIndexed { index, (pick, _) -> pick.copy(rank = index + 1) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ measurement

    private suspend fun measure(
        q: ScreenerQuote,
        preMarketPct: Double,
        ext: ExtendedHours?,
        session: Dates.MarketSession,
        target: Double,
        dateIso: String,
        trends: List<SectorTrend>,
        rankByKey: Map<String, Int>
    ): Pair<PreMarketPick, Double>? {
        return try {
            val daily = market.getDailyCandles(q.symbol, HISTORY_DAYS)
            if (daily.size < 25) return null
            val now = System.currentTimeMillis()

            // Exclude today's in-progress bar from every historical statistic.
            val lastIsToday = Dates.sameEtDay(daily.last().ts, now)
            val history = if (lastIsToday) daily.dropLast(1) else daily
            if (history.size < 20) return null

            // News tone: summed 5-day headline sentiment. One-letter tickers
            // pull garbage feeds, so they skip the lookup entirely.
            val newsItems = if (news != null && q.symbol.length >= 2) {
                try {
                    news.getNews(q.symbol, daily)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val newsScore = newsItems.sumOf { it.sentiment }.coerceIn(-3, 3)
            val headline = newsItems.firstOrNull { it.sentiment != 0 } ?: newsItems.firstOrNull()

            // (3) Volatility capacity.
            val atr = Indicators.atr(history) ?: return null
            val lastClose = history.last().close
            if (lastClose <= 0.0) return null
            val atrPct = atr / lastClose * 100.0

            // Five sessions of 5-minute bars: the timing windows, plus today's
            // open when the daily bar has not caught up yet.
            val bars5m = try {
                market.getRangeCandles(q.symbol, "5d", "5m")
            } catch (_: Exception) {
                emptyList()
            }

            // Entry must be whatever the stock ACTUALLY trades at right now,
            // chosen by session. Before the open the screener's
            // regularMarketPrice still holds yesterday's close, and the gap to
            // the pre-market print is routinely wider than the target itself —
            // pricing off it would make every level on the card wrong. During
            // the regular session the reverse applies: this morning's
            // pre-market print is stale and the regular price is live. (The
            // movers list only exists in PRE and REGULAR, so no POST branch.)
            val entry = when (session) {
                Dates.MarketSession.PRE ->
                    ext?.preMarketPrice?.takeIf { it > 0.0 }
                        ?: q.price.takeIf { it > 0.0 }
                        ?: lastClose
                Dates.MarketSession.REGULAR ->
                    ext?.regularPrice?.takeIf { it > 0.0 }
                        ?: q.price.takeIf { it > 0.0 }
                        ?: lastClose
                else ->
                    q.price.takeIf { it > 0.0 } ?: lastClose
            }
            // Fallback prints describe the LAST session: its close is the
            // price, so the "prev" its percentage compares against is the
            // close BEFORE it — not that same close again.
            val priorClose = ext?.prevClose?.takeIf { it > 0.0 } ?: when (session) {
                Dates.MarketSession.REGULAR -> lastClose
                else -> history.getOrNull(history.size - 2)?.close?.takeIf { it > 0.0 }
                    ?: lastClose
            }

            // The odds must answer the card's actual question: what
            // open-to-high total does the day need for a buyer at ENTRY to
            // make the target? During the regular session the entry sits above
            // the open, so the day must stretch further than the target alone.
            val todayOpen = when {
                lastIsToday && daily.last().open > 0.0 -> daily.last().open
                else -> bars5m.firstOrNull { Dates.sameEtDay(it.ts, now) }
                    ?.open?.takeIf { it > 0.0 }
            }
            val needed = if (session == Dates.MarketSession.REGULAR && todayOpen != null) {
                ((entry * (1.0 + target / 100.0)) / todayOpen - 1.0) * 100.0
            } else {
                target
            }

            // (1) Hit rate — counted on the sample that looks like today. A
            // gapping candidate is judged on its gap-up days when enough
            // exist; the all-days rate answers a different kind of morning.
            val allReaches = history.mapNotNull { c ->
                if (c.open <= 0.0) null else (c.high - c.open) / c.open * 100.0
            }
            if (allReaches.isEmpty()) return null
            val gapReaches = if (preMarketPct >= 1.0) {
                history.indices.drop(1).mapNotNull { i ->
                    val prev = history[i - 1].close
                    val c = history[i]
                    if (prev <= 0.0 || c.open <= prev * 1.003) null
                    else (c.high - c.open) / c.open * 100.0
                }
            } else {
                emptyList()
            }
            val gapConditioned = gapReaches.size >= 12
            val reaches = if (gapConditioned) gapReaches else allReaches
            val hits = reaches.count { it >= needed }
            val hitRate = hits * 100.0 / reaches.size

            // (2) Typical day: the median open-to-high reach of that sample.
            val medianRange = reaches.sorted().let { s ->
                if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            }

            // Gap behaviour: on days that opened up, did the move hold into the close?
            val gapDays = history.indices.drop(1).mapNotNull { i ->
                val prev = history[i - 1].close
                val c = history[i]
                if (prev <= 0.0 || c.open <= 0.0) return@mapNotNull null
                if ((c.open - prev) / prev * 100.0 <= 0.3) null else (c.close >= c.open)
            }
            val gapHold = if (gapDays.isEmpty()) 0.0 else gapDays.count { it } * 100.0 / gapDays.size

            val odds = when {
                hitRate >= 70.0 -> TargetOdds.RELIABLE
                hitRate >= 55.0 -> TargetOdds.FREQUENT
                hitRate >= 40.0 -> TargetOdds.COINFLIP
                else -> TargetOdds.RARE
            }

            // Timing from five sessions of 5-minute bars, finished days only.
            val timing = timingFor(bars5m, session)

            val targetPrice = entry * (1.0 + target / 100.0)
            // ATR-based stop, floored so a wide ATR can never park the stop
            // more than 10% under the entry (or below zero on a cheap name).
            val stop = (entry - atr * 0.6).coerceAtLeast(entry * 0.90)
            val stopDistancePct = (entry - stop) / entry * 100.0

            // Trending-theme read: a free lookup over the tracked watch names.
            // Untracked symbols get no boost rather than a network call — this
            // desk rescans every two minutes.
            val theme = SectorTrends.SYMBOL_THEME[q.symbol]
            val sectorLabel = theme?.second ?: ""
            val themeRank = theme?.let { rankByKey[it.first] }
            val sectorBoost = when (themeRank) {
                0 -> 6.0
                1 -> 4.0
                2 -> 2.0
                else -> 0.0
            }
            val sectorNote = if (themeRank != null && themeRank < 3) {
                String.format(
                    Locale.US,
                    "%s is this week's #%d theme (%+.1f%% in 5 days)",
                    sectorLabel, themeRank + 1, trends[themeRank].r5Pct
                )
            } else {
                ""
            }

            val caution = buildString {
                // Measured against needed, not target: during the regular
                // session the day must stretch past the entry's premium too.
                if (medianRange < needed) {
                    append(
                        String.format(
                            Locale.US,
                            "A typical day only reaches %.2f%% above the open — %.2f%% asks for " +
                                "a better-than-usual session. ",
                            medianRange, needed
                        )
                    )
                }
                if (preMarketPct >= 8.0) {
                    append(
                        "Already up sharply pre-market; gap-ups this size often give back " +
                            "some of the move after the open. "
                    )
                }
                if (gapHold < 45.0 && gapDays.isNotEmpty()) {
                    append(
                        String.format(
                            Locale.US,
                            "Only %.0f%% of its gap-ups closed above the open. ",
                            gapHold
                        )
                    )
                }
                if (stopDistancePct > target) {
                    append(
                        String.format(
                            Locale.US,
                            "The stop risks %.1f%% against a %.1f%% goal — size the position " +
                                "for the stop, not the target. ",
                            stopDistancePct, target
                        )
                    )
                }
                if (newsScore <= -2) {
                    append(
                        "Negative news tone this week — read the headline before trusting " +
                            "the move."
                    )
                }
            }.trim()

            val reason = if (gapConditioned) {
                String.format(
                    Locale.US,
                    "Reached %.2f%%+ above the open on %d of %d gap-up days like today " +
                        "(%.0f%%). Median such day runs %.2f%%, ATR %.1f%%.",
                    needed, hits, reaches.size, hitRate, medianRange, atrPct
                )
            } else {
                String.format(
                    Locale.US,
                    "Reached %.2f%%+ above the open on %d of %d sessions (%.0f%%). " +
                        "Median day runs %.2f%%, ATR %.1f%%.",
                    needed, hits, reaches.size, hitRate, medianRange, atrPct
                )
            }

            // Rank: the strength of the case for the target, not the gap size.
            // Hit rate dominates; news tone and a trending theme tilt it; the
            // pre-market move only breaks ties.
            val score = hitRate * 1.0 +
                (medianRange / needed * 20.0).coerceAtMost(25.0) +
                (gapHold * 0.15) +
                (preMarketPct.coerceAtMost(10.0) * 0.8) +
                newsScore * 2.5 +
                sectorBoost

            val pick = PreMarketPick(
                date = dateIso,
                rank = 0,
                symbol = q.symbol,
                name = q.name.ifEmpty { q.symbol },
                price = round2(entry),
                prevClose = round2(priorClose),
                preMarketPct = round2(preMarketPct),
                targetPct = target,
                targetPrice = round2(targetPrice),
                suggestedEntry = round2(entry),
                stop = round2(stop),
                hitRatePct = round1(hitRate),
                sessionsAnalyzed = reaches.size,
                medianRangePct = round2(medianRange),
                atrPct = round1(atrPct),
                gapHoldRatePct = round1(gapHold),
                odds = odds,
                buyWindow = timing.buyWindow,
                sellWindow = timing.sellWindow,
                timingSessions = timing.sessions,
                timingNote = timing.note,
                reason = reason,
                caution = caution,
                neededPct = round2(needed),
                newsScore = newsScore,
                headline = headline?.title ?: "",
                headlineSource = headline?.source ?: "",
                headlineSentiment = headline?.sentiment ?: 0,
                sectorLabel = sectorLabel,
                sectorNote = sectorNote,
                // Live only when THIS pick's numbers came from an actual
                // extended-hours print; fallback-ranked picks get false.
                livePreMarket = ext != null,
                asOf = now
            )
            pick to score
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Where the session low and high actually printed over the last five
     * sessions of [bars] (5-minute). The math runs in ET (the exchange's
     * clock); the windows are DISPLAYED in Amman time, the user's own. The
     * in-progress session is excluded during regular hours — its true low
     * and high have not printed yet, and counting it drags the windows
     * early. Returns ("", "", 0) when intraday history is unavailable — the
     * UI then says so instead of guessing.
     */
    private fun timingFor(bars: List<Candle>, session: Dates.MarketSession): Timing {
        return try {
            if (bars.size < 30) return Timing.EMPTY
            val now = System.currentTimeMillis()
            // Group by ET calendar day, regular session only (09:30–16:00).
            val byDay = LinkedHashMap<String, MutableList<Candle>>()
            for (c in bars) {
                if (session == Dates.MarketSession.REGULAR && Dates.sameEtDay(c.ts, now)) continue
                val et = Instant.ofEpochMilli(c.ts).atZone(ET)
                val minutes = et.hour * 60 + et.minute
                if (minutes < 9 * 60 + 30 || minutes >= 16 * 60) continue
                byDay.getOrPut(et.toLocalDate().toString()) { ArrayList() }.add(c)
            }
            val days = byDay.values.filter { it.size >= 20 }
            if (days.isEmpty()) return Timing.EMPTY

            val lowMinutes = ArrayList<Int>()
            val highMinutes = ArrayList<Int>()
            // How often the high arrives before the low — on those names the
            // strength comes early and "waiting for the dip" misses the trade.
            var highFirst = 0
            for (day in days) {
                val low = day.minByOrNull { it.low } ?: continue
                val high = day.maxByOrNull { it.high } ?: continue
                val lowMin = minuteOfDayEt(low.ts)
                val highMin = minuteOfDayEt(high.ts)
                lowMinutes.add(lowMin)
                highMinutes.add(highMin)
                if (highMin < lowMin) highFirst++
            }
            if (lowMinutes.isEmpty() || highMinutes.isEmpty()) return Timing.EMPTY

            val lowMedian = median(lowMinutes)
            val highMedian = median(highMinutes)
            val earlyStrength = highMedian <= lowMedian || highFirst * 2 > days.size
            val note = if (earlyStrength) {
                "The high usually lands before the low on this name — its move tends to come " +
                    "right after the open, so waiting for a dip often misses it. Buy into the " +
                    "open and take the target when it prints."
            } else {
                "It typically dips first and peaks later, so the buy window below is the one " +
                    "to wait for."
            }
            Timing(
                // When the strength comes early, a dip-buy clock time would
                // contradict the note right under it — say so instead.
                buyWindow = if (earlyStrength) "At the open" else windowAround(lowMedian),
                sellWindow = windowAround(highMedian),
                sessions = days.size,
                note = note
            )
        } catch (_: Exception) {
            Timing.EMPTY
        }
    }

    /** Intraday timing read for one symbol. */
    private data class Timing(
        val buyWindow: String,
        val sellWindow: String,
        val sessions: Int,
        val note: String
    ) {
        companion object {
            val EMPTY = Timing("", "", 0, "")
        }
    }

    private fun minuteOfDayEt(ts: Long): Int {
        val et = Instant.ofEpochMilli(ts).atZone(ET)
        return et.hour * 60 + et.minute
    }

    private fun median(values: List<Int>): Int {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * A ±15-minute window around [minute] (an ET minute-of-day), clamped to
     * the regular session and rendered as Amman wall-clock time. The zone is
     * named once by the card that shows these windows, not on every row.
     */
    private fun windowAround(minute: Int): String {
        val start = (minute - 15).coerceAtLeast(9 * 60 + 30)
        val end = (minute + 15).coerceAtMost(16 * 60)
        return "${ammanClock(start)}–${ammanClock(end)}"
    }

    /** Today's ET minute-of-day as an Amman wall-clock label, e.g. "4:45 PM". */
    private fun ammanClock(minutesEt: Int): String {
        val et = java.time.ZonedDateTime.now(ET)
            .withHour(minutesEt / 60).withMinute(minutesEt % 60)
            .withSecond(0).withNano(0)
        return et.withZoneSameInstant(AMMAN)
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
