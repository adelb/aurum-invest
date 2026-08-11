package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ExtendedHours
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
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
    val hitRatePct: Double,          // % of sessions where high >= open*(1+target)
    val sessionsAnalyzed: Int,
    val medianRangePct: Double,      // median (high-open)/open of those sessions
    val atrPct: Double,
    val gapHoldRatePct: Double,      // % of gap-up days that closed above the open
    val odds: TargetOdds,
    val buyWindowEt: String,         // when the day's low typically prints
    val sellWindowEt: String,        // when the day's high typically prints
    val timingSessions: Int,         // sessions behind the timing read
    /**
     * Plain-language reading of the two windows — including the case where the
     * high lands BEFORE the low, which means waiting for a dip usually misses
     * the move on this name.
     */
    val timingNote: String,
    val reason: String,
    val caution: String,             // "" when nothing needs flagging
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
class PreMarketPicker(private val market: MarketRepository) {

    companion object {
        /** The user's daily goal; every reading is measured against this. */
        const val DEFAULT_TARGET_PCT = 2.06

        private const val POOL_SCREENS_CHUNK = 4
        private const val SHORTLIST = 34
        private const val DEEP_CHUNK = 6
        private const val HISTORY_DAYS = 90
        private val ET: ZoneId = ZoneId.of("America/New_York")

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
                    put("buyWindowEt", p.buyWindowEt)
                    put("sellWindowEt", p.sellWindowEt)
                    put("timingSessions", p.timingSessions)
                    put("timingNote", p.timingNote)
                    put("reason", p.reason)
                    put("caution", p.caution)
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
                out.add(
                    PreMarketPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        price = o.optDouble("price", 0.0),
                        prevClose = o.optDouble("prevClose", 0.0),
                        preMarketPct = o.optDouble("preMarketPct", 0.0),
                        targetPct = o.optDouble("targetPct", DEFAULT_TARGET_PCT),
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
                        buyWindowEt = o.optString("buyWindowEt", ""),
                        sellWindowEt = o.optString("sellWindowEt", ""),
                        timingSessions = o.optInt("timingSessions", 0),
                        timingNote = o.optString("timingNote", ""),
                        reason = o.optString("reason", ""),
                        caution = o.optString("caution", ""),
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
            // the copy on screen says which of the two it is showing. The flag
            // records where the PERCENTAGE came from; how fresh that is comes
            // from the session, which the screen shows separately.
            val live = movers.isNotEmpty()
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

            // 4 — measure each candidate against the goal.
            val measured = ArrayList<Pair<PreMarketPick, Double>>()
            for (chunk in ranked.chunked(DEEP_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { (q, pre, ext) ->
                        async { measure(q, pre, ext, live, session, target, dateIso) }
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
        live: Boolean,
        session: Dates.MarketSession,
        target: Double,
        dateIso: String
    ): Pair<PreMarketPick, Double>? {
        return try {
            val daily = market.getDailyCandles(q.symbol, HISTORY_DAYS)
            if (daily.size < 25) return null

            // Exclude today's in-progress bar from every historical statistic.
            val lastIsToday = Dates.sameDay(daily.last().ts, System.currentTimeMillis())
            val history = if (lastIsToday) daily.dropLast(1) else daily
            if (history.size < 20) return null

            // (1) Hit rate: how often the target was actually available from the open.
            val reaches = history.mapNotNull { c ->
                if (c.open <= 0.0) null else (c.high - c.open) / c.open * 100.0
            }
            if (reaches.isEmpty()) return null
            val hits = reaches.count { it >= target }
            val hitRate = hits * 100.0 / reaches.size

            // (2) Typical day: the median open-to-high reach.
            val medianRange = reaches.sorted().let { s ->
                if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            }

            // (3) Volatility capacity.
            val atr = Indicators.atr(history) ?: return null
            val lastClose = history.last().close
            if (lastClose <= 0.0) return null
            val atrPct = atr / lastClose * 100.0

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

            // Timing from five sessions of 5-minute bars.
            val timing = timingFor(q.symbol)

            // Entry must be whatever the stock ACTUALLY trades at right now,
            // chosen by session. Before the open the screener's
            // regularMarketPrice still holds yesterday's close, and the gap to
            // the pre-market print is routinely wider than the target itself —
            // pricing off it would make every level on the card wrong. During
            // the regular session the reverse applies: this morning's
            // pre-market print is stale and the regular price is live.
            val entry = when (session) {
                Dates.MarketSession.PRE ->
                    ext?.preMarketPrice?.takeIf { it > 0.0 }
                        ?: q.price.takeIf { it > 0.0 }
                        ?: lastClose
                Dates.MarketSession.REGULAR ->
                    ext?.regularPrice?.takeIf { it > 0.0 }
                        ?: q.price.takeIf { it > 0.0 }
                        ?: lastClose
                Dates.MarketSession.POST ->
                    ext?.postMarketPrice?.takeIf { it > 0.0 }
                        ?: ext?.regularPrice?.takeIf { it > 0.0 }
                        ?: q.price.takeIf { it > 0.0 }
                        ?: lastClose
                else ->
                    q.price.takeIf { it > 0.0 } ?: lastClose
            }
            val priorClose = ext?.prevClose?.takeIf { it > 0.0 } ?: lastClose
            val targetPrice = entry * (1.0 + target / 100.0)
            val stop = entry - atr * 0.6

            val caution = buildString {
                if (medianRange < target) {
                    append(
                        String.format(
                            Locale.US,
                            "A typical day only reaches %.2f%% above the open — %.2f%% asks for " +
                                "a better-than-usual session. ",
                            medianRange, target
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
                            "Only %.0f%% of its gap-ups closed above the open.",
                            gapHold
                        )
                    )
                }
            }.trim()

            val reason = String.format(
                Locale.US,
                "Reached %.2f%%+ above the open on %d of %d sessions (%.0f%%). " +
                    "Median day runs %.2f%%, ATR %.1f%%.",
                target, hits, reaches.size, hitRate, medianRange, atrPct
            )

            // Rank: the strength of the case for the target, not the gap size.
            // Hit rate dominates; the pre-market move only breaks ties.
            val score = hitRate * 1.0 +
                (medianRange / target * 20.0).coerceAtMost(25.0) +
                (gapHold * 0.15) +
                (preMarketPct.coerceAtMost(10.0) * 0.8)

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
                buyWindowEt = timing.buyWindow,
                sellWindowEt = timing.sellWindow,
                timingSessions = timing.sessions,
                timingNote = timing.note,
                reason = reason,
                caution = caution,
                livePreMarket = live,
                asOf = System.currentTimeMillis()
            )
            pick to score
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Where the session low and high actually printed over the last five
     * sessions, as ET clock windows. Returns ("", "", 0) when intraday
     * history is unavailable — the UI then says so instead of guessing.
     */
    private suspend fun timingFor(symbol: String): Timing {
        return try {
            val bars = market.getRangeCandles(symbol, "5d", "5m")
            if (bars.size < 30) return Timing.EMPTY
            // Group by ET calendar day, regular session only (09:30–16:00).
            val byDay = LinkedHashMap<String, MutableList<Candle>>()
            for (c in bars) {
                val et = Instant.ofEpochMilli(c.ts).atZone(ET)
                val minutes = et.hour * 60 + et.minute
                if (minutes < 9 * 60 + 30 || minutes > 16 * 60) continue
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
                buyWindow = windowAround(lowMedian),
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

    /** A ±15-minute window around [minute], clamped to the regular session. */
    private fun windowAround(minute: Int): String {
        val start = (minute - 15).coerceAtLeast(9 * 60 + 30)
        val end = (minute + 15).coerceAtMost(16 * 60)
        return "${clock(start)}–${clock(end)} ET"
    }

    private fun clock(minutes: Int): String {
        val h24 = minutes / 60
        val m = minutes % 60
        val suffix = if (h24 >= 12) "PM" else "AM"
        val h12 = when {
            h24 == 0 -> 12
            h24 > 12 -> h24 - 12
            else -> h24
        }
        return String.format(Locale.US, "%d:%02d %s", h12, m, suffix)
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
