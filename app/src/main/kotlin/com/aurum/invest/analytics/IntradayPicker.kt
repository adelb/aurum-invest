package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import java.util.Locale
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * One open-session candidate: a stock the engine reads as positioned to gain
 * at least the target from its CURRENT price before the close.
 *
 * Every number is measured, not assumed. The core claim — "this can still add
 * [targetPct]% from here" — is tested against history: how often the stock's
 * intraday high ultimately rose far enough above the open to hand today's
 * buyer that move from the current price.
 */
data class IntradayPick(
    val date: String,
    val rank: Int,
    val symbol: String,
    val name: String,
    val price: Double,               // live price at scan time
    val openPrice: Double,           // today's regular-session open
    val sinceOpenPct: Double,        // current move off the open
    val dayChangePct: Double,        // move vs previous close
    val targetPct: Double,           // the goal being tested (e.g. 2.06)
    val targetPrice: Double,         // price * (1 + target)
    val stop: Double,                // ATR-based, under the current price
    // --- evidence ---------------------------------------------------------
    /** Open-to-high total the day must print for +target% from HERE. */
    val neededFromOpenPct: Double,
    /** % of recent sessions whose open-to-high reach covered that total. */
    val hitRatePct: Double,
    val sessionsAnalyzed: Int,
    /** Today's volume vs its 3-month average, adjusted for session progress. */
    val relativeVolume: Double,
    /** Last ~30 minutes of price drift, percent. */
    val momentum30Pct: Double,
    /** Where the price sits in today's range: 100 = at the high. */
    val rangePosPct: Double,
    val atrPct: Double,
    val techDirection: String,
    val techBullish: Int,
    val techTotal: Int,
    val techConfidence: Int,
    val reason: String,
    val caution: String,             // "" when nothing needs flagging
    val asOf: Long = 0L
)

/**
 * The open-session half of the 2% desk: once the US market is trading, scan
 * the whole market for stocks still positioned to gain at least [targetPct]
 * from their current price before the close.
 *
 * Integrity rules — a name is proposed only when ALL of these hold:
 *  1. **The technique board confirms it** — the 35-technique outlook must be
 *     BULLISH. A trending screener line with a bearish board is dropped.
 *  2. **The current move agrees** — the stock trades above its open. A name
 *     falling on the session is never proposed for a further gain.
 *  3. **Volume backs the move** — today's traded volume, scaled by how much
 *     of the session has elapsed, must at least approach its normal pace.
 *  4. **The target is historically reachable from here** — the open-to-high
 *     reach the claim requires (current gain + target) is counted against
 *     ~60 recent sessions, and that hit rate leads the ranking.
 *
 * Outside the regular session the scan returns empty rather than dressing
 * stale numbers as live ones — the screen says when it last ran.
 */
class IntradayPicker(private val market: MarketRepository) {

    companion object {
        private const val POOL_SCREENS_CHUNK = 4
        private const val SHORTLIST = 34
        private const val DEEP_CHUNK = 6
        private const val HISTORY_DAYS = 90

        /** Minimum time-adjusted relative volume for a "volume-backed" claim. */
        private const val MIN_REL_VOLUME = 0.75

        /** Intraday prints move constantly — never serve them more than a minute stale. */
        private const val LIVE_MAX_AGE_MS = 60_000L

        fun toJson(picks: List<IntradayPick>): String {
            val arr = JSONArray()
            picks.forEach { p ->
                arr.put(JSONObject().apply {
                    put("date", p.date)
                    put("rank", p.rank)
                    put("symbol", p.symbol)
                    put("name", p.name)
                    put("price", p.price)
                    put("openPrice", p.openPrice)
                    put("sinceOpenPct", p.sinceOpenPct)
                    put("dayChangePct", p.dayChangePct)
                    put("targetPct", p.targetPct)
                    put("targetPrice", p.targetPrice)
                    put("stop", p.stop)
                    put("neededFromOpenPct", p.neededFromOpenPct)
                    put("hitRatePct", p.hitRatePct)
                    put("sessionsAnalyzed", p.sessionsAnalyzed)
                    put("relativeVolume", p.relativeVolume)
                    put("momentum30Pct", p.momentum30Pct)
                    put("rangePosPct", p.rangePosPct)
                    put("atrPct", p.atrPct)
                    put("techDirection", p.techDirection)
                    put("techBullish", p.techBullish)
                    put("techTotal", p.techTotal)
                    put("techConfidence", p.techConfidence)
                    put("reason", p.reason)
                    put("caution", p.caution)
                    put("asOf", p.asOf)
                })
            }
            return arr.toString()
        }

        fun fromJson(s: String): List<IntradayPick> = try {
            val arr = JSONArray(s)
            val out = ArrayList<IntradayPick>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    IntradayPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        price = o.optDouble("price", 0.0),
                        openPrice = o.optDouble("openPrice", 0.0),
                        sinceOpenPct = o.optDouble("sinceOpenPct", 0.0),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        targetPct = o.optDouble("targetPct", PreMarketPicker.DEFAULT_TARGET_PCT),
                        targetPrice = o.optDouble("targetPrice", 0.0),
                        stop = o.optDouble("stop", 0.0),
                        neededFromOpenPct = o.optDouble("neededFromOpenPct", 0.0),
                        hitRatePct = o.optDouble("hitRatePct", 0.0),
                        sessionsAnalyzed = o.optInt("sessionsAnalyzed", 0),
                        relativeVolume = o.optDouble("relativeVolume", 0.0),
                        momentum30Pct = o.optDouble("momentum30Pct", 0.0),
                        rangePosPct = o.optDouble("rangePosPct", 0.0),
                        atrPct = o.optDouble("atrPct", 0.0),
                        techDirection = o.optString("techDirection", "NEUTRAL"),
                        techBullish = o.optInt("techBullish", 0),
                        techTotal = o.optInt("techTotal", 0),
                        techConfidence = o.optInt("techConfidence", 0),
                        reason = o.optString("reason", ""),
                        caution = o.optString("caution", ""),
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
     * The [count] strongest open-session candidates for +[targetPct]% from
     * their current price. Empty outside the regular session — this scan has
     * no honest answer when the market is not trading.
     */
    suspend fun computePicks(
        dateIso: String,
        targetPct: Double = PreMarketPicker.DEFAULT_TARGET_PCT,
        count: Int = 10
    ): List<IntradayPick> {
        return try {
            if (Dates.marketSessionNow() != Dates.MarketSession.REGULAR) return emptyList()
            val target = targetPct.takeIf { it > 0.0 } ?: PreMarketPicker.DEFAULT_TARGET_PCT

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

            // 2 — liquid, sanely priced names that are actually UP on the day.
            // The scan asks for a further gain; names falling on the session
            // do not qualify for that claim.
            val tradeable = pool.values.filter {
                it.price in 3.0..2000.0 &&
                    it.dayChangePct > -0.2 &&
                    it.avgVolume3M >= 1_000_000L &&
                    it.price * it.avgVolume3M >= 25_000_000.0 &&
                    it.marketCap >= 300_000_000.0 &&
                    it.symbol.all { ch -> ch.isLetterOrDigit() }
            }.sortedByDescending { it.dayVolume }.take(SHORTLIST)
            if (tradeable.isEmpty()) return emptyList()

            // 3 — measure every shortlisted name against the goal.
            val measured = ArrayList<Pair<IntradayPick, Double>>()
            for (chunk in tradeable.chunked(DEEP_CHUNK)) {
                val results = coroutineScope {
                    chunk.map { q -> async { measure(q, target, dateIso) } }.awaitAll()
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
        target: Double,
        dateIso: String
    ): Pair<IntradayPick, Double>? {
        return try {
            val daily = market.getDailyCandles(q.symbol, HISTORY_DAYS)
            if (daily.size < 25) return null
            val now = System.currentTimeMillis()

            // Today's bar carries the session open; history excludes it so
            // no statistic is contaminated by the in-progress day.
            val lastIsToday = Dates.sameDay(daily.last().ts, now)
            val history = if (lastIsToday) daily.dropLast(1) else daily
            if (history.size < 20) return null

            // Live 5-minute bars: the current price, today's range, and the
            // last half hour of drift. Fetched fresh — this list is only as
            // honest as its prints are recent.
            val intraday = market.getIntraday(q.symbol, maxAgeMs = LIVE_MAX_AGE_MS)
            val todayBars = intraday.filter { Dates.sameDay(it.ts, now) }

            // The session open, from today's daily bar or the first live bar.
            // A name with no observable open is skipped, never guessed.
            val open = when {
                lastIsToday && daily.last().open > 0.0 -> daily.last().open
                todayBars.isNotEmpty() && todayBars.first().open > 0.0 -> todayBars.first().open
                else -> return null
            }
            val price = todayBars.lastOrNull()?.close?.takeIf { it > 0.0 } ?: q.price
            if (price <= 0.0) return null

            // Integrity gate 1 — the current move must agree with the claim.
            val sinceOpen = (price / open - 1.0) * 100.0
            if (sinceOpen <= 0.0) return null

            // Integrity gate 2 — the technique board must confirm.
            val analysis = Techniques.analyze(q.symbol, daily) ?: return null
            if (analysis.outlook.direction != TechniqueVerdict.BULLISH) return null
            val bullish = analysis.outlook.bullishCount
            val total = analysis.results.size
            val confidence = analysis.outlook.confidence

            // Integrity gate 3 — volume must back the move. Today's traded
            // volume is scaled by how much of the session has elapsed before
            // comparing against the 3-month average.
            val elapsed = sessionElapsedFraction()
            val dayVolume = q.dayVolume.takeIf { it > 0L }
                ?: todayBars.sumOf { it.volume }
            if (q.avgVolume3M <= 0L || dayVolume <= 0L) return null
            val relVol = dayVolume.toDouble() / (q.avgVolume3M.toDouble() * elapsed)
            if (relVol < MIN_REL_VOLUME) return null

            // Integrity gate 4 — the target must be historically reachable
            // from HERE: today's high must ultimately print (current gain +
            // target) above the open. Count how often that reach occurred.
            val needed = ((price * (1.0 + target / 100.0)) / open - 1.0) * 100.0
            val reaches = history.mapNotNull { c ->
                if (c.open <= 0.0) null else (c.high - c.open) / c.open * 100.0
            }
            if (reaches.isEmpty()) return null
            val hits = reaches.count { it >= needed }
            val hitRate = hits * 100.0 / reaches.size

            val atr = Indicators.atr(history) ?: return null
            val lastClose = history.last().close
            if (lastClose <= 0.0) return null
            val atrPct = atr / lastClose * 100.0

            // Last ~30 minutes of drift (six 5-minute bars).
            val momentum30 =
                if (todayBars.size >= 7) {
                    val base = todayBars[todayBars.size - 7].close
                    if (base > 0.0) (todayBars.last().close / base - 1.0) * 100.0 else 0.0
                } else 0.0

            // Where the price sits in today's range; buying at the high of a
            // faded range is a different trade than buying a fresh push.
            val dayHigh = maxOf(todayBars.maxOfOrNull { it.high } ?: price, price)
            val dayLow = minOf(todayBars.minOfOrNull { it.low } ?: price, price)
            val rangePos =
                if (dayHigh > dayLow) (price - dayLow) / (dayHigh - dayLow) * 100.0 else 50.0

            val targetPrice = price * (1.0 + target / 100.0)
            val stop = price - atr * 0.5

            val rangeUsed = if (atr > 0.0) (dayHigh - dayLow) / atr else 0.0
            val caution = buildString {
                if (hitRate < 40.0) {
                    append(
                        String.format(
                            Locale.US,
                            "Only %.0f%% of recent sessions stretched %.2f%% above the open — " +
                                "from here the target needs an unusual day. ",
                            hitRate, needed
                        )
                    )
                }
                if (rangeUsed > 1.5) {
                    append(
                        String.format(
                            Locale.US,
                            "Today's range has already used %.1fx its normal ATR budget. ",
                            rangeUsed
                        )
                    )
                }
                if (momentum30 < -0.3) {
                    append("The last half hour has been fading rather than pushing.")
                }
            }.trim()

            val reason = String.format(
                Locale.US,
                "Up %.1f%% off the open on %.1fx normal volume, %d of %d techniques bullish. " +
                    "The +%.2f%% from here needs a %.2f%% open-to-high day — " +
                    "printed on %d of %d recent sessions (%.0f%%).",
                sinceOpen, relVol, bullish, total,
                target, needed, hits, reaches.size, hitRate
            )

            // Rank: the historical odds of the remaining move lead; live
            // confirmation (volume, momentum, range position) breaks ties.
            val score = hitRate * 1.0 +
                (relVol.coerceAtMost(4.0) * 6.0) +
                (momentum30.coerceIn(-2.0, 2.0) * 5.0) +
                (rangePos * 0.08) +
                (confidence * 0.15)

            val pick = IntradayPick(
                date = dateIso,
                rank = 0,
                symbol = q.symbol,
                name = q.name.ifEmpty { q.symbol },
                price = round2(price),
                openPrice = round2(open),
                sinceOpenPct = round2(sinceOpen),
                dayChangePct = round2(q.dayChangePct),
                targetPct = target,
                targetPrice = round2(targetPrice),
                stop = round2(stop),
                neededFromOpenPct = round2(needed),
                hitRatePct = round1(hitRate),
                sessionsAnalyzed = reaches.size,
                relativeVolume = round1(relVol),
                momentum30Pct = round2(momentum30),
                rangePosPct = round1(rangePos),
                atrPct = round1(atrPct),
                techDirection = analysis.outlook.direction.name,
                techBullish = bullish,
                techTotal = total,
                techConfidence = confidence,
                reason = reason,
                caution = caution,
                asOf = now
            )
            pick to score
        } catch (_: Exception) {
            null
        }
    }

    /** How much of the 390-minute regular session has elapsed, 0.05..1. */
    private fun sessionElapsedFraction(): Double {
        val nowEt = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"))
        val minutes = nowEt.hour * 60 + nowEt.minute - (9 * 60 + 30)
        return (minutes / 390.0).coerceIn(0.05, 1.0)
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
