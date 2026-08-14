package com.aurum.invest.analytics

import com.aurum.invest.core.Dates
import com.aurum.invest.data.model.Candle
import com.aurum.invest.data.model.ScreenerQuote
import com.aurum.invest.data.repo.MarketRepository
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * The U-pattern engine: finds the stocks that habitually dip after the open
 * and climb back through the close — the intraday "U" — and tells the user
 * WHEN today's turn is confirmed enough to buy.
 *
 * Everything is measured, nothing is styled:
 *
 *  1. WHOLE-MARKET POOL — Yahoo's eight market-wide screens, liquidity-gated
 *     (dollar volume, sane price band).
 *  2. FINGERPRINT — ~21 sessions of 5-minute bars per name. A session counts
 *     as a U-day only when four measured legs agree: the low printed before
 *     13:00 ET, the close landed in the top 30% of the day's range, the
 *     rebound off the low was at least 1%, and an OLS parabola fitted to the
 *     day's closes curves upward (a > 0 — the mathematical definition of a
 *     U). The fingerprint also captures WHERE the low usually lands (median
 *     and IQR of its clock time) and how big the dip and rebound really are.
 *  3. RULE BACKTEST — the exact rule the app will signal live ("after a
 *     ≥0.8% dip, buy when a bar closes back above VWAP without making a new
 *     low; exit 15:45 ET") is replayed over every fingerprint session, U-days
 *     and failures alike. The record — fired / won / average return — is
 *     printed on the card and gates the pick: no measured edge, no listing.
 *  4. TECHNIQUE BOARD — finalists run the 20-technique daily board; a
 *     BEARISH board disqualifies (a U inside a downtrend is a trap).
 *  5. LIVE STATE — during the session each pick is tracked bar by bar:
 *     no-dip-yet → in-the-dip → BUY ZONE (VWAP reclaimed on a higher low,
 *     with entry, stop under the session low, target from the measured
 *     median rebound) → too-late once most of that rebound is spent.
 *
 * Integrity rules: fixed-scale scores (80 means the same next month), hard
 * qualification gates (a day with no qualifying names shows none), and every
 * claim on the card carries the number it was measured from. Never throws —
 * symbols that fail to fetch are skipped.
 */

/** Where a pick stands right now in its own day. */
enum class UState {
    MARKET_CLOSED,  // no live session to read
    NO_DIP_YET,     // session running, the dip hasn't printed
    IN_DIP,         // dipped, turn not confirmed — wait
    BUY_ZONE,       // turn confirmed, most of the rebound still ahead
    TOO_LATE,       // turn confirmed long ago — don't chase
    NO_TURN,        // the U didn't come today
    AFTER_CLOSE     // session over — the day is summarized
}

data class UPick(
    val date: String,            // ISO day the pick list belongs to
    val rank: Int,
    val symbol: String,
    val name: String,
    val price: Double,
    val dayChangePct: Double,
    /** 0..100 on a FIXED scale — comparable across days and weeks. */
    val score: Int,
    // ---- the measured fingerprint ----
    val sessions: Int,           // valid sessions inspected
    val uDays: Int,              // sessions that met all four U legs
    val medianDipPct: Double,    // open -> low on U-days
    val medianReboundPct: Double,// low -> close on U-days
    val convexDays: Int,         // sessions whose fitted parabola curved up
    val buyWindow: String,       // where the low usually lands (Amman clock)
    val sellWindow: String,      // where the high usually lands (Amman clock)
    // ---- the rule's own record ----
    val btFired: Int,
    val btWins: Int,
    val btMedianRetPct: Double,
    val btMeanRetPct: Double,
    // ---- confirmation ----
    val techBullish: Int,
    val techTotal: Int,
    // ---- today, live ----
    val state: String,           // UState name
    val stateNote: String,
    val dipTodayPct: Double?,    // open -> session low so far
    val vwap: Double?,           // running session VWAP
    val entry: Double?,          // only in BUY_ZONE
    val stop: Double?,
    val target: Double?,
    val rewardRisk: Double?,
    val reason: String,
    val asOf: Long
)

/** Per-symbol fingerprint persistence, implemented by the repository. */
interface UFingerprintCache {
    suspend fun read(symbol: String): Pair<String, Long>?   // json + storedAt
    suspend fun write(symbol: String, json: String)
}

class UPatternEngine(
    private val market: MarketRepository,
    private val cache: UFingerprintCache? = null
) {

    companion object {
        // ---- pool gates ----
        private const val MIN_PRICE = 3.0
        private const val MAX_PRICE = 1_500.0
        private const val MIN_DOLLAR_VOLUME = 8_000_000.0

        // ---- U-day definition (all four must hold) ----
        private const val LOW_BEFORE_MIN = 13 * 60          // low before 13:00 ET
        private const val MIN_CLOSE_POS = 0.70              // close in top 30% of range
        private const val MIN_REBOUND_PCT = 1.0             // low -> close
        private const val CONVEX_MIN_R2 = 0.30              // parabola fit quality

        // ---- live/backtest entry rule ----
        private const val MIN_DIP_PCT = 0.8                 // dip that arms the rule
        private const val RULE_START_MIN = 10 * 60          // no entries before 10:00 ET
        private const val RULE_LAST_ENTRY_MIN = 14 * 60 + 30
        private const val RULE_EXIT_MIN = 15 * 60 + 45
        private const val STOP_PAD = 0.003                  // stop 0.3% under the session low
        private const val CHASE_LIMIT = 0.55                // rebound share that closes the buy zone

        // ---- qualification gates: fail any, and the name is not listed ----
        private const val MIN_SESSIONS = 12
        private const val MIN_U_RATE = 0.40
        private const val MIN_MEDIAN_REBOUND = 1.0
        private const val MIN_BT_FIRED = 5
        private const val MIN_BT_WIN_RATE = 0.55
        private const val MIN_BT_MEAN_RET = 0.15            // percent per trade

        // ---- sizes ----
        private const val FINGERPRINT_BUDGET = 120          // deep-reads per compute
        private const val FINGERPRINT_TTL_MS = 3L * 24 * 3_600_000
        private const val CHUNK = 6
        private const val FINALISTS = 14
        private const val PICKS = 10

        private val ET = ZoneId.of("America/New_York")
        private val AMMAN = ZoneId.of("Asia/Amman")

        // ------------------------------- JSON: pick list --------------------

        fun toJson(picks: List<UPick>): String {
            val arr = JSONArray()
            picks.forEach { p ->
                arr.put(JSONObject().apply {
                    put("date", p.date); put("rank", p.rank)
                    put("symbol", p.symbol); put("name", p.name)
                    put("price", p.price); put("dayChangePct", p.dayChangePct)
                    put("score", p.score)
                    put("sessions", p.sessions); put("uDays", p.uDays)
                    put("medianDipPct", p.medianDipPct)
                    put("medianReboundPct", p.medianReboundPct)
                    put("convexDays", p.convexDays)
                    put("buyWindow", p.buyWindow); put("sellWindow", p.sellWindow)
                    put("btFired", p.btFired); put("btWins", p.btWins)
                    put("btMedianRetPct", p.btMedianRetPct)
                    put("btMeanRetPct", p.btMeanRetPct)
                    put("techBullish", p.techBullish); put("techTotal", p.techTotal)
                    put("state", p.state); put("stateNote", p.stateNote)
                    p.dipTodayPct?.let { put("dipTodayPct", it) }
                    p.vwap?.let { put("vwap", it) }
                    p.entry?.let { put("entry", it) }
                    p.stop?.let { put("stop", it) }
                    p.target?.let { put("target", it) }
                    p.rewardRisk?.let { put("rewardRisk", it) }
                    put("reason", p.reason); put("asOf", p.asOf)
                })
            }
            return arr.toString()
        }

        fun fromJson(s: String): List<UPick> = try {
            val arr = JSONArray(s)
            val out = ArrayList<UPick>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    UPick(
                        date = o.getString("date"),
                        rank = o.getInt("rank"),
                        symbol = o.getString("symbol"),
                        name = o.optString("name", ""),
                        price = o.optDouble("price", 0.0),
                        dayChangePct = o.optDouble("dayChangePct", 0.0),
                        score = o.optInt("score", 0),
                        sessions = o.optInt("sessions", 0),
                        uDays = o.optInt("uDays", 0),
                        medianDipPct = o.optDouble("medianDipPct", 0.0),
                        medianReboundPct = o.optDouble("medianReboundPct", 0.0),
                        convexDays = o.optInt("convexDays", 0),
                        buyWindow = o.optString("buyWindow", ""),
                        sellWindow = o.optString("sellWindow", ""),
                        btFired = o.optInt("btFired", 0),
                        btWins = o.optInt("btWins", 0),
                        btMedianRetPct = o.optDouble("btMedianRetPct", 0.0),
                        btMeanRetPct = o.optDouble("btMeanRetPct", 0.0),
                        techBullish = o.optInt("techBullish", 0),
                        techTotal = o.optInt("techTotal", 0),
                        state = o.optString("state", UState.MARKET_CLOSED.name),
                        stateNote = o.optString("stateNote", ""),
                        dipTodayPct = if (o.has("dipTodayPct")) o.getDouble("dipTodayPct") else null,
                        vwap = if (o.has("vwap")) o.getDouble("vwap") else null,
                        entry = if (o.has("entry")) o.getDouble("entry") else null,
                        stop = if (o.has("stop")) o.getDouble("stop") else null,
                        target = if (o.has("target")) o.getDouble("target") else null,
                        rewardRisk = if (o.has("rewardRisk")) o.getDouble("rewardRisk") else null,
                        reason = o.optString("reason", ""),
                        asOf = o.optLong("asOf", 0L)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ fingerprint

    /** The measured intraday habit of one symbol. */
    private data class Fingerprint(
        val symbol: String,
        val sessions: Int,
        val uDays: Int,
        val medianDipPct: Double,
        val medianReboundPct: Double,
        val lowMedianMin: Int,
        val lowIqrMin: Int,
        val highMedianMin: Int,
        val convexDays: Int,
        val btFired: Int,
        val btWins: Int,
        val btMedianRetPct: Double,
        val btMeanRetPct: Double
    ) {
        val uRate: Double get() = if (sessions > 0) uDays.toDouble() / sessions else 0.0
        val btWinRate: Double get() = if (btFired > 0) btWins.toDouble() / btFired else 0.0

        fun qualifies(): Boolean =
            sessions >= MIN_SESSIONS &&
                uRate >= MIN_U_RATE &&
                medianReboundPct >= MIN_MEDIAN_REBOUND &&
                btFired >= MIN_BT_FIRED &&
                btWinRate >= MIN_BT_WIN_RATE &&
                btMeanRetPct >= MIN_BT_MEAN_RET

        /** Fixed-scale 0..100; every part has a documented ceiling. */
        fun score(): Int {
            val uPart = (uRate.coerceAtMost(0.75) / 0.75) * 30.0
            val winPart = ((btWinRate - 0.55) / 0.30).coerceIn(0.0, 1.0) * 20.0
            val edgePart = (btMeanRetPct / 0.8).coerceIn(0.0, 1.0) * 15.0
            val sizePart = (medianReboundPct / 3.0).coerceIn(0.0, 1.0) * 15.0
            val timingPart = ((120.0 - lowIqrMin) / 85.0).coerceIn(0.0, 1.0) * 10.0
            val convexPart = if (sessions > 0) convexDays.toDouble() / sessions * 10.0 else 0.0
            return (uPart + winPart + edgePart + sizePart + timingPart + convexPart)
                .roundToIntSafe()
        }

        fun toJson(): String = JSONObject().apply {
            put("symbol", symbol); put("sessions", sessions); put("uDays", uDays)
            put("dip", medianDipPct); put("rebound", medianReboundPct)
            put("lowMed", lowMedianMin); put("lowIqr", lowIqrMin); put("highMed", highMedianMin)
            put("convex", convexDays)
            put("btFired", btFired); put("btWins", btWins)
            put("btMedian", btMedianRetPct); put("btMean", btMeanRetPct)
        }.toString()

        companion object {
            fun fromJson(s: String): Fingerprint? = try {
                val o = JSONObject(s)
                Fingerprint(
                    symbol = o.getString("symbol"),
                    sessions = o.getInt("sessions"),
                    uDays = o.getInt("uDays"),
                    medianDipPct = o.optDouble("dip", 0.0),
                    medianReboundPct = o.optDouble("rebound", 0.0),
                    lowMedianMin = o.optInt("lowMed", 0),
                    lowIqrMin = o.optInt("lowIqr", 999),
                    highMedianMin = o.optInt("highMed", 0),
                    convexDays = o.optInt("convex", 0),
                    btFired = o.optInt("btFired", 0),
                    btWins = o.optInt("btWins", 0),
                    btMedianRetPct = o.optDouble("btMedian", 0.0),
                    btMeanRetPct = o.optDouble("btMean", 0.0)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    // --------------------------------------------------------------- compute

    /**
     * The full scan: pool the market screens, fingerprint (cache-first, up to
     * [FINGERPRINT_BUDGET] fresh deep-reads per call — repeated calls through
     * the day walk the rest of the pool), gate, confirm, and read today live.
     */
    suspend fun compute(dateIso: String): List<UPick> {
        return try {
            // 1 — whole-market pool, liquidity-gated.
            val pool = LinkedHashMap<String, ScreenerQuote>()
            for (chunk in EntryPicker.MARKET_SCREENS.chunked(4)) {
                coroutineScope {
                    chunk.map { id -> async { market.getScreener(id) } }.awaitAll()
                }.forEach { list -> list.forEach { q -> pool.putIfAbsent(q.symbol, q) } }
            }
            val gated = pool.values.filter { q ->
                q.price in MIN_PRICE..MAX_PRICE &&
                    q.price * q.avgVolume3M >= MIN_DOLLAR_VOLUME &&
                    abs(q.dayChangePct) < 25.0    // halted/berserk names are not a pattern
            }
            if (gated.isEmpty()) return emptyList()

            // Priority: during the session, names already recovering off a real
            // low go first; liquidity breaks ties. Off-session: liquidity.
            val session = Dates.marketSessionNow()
            val prioritized = gated.sortedByDescending { q ->
                val liquidity = (q.price * q.avgVolume3M / 200_000_000.0).coerceAtMost(1.0)
                val liveBoost =
                    if (session == Dates.MarketSession.REGULAR &&
                        q.dayLow > 0.0 && q.dayHigh > q.dayLow
                    ) {
                        val pos = (q.price - q.dayLow) / (q.dayHigh - q.dayLow)
                        if (pos in 0.15..0.9) 1.0 else 0.0
                    } else 0.0
                liveBoost * 2.0 + liquidity
            }

            // 2 — fingerprints, cache-first with a bounded fresh budget.
            val prints = fingerprints(prioritized)

            // 3 — qualification gates + fixed-scale ranking.
            val qualified = prints.filter { it.qualifies() }
                .sortedByDescending { it.score() }
                .take(FINALISTS)
            if (qualified.isEmpty()) return emptyList()

            // 4 — technique confirmation + live read per finalist.
            val quoteMap = gated.associateBy { it.symbol }
            val picks = coroutineScope {
                qualified.map { fp ->
                    async { buildPick(dateIso, fp, quoteMap[fp.symbol], session) }
                }.awaitAll()
            }.filterNotNull()

            picks.sortedByDescending { it.score }
                .take(PICKS)
                .mapIndexed { i, p -> p.copy(rank = i + 1) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Cheap re-read of today's state for already-computed picks (the 15-minute
     * worker and pull-to-refresh path): one intraday fetch per pick, no
     * fingerprinting, no screens.
     */
    suspend fun refreshLive(picks: List<UPick>): List<UPick> {
        if (picks.isEmpty()) return picks
        val session = Dates.marketSessionNow()
        return try {
            val quotes = try {
                market.getQuotes(picks.map { it.symbol }, maxAgeMs = 60_000L)
            } catch (_: Exception) {
                emptyMap()
            }
            coroutineScope {
                picks.map { p ->
                    async {
                        try {
                            val bars = market.getIntraday(p.symbol, maxAgeMs = 120_000L)
                            val quote = quotes[p.symbol]
                            val live = liveRead(
                                bars, session,
                                p.medianDipPct, p.medianReboundPct, p.buyWindow
                            )
                            p.copy(
                                price = quote?.price ?: p.price,
                                dayChangePct = quote?.dayChangePct ?: p.dayChangePct,
                                state = live.state.name,
                                stateNote = live.note,
                                dipTodayPct = live.dipPct,
                                vwap = live.vwap,
                                entry = live.entry,
                                stop = live.stop,
                                target = live.target,
                                rewardRisk = live.rewardRisk,
                                asOf = System.currentTimeMillis()
                            )
                        } catch (_: Exception) {
                            p
                        }
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {
            picks
        }
    }

    // ------------------------------------------------------ fingerprint sweep

    private suspend fun fingerprints(prioritized: List<ScreenerQuote>): List<Fingerprint> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Fingerprint>()
        val toCompute = ArrayList<ScreenerQuote>()
        for (q in prioritized) {
            val cached = try {
                cache?.read(q.symbol)
            } catch (_: Exception) {
                null
            }
            val parsed = cached?.let { (json, storedAt) ->
                if (now - storedAt <= FINGERPRINT_TTL_MS) Fingerprint.fromJson(json) else null
            }
            if (parsed != null) out.add(parsed)
            else if (toCompute.size < FINGERPRINT_BUDGET) toCompute.add(q)
        }
        for (chunk in toCompute.chunked(CHUNK)) {
            val part = coroutineScope {
                chunk.map { q -> async { fingerprintOf(q.symbol) } }.awaitAll()
            }
            part.filterNotNull().forEach { fp ->
                out.add(fp)
                try {
                    cache?.write(fp.symbol, fp.toJson())
                } catch (_: Exception) {
                    // cache write failure is non-fatal
                }
            }
        }
        return out
    }

    /** One symbol's fingerprint from ~21 sessions of 5-minute bars. */
    private suspend fun fingerprintOf(symbol: String): Fingerprint? {
        return try {
            val bars = market.getRangeCandles(symbol, "1mo", "5m", maxAgeMs = 21_600_000L)
            if (bars.size < 200) return null
            val days = regularSessionDays(bars, excludeToday = true)
            if (days.size < MIN_SESSIONS) return null

            val dips = ArrayList<Double>()
            val rebounds = ArrayList<Double>()
            val lowMins = ArrayList<Int>()
            val highMins = ArrayList<Int>()
            var uDays = 0
            var convexDays = 0
            val rets = ArrayList<Double>()

            for (day in days) {
                val open = day.first().open
                val close = day.last().close
                if (open <= 0.0 || close <= 0.0) continue
                val lowBar = day.minByOrNull { it.low } ?: continue
                val highBar = day.maxByOrNull { it.high } ?: continue
                val low = lowBar.low
                val high = highBar.high
                if (low <= 0.0 || high <= low) continue

                val dipPct = (open - low) / open * 100.0
                val reboundPct = (close - low) / low * 100.0
                val closePos = (close - low) / (high - low)
                val lowMin = minuteOfDayEt(lowBar.ts)
                val highMin = minuteOfDayEt(highBar.ts)

                // The parabola: p(t) = a·t² + b·t + c over normalized closes.
                val fit = quadFit(day.map { it.close }, open)
                val convex = fit != null && fit.a > 0.0 && fit.r2 >= CONVEX_MIN_R2
                if (convex) convexDays++

                val isU = lowMin <= LOW_BEFORE_MIN &&
                    closePos >= MIN_CLOSE_POS &&
                    reboundPct >= MIN_REBOUND_PCT &&
                    fit != null && fit.a > 0.0
                if (isU) {
                    uDays++
                    dips.add(dipPct)
                    rebounds.add(reboundPct)
                    lowMins.add(lowMin)
                    highMins.add(highMin)
                }

                // The rule replays on EVERY session — its losses count too.
                backtestDay(day)?.let { rets.add(it) }
            }
            // A zero-U name is still a RESULT — it must cache, or every scan
            // would re-spend its budget refingerprinting the same misses.
            val lowSorted = lowMins.sorted()
            val iqr = if (lowSorted.size >= 4) {
                lowSorted[(lowSorted.size * 3) / 4] - lowSorted[lowSorted.size / 4]
            } else 999
            val wins = rets.count { it > 0.0 }
            Fingerprint(
                symbol = symbol,
                sessions = days.size,
                uDays = uDays,
                medianDipPct = round1(medianD(dips)),
                medianReboundPct = round1(medianD(rebounds)),
                lowMedianMin = medianI(lowMins),
                lowIqrMin = iqr,
                highMedianMin = medianI(highMins),
                convexDays = convexDays,
                btFired = rets.size,
                btWins = wins,
                btMedianRetPct = round2(medianD(rets)),
                btMeanRetPct = round2(if (rets.isEmpty()) 0.0 else rets.average())
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replay the live rule over one finished session. Returns the trade's
     * percent result, or null when the rule never fired that day.
     */
    private fun backtestDay(day: List<Candle>): Double? {
        val open = day.first().open
        if (open <= 0.0) return null
        var cumPV = 0.0
        var cumV = 0.0
        var sessionLow = Double.MAX_VALUE
        var entry: Double? = null
        var exit: Double? = null
        for (bar in day) {
            val minute = minuteOfDayEt(bar.ts)
            val lowBefore = sessionLow
            sessionLow = minOf(sessionLow, bar.low)
            val typical = (bar.high + bar.low + bar.close) / 3.0
            cumPV += typical * bar.volume
            cumV += bar.volume.toDouble()
            val vwap = if (cumV > 0.0) cumPV / cumV else bar.close

            if (entry == null &&
                minute in RULE_START_MIN..RULE_LAST_ENTRY_MIN &&
                lowBefore < Double.MAX_VALUE &&
                (open - lowBefore) / open * 100.0 >= MIN_DIP_PCT &&
                bar.close > vwap &&
                bar.low > lowBefore
            ) {
                entry = bar.close
            }
            if (minute <= RULE_EXIT_MIN) exit = bar.close
        }
        val e = entry ?: return null
        val x = exit ?: return null
        if (e <= 0.0) return null
        return (x / e - 1.0) * 100.0
    }

    // ------------------------------------------------------------- live state

    private data class LiveRead(
        val state: UState,
        val note: String,
        val dipPct: Double?,
        val vwap: Double?,
        val entry: Double?,
        val stop: Double?,
        val target: Double?,
        val rewardRisk: Double?
    )

    /** Today's bars against the fingerprint: where does this name stand now? */
    private fun liveRead(
        bars: List<Candle>,
        session: Dates.MarketSession,
        medianDipPct: Double,
        medianReboundPct: Double,
        buyWindow: String
    ): LiveRead {
        val today = try {
            regularSessionDays(bars, excludeToday = false)
                .lastOrNull { day -> Dates.sameEtDay(day.last().ts, System.currentTimeMillis()) }
        } catch (_: Exception) {
            null
        }
        if (session == Dates.MarketSession.WEEKEND ||
            session == Dates.MarketSession.PRE ||
            (session == Dates.MarketSession.OVERNIGHT && today == null)
        ) {
            return LiveRead(
                UState.MARKET_CLOSED,
                "Market closed. The live scan resumes at ${Dates.etAsAmman(9, 30)} — " +
                    "its usual dip window is $buyWindow.",
                null, null, null, null, null, null
            )
        }
        if (today == null || today.size < 3) {
            return LiveRead(
                UState.NO_DIP_YET,
                "Waiting for enough of today's bars to read the shape.",
                null, null, null, null, null, null
            )
        }

        val open = today.first().open
        var cumPV = 0.0
        var cumV = 0.0
        for (bar in today) {
            val typical = (bar.high + bar.low + bar.close) / 3.0
            cumPV += typical * bar.volume
            cumV += bar.volume.toDouble()
        }
        val vwap = if (cumV > 0.0) cumPV / cumV else today.last().close
        val sessionLow = today.minOf { it.low }
        val last = today.last()
        val price = last.close
        if (open <= 0.0 || sessionLow <= 0.0 || price <= 0.0) {
            return LiveRead(UState.NO_DIP_YET, "", null, null, null, null, null, null)
        }
        val dipPct = (open - sessionLow) / open * 100.0
        val reboundUsed = (price / sessionLow - 1.0) / (medianReboundPct / 100.0)
        val lastMin = minuteOfDayEt(last.ts)
        val sessionOver = session == Dates.MarketSession.POST ||
            session == Dates.MarketSession.OVERNIGHT

        if (sessionOver) {
            val closePos = run {
                val high = today.maxOf { it.high }
                if (high > sessionLow) (price - sessionLow) / (high - sessionLow) else 0.0
            }
            val wasU = dipPct >= MIN_DIP_PCT && closePos >= MIN_CLOSE_POS &&
                (price / sessionLow - 1.0) * 100.0 >= MIN_REBOUND_PCT
            return LiveRead(
                UState.AFTER_CLOSE,
                if (wasU) {
                    String.format(
                        Locale.US,
                        "Today printed the U: dipped %.1f%% then closed %+.1f%% off the low.",
                        dipPct, (price / sessionLow - 1.0) * 100.0
                    )
                } else {
                    "Session over — today did not complete the U shape."
                },
                round1(dipPct), round2(vwap), null, null, null, null
            )
        }

        val confirmed = lastMin >= RULE_START_MIN &&
            dipPct >= MIN_DIP_PCT &&
            price > vwap &&
            last.low > sessionLow

        return when {
            dipPct < MIN_DIP_PCT && lastMin < LOW_BEFORE_MIN -> LiveRead(
                UState.NO_DIP_YET,
                String.format(
                    Locale.US,
                    "No meaningful dip yet (%.1f%% vs the %.1f%% it usually prints). " +
                        "Its dip window is %s — the setup needs the dip first.",
                    dipPct, medianDipPct, buyWindow
                ),
                round1(dipPct), round2(vwap), null, null, null, null
            )
            dipPct < MIN_DIP_PCT -> LiveRead(
                UState.NO_TURN,
                "No meaningful dip printed today — there is no U to buy.",
                round1(dipPct), round2(vwap), null, null, null, null
            )
            confirmed && reboundUsed < CHASE_LIMIT -> {
                val stop = sessionLow * (1.0 - STOP_PAD)
                val target = sessionLow * (1.0 + medianReboundPct / 100.0)
                val rr = if (price > stop) (target - price) / (price - stop) else 0.0
                LiveRead(
                    UState.BUY_ZONE,
                    String.format(
                        Locale.US,
                        "Turn confirmed: dipped %.1f%%, now back above VWAP $%.2f on a higher " +
                            "low. Entry here, stop under the session low, target from the " +
                            "measured median rebound.",
                        dipPct, vwap
                    ),
                    round1(dipPct), round2(vwap),
                    round2(price), round2(stop), round2(target), round1(rr)
                )
            }
            confirmed -> LiveRead(
                UState.TOO_LATE,
                String.format(
                    Locale.US,
                    "The turn already ran: %.0f%% of the measured rebound is spent. " +
                        "Chasing here buys the top of the U.",
                    (reboundUsed * 100.0).coerceAtMost(400.0)
                ),
                round1(dipPct), round2(vwap), null, null, null, null
            )
            lastMin >= RULE_LAST_ENTRY_MIN -> LiveRead(
                UState.NO_TURN,
                String.format(
                    Locale.US,
                    "Dipped %.1f%% but never reclaimed VWAP ($%.2f) — the U did not " +
                        "complete today. No entry this late.",
                    dipPct, vwap
                ),
                round1(dipPct), round2(vwap), null, null, null, null
            )
            else -> LiveRead(
                UState.IN_DIP,
                String.format(
                    Locale.US,
                    "In the dip: %.1f%% below the open, price $%.2f under VWAP $%.2f. " +
                        "Wait — the buy signal is a bar closing back above VWAP without " +
                        "a new session low.",
                    dipPct, price, vwap
                ),
                round1(dipPct), round2(vwap), null, null, null, null
            )
        }
    }

    // ----------------------------------------------------------- pick builder

    private suspend fun buildPick(
        dateIso: String,
        fp: Fingerprint,
        quote: ScreenerQuote?,
        session: Dates.MarketSession
    ): UPick? {
        return try {
            // The 20-technique daily board: a bearish read disqualifies.
            var techBullish = 0
            var techTotal = 0
            try {
                val daily = market.getDailyCandles(fp.symbol, 180)
                if (daily.size >= 30) {
                    val analysis = Techniques.analyze(fp.symbol, daily)
                    if (analysis?.outlook?.direction == TechniqueVerdict.BEARISH) return null
                    techBullish = analysis?.outlook?.bullishCount ?: 0
                    techTotal = analysis?.results?.size ?: 0
                }
            } catch (_: Exception) {
                // The board is confirmation; its absence is not disqualification.
            }

            val buyWindow = windowAround(fp.lowMedianMin)
            val sellWindow = windowAround(fp.highMedianMin)
            val bars = try {
                market.getIntraday(fp.symbol, maxAgeMs = 120_000L)
            } catch (_: Exception) {
                emptyList()
            }
            val live = liveRead(bars, session, fp.medianDipPct, fp.medianReboundPct, buyWindow)

            val reason = String.format(
                Locale.US,
                "U on %d of %d sessions (%.0f%%) · median dip −%.1f%%, rebound +%.1f%% · " +
                    "low usually %s · the VWAP-reclaim rule paid %d of %d (%.0f%%), avg %+.2f%%",
                fp.uDays, fp.sessions, fp.uRate * 100.0,
                fp.medianDipPct, fp.medianReboundPct, buyWindow,
                fp.btWins, fp.btFired, fp.btWinRate * 100.0, fp.btMeanRetPct
            )

            UPick(
                date = dateIso,
                rank = 0,
                symbol = fp.symbol,
                name = quote?.name ?: "",
                price = quote?.price ?: 0.0,
                dayChangePct = quote?.dayChangePct ?: 0.0,
                score = fp.score(),
                sessions = fp.sessions,
                uDays = fp.uDays,
                medianDipPct = fp.medianDipPct,
                medianReboundPct = fp.medianReboundPct,
                convexDays = fp.convexDays,
                buyWindow = buyWindow,
                sellWindow = sellWindow,
                btFired = fp.btFired,
                btWins = fp.btWins,
                btMedianRetPct = fp.btMedianRetPct,
                btMeanRetPct = fp.btMeanRetPct,
                techBullish = techBullish,
                techTotal = techTotal,
                state = live.state.name,
                stateNote = live.note,
                dipTodayPct = live.dipPct,
                vwap = live.vwap,
                entry = live.entry,
                stop = live.stop,
                target = live.target,
                rewardRisk = live.rewardRisk,
                reason = reason,
                asOf = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Bars grouped by ET day, regular session (09:30–16:00) only. */
    private fun regularSessionDays(
        bars: List<Candle>,
        excludeToday: Boolean
    ): List<List<Candle>> {
        val now = System.currentTimeMillis()
        val byDay = LinkedHashMap<String, MutableList<Candle>>()
        for (c in bars) {
            if (excludeToday && Dates.sameEtDay(c.ts, now)) continue
            val et = Instant.ofEpochMilli(c.ts).atZone(ET)
            val minutes = et.hour * 60 + et.minute
            if (minutes < 9 * 60 + 30 || minutes >= 16 * 60) continue
            byDay.getOrPut(et.toLocalDate().toString()) { ArrayList() }.add(c)
        }
        return byDay.values.filter { it.size >= 50 }
    }

    private class QuadFit(val a: Double, val r2: Double)

    /**
     * OLS parabola p(t) = a·t² + b·t + c over the day's closes, normalized to
     * t ∈ [0,1] and p = (close − open)/open. a > 0 is the U's curvature.
     */
    private fun quadFit(closes: List<Double>, open: Double): QuadFit? {
        val n = closes.size
        if (n < 10 || open <= 0.0) return null
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var sy = 0.0; var sty = 0.0; var st2y = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            val y = (closes[i] - open) / open
            val t2 = t * t
            s0 += 1.0; s1 += t; s2 += t2; s3 += t2 * t; s4 += t2 * t2
            sy += y; sty += t * y; st2y += t2 * y
        }
        // Solve the 3x3 normal equations by Cramer's rule.
        val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
        if (abs(det) < 1e-12) return null
        val c = (sy * (s2 * s4 - s3 * s3) - s1 * (sty * s4 - st2y * s3) +
            s2 * (sty * s3 - st2y * s2)) / det
        val b = (s0 * (sty * s4 - st2y * s3) - sy * (s1 * s4 - s3 * s2) +
            s2 * (s1 * st2y - s2 * sty)) / det
        val a = (s0 * (s2 * st2y - s3 * sty) - s1 * (s1 * st2y - s2 * sty) +
            sy * (s1 * s3 - s2 * s2)) / det
        // R² against the mean.
        val mean = sy / n
        var sse = 0.0
        var sst = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            val y = (closes[i] - open) / open
            val fit = a * t * t + b * t + c
            sse += (y - fit) * (y - fit)
            sst += (y - mean) * (y - mean)
        }
        val r2 = if (sst > 0.0) 1.0 - sse / sst else 0.0
        return QuadFit(a, r2)
    }

    private fun minuteOfDayEt(ts: Long): Int {
        val et = Instant.ofEpochMilli(ts).atZone(ET)
        return et.hour * 60 + et.minute
    }

    /** ±20-minute window around an ET minute-of-day, shown as Amman clock. */
    private fun windowAround(minute: Int): String {
        val start = (minute - 20).coerceAtLeast(9 * 60 + 30)
        val end = (minute + 20).coerceAtMost(16 * 60)
        return "${ammanClock(start)}–${ammanClock(end)}"
    }

    private fun ammanClock(minutesEt: Int): String {
        val et = java.time.ZonedDateTime.now(ET)
            .withHour(minutesEt / 60).withMinute(minutesEt % 60)
            .withSecond(0).withNano(0)
        return et.withZoneSameInstant(AMMAN)
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }

    private fun medianD(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun medianI(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}

private fun Double.roundToIntSafe(): Int = round(this).toInt().coerceIn(0, 100)
